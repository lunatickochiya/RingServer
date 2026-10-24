#!/system/bin/sh
# RingServer 看门狗（root 独立守护进程）
# 作用：
#   1. 应用进程被杀/被系统回收后，自动拉起前台服务
#   2. 周期性把本应用进程的 oom_score_adj 固定为 -800（低内存不优先回收）
# 由应用内 RootHelper 以 su + nohup/setsid 方式启动，不随应用进程退出。
#
# 兼容 Android 5.0+：低版本没有 am start-foreground-service / pidof 时自动回落。

PKG=com.example.ringserver
SVC=com.example.ringserver/.RingServerService
PIDFILE=/data/local/tmp/ringserver_watchdog.pid
LOGFILE=/data/local/tmp/ringserver_watchdog.log
OOM_ADJ=-800
backoff=0

SDK=$(getprop ro.build.version.sdk)
[ -z "$SDK" ] && SDK=21

echo $$ > "$PIDFILE"
log() { echo "[$(date '+%Y-%m-%d %H:%M:%S')] $*" >> "$LOGFILE"; }

# 日志上限 128KB，超出清空重记
LOGSIZE=$(wc -c < "$LOGFILE" 2>/dev/null)
if [ -n "$LOGSIZE" ] && [ "$LOGSIZE" -gt 131072 ]; then
  : > "$LOGFILE"
fi
log "watchdog started pid $$ (sdk $SDK)"

app_running() {
  if command -v pidof >/dev/null 2>&1; then
    pidof "$PKG" >/dev/null 2>&1
  else
    ps | grep "$PKG" | grep -qv grep
  fi
}

app_pids() {
  if command -v pidof >/dev/null 2>&1; then
    pidof "$PKG" 2>/dev/null
  else
    ps | while read -r user pid ppid rest; do
      case "$rest" in
        *"$PKG"*) printf '%s ' "$pid" ;;
      esac
    done
  fi
}

start_service() {
  if [ "$SDK" -ge 26 ]; then
    am start-foreground-service -n "$SVC" >/dev/null 2>&1
  else
    # Android 5.0 - 7.1 没有 start-foreground-service
    am startservice -n "$SVC" >/dev/null 2>&1
  fi
}

while :; do
  if ! app_running; then
    # 进程不在 -> 拉起服务
    start_service
    sleep 3
    if app_running; then
      backoff=0
      log "service started"
    else
      backoff=$((backoff + 1))
      if [ "$backoff" -ge 6 ]; then
        # 连续失败（如被强行停止，stopped 状态起不来），降频重试
        log "app cannot start (force-stopped?), slow retry every 30s"
        sleep 30
        continue
      fi
    fi
  else
    backoff=0
  fi

  # OOM 保护：系统会周期性重置该值，这里持续重写
  for p in $(app_pids); do
    echo "$OOM_ADJ" > "/proc/$p/oom_score_adj" 2>/dev/null
  done

  sleep 5
done
