/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <assert.h>
#include <errno.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"

#define TAG "nativeDataLogger"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_VERBOSE,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif
#define LOGI(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)


#define MAX_RETRIES 3


static bool logfile_open_unlocked(struct datalogger_t *logger);
static void logfile_close_unlocked(struct datalogger_t *logger);
static void logfile_write_unlocked(struct datalogger_t * __restrict logger, const uint8_t * __restrict data, size_t size);
static void datalogger_stop_unlocked(struct datalogger_t *logger);

void datalogger_init(struct datalogger_t *datalogger)
{
  pthread_mutex_init(&datalogger->mtx, NULL);
  datalogger->enabled = true;
  datalogger->format = DATALOGGER_FORMAT_RAW;
  datalogger->logs_dir[0] = '\0';
  datalogger->log_prefix[0] = '\0';
  datalogger->cur_file_name[0] = '\0';
  datalogger->cur_file = NULL;
}

void datalogger_destroy(struct datalogger_t *logger)
{
  logfile_close_unlocked(logger);
  pthread_mutex_destroy(&logger->mtx);
}

bool datalogger_configure(struct datalogger_t * __restrict logger,
    bool enabled,
    int format,
    const char * __restrict tracks_dir,
    const char * __restrict file_prefix)
{
  if ((format != DATALOGGER_FORMAT_RAW)
      && (format != DATALOGGER_FORMAT_NMEA))
    return false;

  pthread_mutex_lock(&logger->mtx);
  logger->enabled = enabled;
  logger->format = format;
  strncpy(logger->logs_dir, tracks_dir, sizeof(logger->logs_dir)-1);
  logger->logs_dir[sizeof(logger->logs_dir)-1]='\0';

  strncpy(logger->log_prefix, file_prefix, sizeof(logger->log_prefix)-1);
  logger->log_prefix[sizeof(logger->log_prefix)-1]='\0';

  datalogger_stop_unlocked(logger);

  LOGV("datalogger_configure() enabled: %c, format: %s, logs_dir: %s, log_prefix: %s",
      (logger->enabled ? 'Y' : 'N'),
      (logger->format == DATALOGGER_FORMAT_NMEA ? "nmea" : "raw"),
      logger->logs_dir,
      logger->log_prefix
      );

  pthread_mutex_unlock(&logger->mtx);

  return true;
}

void datalogger_log_raw_data(struct datalogger_t * __restrict logger, const uint8_t * __restrict buf, size_t size)
{
  pthread_mutex_lock(&logger->mtx);
  if (logger->enabled && (logger->format == DATALOGGER_FORMAT_RAW)) {
    logfile_write_unlocked(logger, buf, size);
  }
  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_log_msg(struct datalogger_t * __restrict logger,
    const uint8_t * __restrict msg,
    const struct gps_msg_metadata_t * __restrict metadata)
{
  pthread_mutex_lock(&logger->mtx);
  if (logger->enabled
      && (logger->format == DATALOGGER_FORMAT_NMEA)
      && (metadata->type == MSG_TYPE_NMEA)) {
    logfile_write_unlocked(logger, msg, metadata->size);
  }
  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_start(struct datalogger_t *logger)
{
  const char *ext;
  time_t tt;
  char timestamp[80];

  pthread_mutex_lock(&logger->mtx);

  if (logger->cur_file_name[0] != '\0') {
    datalogger_stop_unlocked(logger);
  }

  if (!logger->enabled) {
    pthread_mutex_unlock(&logger->mtx);
    return;
  }

  if (logger->format == DATALOGGER_FORMAT_NMEA)
    ext = "nmea";
  else
    ext = "raw";

  tt = time(NULL);
  if (strftime(timestamp, sizeof(timestamp), "%Y%b%d_%H-%M", localtime(&tt)) == 0) {
    snprintf(timestamp, sizeof(timestamp), "%ld", tt);
  }

  snprintf(logger->cur_file_name, sizeof(logger->cur_file_name),
      "%s/%s_%s.%s", logger->logs_dir, logger->log_prefix, timestamp, ext);

  LOGV("datalogger_start() file: %s", logger->cur_file_name);

  pthread_mutex_unlock(&logger->mtx);
}

void datalogger_stop(struct datalogger_t *logger)
{
  LOGV("datalogger_stop()");
  pthread_mutex_lock(&logger->mtx);
  datalogger_stop_unlocked(logger);
  pthread_mutex_unlock(&logger->mtx);
}

static void datalogger_stop_unlocked(struct datalogger_t *logger)
{
  logfile_close_unlocked(logger);
  logger->cur_file_name[0] = '\0';
}

static void logfile_close_unlocked(struct datalogger_t *logger)
{
  if (logger->cur_file == NULL)
    return;

  fclose(logger->cur_file);
  logger->cur_file = NULL;
  return;
}

static bool logfile_open_unlocked(struct datalogger_t *logger)
{
  LOGV("logfile_open_unlocked() file: %s", logger->cur_file_name);

  if (logger->cur_file_name[0] == '\0')
    return false;

  logger->cur_file = fopen(logger->cur_file_name, "a");
  if (logger->cur_file == NULL) {
    // XXX
    LOGI("fopen() error %s", strerror(errno));
    return false;
  }

  setbuffer(logger->cur_file, logger->buffer, sizeof(logger->buffer));
  return true;
}

static void logfile_write_unlocked(struct datalogger_t * __restrict logger, const uint8_t * __restrict data, size_t size)
{
  int retry_cnt;
  int last_errno;
  size_t written;

  /* LOGV("logfile_write_unlocked size: %u, file: %s", size, logger->cur_file_name); */

  if (size == 0)
    return;

  if (logger->cur_file_name[0] == '\0')
    return;

  written = 0;
  for(retry_cnt=0; retry_cnt < MAX_RETRIES; ++retry_cnt) {
    if (logger->cur_file == NULL) {
      if (!logfile_open_unlocked(logger))
        return;
    }
    assert(logger->cur_file != NULL);
    written += fwrite(&data[written], 1, size-written, logger->cur_file);
    if (written >= size)
      return;

    last_errno = errno;
    LOGV("fwrite() error %s", strerror(last_errno));
    logfile_close_unlocked(logger);
  }

  LOGI("fwrite() error %s", strerror(last_errno));

}
