package id.my.minirouter;

import android.app.Service;
import android.content.*;
import android.os.*;

import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

public class GatewayService extends Service {
    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private PowerManager.WakeLock wakeLock;

    private static final int MAX_PROVIDERS = 10;
    private static final AtomicLong REQ = new AtomicLong();
    private static final AtomicLong FAIL = new AtomicLong();
    private static volatile String lastProvider = "-";
    private static volatile String lastError = "-";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (!running) startGateway();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        try { if (serverSocket != null) serverSocket.close(); } catch (Exception ignored) {}
        if (pool != null) pool.shutdownNow();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override public android.os.IBinder onBind(Intent intent) { return null; }

    private void startGateway() {
        final SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int port;
        try { port = Integer.parseInt(cfg.getString("port", "20128")); }
        catch (Exception e) { port = 20128; }

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mini9Router:Server");
            wakeLock.acquire();

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
            pool = Executors.newFixedThreadPool(4);
            running = true;

            acceptThread = new Thread(() -> {
                while (running) {
                    try {
                        final Socket s = serverSocket.accept();
                        pool.submit(() -> handle(s, cfg));
                    } catch (Exception e) {
                        if (running) e.printStackTrace();
                    }
                }
            }, "9router-mobile");
            acceptThread.start();
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            stopSelf();
        }
    }

    private void handle(Socket socket, SharedPreferences cfg) {
        try {
            socket.setSoTimeout(180000);
            socket.setTcpNoDelay(true);
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());

            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] first = requestLine.split(" ");
            if (first.length < 2) { respond(out, 400, "{\"error\":\"bad request\"}"); return; }
            String method = first[0];
            String rawPath = first[1];
            String path = rawPath.split("\\?", 2)[0];

            Map<String,String> headers = new HashMap<>();
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase(Locale.US);
                    String v = line.substring(idx + 1).trim();
                    headers.put(k, v);
                    if ("content-length".equals(k)) {
                        try { contentLength = Integer.parseInt(v); } catch (Exception ignored) {}
                    }
                }
            }

            byte[] body = new byte[Math.max(0, contentLength)];
            int off = 0;
            while (off < body.length) {
                int n = in.read(body, off, body.length - off);
                if (n < 0) break;
                off += n;
            }

            if ("OPTIONS".equals(method)) { respond(out, 204, ""); return; }

            // Web dashboard and management endpoints are intentionally reachable from the LAN.
            if ("GET".equals(method) && ("/dashboard".equals(path) || "/".equals(path))) {
                writeHtml(out, dashboardHtml(cfg));
                return;
            }

            if ("POST".equals(method) && "/dashboard/save".equals(path)) {
                Map<String,String> f = parseForm(new String(body, StandardCharsets.UTF_8));
                SharedPreferences.Editor e = cfg.edit();
                saveField(e, f, "model");
                saveField(e, f, "localKey");

                int providerCount = 3;
                try { providerCount = Integer.parseInt(f.get("providerCount")); } catch (Exception ignored) {}
                providerCount = Math.max(1, Math.min(MAX_PROVIDERS, providerCount));
                e.putInt("providerCount", providerCount);

                for (int i=1; i<=MAX_PROVIDERS; i++) {
                    e.putBoolean("p"+i+"Enabled", "on".equals(f.get("p"+i+"Enabled")));
                    saveField(e, f, "p"+i+"Name");
                    saveField(e, f, "p"+i+"Url");
                    saveField(e, f, "p"+i+"Key");
                    saveField(e, f, "p"+i+"Model");
                    saveField(e, f, "p"+i+"Type");
                }
                e.apply();
                redirect(out, "/dashboard?saved=1");
                return;
            }

            if ("GET".equals(method) && "/codex/oauth/start".equals(path)) {
                try {
                    CodexOAuth.start(cfg);
                    redirect(out, "/dashboard?codex=started");
                } catch (Exception e) {
                    lastError = "Codex OAuth start: " + String.valueOf(e.getMessage());
                    writeHtml(out, simpleErrorPage("Codex OAuth gagal dimulai", e));
                }
                return;
            }

            if ("GET".equals(method) && "/codex/oauth/poll".equals(path)) {
                String status = CodexOAuth.poll(cfg);
                respond(out, 200, "{\"status\":\"" + esc(status) + "\"}");
                return;
            }

            if ("POST".equals(method) && "/codex/oauth/disconnect".equals(path)) {
                CodexOAuth.disconnect(cfg);
                redirect(out, "/dashboard?codex=disconnected");
                return;
            }

            if ("GET".equals(method) && "/health".equals(path)) {
                respond(out, 200, "{\"ok\":true,\"service\":\"9Router Mobile\",\"requests\":"+REQ.get()+",\"failures\":"+FAIL.get()+",\"codex_oauth\":"+CodexOAuth.isConnected(cfg)+"}");
                return;
            }

            String localKey = cfg.getString("localKey", "");
            if (!localKey.isEmpty()) {
                String auth = headers.get("authorization");
                String apiKey = headers.get("api-key");
                String xApiKey = headers.get("x-api-key");
                boolean valid = ("Bearer " + localKey).equals(auth) || localKey.equals(apiKey) || localKey.equals(xApiKey);
                if (!valid) { respond(out, 401, "{\"error\":{\"message\":\"Unauthorized\"}}"); return; }
            }

            if ("GET".equals(method) && ("/v1/models".equals(path) || "/models".equals(path))) {
                String m = esc(cfg.getString("model", "AGY"));
                respond(out, 200, "{\"object\":\"list\",\"data\":[{\"id\":\""+m+"\",\"object\":\"model\",\"owned_by\":\"9router-mobile\"}]}");
                return;
            }

            if ("GET".equals(method) && "/v1/providers".equals(path)) {
                respond(out, 200, providersJson(cfg));
                return;
            }

            if ("POST".equals(method) && ("/v1/chat/completions".equals(path) || "/chat/completions".equals(path))) {
                String bodyText = new String(body, StandardCharsets.UTF_8);
                boolean wantsStream = bodyText.matches("(?s).*\\\"stream\\\"\\s*:\\s*true.*");
                REQ.incrementAndGet();
                proxyWithFallback(out, bodyText, cfg, wantsStream);
                return;
            }

            respond(out, 404, "{\"error\":{\"message\":\"Not found\"}}");
        } catch (Exception e) {
            FAIL.incrementAndGet();
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            try { respond(socket.getOutputStream(), 500, "{\"error\":{\"message\":\"Internal error\"}}"); }
            catch (Exception ignored) {}
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void proxyWithFallback(OutputStream client, String body, SharedPreferences cfg, boolean wantsStream) throws Exception {
        Exception last = null;
        int providerCount = Math.max(1, Math.min(MAX_PROVIDERS, cfg.getInt("providerCount", 3)));

        for (int i=1; i<=providerCount; i++) {
            if (!cfg.getBoolean("p"+i+"Enabled", i==1)) continue;

            String name = cfg.getString("p"+i+"Name", "Provider "+i);
            String type = cfg.getString("p"+i+"Type", "openai");
            String base = cfg.getString("p"+i+"Url", i==1 ? cfg.getString("baseUrl", "https://router.bynara.id/v1") : "");
            String key = cfg.getString("p"+i+"Key", i==1 ? cfg.getString("providerKey", "") : "");
            String upstreamModel = cfg.getString("p"+i+"Model", "");

            try {
                if ("codex".equals(type)) {
                    String result = CodexOAuth.chat(cfg, body, upstreamModel);
                    if (wantsStream) writeSyntheticStream(client, result);
                    else writeRaw(client, 200, "OK", result.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
                } else {
                    if (base == null || base.trim().isEmpty()) continue;
                    String sendBody = body;
                    if (upstreamModel != null && !upstreamModel.trim().isEmpty()) sendBody = replaceJsonModel(sendBody, upstreamModel.trim());
                    proxyOne(client, sendBody.getBytes(StandardCharsets.UTF_8), base, key, wantsStream);
                }
                lastProvider = name;
                lastError = "-";
                return;
            } catch (Exception ex) {
                last = ex;
                FAIL.incrementAndGet();
                lastError = name + ": " + String.valueOf(ex.getMessage());
            }
        }

        throw last != null ? last : new IOException("No enabled provider");
    }

    private void proxyOne(OutputStream client, byte[] body, String base, String key, boolean wantsStream) throws Exception {
        while (base.endsWith("/")) base = base.substring(0, base.length()-1);
        URL url = new URL(base + "/chat/completions");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", wantsStream ? "text/event-stream" : "application/json");
        if (key != null && !key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = c.getOutputStream()) { os.write(body); os.flush(); }

        int code = c.getResponseCode();
        InputStream pin = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (code < 200 || code >= 300) {
            byte[] err = pin == null ? new byte[0] : readAll(pin);
            c.disconnect();
            throw new IOException("HTTP " + code + " " + new String(err, StandardCharsets.UTF_8));
        }
        if (pin == null) pin = new ByteArrayInputStream(new byte[0]);
        String type = c.getContentType();

        if (wantsStream) {
            writeStreamHeaders(client, type);
            byte[] buf = new byte[1024];
            int n;
            while ((n = pin.read(buf)) != -1) if (n > 0) writeChunk(client, buf, n);
            finishChunks(client);
        } else {
            writeRaw(client, 200, "OK", readAll(pin), type);
        }
        c.disconnect();
    }

    private static void writeSyntheticStream(OutputStream out, String chatJson) throws Exception {
        JSONObject root = new JSONObject(chatJson);
        String content = root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
        JSONObject delta = new JSONObject();
        delta.put("content", content);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("delta", delta);
        choice.put("finish_reason", JSONObject.NULL);
        org.json.JSONArray choices = new org.json.JSONArray();
        choices.put(choice);
        JSONObject chunk = new JSONObject();
        chunk.put("id", root.optString("id", "codex"));
        chunk.put("object", "chat.completion.chunk");
        chunk.put("created", root.optLong("created", System.currentTimeMillis()/1000L));
        chunk.put("model", root.optString("model", "codex"));
        chunk.put("choices", choices);

        byte[] payload = ("data: " + chunk.toString() + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
        writeStreamHeaders(out, "text/event-stream; charset=utf-8");
        writeChunk(out, payload, payload.length);
        finishChunks(out);
    }

    private static String replaceJsonModel(String body, String model) {
        String escaped = model.replace("\\", "\\\\").replace("\"", "\\\"");
        if (body.matches("(?s).*\\\"model\\\"\\s*:.*")) {
            return body.replaceFirst("\\\"model\\\"\\s*:\\s*\\\"[^\\\"]*\\\"", "\\\"model\\\":\\\""+escaped+"\\\"");
        }
        return body;
    }

    private static String providersJson(SharedPreferences c) {
        int providerCount = Math.max(1, Math.min(MAX_PROVIDERS, c.getInt("providerCount", 3)));
        StringBuilder b = new StringBuilder("{\"providers\":[");
        for (int i=1; i<=providerCount; i++) {
            if (i>1) b.append(',');
            b.append("{\"slot\":").append(i)
             .append(",\"name\":\"").append(esc(c.getString("p"+i+"Name","Provider "+i)))
             .append("\",\"type\":\"").append(esc(c.getString("p"+i+"Type","openai")))
             .append("\",\"enabled\":").append(c.getBoolean("p"+i+"Enabled", i==1)).append("}");
        }
        return b.append("],\"codex_oauth\":").append(CodexOAuth.isConnected(c)).append('}').toString();
    }

    private static String dashboardHtml(SharedPreferences c) {
        int providerCount = Math.max(1, Math.min(MAX_PROVIDERS, c.getInt("providerCount", 3)));
        boolean codexConnected = CodexOAuth.isConnected(c);
        String codexCode = CodexOAuth.userCode(c);
        StringBuilder h = new StringBuilder();

        h.append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>9Router Mobile</title><style>")
         .append("body{font-family:Arial;background:#111;color:#eee;margin:0;padding:20px}.box{max-width:900px;margin:auto}.card{background:#1c1c1c;padding:16px;margin:12px 0;border-radius:12px}input,select{width:100%;box-sizing:border-box;padding:10px;margin:5px 0;background:#292929;color:#fff;border:1px solid #555;border-radius:7px}button,.btn{display:inline-block;padding:12px 18px;border:0;border-radius:8px;font-weight:bold;margin:5px 4px 5px 0;text-decoration:none}.add{background:#2457d6;color:#fff}.save{background:#eee;color:#111}.oauth{background:#10a37f;color:#fff}.danger{background:#8b2635;color:#fff}small{color:#aaa}.ok{color:#6f6}.bad{color:#f77}.provider-hidden{display:none}.code{font-size:28px;letter-spacing:4px;font-weight:bold;background:#0d0d0d;padding:12px;border-radius:8px;display:inline-block}</style></head><body><div class='box'>")
         .append("<h1>9Router Mobile</h1>")
         .append("<div class='card'><b>Status:</b> RUNNING<br>Requests: ").append(REQ.get()).append(" &nbsp; Failures: ").append(FAIL.get()).append("<br>Last provider: ").append(html(lastProvider)).append("<br><small>Last error: ").append(html(lastError)).append("</small></div>");

        h.append("<div class='card'><h3>Codex OAuth</h3><p>Status: <b class='").append(codexConnected?"ok":"bad").append("'>").append(codexConnected?"CONNECTED":"DISCONNECTED").append("</b></p>");
        if (codexConnected) {
            h.append("<form method='post' action='/codex/oauth/disconnect'><button class='danger' type='submit'>DISCONNECT CODEX</button></form><small>Token OAuth disimpan lokal di private storage aplikasi.</small>");
        } else if (!codexCode.isEmpty()) {
            h.append("<p>1. Buka <a style='color:#7aa7ff' target='_blank' href='").append(CodexOAuth.VERIFY_URL).append("'>").append(CodexOAuth.VERIFY_URL).append("</a></p>")
             .append("<p>2. Masukkan kode:</p><div class='code'>").append(html(codexCode)).append("</div><p id='codexPoll'>Menunggu login...</p>")
             .append("<script>function pollCodex(){fetch('/codex/oauth/poll').then(function(r){return r.json()}).then(function(x){document.getElementById('codexPoll').innerText='Status: '+x.status;if(x.status==='connected'){location.href='/dashboard?codex=connected';return;}if(x.status==='pending'){setTimeout(pollCodex,3000);}}).catch(function(){setTimeout(pollCodex,4000);});}setTimeout(pollCodex,2000);</script>");
        } else {
            h.append("<a class='btn oauth' href='/codex/oauth/start'>CONNECT CODEX OAUTH</a><br><small>Menggunakan device-code flow Codex. Login dilakukan di browser; password ChatGPT tidak masuk ke router.</small>");
        }
        h.append("</div>");

        h.append("<form method='post' action='/dashboard/save' id='cfgForm'><input type='hidden' id='providerCount' name='providerCount' value='").append(providerCount).append("'>")
         .append("<div class='card'><h3>Gateway</h3><label>Public model alias</label><input name='model' value='").append(attr(c.getString("model","AGY"))).append("'><label>Local API key</label><input type='password' name='localKey' value='").append(attr(c.getString("localKey",""))).append("'></div>");

        for (int i=1; i<=MAX_PROVIDERS; i++) {
            boolean visible = i <= providerCount;
            String type = c.getString("p"+i+"Type", "openai");
            h.append("<div class='card provider-card").append(visible ? "" : " provider-hidden").append("' id='providerCard").append(i).append("'><h3>Provider ").append(i).append(i==1?" · Primary":" · Fallback").append("</h3>")
             .append("<label><input style='width:auto' type='checkbox' name='p").append(i).append("Enabled' ").append(c.getBoolean("p"+i+"Enabled",i==1)?"checked":"").append("> Enabled</label>")
             .append("<label>Provider type</label><select name='p").append(i).append("Type'><option value='openai' ").append("openai".equals(type)?"selected":"").append(">OpenAI Compatible</option><option value='codex' ").append("codex".equals(type)?"selected":"").append(">Codex OAuth (ChatGPT)</option></select>")
             .append("<input name='p").append(i).append("Name' placeholder='Name' value='").append(attr(c.getString("p"+i+"Name",i==1?"Primary":"Fallback "+(i-1)))).append("'>")
             .append("<input name='p").append(i).append("Url' placeholder='Base URL (diabaikan untuk Codex OAuth)' value='").append(attr(c.getString("p"+i+"Url",i==1?c.getString("baseUrl","https://router.bynara.id/v1"):""))).append("'>")
             .append("<input type='password' name='p").append(i).append("Key' placeholder='API key (diabaikan untuk Codex OAuth)' value='").append(attr(c.getString("p"+i+"Key",i==1?c.getString("providerKey",""):""))).append("'>")
             .append("<input name='p").append(i).append("Model' placeholder='Upstream model (wajib untuk Codex OAuth)' value='").append(attr(c.getString("p"+i+"Model",""))).append("'></div>");
        }

        h.append("<button class='add' type='button' id='addFallback' onclick='addFallbackProvider()'>+ ADD FALLBACK</button><button class='save' type='submit'>SAVE CONFIG</button></form>")
         .append("<p><small>Maksimal ").append(MAX_PROVIDERS).append(" provider. Urutan provider menentukan prioritas fallback. Codex OAuth saat ini memakai adapter text Chat Completions → Responses API.</small></p>")
         .append("<p><small>API endpoint: /v1/chat/completions · /v1/models · /v1/providers · /health</small></p>")
         .append("<script>function addFallbackProvider(){var c=document.getElementById('providerCount');var n=parseInt(c.value||'1',10);if(n>=").append(MAX_PROVIDERS).append("){alert('Maksimal ").append(MAX_PROVIDERS).append(" provider');return;}n++;c.value=n;var card=document.getElementById('providerCard'+n);if(card)card.className='card provider-card';if(card&&card.scrollIntoView)card.scrollIntoView({behavior:'smooth',block:'center'});}</script>")
         .append("</div></body></html>");
        return h.toString();
    }

    private static String simpleErrorPage(String title, Exception e) {
        return "<!doctype html><html><body style='font-family:Arial;background:#111;color:#eee;padding:24px'><h2>"+html(title)+"</h2><pre>"+html(String.valueOf(e.getMessage()))+"</pre><a style='color:#7aa7ff' href='/dashboard'>Kembali ke dashboard</a></body></html>";
    }

    private static void saveField(SharedPreferences.Editor e, Map<String,String> f, String k) {
        if (f.containsKey(k)) e.putString(k, f.get(k));
    }

    private static Map<String,String> parseForm(String s) throws Exception {
        Map<String,String> m = new HashMap<>();
        for (String p : s.split("&")) {
            int x=p.indexOf('=');
            String k=x>=0?p.substring(0,x):p;
            String v=x>=0?p.substring(x+1):"";
            m.put(URLDecoder.decode(k,"UTF-8"), URLDecoder.decode(v,"UTF-8"));
        }
        return m;
    }

    private static String html(String s){
        return s==null?"":s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
    }

    private static String attr(String s){
        return html(s).replace("'","&#39;").replace("\"","&quot;");
    }

    private static void redirect(OutputStream out, String path) throws IOException {
        String h="HTTP/1.1 303 See Other\r\nLocation: "+path+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writeHtml(OutputStream out, String html) throws IOException {
        byte[] d=html.getBytes(StandardCharsets.UTF_8);
        String h="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+d.length+"\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(d);
        out.flush();
    }

    private static void writeStreamHeaders(OutputStream out, String type) throws IOException {
        if (type==null || !type.toLowerCase(Locale.US).contains("text/event-stream")) type="text/event-stream; charset=utf-8";
        String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+"\r\nCache-Control: no-cache, no-transform\r\nTransfer-Encoding: chunked\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, api-key, x-api-key\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: keep-alive\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writeChunk(OutputStream out, byte[] data, int len) throws IOException {
        out.write((Integer.toHexString(len)+"\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(data,0,len);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void finishChunks(OutputStream out) throws IOException {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        int prev=-1,cur;
        while((cur=in.read())!=-1){
            if(prev=='\r'&&cur=='\n'){
                byte[] d=b.toByteArray();
                int l=d.length;
                if(l>0&&d[l-1]=='\r')l--;
                return new String(d,0,l,StandardCharsets.ISO_8859_1);
            }
            b.write(cur);
            prev=cur;
            if(b.size()>16384)throw new IOException("header too large");
        }
        return b.size()==0?null:new String(b.toByteArray(),StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        byte[] buf=new byte[8192];
        int n;
        while((n=in.read(buf))!=-1)b.write(buf,0,n);
        return b.toByteArray();
    }

    private static void respond(OutputStream out,int code,String json)throws IOException{
        writeRaw(out,code,code==200?"OK":"",json.getBytes(StandardCharsets.UTF_8),"application/json; charset=utf-8");
    }

    private static void writeRaw(OutputStream out,int code,String status,byte[] data,String type)throws IOException{
        if(type==null)type="application/json; charset=utf-8";
        String h="HTTP/1.1 "+code+" "+status+"\r\nContent-Type: "+type+"\r\nContent-Length: "+data.length+"\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, api-key, x-api-key\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String esc(String s){
        return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
