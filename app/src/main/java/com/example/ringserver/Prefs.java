package com.example.ringserver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置：HTTP 服务端口 + /ring、/stop 的访问密钥。
 *
 * <p>本应用只监听局域网，不包含任何公网穿透能力；密钥用于在同一 Wi-Fi
 * 内做一层简单鉴权（例如访客网络里也能防止别人触发响铃）。
 */
final class Prefs {

    private static final String FILE = "ringserver_net_prefs";

    // ---- 服务端口 ----
    static final String KEY_PORT = "server_port";
    /** 默认端口；低于 1024 的端口在非 root 进程里通常无法绑定 */
    static final int DEFAULT_PORT = 80;
    /** 配置端口不可用时依次回退的端口 */
    static final int FALLBACK_PORT = 8089;
    static final int SECONDARY_FALLBACK_PORT = 8080;

    // ---- /ring 鉴权 ----
    static final String KEY_RING_KEY = "ring_key";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // -------------------------------------------------------------- 服务端口

    /** 用户配置的端口；非法值回落到默认 80 */
    static int getPort(Context c) {
        int port = sp(c).getInt(KEY_PORT, DEFAULT_PORT);
        return (port >= 1 && port <= 65535) ? port : DEFAULT_PORT;
    }

    static void setPort(Context c, int port) {
        sp(c).edit().putInt(KEY_PORT, port).apply();
    }

    // -------------------------------------------------------- /ring 访问密钥

    /** 为空表示不做鉴权 */
    static String getRingKey(Context c) {
        return safe(sp(c).getString(KEY_RING_KEY, ""));
    }

    static void setRingKey(Context c, String key) {
        sp(c).edit().putString(KEY_RING_KEY, safe(key)).apply();
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
