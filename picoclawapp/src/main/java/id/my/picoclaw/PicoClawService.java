package id.my.picoclaw;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.regex.Pattern;

public class PicoClawService extends Service {
    public static volatile boolean running = false;
    public static volatile String status = "STOPPED";
    public static volatile String lastLine = "-";

    private static final int PANEL_PORT = 18791;
    private static final int MAX_LOG_LINES = 120;
    private static final Deque<String> recentLogs = new ArrayDeque<>();
    private static final Pattern ANSI = Pattern.compile("\\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\\u0007]*(?:\\u0007|\\u001B\\\\))");

    private Process process;
    private Thread readerThread;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean panelRunning = false;
    private ServerSocket panelSocket;
    private Thread panelThread;
    private volatile boolean desiredRunning = false;
    private volatile boolean watchdogRunning = false;
    private Thread watchdogThread;
    private volatile int restartCount = 0;
    private volatile long lastStartAt = 0;

    @Override public void onCreate() {
        super.onCreate();
        startWebPanel();
        startWatchdog();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "START" : intent.getStringExtra("action");
        if ("STOP".equals(action)) {
            desiredRunning = false;
            stopNative();
            return START_STICKY;
        }
        desiredRunning = true;
        if (!running) startNative();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        desiredRunning = false;
        watchdogRunning = false;
        stopNative();
        stopWebPanel();
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    public static synchronized String recentLog() {
        StringBuilder sb = new StringBuilder();
        for (String line : recentLogs) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(line);
        }
        return sb.length() == 0 ? "-" : sb.toString();
    }

    private static synchronized void appendLog(String line) {
        if (line == null) return;
        line = ANSI.matcher(line).replaceAll("").replace("\u001b", "").trim();
        if (line.length() == 0) return;
        recentLogs.addLast(line);
        while (recentLogs.size() > MAX_LOG_LINES) recentLogs.removeFirst();
        lastLine = line;
    }

    private synchronized void startNative() {
        if (running) return;
        try {
            File home = new File(getFilesDir(), "picoclaw");
            File workspace = new File(home, "workspace");
            File tmp = new File(getCacheDir(), "picoclaw-tmp");
            home.mkdirs(); workspace.mkdirs(); tmp.mkdirs();

            File config = new File(home, "config.json");
            if (!config.exists()) writeDefaultConfig(config, workspace);

            File nativeBin = new File(getApplicationInfo().nativeLibraryDir, "libpicoclaw.so");
            if (!nativeBin.exists()) throw new IOException("libpicoclaw.so tidak ditemukan untuk armeabi-v7a");
            nativeBin.setExecutable(true, false);

            ProcessBuilder pb = new ProcessBuilder(nativeBin.getAbsolutePath(), "gateway");
            pb.redirectErrorStream(true);
            pb.directory(home);
            Map<String,String> env = pb.environment();
            env.put("PICOCLAW_HOME", home.getAbsolutePath());
            env.put("PICOCLAW_CONFIG", config.getAbsolutePath());
            env.put("HOME", home.getAbsolutePath());
            env.put("TMPDIR", tmp.getAbsolutePath());
            env.put("PICOCLAW_GATEWAY_HOST", "0.0.0.0");
            env.put("PICOCLAW_GATEWAY_PORT", "18790");

            PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
            if (wakeLock == null || !wakeLock.isHeld()) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoClawARMv7:Gateway");
                wakeLock.acquire();
            }

            process = pb.start();
            running = true;
            status = "RUNNING";
            lastStartAt = System.currentTimeMillis();
            appendLog("PicoClaw gateway starting on :18790");

            final Process startedProcess = process;
            readerThread = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(startedProcess.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) appendLog(line);
                    int code = startedProcess.waitFor();
                    appendLog("PicoClaw exited with code " + code);
                    status = "EXITED (" + code + ")";
                } catch (Exception e) {
                    appendLog(e.getClass().getSimpleName() + ": " + e.getMessage());
                    status = "ERROR";
                } finally {
                    synchronized (PicoClawService.this) {
                        if (process == startedProcess) process = null;
                    }
                    running = false;
                    releaseWakeLock();
                }
            }, "picoclaw-output");
            readerThread.start();
        } catch (Exception e) {
            running = false;
            status = "ERROR";
            appendLog(e.getClass().getSimpleName() + ": " + e.getMessage());
            releaseWakeLock();
        }
    }

    private synchronized void stopNative() {
        if (process != null) {
            try { process.destroy(); } catch (Exception ignored) {}
            process = null;
        }
        running = false;
        status = "STOPPED";
        appendLog("PicoClaw stopped");
        releaseWakeLock();
    }

    private synchronized void restartNative() {
        desiredRunning = true;
        appendLog("Manual restart requested");
        stopNative();
        try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        startNative();
    }

    private void startWatchdog() {
        if (watchdogRunning) return;
        watchdogRunning = true;
        watchdogThread = new Thread(() -> {
            while (watchdogRunning) {
                try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
                if (!watchdogRunning) break;
                if (desiredRunning && !running) {
                    restartCount++;
                    appendLog("Watchdog: gateway down, auto-restart #" + restartCount);
                    startNative();
                    continue;
                }
                if (running && !gatewayHealthy()) {
                    long age = System.currentTimeMillis() - lastStartAt;
                    if (age > 20000) {
                        restartCount++;
                        appendLog("Watchdog: /health unavailable, restarting #" + restartCount);
                        restartNative();
                    }
                }
            }
        }, "picoclaw-watchdog");
        watchdogThread.start();
    }

    private boolean gatewayHealthy() {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL("http://127.0.0.1:18790/health").openConnection();
            c.setConnectTimeout(1200);
            c.setReadTimeout(1200);
            c.setUseCaches(false);
            return c.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private void releaseWakeLock() {
        try { if (wakeLock != null && wakeLock.isHeld()) wakeLock.release(); } catch (Exception ignored) {}
        wakeLock = null;
    }

    private File configFile() {
        return new File(new File(getFilesDir(), "picoclaw"), "config.json");
    }

    private void startWebPanel() {
        if (panelRunning) return;
        panelRunning = true;
        panelThread = new Thread(() -> {
            try {
                panelSocket = new ServerSocket(PANEL_PORT);
                appendLog("Web control panel listening on :" + PANEL_PORT);
                while (panelRunning) {
                    final Socket client = panelSocket.accept();
                    new Thread(() -> handlePanelClient(client), "picoclaw-panel-client").start();
                }
            } catch (Exception e) {
                if (panelRunning) appendLog("Web panel error: " + e.getMessage());
            }
        }, "picoclaw-web-panel");
        panelThread.start();
    }

    private void stopWebPanel() {
        panelRunning = false;
        try { if (panelSocket != null) panelSocket.close(); } catch (Exception ignored) {}
        panelSocket = null;
    }

    private void handlePanelClient(Socket socket) {
        try {
            socket.setSoTimeout(5000);
            BufferedReader br = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            String request = br.readLine();
            if (request == null) return;
            String[] parts = request.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String path = parts.length > 1 ? parts[1] : "/";
            int q = path.indexOf('?'); if (q >= 0) path = path.substring(0,q);
            int contentLength = 0;
            String line;
            while ((line = br.readLine()) != null && line.length() > 0) {
                int p = line.indexOf(':');
                if (p > 0 && "content-length".equalsIgnoreCase(line.substring(0, p).trim())) {
                    try { contentLength = Integer.parseInt(line.substring(p + 1).trim()); } catch (Exception ignored) {}
                }
            }
            char[] bodyChars = new char[Math.max(0, contentLength)];
            int got = 0;
            while (got < bodyChars.length) {
                int n = br.read(bodyChars, got, bodyChars.length - got);
                if (n < 0) break;
                got += n;
            }
            String body = new String(bodyChars, 0, got);

            if ("GET".equals(method) && "/".equals(path)) {
                send(socket, 200, "text/html; charset=utf-8", panelHtml());
            } else if ("GET".equals(method) && "/api/status".equals(path)) {
                JSONObject o = SystemInfo.snapshot();
                o.put("running", running);
                o.put("desired_running", desiredRunning);
                o.put("gateway_healthy", gatewayHealthy());
                o.put("status", status);
                o.put("last_line", lastLine);
                o.put("logs", recentLog());
                o.put("restart_count", restartCount);
                o.put("gateway", "http://<IP-HP>:18790");
                o.put("panel", "http://<IP-HP>:18791");
                send(socket, 200, "application/json; charset=utf-8", o.toString());
            } else if ("GET".equals(method) && "/api/system".equals(path)) {
                send(socket, 200, "application/json; charset=utf-8", SystemInfo.snapshot().toString());
            } else if ("GET".equals(method) && "/api/config".equals(path)) {
                send(socket, 200, "application/json; charset=utf-8", read(configFile()));
            } else if ("POST".equals(method) && "/api/start".equals(path)) {
                desiredRunning = true;
                startNative();
                send(socket, 200, "application/json", "{\"ok\":true}");
            } else if ("POST".equals(method) && "/api/stop".equals(path)) {
                desiredRunning = false;
                stopNative();
                send(socket, 200, "application/json", "{\"ok\":true}");
            } else if ("POST".equals(method) && "/api/restart".equals(path)) {
                restartNative();
                send(socket, 200, "application/json", "{\"ok\":true}");
            } else if ("POST".equals(method) && "/api/config".equals(path)) {
                try {
                    new JSONObject(body);
                    write(configFile(), body);
                    appendLog("Config saved from web panel");
                    send(socket, 200, "application/json", "{\"ok\":true}");
                } catch (Exception e) {
                    send(socket, 400, "application/json", new JSONObject().put("ok", false).put("error", e.getMessage()).toString());
                }
            } else {
                send(socket, 404, "text/plain; charset=utf-8", "404 not found");
            }
        } catch (Exception ignored) {
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void send(Socket socket, int code, String type, String body) throws IOException {
        byte[] data = body.getBytes(Charset.forName("UTF-8"));
        String reason = code == 200 ? "OK" : code == 400 ? "Bad Request" : "Not Found";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Connection: close\r\n" +
                "Cache-Control: no-store\r\n\r\n";
        OutputStream out = socket.getOutputStream();
        out.write(head.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    private String panelHtml() {
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>PicoClaw Mobile</title><style>" +
                "*{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif;background:#0b0f14;color:#e8edf2}.top{padding:18px 24px;border-bottom:1px solid #27313d;background:#10161d}.top b{font-size:20px}.wrap{max-width:1250px;margin:auto;padding:24px}.grid{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}.card{background:#141b24;border:1px solid #293442;border-radius:14px;padding:18px;margin-bottom:16px}.label{color:#8fa1b3;font-size:12px}.big{font-size:24px;font-weight:700;margin-top:5px}.small{font-size:13px;color:#91a0af}.ok{color:#35d07f}.bad{color:#ff667a}.warn{color:#ffb454}button{border:0;border-radius:10px;padding:12px 18px;color:white;font-weight:700;cursor:pointer;margin-right:8px}.start{background:#10a37f}.stop{background:#c94d59}.restart{background:#4f7cff}.save{background:#ff6b35}textarea{width:100%;min-height:360px;background:#0b1016;color:#dfe8f1;border:1px solid #334253;border-radius:10px;padding:14px;font-family:monospace;font-size:13px}pre{white-space:pre-wrap;word-break:break-word;background:#090d12;border-radius:10px;padding:14px;max-height:360px;overflow:auto}.muted{color:#91a0af}@media(max-width:900px){.grid{grid-template-columns:repeat(2,1fr)}}@media(max-width:600px){.grid{grid-template-columns:1fr}.wrap{padding:14px}}" +
                "</style></head><body><div class='top'><b>PicoClaw Mobile v0.3</b> <span class='muted'>ARMv7 · Android 5+</span></div><div class='wrap'>" +
                "<div class='grid'><div class='card'><div class='label'>Gateway</div><div class='big'>:18790</div><div id='health' class='small'>...</div></div><div class='card'><div class='label'>Status</div><div id='st' class='big'>...</div><div id='restarts' class='small'></div></div><div class='card'><div class='label'>RAM</div><div id='ram' class='big'>...</div><div id='ram2' class='small'></div></div><div class='card'><div class='label'>Storage</div><div id='disk' class='big'>...</div><div id='uptime' class='small'></div></div></div>" +
                "<div class='card'><button class='start' onclick=post('/api/start')>START</button><button class='stop' onclick=post('/api/stop')>STOP</button><button class='restart' onclick=post('/api/restart')>RESTART</button><span id='last' class='muted'></span></div>" +
                "<div class='card'><h3>config.json</h3><textarea id='cfg'></textarea><br><br><button class='save' onclick=saveCfg()>SAVE CONFIG</button> <span class='muted'>Setelah save, tekan RESTART.</span></div>" +
                "<div class='card'><h3>Recent Logs</h3><pre id='logs'>loading...</pre></div></div>" +
                "<script>function mb(k){return (k/1024).toFixed(0)+' MiB'}function gb(b){return (b/1073741824).toFixed(1)+' GB'}function up(s){s=Math.floor(s||0);let d=Math.floor(s/86400),h=Math.floor((s%86400)/3600),m=Math.floor((s%3600)/60);return (d?d+'d ':'')+h+'h '+m+'m'}async function post(p){await fetch(p,{method:'POST'});setTimeout(refresh,700)}async function saveCfg(){let r=await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:document.getElementById('cfg').value});if(r.ok)alert('Config tersimpan');else alert(await r.text())}async function refresh(){try{let s=await (await fetch('/api/status')).json();let e=document.getElementById('st');e.textContent=s.status;e.className='big '+(s.running?'ok':'bad');document.getElementById('health').textContent=s.gateway_healthy?'health: OK':'health: DOWN';document.getElementById('health').className='small '+(s.gateway_healthy?'ok':'bad');document.getElementById('restarts').textContent='watchdog restarts: '+s.restart_count;document.getElementById('ram').textContent=(s.ram_used_percent||0).toFixed(1)+'%';document.getElementById('ram2').textContent=mb(s.ram_used_kb||0)+' / '+mb(s.ram_total_kb||0)+' · available '+mb(s.ram_available_kb||0)+' · swap '+mb(s.swap_used_kb||0);document.getElementById('disk').textContent=(s.disk_used_percent||0).toFixed(1)+'%';document.getElementById('uptime').textContent='free '+gb(s.disk_free_bytes||0)+' · uptime '+up(s.uptime_seconds);document.getElementById('last').textContent=s.last_line;document.getElementById('logs').textContent=s.logs}catch(e){}}async function load(){document.getElementById('cfg').value=await (await fetch('/api/config')).text();refresh();setInterval(refresh,2000)}load()</script></body></html>";
    }

    private void writeDefaultConfig(File file, File workspace) throws IOException {
        String p = workspace.getAbsolutePath().replace("\\", "\\\\").replace("\"", "\\\"");
        String json = "{\n" +
                "  \"gateway\": {\"host\": \"0.0.0.0\", \"port\": 18790, \"log_level\": \"info\"},\n" +
                "  \"agents\": {\"defaults\": {\"workspace\": \"" + p + "\", \"restrict_to_workspace\": true}},\n" +
                "  \"model_list\": []\n" +
                "}\n";
        write(file, json);
    }

    private static String read(File f) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close();
        return sb.toString();
    }

    private static void write(File f, String s) throws IOException {
        File parent = f.getParentFile();
        if (parent != null) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        fos.write(s.getBytes("UTF-8"));
        fos.close();
    }
}
