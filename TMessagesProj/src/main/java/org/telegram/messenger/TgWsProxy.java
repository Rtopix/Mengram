package org.telegram.messenger;

import tgwsproxy.Tgwsproxy;

public class TgWsProxy {
    
    public static String generateSecret() {
        byte[] bytes = new byte[16];
        new java.security.SecureRandom().nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    public static long runEngine(int port, String secret) {
        Tgwsproxy.setPoolSize(4L);
        Tgwsproxy.setCfProxyConfig(1L, 1L, ""); // cfPriority = true
        return Tgwsproxy.startProxy("127.0.0.1", (long) port, "", secret, 1L); // isDcAuto = true (empty ips)
    }

    public static String getSecret() {
        return Tgwsproxy.getSecretWithPrefix();
    }

    public static void stop() {
        Tgwsproxy.stopProxy();
    }
}