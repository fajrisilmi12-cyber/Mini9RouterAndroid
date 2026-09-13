package id.my.picoclaw;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.PowerManager;

import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

public class PicoClawService extends Service {
    public static volatile boolean running = false;
    public static volatile String status = "STOPPED";
    public static volatile String lastLine = "-";

    private static final int PANEL_PORT = 18791;
    private static final int MAX_LOG_LINES = 80;
    private static final Deque<String> recentLogs = new ArrayDeque<>();

    private Process process;
    private Thread readerThread;
    private PowerManager.WakeLock wakeLock;
    private volatile boolean panelRunning = false;
    private ServerSocket panelSocket;
    private Thread panelThread;

    @Override public void onCreate() {
        super.onCreate();
        startWebPanel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "START" : intent.getStringExtra("action");
        if ("STOP".equals(action)) {
            stopNative();
            return START_STICKY;
        }
        if (!running) startNative();
        return START_STICKY;
    }

    @Override public void onDestroy() {
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
        line = line.trim();
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
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PicoClawARMv7:Gateway");
            wakeLock.acquire();

            process = pb.start();
            running = true;
            status = "RUNNING";
            appendLog("PicoClaw gateway starting on :18790");

            readerThread = new Thread(() -> {
                try {
                    BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    String line;
                    while ((line = br.readLine()) != null) appendLog(line);
                    int code = process.waitFor();
                    appendLog("PicoClaw exited with code " + code);
                    status = "EXITED (" + code + ")";
                } catch (Exception e) {
                    appendLog(e.getClass().getSimpleName() + ": " + e.getMessage());
                    status = "ERROR";
                } finally {
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
                JSONObject o = new JSONObject();
                o.put("running", running);
                o.put("status", status);
                o.put("last_line", lastLine);
                o.put("logs", recentLog());
                o.put("gateway", "http://<IP-HP>:18790");
                o.put("panel", "http://<IP-HP>:18791");
                send(socket, 200, "application/json; charset=utf-8", o.toString());
            } else if ("GET".equals(method) && "/api/config".equals(path)) {
                send(socket, 200, "application/json; charset=utf-8", read(configFile()));
            } else if ("POST".equals(method) && "/api/start".equals(path)) {
                startNative();
                send(socket, 200, "application/json", "{\"ok\":true}");
            } else if ("POST".equals(method) && "/api/stop".equals(path)) {
                stopNative();
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
                "*{box-sizing:border-box}body{margin:0;font-family:Arial,sans-serif;background:#0b0f14;color:#e8edf2}.top{padding:18px 24px;border-bottom:1px solid #27313d;background:#10161d}.top b{font-size:20px}.wrap{max-width:1200px;margin:auto;padding:24px}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:14px}.card{background:#141b24;border:1px solid #293442;border-radius:14px;padding:18px;margin-bottom:16px}.label{color:#8fa1b3;font-size:12px}.big{font-size:24px;font-weight:700;margin-top:5px}.ok{color:#35d07f}.bad{color:#ff667a}button{border:0;border-radius:10px;padding:12px 18px;color:white;font-weight:700;cursor:pointer;margin-right:8px}.start{background:#10a37f}.stop{background:#c94d59}.save{background:#ff6b35}textarea{width:100%;min-height:360px;background:#0b1016;color:#dfe8f1;border:1px solid #334253;border-radius:10px;padding:14px;font-family:monospace;font-size:13px}pre{white-space:pre-wrap;word-break:break-word;background:#090d12;border-radius:10px;padding:14px;max-height:320px;overflow:auto}.muted{color:#91a0af}@media(max-width:800px){.grid{grid-template-columns:1fr}.wrap{padding:14px}}" +
                "</style></head><body><div class='top'><b>PicoClaw Mobile</b> <span class='muted'>ARMv7 · Android 5+</span></div><div class='wrap'>" +
                "<div class='grid'><div class='card'><div class='label'>Gateway</div><div class='big'>:18790</div></div><div class='card'><div class='label'>Control Panel</div><div class='big'>:18791</div></div><div class='card'><div class='label'>Status</div><div id='st' class='big'>...</div></div></div>" +
                "<div class='card'><button class='start' onclick=post('/api/start')>START</button><button class='stop' onclick=post('/api/stop')>STOP</button><span id='last' class='muted'></span></div>" +
                "<div class='card'><h3>config.json</h3><textarea id='cfg'></textarea><br><br><button class='save' onclick=saveCfg()>SAVE CONFIG</button> <span class='muted'>Setelah save: STOP lalu START.</span></div>" +
                "<div class='card'><h3>Recent Logs</h3><pre id='logs'>loading...</pre></div></div>" +
                "<script>async function post(p){await fetch(p,{method:'POST'});setTimeout(refresh,300)}async function saveCfg(){let r=await fetch('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:document.getElementById('cfg').value});if(r.ok)alert('Config tersimpan');else alert(await r.text())}async function refresh(){try{let s=await (await fetch('/api/status')).json();let e=document.getElementById('st');e.textContent=s.status;e.className='big '+(s.running?'ok':'bad');document.getElementById('last').textContent=s.last_line;document.getElementById('logs').textContent=s.logs}catch(e){}}async function load(){document.getElementById('cfg').value=await (await fetch('/api/config')).text();refresh();setInterval(refresh,1500)}load()</script></body></html>";
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
