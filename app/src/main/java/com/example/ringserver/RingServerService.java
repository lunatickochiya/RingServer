package com.example.ringserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;

/**
 * 前台服务：常驻运行 HTTP 服务器。
 * 必须以前台服务方式运行，否则 Android 8+ 在应用退到后台后会杀掉进程。
 *
 * <p>端口可在应用内配置（默认 80）。低于 1024 的端口需要特权：
 * Root 模式开启时会尝试放开内核限制，失败则自动回退到 8089 / 8080。
 *
 * <p>兼容 Android 5.0（API 21）：通知渠道、startForegroundService 等
 * 仅在系统版本满足时使用，低版本回落到旧 API。
 */
public class RingServerService extends Service {

    /** 界面修改端口后，用这个 action 让服务重启 HTTP 服务器 */
    public static final String ACTION_RESTART = "com.example.ringserver.action.RESTART";

    private static final String TAG = "RingServerService";
    private static final String CHANNEL_ID = "ring_server";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean running = false;
    private static volatile int activePort = Prefs.DEFAULT_PORT;
    private static volatile String lastError = "";

    private RingHttpServer server;

    public static boolean isRunning() {
        return running;
    }

    /** 实际监听端口（配置端口不可用时可能是回退端口） */
    public static int getActivePort() {
        return activePort;
    }

    /** 最近一次启动/回退的错误说明，空字符串表示正常 */
    public static String getLastError() {
        return lastError;
    }

    /**
     * 启动服务：Android 8+ 必须用 startForegroundService，低版本用 startService。
     */
    public static void start(Context context) {
        startServiceCompat(context, new Intent(context, RingServerService.class));
    }

    /** 按最新配置重启 HTTP 服务器（用于界面修改端口后） */
    public static void restart(Context context) {
        Intent intent = new Intent(context, RingServerService.class);
        intent.setAction(ACTION_RESTART);
        startServiceCompat(context, intent);
    }

    private static void startServiceCompat(Context context, Intent intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // 先把前台通知发出去，再绑定端口；绑定失败/回退后会更新通知
        startForeground(NOTIFICATION_ID, buildNotification("正在启动服务…"));
        startServer();
        updateNotification();

        // Root 模式开启时确保看门狗在跑（进程被杀后自动拉起本服务）
        RootHelper.ensureWatchdog(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_RESTART.equals(intent.getAction())) {
            startServer();
            updateNotification();
        } else if (!running) {
            // 上次启动失败（端口冲突等）时，再次 start 会重试
            startServer();
            updateNotification();
        }
        // 进程被杀后尝试重启服务
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopServer();
        RingHelper.stopRinging();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------ HTTP 服务

    /** 按当前配置启动 HTTP 服务器；低端口失败时尝试回退端口 */
    private void startServer() {
        stopServer();

        int requested = Prefs.getPort(this);
        RingHttpServer started = tryStart(requested);

        // 80 等低端口需要特权：Root 模式开启时尝试放开内核限制（Linux 4.11+）
        if (started == null && requested < 1024 && RootHelper.isRootModeEnabled(this)) {
            if (RootHelper.allowLowPorts()) {
                started = tryStart(requested);
            }
        }

        int boundPort = requested;
        if (started == null) {
            // 配置端口不可用：依次尝试回退端口，保证服务仍可访问
            int[] fallbacks = {Prefs.FALLBACK_PORT, Prefs.SECONDARY_FALLBACK_PORT};
            for (int candidate : fallbacks) {
                if (candidate == requested) {
                    continue;
                }
                started = tryStart(candidate);
                if (started != null) {
                    boundPort = candidate;
                    break;
                }
            }
        }

        if (started != null) {
            server = started;
            int listening = started.getListeningPort();
            activePort = listening > 0 ? listening : boundPort;
            running = true;
            if (activePort != requested) {
                if (requested < 1024 && !RootHelper.isRootModeEnabled(this)) {
                    lastError = "端口 " + requested + " 需要 Root 模式才能使用，已回退到 " + activePort;
                } else {
                    lastError = "端口 " + requested + " 不可用，已自动回退到 " + activePort;
                }
            } else {
                lastError = "";
            }
            Log.i(TAG, "HTTP server started on port " + activePort
                    + " (requested " + requested + ")");
        } else {
            running = false;
            activePort = requested;
            if (requested < 1024) {
                lastError = "端口 " + requested + " 启动失败；低端口需要 Root 模式且内核支持，"
                        + "请改用 1024 以上端口";
            } else {
                lastError = "端口 " + requested + " 启动失败，请检查端口是否被占用";
            }
            Log.e(TAG, lastError);
        }
    }

    private RingHttpServer tryStart(int port) {
        RingHttpServer s = new RingHttpServer(port, this);
        try {
            s.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            return s;
        } catch (IOException e) {
            Log.e(TAG, "Failed to start HTTP server on port " + port, e);
            try {
                s.stop();
            } catch (Exception ignored) {
                // 绑定失败时通常还没有可关闭的 socket
            }
            return null;
        }
    }

    private void stopServer() {
        if (server != null) {
            server.stop();
            server = null;
        }
        running = false;
    }

    // -------------------------------------------------------------- 通知

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFICATION_ID, buildNotification(notificationText()));
        }
    }

    private String notificationText() {
        if (running) {
            return "端口 " + activePort + "，控制页 / 已就绪";
        }
        return lastError.isEmpty() ? "服务未运行" : lastError;
    }

    private Notification buildNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ 需要通知渠道
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "响铃服务", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("HTTP 响铃服务运行中");
            nm.createNotificationChannel(channel);
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            // Android 5.0 - 7.1：无通知渠道
            builder = new Notification.Builder(this)
                    .setPriority(Notification.PRIORITY_LOW);
        }

        Intent openIntent = new Intent(this, MainActivity.class);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE : 0;
        PendingIntent pi = PendingIntent.getActivity(this, 0, openIntent, piFlags);

        return builder
                .setSmallIcon(R.drawable.ic_stat_bell)
                .setContentTitle(running ? "响铃服务运行中" : "响铃服务")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
