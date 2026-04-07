package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import org.telegram.tgnet.ConnectionsManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MengramProxyEngine implements NotificationCenter.NotificationCenterDelegate {

    private static volatile MengramProxyEngine instance;
    private static final String PREF_NAME = "mengram_settings";
    private final ExecutorService executor = Executors.newFixedThreadPool(40);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<ProxyInfo> proxyList = new CopyOnWriteArrayList<>();
    private volatile boolean isLoading = false;
    private int currentProxyIndex = 0;
    private ProxyListener listener;
    private Runnable rotationRunnable;


    private static final String[] PROXY_SOURCES = {
            "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt",
            "https://raw.githubusercontent.com/Grim1313/mtproto-for-telegram/master/all_proxies.txt",
            "https://raw.githubusercontent.com/Argh94/Proxy-List/main/MTProto.txt"
    };


    public interface ProxyListener {
        void onProgress(int found, int total);
        void onProxyFound(ProxyInfo proxy);
        void onProxyError(String message);
    }

    public static class ProxyInfo {
        public String server;
        public int port;
        public String secret;
        public long pingMs = -1;

        public ProxyInfo(String server, int port, String secret) {
            this.server = server;
            this.port = port;
            this.secret = secret;
        }
    }

    private MengramProxyEngine() {
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
            
            // работает или нкет
            if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
                FileLog.d("MengramProxy: Alive! State: " + state + ". Stopping timer.");
                stopRotationTimer();
            } else if (isMTProtoEnabled() && !isLoading) {
                startRotationTimer();
            }
        }
    }

    public void refreshProxyList() {
        if (isLoading) return;
        isLoading = true;
        proxyList.clear();

        executor.execute(() -> {
            Set<String> uniqueStrings = new HashSet<>();
            List<ProxyInfo> rawList = new ArrayList<>();

            for (String source : PROXY_SOURCES) {
                try {
                    rawList.addAll(fetchProxiesFromUrl(source, uniqueStrings));
                } catch (Exception e) {
                    FileLog.e("MengramProxy: Error fetching " + source + " : " + e.getMessage());
                }
            }

            if (rawList.isEmpty()) {
                stopLoading("Списки пусты. GitHub недоступен.");
                return;
            }

            checkProxiesParallel(rawList);
        });
    }

    private List<ProxyInfo> fetchProxiesFromUrl(String urlString, Set<String> unique) throws Exception {
        List<ProxyInfo> found = new ArrayList<>();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setInstanceFollowRedirects(true); // Для обхода 301/302 на GitHub
        conn.setConnectTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");

        int responseCode = conn.getResponseCode();
        FileLog.d("MengramProxy: URL " + urlString + " Result: " + responseCode);

        if (responseCode != 200) return found;

        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                String srv = extractParam(line, "server");
                String prtStr = extractParam(line, "port");
                String sec = extractParam(line, "secret");

                if (srv != null && prtStr != null && sec != null) {
                    try {
                        int prt = Integer.parseInt(prtStr);
                        if (sec.length() >= 32 && unique.add(srv + ":" + prt)) {
                            found.add(new ProxyInfo(srv, prt, sec));
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        return found;
    }

    private String extractParam(String line, String key) {
        Pattern p = Pattern.compile(key + "=([^&\\s]+)");
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private void checkProxiesParallel(List<ProxyInfo> list) {
        int totalToCheck = Math.min(list.size(), 150);
        CountDownLatch latch = new CountDownLatch(totalToCheck);
        List<ProxyInfo> alive = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < totalToCheck; i++) {
            ProxyInfo p = list.get(i);
            executor.execute(() -> {
                Socket socket = null;
                try {
                    long start = System.currentTimeMillis();
                    socket = new Socket();
                    socket.connect(new InetSocketAddress(p.server, p.port), 5000);
                    p.pingMs = System.currentTimeMillis() - start;
                    alive.add(p);
                } catch (Exception ignored) {
                } finally {
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                    latch.countDown();
                    if (listener != null) mainHandler.post(() -> listener.onProgress(alive.size(), totalToCheck));
                }
            });
        }

        try { latch.await(15, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        Collections.sort(alive, (a, b) -> Long.compare(a.pingMs, b.pingMs));

        if (!alive.isEmpty()) {
            proxyList.clear();
            proxyList.addAll(alive);
            currentProxyIndex = 0;
            mainHandler.post(this::connectToBest);
        } else {
            stopLoading("Нет живых прокси.");
        }
    }

    public void connectToBest() {
        if (proxyList.isEmpty()) return;
        if (currentProxyIndex >= proxyList.size()) currentProxyIndex = 0;
        applyProxy(proxyList.get(currentProxyIndex));
    }

    public void switchToNext() {
        currentProxyIndex++;
        connectToBest();
    }

    private void applyProxy(ProxyInfo proxy) {
        isLoading = false;
        SharedConfig.loadProxyList();
        SharedConfig.ProxyInfo tgProxy = new SharedConfig.ProxyInfo(proxy.server, proxy.port, "", "", proxy.secret);

        SharedConfig.proxyList.clear();
        SharedConfig.proxyList.add(0, tgProxy);
        SharedConfig.currentProxy = tgProxy;
        SharedConfig.saveProxyList();

        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);

        ConnectionsManager.setProxySettings(true, proxy.server, proxy.port, "", "", proxy.secret);
        ConnectionsManager.getInstance(UserConfig.selectedAccount).checkConnection();

        if (listener != null) mainHandler.post(() -> listener.onProxyFound(proxy));
        startRotationTimer();
    }

    public void startRotationTimer() {
        stopRotationTimer();
        
        int cooldown = getRotationCooldown();
        FileLog.d("MengramProxy: Timer started with cooldown: " + cooldown + "s");

        rotationRunnable = new Runnable() {
            @Override
            public void run() {
                int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();

                if (state != ConnectionsManager.ConnectionStateConnected && state != ConnectionsManager.ConnectionStateUpdating) {
                    FileLog.d("MengramProxy: Cooldown " + cooldown + "s reached. Switching...");
                    switchToNext();
                }
            }
        };

        mainHandler.postDelayed(rotationRunnable, cooldown * 1000L);
    }

    public void stopRotationTimer() {
        if (rotationRunnable != null) {
            mainHandler.removeCallbacks(rotationRunnable);
            rotationRunnable = null;
        }
    }

    public static int getRotationCooldown() {
        return ApplicationLoader.applicationContext
                .getSharedPreferences(PREF_NAME, 0)
                .getInt("proxy_rotation_cooldown", 15);
    }

    public static void setRotationCooldown(int seconds) {
        ApplicationLoader.applicationContext
                .getSharedPreferences(PREF_NAME, 0)
                .edit()
                .putInt("proxy_rotation_cooldown", seconds)
                .apply();


        int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
        if (state != ConnectionsManager.ConnectionStateConnected && state != ConnectionsManager.ConnectionStateUpdating) {
            getInstance().startRotationTimer(); 
        }
    }

    public static boolean isMTProtoEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getBoolean("mtproto_enabled", false);
    }

    public static void toggleMTProto(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putBoolean("mtproto_enabled", enabled).apply();
        if (enabled) getInstance().refreshProxyList();
        else getInstance().cancelSearch();
    }

    public void cancelSearch() {
        isLoading = false;
        stopRotationTimer();
    }

    private void stopLoading(String error) {
        isLoading = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onProxyError(error);
        });
    }
}
