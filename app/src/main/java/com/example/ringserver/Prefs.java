package com.example.ringserver;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 本地配置：/ring 与 /stop 的访问密钥。
 *
 * <p>本应用只监听局域网，不包含任何公网穿透能力；密钥用于在同一 Wi-Fi
 * 内做一层简单鉴权（例如访客网络里也能防止别人触发响铃）。
 */
final class Prefs {

    private static final String FILE = "ringserver_net_prefs";

    // ---- /ring 鉴权 ----
    static final String KEY_RING_KEY = "ring_key";

    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
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
