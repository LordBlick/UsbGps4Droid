/* vim: set tabstop=2 shiftwidth=2 expandtab: */

#include <assert.h>
#include <ctype.h>
#include <errno.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <stdarg.h>

#include <jni.h>
#include <android/log.h>

#include "usbconverter.h"

#define TAG "nativeNmea"
#ifdef ENABLE_LOG
#define LOGV(x...) __android_log_print(ANDROID_LOG_INFO,TAG,x)
#else
#define LOGV(...)  do {} while (0)
#endif

#define KNOTS_TO_MPS 0.514444
#define KMPH_TO_MPS (1000.0 / 3600.0)

typedef enum {
  FIELD_INVALID = -1,
  FIELD_UNDEFINED = 0,
  FIELD_VALID = 1
} parse_error_t;


static bool parse_gga(const uint8_t *msg, size_t msg_size, struct nmea_gpgga_t *dst,
    struct gps_msg_status_t *status);
static bool parse_rmc(const uint8_t *msg, size_t msg_size,
    struct nmea_gprmc_t *dst, struct gps_msg_status_t *status);
static bool parse_gll(const uint8_t *msg, size_t msg_size, struct nmea_gpgll_t *dst,
    struct gps_msg_status_t *status);
static bool parse_vtg(const uint8_t *msg, size_t msg_size,
    struct nmea_gpvtg_t *dst, struct gps_msg_status_t *status);
static bool parse_gsa(const uint8_t *msg, size_t msg_size,
    struct nmea_gpgsa_t *dst, struct gps_msg_status_t *status);
static bool parse_zda(const uint8_t *msg, size_t msg_size,
    struct nmea_gpzda_t *dst, struct gps_msg_status_t *status);
static bool parse_gst(const uint8_t *msg, size_t msg_size,
    struct nmea_gpgst_t *dst, struct gps_msg_status_t *status);

/*
static int put_gsv(struct nmea_parser_t *ctx, const uint8_t *msg, size_t msg_size);
static int put_gll(struct nmea_parser_t *ctx, const uint8_t *msg, size_t msg_size);
static int put_vtg(struct nmea_parser_t *ctx, const uint8_t *msg, size_t msg_size);
*/

static unsigned split_nmea_str(const uint8_t *msg, size_t msg_size,
    const char **fields, int fields_size, char *buf);
static parse_error_t parse_nmea_fix_time(const char *hhmmss_mss, struct nmea_fix_time_t *dst);
static parse_error_t parse_nmea_latitude(const char *deg_str, const char *ns, double *dst);
static parse_error_t parse_nmea_longitude(const char *deg_str, const char *ew, double *dst);
static parse_error_t parse_float(const char *str, float *dst);

static void open_nmea_fix(struct nmea_fix_t *fix, struct nmea_fix_time_t time);
static inline bool is_same_fix_time(struct nmea_fix_time_t t1, struct nmea_fix_time_t t2);
static bool close_nmea_fix(struct nmea_parser_t *ctx);
static void merge_full_time(struct nmea_parser_t *ctx);
static bool compose_location(const struct nmea_parser_t *ctx, struct location_t *dst);
static bool set_nmea_error(struct gps_msg_status_t *status,
    const uint8_t *nmea_msg, size_t nmea_msg_size, char *fmt, ...);

inline int looks_like_nmea(const uint8_t *msg, size_t max_len)
{
  unsigned i;
  int crlf_pos;
  int csum_pos;

  assert(max_len > 0);

  if (msg[0] != '$')
    return LOOKS_NOT_LIKE_GPS_MSG;

  crlf_pos = -1;
  csum_pos = -1;

  for (i=1; i < max_len; ++i) {

    if (i > NMEA_MAX)
        return LOOKS_NOT_LIKE_GPS_MSG;

    if (msg[i] == '*') {
      csum_pos = (int)i;
    }else if (msg[i] == 0x0d) {
      if (i + 1 == max_len) {
        return LOOKS_LIKE_TRUNCATED_MSG;
      }else if (msg[i+1] != 0x0a) {
        return LOOKS_NOT_LIKE_GPS_MSG;
      }else {
        crlf_pos = (int)i;
        break;
      }
    }else if (!isprint(msg[i])) {
      return LOOKS_NOT_LIKE_GPS_MSG;
    }
  }

  if (crlf_pos < 0)
    return LOOKS_LIKE_TRUNCATED_MSG;

  if (csum_pos > 0) {
    unsigned msg_csum;
    unsigned our_csum;
    unsigned char csum_str[3];
    char *endptr;

    // Optional checksum (*XX<CR><LF>)
    if (csum_pos + 3 != crlf_pos)
      return LOOKS_NOT_LIKE_GPS_MSG;
    csum_str[0] = msg[csum_pos+1];
    csum_str[1] = msg[csum_pos+2];
    csum_str[2] = '\0';

    msg_csum = (unsigned)strtol((const char *)csum_str, &endptr, 16);
    if (*endptr != '\0')
      return LOOKS_NOT_LIKE_GPS_MSG;

    our_csum = 0;
    for (i=1; i < (unsigned)csum_pos; ++i) our_csum ^= msg[i] & 0x7f;
    if (our_csum != msg_csum) {
      LOGV("NMEA Checksum mismatch. 0x%x != 0x%x", msg_csum, our_csum);
      return LOOKS_NOT_LIKE_GPS_MSG;
    }
  }

  return crlf_pos + 2;
}


bool put_nmea_msg(struct nmea_parser_t *ctx, const uint8_t *msg, size_t msg_size, struct gps_msg_status_t *status)
{
  unsigned i;
  char msg_id[9];

  assert(msg_size > 1 &&  msg_size <= NMEA_MAX);
  assert((size_t)looks_like_nmea(msg, msg_size) == msg_size);

  status->is_valid = false;
  status->location_changed = false;
  status->err[0] = '\0';

  for (i=0; i < sizeof(msg_id); ++i) {
    if (i >= msg_size) {
      msg_id[i] = '\0';
      break;
    }else {
      if (msg[i] == ',') {
        msg_id[i] = '\0';
        break;
      }else {
        msg_id[i] = (char)msg[i];
      }
    }
  }
  msg_id[sizeof(msg_id)-1] = '\0';

  if (
      (strcmp("$GPGGA", msg_id) == 0)
      || (strcmp("$GNGGA", msg_id) == 0)
      || (strcmp("$GLGGA", msg_id) == 0)
      || (strcmp("$GAGGA", msg_id) == 0)
      ) {
    struct nmea_gpgga_t gxgga;
    if (parse_gga(msg, msg_size, &gxgga, status)) {
      if (!is_same_fix_time(ctx->fix.fix_time, gxgga.fix_time)) {
        status->location_changed = close_nmea_fix(ctx);
        open_nmea_fix(&ctx->fix, gxgga.fix_time);
      }
      ctx->fix.gpgga_active = true;
      ctx->fix.gpgga = gxgga;
    }
  }else if (
      (strcmp("$GPRMC", msg_id) == 0)
      || (strcmp("$GNRMC", msg_id) == 0)
      || (strcmp("$GLRMC", msg_id) == 0)
      || (strcmp("$GARMC", msg_id) == 0)
      ) {
    struct nmea_gprmc_t gxrmc;
    if (parse_rmc(msg, msg_size, &gxrmc, status)) {
      if (!is_same_fix_time(ctx->fix.fix_time, gxrmc.fix_time)) {
        status->location_changed = close_nmea_fix(ctx);
        open_nmea_fix(&ctx->fix, gxrmc.fix_time);
      }
      ctx->fix.gprmc_active = true;
      ctx->fix.gprmc = gxrmc;
    }
  }else if (strcmp("$GPGLL", msg_id) == 0) {
    struct nmea_gpgll_t gxgll;
    if (parse_gll(msg, msg_size, &gxgll, status)) {
      if (!is_same_fix_time(ctx->fix.fix_time, gxgll.fix_time)) {
        status->location_changed = close_nmea_fix(ctx);
        open_nmea_fix(&ctx->fix, gxgll.fix_time);
      }
      ctx->fix.gpgll_active = true;
      ctx->fix.gpgll = gxgll;
    }
  }else if (strcmp("$GPGST", msg_id) == 0) {
    struct nmea_gpgst_t gpgst;
    if (parse_gst(msg, msg_size, &gpgst, status)) {
      if (!is_same_fix_time(ctx->fix.fix_time, gpgst.fix_time)) {
        status->location_changed = close_nmea_fix(ctx);
        open_nmea_fix(&ctx->fix, gpgst.fix_time);
      }
      ctx->fix.gpgst_active = true;
      ctx->fix.gpgst = gpgst;
    }
  }else if (strcmp("$GPGSA", msg_id) == 0) {
    struct nmea_gpgsa_t gpgsa;
    if (parse_gsa(msg, msg_size, &gpgsa, status)) {
      gpgsa.is_valid = true;
      ctx->gpgsa = gpgsa;
    }
  }else if (strcmp("$GPVTG", msg_id) == 0) {
    struct nmea_gpvtg_t gpvtg;
    if (parse_vtg(msg, msg_size, &gpvtg, status)) {
      gpvtg.is_valid = true;
      ctx->gpvtg = gpvtg;
    }
  }else if (strcmp("$GPZDA", msg_id) == 0) {
    struct nmea_gpzda_t gpzda;
    if (parse_zda(msg, msg_size, &gpzda, status)) {
      ctx->fix.gpzda_active = true;
      ctx->fix.gpzda = gpzda;
    }
  }else if (strcmp("$GPGSV", msg_id) == 0) {
    // TODO
    status->is_valid = true;
  }else if (strcmp("$PUBX", msg_id) == 0) {
    // TODO
    status->is_valid = true;
  } else {
    set_nmea_error(status, msg, msg_size, "unk msg");
    status->is_valid = true;
  }

  return status->is_valid;
}

void put_nmea_timedout(struct nmea_parser_t *ctx, struct gps_msg_status_t *status)
{
  status->is_valid = false;
  status->location_changed = close_nmea_fix(ctx);
  status->err[0] = '\0';
}


static bool parse_gga(const uint8_t *msg, size_t msg_size,
    struct nmea_gpgga_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  parse_error_t field_err;
  struct nmea_gpgga_t gpgga;
  const char *fields[15];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));

  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 15) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid field count %u", fields_nb);
  }

  /* Field 1. Time of fix */
  if (parse_nmea_fix_time(fields[1], &gpgga.fix_time) != FIELD_VALID)
    return set_nmea_error(status, msg, msg_size, "Invalid NMEA fix time");

  /* Fields 2,3 latitude */
  field_err = parse_nmea_latitude(fields[2], fields[3], &gpgga.latitude);
  if (field_err == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid latitude");
  }else if (field_err == FIELD_UNDEFINED) {
    set_nmea_error(status, msg, msg_size,
        "latitude not defined");
    status->is_valid = true;
    return status->is_valid;
  }else {
    assert(field_err == FIELD_VALID);
  }

  /* Fields 4,5 longitude */
  field_err = parse_nmea_longitude(fields[4], fields[5], &gpgga.longitude);
  if (field_err == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid longitude");
  }else if (field_err == FIELD_UNDEFINED) {
    set_nmea_error(status, msg, msg_size,
        "longitude not defined");
    status->is_valid = true;
    return status->is_valid;
  }else {
    assert(field_err == FIELD_VALID);
  }

  /* Field 6 fix quality */
  if (fields[6][0] == '\0')
    gpgga.fix_quality = 0;
  else {
    char *endptr;
    unsigned long fixq;
    errno = 0;
    fixq = strtoul(fields[6], &endptr, 10);
    if ((errno == ERANGE && fixq == ULONG_MAX)
        || (errno != 0 && fixq == 0)
        || *endptr != '\0') {
      return set_nmea_error(status, msg, msg_size,
          "Invalid fix quality");
    }
    gpgga.fix_quality = fixq;
  }

  /* Field 7. Number of satellites being tracked */
  if (fields[7][0] == '\0') {
    gpgga.sattelites_nb = -1;
  }else {
    unsigned long sat_nb;
    char *endptr;
    errno = 0;
    sat_nb = strtoul(fields[7], &endptr, 10);
    if ((errno == ERANGE && sat_nb == LONG_MAX)
        || (errno != 0 && sat_nb == 0)
        || *endptr != '\0') {
      return set_nmea_error(status, msg, msg_size,
          "Invalid number of satellites");
    }
    gpgga.sattelites_nb = (int)sat_nb;
  }

  /* Field 8. HDOP */
  if (parse_float(fields[8], &gpgga.hdop) == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size, "Invalid HDOP");
  }

  /* Field 9.  Altitude above mean sea level */
  if (fields[9][0] == '\0') {
    gpgga.altitude = NAN;
  }else {
    char *endptr;
    errno = 0;
    gpgga.altitude = strtod(fields[9], &endptr);
    if ((errno == ERANGE && (gpgga.altitude == HUGE_VAL || gpgga.altitude == -HUGE_VAL))
        || (errno != 0 && gpgga.altitude == 0)
        || (*endptr != '\0')
       ) {
      return set_nmea_error(status, msg, msg_size, "Invalid altitude");
    }
  }

  /* Field 11. Geoid height */
  if (fields[11][0] == '\0') {
    gpgga.geoid_height = FP_NAN;
  }else {
    char *endptr;
    errno = 0;
    gpgga.geoid_height = strtod(fields[11], &endptr);
    if ((errno == ERANGE && (gpgga.geoid_height == HUGE_VAL || gpgga.geoid_height == -HUGE_VAL))
        || (errno != 0 && gpgga.geoid_height == 0)
        || (*endptr != '\0')
       ) {
      return set_nmea_error(status, msg, msg_size, "Invalid geoid height");
    }
  }

  status->is_valid = true;
  status->err[0] = '\0';
  *dst = gpgga;
  return status->is_valid;
}

static bool parse_rmc(const uint8_t *msg, size_t msg_size,
    struct nmea_gprmc_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  parse_error_t field_err;
  struct nmea_gprmc_t gprmc;
  const char *fields[12];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));
  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 12)
    return set_nmea_error(status, msg, msg_size, "Invalid field count %u", fields_nb);

  /* Field 1. Time of fix */
  if (parse_nmea_fix_time(fields[1], &gprmc.fix_time) != FIELD_VALID)
    return set_nmea_error(status, msg, msg_size, "Invalid NMEA fix time");

  /* Field 2. Status */
  gprmc.status_active = fields[2][0] == 'A';

  /* Fields 3,4 latitude */
  field_err = parse_nmea_latitude(fields[3], fields[4], &gprmc.latitude);
  if (field_err == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid latitude");
  }else if (field_err == FIELD_UNDEFINED) {
    set_nmea_error(status, msg, msg_size,
        "latitude not defined");
    status->is_valid = true;
    return status->is_valid;
  }else {
    assert(field_err == FIELD_VALID);
  }

  /* Fields 5,6 longitude */
  field_err = parse_nmea_longitude(fields[5], fields[6], &gprmc.longitude);
  if (field_err == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid longitude");
  }else if (field_err == FIELD_UNDEFINED) {
    set_nmea_error(status, msg, msg_size,
        "longitude not defined");
    status->is_valid = true;
    return status->is_valid;
  }else {
    assert(field_err == FIELD_VALID);
  }

  /* Field 7. Speed over the ground  */
  if (parse_float(fields[7], &gprmc.speed) == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size, "Invalid speed");
  }else {
    gprmc.speed *= KNOTS_TO_MPS;
  }

  /* Field 8 course */
  if (parse_float(fields[8], &gprmc.course) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid course");

  /* Field 9. Date */
  if (fields[9][0] == '\0')
    gprmc.ddmmyy = 0;
  else {
    char *endptr;
    unsigned long ddmmyy;
    errno = 0;
    ddmmyy = strtoul(fields[9], &endptr, 10);
    if ((ddmmyy > 311299)
        || (errno != 0 && ddmmyy == 0)
        || *endptr != '\0') {
      return set_nmea_error(status, msg, msg_size, "Invalid date");
    }
    gprmc.ddmmyy = ddmmyy;
  }

  status->is_valid = true;
  status->err[0] = '\0';
  *dst = gprmc;
  return status->is_valid;
}

static bool parse_gll(const uint8_t *msg, size_t msg_size,
    struct nmea_gpgll_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  parse_error_t field_err;
  struct nmea_gpgll_t gpgll;
  const char *fields[6];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));

  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 5) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid field count %u", fields_nb);
  }

  /* Fields 1,2 latitude */
  field_err = parse_nmea_latitude(fields[1], fields[2], &gpgll.latitude);
  if (field_err == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid latitude");
  }else if (field_err == FIELD_UNDEFINED) {
    set_nmea_error(status, msg, msg_size,
        "latitude not defined");
    status->is_valid = true;
    return status->is_valid;
  }else {
    assert(field_err == FIELD_VALID);
  }

  /* Fields 3,4 longitude */
  field_err = parse_nmea_longitude(fields[3], fields[4], &gpgll.longitude);
  if (field_err == FIELD_INVALID) {
    return set_nmea_error(status, msg, msg_size,
        "Invalid longitude");
  }else if (field_err == FIELD_UNDEFINED) {
    set_nmea_error(status, msg, msg_size,
        "longitude not defined");
    status->is_valid = true;
    return status->is_valid;
  }else {
    assert(field_err == FIELD_VALID);
  }

  if (fields_nb < 6) {
    gpgll.status = true;
  }else {
    gpgll.status = fields[5][0] != 'N';
  }

  status->is_valid = true;
  status->err[0] = '\0';
  *dst = gpgll;
  return status->is_valid;
}

static bool parse_vtg(const uint8_t *msg, size_t msg_size,
    struct nmea_gpvtg_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  struct nmea_gpvtg_t gpvtg;
  const char *fields[10];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));
  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 9)
    return set_nmea_error(status, msg, msg_size, "Invalid field count %u", fields_nb);

  /* Field 1, 2. True course made good over ground */
  if (parse_float(fields[1], &gpvtg.course_true) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid true course");

  /* Field 3, 4. Magnetic course made good over ground */
  if (parse_float(fields[3], &gpvtg.course_magn) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid magnetic course");

  /* Field 5, 6. Speed, Knots*/
  if (parse_float(fields[5], &gpvtg.speed_knots) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid ground speed (knots)");

  /* Field 7, 8. Speed, kmph*/
  if (parse_float(fields[7], &gpvtg.speed_kmph) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid ground speed (kmph)");

  /* Field 9. Mode indicator */
  if (fields_nb < 10) {
    gpvtg.fix_mode = 0;
  }else {
    gpvtg.fix_mode = fields[9][0];
  }

  gpvtg.is_valid = true;

  *dst = gpvtg;
  status->is_valid = true;
  status->err[0] = '\0';
  return status->is_valid;
}

static bool parse_gsa(const uint8_t *msg, size_t msg_size,
    struct nmea_gpgsa_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  unsigned i;
  struct nmea_gpgsa_t gpgsa;
  const char *fields[18];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));
  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 18)
    return set_nmea_error(status, msg, msg_size, "Invalid field count %u", fields_nb);

  /* Field 1. Fix mode */
  gpgsa.fix_mode = (int)fields[1];

  /* Field 2. Fix type */
  if (fields[2][0] == '\0')
    gpgsa.fix_type = -1;
  else {
    char *endptr;
    unsigned long type;
    errno = 0;
    type = strtoul(fields[2], &endptr, 10);
    if ((errno == ERANGE && type == ULONG_MAX)
        || (errno != 0 && type == 0)
        || *endptr != '\0') {
      return set_nmea_error(status, msg, msg_size, "Invalid fix type");
    }
    gpgsa.fix_type = (int)type;
  }

  /* Field 3-14 PRN's of Satellite Vechicles */
  for (i=0; i<12; ++i) {
    if (fields[3+i][0] == '\0')
      gpgsa.prn[0] = 0;
    else {
      char *endptr;
      unsigned long prn;
      errno = 0;
      prn = strtoul(fields[3+i], &endptr, 10);
      if ((errno == ERANGE && prn == ULONG_MAX)
          || (errno != 0 && prn == 0)
          || *endptr != '\0') {
        return set_nmea_error(status, msg, msg_size, "Invalid PRN");
      }
      gpgsa.prn[i] = (unsigned)prn;
    }
  }

  /* Field 15. PDOP */
  if (parse_float(fields[15], &gpgsa.pdop) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid PDOP");

  /* Field 16. HDOP */
  if (parse_float(fields[16], &gpgsa.hdop) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid HDOP");

  /* Field 17. VDOP */
  if (parse_float(fields[17], &gpgsa.vdop) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid VDOP");

  *dst = gpgsa;
  status->is_valid = true;
  status->err[0] = '\0';
  return status->is_valid;
}

static bool parse_zda(const uint8_t *msg, size_t msg_size,
    struct nmea_gpzda_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  struct nmea_gpzda_t gpzda;
  const char *fields[8];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));
  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 5)
    return set_nmea_error(status, msg, msg_size, "Invalid field count %u", fields_nb);

  /* Field 1. UTC Time */
  if (parse_nmea_fix_time(fields[1], &gpzda.fix_time) != FIELD_VALID)
    return set_nmea_error(status, msg, msg_size, "Invalid NMEA fix time");

  /* Field 2 UTC day */
  if (fields[2][0] == '\0')
    gpzda.day = 0;
  else {
    char *endptr;
    unsigned long day;
    errno = 0;
    day = strtoul(fields[2], &endptr, 10);
    if ((*endptr != '\0')
        || (day < 1 || day > 31)
        ) {
      return set_nmea_error(status, msg, msg_size, "Invalid day");
    }
    gpzda.day = (unsigned)day;
  }

  /* Field 3 UTC month */
  if (fields[3][0] == '\0')
    gpzda.month = 0;
  else {
    char *endptr;
    unsigned long month;
    errno = 0;
    month = strtoul(fields[3], &endptr, 10);
    if ((*endptr != '\0')
        || ( month < 1 || month > 12)
        ) {
      return set_nmea_error(status, msg, msg_size, "Invalid month");
    }
    gpzda.month = (unsigned)month;
  }

  /* Field 4 UTC 4-digit year */
  if (fields[4][0] == '\0')
    gpzda.year = 0;
  else {
    char *endptr;
    unsigned long year;
    errno = 0;
    year = strtoul(fields[4], &endptr, 10);
    if ((*endptr != '\0')
        || (year < 1990 || year > 2089)
        ) {
      return set_nmea_error(status, msg, msg_size, "Invalid year");
    }
    gpzda.year = (unsigned)year;
  }

  /* Field 5. Local zone hours */
  if (fields_nb <= 5) {
    gpzda.zone_hours = 0;
  }else {
    if (fields[5][0] == '\0'){
      gpzda.zone_hours = 0;
    }else {
      char *endptr;
      long zone_hours;
      errno = 0;
      zone_hours = strtol(fields[5], &endptr, 10);
      if ((errno != 0 && zone_hours == 0)
          || *endptr != '\0'
          || (zone_hours < -13 || zone_hours > 13)
         ) {
        return set_nmea_error(status, msg, msg_size, "Invalid local zone hours");
      }
      gpzda.zone_hours = (int)zone_hours;
    }
  }

  /* Field 6. Local zone minutes */
  if (fields_nb <= 6) {
    gpzda.zone_minutes = 0;
  }else {
    if (fields[6][0] == '\0'){
      gpzda.zone_minutes = 0;
    }else {
      char *endptr;
      unsigned long min;
      errno = 0;

      min = strtoul(fields[6], &endptr, 10);
      if ((errno != 0 && min == 0)
          || (*endptr != '\0')
          || (min > 59)
          ) {
        return set_nmea_error(status, msg, msg_size, "Invalid local zone minutes");
      }
      gpzda.zone_minutes = min;
    }
  }

  *dst = gpzda;
  status->is_valid = true;
  status->err[0] = '\0';
  return status->is_valid;
}

static bool parse_gst(const uint8_t *msg, size_t msg_size,
    struct nmea_gpgst_t *dst, struct gps_msg_status_t *status)
{
  unsigned fields_nb;
  struct nmea_gpgst_t gpgst;
  const char *fields[9];
  char buf[NMEA_MAX];

  assert(msg_size <= sizeof(buf));
  fields_nb = split_nmea_str(msg, msg_size, fields, sizeof(fields)/sizeof(fields[0]), buf);

  if (fields_nb < 9)
    return set_nmea_error(status, msg, msg_size, "Invalid field count %u", fields_nb);

  /* Field 1. Time of fix */
  if (parse_nmea_fix_time(fields[1], &gpgst.fix_time) != FIELD_VALID)
    return set_nmea_error(status, msg, msg_size, "Invalid NMEA fix time");

  /* Field 2. RMS deviation */
  if (parse_float(fields[2], &gpgst.range_rms) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid RMS deviation");

  /* Field 3. Semi-major deviation */
  if (parse_float(fields[3], &gpgst.std_major) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid Semi-major deviation");

  /* Field 4. Semi-minor deviation */
  if (parse_float(fields[4], &gpgst.std_minor) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid Semi-minor deviation");

  /* Field 5. Semi-major orientation */
  if (parse_float(fields[5], &gpgst.orient) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid Semi-major orientation");

  /* Field 6. Latitude error deviation */
  if (parse_float(fields[6], &gpgst.std_lat) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid Latitude error");

  /* Field 7. Longitude error deviation */
  if (parse_float(fields[7], &gpgst.std_lon) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid Longitude error");

  /* Field 8. Altitude error deviation */
  if (parse_float(fields[8], &gpgst.std_alt) == FIELD_INVALID)
    return set_nmea_error(status, msg, msg_size, "Invalid altitude error");

  *dst = gpgst;

  status->is_valid = true;
  status->err[0] = '\0';
  return status->is_valid;
}

static unsigned split_nmea_str(const uint8_t *msg, size_t msg_size,
    const char **fields, int fields_size, char *buf)
{
  unsigned i;
  int fields_nb;

  buf[0] = msg[0];
  fields[0] = &buf[0];
  fields_nb = 1;
  for (i=1; i < msg_size; ++i) {
    if (msg[i] == ',') {
      buf[i] = '\0';
    }else {
      buf[i] = (char)msg[i];
    }

    if (msg[i-1] == ',') {
      if (fields_nb >= fields_size)
        break;
      else
        fields[fields_nb++] = &buf[i];
    }

    if ((msg[i] == '*') || (msg[i] == 0x0d) || (msg[i] == 0x0a)) {
      buf[i] = '\0';
      break;
    }

  }
  buf[msg_size-1] = '\0';

  return fields_nb;
}

void reset_nmea_parser(struct nmea_parser_t *ctx)
{
  time_t curtime;

  ctx->fix.fix_time.hhmmss = 0;
  ctx->fix.fix_time.mss = 0;
  ctx->fix.is_closed = true;
  ctx->fix.gpgga_active = false;
  ctx->fix.gpgll_active = false;
  ctx->fix.gprmc_active = false;
  ctx->fix.gpgst_active = false;
  ctx->fix.gpzda_active = false;

  time(&curtime);
  gmtime_r(&curtime, &ctx->time_full);

  ctx->gpgsa.is_valid = false;
  ctx->gpvtg.is_valid = false;
  ctx->location.is_valid = false;
}

static void open_nmea_fix(struct nmea_fix_t *fix, struct nmea_fix_time_t time)
{
  fix->fix_time = time;
  fix->is_closed = false;
  fix->gpgga_active = false;
  fix->gpgll_active = false;
  fix->gprmc_active = false;
  fix->gpgst_active = false;
  fix->gpzda_active = false;
}

static inline bool fix_ready_to_close(struct nmea_fix_t *fix)
{
  if (fix) {}
  /* XXX */
  /* return fix->gpgga_active && fix->gprmc_active && fix->gpgst_active; */
  return false;
}

static bool close_nmea_fix(struct nmea_parser_t *ctx)
{
  struct nmea_fix_t *fix;
  struct location_t location;

  fix = &ctx->fix;

  if (fix->is_closed)
    return false;

  if (!fix->gpgga_active && !fix->gprmc_active && !fix->gpgll_active) {
    LOGV("No GPGGA/GPGLL/GPRMC sentences received on NMEA fix time %06u.%03u",
        fix->fix_time.hhmmss, fix->fix_time.mss);
    fix->is_closed = true;
    if (ctx->location.is_valid) {
      ctx->location.is_valid = false;
      return true;
    }else
      return false;
  }

  merge_full_time(ctx);
  compose_location(ctx, &location);

  if (!location.is_valid) {
    if (ctx->location.is_valid) {
      ctx->location = location;
      return true;
    }else
      return false;
  }

  assert(location.is_valid);
  if (ctx->location.is_valid && (location.time == ctx->location.time)) {
    return false;
  }else {
    ctx->location = location;
    return true;
  }
}

static void merge_full_time(struct nmea_parser_t *ctx)
{
  unsigned gpzda_year;

  if (ctx->fix.gpzda_active
      && (ctx->fix.gpzda.zone_hours == 0)
      && (ctx->fix.gpzda.zone_minutes == 0)) {
    gpzda_year = ctx->fix.gpzda.year;
    ctx->time_full.tm_year = gpzda_year - 1900;
  }else {
    gpzda_year = 0;
  }

  if (ctx->fix.gprmc_active && (ctx->fix.gprmc.ddmmyy != 0)) {
    if (gpzda_year == 0) {
      int gprmc_year;
      gprmc_year = ctx->time_full.tm_year + 1900;
      gprmc_year = gprmc_year - gprmc_year % 100 + ctx->fix.gprmc.ddmmyy % 100;
      ctx->time_full.tm_year = gprmc_year - 1900;
    }
    ctx->time_full.tm_mon = ((ctx->fix.gprmc.ddmmyy / 100) % 100) - 1;
    ctx->time_full.tm_mday = (ctx->fix.gprmc.ddmmyy / 10000) % 100;
  }
  ctx->time_full.tm_hour = (ctx->fix.fix_time.hhmmss / 10000) % 100;
  ctx->time_full.tm_min = (ctx->fix.fix_time.hhmmss / 100) % 100;
  ctx->time_full.tm_sec = ctx->fix.fix_time.hhmmss % 100;
}

static bool compose_location(const struct nmea_parser_t *ctx, struct location_t *dst)
{
  const struct nmea_fix_t *fix;
  bool is_valid;
  struct tm time_full;

  fix = &ctx->fix;
  is_valid = fix->gpgga_active || fix->gprmc_active;
  if (fix->gpgga_active)
    is_valid &= fix->gpgga.fix_quality != 0;
  if (fix->gprmc_active)
    is_valid &= fix->gprmc.status_active;

  if (!is_valid) {
    dst->is_valid = false;
    return dst->is_valid;
  }

  dst->is_valid = true;
  time_full = ctx->time_full;
  dst->time = 1000ll * (long long)timegm64(&time_full) + ctx->fix.fix_time.mss;

  // Latitude, longitude
  if (fix->gpgga_active) {
    dst->latitude = fix->gpgga.latitude;
    dst->longitude = fix->gpgga.longitude;
  }else if(fix->gprmc_active) {
    dst->latitude = fix->gprmc.latitude;
    dst->longitude = fix->gprmc.longitude;
  }else {
    assert(fix->gpgll_active);
    dst->latitude = fix->gpgll.latitude;
    dst->longitude = fix->gpgll.longitude;
  }

  // Altitude
  if (fix->gpgga_active && !isnanf(fix->gpgga.altitude)) {
    dst->has_altitude = true;
    dst->altitude = fix->gpgga.altitude;
  }else {
    dst->has_altitude = false;
    dst->altitude = 0;
  }

  // Satellites
  if (fix->gpgga_active && fix->gpgga.sattelites_nb > 0) {
    dst->satellites = fix->gpgga.sattelites_nb;
  }else if (ctx->gpgsa.is_valid) {
    unsigned i;
    dst->satellites = 0;
    for (i=0; i<sizeof(ctx->gpgsa.prn)/sizeof(ctx->gpgsa.prn[0]); ++i) {
      if (ctx->gpgsa.prn[i] > 0)
        dst->satellites += 1;
    }
  }else {
    dst->satellites = -1;
  }

  // Bearing
  if (fix->gprmc_active && !isnanf(fix->gprmc.course)) {
    dst->has_bearing = true;
    dst->bearing = fix->gprmc.course;
  }else if (ctx->gpvtg.is_valid
      && (ctx->gpvtg.fix_mode != 'N')
      && !isnanf(ctx->gpvtg.course_true)) {
    dst->has_bearing = true;
    dst->bearing = ctx->gpvtg.course_true;
  }else {
    dst->has_bearing = false;
    dst->bearing = 0;
  }

  // Speed
  if (fix->gprmc_active && !isnanf(fix->gprmc.speed)) {
    dst->has_speed = true;
    dst->speed = fix->gprmc.speed;
  }else if (ctx->gpvtg.is_valid
      && (ctx->gpvtg.fix_mode != 'N')
      && !isnanf(ctx->gpvtg.speed_kmph)) {
    dst->has_speed = true;
    dst->speed = ctx->gpvtg.speed_kmph * KMPH_TO_MPS;
  }else {
    dst->has_speed = false;
    dst->speed = 0;
  }

  // Accuracy
  dst->has_accuracy = false;
  dst->accuracy = 0;
  if (fix->gpgst_active) {
    if (!isnanf(fix->gpgst.std_lat) && !isnanf(fix->gpgst.std_lon)) {
      dst->has_accuracy = true;
      dst->accuracy = hypotf(fix->gpgst.std_lat, fix->gpgst.std_lon);
    }else if (!isnanf(fix->gpgst.range_rms)) {
      dst->has_accuracy = true;
      dst->accuracy = fix->gpgst.range_rms;
    }else {
      assert(!dst->has_accuracy);
    }
  }

  return dst->is_valid;
}

static inline bool is_same_fix_time(struct nmea_fix_time_t t1, struct nmea_fix_time_t t2)
{
  if (t1.hhmmss != t2.hhmmss)
    return false;
  else
    return abs((int)t2.mss - (int)t1.mss) < 50;
}

static parse_error_t parse_nmea_fix_time(const char *hhmmss_mss, struct nmea_fix_time_t *dst)
{
  char *endptr;
  unsigned long hhmmss;
  unsigned mss;

  assert(dst);

  if (hhmmss_mss[0] == '\0') {
    dst->hhmmss = 0;
    dst->mss = 0;
    return FIELD_UNDEFINED;
  }

  hhmmss = strtoul(hhmmss_mss, &endptr, 10);
  if ((*endptr != '.') && (*endptr != '\0')) {
    return FIELD_INVALID;
  }

  if (hhmmss > 240000)
    return FIELD_INVALID;

  if ((hhmmss % 10000) > 6000)
    return FIELD_INVALID;

  if ((hhmmss % 100) > 60)
    return FIELD_INVALID;

  if (endptr[0] == '.' && (endptr[1] != '\0') ) {
    unsigned i;
    char mss_s[4] = "000";

    for (i=0; i<3; ++i) {
      if (endptr[i+1] == '\0')
        break;
      else
        mss_s[i] = endptr[i+1];
    }

    mss = strtoul(mss_s, &endptr, 10);
    if (*endptr != '\0')
      return FIELD_INVALID;
    assert(mss < 1000);
  }else {
    mss = 0;
  }

  dst->hhmmss = hhmmss;
  dst->mss = mss;

  return FIELD_VALID;
}

static inline parse_error_t parse_nmea_degrees(const char *deg_str, bool reverse_direction, double *dst)
{
  char *endptr;
  double res, degrees, minutes;

  if (deg_str[0] == '\0')
    return FIELD_UNDEFINED;

  errno = 0;
  res = strtod(deg_str, &endptr);
  if ((errno == ERANGE && (res == HUGE_VAL || res == -HUGE_VAL))
      || (errno != 0 && res == 0)
      || (*endptr != '\0')
      )
    return FIELD_INVALID;

  minutes = 100.0 * modf(res / 100.0, &degrees);

  *dst = (reverse_direction ? -1.0 : 1.0) * (degrees + minutes / 60.0);

  return FIELD_VALID;
}

static parse_error_t parse_nmea_latitude(const char *deg_str, const char *ns, double *dst)
{
  parse_error_t err = parse_nmea_degrees(deg_str, *ns == 'S', dst);
  if (err != FIELD_VALID)
    return err;
  if (*dst < -90.0 || *dst > 90.0)
    return FIELD_INVALID;
  return FIELD_VALID;
}

static parse_error_t parse_nmea_longitude(const char *deg_str, const char *ew, double *dst)
{
  parse_error_t err = parse_nmea_degrees(deg_str, *ew == 'W', dst);
  if (err != FIELD_VALID)
    return err;
  if (*dst < -180.0 || *dst > 180.0)
    return FIELD_INVALID;
  return FIELD_VALID;
}

static parse_error_t parse_float(const char *str, float *dst)
{
  char *endptr;
  errno = 0;
  if (*str == '\0') {
    *dst = FP_NAN;
    return FIELD_UNDEFINED;
  }else {
    *dst = strtof(str, &endptr);
    if ((errno == ERANGE && (*dst == HUGE_VALF || *dst == -HUGE_VALF))
        || (errno != 0 && *dst == 0)
        || (*endptr != '\0')
       ) {
      return FIELD_INVALID;
    }
  }
  return FIELD_VALID;
}

static bool set_nmea_error(struct gps_msg_status_t *status,
    const uint8_t *nmea_msg, size_t nmea_msg_size, char *fmt, ...)
{
  va_list ap;
  char nmea_str[NMEA_MAX];
  char err_str[sizeof(status->err)];

  assert(nmea_msg_size <= sizeof(nmea_str));
  memcpy(nmea_str, nmea_msg, nmea_msg_size);
  nmea_str[nmea_msg_size-2] = '\0';

  va_start(ap, fmt);
  vsnprintf(err_str, sizeof(err_str), fmt, ap);
  va_end(ap);

  status->is_valid = false;
  status->location_changed = false;
  snprintf(status->err, sizeof(status->err), "%s NMEA: %s", err_str, nmea_str);

  return false;
}