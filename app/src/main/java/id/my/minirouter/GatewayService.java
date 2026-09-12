package id.my.minirouter;

import android.app.Service;
import android.content.*;
import android.os.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class GatewayService extends Service {
    private volatile boolean running;
    private ServerSocket serverSocket;
    private ExecutorService pool;
    private Thread acceptThread;
    private PowerManager.WakeLock wakeLock;

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
        final int listenPort = port;

        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Mini9Router:Server");
            wakeLock.acquire();

            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", listenPort));
            pool = Executors.newFixedThreadPool(3);
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
            }, "mini9-accept");
            acceptThread.start();
        } catch (Exception e) {
            e.printStackTrace();
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
            String method = first[0], path = first[1];

            Map<String,String> headers = new HashMap<>();
            String line;
            int contentLength = 0;
            while ((line = readLine(in)) != null && line.length() > 0) {
                int idx = line.indexOf(':');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim().toLowerCase(Locale.US);
                    String v = line.substring(idx + 1).trim();
                    headers.put(k, v);
                    if ("content-length".equals(k)) {
                        try { contentLength = Integer.parseInt(v); } catch(Exception ignored) {}
                    }
                }
            }

            if ("OPTIONS".equals(method)) {
                respond(out, 204, "");
                return;
            }

            String localKey = cfg.getString("localKey", "");
            if (!localKey.isEmpty() && !"/health".equals(path)) {
                String auth = headers.get("authorization");
                if (!("Bearer " + localKey).equals(auth)) {
                    respond(out, 401, "{\"error\":{\"message\":\"Unauthorized\"}}");
                    return;
                }
            }

            if ("GET".equals(method) && "/health".equals(path)) {
                respond(out, 200, "{\"ok\":true,\"service\":\"Mini9Router Android\",\"streaming\":true}");
                return;
            }

            if ("GET".equals(method) && ("/v1/models".equals(path) || "/models".equals(path))) {
                String m = esc(cfg.getString("model","AGY"));
                respond(out, 200, "{\"object\":\"list\",\"data\":[{\"id\":\""+m+"\",\"object\":\"model\",\"owned_by\":\"mini9router\"}]}");
                return;
            }

            if ("POST".equals(method) && ("/v1/chat/completions".equals(path) || "/chat/completions".equals(path))) {
                byte[] body = new byte[Math.max(0, contentLength)];
                int off = 0;
                while (off < body.length) {
                    int n = in.read(body, off, body.length - off);
                    if (n < 0) break;
                    off += n;
                }
                String bodyText = new String(body, StandardCharsets.UTF_8);
                boolean wantsStream = bodyText.matches("(?s).*\\\"stream\\\"\\s*:\\s*true.*");
                proxy(out, body, cfg, wantsStream);
                return;
            }

            respond(out, 404, "{\"error\":{\"message\":\"Not found\"}}");
        } catch (Exception e) {
            try { respond(socket.getOutputStream(), 500, "{\"error\":{\"message\":\"Internal error\"}}"); }
            catch(Exception ignored) {}
        } finally {
            try { socket.close(); } catch(Exception ignored) {}
        }
    }

    private void proxy(OutputStream client, byte[] body, SharedPreferences cfg, boolean wantsStream) throws Exception {
        String base = cfg.getString("baseUrl","https://router.bynara.id/v1");
        while (base.endsWith("/")) base = base.substring(0, base.length()-1);
        URL url = new URL(base + "/chat/completions");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", wantsStream ? "text/event-stream" : "application/json");
        c.setRequestProperty("Connection", "keep-alive");
        String key = cfg.getString("providerKey","");
        if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
        try (OutputStream os = c.getOutputStream()) { os.write(body); os.flush(); }

        int code = c.getResponseCode();
        InputStream pin = code >= 400 ? c.getErrorStream() : c.getInputStream();
        if (pin == null) pin = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        String upstreamType = c.getContentType();

        if (wantsStream && code >= 200 && code < 300) {
            writeStreamHeaders(client, upstreamType);
            byte[] buf = new byte[1024];
            int n;
            while ((n = pin.read(buf)) != -1) {
                if (n == 0) continue;
                writeChunk(client, buf, n);
            }
            finishChunks(client);
        } else {
            byte[] data = readAll(pin);
            String status = code >= 200 && code < 300 ? "OK" : "Upstream";
            writeRaw(client, code, status, data, upstreamType);
        }
        c.disconnect();
    }

    private static void writeStreamHeaders(OutputStream out, String type) throws IOException {
        if (type == null || !type.toLowerCase(Locale.US).contains("text/event-stream")) {
            type = "text/event-stream; charset=utf-8";
        }
        String h = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Cache-Control: no-cache, no-transform\r\n" +
                "X-Accel-Buffering: no\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Connection: keep-alive\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void writeChunk(OutputStream out, byte[] data, int len) throws IOException {
        String prefix = Integer.toHexString(len) + "\r\n";
        out.write(prefix.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data, 0, len);
        out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static void finishChunks(OutputStream out) throws IOException {
        out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int prev = -1, cur;
        while ((cur = in.read()) != -1) {
            if (prev == '\r' && cur == '\n') {
                byte[] d = b.toByteArray();
                int len = d.length;
                if (len > 0 && d[len-1] == '\r') len--;
                return new String(d, 0, len, StandardCharsets.ISO_8859_1);
            }
            b.write(cur);
            prev = cur;
            if (b.size() > 16384) throw new IOException("header too large");
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf,0,n);
        return b.toByteArray();
    }

    private static void respond(OutputStream out, int code, String json) throws IOException {
        writeRaw(out, code, code == 200 ? "OK" : "", json.getBytes(StandardCharsets.UTF_8), "application/json; charset=utf-8");
    }

    private static void writeRaw(OutputStream out, int code, String status, byte[] data, String type) throws IOException {
        if (type == null) type = "application/json; charset=utf-8";
        String h = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Type: " + type + "\r\n" +
                "Content-Length: " + data.length + "\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Access-Control-Allow-Headers: Authorization, Content-Type\r\n" +
                "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
                "Connection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.ISO_8859_1));
        out.write(data);
        out.flush();
    }

    private static String esc(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"");
    }
}
