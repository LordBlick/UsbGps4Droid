package org.broeuschmeul.android.gps.usb;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.ConditionVariable;
import android.util.Log;

import org.broeuschmeul.android.gps.usb.UsbSerialController.UsbControllerException;
import org.broeuschmeul.android.gps.usb.provider.BuildConfig;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class UsbGpsManager {

    private static final boolean DBG = BuildConfig.DEBUG & true;
    static final String TAG = UsbGpsManager.class.getSimpleName();

    // Constants that indicate the current connection state
    public static final int STATE_IDLE = 0;
    public static final int STATE_CONNECTING = 1;
    public static final int STATE_CONNECTED = 2;
    public static final int STATE_WAITING = 3;
    public static final int STATE_RECONNECTING = 4;

    public static final String ACTION_USB_DEVICE_ATTACHED = "ru0xdc.rtkgps.UsbToRtklib.ACTION_USB_DEVICE_ATTACHED";

    final UsbReceiver mUsbReceiver;

    private Callbacks mCallbacks;

    public static final int RECONNECT_TIMEOUT_MS = 2000;

    private static final Callbacks sDummyCallbacks = new Callbacks() {
        @Override
        public void onConnected() {}
        @Override
        public void onStopped() {}
        @Override
        public void onConnectionLost() {}
    };


    public interface Callbacks {

        public void onConnected();

        public void onStopped();

        public void onConnectionLost();

    }

    public UsbGpsManager(Context serviceContext) {
        mUsbReceiver = new UsbReceiver(serviceContext);
        mCallbacks = sDummyCallbacks;
    }

    public void start() {
        mUsbReceiver.start();
    }

    public void stop() {
        mUsbReceiver.stop();
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

    private class UsbReceiver {

        final String ACTION_USB_PERMISSION = UsbReceiver.class.getName() + ".USB_PERMISSION";

        private int mBaudrate = UsbSerialController.DEFAULT_BAUDRATE;

        private Context mContext;

        private UsbManager mUsbManager;

        final ConditionVariable mIsUsbDeviceReadyCondvar;

        private UsbServiceThread mServiceThread;

        public UsbReceiver(Context pContext) {

            this.mContext = pContext;
            this.mUsbManager = (UsbManager) pContext.getSystemService(Context.USB_SERVICE);
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

            private InputStream mInputStream;
            private OutputStream mOutputStream;

            private int mConnectionState;
            private volatile boolean cancelRequested;

            private UsbSerialController mUsbController;

            private final ConditionVariable serialControllerSet;

            public UsbServiceThread() {
                mInputStream = UsbUtils.DummyInputStream.instance;
                mOutputStream = UsbUtils.DummyOutputStream.instance;
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

                if (mConnectionState == STATE_CONNECTED)
                    mIsUsbDeviceReadyCondvar.open();
                else {
                    mIsUsbDeviceReadyCondvar.close();
                    //mLocalSocketThread.disconnect();
                }
            }

            public synchronized void cancel() {
                cancelRequested = true;
                mCallbacks.onStopped();
                if (mUsbController != null) {
                    mUsbController.detach();
                    mUsbController=null;
                }
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
                int rcvd;
                final byte buf[] = new byte[4096];

                try {
                    while(true) {
                        rcvd =  mInputStream.read(buf, 0, buf.length);
                        if (rcvd >= 0) {
                            /*
                            try {
                                //mLocalSocketThread.write(buf, 0, rcvd);
                            }catch (IOException e) {
                                // TODO
                                e.printStackTrace();
                            }
                            */
                        }
                        if (rcvd < 0)
                            throw new IOException("EOF");
                    }
                }catch (IOException e) {
                    synchronized(this) {
                        if (mUsbController!=null) mUsbController.detach();
                        //mInputStream = RtklibLocalSocketThread.DummyInputStream.instance;
                        //mOutputStream = RtklibLocalSocketThread.DummyOutputStream.instance;
                        throwIfCancelRequested();
                    }
                }
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



}
