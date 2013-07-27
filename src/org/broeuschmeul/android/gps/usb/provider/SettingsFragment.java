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
import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.res.Resources;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.preference.EditTextPreference;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.util.Log;

import java.util.HashMap;

/**
 * A SettingsFragment Class used to configure, start and stop the NMEA tracker service.
 *
 * @author Herbert von Broeuschmeul
 *
 */
public class SettingsFragment extends PreferenceFragment {

    private static final boolean DBG = BuildConfig.DEBUG & false;
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

    private String deviceName = "";

    private UsbManager usbManager;

    private ListPreference mGpsDevicePreference, mDeviceSpeedPreference;
    private EditTextPreference mMockGpsNamePreference, mConnectionRetriesPreference;
    private Preference mTrackRecordingPreference;
    private PreferenceScreen mGpsLocationProviderPreference;

    @Override
    public void onAttach(Activity activity) {
        // TODO Auto-generated method stub
        super.onAttach(activity);

        if (!(activity instanceof Callbacks)) {
            throw new IllegalStateException("Activity must implement fragment's callbacks.");
        }

        mCallbacks = (Callbacks)activity;
        usbManager = (UsbManager) activity.getSystemService(Context.USB_SERVICE);
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.pref);

        mGpsDevicePreference = (ListPreference)findPreference(UsbGpsProviderService.PREF_GPS_DEVICE);
        mDeviceSpeedPreference = (ListPreference)findPreference(UsbGpsProviderService.PREF_GPS_DEVICE_SPEED);
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
        mGpsDevicePreference = null;
        mGpsDevicePreference = null;
        mDeviceSpeedPreference = null;
        mTrackRecordingPreference = null;
        mMockGpsNamePreference = null;
        mConnectionRetriesPreference = null;
        mGpsLocationProviderPreference = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mCallbacks = sDummyCallbacks;
        usbManager = null;
    }

    private void updateDevicePreferenceSummary(){
        final SharedPreferences sharedPref = getPreferenceManager().getSharedPreferences();

        // update USB device summary
        String defaultDeviceName = "";
        if (! usbManager.getDeviceList().isEmpty()){
            defaultDeviceName = usbManager.getDeviceList().keySet().iterator().next();
        }
        deviceName = sharedPref.getString(UsbGpsProviderService.PREF_GPS_DEVICE, defaultDeviceName);
        String deviceDisplayedName = "";
        if (! usbManager.getDeviceList().isEmpty() && usbManager.getDeviceList().get(deviceName) != null){
            deviceDisplayedName = usbManager.getDeviceList().get(deviceName).getDeviceName();
        } else if ((usbManager.getDeviceList().size() == 1) && (usbManager.getDeviceList().get(defaultDeviceName) != null)){
            deviceDisplayedName = usbManager.getDeviceList().get(defaultDeviceName).getDeviceName();
            deviceName = defaultDeviceName;
            mGpsDevicePreference.setValue(defaultDeviceName);
        }
        mGpsDevicePreference.setSummary(getString(R.string.pref_gps_device_summary, deviceDisplayedName));
        mDeviceSpeedPreference.setSummary(getString(R.string.pref_gps_device_speed_summary, sharedPref.getString(UsbGpsProviderService.PREF_GPS_DEVICE_SPEED, getString(R.string.defaultGpsDeviceSpeed))));
    }

    private void updateDevicePreferenceList(){
        final SharedPreferences sharedPref = getPreferenceManager().getSharedPreferences();
        final Resources resources = getResources();
        final String mockProviderName;

        // update bluetooth device summary
        updateDevicePreferenceSummary();
        // update bluetooth device list
        HashMap<String, UsbDevice> connectedUsbDevices = usbManager.getDeviceList();
        String[] entryValues = new String[connectedUsbDevices.size()];
        String[] entries = new String[connectedUsbDevices.size()];
        int i = 0;
        // Loop through usb devices
        for (String name : connectedUsbDevices.keySet()) {
            // Add the name and address to the ListPreference enties and entyValues
            UsbDevice device = connectedUsbDevices.get(name);
            if (DBG) Log.v(TAG, "device: " + device);
            for (int k=0; k < device.getInterfaceCount(); k++){
                UsbInterface usbIntf = device.getInterface(k);
                for (int j=0; j < usbIntf.getEndpointCount(); j++){
                    UsbEndpoint endPt = usbIntf.getEndpoint(j);
                    if (DBG) Log.v(TAG, "endPt: : "+endPt + " type: "+endPt.getType()+ " dir: "+endPt.getDirection() );
                }
            }
            entryValues[i] = device.getDeviceName();
            entries[i] = name;
            i++;
        }
        mGpsDevicePreference.setEntryValues(entryValues);
        mGpsDevicePreference.setEntries(entries);

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
            String s = getString(R.string.pref_gps_location_provider_summary);
            mGpsLocationProviderPreference.setSummary(s);
        } else {
            String s = getString(R.string.pref_mock_gps_name_summary, mockProviderName);
            mGpsLocationProviderPreference.setSummary(s);
        }
    }

    private final OnSharedPreferenceChangeListener mOnSharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            if (UsbGpsProviderService.PREF_START_GPS_PROVIDER.equals(key)){
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
            } else if (UsbGpsProviderService.PREF_GPS_DEVICE.equals(key)){
                updateDevicePreferenceSummary();
            } else if (UsbGpsProviderService.PREF_GPS_DEVICE_SPEED.equals(key)){
                updateDevicePreferenceSummary();
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

}
