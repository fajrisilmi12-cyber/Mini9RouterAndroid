package id.my.minirouter;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.*;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class HermesActivity extends Activity {
    TextView chatLog, status;
    EditText input, systemPrompt, memoryBox;
    ScrollView chatScroll;
    JSONArray history = new JSONArray();
    volatile boolean busy = false;

    @Override public void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_hermes);

        chatLog = findViewById(R.id.chatLog);
        status = findViewById(R.id.hermesStatus);
        input = findViewById(R.id.input);
        systemPrompt = findViewById(R.id.systemPrompt);
        memoryBox = findViewById(R.id.memoryBox);
        chatScroll = findViewById(R.id.chatScroll);

        SharedPreferences p = getSharedPreferences("hermes", MODE_PRIVATE);
        systemPrompt.setText(p.getString("systemPrompt",
            "You are Hermes Lite, a concise helpful Android agent. Use tools only when needed."));
        memoryBox.setText(p.getString("memory", ""));
        loadHistory();
        renderHistory();

        findViewById(R.id.sendBtn).setOnClickListener(v -> sendMessage());
        findViewById(R.id.clearBtn).setOnClickListener(v -> {
            history = new JSONArray();
            saveHistory();
            renderHistory();
            status.setText("History cleared");
        });
        findViewById(R.id.saveMemoryBtn).setOnClickListener(v -> {
            getSharedPreferences("hermes", MODE_PRIVATE).edit()
                .putString("systemPrompt", systemPrompt.getText().toString())
                .putString("memory", memoryBox.getText().toString())
                .apply();
            Toast.makeText(this, "Persona + memory saved", Toast.LENGTH_SHORT).show();
        });
    }

    private void sendMessage() {
        if (busy) return;
        final String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        input.setText("");
        try {
            JSONObject u = new JSONObject();
            u.put("role", "user");
            u.put("content", text);
            history.put(u);
        } catch (Exception ignored) {}
        saveHistory();
        renderHistory();
        busy = true;
        status.setText("Thinking...");

        new Thread(() -> {
            try {
                String answer = runAgentLoop();
                JSONObject a = new JSONObject();
                a.put("role", "assistant");
                a.put("content", answer);
                history.put(a);
                saveHistory();
                runOnUiThread(() -> {
                    renderHistory();
                    status.setText("Ready");
                    busy = false;
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    status.setText("Error: " + e.getMessage());
                    busy = false;
                });
            }
        }).start();
    }

    private String runAgentLoop() throws Exception {
        JSONArray working = new JSONArray();
        JSONObject sys = new JSONObject();
        sys.put("role", "system");
        String memory = memoryBox.getText().toString().trim();
        String tools = "\n\nAvailable tools. If a tool is needed, reply ONLY with one JSON object. " +
                "http_get: {\"tool\":\"http_get\",\"url\":\"https://...\"}. " +
                "remember: {\"tool\":\"remember\",\"text\":\"...\"}. " +
                "read_file: {\"tool\":\"read_file\",\"name\":\"notes.txt\"}. " +
                "write_file: {\"tool\":\"write_file\",\"name\":\"notes.txt\",\"text\":\"...\"}. " +
                "After receiving TOOL_RESULT, answer normally unless another tool is truly necessary.";
        sys.put("content", systemPrompt.getText().toString() +
                (memory.isEmpty() ? "" : "\n\nPersistent memory:\n" + memory) + tools);
        working.put(sys);
        for (int i = Math.max(0, history.length() - 16); i < history.length(); i++) working.put(history.getJSONObject(i));

        for (int step = 0; step < 3; step++) {
            String out = callGateway(working);
            JSONObject tool = parseTool(out);
            if (tool == null) return out;

            JSONObject assistant = new JSONObject();
            assistant.put("role", "assistant");
            assistant.put("content", out);
            working.put(assistant);

            String result = executeTool(tool);
            JSONObject tr = new JSONObject();
            tr.put("role", "user");
            tr.put("content", "TOOL_RESULT: " + result);
            working.put(tr);
        }
        return "Tool loop limit reached.";
    }

    private String callGateway(JSONArray messages) throws Exception {
        SharedPreferences cfg = getSharedPreferences("cfg", MODE_PRIVATE);
        int port;
        try { port = Integer.parseInt(cfg.getString("port", "20128")); }
        catch (Exception e) { port = 20128; }
        String model = cfg.getString("model", "AGY");
        String localKey = cfg.getString("localKey", "");

        URL url = new URL("http://127.0.0.1:" + port + "/v1/chat/completions");
        HttpURLConnection c = (HttpURLConnection) url.openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(120000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        if (!localKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + localKey);

        JSONObject body = new JSONObject();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);

        try (OutputStream os = c.getOutputStream()) {
            os.write(body.toString().getBytes(StandardCharsets.UTF_8));
        }

        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String raw = readAll(in);
        if (code >= 400) throw new IOException("Gateway HTTP " + code + ": " + raw);
        JSONObject root = new JSONObject(raw);
        return root.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "");
    }

    private JSONObject parseTool(String s) {
        try {
            String t = s.trim();
            if (!t.startsWith("{") || !t.endsWith("}")) return null;
            JSONObject o = new JSONObject(t);
            return o.has("tool") ? o : null;
        } catch (Exception e) { return null; }
    }

    private String executeTool(JSONObject o) {
        try {
            String tool = o.optString("tool", "");
            if ("http_get".equals(tool)) {
                String u = o.optString("url", "");
                if (!(u.startsWith("https://") || u.startsWith("http://"))) return "Blocked URL";
                HttpURLConnection c = (HttpURLConnection) new URL(u).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "HermesLite/0.2");
                String r = readAll(c.getInputStream());
                return r.length() > 8000 ? r.substring(0, 8000) : r;
            }
            if ("remember".equals(tool)) {
                String text = o.optString("text", "").trim();
                String old = getSharedPreferences("hermes", MODE_PRIVATE).getString("memory", "");
                String now = old.isEmpty() ? text : old + "\n" + text;
                getSharedPreferences("hermes", MODE_PRIVATE).edit().putString("memory", now).apply();
                runOnUiThread(() -> memoryBox.setText(now));
                return "Saved to persistent memory";
            }
            if ("read_file".equals(tool)) {
                String name = safeName(o.optString("name", "notes.txt"));
                File f = new File(getFilesDir(), name);
                if (!f.exists()) return "File not found";
                return readAll(new FileInputStream(f));
            }
            if ("write_file".equals(tool)) {
                String name = safeName(o.optString("name", "notes.txt"));
                String text = o.optString("text", "");
                try (FileOutputStream fos = new FileOutputStream(new File(getFilesDir(), name))) {
                    fos.write(text.getBytes(StandardCharsets.UTF_8));
                }
                return "Wrote " + name;
            }
            return "Unknown tool: " + tool;
        } catch (Exception e) {
            return "Tool error: " + e.getMessage();
        }
    }

    private String safeName(String n) {
        n = n.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.length() > 64 ? n.substring(0,64) : n;
    }

    private void loadHistory() {
        try {
            String s = getSharedPreferences("hermes", MODE_PRIVATE).getString("history", "[]");
            history = new JSONArray(s);
        } catch (Exception e) { history = new JSONArray(); }
    }

    private void saveHistory() {
        getSharedPreferences("hermes", MODE_PRIVATE).edit().putString("history", history.toString()).apply();
    }

    private void renderHistory() {
        StringBuilder b = new StringBuilder();
        try {
            for (int i = 0; i < history.length(); i++) {
                JSONObject m = history.getJSONObject(i);
                b.append("user".equals(m.optString("role")) ? "You: " : "Hermes: ")
                 .append(m.optString("content")).append("\n\n");
            }
        } catch (Exception ignored) {}
        chatLog.setText(b.toString());
        chatScroll.post(() -> chatScroll.fullScroll(ScrollView.FOCUS_DOWN));
    }

    private static String readAll(InputStream in) throws IOException {
        if (in == null) return "";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return new String(b.toByteArray(), StandardCharsets.UTF_8);
    }
}
