/*
 * Copyright (C) 2010, 2011, 2012 Herbert von Broeuschmeul
 * Copyright (C) 2010, 2011, 2012 BluetoothGPS4Droid Project
 * Copyright (C) 2011, 2012 UsbGPS4Droid Project
 * Copyright (C) 2013 Alexey Illarionov
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

package org.broeuschmeul.android.gps.usb.provider;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.widget.BaseAdapter;

import org.broeuschmeul.android.gps.usb.SerialLineConfiguration;
import org.broeuschmeul.android.gps.usb.SerialLineConfiguration.Parity;
import org.broeuschmeul.android.gps.usb.SerialLineConfiguration.StopBits;

/**
 * A SettingsFragment Class used to configure, start and stop the NMEA tracker service.
 *
 * @author Herbert von Broeuschmeul
 *
 */
public class SettingsFragment extends PreferenceFragment {

    @SuppressWarnings("unused")
    private static final boolean DBG = BuildConfig.DEBUG & false;
    @SuppressWarnings("unused")
    private static final String TAG = SettingsFragment.class.getSimpleName();

    public interface Callbacks {

        void displayAboutDialog();

        void startGpsProviderService();

        void stopGpsProviderService();

        void startTrackRecording();

        void stopTrackRecording();

        void setSirfFeature(String key, boolean enabled);
    }

    private static final Callbacks sDummyCallbacks = new Callbacks() {
        @Override public void displayAboutDialog() {}
        @Override public void startGpsProviderService() {}
        @Override public void stopGpsProviderService() {}
        @Override public void startTrackRecording() {} ;
        @Override public void stopTrackRecording() {} ;
        @Override public void setSirfFeature(String key, boolean enabled) {} ;
    };

    private Callbacks mCallbacks = sDummyCallbacks;


    private EditTextPreference mMockGpsNamePreference, mConnectionRetriesPreference;
    private Preference mTrackRecordingPreference;
    private PreferenceScreen mGpsLocationProviderPreference;

    private UsbSerialSettings mUsbSerialSettings;

    @Override
    public void onAttach(Activity activity) {
        // TODO Auto-generated method stub
        super.onAttach(activity);

        if (!(activity instanceof Callbacks)) {
            throw new IllegalStateException("Activity must implement fragment's callbacks.");
        }

        mCallbacks = (Callbacks)activity;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref);

        mUsbSerialSettings = new UsbSerialSettings(getPreferenceScreen());
        mTrackRecordingPreference = findPreference(UsbGpsProviderService.PREF_TRACK_RECORDING);
        mMockGpsNamePreference = (EditTextPreference)findPreference(UsbGpsProviderService.PREF_MOCK_GPS_NAME);
        mConnectionRetriesPreference = (EditTextPreference)findPreference(UsbGpsProviderService.PREF_CONNECTION_RETRIES);
        mGpsLocationProviderPreference = (PreferenceScreen)findPreference(UsbGpsProviderService.PREF_GPS_LOCATION_PROVIDER);

        Preference pref = findPreference(UsbGpsProviderService.PREF_ABOUT);
        pref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
            @Override
            public boolean onPreferenceClick(Preference preference) {
                mCallbacks.displayAboutDialog();
                return true;
            }
        });
    }

    @Override
    public void onStart() {
        super.onStart();
        getPreferenceManager()
            .getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
    }

    @Override
    public void onResume() {
        super.onResume();
        updateDevicePreferenceList();
    }

    @Override
    public void onStop() {
        super.onStop();
        getPreferenceManager()
            .getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(mOnSharedPreferenceChangeListener);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mUsbSerialSettings = null;
        mTrackRecordingPreference = null;
        mMockGpsNamePreference = null;
        mConnectionRetriesPreference = null;
        mGpsLocationProviderPreference = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
    }

    private void updateDevicePreferenceList(){
        final SharedPreferences sharedPref = getPreferenceManager().getSharedPreferences();
        final Resources resources = getResources();
        final String mockProviderName;

        mUsbSerialSettings.updateSummaries();
        mTrackRecordingPreference.setEnabled(sharedPref.getBoolean(UsbGpsProviderService.PREF_START_GPS_PROVIDER, false));

        mockProviderName = mMockGpsNamePreference.getText();
        mMockGpsNamePreference.setSummary(mockProviderName);

        int connRetries;

        try {
            connRetries = Integer.valueOf(mConnectionRetriesPreference.getText());
        }catch (NumberFormatException nfe) {
            nfe.printStackTrace();
            connRetries = Integer.valueOf(getString(R.string.defaultConnectionRetries));
        }

        mConnectionRetriesPreference.setSummary(resources.getQuantityString(
                R.plurals.pref_connection_retries_summary, connRetries, connRetries));

        if (sharedPref.getBoolean(UsbGpsProviderService.PREF_REPLACE_STD_GPS, true)){
            mGpsLocationProviderPreference.setSummary(R.string.pref_gps_location_provider_summary);
        } else {
            String s = getString(R.string.pref_mock_gps_name_summary, mockProviderName);
            mGpsLocationProviderPreference.setSummary(s);
        }
    }

    private final OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (mUsbSerialSettings.isSerialSettingsPref(key)) {
                mUsbSerialSettings.updateSummaries();
                ((BaseAdapter)getPreferenceScreen().getRootAdapter()).notifyDataSetChanged();
            } else if (UsbGpsProviderService.PREF_START_GPS_PROVIDER.equals(key)){
                boolean val = sharedPreferences.getBoolean(key, false);
                if (val){
                    mCallbacks.startGpsProviderService();
                } else {
                    mCallbacks.stopGpsProviderService();
                }
            } else if (UsbGpsProviderService.PREF_TRACK_RECORDING.equals(key)){
                boolean val = sharedPreferences.getBoolean(key, false);
                if (val){
                    mCallbacks.startTrackRecording();
                } else {
                    mCallbacks.stopTrackRecording();
                }
            } else if (UsbGpsProviderService.PREF_SIRF_ENABLE_GLL.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_GGA.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_RMC.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_VTG.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_GSA.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_GSV.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_ZDA.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_SBAS.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_NMEA.equals(key)
                    || UsbGpsProviderService.PREF_SIRF_ENABLE_STATIC_NAVIGATION.equals(key)
            ){
                final boolean enabled = sharedPreferences.getBoolean(key, false);
                mCallbacks.setSirfFeature(key, enabled);
            }
            updateDevicePreferenceList();
        }
    };

    public static class UsbSerialSettings {

        public static final String PREF_USB_SERIAL_AUTO_BAUDRATE_ENTRY = "auto";

        private final PreferenceScreen mSettingsPref;
        private final ListPreference mBaudratePref, mDataBitsPref, mParityPref, mStopBitsPref;

        public UsbSerialSettings(PreferenceGroup rootScreen) {
            mSettingsPref = (PreferenceScreen)rootScreen.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_SETTINGS);

            mBaudratePref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_BAUDRATE);
            mDataBitsPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_DATA_BITS);
            mParityPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_PARITY);
            mStopBitsPref = (ListPreference)mSettingsPref.findPreference(UsbGpsProviderService.PREF_USB_SERIAL_STOP_BITS);
        }


        public boolean isSerialSettingsPref(String key) {
            return (UsbGpsProviderService.PREF_USB_SERIAL_BAUDRATE.equals(key)
                    || UsbGpsProviderService.PREF_USB_SERIAL_DATA_BITS.equals(key)
                    || UsbGpsProviderService.PREF_USB_SERIAL_PARITY.equals(key)
                    || UsbGpsProviderService.PREF_USB_SERIAL_STOP_BITS.equals(key)
                    );
        }

        public void updateSummaries() {
            final SerialLineConfiguration serialConf;
            serialConf = readConf(mSettingsPref.getSharedPreferences());

            mSettingsPref.setSummary(serialConf.toString(mSettingsPref.getContext().getResources()));
            mBaudratePref.setSummary(mBaudratePref.getEntry());
            mDataBitsPref.setSummary(mDataBitsPref.getEntry());
            mParityPref.setSummary(mParityPref.getEntry());
            mStopBitsPref.setSummary(mStopBitsPref.getEntry());
        }

        public static SerialLineConfiguration readConf(SharedPreferences prefs) {
            final SerialLineConfiguration serialConf;
            final String baudrate, dataBits, parity, stopBits;

            serialConf = new SerialLineConfiguration();

            baudrate = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_BAUDRATE, null);
            dataBits = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_DATA_BITS, null);
            parity = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_PARITY, null);
            stopBits = prefs.getString(UsbGpsProviderService.PREF_USB_SERIAL_STOP_BITS, null);

            if (baudrate != null) {
                if (PREF_USB_SERIAL_AUTO_BAUDRATE_ENTRY.equals(baudrate)) {
                    serialConf.setAutoBaudrateDetection(true);
                }else {
                    serialConf.setBaudrate(Integer.valueOf(baudrate), false);
                }
            }

            if (dataBits != null) {
                serialConf.setDataBits(Integer.valueOf(dataBits));
            }


            if (parity != null) {
                serialConf.setParity(Parity.valueOfChar(parity.charAt(0)));
            }

            if (stopBits != null) {
                serialConf.setStopBits(StopBits.valueOfString(stopBits));
            }

            return serialConf;
        }

    }

}
