#!/system/bin/sh
# RingServer 开机自启（Magisk service.d 脚本，开机时以 root 运行）
# 安装位置：/data/adb/service.d/ringserver_boot.sh
# 等待系统启动完成后拉起前台服务；若看门狗脚本存在则一并拉起。
#
# 兼容 Android 5.0+：低版本没有 am start-foreground-service / setsid 时自动回落。

PKG=com.example.ringserver
SVC=com.example.ringserver/.RingServerService
WATCHDOG=/data/local/tmp/ringserver_watchdog.sh

SDK=$(getprop ro.build.version.sdk)
[ -z "$SDK" ] && SDK=21

# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 2
done
sleep 5

# 拉起服务（root 上下文，不受后台启动限制）
if [ "$SDK" -ge 26 ]; then
  am start-foreground-service -n "$SVC" >/dev/null 2>&1
else
  am startservice -n "$SVC" >/dev/null 2>&1
fi

# 看门狗存在则启动（Root 模式开启时才有）
if [ -f "$WATCHDOG" ]; then
  if command -v setsid >/dev/null 2>&1; then
    setsid sh "$WATCHDOG" >/dev/null 2>&1 &
  else
    nohup sh "$WATCHDOG" >/dev/null 2>&1 &
  fi
fi

exit 0
