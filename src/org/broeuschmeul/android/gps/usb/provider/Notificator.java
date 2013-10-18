package org.broeuschmeul.android.gps.usb.provider;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.v4.content.LocalBroadcastManager;
import android.widget.Toast;

import java.lang.reflect.InvocationTargetException;

public class Notificator {

    public static final int FOREGROUND_NOTIFICATION_ID = 1;

    private final Context mServiceContext;
    private final NotificationManager mNoficationManager;

    public Notificator(Context serviceContext) {
        mServiceContext = serviceContext;
        mNoficationManager = (NotificationManager) serviceContext.getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private Notification.Builder createNotificationBuilder() {
        final PendingIntent contentIntent;

        contentIntent = PendingIntent.getActivity(mServiceContext, 0,
                new Intent(mServiceContext, SettingsActivity.class), 0);

        return new Notification.Builder(mServiceContext)
            .setContentIntent(contentIntent)
            .setOngoing(true);
    }

    @SuppressWarnings("deprecation")
    public Notification createForegroundNotification() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_gps_provider_started))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_please_plug_in_gps_receiver))
                .setSmallIcon(R.drawable.ic_stat_notify);

        return builder.getNotification();
    }

    public void onServiceStarted() {
        LocalBroadcastManager.getInstance(mServiceContext).registerReceiver(mBroadcastReceiver, createIntentFilter());
    }

    public void onServiceStopped() {
        LocalBroadcastManager.getInstance(mServiceContext).unregisterReceiver(mBroadcastReceiver);
    }

    private IntentFilter createIntentFilter() {
        IntentFilter f = new IntentFilter();
        f.addAction(UsbGpsConverter.ACTION_USB_ATTACHED);
        f.addAction(UsbGpsConverter.ACTION_USB_DETACHED);
        f.addAction(UsbGpsConverter.ACTION_AUTOCONF_STARTED);
        f.addAction(UsbGpsConverter.ACTION_AUTOCONF_STOPPED);
        f.addAction(UsbGpsConverter.ACTION_VALID_GPS_MESSAGE_RECEIVED);
        f.addAction(UsbGpsConverter.ACTION_VALID_LOCATION_RECEIVED);

        return f;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (UsbGpsConverter.ACTION_USB_ATTACHED.equals(action)) {
                onUsbAttached();
            }else if (UsbGpsConverter.ACTION_USB_DETACHED.equals(action)) {
                onUsbDetached();
            }else if (UsbGpsConverter.ACTION_AUTOCONF_STARTED.equals(action)) {
                onAutoconfStarted();
            }else if (UsbGpsConverter.ACTION_AUTOCONF_STOPPED.equals(action)) {
                onAutoconfStopped();
            }else if (UsbGpsConverter.ACTION_VALID_GPS_MESSAGE_RECEIVED.equals(action)) {
                onValidGpsMessageReceived();
            }else if (UsbGpsConverter.ACTION_VALID_LOCATION_RECEIVED.equals(action)) {
                onValidLocationReceived();
            }else {
                throw new IllegalStateException();
            }
        }
    };

    @SuppressWarnings("deprecation")
    private void onUsbAttached() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_usb_gps_attached))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_usb_gps_attached))
                .setSmallIcon(R.drawable.ic_stat_notify);

        mNoficationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.getNotification());

        Toast.makeText(mServiceContext,
                mServiceContext.getText(R.string.msg_usb_gps_attached),
                Toast.LENGTH_SHORT).show();
    }

    private void onUsbDetached() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_usb_gps_detached))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_lost_connection))
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setVibrate(new long[] {0, 400} )
                ;

        mNoficationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.getNotification());

        Toast.makeText(mServiceContext,
                mServiceContext.getText(R.string.msg_lost_connection_to_usb_receiver),
                Toast.LENGTH_SHORT).show();
    }

    private void onAutoconfStarted() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_autodetect))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_autodetect))
                .setProgress(0, 0, true)
                .setSmallIcon(R.drawable.ic_stat_notify)
                ;

        mNoficationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.getNotification());
    }

    private void onAutoconfStopped() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_running))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_autodetect_complete))
                .setSmallIcon(R.drawable.ic_stat_notify)
                ;

        try {
            builder.getClass().getMethod("setUsesChronometer", boolean.class).invoke(builder, true);
        } catch (IllegalArgumentException ignore) {
        } catch (IllegalAccessException ignore) {
        } catch (InvocationTargetException ignore) {
        } catch (NoSuchMethodException ignore) {
        }

        mNoficationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.getNotification());
    }

    private void onValidGpsMessageReceived() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_running))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_running))
                .setSmallIcon(R.drawable.ic_stat_notify)
                ;
        try {
            builder.getClass().getMethod("setUsesChronometer", boolean.class).invoke(builder, true);
        } catch (IllegalArgumentException ignore) {
        } catch (IllegalAccessException ignore) {
        } catch (InvocationTargetException ignore) {
        } catch (NoSuchMethodException ignore) {
        }

        mNoficationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.getNotification());
    }

    private void onValidLocationReceived() {
        final Notification.Builder builder;

        builder = createNotificationBuilder()
                .setContentTitle(mServiceContext.getText(R.string.foreground_notification_title_running))
                .setContentText(mServiceContext.getText(R.string.foreground_notification_text_has_known_location))
                .setSmallIcon(R.drawable.ic_stat_notify)
                ;

        try {
            builder.getClass().getMethod("setUsesChronometer", boolean.class).invoke(builder, true);
        } catch (IllegalArgumentException ignore) {
        } catch (IllegalAccessException ignore) {
        } catch (InvocationTargetException ignore) {
        } catch (NoSuchMethodException ignore) {
        }

        mNoficationManager.notify(FOREGROUND_NOTIFICATION_ID, builder.getNotification());
    }
}
