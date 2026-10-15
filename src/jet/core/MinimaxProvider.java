package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * MiniMax Code（MiniMax M 系）适配器。
 *
 * - 登录：OAuth 设备码 + PKCE（account.minimax.cn，clientId=mcode-public），手机浏览器可完成
 * - 续期：POST /oauth2/token，form：grant_type=refresh_token
 * - 模型：GET /mavis/api/v1/models → providers[providerId=minimax].config.models（对象表，key=长 id）
 * - 对话：POST /mavis/api/v1/llm/v1/messages —— **Anthropic messages 格式**，强制 stream:true，
 *   头只需 Authorization+Content-Type+Accept:text/event-stream（实测无需 anthropic-version）
 */
public final class MinimaxProvider {

    public static final String ID = "minimax";
    public static final String ACCOUNT_HOST = "https://account.minimax.cn";
    public static final String API_HOST = "https://agent.minimax.cn";
    public static final String TOKEN_PATH = "/oauth2/token";
    public static final String MODELS_PATH = "/mavis/api/v1/models";
    public static final String INFER_PATH = "/mavis/api/v1/llm/v1/messages";
    public static final String CLIENT_ID = "mcode-public";
    public static final String AUDIENCE = "agent-backend";
    public static final String SCOPE = "agent.default";
    public static final String CREDENTIAL_REF = "JETHUB_MINIMAX_TOKEN";
    public static final int HTTP_TIMEOUT_MS = 30_000;

    private final CredentialStore store;

    public MinimaxProvider(CredentialStore store) { this.store = store; }

    public static final class Credential {
        public String accessToken = "";
        public String refreshToken = "";
        public Long expireTimeMs;
        public String nickname = "";

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("access_token", accessToken);
            o.put("refresh_token", refreshToken);
            if (expireTimeMs != null) o.put("expire_time", expireTimeMs.longValue());
            o.put("nickname", nickname);
            return o;
        }

        public static Credential fromJson(JSONObject o) {
            Credential c = new Credential();
            c.accessToken = o.optString("access_token", "");
            c.refreshToken = o.optString("refresh_token", "");
            // jethub 凭据字段是 expires_at（毫秒字符串）；本类持久化用 expire_time
            if (o.has("expire_time")) c.expireTimeMs = o.optLong("expire_time");
            else if (o.has("expires_at")) {
                long v = o.optLong("expires_at");
                if (v > 0 && v < 1_000_000_000_000L) v *= 1000L;
                c.expireTimeMs = v;
            }
            c.nickname = o.optString("nickname", "");
            return c;
        }
    }

    public Credential loadCredential() {
        String raw = store.get(CREDENTIAL_REF);
        if (raw == null || raw.isEmpty()) return null;
        try { return Credential.fromJson(new JSONObject(raw)); } catch (Exception e) { return null; }
    }

    public void saveCredential(Credential c) throws Exception { store.set(CREDENTIAL_REF, c.toJson().toString()); }

    public boolean isExpired(Credential c) {
        return c.expireTimeMs != null && System.currentTimeMillis() >= c.expireTimeMs.longValue();
    }

    /** refresh_token grant 续期；终态抛 TokenExpiredException。 */
    public synchronized Credential refresh(Credential c) throws Exception {
        if (c.refreshToken == null || c.refreshToken.isEmpty()) {
            throw new TokenExpiredException("minimax: no refresh token, re-login required");
        }
        StringBuilder form = new StringBuilder();
        form.append("grant_type=refresh_token")
            .append("&refresh_token=").append(enc(c.refreshToken))
            .append("&client_id=").append(enc(CLIENT_ID))
            .append("&scope=").append(enc(SCOPE))
            .append("&audience=").append(enc(AUDIENCE));
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        HttpFetcher.Response resp = HttpFetcher.fetch("POST", ACCOUNT_HOST + TOKEN_PATH, h,
                form.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status != 200) {
            // OAuth 错误（invalid_grant 等）即终态
            throw new TokenExpiredException("minimax: refresh failed HTTP " + resp.status + " " + shortText(text));
        }
        JSONObject o = new JSONObject(text);
        String at = o.optString("access_token", "");
        if (at.isEmpty()) throw new IllegalStateException("minimax: token grant missing access_token");
        c.accessToken = at;
        String rt = o.optString("refresh_token", "");
        if (!rt.isEmpty()) c.refreshToken = rt;
        long expiresIn = o.optLong("expires_in", 3600);
        c.expireTimeMs = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;
        saveCredential(c);
        return c;
    }

    public Credential ensureFresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException("minimax: not logged in");
        if (isExpired(c)) c = refresh(c);
        return c;
    }

    // ── 模型目录 ──

    public JSONObject listModels() throws Exception {
        Credential c = ensureFresh();
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + c.accessToken);
        h.put("Accept", "application/json");
        HttpFetcher.Response resp = HttpFetcher.get(API_HOST + MODELS_PATH, h, HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status == 401 || resp.status == 403) {
            throw new TokenExpiredException("minimax: models HTTP " + resp.status);
        }
        if (resp.status != 200) throw new IllegalStateException("minimax: models HTTP " + resp.status + " " + shortText(text));
        JSONObject root = new JSONObject(text);
        JSONArray providers = root.optJSONArray("providers");
        if (providers == null) throw new IllegalStateException("minimax: models payload has no providers");
        JSONObject minimax = null;
        for (int i = 0; i < providers.length(); i++) {
            JSONObject p = providers.optJSONObject(i);
            if (p != null && "minimax".equals(p.optString("providerId"))) { minimax = p; break; }
        }
        if (minimax == null) throw new IllegalStateException("minimax: provider entry missing");
        JSONObject config = minimax.optJSONObject("config");
        JSONObject models = config == null ? null : config.optJSONObject("models");
        if (models == null) throw new IllegalStateException("minimax: config.models missing");
        JSONArray order = config.optJSONArray("model_order");

        JSONArray out = new JSONArray();
        // 先按 model_order 输出，再补未列入 order 的键
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        if (order != null) for (int i = 0; i < order.length(); i++) {
            String id = order.optString(i, "");
            if (!id.isEmpty() && models.has(id)) ids.add(id);
        }
        java.util.Iterator<String> keys = models.keys();
        while (keys.hasNext()) { String k = keys.next(); ids.add(k); }
        for (String id : ids) {
            JSONObject nm = new JSONObject();
            nm.put("id", ID + "/" + id);
            nm.put("object", "model");
            nm.put("owned_by", ID);
            JSONObject meta = models.optJSONObject(id);
            if (meta != null && meta.has("name")) nm.put("name", meta.optString("name"));
            out.put(nm);
        }
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", out);
        return result;
    }

    // ── 对话（OpenAI → Anthropic messages → SSE → OpenAI）──

    public Map<String, String> chatHeaders(Credential c) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + c.accessToken);
        h.put("Content-Type", "application/json");
        h.put("Accept", "text/event-stream");
        return h;
    }

    /** OpenAI chat 请求 → Anthropic messages 请求体。 */
    public static JSONObject toMessagesPayload(JSONObject openai) throws org.json.JSONException {
        JSONObject payload = new JSONObject();
        payload.put("model", openai.optString("model"));
        payload.put("stream", true);
        JSONArray out = new JSONArray();
        StringBuilder system = new StringBuilder();
        JSONArray messages = openai.optJSONArray("messages");
        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.getJSONObject(i);
                String role = m.optString("role", "user");
                String content = contentText(m.opt("content"));
                if ("system".equals(role) || "developer".equals(role)) {
                    if (system.length() > 0) system.append("\n\n");
                    system.append(content);
                    continue;
                }
                if (content.isEmpty()) continue;
                JSONObject om = new JSONObject();
                om.put("role", "assistant".equals(role) ? "assistant" : "user");
                om.put("content", content);
                out.put(om);
            }
        }
        payload.put("messages", out);
        if (system.length() > 0) payload.put("system", system.toString());
        payload.put("max_tokens", openai.optInt("max_tokens", 8192));
        if (openai.has("temperature") && !openai.isNull("temperature")) {
            payload.put("temperature", openai.optDouble("temperature"));
        }
        JSONArray stop = openai.optJSONArray("stop");
        if (stop != null && stop.length() > 0) payload.put("stop_sequences", stop);
        return payload;
    }

    private static String contentText(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof JSONArray) {
            StringBuilder sb = new StringBuilder();
            JSONArray arr = (JSONArray) content;
            for (int i = 0; i < arr.length(); i++) {
                Object part = arr.opt(i);
                if (part instanceof String) { sb.append((String) part); continue; }
                if (part instanceof JSONObject) {
                    JSONObject p = (JSONObject) part;
                    if ("text".equals(p.optString("type"))) sb.append(p.optString("text", ""));
                }
            }
            return sb.toString();
        }
        return String.valueOf(content);
    }

    public HttpFetcher.Response chatRaw(Credential c, JSONObject messagesPayload) throws Exception {
        return HttpFetcher.fetch("POST", API_HOST + INFER_PATH, chatHeaders(c),
                messagesPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 600_000);
    }

    private static String enc(String s) {
        try { return URLEncoder.encode(s, "UTF-8"); }
        catch (UnsupportedEncodingException e) { return s; }
    }

    private static String shortText(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    public static class TokenExpiredException extends IllegalStateException {
        public TokenExpiredException(String msg) { super(msg); }
    }

    // ── 设备码 + PKCE 登录（对齐 minimax-oauth.ts）──

    public static final class DeviceAuthorization {
        public String deviceCode;
        public String codeVerifier;
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete;
        public long expiresAtMs;
        public long intervalMs;
    }

    public static final class PollResult {
        public enum Kind { PENDING, SLOW_DOWN, DENIED, EXPIRED, DONE, ERROR }
        public Kind kind;
        public String message = "";
    }

    /** 申请设备码。 */
    public DeviceAuthorization startDeviceLogin() throws Exception {
        byte[] raw = new byte[32];
        new java.security.SecureRandom().nextBytes(raw);
        String verifier = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String challenge = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(digest);

        String form = "client_id=" + enc(CLIENT_ID)
                + "&scope=" + enc(SCOPE)
                + "&audience=" + enc(AUDIENCE)
                + "&code_challenge=" + enc(challenge)
                + "&code_challenge_method=S256";
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        HttpFetcher.Response resp = HttpFetcher.fetch("POST", ACCOUNT_HOST + "/oauth2/device/code", h,
                form.getBytes(java.nio.charset.StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status != 200) throw new IllegalStateException("minimax: device code HTTP " + resp.status);
        JSONObject o = new JSONObject(text);
        DeviceAuthorization a = new DeviceAuthorization();
        a.deviceCode = o.optString("device_code", "");
        a.userCode = o.optString("user_code", "");
        a.verificationUri = o.optString("verification_uri", o.optString("verification_url", ""));
        a.verificationUriComplete = o.optString("verification_uri_complete", a.verificationUri);
        if (a.deviceCode.isEmpty() || a.userCode.isEmpty() || a.verificationUri.isEmpty()) {
            throw new IllegalStateException("minimax: device code response unparsable");
        }
        a.expiresAtMs = System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000L;
        // ⚠️ `interval` 单位是**秒**，必须先乘 1000 再取下限。
        // 写成 Math.max(1000, interval) * 1000 会让 5 秒变成 1000 秒：
        // 设备码 600 秒就过期，自动轮询永远来不及触发，用户授权完也不会落库
        //（表现为「浏览器里点了授权，回到 App 还是没登录」）。
        a.intervalMs = Math.max(1000L, o.optLong("interval", 5) * 1000L);
        a.codeVerifier = verifier;
        return a;
    }

    /** 轮询一次；DONE 时保存凭据。⚠️ pending 是 200+status=pending（非标形态）。 */
    public PollResult pollDeviceLogin(DeviceAuthorization a) throws Exception {
        String form = "grant_type=" + enc("urn:ietf:params:oauth:grant-type:device_code")
                + "&device_code=" + enc(a.deviceCode)
                + "&client_id=" + enc(CLIENT_ID)
                + "&code_verifier=" + enc(a.codeVerifier);
        Map<String, String> h = new HashMap<>();
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/x-www-form-urlencoded");
        HttpFetcher.Response resp = HttpFetcher.fetch("POST", ACCOUNT_HOST + TOKEN_PATH, h,
                form.getBytes(java.nio.charset.StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        JSONObject o;
        try { o = new JSONObject(text); } catch (Exception e) {
            o = new JSONObject();
        }
        String status = o.optString("status", "");
        String error = o.optString("error", "");
        PollResult r = new PollResult();

        if (resp.status == 200 && "pending".equals(status)) { r.kind = PollResult.Kind.PENDING; return r; }
        if (resp.status == 200 && "slow_down".equals(status)) { r.kind = PollResult.Kind.SLOW_DOWN; return r; }
        if (resp.status == 200 && ("denied".equals(status) || "access_denied".equals(status))) {
            r.kind = PollResult.Kind.DENIED; return r;
        }
        if (resp.status == 200 && ("expired".equals(status) || "expired_token".equals(status))) {
            r.kind = PollResult.Kind.EXPIRED; return r;
        }
        if ("authorization_pending".equals(error)) { r.kind = PollResult.Kind.PENDING; return r; }
        if ("slow_down".equals(error)) { r.kind = PollResult.Kind.SLOW_DOWN; return r; }
        if ("access_denied".equals(error)) { r.kind = PollResult.Kind.DENIED; return r; }
        if ("expired_token".equals(error) || "expired".equals(error)) { r.kind = PollResult.Kind.EXPIRED; return r; }

        if (resp.status == 200) {
            // 令牌授予：硬校验 access_token/refresh_token/token_type=Bearer/expires_in/scope
            String at = o.optString("access_token", "");
            String rt = o.optString("refresh_token", "");
            String tokenType = o.optString("token_type", "");
            long expiresIn = o.optLong("expires_in", 0);
            String scope = o.optString("scope", "");
            if (at.isEmpty() || rt.isEmpty() || !"Bearer".equalsIgnoreCase(tokenType) || expiresIn <= 0
                    || !scope.contains(SCOPE)) {
                r.kind = PollResult.Kind.ERROR;
                r.message = "token grant invalid (scope/token_type/expires_in)";
                return r;
            }
            Credential c = new Credential();
            c.accessToken = at;
            c.refreshToken = rt;
            c.expireTimeMs = System.currentTimeMillis() + Math.max(60, expiresIn - 60) * 1000L;
            saveCredential(c);
            r.kind = PollResult.Kind.DONE;
            return r;
        }
        r.kind = PollResult.Kind.ERROR;
        r.message = error.isEmpty() ? ("HTTP " + resp.status) : error;
        return r;
    }
}
