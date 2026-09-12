package id.my.minirouter;

import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

/**
 * Codex ChatGPT OAuth bridge based on the public Codex device-code flow.
 * Tokens stay in this app's private SharedPreferences.
 */
public final class CodexOAuth {
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String AUTH_BASE = "https://auth.openai.com";
    public static final String VERIFY_URL = AUTH_BASE + "/codex/device";
    private static final String DEVICE_API = AUTH_BASE + "/api/accounts/deviceauth";
    private static final String TOKEN_URL = AUTH_BASE + "/oauth/token";
    private static final String CODEX_RESPONSES = "https://chatgpt.com/backend-api/codex/responses";
    private static final String CODEX_MODELS = "https://chatgpt.com/backend-api/codex/models";

    private CodexOAuth() {}

    public static boolean isConnected(SharedPreferences p) {
        return !p.getString("codexAccessToken", "").isEmpty();
    }

    public static String userCode(SharedPreferences p) {
        return p.getString("codexUserCode", "");
    }

    public static void start(SharedPreferences p) throws Exception {
        JSONObject req = new JSONObject();
        req.put("client_id", CLIENT_ID);
        HttpResult r = jsonRequest("POST", DEVICE_API + "/usercode", req.toString(), null, null);
        if (r.code < 200 || r.code >= 300) throw new IOException("Codex device auth HTTP " + r.code + ": " + r.body);
        JSONObject o = new JSONObject(r.body);
        p.edit()
            .putString("codexDeviceAuthId", o.getString("device_auth_id"))
            .putString("codexUserCode", o.optString("user_code", o.optString("usercode", "")))
            .putString("codexInterval", String.valueOf(o.opt("interval")))
            .putLong("codexDeviceStarted", System.currentTimeMillis())
            .apply();
    }

    public static String poll(SharedPreferences p) {
        try {
            String deviceId = p.getString("codexDeviceAuthId", "");
            String code = p.getString("codexUserCode", "");
            long started = p.getLong("codexDeviceStarted", 0L);
            if (deviceId.isEmpty() || code.isEmpty()) return "error:no_device_login";
            if (started > 0 && System.currentTimeMillis() - started > 15L * 60L * 1000L) return "expired";

            JSONObject req = new JSONObject();
            req.put("device_auth_id", deviceId);
            req.put("user_code", code);
            HttpResult r = jsonRequest("POST", DEVICE_API + "/token", req.toString(), null, null);
            if (r.code == 403 || r.code == 404) return "pending";
            if (r.code < 200 || r.code >= 300) return "error:HTTP_" + r.code + ":" + compact(r.body);

            JSONObject o = new JSONObject(r.body);
            String form = "grant_type=authorization_code" +
                "&code=" + enc(o.getString("authorization_code")) +
                "&redirect_uri=" + enc(AUTH_BASE + "/deviceauth/callback") +
                "&client_id=" + enc(CLIENT_ID) +
                "&code_verifier=" + enc(o.getString("code_verifier"));
            HttpResult tr = formRequest(TOKEN_URL, form);
            if (tr.code < 200 || tr.code >= 300) return "error:token_HTTP_" + tr.code + ":" + compact(tr.body);
            saveTokens(p, new JSONObject(tr.body));
            p.edit().remove("codexDeviceAuthId").remove("codexUserCode").remove("codexInterval").remove("codexDeviceStarted").apply();
            return "connected";
        } catch (Exception e) {
            return "error:" + e.getClass().getSimpleName() + ":" + String.valueOf(e.getMessage());
        }
    }

    public static void disconnect(SharedPreferences p) {
        p.edit()
            .remove("codexAccessToken")
            .remove("codexRefreshToken")
            .remove("codexIdToken")
            .remove("codexAccountId")
            .remove("codexTokenSavedAt")
            .remove("codexDeviceAuthId")
            .remove("codexUserCode")
            .remove("codexInterval")
            .remove("codexDeviceStarted")
            .apply();
    }

    /** Validate OAuth and return the models payload from the official Codex backend. */
    public static String models(SharedPreferences p) throws Exception {
        HttpResult r = codexGet(p, CODEX_MODELS);
        if (r.code == 401) {
            refresh(p);
            r = codexGet(p, CODEX_MODELS);
        }
        if (r.code < 200 || r.code >= 300) throw new IOException("Codex models HTTP " + r.code + ": " + compact(r.body));
        return r.body;
    }

    /**
     * Accepts an OpenAI Chat Completions body. Codex's ChatGPT endpoint is a
     * streaming Responses API, so we send the same shape Codex CLI expects,
     * consume SSE, then convert the final text back to Chat Completions JSON.
     */
    public static String chat(SharedPreferences p, String chatBody, String forcedModel) throws Exception {
        if (!isConnected(p)) throw new IOException("Codex OAuth is not connected");
        JSONObject incoming = new JSONObject(chatBody);
        String model = forcedModel == null || forcedModel.trim().isEmpty() ? incoming.optString("model", "") : forcedModel.trim();
        if (model.isEmpty() || "AGY".equalsIgnoreCase(model)) {
            throw new IOException("Set an upstream Codex model in the provider card");
        }

        JSONObject request = toResponsesRequest(incoming, model);
        HttpResult r = codexRequest(p, request.toString());
        if (r.code == 401) {
            refresh(p);
            r = codexRequest(p, request.toString());
        }
        if (r.code < 200 || r.code >= 300) {
            throw new IOException("Codex HTTP " + r.code + ": " + compact(r.body));
        }

        CodexSseResult parsed = parseSse(r.body);
        if (parsed.error != null && !parsed.error.isEmpty()) {
            throw new IOException("Codex SSE error: " + parsed.error);
        }
        if (parsed.text.isEmpty() && parsed.finalResponse == null) {
            throw new IOException("Codex returned no text. Raw: " + compact(r.body));
        }
        return toChatCompletion(parsed.finalResponse, parsed.text, model).toString();
    }

    private static JSONObject toResponsesRequest(JSONObject in, String model) throws Exception {
        JSONObject out = new JSONObject();
        out.put("model", model);
        out.put("stream", true);
        out.put("store", false);

        StringBuilder instructions = new StringBuilder();
        StringBuilder transcript = new StringBuilder();
        JSONArray messages = in.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                String text = textContent(m.opt("content"));
                if (text.isEmpty()) continue;
                if ("system".equals(role) || "developer".equals(role)) {
                    if (instructions.length() > 0) instructions.append("\n\n");
                    instructions.append(text);
                } else {
                    if (transcript.length() > 0) transcript.append("\n\n");
                    transcript.append("assistant".equals(role) ? "Assistant: " : "User: ").append(text);
                }
            }
        }

        if (instructions.length() == 0) {
            instructions.append("You are a helpful coding assistant. Answer the user's request directly.");
        }
        out.put("instructions", instructions.toString());

        JSONArray content = new JSONArray();
        JSONObject inputText = new JSONObject();
        inputText.put("type", "input_text");
        inputText.put("text", transcript.length() == 0 ? "Hello" : transcript.toString());
        content.put(inputText);

        JSONObject message = new JSONObject();
        message.put("type", "message");
        message.put("role", "user");
        message.put("content", content);
        JSONArray input = new JSONArray();
        input.put(message);
        out.put("input", input);

        // Avoid forwarding Chat Completions-only fields that Codex Responses does not accept.
        if (in.has("max_tokens")) out.put("max_output_tokens", in.optInt("max_tokens"));
        return out;
    }

    private static String textContent(Object c) {
        if (c == null || c == JSONObject.NULL) return "";
        if (c instanceof String) return (String)c;
        if (c instanceof JSONArray) {
            JSONArray a = (JSONArray)c;
            StringBuilder b = new StringBuilder();
            for (int i=0;i<a.length();i++) {
                Object v = a.opt(i);
                if (v instanceof JSONObject) {
                    JSONObject part = (JSONObject)v;
                    String t = part.optString("text", "");
                    if (!t.isEmpty()) b.append(t);
                } else if (v != null && v != JSONObject.NULL) {
                    b.append(String.valueOf(v));
                }
            }
            return b.toString();
        }
        return String.valueOf(c);
    }

    private static CodexSseResult parseSse(String raw) {
        CodexSseResult out = new CodexSseResult();
        if (raw == null) return out;
        String[] lines = raw.split("\\r?\\n");
        StringBuilder deltas = new StringBuilder();
        for (String line : lines) {
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty() || "[DONE]".equals(data)) continue;
            try {
                JSONObject ev = new JSONObject(data);
                String type = ev.optString("type", "");
                if ("response.output_text.delta".equals(type)) {
                    deltas.append(ev.optString("delta", ""));
                } else if ("response.completed".equals(type)) {
                    JSONObject response = ev.optJSONObject("response");
                    if (response != null) out.finalResponse = response;
                } else if ("response.failed".equals(type) || "error".equals(type)) {
                    out.error = compact(data);
                }
            } catch (Exception ignored) {}
        }
        out.text = deltas.toString();
        if (out.text.isEmpty() && out.finalResponse != null) out.text = extractResponseText(out.finalResponse);
        return out;
    }

    private static String extractResponseText(JSONObject response) {
        if (response == null) return "";
        String direct = response.optString("output_text", "");
        if (!direct.isEmpty()) return direct;
        JSONArray output = response.optJSONArray("output");
        if (output == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i=0;i<output.length();i++) {
            JSONObject item = output.optJSONObject(i);
            if (item == null || !"message".equals(item.optString("type"))) continue;
            JSONArray content = item.optJSONArray("content");
            if (content == null) continue;
            for (int j=0;j<content.length();j++) {
                JSONObject part = content.optJSONObject(j);
                if (part == null) continue;
                String type = part.optString("type", "");
                if ("output_text".equals(type) || "text".equals(type)) b.append(part.optString("text", ""));
            }
        }
        return b.toString();
    }

    private static JSONObject toChatCompletion(JSONObject response, String text, String model) throws Exception {
        JSONObject msg = new JSONObject();
        msg.put("role", "assistant");
        msg.put("content", text == null ? "" : text);
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", msg);
        choice.put("finish_reason", "stop");
        JSONArray choices = new JSONArray();
        choices.put(choice);

        JSONObject out = new JSONObject();
        out.put("id", response == null ? "codex-" + System.currentTimeMillis() : response.optString("id", "codex-" + System.currentTimeMillis()));
        out.put("object", "chat.completion");
        out.put("created", System.currentTimeMillis()/1000L);
        out.put("model", response == null ? model : response.optString("model", model));
        out.put("choices", choices);
        if (response != null && response.has("usage")) out.put("usage", response.opt("usage"));
        return out;
    }

    private static HttpResult codexRequest(SharedPreferences p, String body) throws Exception {
        String token = p.getString("codexAccessToken", "");
        String account = p.getString("codexAccountId", "");
        HttpURLConnection c = open(CODEX_RESPONSES);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "text/event-stream");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("OpenAI-Beta", "responses=experimental");
        c.setRequestProperty("originator", "codex_cli_rs");
        c.setRequestProperty("User-Agent", "codex_cli_rs/9router-mobile");
        if (!account.isEmpty()) c.setRequestProperty("ChatGPT-Account-ID", account);
        try (OutputStream os = c.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.flush();
        }
        return read(c);
    }

    private static HttpResult codexGet(SharedPreferences p, String url) throws Exception {
        String token = p.getString("codexAccessToken", "");
        String account = p.getString("codexAccountId", "");
        HttpURLConnection c = open(url);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("originator", "codex_cli_rs");
        c.setRequestProperty("User-Agent", "codex_cli_rs/9router-mobile");
        if (!account.isEmpty()) c.setRequestProperty("ChatGPT-Account-ID", account);
        return read(c);
    }

    private static void refresh(SharedPreferences p) throws Exception {
        String refresh = p.getString("codexRefreshToken", "");
        if (refresh.isEmpty()) throw new IOException("No Codex refresh token; reconnect OAuth");
        String form = "grant_type=refresh_token&refresh_token=" + enc(refresh) + "&client_id=" + enc(CLIENT_ID);
        HttpResult r = formRequest(TOKEN_URL, form);
        if (r.code < 200 || r.code >= 300) throw new IOException("Codex refresh HTTP " + r.code + ": " + compact(r.body));
        saveTokens(p, new JSONObject(r.body));
    }

    private static void saveTokens(SharedPreferences p, JSONObject o) throws Exception {
        String access = o.optString("access_token", p.getString("codexAccessToken", ""));
        String refresh = o.optString("refresh_token", p.getString("codexRefreshToken", ""));
        String id = o.optString("id_token", p.getString("codexIdToken", ""));
        String account = jwtStringClaim(id, "chatgpt_account_id");
        if (account.isEmpty()) account = jwtStringClaim(access, "chatgpt_account_id");
        SharedPreferences.Editor e = p.edit()
            .putString("codexAccessToken", access)
            .putString("codexRefreshToken", refresh)
            .putString("codexIdToken", id)
            .putLong("codexTokenSavedAt", System.currentTimeMillis());
        if (!account.isEmpty()) e.putString("codexAccountId", account);
        e.apply();
    }

    private static String jwtStringClaim(String jwt, String key) {
        try {
            if (jwt == null) return "";
            String[] parts = jwt.split("\\.");
            if (parts.length != 3) return "";
            byte[] raw = Base64.decode(parts[1], Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            JSONObject o = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            return o.optString(key, "");
        } catch (Exception ignored) { return ""; }
    }

    private static HttpResult jsonRequest(String method, String url, String body, String bearer, String account) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestMethod(method);
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("Accept", "application/json");
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
        if (account != null && !account.isEmpty()) c.setRequestProperty("ChatGPT-Account-ID", account);
        try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        return read(c);
    }

    private static HttpResult formRequest(String url, String form) throws Exception {
        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        c.setRequestProperty("Accept", "application/json");
        try (OutputStream os = c.getOutputStream()) { os.write(form.getBytes(StandardCharsets.UTF_8)); }
        return read(c);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection)new URL(url).openConnection();
        c.setConnectTimeout(30000);
        c.setReadTimeout(180000);
        c.setUseCaches(false);
        c.setInstanceFollowRedirects(true);
        return c;
    }

    private static HttpResult read(HttpURLConnection c) throws Exception {
        int code = c.getResponseCode();
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        c.disconnect();
        return new HttpResult(code, body);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
        return b.toByteArray();
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s == null ? "" : s, "UTF-8");
    }

    private static String compact(String s) {
        if (s == null) return "";
        String x = s.replace('\r', ' ').replace('\n', ' ').trim();
        return x.length() > 500 ? x.substring(0, 500) + "..." : x;
    }

    private static final class HttpResult {
        final int code;
        final String body;
        HttpResult(int code, String body) { this.code = code; this.body = body == null ? "" : body; }
    }

    private static final class CodexSseResult {
        String text = "";
        String error = "";
        JSONObject finalResponse;
    }
}
