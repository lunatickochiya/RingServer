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
 * <p>兼容 Android 5.0（API 21）：通知渠道、startForegroundService 等
 * 仅在系统版本满足时使用，低版本回落到旧 API。
 */
public class RingServerService extends Service {

    public static final int PORT = 8089;

    private static final String TAG = "RingServerService";
    private static final String CHANNEL_ID = "ring_server";
    private static final int NOTIFICATION_ID = 1;

    private static volatile boolean running = false;

    private RingHttpServer server;

    public static boolean isRunning() {
        return running;
    }

    /**
     * 启动服务：Android 8+ 必须用 startForegroundService，低版本用 startService。
     */
    public static void start(Context context) {
        Intent intent = new Intent(context, RingServerService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification());

        server = new RingHttpServer(PORT, this);
        try {
            server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            running = true;
            Log.i(TAG, "HTTP server started on port " + PORT);
        } catch (IOException e) {
            running = false;
            Log.e(TAG, "Failed to start HTTP server", e);
        }

        // Root 模式开启时确保看门狗在跑（进程被杀后自动拉起本服务）
        RootHelper.ensureWatchdog(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 进程被杀后尝试重启服务
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        if (server != null) {
            server.stop();
            server = null;
        }
        RingHelper.stopRinging();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification buildNotification() {
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
                .setContentTitle("响铃服务运行中")
                .setContentText("端口 " + PORT + "，收到 /ring 请求即强制响铃")
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}
