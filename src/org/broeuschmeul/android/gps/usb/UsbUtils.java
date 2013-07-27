/*
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

package org.broeuschmeul.android.gps.usb;

import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbControllerException;
import org.broeuschmeul.android.gps.usb.provider.BuildConfig;
import org.broeuschmeul.android.gps.usb.provider.R;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class UsbUtils {

    // Debugging
    private static final String TAG = UsbUtils.class.getSimpleName();
    private static final boolean DBG = BuildConfig.DEBUG & true;


    public static boolean isInUsbDeviceFilter(UsbDevice d, Resources appResources) {
        int type;
        XmlResourceParser parser;
        boolean match = false;

        parser = appResources.getXml(R.xml.device_filter);
        try {
            for (type=parser.getEventType();
                    !match && (type != XmlResourceParser.END_DOCUMENT);
                    type = parser.next()) {
                int count;
                int vendorId = -1;
                int productId = -1;
                int deviceClass = -1;
                int deviceSubclass = -1;
                int deviceProtocol = -1;


                if (type != XmlResourceParser.START_TAG) continue;
                if ("usb-device".equals(parser.getName())) continue;

                count = parser.getAttributeCount();
                for(int i=0; i<count; ++i) {
                    String name = parser.getAttributeName(i);
                    // All attribute values are ints
                    int value = Integer.parseInt(parser.getAttributeValue(i));

                    if ("vendor-id".equals(name)) {
                        vendorId = value;
                    } else if ("product-id".equals(name)) {
                        productId = value;
                    } else if ("class".equals(name)) {
                        deviceClass = value;
                    } else if ("subclass".equals(name)) {
                        deviceSubclass = value;
                    } else if ("protocol".equals(name)) {
                        deviceProtocol = value;
                    }
                }
                match = ((vendorId < 0)  || (d.getVendorId() == vendorId)
                        && ((productId < 0) || (d.getProductId() == productId) )
                        && ((deviceClass < 0) || (d.getDeviceClass() == deviceClass) )
                        && ((deviceSubclass < 0) || (d.getDeviceSubclass() == deviceSubclass))
                        && ((deviceProtocol < 0) || (d.getDeviceProtocol() == deviceProtocol))
                        );
            }
        } catch (NumberFormatException e) {
            e.printStackTrace();
        } catch (XmlPullParserException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }

        return match;
    }

    public static List<UsbDevice> findSupportedDevices(UsbManager usbManager) {
        final ArrayList<UsbDevice> supportedList;
        final HashMap<String, UsbDevice> deviceList;

        deviceList = usbManager.getDeviceList();
        supportedList = new ArrayList<UsbDevice>(deviceList.size());

        for (UsbDevice d: deviceList.values()) {
            if (probeDevice(usbManager, d) != null) {
                supportedList.add(d);
            }
        }
        return supportedList;
    }

    public static UsbSerialController probeDevice(UsbManager usbManager, UsbDevice d) {
        if (DBG) Log.d(TAG, "probeDevice() device=" + d.toString());
        try {
            final UsbPl2303Controller c = new UsbPl2303Controller(usbManager, d);
            return c;
        }catch(UsbControllerException ignore) { }

        try {
            final UsbAcmController c = new UsbAcmController(usbManager, d);
            return c;
        }catch (UsbControllerException ignore) {}

       return null;
    }

    static class DummyInputStream extends InputStream {

        public static final DummyInputStream instance = new DummyInputStream();

        private DummyInputStream() {}

        @Override
        public int read() throws IOException {
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return -1;
        }
    }

    static class DummyOutputStream extends OutputStream  {

        public static final DummyOutputStream instance = new DummyOutputStream();

        private DummyOutputStream() {}

        @Override
        public void write(int arg0) throws IOException {
        }

        @Override
        public void write(byte[] buffer, int offset, int count)
                throws IOException {
        }
    }


}
