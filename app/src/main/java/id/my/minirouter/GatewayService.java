package id.my.minirouter;

import android.app.Service;
import android.content.*;
import android.os.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mini9Router Android
 * Lightweight 9Router-style gateway for Android 5+.
 */
public class GatewayService extends Service {
    private static final int MAX_PROVIDERS = 10;
    private static final int MAX_LOGS = 80;
    private static final long COOLDOWN_MS = 60_000L;

    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private PowerManager.WakeLock wakeLock;

    private static final AtomicLong REQ = new AtomicLong();
    private static final AtomicLong FAIL = new AtomicLong();
    private static final AtomicLong FALLBACKS = new AtomicLong();
    private static final ConcurrentHashMap<Integer, Long> COOLDOWN = new ConcurrentHashMap<Integer, Long>();
    private static final ArrayDeque<String> LOGS = new ArrayDeque<String>();
    private static volatile String lastProvider = "-";
    private static volatile String lastModel = "-";
    private static volatile String lastError = "-";

    private static class UpstreamException extends IOException {
        final int code;
        UpstreamException(int code, String message) { super(message); this.code = code; }
    }

    private static class Target {
        int slot;
        String model;
        Target(int slot, String model) { this.slot = slot; this.model = model; }
    }

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

    @Override public IBinder onBind(Intent intent) { return null; }

    private void startGateway() {
        final SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        REQ.set(cfg.getLong("stats_requests", 0));
        FAIL.set(cfg.getLong("stats_failures", 0));
        FALLBACKS.set(cfg.getLong("stats_fallbacks", 0));

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
            log("gateway started on 0.0.0.0:" + port);

            acceptThread = new Thread(new Runnable() {
                @Override public void run() {
                    while (running) {
                        try {
                            final Socket s = serverSocket.accept();
                            pool.submit(new Runnable() {
                                @Override public void run() { handle(s, cfg); }
                            });
                        } catch (Exception e) {
                            if (running) log("accept error: " + e.getMessage());
                        }
                    }
                }
            }, "mini9router-android");
            acceptThread.start();
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            log("startup failed: " + lastError);
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
            if (requestLine == null || requestLine.trim().isEmpty()) return;
            String[] first = requestLine.split(" ");
            if (first.length < 2) { respond(out, 400, "{\"error\":\"bad request\"}"); return; }

            String method = first[0].toUpperCase(Locale.US);
            String rawPath = first[1];
            String path = rawPath.split("\\?", 2)[0];

            Map<String,String> headers = new HashMap<String,String>();
            int contentLength = 0;
            boolean chunked = false;
            boolean expectContinue = false;
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
                    if ("transfer-encoding".equals(k) && v.toLowerCase(Locale.US).contains("chunked")) chunked = true;
                    if ("expect".equals(k) && v.toLowerCase(Locale.US).contains("100-continue")) expectContinue = true;
                }
            }

            if (expectContinue) {
                out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }

            byte[] body = chunked ? readChunkedBody(in, 4 * 1024 * 1024)
                                  : readFixed(in, contentLength, 4 * 1024 * 1024);

            if ("OPTIONS".equals(method)) { respond(out, 204, ""); return; }

            if ("GET".equals(method) && ("/dashboard".equals(path) || "/".equals(path))) {
                writeHtml(out, dashboardHtml(cfg));
                return;
            }

            if ("POST".equals(method) && "/dashboard/save".equals(path)) {
                saveDashboard(cfg, new String(body, StandardCharsets.UTF_8));
                redirect(out, "/dashboard?saved=1");
                return;
            }

            if ("POST".equals(method) && "/dashboard/reset-stats".equals(path)) {
                REQ.set(0); FAIL.set(0); FALLBACKS.set(0);
                cfg.edit().putLong("stats_requests",0).putLong("stats_failures",0).putLong("stats_fallbacks",0).apply();
                synchronized (LOGS) { LOGS.clear(); }
                redirect(out, "/dashboard#usage");
                return;
            }

            if ("GET".equals(method) && "/codex/oauth/start".equals(path)) {
                try {
                    CodexOAuth.start(cfg);
                    redirect(out, "/dashboard?codex=started#providers");
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
                redirect(out, "/dashboard?codex=disconnected#providers");
                return;
            }

            if ("GET".equals(method) && "/health".equals(path)) {
                respond(out, 200, healthJson(cfg));
                return;
            }

            if ("GET".equals(method) && "/api/status".equals(path)) {
                respond(out, 200, statusJson(cfg));
                return;
            }

            if ("GET".equals(method) && "/api/logs".equals(path)) {
                respond(out, 200, logsJson());
                return;
            }

            if (!isAuthorized(headers, cfg)) {
                respond(out, 401, "{\"error\":{\"message\":\"A valid API key is required\"}}");
                return;
            }

            if ("GET".equals(method) && ("/v1/models".equals(path) || "/models".equals(path))) {
                respond(out, 200, modelsJson(cfg));
                return;
            }

            if ("GET".equals(method) && "/v1/providers".equals(path)) {
                respond(out, 200, providersJson(cfg));
                return;
            }

            if ("POST".equals(method) && ("/v1/chat/completions".equals(path) || "/chat/completions".equals(path))) {
                String bodyText = new String(body, StandardCharsets.UTF_8);
                boolean wantsStream = jsonBoolean(bodyText, "stream", false);
                countRequest(cfg);
                routeRequest(out, bodyText, cfg, wantsStream, "chat/completions");
                return;
            }

            if ("POST".equals(method) && "/v1/responses".equals(path)) {
                String bodyText = new String(body, StandardCharsets.UTF_8);
                boolean wantsStream = jsonBoolean(bodyText, "stream", false);
                countRequest(cfg);
                routeRequest(out, bodyText, cfg, wantsStream, "responses");
                return;
            }

            if ("POST".equals(method) && "/v1/messages".equals(path)) {
                respond(out, 501, "{\"error\":{\"message\":\"Anthropic /v1/messages translation is not enabled yet\"}}");
                return;
            }

            respond(out, 404, "{\"error\":{\"message\":\"Not found\"}}");
        } catch (Exception e) {
            countFailure(cfg);
            lastError = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
            log("request failed: " + lastError);
            try { respond(socket.getOutputStream(), 500, "{\"error\":{\"message\":\""+esc(lastError)+"\"}}"); }
            catch (Exception ignored) {}
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
    }

    private void routeRequest(OutputStream client, String body, SharedPreferences cfg, boolean wantsStream, String endpoint) throws Exception {
        String requestedModel = jsonString(body, "model", cfg.getString("model", "AGY"));
        List<Target> targets = resolveTargets(requestedModel, cfg);
        if (targets.isEmpty()) targets = fallbackTargets(requestedModel, cfg);

        Exception last = null;
        int attempted = 0;

        for (Target t : targets) {
            if (t.slot < 1 || t.slot > MAX_PROVIDERS) continue;
            if (!cfg.getBoolean("p"+t.slot+"Enabled", t.slot == 1)) continue;

            Long until = COOLDOWN.get(t.slot);
            if (until != null && until > System.currentTimeMillis() && targets.size() > 1) continue;

            if (attempted++ > 0) {
                FALLBACKS.incrementAndGet();
                persistStats(cfg);
            }

            String name = cfg.getString("p"+t.slot+"Name", "Provider "+t.slot);
            String type = cfg.getString("p"+t.slot+"Type", "openai");
            String base = cfg.getString("p"+t.slot+"Url",
                    t.slot==1 ? cfg.getString("baseUrl", "https://router.bynara.id/v1") : "");
            String key = cfg.getString("p"+t.slot+"Key",
                    t.slot==1 ? cfg.getString("providerKey", "") : "");
            String model = t.model;

            if (model == null || model.trim().isEmpty())
                model = cfg.getString("p"+t.slot+"Model", requestedModel);
            if (model == null || model.trim().isEmpty()) model = requestedModel;

            try {
                String sendBody = replaceJsonModel(body, model);
                long started = System.currentTimeMillis();

                if ("codex".equals(type)) {
                    if (!"chat/completions".equals(endpoint))
                        throw new IOException("Codex OAuth adapter currently supports chat/completions only");
                    String result = CodexOAuth.chat(cfg, sendBody, model);
                    if (wantsStream) writeSyntheticStream(client, result);
                    else writeRaw(client, 200, "OK", result.getBytes(StandardCharsets.UTF_8),
                            "application/json; charset=utf-8");
                } else {
                    if (base == null || base.trim().isEmpty())
                        throw new IOException("provider base URL kosong");
                    proxyOne(client, sendBody.getBytes(StandardCharsets.UTF_8), base, key, wantsStream, endpoint);
                }

                COOLDOWN.remove(t.slot);
                lastProvider = name;
                lastModel = model;
                lastError = "-";
                log("OK " + name + " / " + model + " " + (System.currentTimeMillis()-started) + "ms");
                return;
            } catch (UpstreamException ex) {
                last = ex;
                lastError = name + ": HTTP " + ex.code + " " + ex.getMessage();
                log("FAIL " + lastError);
                if (isFallbackEligible(ex.code))
                    COOLDOWN.put(t.slot, System.currentTimeMillis() + COOLDOWN_MS);
                else
                    throw ex;
            } catch (Exception ex) {
                last = ex;
                lastError = name + ": " + String.valueOf(ex.getMessage());
                log("FAIL " + lastError);
                COOLDOWN.put(t.slot, System.currentTimeMillis() + COOLDOWN_MS);
            }
        }

        countFailure(cfg);
        throw last != null ? last : new IOException("No enabled provider available");
    }

    private static boolean isFallbackEligible(int code) {
        return code == 401 || code == 403 || code == 408 || code == 409 || code == 429 || code >= 500;
    }

    private List<Target> resolveTargets(String requestedModel, SharedPreferences cfg) {
        Map<String,String> combos = parseRules(cfg.getString("combos", ""));
        if (combos.containsKey(requestedModel)) {
            List<Target> out = parseTargetList(combos.get(requestedModel));
            if (!out.isEmpty()) return out;
        }

        Map<String,String> aliases = parseRules(cfg.getString("aliases", ""));
        if (aliases.containsKey(requestedModel)) {
            Target t = parseTarget(aliases.get(requestedModel));
            if (t != null) return Collections.singletonList(t);
        }

        if (requestedModel != null && requestedModel.matches("(?i)^p[0-9]+/.+")) {
            int slash = requestedModel.indexOf('/');
            try {
                int slot = Integer.parseInt(requestedModel.substring(1, slash));
                return Collections.singletonList(new Target(slot, requestedModel.substring(slash+1)));
            } catch (Exception ignored) {}
        }
        return Collections.emptyList();
    }

    private List<Target> fallbackTargets(String requestedModel, SharedPreferences cfg) {
        List<Target> out = new ArrayList<Target>();
        int count = Math.max(1, Math.min(MAX_PROVIDERS, cfg.getInt("providerCount", 3)));
        for (int i=1; i<=count; i++) {
            if (!cfg.getBoolean("p"+i+"Enabled", i==1)) continue;
            String m = cfg.getString("p"+i+"Model", "");
            out.add(new Target(i, m == null || m.trim().isEmpty() ? requestedModel : m.trim()));
        }
        return out;
    }

    private static Target parseTarget(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.startsWith("p") || s.startsWith("P")) s = s.substring(1);
        int slash = s.indexOf('/');
        if (slash <= 0) return null;
        try {
            int slot = Integer.parseInt(s.substring(0, slash).trim());
            String model = s.substring(slash+1).trim();
            if (slot < 1 || slot > MAX_PROVIDERS || model.isEmpty()) return null;
            return new Target(slot, model);
        } catch (Exception e) { return null; }
    }

    private static List<Target> parseTargetList(String raw) {
        List<Target> out = new ArrayList<Target>();
        if (raw == null) return out;
        for (String part : raw.split(",")) {
            Target t = parseTarget(part);
            if (t != null) out.add(t);
        }
        return out;
    }

    private static Map<String,String> parseRules(String text) {
        LinkedHashMap<String,String> out = new LinkedHashMap<String,String>();
        if (text == null) return out;
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq > 0) out.put(line.substring(0,eq).trim(), line.substring(eq+1).trim());
        }
        return out;
    }

    private void proxyOne(OutputStream client, byte[] body, String base, String key,
                          boolean wantsStream, String endpoint) throws Exception {
        while (base.endsWith("/")) base = base.substring(0, base.length()-1);
        URL url = new URL(base + "/" + endpoint);
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", wantsStream ? "text/event-stream" : "application/json");
        c.setRequestProperty("Connection", "keep-alive");
        if (key != null && !key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);

        OutputStream os = c.getOutputStream();
        os.write(body); os.flush(); os.close();

        int code = c.getResponseCode();
        InputStream pin = code >= 400 ? c.getErrorStream() : c.getInputStream();

        if (code < 200 || code >= 300) {
            byte[] err = pin == null ? new byte[0] : readAllLimited(pin, 256 * 1024);
            c.disconnect();
            throw new UpstreamException(code, new String(err, StandardCharsets.UTF_8));
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
            writeRaw(client, 200, "OK", readAllLimited(pin, 4 * 1024 * 1024), type);
        }
        c.disconnect();
    }

    private static void writeSyntheticStream(OutputStream out, String chatJson) throws Exception {
        JSONObject root = new JSONObject(chatJson);
        String content = root.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "");
        JSONObject delta = new JSONObject(); delta.put("content", content);
        JSONObject choice = new JSONObject();
        choice.put("index",0); choice.put("delta",delta); choice.put("finish_reason",JSONObject.NULL);
        JSONArray choices = new JSONArray(); choices.put(choice);
        JSONObject chunk = new JSONObject();
        chunk.put("id", root.optString("id","codex"));
        chunk.put("object","chat.completion.chunk");
        chunk.put("created", root.optLong("created",System.currentTimeMillis()/1000L));
        chunk.put("model", root.optString("model","codex"));
        chunk.put("choices",choices);

        byte[] payload = ("data: "+chunk.toString()+"\n\ndata: [DONE]\n\n")
                .getBytes(StandardCharsets.UTF_8);
        writeStreamHeaders(out,"text/event-stream; charset=utf-8");
        writeChunk(out,payload,payload.length);
        finishChunks(out);
    }

    private static String replaceJsonModel(String body, String model) {
        try {
            JSONObject o = new JSONObject(body);
            o.put("model", model);
            return o.toString();
        } catch (Exception e) {
            return body;
        }
    }

    private static boolean jsonBoolean(String body, String key, boolean def) {
        try { return new JSONObject(body).optBoolean(key, def); } catch (Exception e) { return def; }
    }

    private static String jsonString(String body, String key, String def) {
        try { return new JSONObject(body).optString(key, def); } catch (Exception e) { return def; }
    }

    private static boolean isAuthorized(Map<String,String> headers, SharedPreferences cfg) {
        LinkedHashSet<String> validKeys = new LinkedHashSet<String>();
        String legacy = cfg.getString("localKey", "");
        if (legacy != null && !legacy.trim().isEmpty()) validKeys.add(legacy.trim());

        String many = cfg.getString("localKeys", "");
        if (many != null) {
            for (String k : many.split("[,\\r\\n]+"))
                if (!k.trim().isEmpty()) validKeys.add(k.trim());
        }
        if (validKeys.isEmpty()) return true;

        String auth = headers.get("authorization");
        String api = headers.get("api-key");
        String xapi = headers.get("x-api-key");
        for (String k : validKeys) {
            if (("Bearer " + k).equals(auth) || k.equals(api) || k.equals(xapi)) return true;
        }
        return false;
    }

    private static String modelsJson(SharedPreferences c) {
        LinkedHashSet<String> models = new LinkedHashSet<String>();
        models.add(c.getString("model", "AGY"));
        models.addAll(parseRules(c.getString("aliases","")).keySet());
        models.addAll(parseRules(c.getString("combos","")).keySet());

        int count = Math.max(1, Math.min(MAX_PROVIDERS, c.getInt("providerCount",3)));
        for (int i=1;i<=count;i++) {
            if (!c.getBoolean("p"+i+"Enabled", i==1)) continue;
            String m=c.getString("p"+i+"Model","");
            if (m!=null && !m.trim().isEmpty()) models.add("p"+i+"/"+m.trim());
        }

        JSONArray a=new JSONArray();
        for(String m:models){
            if(m==null||m.trim().isEmpty()) continue;
            JSONObject o=new JSONObject();
            try {
                o.put("id",m);
                o.put("object","model");
                o.put("owned_by","mini9router-android");
                a.put(o);
            } catch(Exception ignored){}
        }
        JSONObject root=new JSONObject();
        try { root.put("object","list"); root.put("data",a); } catch(Exception ignored){}
        return root.toString();
    }

    private static String providersJson(SharedPreferences c) {
        JSONArray a = new JSONArray();
        int count = Math.max(1, Math.min(MAX_PROVIDERS, c.getInt("providerCount",3)));
        for(int i=1;i<=count;i++){
            JSONObject o=new JSONObject();
            try{
                o.put("slot",i);
                o.put("name",c.getString("p"+i+"Name","Provider "+i));
                o.put("type",c.getString("p"+i+"Type","openai"));
                o.put("enabled",c.getBoolean("p"+i+"Enabled",i==1));
                o.put("model",c.getString("p"+i+"Model",""));
                Long until=COOLDOWN.get(i);
                o.put("cooldown",until!=null&&until>System.currentTimeMillis());
                a.put(o);
            }catch(Exception ignored){}
        }
        JSONObject root=new JSONObject();
        try { root.put("providers",a); root.put("codex_oauth",CodexOAuth.isConnected(c)); }
        catch(Exception ignored){}
        return root.toString();
    }

    private static String healthJson(SharedPreferences c) {
        JSONObject o=new JSONObject();
        try {
            o.put("ok",true);
            o.put("service","Mini9Router Android");
            o.put("requests",REQ.get());
            o.put("failures",FAIL.get());
            o.put("fallbacks",FALLBACKS.get());
            o.put("streaming",true);
            o.put("chunked_request",true);
            o.put("codex_oauth",CodexOAuth.isConnected(c));
        } catch(Exception ignored){}
        return o.toString();
    }

    private static String statusJson(SharedPreferences c) {
        JSONObject o=new JSONObject();
        try {
            o.put("requests",REQ.get());
            o.put("failures",FAIL.get());
            o.put("fallbacks",FALLBACKS.get());
            o.put("last_provider",lastProvider);
            o.put("last_model",lastModel);
            o.put("last_error",lastError);
            o.put("providers",new JSONObject(providersJson(c)).getJSONArray("providers"));
        } catch(Exception ignored){}
        return o.toString();
    }

    private static String logsJson() {
        JSONArray a=new JSONArray();
        synchronized(LOGS){ for(String s:LOGS) a.put(s); }
        JSONObject o=new JSONObject();
        try { o.put("logs",a); } catch(Exception ignored){}
        return o.toString();
    }

    private static void countRequest(SharedPreferences c){ REQ.incrementAndGet(); persistStats(c); }
    private static void countFailure(SharedPreferences c){ FAIL.incrementAndGet(); persistStats(c); }
    private static void persistStats(SharedPreferences c){
        c.edit().putLong("stats_requests",REQ.get())
                .putLong("stats_failures",FAIL.get())
                .putLong("stats_fallbacks",FALLBACKS.get()).apply();
    }

    private static void log(String msg) {
        String row = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date()) + "  " + msg;
        synchronized(LOGS){
            LOGS.addFirst(row);
            while(LOGS.size()>MAX_LOGS) LOGS.removeLast();
        }
    }

    private static void saveDashboard(SharedPreferences cfg, String encoded) throws Exception {
        Map<String,String> f=parseForm(encoded);
        SharedPreferences.Editor e=cfg.edit();

        saveField(e,f,"model");
        saveField(e,f,"localKey");
        saveField(e,f,"localKeys");
        saveField(e,f,"aliases");
        saveField(e,f,"combos");

        int providerCount=3;
        try { providerCount=Integer.parseInt(f.get("providerCount")); } catch(Exception ignored){}
        providerCount=Math.max(1,Math.min(MAX_PROVIDERS,providerCount));
        e.putInt("providerCount",providerCount);

        for(int i=1;i<=MAX_PROVIDERS;i++){
            e.putBoolean("p"+i+"Enabled","on".equals(f.get("p"+i+"Enabled")));
            saveField(e,f,"p"+i+"Name");
            saveField(e,f,"p"+i+"Url");
            saveField(e,f,"p"+i+"Key");
            saveField(e,f,"p"+i+"Model");
            saveField(e,f,"p"+i+"Type");
        }
        e.apply();
    }

    private static String dashboardHtml(SharedPreferences c) {
        int providerCount=Math.max(1,Math.min(MAX_PROVIDERS,c.getInt("providerCount",3)));
        boolean codexConnected=CodexOAuth.isConnected(c);
        String codexCode=CodexOAuth.userCode(c);
        StringBuilder h=new StringBuilder();

        h.append("<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>")
         .append("<meta charset='utf-8'><title>Mini9Router</title><style>")
         .append("*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;font-family:Inter,Arial,sans-serif;background:#fbfaf8;color:#25211f}")
         .append("a{text-decoration:none;color:inherit}.layout{display:flex;min-height:100vh}.sidebar{width:285px;background:#fff;border-right:1px solid #eee7e2;position:fixed;inset:0 auto 0 0;padding:24px 16px;overflow:auto}")
         .append(".brand{display:flex;align-items:center;gap:12px;padding:8px 10px 24px}.logo{width:42px;height:42px;border-radius:12px;background:#ef6546;color:#fff;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:20px}.brand b{font-size:20px}.brand small{display:block;color:#8f8781;margin-top:3px}")
         .append(".nav-title{font-size:12px;color:#a39a94;font-weight:700;letter-spacing:.08em;padding:18px 14px 8px}.nav a{display:flex;gap:12px;align-items:center;padding:12px 14px;margin:3px 0;border-radius:10px;color:#625a55;font-weight:600}.nav a:hover,.nav a.active{background:#fff0eb;color:#ef6546}")
         .append(".ico{width:22px;text-align:center}.main{margin-left:285px;width:calc(100% - 285px)}.topbar{height:72px;background:rgba(255,255,255,.94);border-bottom:1px solid #eee7e2;display:flex;align-items:center;justify-content:space-between;padding:0 34px;position:sticky;top:0;z-index:5}")
         .append(".topbar h1{font-size:25px;margin:0}.topbar small{color:#9b918b}.content{padding:30px 42px 70px;max-width:1480px;margin:auto}.page-title{margin:0 0 24px}.page-title h2{font-size:30px;margin:0 0 5px}.page-title p{color:#8f8781;margin:0}")
         .append(".card{background:#fff;border:1px solid #eee6e1;border-radius:18px;box-shadow:0 2px 10px rgba(50,35,25,.035);padding:26px;margin:0 0 24px}.card h3{margin:0 0 8px;font-size:21px}.muted{color:#958b85;font-size:13px}.row{display:grid;grid-template-columns:repeat(12,1fr);gap:16px}.col3{grid-column:span 3}.col4{grid-column:span 4}.col6{grid-column:span 6}.col8{grid-column:span 8}.col12{grid-column:span 12}")
         .append(".statbox{background:#fff;border:1px solid #eee6e1;border-radius:16px;padding:20px}.statbox span{color:#978d87;font-size:13px}.statbox strong{display:block;font-size:28px;margin-top:7px}.good{color:#11a878}.bad{color:#e94b64}.orange{color:#ef6546}")
         .append("label{display:block;font-size:13px;color:#5d5550;font-weight:700;margin:12px 0 6px}input,select,textarea{width:100%;border:1px solid #ddd4cf;background:#fff;padding:12px 14px;border-radius:10px;font-size:14px;color:#2d2825;outline:none}input:focus,select:focus,textarea:focus{border-color:#ef846d;box-shadow:0 0 0 3px rgba(239,101,70,.09)}textarea{min-height:105px;resize:vertical;font-family:Consolas,monospace}")
         .append(".endpoint{display:flex;gap:10px;align-items:center;background:#f7f5f3;border-radius:10px;padding:9px 12px}.endpoint code{flex:1;font-size:15px}.btn,button{border:0;border-radius:10px;padding:11px 16px;font-weight:800;cursor:pointer;font-size:14px}.btn-orange{background:#ef6546;color:#fff}.btn-soft{background:#fff0eb;color:#d84c30}.btn-green{background:#16b88b;color:#fff}.btn-danger{background:#fff0f2;color:#d53f58}.btn-dark{background:#2e2926;color:#fff}.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:16px}")
         .append(".provider{padding:20px;border:1px solid #ece4df;border-radius:14px;margin-top:14px;background:#fff}.provider-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.badge{display:inline-block;padding:5px 9px;border-radius:999px;background:#fff0eb;color:#dd5437;font-size:11px;font-weight:800}.switchline{display:flex;align-items:center;gap:8px;font-size:13px}.provider-hidden{display:none}")
         .append(".logs{background:#171411;color:#eee1d7;border-radius:12px;padding:14px;min-height:100px;max-height:310px;overflow:auto;font:12px/1.7 Consolas,monospace}.section{scroll-margin-top:92px}.code{font-size:28px;letter-spacing:4px;background:#f5f2ef;border:1px dashed #d7cbc4;padding:13px 16px;border-radius:10px;display:inline-block}")
         .append(".saved{background:#eaf9f2;color:#16815f;border:1px solid #cbeedd;padding:11px 14px;border-radius:10px;margin-bottom:20px;display:none}")
         .append("@media(max-width:980px){.sidebar{width:82px;padding:18px 10px}.brand div:last-child,.nav span.txt,.nav-title{display:none}.brand{padding:6px 10px 22px}.main{margin-left:82px;width:calc(100% - 82px)}.content{padding:22px}.col3,.col4,.col6,.col8{grid-column:span 12}.topbar{padding:0 20px}}")
         .append("@media(max-width:620px){.sidebar{display:none}.main{margin-left:0;width:100%}.content{padding:16px}.topbar{height:60px}.topbar h1{font-size:19px}.card{padding:18px}.row{gap:10px}}")
         .append("</style></head><body>");

        h.append("<div class='layout'><aside class='sidebar'>")
         .append("<div class='brand'><div class='logo'>9</div><div><b>Mini9Router</b><small>Android Edition</small></div></div>")
         .append("<div class='nav'><a class='active' href='#endpoint'><span class='ico'>◆</span><span class='txt'>Endpoint & Key</span></a>")
         .append("<a href='#providers'><span class='ico'>▣</span><span class='txt'>Providers</span></a>")
         .append("<a href='#combos'><span class='ico'>◇</span><span class='txt'>Combo & Aliases</span></a>")
         .append("<a href='#usage'><span class='ico'>▥</span><span class='txt'>Usage</span></a>")
         .append("<a href='#quota'><span class='ico'>◔</span><span class='txt'>Quota Tracker</span></a>")
         .append("<a href='#logs'><span class='ico'>▤</span><span class='txt'>Console Log</span></a>")
         .append("<div class='nav-title'>SYSTEM</div>")
         .append("<a href='#settings'><span class='ico'>⚙</span><span class='txt'>Settings</span></a></div></aside>");

        h.append("<main class='main'><div class='topbar'><div><h1>Endpoint</h1><small>API endpoint configuration</small></div><div><span class='badge'>Android 5+</span></div></div><div class='content'>")
         .append("<div id='savedMsg' class='saved'>Configuration saved.</div>")
         .append("<section id='endpoint' class='section'><div class='page-title'><h2>Endpoint & Key</h2><p>Connect VS Code, Copilot, OpenClaw, Hermes or any OpenAI-compatible client.</p></div>");

        String endpoint = "http://"+localAddress()+":"+c.getString("port","20128")+"/v1";
        h.append("<div class='card'><h3>◆ &nbsp; API Endpoint</h3><p class='muted'>Use this local endpoint from devices on the same network.</p>")
         .append("<div class='endpoint'><span class='badge'>Local</span><code id='endpointText'>").append(html(endpoint)).append("</code><button type='button' class='btn-soft' onclick='copyEndpoint()'>COPY</button></div>")
         .append("</div>");

        h.append("<form method='post' action='/dashboard/save' id='cfgForm'><input type='hidden' id='providerCount' name='providerCount' value='").append(providerCount).append("'>")
         .append("<div class='card'><div class='provider-head'><div><h3>🔑 API Keys</h3><div class='muted'>Requests can be protected by one or more local keys.</div></div></div>")
         .append("<div class='row'><div class='col6'><label>Default / public model</label><input name='model' value='").append(attr(c.getString("model","AGY"))).append("'></div>")
         .append("<div class='col6'><label>Primary local API key</label><input type='password' name='localKey' value='").append(attr(c.getString("localKey",""))).append("' placeholder='leave blank to disable auth'></div>")
         .append("<div class='col12'><label>Additional API keys</label><textarea name='localKeys' placeholder='one per line or comma separated'>").append(html(c.getString("localKeys",""))).append("</textarea></div></div></div></section>");

        h.append("<section id='providers' class='section'><div class='page-title'><h2>Providers</h2><p>Primary provider, fallback chain and Codex OAuth.</p></div>")
         .append("<div class='card'><div class='provider-head'><div><h3>Codex OAuth</h3><div class='muted'>ChatGPT/Codex subscription connection</div></div><b class='").append(codexConnected?"good":"bad").append("'>").append(codexConnected?"CONNECTED":"DISCONNECTED").append("</b></div>");

        if(codexConnected){
            h.append("<form method='post' action='/codex/oauth/disconnect'><button type='submit' class='btn-danger'>Disconnect Codex</button></form>");
        } else if(!codexCode.isEmpty()){
            h.append("<p>Open <a class='orange' target='_blank' href='").append(CodexOAuth.VERIFY_URL).append("'>").append(CodexOAuth.VERIFY_URL).append("</a> and enter this code:</p>")
             .append("<div class='code'>").append(html(codexCode)).append("</div><p id='codexPoll' class='muted'>Waiting for login...</p>");
        } else {
            h.append("<a class='btn btn-green' href='/codex/oauth/start'>Connect Codex OAuth</a>");
        }
        h.append("</div>");

        for(int i=1;i<=MAX_PROVIDERS;i++){
            boolean visible=i<=providerCount;
            String type=c.getString("p"+i+"Type","openai");
            h.append("<div class='provider provider-card").append(visible?"":" provider-hidden").append("' id='providerCard").append(i).append("'>")
             .append("<div class='provider-head'><div><h3>Provider ").append(i).append(i==1?" · Primary":" · Fallback").append("</h3><span class='badge'>p").append(i).append("</span></div>")
             .append("<label class='switchline'><input style='width:auto;margin:0' type='checkbox' name='p").append(i).append("Enabled' ").append(c.getBoolean("p"+i+"Enabled",i==1)?"checked":"").append("> Enabled</label></div>")
             .append("<div class='row'><div class='col4'><label>Provider type</label><select name='p").append(i).append("Type'><option value='openai' ").append("openai".equals(type)?"selected":"").append(">OpenAI Compatible</option><option value='codex' ").append("codex".equals(type)?"selected":"").append(">Codex OAuth (ChatGPT)</option></select></div>")
             .append("<div class='col4'><label>Name</label><input name='p").append(i).append("Name' value='").append(attr(c.getString("p"+i+"Name",i==1?"Primary":"Fallback "+(i-1)))).append("'></div>")
             .append("<div class='col4'><label>Default upstream model</label><input name='p").append(i).append("Model' value='").append(attr(c.getString("p"+i+"Model",""))).append("' placeholder='model name'></div>")
             .append("<div class='col8'><label>Base URL</label><input name='p").append(i).append("Url' value='").append(attr(c.getString("p"+i+"Url",i==1?c.getString("baseUrl","https://router.bynara.id/v1"):""))).append("' placeholder='https://provider.example/v1'></div>")
             .append("<div class='col4'><label>Provider API key</label><input type='password' name='p").append(i).append("Key' value='").append(attr(c.getString("p"+i+"Key",i==1?c.getString("providerKey",""):""))).append("'></div></div></div>");
        }

        h.append("<div class='actions'><button class='btn-orange' type='button' onclick='addProvider()'>+ Add Fallback</button></div></section>");

        h.append("<section id='combos' class='section'><div class='page-title'><h2>Combo & Aliases</h2><p>9Router-style model aliases and fallback chains.</p></div>")
         .append("<div class='card'><div class='row'><div class='col6'><h3>Model Aliases</h3><label>Alias rules</label><textarea name='aliases' placeholder='AGY=p1/gpt-5.6-luna&#10;FAST=p2/model-name'>").append(html(c.getString("aliases",""))).append("</textarea><div class='muted'>Format: ALIAS=pN/model</div></div>")
         .append("<div class='col6'><h3>Combos</h3><label>Fallback chains</label><textarea name='combos' placeholder='coding=p1/model-a,p2/model-b,p3/model-c'>").append(html(c.getString("combos",""))).append("</textarea><div class='muted'>Models are tried from left to right.</div></div></div></div></section>");

        h.append("<section id='usage' class='section'><div class='page-title'><h2>Usage</h2><p>Live counters from the Android router core.</p></div><div class='row'>")
         .append("<div class='col3 statbox'><span>Requests</span><strong id='stReq'>").append(REQ.get()).append("</strong></div>")
         .append("<div class='col3 statbox'><span>Failures</span><strong id='stFail'>").append(FAIL.get()).append("</strong></div>")
         .append("<div class='col3 statbox'><span>Fallbacks</span><strong id='stFallback'>").append(FALLBACKS.get()).append("</strong></div>")
         .append("<div class='col3 statbox'><span>Last route</span><strong style='font-size:18px' id='stRoute'>").append(html(lastProvider)).append("</strong><span id='stModel'>").append(html(lastModel)).append("</span></div></div>")
         .append("<div class='card' style='margin-top:18px'><h3>Router Health</h3><p><b>Status:</b> <span class='good'>RUNNING</span></p><p class='muted'>Last error: <span id='stError'>").append(html(lastError)).append("</span></p>")
         .append("<div class='actions'><button type='button' class='btn-dark' onclick='location.reload()'>Refresh</button></div></div></section>");

        h.append("<section id='quota' class='section'><div class='page-title'><h2>Quota Tracker</h2><p>Provider health/cooldown overview.</p></div><div class='card'><div id='providerHealth' class='muted'>Loading provider health...</div></div></section>");

        h.append("<section id='logs' class='section'><div class='page-title'><h2>Console Log</h2><p>Recent routing events. Request bodies are not logged.</p></div><div class='card'><div class='logs' id='logBox'>Loading...</div>")
         .append("<div class='actions'><form method='post' action='/dashboard/reset-stats'><button class='btn-danger' type='submit'>Reset Stats & Logs</button></form></div></div></section>");

        h.append("<section id='settings' class='section'><div class='page-title'><h2>Settings</h2><p>Save all gateway, key, provider, alias and combo settings.</p></div><div class='card'><h3>Apply Configuration</h3><p class='muted'>Changes are written to Android private SharedPreferences.</p><div class='actions'><button class='btn-orange' type='submit'>Save Configuration</button></div></div></section></form>");

        h.append("</div></main></div><script>")
         .append("function addProvider(){var c=document.getElementById('providerCount'),n=parseInt(c.value||'1',10);if(n>=").append(MAX_PROVIDERS).append("){alert('Maximum ").append(MAX_PROVIDERS).append(" providers');return;}n++;c.value=n;var x=document.getElementById('providerCard'+n);if(x){x.className='provider provider-card';x.scrollIntoView({behavior:'smooth',block:'center'});}}")
         .append("function copyEndpoint(){var t=document.getElementById('endpointText').innerText;if(navigator.clipboard){navigator.clipboard.writeText(t);}else{var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();document.execCommand('copy');a.remove();}}")
         .append("function escHtml(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}")
         .append("function loadStatus(){fetch('/api/status').then(function(r){return r.json()}).then(function(x){document.getElementById('stReq').innerText=x.requests||0;document.getElementById('stFail').innerText=x.failures||0;document.getElementById('stFallback').innerText=x.fallbacks||0;document.getElementById('stRoute').innerText=x.last_provider||'-';document.getElementById('stModel').innerText=x.last_model||'-';document.getElementById('stError').innerText=x.last_error||'-';var p=x.providers||[];document.getElementById('providerHealth').innerHTML=p.map(function(v){return '<div style=\"padding:10px 0;border-bottom:1px solid #eee6e1\"><b>p'+v.slot+' · '+escHtml(v.name)+'</b> &nbsp; <span class=\"badge\">'+escHtml(v.type)+'</span> &nbsp; '+(v.enabled?'<span class=\"good\">Enabled</span>':'Disabled')+(v.cooldown?' · <span class=\"bad\">Cooldown</span>':'')+'</div>';}).join('')||'No providers';}).catch(function(){});}")
         .append("function loadLogs(){fetch('/api/logs').then(function(r){return r.json()}).then(function(x){document.getElementById('logBox').innerHTML=(x.logs||[]).map(function(s){return '<div>'+escHtml(s)+'</div>';}).join('')||'No logs yet';}).catch(function(){});}")
         .append("if(location.search.indexOf('saved=1')>=0){document.getElementById('savedMsg').style.display='block';}")
         .append("loadStatus();loadLogs();setInterval(loadStatus,4000);setInterval(loadLogs,5000);");

        if(!codexConnected && !codexCode.isEmpty()){
            h.append("function pc(){fetch('/codex/oauth/poll').then(function(r){return r.json()}).then(function(x){var e=document.getElementById('codexPoll');if(e)e.innerText='Status: '+x.status;if(x.status==='connected'){location='/dashboard?codex=connected#providers';}else if(x.status==='pending'){setTimeout(pc,3000);}}).catch(function(){setTimeout(pc,4000);});}setTimeout(pc,1500);");
        }

        h.append("</script></body></html>");
        return h.toString();
    }

    private static String localAddress() {
        try {
            Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
            while (e.hasMoreElements()) {
                NetworkInterface n = e.nextElement();
                Enumeration<InetAddress> as = n.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {}
        return "127.0.0.1";
    }

    private static String simpleErrorPage(String title, Exception e){
        return "<!doctype html><html><body style='font-family:Arial;background:#fbfaf8;color:#282321;padding:24px'><h2>"
                +html(title)+"</h2><pre>"+html(String.valueOf(e.getMessage()))
                +"</pre><a style='color:#ef6546' href='/dashboard'>Kembali</a></body></html>";
    }

    private static void saveField(SharedPreferences.Editor e, Map<String,String> f, String k){
        if(f.containsKey(k)) e.putString(k,f.get(k));
    }

    private static Map<String,String> parseForm(String s)throws Exception{
        Map<String,String>m=new HashMap<String,String>();
        for(String p:s.split("&")){
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

    private static String esc(String s){
        return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r","\\r");
    }

    private static void redirect(OutputStream out,String path)throws IOException{
        String h="HTTP/1.1 303 See Other\r\nLocation: "+path+"\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));out.flush();
    }

    private static void writeHtml(OutputStream out,String html)throws IOException{
        byte[]d=html.getBytes(StandardCharsets.UTF_8);
        String h="HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: "+d.length+"\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));out.write(d);out.flush();
    }

    private static void writeStreamHeaders(OutputStream out,String type)throws IOException{
        if(type==null||!type.toLowerCase(Locale.US).contains("text/event-stream"))
            type="text/event-stream; charset=utf-8";
        String h="HTTP/1.1 200 OK\r\nContent-Type: "+type+
                "\r\nCache-Control: no-cache, no-transform\r\nX-Accel-Buffering: no\r\nTransfer-Encoding: chunked\r\n"+
                "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, api-key, x-api-key\r\n"+
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: keep-alive\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));out.flush();
    }

    private static void writeChunk(OutputStream out,byte[]data,int len)throws IOException{
        out.write((Integer.toHexString(len)+"\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.write(data,0,len);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void finishChunks(OutputStream out)throws IOException{
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));out.flush();
    }

    private static String readLine(InputStream in)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        int prev=-1,cur;
        while((cur=in.read())!=-1){
            if(prev=='\r'&&cur=='\n'){
                byte[]a=b.toByteArray();
                int len=a.length>0&&a[a.length-1]=='\r'?a.length-1:a.length;
                return new String(a,0,len,StandardCharsets.ISO_8859_1);
            }
            b.write(cur); prev=cur;
            if(b.size()>16384) throw new IOException("header line too long");
        }
        return b.size()==0?null:new String(b.toByteArray(),StandardCharsets.ISO_8859_1);
    }

    private static byte[] readFixed(InputStream in,int len,int max)throws IOException{
        if(len<0||len>max) throw new IOException("request body too large");
        byte[]b=new byte[len];
        int off=0;
        while(off<len){
            int n=in.read(b,off,len-off);
            if(n<0)break;
            off+=n;
        }
        if(off==len)return b;
        return Arrays.copyOf(b,off);
    }

    private static byte[] readChunkedBody(InputStream in,int max)throws IOException{
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        while(true){
            String line=readLine(in);
            if(line==null) throw new EOFException("chunk size missing");
            int semi=line.indexOf(';');
            if(semi>=0) line=line.substring(0,semi);
            int size=Integer.parseInt(line.trim(),16);
            if(size==0){
                while((line=readLine(in))!=null&&!line.isEmpty()){}
                break;
            }
            if(out.size()+size>max) throw new IOException("chunked body too large");
            byte[]b=readFixed(in,size,max);
            out.write(b);
            readLine(in);
        }
        return out.toByteArray();
    }

    private static byte[] readAllLimited(InputStream in,int max)throws IOException{
        ByteArrayOutputStream b=new ByteArrayOutputStream();
        byte[]buf=new byte[4096];
        int n,total=0;
        while((n=in.read(buf))!=-1){
            total+=n;
            if(total>max) throw new IOException("response too large");
            b.write(buf,0,n);
        }
        return b.toByteArray();
    }

    private static void respond(OutputStream out,int code,String body)throws IOException{
        String reason=code==200?"OK":code==204?"No Content":code==400?"Bad Request":code==401?"Unauthorized":code==404?"Not Found":code==501?"Not Implemented":"Error";
        byte[]d=body.getBytes(StandardCharsets.UTF_8);
        String h="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: "+d.length+
                "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, api-key, x-api-key\r\n"+
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        if(d.length>0)out.write(d);
        out.flush();
    }

    private static void writeRaw(OutputStream out,int code,String reason,byte[]body,String type)throws IOException{
        if(type==null||type.trim().isEmpty()) type="application/json; charset=utf-8";
        String h="HTTP/1.1 "+code+" "+reason+"\r\nContent-Type: "+type+"\r\nContent-Length: "+body.length+
                "\r\nAccess-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: Authorization, Content-Type, api-key, x-api-key\r\n"+
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }
}
