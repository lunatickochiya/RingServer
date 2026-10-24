package com.example.ringserver;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

/**
 * 轻量级 HTTP 服务器，基于 NanoHTTPD 2.3.1（fi.iki.elonen 单文件实现）。
 *
 * 端点：
 *   GET /            -> 网页控制面板（开始/停止响铃、状态、密钥输入）
 *   GET /status      -> {"ringing":bool,"port":int}
 *   GET/POST /ring   -> 强制响铃（闹钟音频流，静音/勿扰下仍发声）
 *   GET/POST /stop   -> 停止响铃
 *
 * 鉴权：若在设置里填了「访问密钥」，则 /ring 与 /stop 必须带 key：
 *   - query 参数：/ring?key=xxxx
 *   - 或请求头：  X-Ring-Key: xxxx
 * 密钥为空时不做校验。/ 与 /status 不鉴权（页面上的操作按钮会带 key）。
 */
public class RingHttpServer extends NanoHTTPD {

    private static final String TAG = "RingHttpServer";
    private static final String HEADER_KEY = "x-ring-key";

    private final Context context;

    public RingHttpServer(int port, Context context) {
        super(port);
        this.context = context.getApplicationContext();
    }

    @Override
    public Response serve(IHTTPSession session) {
        Method method = session.getMethod();
        String uri = session.getUri();
        Log.i(TAG, method + " " + uri);

        if ("/ring".equals(uri) && (Method.GET.equals(method) || Method.POST.equals(method))) {
            Response denied = checkKey(session);
            if (denied != null) {
                return denied;
            }
            RingHelper.ring(context);
            return text(Response.Status.OK, "RINGING\n");
        }

        if ("/stop".equals(uri) && (Method.GET.equals(method) || Method.POST.equals(method))) {
            Response denied = checkKey(session);
            if (denied != null) {
                return denied;
            }
            RingHelper.stopRinging();
            return text(Response.Status.OK, "STOPPED\n");
        }

        if ("/status".equals(uri) && Method.GET.equals(method)) {
            int port = getListeningPort();
            String json = "{\"ringing\":" + RingHelper.isRinging()
                    + ",\"port\":" + (port > 0 ? port : 0) + "}";
            Response response = newFixedLengthResponse(
                    Response.Status.OK, "application/json; charset=utf-8", json);
            response.addHeader("Cache-Control", "no-store");
            return response;
        }

        if ("/".equals(uri) && Method.GET.equals(method)) {
            boolean authOn = !TextUtils.isEmpty(Prefs.getRingKey(context));
            String page = PAGE_TEMPLATE.replace("__AUTH_ON__", authOn ? "true" : "false");
            Response response = newFixedLengthResponse(
                    Response.Status.OK, "text/html; charset=utf-8", page);
            response.addHeader("Cache-Control", "no-store");
            response.addHeader("X-Content-Type-Options", "nosniff");
            return response;
        }

        return text(Response.Status.NOT_FOUND, "Not Found\n");
    }

    /** 校验通过返回 null，否则返回 403 响应 */
    private Response checkKey(IHTTPSession session) {
        String expected = Prefs.getRingKey(context);
        if (TextUtils.isEmpty(expected)) {
            return null;
        }

        String provided = null;
        try {
            provided = session.getParms().get("key");
        } catch (Exception ignored) {
            // 某些请求体解析失败时忽略，继续尝试 header
        }
        if (TextUtils.isEmpty(provided)) {
            provided = session.getHeaders().get(HEADER_KEY);
        }
        if (!expected.equals(provided)) {
            Log.w(TAG, "rejected: bad key, uri=" + session.getUri());
            return text(Response.Status.FORBIDDEN, "FORBIDDEN\n");
        }
        return null;
    }

    private static Response text(Response.Status status, String body) {
        return newFixedLengthResponse(status, "text/plain; charset=utf-8", body);
    }

    /**
     * 控制页模板。__AUTH_ON__ 在运行时替换成 true / false。
     * 页面只使用相对路径，因此换端口后无需修改。
     */
    private static final String PAGE_TEMPLATE = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
            <meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <title>响铃服务器</title>
            <style>
            :root { color-scheme: light dark; }
            * { box-sizing: border-box; }
            html, body { margin: 0; min-height: 100%; }
            body {
              min-height: 100vh;
              display: flex;
              align-items: center;
              justify-content: center;
              padding: 24px 16px;
              font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto,
                "Helvetica Neue", Arial, "Noto Sans SC", sans-serif;
              background: linear-gradient(135deg, #eef3ff 0%, #f8f9fc 100%);
              color: #1b1f2a;
            }
            .card {
              width: 100%;
              max-width: 420px;
              padding: 24px;
              border-radius: 20px;
              background: #fff;
              box-shadow: 0 16px 48px rgba(20, 40, 80, 0.12);
            }
            .header { display: flex; align-items: center; }
            .logo {
              width: 48px;
              height: 48px;
              flex: 0 0 48px;
              margin-right: 14px;
              display: flex;
              align-items: center;
              justify-content: center;
              border-radius: 14px;
              background: #1a73e8;
              font-size: 24px;
            }
            h1 { margin: 0; font-size: 21px; line-height: 1.2; }
            .sub { margin: 4px 0 0; color: #6b7280; font-size: 13px; }
            .status {
              margin: 20px 0 16px;
              padding: 10px 14px;
              border-radius: 10px;
              text-align: center;
              font-size: 14px;
              font-weight: 700;
              background: #f1f3f5;
              color: #6b7280;
            }
            .status.on { background: #fdecec; color: #d93025; }
            .status.off { background: #eef3ff; color: #1a73e8; }
            .actions { display: flex; }
            .btn {
              flex: 1;
              border: 0;
              border-radius: 12px;
              padding: 16px 10px;
              color: #fff;
              font-size: 16px;
              font-weight: 700;
              cursor: pointer;
              transition: opacity .15s ease, transform .05s ease;
            }
            .btn + .btn { margin-left: 12px; }
            .btn:active { transform: scale(.98); }
            .btn:disabled { opacity: .5; cursor: default; }
            .btn.ring { background: #1a73e8; }
            .btn.stop { background: #d93025; }
            .tip { margin: 12px 0 0; color: #9aa1ad; font-size: 12px; text-align: center; }
            .key-panel {
              margin-top: 20px;
              padding-top: 16px;
              border-top: 1px solid #eceff3;
            }
            .key-panel label {
              display: block;
              margin-bottom: 6px;
              color: #4b5563;
              font-size: 13px;
            }
            .key-panel input {
              width: 100%;
              padding: 10px 12px;
              border: 1px solid #d7dce3;
              border-radius: 10px;
              font-size: 14px;
              outline: none;
            }
            .key-panel input:focus {
              border-color: #1a73e8;
              box-shadow: 0 0 0 3px rgba(26, 115, 232, .12);
            }
            .hint { margin: 6px 0 0; color: #9aa1ad; font-size: 12px; }
            .message {
              min-height: 18px;
              margin: 14px 0 0;
              color: #2e7d32;
              font-size: 13px;
              text-align: center;
            }
            .message.error { color: #d93025; }
            footer { margin-top: 18px; color: #9aa1ad; font-size: 12px; text-align: center; }
            @media (prefers-color-scheme: dark) {
              body { background: #0f1115; color: #e8eaed; }
              .card { background: #181b20; box-shadow: none; }
              .sub { color: #9aa1ad; }
              .status { background: #22262d; color: #bdc1c6; }
              .status.on { background: #3a1d1d; color: #f28b82; }
              .status.off { background: #1c2a45; color: #8ab4f8; }
              .key-panel { border-top-color: #2a2f36; }
              .key-panel label { color: #bdc1c6; }
              .key-panel input { background: #111419; color: #e8eaed; border-color: #333941; }
              .hint, footer, .tip { color: #7f8792; }
            }
            </style>
            </head>
            <body>
            <main class="card">
              <div class="header">
                <div class="logo">🔔</div>
                <div>
                  <h1>响铃服务器</h1>
                  <p class="sub">局域网控制面板</p>
                </div>
              </div>
              <div id="status" class="status off">连接中…</div>
              <div class="actions">
                <button id="ringBtn" class="btn ring" type="button">开始响铃</button>
                <button id="stopBtn" class="btn stop" type="button">停止响铃</button>
              </div>
              <p class="tip">响铃 60 秒后自动停止</p>
              <div id="keyPanel" class="key-panel" style="display:none">
                <label for="keyInput">访问密钥</label>
                <input id="keyInput" type="text" autocomplete="off" spellcheck="false"
                  placeholder="输入访问密钥">
                <p class="hint">密钥保存在本机浏览器，仅用于访问本页面。</p>
              </div>
              <p id="message" class="message"></p>
              <footer>同一局域网内打开本页面即可控制 · <span id="portInfo">端口 -</span></footer>
            </main>
            <script>
            (function () {
              var AUTH_ON = __AUTH_ON__;
              var keyPanel = document.getElementById('keyPanel');
              var keyInput = document.getElementById('keyInput');
              var statusEl = document.getElementById('status');
              var messageEl = document.getElementById('message');
              var ringBtn = document.getElementById('ringBtn');
              var stopBtn = document.getElementById('stopBtn');
              var portInfo = document.getElementById('portInfo');

              function keyFromUrl() {
                var m = /[?&]key=([^&]*)/.exec(window.location.search);
                if (!m) return '';
                try { return decodeURIComponent(m[1]); } catch (e) { return ''; }
              }
              function loadKey() {
                var fromUrl = keyFromUrl();
                if (fromUrl) {
                  try { localStorage.setItem('ringKey', fromUrl); } catch (e) {}
                  return fromUrl;
                }
                try { return localStorage.getItem('ringKey') || ''; } catch (e) { return ''; }
              }

              var key = AUTH_ON ? loadKey() : '';
              if (AUTH_ON) {
                keyPanel.style.display = 'block';
                keyInput.value = key;
                keyInput.addEventListener('input', function () {
                  key = keyInput.value.trim();
                  try { localStorage.setItem('ringKey', key); } catch (e) {}
                });
              }

              function withKey(url) {
                if (!key) return url;
                return url + (url.indexOf('?') >= 0 ? '&' : '?')
                  + 'key=' + encodeURIComponent(key);
              }
              function setBusy(busy) {
                ringBtn.disabled = busy;
                stopBtn.disabled = busy;
              }
              function setStatus(text, cls) {
                statusEl.textContent = text;
                statusEl.className = 'status ' + cls;
              }
              function showMessage(text, isError) {
                messageEl.textContent = text || '';
                messageEl.className = 'message' + (isError ? ' error' : '');
              }
              function request(method, url, callback) {
                var xhr = new XMLHttpRequest();
                xhr.open(method, withKey(url), true);
                xhr.timeout = 5000;
                xhr.onload = function () { callback(null, xhr); };
                xhr.onerror = function () { callback(new Error('网络错误')); };
                xhr.ontimeout = function () { callback(new Error('请求超时')); };
                xhr.onabort = function () { callback(new Error('请求已取消')); };
                try { xhr.send(); } catch (e) { callback(e); }
              }
              function refreshStatus() {
                request('GET', '/status', function (err, xhr) {
                  if (err) { setStatus('服务不可达', 'off'); return; }
                  if (xhr.status !== 200) { setStatus('状态未知', 'off'); return; }
                  var data;
                  try { data = JSON.parse(xhr.responseText); } catch (e) {
                    setStatus('状态未知', 'off');
                    return;
                  }
                  if (data.port) portInfo.textContent = '端口 ' + data.port;
                  if (data.ringing) { setStatus('响铃中', 'on'); }
                  else { setStatus('已停止', 'off'); }
                });
              }
              function action(url, okText) {
                setBusy(true);
                showMessage('', false);
                request('POST', url, function (err, xhr) {
                  setBusy(false);
                  if (err) {
                    showMessage(err.message, true);
                    refreshStatus();
                    return;
                  }
                  if (xhr.status === 403) {
                    showMessage('访问密钥错误，请重新输入', true);
                    setStatus('未授权', 'off');
                    return;
                  }
                  if (xhr.status !== 200) {
                    showMessage('操作失败（HTTP ' + xhr.status + '）', true);
                    return;
                  }
                  showMessage(okText, false);
                  refreshStatus();
                });
              }

              ringBtn.addEventListener('click', function () {
                action('/ring', '已开始响铃，60 秒后自动停止');
              });
              stopBtn.addEventListener('click', function () {
                action('/stop', '已停止响铃');
              });

              refreshStatus();
              setInterval(refreshStatus, 3000);
            })();
            </script>
            </body>
            </html>
            """;
}
