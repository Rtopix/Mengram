package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
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
    private static final long TURBO_INTERVAL = 10 * 60 * 1000L;
    private final ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors() * 2);
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private List<ProxyInfo> proxyList = new CopyOnWriteArrayList<>();
    private volatile boolean isLoading = false;
    private int currentProxyIndex = 0;
    private ProxyListener listener;
    private Runnable rotationRunnable;
    private Runnable turboRunnable;

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
            if (state == ConnectionsManager.ConnectionStateConnected || state == ConnectionsManager.ConnectionStateUpdating) {
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
            List<String> sourcesToFetch = new ArrayList<>();
            if (getProxySource() == 0) {
                sourcesToFetch.addAll(Arrays.asList(PROXY_SOURCES));
            } else {
                String custom = getCustomSourceUrl();
                if (!custom.isEmpty()) sourcesToFetch.add(custom);
                else sourcesToFetch.addAll(Arrays.asList(PROXY_SOURCES));
            }
            for (String source : sourcesToFetch) {
                try {
                    rawList.addAll(fetchProxiesFromUrl(source, uniqueStrings));
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (rawList.isEmpty()) {
                stopLoading("Source list is empty.");
                return;
            }
            checkProxiesParallel(rawList);
        });
    }

    private List<ProxyInfo> fetchProxiesFromUrl(String urlString, Set<String> unique) throws Exception {
        List<ProxyInfo> found = new ArrayList<>();
        HttpURLConnection conn = (HttpURLConnection) new URL(urlString).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15000);
        conn.setRequestProperty("User-Agent", "Mozilla/5.0");
        if (conn.getResponseCode() != 200) return found;
        Pattern ipPattern = Pattern.compile("^\\d+\\.\\d+\\.\\d+\\.\\d+$");
        try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                ProxyInfo p = parseLine(line);
                if (p != null) {
                    if (ipPattern.matcher(p.server).matches()) continue;
                    if (unique.add(p.server + ":" + p.port)) {
                        found.add(p);
                    }
                }
            }
        }
        return found;
    }

    private ProxyInfo parseLine(String line) {
        String srv = extractParam(line, "server");
        String prtStr = extractParam(line, "port");
        String sec = extractParam(line, "secret");
        if (srv != null && prtStr != null && sec != null) {
            try {
                int prt = Integer.parseInt(prtStr);
                if (sec.length() >= 32) return new ProxyInfo(srv, prt, sec);
            } catch (Exception ignored) {}
        }
        return null;
    }

    private String extractParam(String line, String key) {
        Pattern p = Pattern.compile(key + "=([^&\\s]+)");
        Matcher m = p.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    private String resolveDoH(String domain) {
        if (!isDoHEnabled()) return domain;
        try {
            URL url = new URL("https://cloudflare-dns.com/dns-query?name=" + domain + "&type=A");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestProperty("Accept", "application/dns-json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            if (conn.getResponseCode() == 200) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while ((line = reader.readLine()) != null) sb.append(line);
                    JSONObject json = new JSONObject(sb.toString());
                    if (json.has("Answer")) {
                        JSONArray answer = json.getJSONArray("Answer");
                        for (int i = 0; i < answer.length(); i++) {
                            JSONObject obj = answer.getJSONObject(i);
                            if (obj.getInt("type") == 1) return obj.getString("data");
                        }
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return domain;
    }

    private void checkProxiesParallel(List<ProxyInfo> list) {
        int totalToCheck = Math.min(list.size(), 150);
        CountDownLatch latch = new CountDownLatch(totalToCheck);
        List<ProxyInfo> alive = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < totalToCheck; i++) {
            ProxyInfo p = list.get(i);
            executor.execute(() -> {
                if (!isLoading) {
                    latch.countDown();
                    return;
                }
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
        try { latch.await(20, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        Collections.sort(alive, (a, b) -> Long.compare(a.pingMs, b.pingMs));
        if (!alive.isEmpty()) {
            proxyList.clear();
            proxyList.addAll(alive);
            currentProxyIndex = 0;
            mainHandler.post(this::connectToBest);
        } else {
            stopLoading("No alive domain-proxies found.");
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
        executor.execute(() -> {
            String resolvedIp = resolveDoH(proxy.server);
            mainHandler.post(() -> {
                SharedConfig.loadProxyList();
                SharedConfig.ProxyInfo tgProxy = new SharedConfig.ProxyInfo(resolvedIp, proxy.port, "", "", proxy.secret);
                SharedConfig.proxyList.clear();
                SharedConfig.proxyList.add(0, tgProxy);
                SharedConfig.currentProxy = tgProxy;
                SharedConfig.saveProxyList();
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                ConnectionsManager.setProxySettings(true, resolvedIp, proxy.port, "", "", proxy.secret);
                ConnectionsManager.getInstance(UserConfig.selectedAccount).checkConnection();
                if (listener != null) mainHandler.post(() -> listener.onProxyFound(proxy));
                startRotationTimer();
                startTurboTimer();
            });
        });
    }

    public void startRotationTimer() {
        stopRotationTimer();
        int cooldown = getRotationCooldown();
        rotationRunnable = () -> {
            int state = ConnectionsManager.getInstance(UserConfig.selectedAccount).getConnectionState();
            if (state != ConnectionsManager.ConnectionStateConnected && state != ConnectionsManager.ConnectionStateUpdating) {
                switchToNext();
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

    private void startTurboTimer() {
        stopTurboTimer();
        if (!isTurboModeEnabled() || !isMTProtoEnabled()) return;
        turboRunnable = () -> {
            switchToNext();
            startTurboTimer();
        };
        mainHandler.postDelayed(turboRunnable, TURBO_INTERVAL);
    }

    private void stopTurboTimer() {
        if (turboRunnable != null) {
            mainHandler.removeCallbacks(turboRunnable);
            turboRunnable = null;
        }
    }

    public void addProxiesFromList(List<String> lines) {
        executor.execute(() -> {
            List<ProxyInfo> raw = new ArrayList<>();
            Set<String> unique = new HashSet<>();
            for (String line : lines) {
                ProxyInfo p = parseLine(line);
                if (p != null && unique.add(p.server + ":" + p.port)) raw.add(p);
            }
            if (!raw.isEmpty()) {
                mainHandler.post(() -> {
                    isLoading = true;
                    if (listener != null) listener.onProgress(0, raw.size());
                });
                checkProxiesParallel(raw);
            }
        });
    }

    public static int getRotationCooldown() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getInt("proxy_rotation_cooldown", 15);
    }

    public static void setRotationCooldown(int seconds) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putInt("proxy_rotation_cooldown", seconds).apply();
    }

    public static boolean isMTProtoEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getBoolean("mtproto_enabled", false);
    }

    public static void toggleMTProto(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putBoolean("mtproto_enabled", enabled).apply();
        if (enabled) {
            getInstance().refreshProxyList();
            getInstance().startTurboTimer();
        } else {
            getInstance().cancelSearch();
            getInstance().stopTurboTimer();
        }
    }

    public void cancelSearch() {
        isLoading = false;
        stopRotationTimer();
        stopTurboTimer();
    }

    private void stopLoading(String error) {
        isLoading = false;
        mainHandler.post(() -> {
            if (listener != null) listener.onProxyError(error);
        });
    }

    public static String getMasking() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getString("masking_host", "Google");
    }

    public static void setMasking(String value) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putString("masking_host", value).apply();
    }

    public static boolean isTurboModeEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getBoolean("turbo_mode", false);
    }

    public static void setTurboMode(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putBoolean("turbo_mode", enabled).apply();
        if (enabled && isMTProtoEnabled()) getInstance().startTurboTimer();
        else getInstance().stopTurboTimer();
    }

    public static boolean isDoHEnabled() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getBoolean("doh_enabled", false);
    }

    public static void setDoHEnabled(boolean enabled) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putBoolean("doh_enabled", enabled).apply();
    }

    public static int getProxySource() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getInt("proxy_source", 0);
    }

    public static void setProxySource(int source) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putInt("proxy_source", source).apply();
    }

    public static String getCustomSourceUrl() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).getString("custom_source_url", "");
    }

    public static void setCustomSourceUrl(String url) {
        ApplicationLoader.applicationContext.getSharedPreferences(PREF_NAME, 0).edit().putString("custom_source_url", url).apply();
    }
}
