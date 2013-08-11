/*
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
 *
 * This file is part of UsbGPS4Droid.
 *
 * UsbGPS4Droid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * UsbGPS4Droid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with UsbGPS4Droid. If not, see <http://www.gnu.org/licenses/>.
 */

/**
 *
 */
package org.broeuschmeul.android.gps.usb.provider;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.location.GpsStatus.NmeaListener;
import android.os.Bundle;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import org.broeuschmeul.android.gps.usb.UsbSerialController;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;


/**
 * A Service used to replace Android internal GPS with a bluetooth GPS and/or write GPS NMEA data in a File.
 *
 * @author Herbert von Broeuschmeul
 *
 */
public class UsbGpsProviderService extends Service {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = UsbGpsProviderService.class.getSimpleName();

	public static final String ACTION_START_TRACK_RECORDING = "org.broeuschmeul.android.gps.usb.tracker.nmea.intent.action.START_TRACK_RECORDING";
	public static final String ACTION_STOP_TRACK_RECORDING = "org.broeuschmeul.android.gps.usb.tracker.nmea.intent.action.STOP_TRACK_RECORDING";
	public static final String ACTION_START_GPS_PROVIDER = "org.broeuschmeul.android.gps.usb.provider.nmea.intent.action.START_GPS_PROVIDER";
	public static final String ACTION_STOP_GPS_PROVIDER = "org.broeuschmeul.android.gps.usb.provider.nmea.intent.action.STOP_GPS_PROVIDER";
	public static final String ACTION_CONFIGURE_SIRF_GPS = "org.broeuschmeul.android.gps.usb.provider.nmea.intent.action.CONFIGURE_SIRF_GPS";

	public static final String PREF_START_GPS_PROVIDER = "startGps";
	public static final String PREF_GPS_LOCATION_PROVIDER = "gpsLocationProviderKey";
	public static final String PREF_REPLACE_STD_GPS = "replaceStdtGps";
	public static final String PREF_FORCE_ENABLE_PROVIDER = "forceEnableProvider";
	public static final String PREF_MOCK_GPS_NAME = "mockGpsName";
	public static final String PREF_CONNECTION_RETRIES = "connectionRetries";
	public static final String PREF_TRACK_RECORDING = "trackRecording";
	public static final String PREF_TRACK_FILE_DIR = "trackFileDirectory";
	public static final String PREF_TRACK_FILE_PREFIX = "trackFilePrefix";
	public static final String PREF_GPS_DEVICE = "usbDevice";
	public static final String PREF_GPS_DEVICE_SPEED = "gpsDeviceSpeed";
	public static final String PREF_ABOUT = "about";

	/**
	 * Tag used for log messages
	 */
	private static final String LOG_TAG = "UsbGPS";

	public static final String PREF_SIRF_GPS = "sirfGps";
	public static final String PREF_SIRF_ENABLE_GGA = "enableGGA";
	public static final String PREF_SIRF_ENABLE_RMC = "enableRMC";
	public static final String PREF_SIRF_ENABLE_GLL = "enableGLL";
	public static final String PREF_SIRF_ENABLE_VTG = "enableVTG";
	public static final String PREF_SIRF_ENABLE_GSA = "enableGSA";
	public static final String PREF_SIRF_ENABLE_GSV = "enableGSV";
	public static final String PREF_SIRF_ENABLE_ZDA = "enableZDA";
	public static final String PREF_SIRF_ENABLE_SBAS = "enableSBAS";
	public static final String PREF_SIRF_ENABLE_NMEA = "enableNMEA";
	public static final String PREF_SIRF_ENABLE_STATIC_NAVIGATION = "enableStaticNavigation";

	private UsbGpsConverter mConverter;

	private PrintWriter writer;
	private File trackFile;
	private boolean preludeWritten = false;

	@Override
	public void onCreate() {
		super.onCreate();
		mConverter = new UsbGpsConverter(this);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {

        if (intent == null) {
            Log.v(TAG, "UsbGpsProviderService restarted");
            processStartGpsProvider();
        }else {
            final String action = intent.getAction();
            if (action.equals(ACTION_START_GPS_PROVIDER)) processStartGpsProvider();
            else if(action.equals(ACTION_STOP_GPS_PROVIDER)) processStopGpsProvider();
            else if(action.equals(ACTION_START_TRACK_RECORDING)) processStartTrackRecording();
            else if(action.equals(ACTION_STOP_TRACK_RECORDING)) processStopTrackRecording();
            else if(action.equals(ACTION_CONFIGURE_SIRF_GPS)) processConfigureSirfGps(intent.getExtras());
            else Log.e(TAG, "onStartCommand(): unknown action " + action);
        }
        return START_STICKY;
	}

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }

    @Override
    public void onDestroy() {
        stop();
    }


    public boolean isServiceStarted() {
        return mConverter.isActive();
    }


    private void processStartGpsProvider() {
        final SharedPreferences prefs;
        final String providerName;
        final MockLocationProvider provider;
        final boolean replaceInternalGps;
        final String usbBaudrate;

        if (isServiceStarted()) return;

        prefs = PreferenceManager.getDefaultSharedPreferences(this);
        providerName = prefs.getString(PREF_MOCK_GPS_NAME,
                MockLocationProvider.DEFAULT_NAME);
        replaceInternalGps = prefs.getBoolean(PREF_REPLACE_STD_GPS, false);
        usbBaudrate = prefs.getString(PREF_GPS_DEVICE_SPEED,
                String.valueOf(UsbSerialController.DEFAULT_BAUDRATE));

        provider = new MockLocationProvider(providerName);
        provider.replaceInternalGps(replaceInternalGps);

        mConverter.setLocationProvider(provider);
        try {
            mConverter.setBaudRate(Integer.valueOf(usbBaudrate));
        } catch (NumberFormatException nfe) {
            nfe.printStackTrace();
        }

        mConverter.start();

        Notification notification = createForegroundNotification();
        startForeground(R.string.foreground_gps_provider_started_notification, notification);

        Toast.makeText(this, this.getString(R.string.msg_gps_provider_started), Toast.LENGTH_SHORT).show();
    }

    private void processStopGpsProvider() {
        stop();
        stopSelf();
    }

    private void processStartTrackRecording() {
        /*
        if (trackFile == null){
            if (mGpsManager != null){
                beginTrack();
                mGpsManager.addNmeaListener(mNmeaListener);
                Toast.makeText(this, getText(R.string.msg_nmea_recording_started),
                        Toast.LENGTH_SHORT).show();
            } else {
                endTrack();
            }
        } else {
            Toast.makeText(this, getText(R.string.msg_nmea_recording_already_started),
                    Toast.LENGTH_SHORT).show();
        }
        */
    }

    private void processStopTrackRecording() {
        /*
        if (mGpsManager != null){
            mGpsManager.removeNmeaListener(mNmeaListener);
            endTrack();
            Toast.makeText(this, getText(R.string.msg_nmea_recording_stopped),
                    Toast.LENGTH_SHORT).show();
        }
        */
    }

    private void processConfigureSirfGps(Bundle extras) {
        if (!isServiceStarted()) return;
        //mGpsManager.enableSirfConfig(extras);
    }

    private void stop() {
        stopForeground(true);

        if (isServiceStarted()) {
            mConverter.stop();

            Toast.makeText(this, R.string.msg_gps_provider_stopped, Toast.LENGTH_SHORT)
            .show();

        }
    }


    @SuppressWarnings("deprecation")
    private Notification createForegroundNotification() {
        CharSequence text = getText(R.string.foreground_gps_provider_started_notification);

        Notification notification = new Notification(R.drawable.ic_stat_notify,
                text, System.currentTimeMillis());

        PendingIntent contentIntent = PendingIntent.getActivity(this, 0,
                new Intent(this, UsbGpsActivity.class), 0);

        notification.setLatestEventInfo(this,
                getText(R.string.foreground_gps_provider_started_notification), text, contentIntent);

        return notification;
    }

	private void beginTrack(){
		SimpleDateFormat fmt = new SimpleDateFormat("_yyyy-MM-dd_HH-mm-ss'.nmea'", Locale.US);
		SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
		String trackDirName = sharedPreferences.getString(PREF_TRACK_FILE_DIR, this.getString(R.string.defaultTrackFileDirectory));
		String trackFilePrefix = sharedPreferences.getString(PREF_TRACK_FILE_PREFIX, this.getString(R.string.defaultTrackFilePrefix));
		trackFile = new File(trackDirName,trackFilePrefix+fmt.format(new Date()));
		Log.d(LOG_TAG, "Writing the prelude of the NMEA file: "+trackFile.getAbsolutePath());
		File trackDir = trackFile.getParentFile();
		try {
			if ((! trackDir.mkdirs()) && (! trackDir.isDirectory())){
				Log.e(LOG_TAG, "Error while creating parent dir of NMEA file: "+trackDir.getAbsolutePath());
			}
			writer = new PrintWriter(new BufferedWriter(new FileWriter(trackFile)));
			preludeWritten = true;
		} catch (IOException e) {
			Log.e(LOG_TAG, "Error while writing the prelude of the NMEA file: "+trackFile.getAbsolutePath(), e);
		}
	}
	private void endTrack(){
		if (trackFile != null && writer != null){
			Log.d(LOG_TAG, "Ending the NMEA file: "+trackFile.getAbsolutePath());
			preludeWritten = false;
			writer.close();
			trackFile = null;
		}
	}

	private void addNMEAString(String data){
		if (! preludeWritten){
			beginTrack();
		}
		Log.v(LOG_TAG, "Adding data in the NMEA file: "+ data);
		if (trackFile != null && writer != null){
			writer.print(data);
		}
	}

	private final NmeaListener mNmeaListener = new NmeaListener() {
	    @Override
	    public void onNmeaReceived(long timestamp, String data) {
	        addNMEAString(data);
	    }
	};


}
