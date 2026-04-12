package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import androidx.core.app.NotificationCompat;

public class ByeDpiService extends Service {
    private static final int NOTIFICATION_ID = 9001;
    private static final String CHANNEL_ID = "byedpi_service";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification());
        ByeDpiRunner.getInstance().start(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY; // Не перезапускать автоматически системой
    }

    @Override
    public void onDestroy() {
        ByeDpiRunner.getInstance().stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "ByeDPI Прокси",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = (NotificationManager) getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ByeDPI работает")
            .setContentText("Прокси 127.0.0.1:1081 активен")
            .setSmallIcon(R.drawable.ic_player)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build();
    }
}
