package org.broeuschmeul.android.gps.usb.provider;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.ConditionVariable;
import android.util.Log;

import org.broeuschmeul.android.gps.usb.UsbSerialController;
import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbControllerException;
import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbSerialInputStream;
import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbSerialOutputStream;
import org.broeuschmeul.android.gps.usb.UsbUtils;
import org.broeuschmeul.android.gps.usb.provider.MockLocationProvider.Status;

import proguard.annotation.Keep;

import java.io.IOException;
import java.io.OutputStream;

public class UsbGpsConverter {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = UsbGpsConverter.class.getSimpleName();

    // Constants that indicate the current connection state
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_WAITING = 3;
    public static final int STATE_RECONNECTING = 4;

    public static final String ACTION_USB_DEVICE_ATTACHED = "org.broeuschmeul.android.gps.usb.provider.ACTION_USB_DEVICE_ATTACHED";

    public static final int RECONNECT_TIMEOUT_MS = 2000;

    /* mObject is used by native code, do not remove or rename */
    @Keep
    protected long mObject;


    private final Context mContext;
    final UsbReceiver mUsbReceiver;
    private Callbacks mCallbacks;
    private MockLocationProvider mLocationProvider;

    private final Location mReportedLocation = new Location("");
    private final Bundle mReportedLocationBundle = new Bundle(1);

    public interface Callbacks {

        public void onConnected();

        public void onStopped();

        public void onConnectionLost();

    }

    private static final Callbacks sDummyCallbacks = new Callbacks() {
        @Override
        public void onConnected() {}
        @Override
        public void onStopped() {}
        @Override
        public void onConnectionLost() {}
    };

    public UsbGpsConverter(Context serviceContext) {
        this(serviceContext, new MockLocationProvider());
    }

    public UsbGpsConverter(Context serviceContext, MockLocationProvider provider) {
        mContext = serviceContext;
        mLocationProvider = provider;
        mUsbReceiver = new UsbReceiver();
        mCallbacks = sDummyCallbacks;
        native_create();
    }

    @Override
    protected void finalize() throws Throwable {
        native_destroy();
        super.finalize();
    }

    public void start() {
        final LocationManager lm = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
        mLocationProvider.attach(lm);
        mUsbReceiver.start();
    }

    public void stop() {
        mUsbReceiver.stop();
        mLocationProvider.detach();
    }

    public boolean isActive() {
        return mLocationProvider.isAttached();
    }

    public void setLocationProvider(MockLocationProvider provider) {
        if (provider == null) throw new NullPointerException();
        mLocationProvider = provider;
    }

    public final MockLocationProvider getLocationProvider() {
        return mLocationProvider;
    }

    public void setBaudRate(int baudrate) {
        mUsbReceiver.setBaudRate(baudrate);
    }

    public int getBaudRate() {
        return mUsbReceiver.getBaudRate();
    }

    public void setCallbacks(Callbacks callbacks) {
        if (callbacks == null) throw new IllegalStateException();
        mCallbacks = callbacks;
    }

    /**
     * Called from native code
     */
    @Keep
    synchronized void reportLocation(
            long time,
            double latitude,
            double longitude,
            double altitude,
            float accuracy,
            float bearing,
            float speed,
            int satellites,
            boolean isValid,
            boolean hasAccuracy,
            boolean hasAltitude,
            boolean hasBearing,
            boolean hasSpeed
            ) {

        if (!isValid) {
            mLocationProvider.setLocation(null);
            return;
        }

        mReportedLocation.reset();
        mReportedLocation.setTime(time);
        mReportedLocation.setLatitude(latitude);
        mReportedLocation.setLongitude(longitude);
        if (hasAltitude) {
            mReportedLocation.setAltitude(altitude);
        }
        if (hasAccuracy) {
            mReportedLocation.setAccuracy(accuracy);
        }
        if (hasBearing) {
            mReportedLocation.setBearing(bearing);
        }
        if (hasSpeed) {
            mReportedLocation.setSpeed(speed);
        }

        if (satellites > 0) {
            mReportedLocationBundle.putInt("satellites", satellites);
            mReportedLocation.setExtras(mReportedLocationBundle);
        }

        mLocationProvider.setLocation(mReportedLocation);
        if (DBG) Log.v(TAG, "loc: " + mReportedLocation);
    }

    private class UsbReceiver {

        final String ACTION_USB_PERMISSION = UsbReceiver.class.getName() + ".USB_PERMISSION";

        private int mBaudrate = UsbSerialController.DEFAULT_BAUDRATE;

        private UsbManager mUsbManager;

        final ConditionVariable mIsUsbDeviceReadyCondvar;

        private UsbServiceThread mServiceThread;

        public UsbReceiver() {
            this.mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
            mIsUsbDeviceReadyCondvar = new ConditionVariable(false);

            if (mUsbManager == null) throw new IllegalStateException("USB not available");
        }

        public synchronized void start() {
            final IntentFilter f;
            f = new IntentFilter();
            f.addAction(ACTION_USB_DEVICE_ATTACHED);
            f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            f.addAction(ACTION_USB_PERMISSION);

            mContext.registerReceiver(mUsbStateListener, f);

            mServiceThread = new UsbServiceThread();
            mServiceThread.start();

            try {
                final UsbDevice d = UsbUtils.findSupportedDevices(mUsbManager).get(0);
                requestPermission(d);
            }catch (IndexOutOfBoundsException ignore) { }

        }

        public synchronized void setBaudRate(int baudrate) {
            this.mBaudrate = baudrate;
        }

        public synchronized int getBaudRate() {
            return this.mBaudrate;
        }


        public boolean isDeviceReady() {
            return mIsUsbDeviceReadyCondvar.block(1);
        }

        public void waitDevice() {
            mIsUsbDeviceReadyCondvar.block();
        }

        public synchronized void write(byte[] buffer, int offset, int count) throws IOException {
            if (mServiceThread == null) throw new IOException("not connected");
            mServiceThread.write(buffer, offset, count);
        }

        public synchronized void stop() {
            mContext.unregisterReceiver(mUsbStateListener);
            mServiceThread.cancel();
            mServiceThread = null;
            mIsUsbDeviceReadyCondvar.open();
        }


        private void requestPermission(UsbDevice d) {
            if (DBG) Log.d(TAG, "requestPermission() device=" + d.toString());
            final PendingIntent premissionIntent = PendingIntent.getBroadcast(mContext,
                    0, new Intent(ACTION_USB_PERMISSION), 0);
            mUsbManager.requestPermission(d, premissionIntent);
        }

        void onUsbDeviceAttached(UsbDevice device) {
            if (DBG) Log.d(TAG, "onUsbDeviceAttached() device=" + device.toString());

            if (UsbUtils.probeDevice(mUsbManager, device) != null) {
                requestPermission(device);
            }
        }

        synchronized void onUsbDeviceDetached(UsbDevice device) {

            if (DBG) Log.d(TAG, "onUsbDeviceDetached() device=" + device.toString());

            UsbSerialController controller = mServiceThread.getController();

            if (controller == null) return;
            if (!device.equals(controller.getDevice())) return;

            mServiceThread.setController(null);
        }

        synchronized void onUsbPermissionGranted(UsbDevice device) {
            if (DBG) Log.d(TAG, "onUsbPermissionGranted() device=" + device.toString());
            UsbSerialController controller = mServiceThread.getController();

            if (controller != null) return;

            controller = UsbUtils.probeDevice(mUsbManager, device);
            if (controller == null) return;

            controller.setBaudRate(mBaudrate);

            mServiceThread.setController(controller);

        }

        private final BroadcastReceiver mUsbStateListener = new BroadcastReceiver() {

            @Override
            public void onReceive(Context context, Intent intent) {
                UsbDevice device;
                String action = intent.getAction();
                Log.v(TAG, "Received intent " + action);

                if (action.equals(ACTION_USB_DEVICE_ATTACHED)) {
                    device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    onUsbDeviceAttached(device);
                }else if (action.equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    onUsbDeviceDetached(device);
                }else if (action.equals(ACTION_USB_PERMISSION)) {
                    boolean granted;
                    device = (UsbDevice) intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    if (granted) {
                        onUsbPermissionGranted(device);
                    }
                }else {
                    Log.e(TAG, "Unknown action " + action);
                }
            }
        };

        private class UsbServiceThread extends Thread {

            private UsbSerialInputStream mInputStream;
            private UsbSerialOutputStream mOutputStream;

            private int mConnectionState;
            private volatile boolean cancelRequested;

            private UsbSerialController mUsbController;

            private final ConditionVariable serialControllerSet;

            public UsbServiceThread() {
                mInputStream = null;
                mOutputStream = null;
                mConnectionState = STATE_IDLE;
                cancelRequested = false;
                mUsbController = null;
                serialControllerSet = new ConditionVariable(false);
            }

            public synchronized void setController(UsbSerialController controller) {
                if (mUsbController != null) {
                    serialControllerSet.close();
                    mUsbController.detach();
                }
                mUsbController = controller;
                if (controller != null) serialControllerSet.open();
            }

            public synchronized UsbSerialController getController() {
                return mUsbController;
            }

            private synchronized void setState(int state) {
                int oldState = mConnectionState;
                mConnectionState = state;
                if (DBG) Log.d(TAG, "setState() " + oldState + " -> " + state);

                if (mConnectionState == STATE_CONNECTED) {
                    mIsUsbDeviceReadyCondvar.open();
                    mLocationProvider.setDeviceStatus(Status.TEMPORARILY_UNAVAILABLE);
                } else {
                    mIsUsbDeviceReadyCondvar.close();
                    mLocationProvider.setDeviceStatus(Status.OUT_OF_SERVICE);
                }
            }

            public synchronized void cancel() {
                cancelRequested = true;
                mCallbacks.onStopped();
                setController(null);
            }

            /**
             * Write to the connected OutStream.
             * @param buffer  The bytes to write
             */
            public void write(byte[] buffer, int offset, int count) throws IOException {
                OutputStream os;
                synchronized(this) {
                    if (mConnectionState != STATE_CONNECTED) {
                        Log.e(TAG, "write() error: not connected");
                        return;
                    }
                    os = mOutputStream;
                }
                os.write(buffer, offset, count);
            }

            private synchronized void throwIfCancelRequested() throws CancelRequestedException {
                if (cancelRequested) throw new CancelRequestedException();
            }

            private void connect() throws UsbControllerException, CancelRequestedException {

                serialControllerSet.block();

                synchronized(UsbReceiver.this) {
                    synchronized (this) {
                        throwIfCancelRequested();
                        if (mUsbController == null) throw new UsbControllerException("");

                        if (DBG) Log.v(TAG, "attach(). baudrate: "+ mUsbController.getBaudRate());
                        mUsbController.attach();
                        mInputStream = mUsbController.getInputStream();
                        mOutputStream = mUsbController.getOutputStream();
                    }
                }
                return;
            }

            private void connectLoop() throws CancelRequestedException {

                if (DBG) Log.v(TAG, "connectLoop()");

                while(true) {
                    try {
                        connect();
                        return;
                    }catch (UsbControllerException e) {
                        synchronized(this) {
                            throwIfCancelRequested();
                            setState(STATE_RECONNECTING);
                            try {
                                wait(RECONNECT_TIMEOUT_MS);
                            } catch(InterruptedException ie) {
                                throwIfCancelRequested();
                            }
                        }
                    }
                }
            }


            private void transferDataLoop() throws CancelRequestedException {
                native_read_loop(mInputStream, mOutputStream);
                throwIfCancelRequested();
            }

            @Override
            public void run() {
                Log.i(TAG, "BEGIN UsbToLocalSocket-USB");
                setName("UsbToLocalSocket-USB");
                try {
                    setState(STATE_CONNECTING);
                    while (true) {
                        throwIfCancelRequested();
                        connectLoop();

                        setState(STATE_CONNECTED);
                        transferDataLoop();

                        setState(STATE_RECONNECTING);
                        mCallbacks.onConnectionLost();
                    }
                }catch(CancelRequestedException cre) {}
            }
        }

        private class CancelRequestedException extends Exception {
            private static final long serialVersionUID = 1L;
        }
    }

    private native void native_create();
    private native void native_read_loop(UsbSerialInputStream inputStream, UsbSerialOutputStream outputStream);
    private native void native_destroy();

    static {
        System.loadLibrary("usbconverter");
    }


}
