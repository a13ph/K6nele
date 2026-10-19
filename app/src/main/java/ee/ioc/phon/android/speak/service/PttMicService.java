package ee.ioc.phon.android.speak.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import ee.ioc.phon.android.speak.R;

/**
 * Gives Kõnele the microphone while push-to-talk records with the keyboard panel hidden.
 * <p>
 * Android lets an app record only while it is in the foreground. The keyboard counts as such only
 * while its panel is on screen, so a take with the panel hidden is refused (error 9). A foreground
 * service of type microphone lifts this, but only if it was started by a foreground app or by
 * root, the system or the shell. The root helper that watches the volume key starts this service
 * before PTT_START (am start-foreground-service) and stops it after the take (am stop-service).
 * The service is not exported; root can start it anyway.
 * <p>
 * It runs in the process of the recognition services, and stops itself after MAX_MS in case the
 * helper never stops it.
 */
public class PttMicService extends Service {

    private static final String CHANNEL_ID = "ptt_mic";
    private static final int NOTIFICATION_ID = 7001;
    // Kõnele's own recording cap (keyAutoStopAfterTime), plus time to transcribe.
    private static final long MAX_MS = 330_000;
    // Logged whatever Log.DEBUG says, so that a failed take can be read from logcat.
    private static final String PTT_TAG = "k6nele-ptt";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mStop = this::stopSelf;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, makeNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, makeNotification());
        }
        android.util.Log.i(PTT_TAG, "mic service in the foreground");
        mHandler.removeCallbacks(mStop);
        mHandler.postDelayed(mStop, MAX_MS);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mStop);
        android.util.Log.i(PTT_TAG, "mic service stopped");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification makeNotification() {
        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                    getString(R.string.labelApp), NotificationManager.IMPORTANCE_MIN);
            nm.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this);
        }
        return builder
                .setSmallIcon(R.drawable.ic_baseline_mic_24)
                .setContentTitle(getString(R.string.labelApp))
                .setOngoing(true)
                .build();
    }
}
