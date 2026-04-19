package org.telegram.messenger;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import android.widget.Toast;
import androidx.core.app.NotificationCompat;
import org.telegram.tgnet.ConnectionsManager;

public class MengramProxyService extends Service {
    private static final String CHANNEL_ID = "MengramWssChannel";
    private static final int NOTIFICATION_ID = 101;
    public static volatile boolean isServiceRunning = false;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (isServiceRunning) return START_STICKY;
        isServiceRunning = true;

        AndroidUtilities.runOnUIThread(() ->
                Toast.makeText(getApplicationContext(), "СЕРВИС ЗАПУЩЕН", Toast.LENGTH_SHORT).show()
        );

        createNotificationChannel();

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mengram WSS")
                .setContentText("Запуск движка...")
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        new Thread(() -> {
            try {
                Log.d("MengramProxy", "Запуск движка...");
                
                SharedPreferences prefs = ApplicationLoader.applicationContext
                        .getSharedPreferences("mengram_settings", Context.MODE_PRIVATE);

                String rawSecret = prefs.getString("wss_secret", "");
                if (rawSecret == null || rawSecret.isEmpty()) {
                    rawSecret = TgWsProxy.generateSecret();
                    prefs.edit().putString("wss_secret", rawSecret).apply();
                }

                long result = TgWsProxy.runEngine(1443, rawSecret);

                if (result == 0) {
                    String finalSecret = TgWsProxy.getSecret();
                    Log.d("MengramProxy", "Движок готов. Secret: " + finalSecret);

                    AndroidUtilities.runOnUIThread(() -> {
                        Log.d("MengramProxy", "UI поток: начало применения прокси");
                        try {
                            SharedConfig.loadProxyList();
                            Log.d("MengramProxy", "loadProxyList — ок");

                            SharedConfig.ProxyInfo proxy = new SharedConfig.ProxyInfo(
                                    "127.0.0.1", 1443, "", "", finalSecret
                            );

                            SharedConfig.proxyList.clear();
                            SharedConfig.proxyList.add(0, proxy);
                            SharedConfig.currentProxy = proxy;
                            SharedConfig.saveProxyList();
                            Log.d("MengramProxy", "saveProxyList — ок");

                            SharedPreferences mainPrefs = MessagesController.getGlobalMainSettings();
                            mainPrefs.edit()
                                    .putBoolean("proxy_enabled", true)
                                    .putBoolean("proxy_enabled_calls", true)
                                    .putString("proxy_ip", "127.0.0.1")
                                    .putInt("proxy_port", 1443)
                                    .putString("proxy_user", "")
                                    .putString("proxy_pass", "")
                                    .putString("proxy_secret", finalSecret)
                                    .commit();
                            Log.d("MengramProxy", "proxy_enabled = true — ок");

                            NotificationCenter.getGlobalInstance()
                                    .postNotificationName(NotificationCenter.proxySettingsChanged);
                            Log.d("MengramProxy", "proxySettingsChanged — отправлен");

                            ConnectionsManager.setProxySettings(
                                    true, "127.0.0.1", 1443, "", "", finalSecret
                            );
                            Log.d("MengramProxy", "setProxySettings — ок");

                            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                                if (UserConfig.getInstance(a).isClientActivated()) {
                                    ConnectionsManager.getInstance(a).checkConnection();
                                    Log.d("MengramProxy", "checkConnection для аккаунта " + a);
                                }
                            }

                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                                        if (UserConfig.getInstance(a).isClientActivated()) {
                                            ConnectionsManager.getInstance(a).checkConnection();
                                        }
                                    }
                                    NotificationCenter.getGlobalInstance()
                                            .postNotificationName(NotificationCenter.didUpdateConnectionState);
                                } catch (Exception e) {
                                    Log.e("MengramProxy", "reconnect error", e);
                                }
                            }, 1500);

                            new Thread(() -> {
                                try {
                                    Thread.sleep(3000);
                                    Log.d("MengramProxy", "PORT CHECK 3s: " + isPortOpen("127.0.0.1", 1443, 1500));
                                    Log.d("MengramProxy", "STATS 3s: " + tgwsproxy.Tgwsproxy.getStats());

                                    Thread.sleep(5000);
                                    Log.d("MengramProxy", "PORT CHECK 8s: " + isPortOpen("127.0.0.1", 1443, 1500));
                                    Log.d("MengramProxy", "STATS 8s: " + tgwsproxy.Tgwsproxy.getStats());

                                    Thread.sleep(7000);
                                    Log.d("MengramProxy", "STATS 15s: " + tgwsproxy.Tgwsproxy.getStats());
                                } catch (Throwable e) {
                                    Log.e("MengramProxy", "STATS/PORT ERROR", e);
                                }
                            }).start();

                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    SharedPreferences mPrefs = MessagesController.getGlobalMainSettings();
                                    Log.d("MengramProxy", "AFTER 3s: proxy_enabled=" + mPrefs.getBoolean("proxy_enabled", false));
                                    Log.d("MengramProxy", "AFTER 3s: proxy_ip=" + mPrefs.getString("proxy_ip", "null"));
                                    Log.d("MengramProxy", "AFTER 3s: proxy_port=" + mPrefs.getInt("proxy_port", 0));
                                    Log.d("MengramProxy", "AFTER 3s: proxy_secret=" + mPrefs.getString("proxy_secret", "null"));
                                    Log.d("MengramProxy", "AFTER 3s: currentProxy=" + SharedConfig.currentProxy);
                                    Log.d("MengramProxy", "AFTER 3s: proxyList size=" + SharedConfig.proxyList.size());
                                } catch (Exception e) {
                                    Log.e("MengramProxy", "AFTER 3s LOG ERROR", e);
                                }
                            }, 3000);

                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    SharedPreferences mPrefs = MessagesController.getGlobalMainSettings();
                                    Log.d("MengramProxy", "AFTER 8s: proxy_enabled=" + mPrefs.getBoolean("proxy_enabled", false));
                                    Log.d("MengramProxy", "AFTER 8s: proxy_ip=" + mPrefs.getString("proxy_ip", "null"));
                                    Log.d("MengramProxy", "AFTER 8s: proxy_port=" + mPrefs.getInt("proxy_port", 0));
                                    Log.d("MengramProxy", "AFTER 8s: proxy_secret=" + mPrefs.getString("proxy_secret", "null"));
                                    Log.d("MengramProxy", "AFTER 8s: currentProxy=" + SharedConfig.currentProxy);
                                    Log.d("MengramProxy", "AFTER 8s: proxyList size=" + SharedConfig.proxyList.size());
                                } catch (Exception e) {
                                    Log.e("MengramProxy", "AFTER 8s LOG ERROR", e);
                                }
                            }, 8000);

                            Toast.makeText(getApplicationContext(), "WSS ПОДКЛЮЧЕН", Toast.LENGTH_SHORT).show();
                        } catch (Exception e) {
                            Log.e("MengramProxy", "ОШИБКА в UI потоке: " + e.getMessage(), e);
                        }
                    });

                    updateNotification("WSS Прокси работает");
                } else {
                    Log.e("MengramProxy", "Ошибка движка, код: " + result);
                    isServiceRunning = false;
                    stopSelf();
                }
            } catch (Exception e) {
                Log.e("MengramProxy", "Критическая ошибка", e);
                isServiceRunning = false;
                stopSelf();
            }
        }).start();

        return START_STICKY;
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try {
            java.net.Socket socket = new java.net.Socket();
            socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            socket.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void updateNotification(String text) {
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Mengram WSS")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .build();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        Log.e("MengramProxy", "onDestroy() вызван");
        TgWsProxy.stop();
        isServiceRunning = false;
        stopForeground(true);
        ApplicationLoader.applicationContext
                .getSharedPreferences("mengram_settings", 0)
                .edit().putBoolean("wss_proxy_enabled", false).apply();
        super.onDestroy();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Mengram Proxy", NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager =
                    (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
