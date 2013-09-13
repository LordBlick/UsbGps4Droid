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

import org.broeuschmeul.android.gps.usb.AutobaudTask;
import org.broeuschmeul.android.gps.usb.SerialLineConfiguration;
import org.broeuschmeul.android.gps.usb.UsbSerialController;
import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbControllerException;
import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbSerialInputStream;
import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbSerialOutputStream;
import org.broeuschmeul.android.gps.usb.UsbUtils;
import org.broeuschmeul.android.gps.usb.provider.MockLocationProvider.Status;

import proguard.annotation.Keep;

import java.io.IOException;
import java.io.OutputStream;

import javax.annotation.concurrent.GuardedBy;

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

    private final Context mContext;
    final UsbReceiver mUsbReceiver;
    private Callbacks mCallbacks;
    private MockLocationProvider mLocationProvider;

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

    public void setSerialLineConfiguration(final SerialLineConfiguration conf) {
        mUsbReceiver.setSerialLineConfigutation(conf);
    }

    public SerialLineConfiguration getSerialLineConfiguration() {
        return mUsbReceiver.getSerialLineConfiguration();
    }

    public void setCallbacks(Callbacks callbacks) {
        if (callbacks == null) throw new IllegalStateException();
        mCallbacks = callbacks;
    }

    private class UsbReceiver {

        final String ACTION_USB_PERMISSION = UsbReceiver.class.getName() + ".USB_PERMISSION";

        @GuardedBy("this")
        private final SerialLineConfiguration mSerialLineConfiguration;

        private UsbManager mUsbManager;

        final ConditionVariable mIsUsbDeviceReadyCondvar;

        @GuardedBy("this")
        private volatile UsbServiceThread mServiceThread;

        public UsbReceiver() {
            this.mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);

            mSerialLineConfiguration = new SerialLineConfiguration();
            mIsUsbDeviceReadyCondvar = new ConditionVariable(false);

            if (mUsbManager == null) throw new IllegalStateException("USB not available");
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

        public synchronized void setSerialLineConfigutation(final SerialLineConfiguration conf) {
            this.mSerialLineConfiguration.set(conf);
        }

        public synchronized SerialLineConfiguration getSerialLineConfiguration() {
            return new SerialLineConfiguration(mSerialLineConfiguration);
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

            mServiceThread.setController(controller);

        }

        private class UsbServiceThread extends Thread {

            /* mObject is used by native code, do not remove or rename */
            @Keep
            protected long mObject;

            private UsbSerialInputStream mInputStream;
            private UsbSerialOutputStream mOutputStream;

            private int mConnectionState;

            @GuardedBy("this")
            private volatile boolean cancelRequested;

            private final ConditionVariable mIsControllerSet;

            @GuardedBy("this")
            private volatile UsbSerialController mUsbController;

            @GuardedBy("this")
            private volatile AutobaudTask mAutobaudThread;

            private final Location mReportedLocation = new Location("");
            private final Bundle mReportedLocationBundle = new Bundle(1);

            public UsbServiceThread() {
                mInputStream = null;
                mOutputStream = null;
                mConnectionState = STATE_IDLE;
                cancelRequested = false;
                mUsbController = null;
                mAutobaudThread = null;
                mIsControllerSet = new ConditionVariable(false);
                native_create();
            }

            public synchronized void setController(UsbSerialController controller) {
                if (mUsbController != null) {
                    mIsControllerSet.close();
                    mUsbController.detach();
                }
                mUsbController = controller;
                if (controller != null) mIsControllerSet.open();
            }

            public synchronized UsbSerialController getController() {
                return mUsbController;
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

            public StatsNative getStats() {
                StatsNative dst = new StatsNative();
                synchronized (dst) {
                    native_get_stats(dst);
                }
                return dst;
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
                        startInitBaudrate();
                        native_read_loop(mInputStream, mOutputStream);
                        throwIfCancelRequested();

                        setState(STATE_RECONNECTING);
                        mCallbacks.onConnectionLost();
                    }
                }catch(CancelRequestedException cre) {
                }finally {
                    synchronized(this) {
                        if (mAutobaudThread != null) {
                            mAutobaudThread.interrupt();
                        }
                    }
                }
            }

            @Override
            protected void finalize() throws Throwable {
                native_destroy();
                super.finalize();
            }

            // Called from native code
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
                    if (DBG) Log.v(TAG, "loc: null");
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

            // Called from native code
            @Keep
            void onGpsMessageReceived(java.nio.ByteBuffer buf, int start, int size, int type) {
                if (DBG) Log.v(TAG, "msg " + type + " start/size: " + start + " " + size);
                synchronized (this) {
                    if (mAutobaudThread != null) {
                        mAutobaudThread.onGpsMessageReceived(buf, start, size, type);
                    }
                }
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

            private synchronized void throwIfCancelRequested() throws CancelRequestedException {
                if (cancelRequested) throw new CancelRequestedException();
            }

            private synchronized void startInitBaudrate() {
                if (mUsbController.getSerialLineConfiguration().isAutoBaudrateDetectionEnabled()) {
                    mAutobaudThread = new AutobaudTask(mContext, mUsbController, mAutobaudThreadCallbacks);
                    mAutobaudThread.start();
                }else {
                    mAutobaudThreadCallbacks.onAutobaudCompleted(true, mSerialLineConfiguration.getBaudrate());
                }
            }

            private final AutobaudTask.Callbacks mAutobaudThreadCallbacks = new AutobaudTask.Callbacks() {
                @Override
                public void onAutobaudCompleted(boolean isSuccessful, int baudrate) {
                    synchronized(UsbServiceThread.this) {
                        if (DBG) Log.v(TAG, "onAutobaudCompleted() " +
                                (isSuccessful ? "successful" : "failed") + " " + baudrate);
                        mAutobaudThread = null;
                    }
                    native_msg_rcvd_cb(false);
                    if (!isSuccessful) {
                        // XXX: report error
                        UsbServiceThread.this.cancel();
                    }
                }
            };

            private void connect() throws UsbControllerException, CancelRequestedException {

                mIsControllerSet.block();

                synchronized(UsbReceiver.this) {
                    synchronized (this) {
                        throwIfCancelRequested();
                        if (mUsbController == null) throw new UsbControllerException("");

                        if (DBG) Log.v(TAG, "attach(). "+ mUsbController.getSerialLineConfiguration().toString());
                        mUsbController.setSerialLineConfiguration(mSerialLineConfiguration);
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

            private class CancelRequestedException extends Exception {
                private static final long serialVersionUID = 1L;
            }

            private native void native_create();
            private native void native_read_loop(UsbSerialInputStream inputStream, UsbSerialOutputStream outputStream);
            private native void native_destroy();
            private native void native_get_stats(StatsNative dst);
            private native synchronized void native_msg_rcvd_cb(boolean activate);
        }

    }

    static {
        System.loadLibrary("usbconverter");
    }


}
