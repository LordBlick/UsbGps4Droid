package org.broeuschmeul.android.gps.usb.provider;

/**
 *
 */


import static junit.framework.Assert.assertTrue;

import android.location.Location;
import android.os.Bundle;
import android.text.format.Time;
import android.util.Log;

import java.util.Arrays;

/**
 * @author Alexey Illarionov
 *
 */
public abstract class GpsMessageParser {

	// Debugging
	private static final String TAG = GpsMessageParser.class.getSimpleName();
	private static final boolean D = BuildConfig.DEBUG & true;

	/**
	 * Knots to meters per second
	 */
	private static final float KNOTS_TO_MPS = 0.514444f;

	final private NmeaFix nmeaFix = new NmeaFix();
	final private SirfFix sirfFix = new SirfFix();

	public GpsMessageParser() {
	}

	public boolean putNmeaMessage(String msg) {
		return nmeaFix.putMessage(msg);
	}

	public boolean putSirfMessage(final byte[] msg, int offset, int length) {
		return sirfFix.putMessage(msg, offset, length);
	}

	public abstract void setNewLocation(final Location l);

	private class NmeaFix {

		/* Current epoch */
		final NmeaFixTime currentTime = new NmeaFixTime();
		final Time nmeaDateTime = new Time("UTC");
		final Location currentLocation = new Location("");

		boolean epochClosed;

		/* GPGGA */
		boolean hasGga;

		/**
		 * Fix quality
		 */
		int ggaFixQuality;

		/**
		 * Number of satellites in use (not those in view)
		 */
		int ggaNbSat;

		/**
		 * Height of geoid above WGS84 ellipsoid
		 */
		double ggaGeoidHeight;

		/* GPRMC */
		boolean hasRmc;

		/**
		 * Date of fix
		 */
		int rmcDdmmyy;

		/**
		 * GPRMC Status true - active (A), false - void (V).
		 */
		boolean rmcStatusIsActive;


		/* GPGSA */
		boolean hasGsa;
		int gsaFixType;
		final int gsaPrn[] = new int[12];
		float gsaPdop;
		float gsaHdop;
		float gsaVdop;

		public NmeaFix() {
			reset();
		}

		void reset() {
			currentTime.reset();

			this.hasGga = false;
			this.ggaFixQuality = -1;
			this.ggaNbSat = -1;

			this.hasRmc = false;
			this.rmcDdmmyy = -1;
			this.rmcStatusIsActive = false;

			this.hasGsa = false;

			this.nmeaDateTime.setToNow();
			this.currentLocation.reset();
			this.epochClosed = true;
		}

		private void openEpoch(NmeaFixTime t) {
			currentTime.set(t);
			currentLocation.reset();
			hasGga = hasRmc = false;
			epochClosed = false;
		}

		private boolean prepareEpoch(NmeaFixTime fixTime) {
			if (currentTime.isCurrentEpoch(fixTime)) {
				if (epochClosed) {
					return false;
				}
			}else {
				/* new epoch */
				if (!epochClosed) {
					closeEpoch(true);
				}
				openEpoch(fixTime);
			}
			return true;
		}

		private void closeEpoch(boolean force) {
			int yyyy, mm, dd;
			boolean locationValid;

			/* No GPGGA/GPRMC sentences received */
			if (!hasGga && !hasRmc) {
				epochClosed = true;
				return;
			}

			if (epochClosed && !force)
				return;

			/* wait for GGA and RMC messages */
			if (!force && (!hasGga || !hasRmc) )
				return;

			if (this.hasRmc && this.rmcStatusIsActive && (this.rmcDdmmyy > 0)) {
				yyyy = nmeaDateTime.year;
				yyyy = yyyy - yyyy % 100 + rmcDdmmyy % 100;
				mm = rmcDdmmyy / 100 % 100;
				dd = rmcDdmmyy / 10000 % 100;
			}else {
				yyyy = nmeaDateTime.year;
				mm = nmeaDateTime.month;
				dd = nmeaDateTime.monthDay;
			}

			nmeaDateTime.set(
					currentTime.getSecond(),
					currentTime.getMinute(),
					currentTime.getHour(),
					dd,
					mm,
					yyyy);

			currentLocation.setTime(nmeaDateTime.toMillis(true) + currentTime.mss);

			if (this.hasGga && this.hasRmc) {
				locationValid = ( (this.ggaFixQuality != 0) && (this.rmcStatusIsActive) );
			}else if (this.hasGga){
				locationValid = this.ggaFixQuality != 0;
			}else {
				assertTrue(this.hasRmc);
				locationValid = this.rmcStatusIsActive;
			}

			if (locationValid) {
				/* Update bundle */
				if (this.hasGga || this.hasGsa) {
					int fields = 0;
					Bundle extras;

					/* Number of satellites used in current solution */
					int satellites = -1;
					if (this.hasGga)
						satellites = this.ggaNbSat;
					if ((satellites < 0) && this.hasGsa) {
						satellites = 0;
						for(int prn: gsaPrn) { if (prn > 0) satellites += 1; }
					}

					extras = new Bundle(5);
					if (satellites >= 0) {
						fields += 1;
						extras.putInt("satellites", satellites);
					}
					if (hasGga) {
						if (!Double.isNaN(ggaGeoidHeight)) {
							extras.putDouble("geoidheight", ggaGeoidHeight);
						}
					}
					if (hasGsa) {
						if (!Float.isNaN(gsaHdop)) {
							fields += 1;
							extras.putFloat("HDOP", gsaHdop);
						}
						if (!Float.isNaN(gsaVdop)) {
							fields += 1;
							extras.putFloat("VDOP", gsaVdop);
						}
						if (!Float.isNaN(gsaPdop)) {
							fields += 1;
							extras.putFloat("PDOP", gsaPdop);
						}
					}

					if (fields > 0) currentLocation.setExtras(extras);
				}

				setNewLocation(currentLocation);
			}else {
				/* Location lost */
				this.hasGsa = false;
				setNewLocation(null);
			}

			epochClosed = true;
		}

		private int nmeaFieldCount(String msg, int start) {
			int pos;
			final int end = msg.length();
			int i;

			i=0;
			pos=start;
			while (pos < end) {
				pos = msg.indexOf(',', pos);
				if (pos < 0)
					break;
				else {
					pos += 1;
					i += 1;
				}
			};
			return i+1;
		}

		private double parseNmeaDegrees(String s, boolean oppositeDirection) throws NumberFormatException{
			double tmp;
			double res;

			if (s.length() < 1) return Double.NaN;
			tmp = Double.parseDouble(s);

			/* Degrees */
			res = Math.floor(tmp/100.0);

			/* Minutes */
			res = res + (tmp - 100.0 * res) / 60.0;

			if (oppositeDirection) res = -res;

			return res;
		}

		private boolean parseGpgga(String msg) {
			int startPos, curPos;
			int fieldCount;
			final NmeaFixTime fixTime = new NmeaFixTime();
			double lat, lon, alt, geoidheight;
			float hdop;
			int fixQ, nbSat;

			startPos = "$GPGGA,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount != 14) {
				Log.d(TAG, "Invalid field count in $GPGGA message: " + fieldCount + " - " + msg);
				return false;
			}

			try {
				/* Field 1. Time of fix */
				curPos = msg.indexOf(',', startPos);
				if ( curPos - startPos < 6) {
					Log.d(TAG, "Invalid time of fix in $GPGGA message - " + msg);
					return false;
				}
				fixTime.set(msg.substring(startPos, curPos));
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid time of fix in $GPGGA message - " + msg);
				return false;
			}

			try {
				String sLat;
				boolean northDirection = true;

				/* Field 2. Latitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLat = msg.substring(startPos, curPos);


				/* Field 3. Latitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'S')
						) {
					northDirection = false;
				}
				lat = parseNmeaDegrees(sLat, !northDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid latitude in $GPGGA message - " + msg);
				return false;
			}

			try {
				String sLon;
				boolean eastDirection = true;

				/* Field 4. Longitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLon = msg.substring(startPos, curPos);

				/* Field 5. Longitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'W')
						) {
					eastDirection = false;
				}
				lon = parseNmeaDegrees(sLon, !eastDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid longitude in $GPGGA message - " + msg);
				return false;
			}

			/* Field 6 fix quality */
			curPos = msg.indexOf(',', (startPos = curPos+1));
			if (curPos-startPos == 0) {
				fixQ = 1;
			}else if (curPos-startPos > 1) {
				Log.d(TAG, "Invalid fix quality in $GPGGA message - " + msg);
				return false;
			}else {
				fixQ = Character.digit(msg.charAt(startPos), 10);
				if (fixQ < 0) {
					Log.d(TAG, "Invalid fix quality in $GPGGA message - " + msg);
					return false;
				}
			}

			/* Field 7. Number of satellites being tracked */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					nbSat = -1;
				}else {
					nbSat = Integer.parseInt(msg.substring(startPos, curPos));
					if (nbSat < 0) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid number of tracked satellites in $GPGGA message - " + msg);
				return false;
			}

			/* Field 8. HDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					hdop = Float.NaN;
				}else {
					hdop = Float.parseFloat(msg.substring(startPos, curPos));
					if (hdop < 0) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid HDOP in $GPGGA message - " + msg);
				return false;
			}

			/* Field 9, 10.  Altitude above mean sea level */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					alt = Double.NaN;
				}else {
					alt = Double.parseDouble(msg.substring(startPos, curPos));
				}

				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) != 'M')
						) {
					alt = Double.NaN;
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid altitude $GPGGA message - " + msg);
				return false;
			}

			/* Field 11, 12. Geoid height */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					geoidheight = Double.NaN;
				}else {
					geoidheight = Double.parseDouble(msg.substring(startPos, curPos));
				}

				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) != 'M')
						) {
					geoidheight = Double.NaN;
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid geoid height $GPGGA message - " + msg);
				return false;
			}

			/* Field 13, 14 not interested in */

			/* Handle received data */
			if (!prepareEpoch(fixTime)) {
				Log.d(TAG, "$GPRMC message from closed epoch - " + msg);
				return false;
			}

			this.hasGga = true;
			this.ggaFixQuality = fixQ;
			this.ggaGeoidHeight = geoidheight;
			if (fixQ != 0) {
				this.ggaNbSat = nbSat;
				currentLocation.setLatitude(lat);
				currentLocation.setLongitude(lon);
				if (!Double.isNaN(alt)) {
					double altEllips;
					/* Set ellipsoid altitude */
					altEllips = alt;
					if (!Double.isNaN(geoidheight))
						altEllips += geoidheight;
					currentLocation.setAltitude(altEllips);
				}else
					currentLocation.removeAltitude();
				if (!Float.isNaN(hdop)) {
					currentLocation.setAccuracy(hdop);
				}else {
					currentLocation.removeAccuracy();
				}
			}
			closeEpoch(false);

			return true;
		}

		private boolean parseGprmc(String msg) {
			int startPos, curPos;
			int fieldCount;
			int ddmmyy;
			double lat, lon;
			float speed, bearing;
			boolean statusIsActive;
			final NmeaFixTime fixTime = new NmeaFixTime();

			startPos = "$GPRMC,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount < 11) {
				Log.d(TAG, "Invalid field count in $GPGGA message: " + fieldCount + " - " + msg);
				return false;
			}

			try {
				/* Field 1. Time of fix */
				curPos = msg.indexOf(',', startPos);
				if ( curPos - startPos < 6) {
					Log.d(TAG, "Invalid time of fix in $GPRMC message - " + msg);
					return false;
				}
				fixTime.set(msg.substring(startPos, curPos));
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid time of fix in $GPRMC message - " + msg);
				return false;
			}

			/* Field 2. Status */
			curPos = msg.indexOf(',', (startPos = curPos+1));
			if (curPos-startPos == 0) {
				statusIsActive = true;
			}else if (curPos-startPos > 1) {
				Log.d(TAG, "Invalid status in $GPRMC message - " + msg);
				return false;
			}else {
				if ((msg.charAt(startPos) != 'A')
						&& (msg.charAt(startPos) != 'V')) {
					statusIsActive = true;
					Log.v(TAG, "Unknown GPRMC status - " + msg);
				}else {
					statusIsActive = (msg.charAt(startPos) == 'A');
				}
			}

			try {
				String sLat;
				boolean northDirection = true;

				/* Field 3. Latitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLat = msg.substring(startPos, curPos);

				/* Field 4. Latitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'S')
						) {
					northDirection = false;
				}
				lat = parseNmeaDegrees(sLat, !northDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid latitude in $GPRMC message - " + msg);
				return false;
			}

			try {
				String sLon;
				boolean eastDirection = true;

				/* Field 5. Longitude */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				sLon = msg.substring(startPos, curPos);

				/* Field 6. Longitude direction */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if ( (curPos - startPos >= 1)
						&& (msg.charAt(startPos) == 'W')
						) {
					eastDirection = false;
				}
				lon = parseNmeaDegrees(sLon, !eastDirection);
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid longitude in $GPRMC message - " + msg);
				return false;
			}

			/* Field 7. Speed over the ground  */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					speed = Float.NaN;
				}else {
					speed = Float.parseFloat(msg.substring(startPos, curPos)) * KNOTS_TO_MPS;
					if (speed < 0.0) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid speed over ground in $GPRMC message - " + msg);
				return false;
			}

			/* Field 8. Track angle */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					bearing = Float.NaN;
				}else {
					bearing = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid track angle in $GPRMC message - " + msg);
				return false;
			}

			/* Field 9. Date */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					ddmmyy = -1;
				}else {
					ddmmyy = Integer.parseInt(msg.substring(startPos, curPos), 10);
					if ((ddmmyy < 0) || ddmmyy > 311299) throw new NumberFormatException();
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid date in $GPRMC message - " + msg);
				return false;
			}

			/* Field 10,11 magnetic variation */
			/* Handle received data */

			/* handle data */
			if (!prepareEpoch(fixTime)) {
					Log.d(TAG, "$GPRMC message from closed epoch - " + msg);
					return false;
			}

			this.hasRmc = true;
			this.rmcStatusIsActive = statusIsActive;
			if (statusIsActive) {
				this.rmcDdmmyy = ddmmyy;
				currentLocation.setLatitude(lat);
				currentLocation.setLongitude(lon);
				if (Float.isNaN(speed)) {
					currentLocation.removeSpeed();
				}else {
					currentLocation.setSpeed(speed);
				}

				if (Float.isNaN(bearing)) {
					currentLocation.removeBearing();
				}else {
					currentLocation.setBearing(bearing);
				}
			}
			closeEpoch(false);
			return true;
		}

		private boolean parseGpgsa(String msg) {
			int startPos, curPos;
			int fieldCount;
			int fixMode;
			float pdop, hdop, vdop;
			final int prns[] = new int[12];

			startPos = "$GPGSA,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount < 17) {
				Log.d(TAG, "Invalid field count in $GPGSA message: " + fieldCount + " - " + msg);
				return false;
			}

			/* Field 1. Auto / Manual selection if 2D/3D fix */
			curPos = msg.indexOf(',', startPos);

			/* Field 2. Fix mode 1 - no fix, 2 - 2D fix, 3 - 3D fix*/
			curPos = msg.indexOf(',', (startPos = curPos+1));
			if (curPos - startPos == 0)
				fixMode = -1;
			else {
				fixMode = Character.digit(msg.charAt(startPos), 10);
				if (fixMode < 0) {
					Log.d(TAG, "Invalid 3D Fix field $GPGSA message: " + msg);
					return false;
				}
			}

			/* 12 PRNs */
			try {
				for (int i=0; i<12; ++i) {
					curPos = msg.indexOf(',', (startPos = curPos+1));
					if (curPos - startPos == 0)
						prns[i] = -1;
					else {
						prns[i] = Integer.parseInt(msg.substring(startPos, curPos));
					}
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid PRN in $GPGSA message - " + msg);
				return false;
			}

			/* Field 15. PDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					pdop = Float.NaN;
				}else {
					pdop = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid PDOP field in $GPGSA message - " + msg);
				return false;
			}

			/* Field 16. HDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) {
					hdop = Float.NaN;
				}else {
					hdop = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid HDOP field in $GPGSA message - " + msg);
				return false;
			}

			/* Field 17. VDOP */
			try {
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos < 0) curPos = msg.length();
				if (curPos-startPos == 0) {
					vdop = Float.NaN;
				}else {
					vdop = Float.parseFloat(msg.substring(startPos, curPos));
				}
			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid VDOP field in $GPGSA message - " + msg);
				return false;
			}

			this.hasGsa = true;
			this.gsaFixType = fixMode;
			this.gsaHdop = hdop;
			this.gsaPdop = pdop;
			this.gsaVdop = vdop;
			System.arraycopy(prns, 0, this.gsaPrn, 0, prns.length);
			if (D) Log.v(TAG, "$GPGSA. 3dfix: " + this.gsaFixType +
					" HDOP: " + this.gsaHdop + " PDOP: " + this.gsaPdop +
					" VDOP: " + this.gsaVdop + " PRNs: " + Arrays.toString(this.gsaPrn));

			return true;
		}

		private boolean parseGpzda(String msg) {
			int startPos, curPos;
			int fieldCount;
			int dd, mm, yyyy;
			final NmeaFixTime currentTime = new NmeaFixTime();

			startPos = "$GPZDA,".length();
			fieldCount = nmeaFieldCount(msg, startPos);
			if (fieldCount < 6) {
				Log.d(TAG, "Invalid field count in $GPZDA message: " + fieldCount + " - " + msg);
				return false;
			}

			try {
				/* Field 1. Current time  */
				curPos = msg.indexOf(',', startPos);
				if ( curPos - startPos < 6) {
					Log.d(TAG, "Invalid time in $GPZDA message - " + msg);
					return false;
				}
				currentTime.set(msg.substring(startPos, curPos));

				/* Field 2. Day */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) throw new NumberFormatException();
				dd = Integer.parseInt(msg.substring(startPos, curPos));
				if (dd < 1 || dd > 31) throw new NumberFormatException();

				/* Field 3. Month */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos-startPos == 0) throw new NumberFormatException();
				mm = Integer.parseInt(msg.substring(startPos, curPos));
				if (mm < 1 || mm > 12) throw new NumberFormatException();

				/* Field 4. Year */
				curPos = msg.indexOf(',', (startPos = curPos+1));
				if (curPos < 0) curPos = msg.length();
				if (curPos-startPos == 0) throw new NumberFormatException();
				yyyy = Integer.parseInt(msg.substring(startPos, curPos));
				if (yyyy < 1995) throw new NumberFormatException();

				nmeaDateTime.set(
						currentTime.getSecond(),
						currentTime.getMinute(),
						currentTime.getHour(),
						dd,
						mm,
						yyyy);

				if (D) Log.v(TAG, "$GPZDA received. New time: " + nmeaDateTime.format3339(false));

			}catch(NumberFormatException ie) {
				Log.d(TAG, "Invalid time in $GPZDA message - " + msg);
				return false;
			}
			return true;
		}

		private boolean putMessage(String msg) {
			int checksumPos;

			/* Trim checksum */
			checksumPos = msg.lastIndexOf('*');
			if (checksumPos >= 4) {
				msg = msg.substring(0, checksumPos);
			}

			if (msg.startsWith("$GPGGA,")) {
				return parseGpgga(msg);
			}else if(msg.startsWith("$GPRMC,")) {
				return parseGprmc(msg);
			}else if (msg.startsWith("$GPGSA,")) {
				return parseGpgsa(msg);
			}else if (msg.startsWith("$GPGSV,")) {
			}else if (msg.startsWith("$GPZDA,")) {
				return parseGpzda(msg);
			}else if (msg.startsWith("$GPGLL,")) {
				/* TODO: $GPGLL message */
			}else if (msg.startsWith("$GPVTG,")) {
				/* TODO: $GPVTG message */
			}else {
				if (D) Log.d(TAG, "Unknown NMEA data type. Msg: " + msg);
				return false;
			}

			return true;
		}
	} /* class NmeaFix */

	private static class NmeaFixTime {
		int hhmmss;
		int mss;

		public NmeaFixTime() {
			reset();
		}

		public void reset() {
			set(-1, -1);
		}

		void set(NmeaFixTime t) {
			set(t.hhmmss, t.mss);
		}

		public void set(int hhmmss, int mss) {
			this.hhmmss = hhmmss;
			this.mss = mss;
		}

		private boolean isCurrentEpoch(NmeaFixTime t) {
			if (t.hhmmss != this.hhmmss)
				return false;
			if (Math.abs(t.mss - this.mss) > 200)
				return false;
			return true;
		}

		public int getHour() { return (hhmmss / 10000) % 100; }
		public int getMinute() { return (hhmmss / 100) % 100; }
		public int getSecond() { return hhmmss % 100; }

		public void set(String s) throws NumberFormatException {
			long dl;

			if (s.length() < 6) throw new NumberFormatException();

			dl = (long)(1000.0 * Double.parseDouble(s));
			hhmmss = (int)(dl/1000);
			mss = (int)(dl % 1000);
			/* XXX: validation too weak */
			if ((hhmmss < 0) || (hhmmss > 240000)) throw new NumberFormatException();
			if ( (hhmmss % 10000) >= 6000) throw new NumberFormatException();
			if (hhmmss % 100 > 60) throw new NumberFormatException();
		}
	} /* class NmeaFixTime */

	private class SirfFix {

		private static final int SIRF_NUM_CHANNELS = 12;

		final Time internalTime = new Time("UTC");
		final Location currentLocation = new Location("");

		private int lastUsedInFixMask = 0;

		private int satPos = 0;
		private final int prn[] = new int[SIRF_NUM_CHANNELS];
		private final float elevation[] = new float[SIRF_NUM_CHANNELS];
		private final float azimuth[] = new float[SIRF_NUM_CHANNELS];
		private final float snr[] = new float[SIRF_NUM_CHANNELS];

		/* 2-bytes bitmask */
		private short get2d(final byte msg[], int p) {
			return (short)(((msg[p] & 0xff) << 8) |
					(msg[p+1] & 0xff));
		}

		/* 2-bytes unsigned integer */
		private int get2u(final byte msg[], int p) {
			return get2d(msg, p) ;
		}

		/* 4-bytes bitmask */
		private int get4d(final byte msg[], int p) {
			return (
					((msg[p] & 0xff) << 24) |
					((msg[p+1] & 0xff) << 16) |
					((msg[p+2] & 0xff) << 8) |
					(msg[p+3] & 0xff));
		}

		/* 4-bytes unsigned integer */
		private long get4u(final byte msg[], int p) {
			return (long)get4d(msg, p) & 0xffff;
		}

		/* 4-bytes signed integer */
		private int get4s(final byte msg[], int p) {
			return get4d(msg, p);
		}

		private boolean parseGeodeticNavData(final byte msg[], int offset, int length) {
			int payloadSize;
			boolean isNavValid;
			int year, month, day, hour, minute, second, mss;
			double lat, lon, altEllips, altMSL;
			float speed, bearing;
			float ehpe, hdop;
			int satellites;
			Bundle b;

			payloadSize = get2u(msg, offset+2);

			if (payloadSize != 91) {
				Log.d(TAG, "parseGeodeticNavData() error: payloadSize != 91 -  " + payloadSize);
				return false;
			}

			/* Field 2. Navigation valid (2D) */
			isNavValid = get2d(msg, offset+5) == 0;
			// Field 3. Navigation type (2D)
			//navType = get2d(msg, start+7);
			// Field 4.  Extended week number (2U)
			//wn = get2u(msg, start+9);
			// Field 5. GPS time of week (4U)
			//tow = get4u(msg, start+11);
			// Field 6. UTC year
			year = get2u(msg, offset+15);
			// Field 7. UTC month
			month = msg[offset+17] & 0xff;
			// Field 8. UTC day
			day = msg[offset+18] & 0xff;
			// Field 9. UTC hour
			hour = msg[offset+19] & 0xff;
			// Field 10. UTC minute
			minute = msg[offset+20] & 0xff;
			// Field 11. UTC second (milliseconds)
			second = get2u(msg, offset+21);
			mss = second % 1000;
			second /= 1000;

			// Field 12. Bitmap of SVS used in solution. (4D)
			lastUsedInFixMask = get4d(msg, 23);
			// Field 13. Latitude (4S)
			lat = get4s(msg, offset+27) * 1.0e-7;
			// Field 14. Longitude (4S)
			lon = get4s(msg, offset+31) * 1.0e-7;
			// Field 15. Altitude from ellipsoid (4S)
			altEllips = get4s(msg, offset+35) * 0.01;
			// Field 16. Altitude from Mean Sea Level (4S)
			altMSL = get4s(msg, offset+39) * 0.01;
			// Field 17. Map datum (21 = WGS-84) (1U)
			// datum = msg[start+43];
			// Field 18. Speed over ground (2U)
			speed = get2u(msg, offset+44) * 0.01f;
			// Field 19. Course over ground (2U)
			bearing = get2u(msg, offset+46) * 0.01f;
			// Field 20. Not implemented magnetic variation (2S)
			// magvar = get2s(msg, start+48);
			// Field 21. Climb rate (2S)
			// climbRate = get2s(msg, start+50);
			// Field 22. Heading rate (2S)
			// headingRate = get2s(msg, start+52);
			// Field 23. Estimated horizontal position error (4U)
			ehpe = get4u(msg, offset+54) * 0.01f;
			// Field 24. Estimated vertical position error (4U)
			// evpe = (float)get4u(msg, start+58) * 0.01f;
			// Field 25. Estimated time error (4U)
			// ete = (float)get4u(msg, start+62) * 0.01f;
			// Field 26. Estimated horizontal velocity error (2U)
			// ehve = (float)get2u(msg, start+66) * 0.01f;
			// Field 27. Clock bias (4S)
			// bias = (float)get4s(msg, start+68) * 0.01f;
			// Field 28. Clock bias error (4U)
			// biasErr = (float)get4u(msg, start+72) * 0.01f;
			// Field 29. Clock drift (4S)
			// drift = (float)get4s(msg, start+76) * 0.01f;
			// Field 30. Clock drift error (4U)
			// driftErr = (float)get4u(msg, start+80) * 0.01f;
			// Field 31. Distance (4U)
			// distance = get4u(msg, start+84);
			// Field 32. Distance error (2U)
			// distanceErr = get2u(msg, start+88);
			// Field 33. Heading error (2U)
			// headingErr = (float)get2u(msg, start+90) * 0.01f;
			// Field 34. number of SVS in fix (1U)
			satellites = msg[offset+92];
			// Field 35. HDOP (1U)
			hdop = msg[offset+93] * 0.2f;
			// Field 36. Additional info (1D)
			// info = msg[start+94];

			if (!isNavValid) {
				setNewLocation(null);
				return true;
			}
			internalTime.set(second, minute, hour, day, month, year);
			currentLocation.setTime(internalTime.toMillis(true) + mss);
			currentLocation.setLatitude(lat);
			currentLocation.setLongitude(lon);
			currentLocation.setAltitude(altEllips);
			currentLocation.setSpeed(speed);
			currentLocation.setBearing(bearing);
			currentLocation.setAccuracy(ehpe);

			b = new Bundle(3);
			b.putInt("satellites", satellites);
			b.putFloat("HDOP", hdop);
			b.putDouble("geoidheight", altEllips - altMSL);
			currentLocation.setExtras(b);
			setNewLocation(currentLocation);

			return true;
		}


		private boolean parseMeasuredTrackerDataOut(final byte msg[], int offset, int length) {
			int payloadSize;
			int i;
			int ephemerisMask = 0;
			int almanacMask = 0;

			payloadSize = get2u(msg, offset+2);

			if (payloadSize != 8 + 15 * SIRF_NUM_CHANNELS) {
				Log.d(TAG, "parseGeodeticNavData() error: payloadSize != 8+15*SIRF_NUM_CHANNELS -  " + payloadSize);
				return false;
			}

			offset += 12;
			satPos=0;
			for (i=0; i<SIRF_NUM_CHANNELS; ++i, offset += 15) {
				float avgCNO = 0;
				int prn = msg[offset+0] & 0xff;
				int state = get2d(msg, offset+3);
				if (prn != 0) {
					this.prn[satPos] = prn;
					this.azimuth[satPos] = (msg[offset+1] & 0xff) * 1.5f;
					this.elevation[satPos] = (msg[offset+2] & 0xff) * 0.5f;
					for (int j=0; j<10; ++j) {
						avgCNO += msg[offset+5+j] & 0xff;
					}
					avgCNO /= 10.0;
					this.snr[satPos] = avgCNO;
					if ((state & 0x80) != 0) {
						ephemerisMask |= 1<<(prn-1);
						/* XXX */
						almanacMask |= 1<<(prn-1);
					}
					satPos += 1;
				}
			}

			return true;
		}


		private boolean putMessage(final byte msg[], int offset, int length) {
			int messageId;

			/* SiRF Message ID */
			messageId = msg[offset+4];
			switch (messageId) {
			case 4:
				return parseMeasuredTrackerDataOut(msg, offset, length);
			case 41:
				return parseGeodeticNavData(msg, offset, length);
			}

			return false;
		}
	} /* class SirfFix */

}
