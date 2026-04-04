package org.telegram.messenger;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.telegram.tgnet.ConnectionsManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MengramProxyEngine implements NotificationCenter.NotificationCenterDelegate {

    private static volatile MengramProxyEngine instance;
    private static final String PREF_NAME = "mengram_settings";
    private static final String KEY_ENABLED = "mtproto_enabled";
    private static final String KEY_COOLDOWN = "proxy_rotation_cooldown"; // Кулдаун в секундах

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private volatile boolean isLoading = false;
    private ProxyListener listener;
    private Runnable rotationRunnable;

    private static final String[] PROXY_SOURCES = {
            "https://raw.githubusercontent.com/ALIILAPRO/MTProtoProxy/main/mtproto.txt",
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            "https://raw.githubusercontent.com/Argh94/Proxy-List/main/MTProto.txt",
            "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt"
    };

    public interface ProxyListener {
        void onProxyFound(ProxyInfo proxy);
        void onProxyError(String message);
    }

    public static class ProxyInfo {
        public String server;
        public int port;
        public String secret;
        public long pingMs = -1;
        public boolean isAlive = false;

        public ProxyInfo(String server, int port, String secret) {
            this.server = server;
            this.port = port;
            this.secret = secret;
        }
    }

    private MengramProxyEngine() {
        // Подписываемся на изменения состояния подключения Telegram
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    public static MengramProxyEngine getInstance() {
        if (instance == null) {
            synchronized (MengramProxyEngine.class) {
                if (instance == null) instance = new MengramProxyEngine();
            }
        }
        return instance;
    }

    public void setListener(ProxyListener listener) {
        this.listener = listener;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();

            if (state == ConnectionsManager.ConnectionStateConnected) {
                stopRotationTimer(); // Соединение ок, таймер не нужен
            } else if (isMTProtoEnabled() && !isLoading) {
                startRotationTimer(); // Соединение пропало или "висит", запускаем отсчет
            }
        }
    }

    private void startRotationTimer() {
        stopRotationTimer();
        int cooldown = getRotationCooldown(); // Получаем из настроек (сек)

        rotationRunnable = () -> {
            int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            if (state != ConnectionsManager.ConnectionStateConnected) {
                // Если за отведенное время не подключились — ищем новый прокси
                refreshProxyList();
            }
        };
        mainHandler.postDelayed(rotationRunnable, cooldown * 1000L);
    }

    private void stopRotationTimer() {
        if (rotationRunnable != null) {
            mainHandler.removeCallbacks(rotationRunnable);
            rotationRunnable = null;
        }
    }

    public static void toggleMTProto(boolean enabled) {
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0);
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        if (enabled) getInstance().refreshProxyList();
        else getInstance().disconnect();
    }

    public static boolean isMTProtoEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getBoolean(KEY_ENABLED, true);
    }

    public static int getRotationCooldown() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getInt(KEY_COOLDOWN, 15);
    }

    public static void setRotationCooldown(int seconds) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putInt(KEY_COOLDOWN, seconds).apply();
    }

    public void refreshProxyList() {
        if (isLoading) return;
        isLoading = true;

        executor.execute(() -> {
            Set<String> uniqueProxies = new HashSet<>();
            List<ProxyInfo> tempList = new ArrayList<>();

            for (String source : PROXY_SOURCES) {
                if (!isLoading) return;
                try {
                    List<ProxyInfo> proxies = fetchProxiesFromUrl(source);
                    for (ProxyInfo proxy : proxies) {
                        if (uniqueProxies.add(proxy.server + ":" + proxy.port)) {
                            tempList.add(proxy);
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (!tempList.isEmpty() && isLoading) {
                checkProxiesParallel(tempList);
            } else {
                stopLoading("Сервера недоступны");
            }
        });
    }

    private List<ProxyInfo> fetchProxiesFromUrl(String urlString) throws Exception {
        List<ProxyInfo> proxies = new ArrayList<>();
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(5000);

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            Pattern pattern = Pattern.compile("server=([^&\\s]+)&port=(\\d+)&secret=([^&\\s]+)");
            while ((line = reader.readLine()) != null) {
                Matcher matcher = pattern.matcher(line);
                while (matcher.find()) {
                    String secret = matcher.group(3);
                    if (secret.startsWith("ee")) {
                        proxies.add(0, new ProxyInfo(matcher.group(1), Integer.parseInt(matcher.group(2)), secret));
                    } else if (secret.length() >= 32) {
                        proxies.add(new ProxyInfo(matcher.group(1), Integer.parseInt(matcher.group(2)), secret));
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        return proxies;
    }

    private void checkProxiesParallel(List<ProxyInfo> list) {
        List<ProxyInfo> subList = list.size() > 40 ? list.subList(0, 40) : list;
        CountDownLatch latch = new CountDownLatch(subList.size());

        for (ProxyInfo proxy : subList) {
            executor.execute(() -> {
                try {
                    long start = System.currentTimeMillis();
                    Socket socket = new Socket();
                    socket.connect(new InetSocketAddress(proxy.server, proxy.port), 2500);
                    socket.close();
                    proxy.pingMs = System.currentTimeMillis() - start;
                    proxy.isAlive = true;
                } catch (Exception ignored) {}
                latch.countDown();
            });
        }

        try {
            latch.await(8, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}

        if (!isLoading) return;

        List<ProxyInfo> alive = new ArrayList<>();
        for (ProxyInfo p : subList) if (p.isAlive) alive.add(p);
        Collections.sort(alive, (p1, p2) -> Long.compare(p1.pingMs, p2.pingMs));

        if (!alive.isEmpty()) {
            mainHandler.post(() -> applyProxy(alive.get(0)));
        } else {
            stopLoading("Все прокси недоступны");
        }
    }

    private void applyProxy(ProxyInfo proxy) {
        isLoading = false;

        SharedConfig.ProxyInfo tgProxy = new SharedConfig.ProxyInfo(proxy.server, proxy.port, "", "", proxy.secret);

        SharedConfig.loadProxyList();
        SharedConfig.proxyList.clear();
        SharedConfig.proxyList.add(0, tgProxy);
        SharedConfig.currentProxy = tgProxy;

        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        preferences.edit()
                .putString("proxy_ip", proxy.server)
                .putInt("proxy_port", proxy.port)
                .putString("proxy_user", "")
                .putString("proxy_pass", "")
                .putString("proxy_secret", proxy.secret)
                .putBoolean("proxy_enabled", true)
                .apply();

        SharedConfig.saveProxyList();
        ConnectionsManager.setProxySettings(true, proxy.server, proxy.port, "", "", proxy.secret);

        if (listener != null) listener.onProxyFound(proxy);

        startRotationTimer();
    }

    public void cancelSearch() {
        isLoading = false;
        stopRotationTimer();
    }

    private void disconnect() {
        isLoading = false;
        stopRotationTimer();
        
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        preferences.edit().putBoolean("proxy_enabled", false).apply();
        
        SharedConfig.saveProxyList();
        ConnectionsManager.setProxySettings(false, "", 1080, "", "", "");
    }

    private void stopLoading(String error) {
        isLoading = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onProxyError(error);
        });
    }
}
