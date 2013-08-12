/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <sys/ioctl.h>
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <unistd.h>

#include <jni.h>
#include <android/log.h>

#include <linux/usbdevice_fs.h>
#include <asm/byteorder.h>

#include "usbconverter.h"

#define TAG "nativeUsbConverter"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

#define MIN(a, b) ((a)<(b)?(a):(b))

#define MAX_BUF_SIZE 8192
#define READ_TIMEOUT_MS 1100

#define EXCEPTION_ILLEGAL_STATE "java/lang/IllegalStateException"
#define EXCEPTION_NULL_POINTER  "java/lang/NullPointerException"


static jfieldID m_object_field;

static jmethodID method_report_location;

struct native_ctx_t {
  void *pointer;
};

struct usb_read_stream_t {
  int fd;
  int endpoint;
  int max_pkt_size;

  int rxbuf_pos;
  uint8_t rx_buf[MAX_BUF_SIZE];
};

struct usb_reader_ctx_t {
  struct nmea_parser_t nmea;
  struct sirf_parser_t sirf;
  struct usb_read_stream_t stream;
};

struct gps_msg_metadata {
  enum {
    MSG_TYPE_NMEA = 0,
    MSG_TYPE_SIRF = 1,
    MSG_TYPE_UBLOX = 2
  } type;
  size_t size;
  bool is_truncated;
};

static void read_loop(JNIEnv *env, jobject this, struct usb_reader_ctx_t *stream);
static void handle_rcvd(JNIEnv *env, jobject this, struct usb_reader_ctx_t *reader);
static void handle_timedout(JNIEnv *env, jobject this, struct usb_reader_ctx_t *reader);
static int find_msg(uint8_t *buf, int start_pos, int buf_size, struct gps_msg_metadata *res);
static bool handle_msg(JNIEnv *env, jobject this, struct usb_reader_ctx_t *reader, uint8_t *msg, struct gps_msg_metadata *metadata);
static void report_location(JNIEnv *env, jobject this, struct location_t *location);

static inline void throw_exception(JNIEnv *env, const char *clazzName, const char *message);

static void native_create(JNIEnv* env, jobject thiz)
{
  struct native_ctx_t *nctx;

  LOGV("native_create()");

  nctx = (struct native_ctx_t *)calloc(1, sizeof(struct native_ctx_t));

  if (nctx == NULL) {
    LOGV("calloc() error");
    return;
  }

  (*env)->SetLongField(env, thiz, m_object_field, (long)nctx);
}

static void native_destroy(JNIEnv *env, jobject thiz)
{
  struct native_ctx_t *nctx;

  LOGV("native_destroy()");

  nctx = (struct native_ctx_t *)(uintptr_t)(*env)->GetLongField(env, thiz, m_object_field);
  if (nctx == NULL) {
    LOGV("nctx is null");
    return;
  }

  free(nctx);
  (*env)->SetLongField(env, thiz, m_object_field, 0L);
}

static void native_read_loop(JNIEnv *env, jobject this,
    jobject j_input_stream, jobject j_output_stream)
{
  static jmethodID method_get_istream_fd;
  static jmethodID method_get_ostream_fd;
  static jmethodID method_get_istream_max_pkt_size;
  static jmethodID method_get_istream_ep_addr;
  struct usb_reader_ctx_t reader;

  if (j_input_stream == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "inputStream is null");

  if (j_output_stream == NULL)
    return throw_exception(env, EXCEPTION_NULL_POINTER, "outputStream is null");

  if (method_get_istream_fd == NULL) {
    jclass class_usb_input_stream;

    class_usb_input_stream = (*env)->GetObjectClass(env, j_input_stream);

    method_get_istream_fd = (*env)->GetMethodID(env,
        class_usb_input_stream, "getFileDescriptor", "()I");
    if (method_get_istream_fd == NULL)
      return;

    method_get_istream_max_pkt_size = (*env)->GetMethodID(env,
        class_usb_input_stream, "getMaxPacketSize", "()I");
    if (method_get_istream_max_pkt_size == NULL)
      return;

    method_get_istream_ep_addr = (*env)->GetMethodID(env,
        class_usb_input_stream, "getEndpointAddress", "()I");
    if (method_get_istream_ep_addr == NULL)
      return;

    method_get_ostream_fd = (*env)->GetMethodID(env,
        (*env)->GetObjectClass(env, j_output_stream),
        "getFileDescriptor", "()I"
        );
    if (method_get_ostream_fd == NULL)
      return;
  }

  reset_nmea_parser(&reader.nmea);
  reset_sirf_parser(&reader.sirf);

  reader.stream.fd = (*env)->CallIntMethod(env, j_input_stream, method_get_istream_fd);
  if (reader.stream.fd < 0)
    return;

  reader.stream.max_pkt_size = (*env)->CallIntMethod(env, j_input_stream, method_get_istream_max_pkt_size);
  if (reader.stream.max_pkt_size <= 0)
    return;

  reader.stream.endpoint = (*env)->CallIntMethod(env, j_input_stream, method_get_istream_ep_addr);

  LOGV("istream_fd: %i, endpoint: 0x%x, max_pkt_size: %i", reader.stream.fd,
      reader.stream.endpoint, reader.stream.max_pkt_size);


  read_loop(env, this, &reader);
}

static void read_loop(JNIEnv *env, jobject this, struct usb_reader_ctx_t *reader)
{
  int rcvd;
  struct usb_read_stream_t *stream;

  stream = &reader->stream;

  stream->rxbuf_pos = 0;
  for (;;) {
    struct usbdevfs_bulktransfer ctrl;

    memset(&ctrl, 0, sizeof(ctrl));
    ctrl.ep = stream->endpoint;
    ctrl.len = MIN(stream->max_pkt_size, (int)sizeof(stream->rx_buf)-stream->rxbuf_pos);
    ctrl.data = &stream->rx_buf[stream->rxbuf_pos];
    ctrl.timeout = READ_TIMEOUT_MS;

    rcvd = ioctl(stream->fd, USBDEVFS_BULK, &ctrl);
    if (rcvd < 0) {
      if (errno == ETIMEDOUT) {
        // XXX: timeout
        LOGV("usb read timeout");
        handle_timedout(env, this, reader);
        continue;
      }else {
        LOGV("read_loop(): rcvd %i, error: %s", rcvd, strerror(errno));
        break;
      }
    }else if (rcvd == 0) {
      // XXX: EOF
      continue;
    }else {
      stream->rxbuf_pos += rcvd;
      handle_rcvd(env, this, reader);
    }
  }
}

static void handle_timedout(JNIEnv *env, jobject this, struct usb_reader_ctx_t *reader)
{
  struct gps_msg_status_t status;
  put_nmea_timedout(&reader->nmea, &status);
  if (status.location_changed)
    report_location(env, this, &reader->nmea.location);
}

static void handle_rcvd(JNIEnv *env, jobject this, struct usb_reader_ctx_t *reader) {
  int pred_msg_pos, msg_pos;
  int pred_msg_len;
  struct gps_msg_metadata msg;
  struct usb_read_stream_t *stream;

  stream = &reader->stream;

  if (stream->rxbuf_pos == 0)
    return;

  pred_msg_pos = 0;
  pred_msg_len = 0;
  msg_pos = find_msg(stream->rx_buf, 0, stream->rxbuf_pos, &msg);
  for (;;) {

    // No nessages found in buffer
    if (msg_pos < 0) {
      LOGV("junk %u", stream->rxbuf_pos);
      stream->rxbuf_pos = 0;
      break;
    }
    // Junk between messages
    if (pred_msg_pos + pred_msg_len != msg_pos) {
      LOGV("inter msg junk %u", msg_pos - pred_msg_pos - pred_msg_len);
    }

    if (!msg.is_truncated) {
      handle_msg(env, this, reader, &stream->rx_buf[msg_pos], &msg);
      pred_msg_pos = msg_pos;
      pred_msg_len = msg.size;
    }else {
      // Truncated message
      if (msg_pos == 0) {
        if (stream->rxbuf_pos == sizeof(stream->rx_buf)) {
          pred_msg_pos = msg_pos+1;
          pred_msg_len = 0;
          // FALLTHROUGH
        }else {
          break;
        }
      }else {
        memmove(&stream->rx_buf[0], &stream->rx_buf[msg_pos], msg.size);
        stream->rxbuf_pos = msg.size;
        break;
      }
    }

    if (pred_msg_pos+pred_msg_len == stream->rxbuf_pos) {
      stream->rxbuf_pos = 0;
      break;
    }

    assert(pred_msg_pos+pred_msg_len < stream->rxbuf_pos);
    msg_pos = find_msg(stream->rx_buf, pred_msg_pos+pred_msg_len, stream->rxbuf_pos, &msg);
  }
}

static int find_msg(uint8_t *buf, int start_pos, int buf_size, struct gps_msg_metadata *res)
{
  int msg_pos;
  int msg_size;
  int msg_type;

  msg_pos = start_pos;
  msg_type = -1;
  while (msg_pos < buf_size) {

    /* Check for NMEA message */
    msg_size = looks_like_nmea(&buf[msg_pos], buf_size - msg_pos);
    if (msg_size != LOOKS_NOT_LIKE_GPS_MSG) {
      msg_type = MSG_TYPE_NMEA;
      break;
    }

    /* Check for SiRF message */
    msg_size = looks_like_sirf(&buf[msg_pos], buf_size - msg_pos);
    if (msg_size != LOOKS_NOT_LIKE_GPS_MSG) {
      msg_type = MSG_TYPE_SIRF;
      break;
    }

    /* Check for u-blox message */
    msg_size = looks_like_ublox(&buf[msg_pos], buf_size - msg_pos);
    if (msg_size != LOOKS_NOT_LIKE_GPS_MSG) {
      msg_type = MSG_TYPE_UBLOX;
      break;
    }

    msg_pos += 1;
  }

  if (msg_type >= 0) {
    res->type = msg_type;
    if (msg_size == LOOKS_LIKE_TRUNCATED_MSG) {
      res->size = buf_size - msg_pos;
      res->is_truncated = true;
    }else {
      res->size = msg_size;
      res->is_truncated = false;
    }
    return msg_pos;
  }

  return -1;
}

static bool handle_msg(JNIEnv *env,
    jobject this,
    struct usb_reader_ctx_t *reader,
    uint8_t *msg,
    struct gps_msg_metadata *metadata) {

  bool is_valid;
  struct gps_msg_status_t status;

  assert(env);
  assert(this);
  assert(msg);
  assert(metadata);

  switch (metadata->type) {
    case MSG_TYPE_NMEA:
      put_nmea_msg(&reader->nmea, msg, metadata->size, &status);
      is_valid = status.is_valid;
      if (status.err[0] != '\0') {
        if (status.is_valid)
          LOGV("WARN: %s", status.err);
        else
          LOGV("%s", status.err);
      }
      if (status.location_changed)
        report_location(env, this, &reader->nmea.location);
      break;
    case MSG_TYPE_SIRF:
      {
        assert(metadata->size > 8);
        assert(msg[0] == 0xa0);
        put_sirf_msg(&reader->sirf, msg,  metadata->size, &status);
        is_valid = status.is_valid;
        if (status.err[0] != '\0') {
          if (status.is_valid)
            LOGV("WARN: %s", status.err);
          else
            LOGV("%s", status.err);
        }
        if (status.location_changed)
          report_location(env, this, &reader->sirf.location);
      }
      break;
    case MSG_TYPE_UBLOX:
      {
        assert(metadata->size > 8);
        assert(msg[0] == 0xb5);
        LOGV("U-BLOX: 0x%02hhx:%02hhx", msg[2], msg[3]);
        is_valid = true;
      }
      break;
    default:
      is_valid = false;
      break;
  }
  return is_valid;
}

static void report_location(JNIEnv *env, jobject this, struct location_t *location)
{
  (*env)->CallVoidMethod(env, this, method_report_location,
      (jlong)location->time,
      (jdouble)location->latitude,
      (jdouble)location->longitude,
      (jdouble)location->altitude,
      (jfloat)location->accuracy,
      (jfloat)location->bearing,
      (jfloat)location->speed,
      (jint)location->satellites,
      (jboolean)location->is_valid,
      (jboolean)location->has_accuracy,
      (jboolean)location->has_altitude,
      (jboolean)location->has_bearing,
      (jboolean)location->has_speed
      );
}

static inline void throw_exception(JNIEnv *env, const char *clazzName, const char *message) {
  (*env)->ThrowNew(env,
      (*env)->FindClass(env, clazzName),
      message);
}

static JNINativeMethod native_methods[] = {
  {"native_create", "()V", (void*)native_create},
  {"native_destroy", "()V", (void*)native_destroy},
  {"native_read_loop", "("
    "Lorg/broeuschmeul/android/gps/usb/UsbSerialController$UsbSerialInputStream;"
      "Lorg/broeuschmeul/android/gps/usb/UsbSerialController$UsbSerialOutputStream;"
      ")V", (void*)native_read_loop}
};

int register_usb_converter_natives(JNIEnv* env) {
  /* look up the class */
  jclass clazz = (*env)->FindClass(env, "org/broeuschmeul/android/gps/usb/provider/UsbGpsConverter");

  if (clazz == NULL)
    return JNI_FALSE;

  if ((*env)->RegisterNatives(env, clazz, native_methods, sizeof(native_methods)
        / sizeof(native_methods[0])) != JNI_OK)
    return JNI_FALSE;

  m_object_field = (*env)->GetFieldID(env, clazz, "mObject", "J");
  if (m_object_field == NULL)
    return JNI_FALSE;

  method_report_location = (*env)->GetMethodID(env,
      clazz, "reportLocation", "(JDDDFFFIZZZZZ)V");
  if (method_report_location == NULL)
    return JNI_FALSE;

  return JNI_TRUE;
}
