package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * CodeBuddy（腾讯 copilot）/ WorkBuddy（国际版）适配器。
 *
 * - 登录：浏览器轮询式（auth/state → 用户授权 → auth/token?state 轮询），可移植到手机
 * - 续期：POST /v2/plugin/auth/token/refresh，X-Refresh-Token 头，{code,message,data} 信封
 * - 模型：GET /console/enterprises/personal/models（优先）+ GET /v3/config（合并）
 * - 对话：POST /v2/chat/completions（标准 OpenAI SSE）
 */
public final class BuddyProvider {

    public static final String ID = "buddy";
    public static final String REFRESH_PATH = "/v2/plugin/auth/token/refresh";
    public static final String CHAT_PATH = "/v2/chat/completions";
    public static final String CONFIG_PATH = "/v3/config";
    public static final String SCOPED_MODELS_PATH = "/console/enterprises/personal/models";
    public static final int HTTP_TIMEOUT_MS = 30_000;

    private final CredentialStore store;
    private final String credentialRef;
    private final String endpoint;
    private final String apiDomain;
    private final String productCode;
    private final String attributionName;
    private final String clientVersion;
    private final String userAgent;
    private final String modelPrefix;
    private final Variant variant;

    public BuddyProvider(CredentialStore store, Variant variant) {
        this.store = store;
        this.variant = variant;
        this.credentialRef = variant.credentialRef;
        this.endpoint = variant.endpoint;
        this.apiDomain = variant.apiDomain;
        this.productCode = variant.productCode;
        this.attributionName = variant.attributionName;
        this.clientVersion = variant.clientVersion;
        this.userAgent = variant.userAgent;
        this.modelPrefix = variant.modelPrefix;
    }

    public static final class Variant {
        public final String credentialRef, endpoint, apiDomain, productCode, attributionName, clientVersion, userAgent, modelPrefix;
        public final String platform;
        public final String pluginVersion;
        public final boolean appendSessionParams;

        public Variant(String credentialRef, String endpoint, String apiDomain, String productCode,
                       String attributionName, String clientVersion, String userAgent, String modelPrefix) {
            this(credentialRef, endpoint, apiDomain, productCode, attributionName, clientVersion, userAgent,
                    modelPrefix, "ide", "", false);
        }

        public Variant(String credentialRef, String endpoint, String apiDomain, String productCode,
                       String attributionName, String clientVersion, String userAgent, String modelPrefix,
                       String platform, String pluginVersion, boolean appendSessionParams) {
            this.credentialRef = credentialRef; this.endpoint = endpoint; this.apiDomain = apiDomain;
            this.productCode = productCode; this.attributionName = attributionName;
            this.clientVersion = clientVersion; this.userAgent = userAgent; this.modelPrefix = modelPrefix;
            this.platform = platform; this.pluginVersion = pluginVersion;
            this.appendSessionParams = appendSessionParams;
        }

        /** CodeBuddy 中国版（copilot.tencent.com）。 */
        public static Variant buddy() {
            return new Variant("JETHUB_BUDDY_TOKEN", "https://copilot.tencent.com", "copilot.tencent.com",
                    "codebuddy", "CodeBuddy", "1.106.1", "CodeBuddyIDE/1.106.1", "buddy");
        }

        /** WorkBuddy 国际版（www.workbuddy.ai）。 */
        public static Variant workbuddy() {
            return new Variant("JETHUB_WORKBUDDY_TOKEN", "https://www.workbuddy.ai", "www.workbuddy.ai",
                    "workbuddy", "WorkBuddy", "5.5.2", "WorkBuddy/5.5.2 WorkBuddy AI/5.5.2 CLI/5.5.2", "workbuddy",
                    "workbuddy-ai", "5.5.2", true);
        }
    }

    // ── 凭据 ──

    public static final class Credential {
        public String accessToken = "";
        public String refreshToken = "";
        public Long expireTimeMs;
        public String domain = "";
        public String enterpriseId = "";
        public String nickname = "";
        public String email = "";

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("access_token", accessToken);
            if (refreshToken != null) o.put("refresh_token", refreshToken);
            if (expireTimeMs != null) o.put("expire_time", expireTimeMs.longValue());
            o.put("domain", domain);
            o.put("enterprise_id", enterpriseId);
            o.put("nickname", nickname);
            o.put("email", email);
            return o;
        }

        public static Credential fromJson(JSONObject o) {
            Credential c = new Credential();
            c.accessToken = o.optString("access_token", "");
            c.refreshToken = o.optString("refresh_token", "");
            if (o.has("expire_time")) c.expireTimeMs = o.optLong("expire_time");
            c.domain = o.optString("domain", "");
            c.enterpriseId = o.optString("enterprise_id", "");
            c.nickname = o.optString("nickname", "");
            c.email = o.optString("email", "");
            return c;
        }
    }

    public String variantProductCode() { return productCode; }

    public Credential loadCredential() {
        String raw = store.get(credentialRef);
        if (raw == null || raw.isEmpty()) return null;
        try { return Credential.fromJson(new JSONObject(raw)); } catch (Exception e) { return null; }
    }

    public void saveCredential(Credential c) throws Exception {
        store.set(credentialRef, c.toJson().toString());
    }

    public boolean isExpired(Credential c) {
        return c.expireTimeMs != null && System.currentTimeMillis() >= c.expireTimeMs.longValue();
    }

    private Map<String, String> baseHeaders(Credential c) {
        Map<String, String> h = new HashMap<>();
        h.put("X-Domain", c.domain == null || c.domain.isEmpty() ? apiDomain : c.domain);
        h.put("User-Agent", userAgent);
        if (c.enterpriseId != null && !c.enterpriseId.isEmpty()) {
            h.put("X-Enterprise-Id", c.enterpriseId);
            h.put("X-Tenant-Id", c.enterpriseId);
        }
        return h;
    }

    /** X-Refresh-Token 续期；终态（401/403/expired/invalid）抛 TokenExpiredException。 */
    public synchronized Credential refresh(Credential c) throws Exception {
        if (c.refreshToken == null || c.refreshToken.isEmpty()) {
            throw new TokenExpiredException(attributionName + ": no refresh token, re-login required");
        }
        Map<String, String> h = baseHeaders(c);
        h.put("Authorization", "Bearer " + c.accessToken);
        h.put("X-Refresh-Token", c.refreshToken);
        h.put("X-Auth-Refresh-Source", "ide-main");
        HttpFetcher.Response resp = HttpFetcher.fetch("POST", endpoint + REFRESH_PATH, h,
                new byte[0], HTTP_TIMEOUT_MS);
        String text = resp.readText();
        int status = resp.status;
        String message = "";
        JSONObject data = null;
        try {
            JSONObject o = new JSONObject(text);
            message = o.optString("message", "");
            data = o.optJSONObject("data");
            if (status == 200 && o.optInt("code", 200) != 200) status = o.optInt("code");
        } catch (Exception ignored) { }
        boolean expired = status == 401 || status == 403
                || message.contains("expired") || message.contains("invalid");
        if (expired) throw new TokenExpiredException(attributionName + ": refresh rejected: " + message);
        if (status != 200 || data == null) {
            throw new IllegalStateException(attributionName + ": refresh HTTP " + status + " " + shortText(text));
        }
        String at = data.optString("access_token", data.optString("token", ""));
        if (at.isEmpty()) throw new TokenExpiredException(attributionName + ": refresh response has no token");
        c.accessToken = at;
        String rt = data.optString("refresh_token", "");
        if (!rt.isEmpty()) c.refreshToken = rt;
        long exp = data.optLong("expires_at", data.optLong("expire_time", 0));
        if (exp > 0 && exp < 1_000_000_000_000L) exp *= 1000L;
        if (exp <= 0) exp = System.currentTimeMillis() + 3600_000L;
        c.expireTimeMs = exp;
        if (data.has("domain")) c.domain = data.optString("domain", c.domain);
        saveCredential(c);
        return c;
    }

    public Credential ensureFresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException(attributionName + ": not logged in");
        if (isExpired(c)) c = refresh(c);
        return c;
    }

    // ── 模型目录（scoped 优先 + /v3/config 合并）──

    public JSONObject listModels() throws Exception {
        Credential c = ensureFresh();
        Map<String, String> h = baseHeaders(c);
        h.put("Authorization", "Bearer " + c.accessToken);
        h.put("Accept", "application/json");

        Map<String, JSONObject> models = new java.util.LinkedHashMap<>();
        fetchModelsInto(h, endpoint + SCOPED_MODELS_PATH, models);
        if (models.isEmpty()) fetchModelsInto(h, endpoint + CONFIG_PATH, models);
        if (models.isEmpty()) throw new IllegalStateException(attributionName + ": model list empty");

        JSONArray outData = new JSONArray();
        for (JSONObject m : models.values()) outData.put(m);
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", outData);
        return result;
    }

    private void fetchModelsInto(Map<String, String> h, String url, Map<String, JSONObject> out) throws Exception {
        HttpFetcher.Response resp = HttpFetcher.get(url, h, HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status != 200) return;
        JSONObject root;
        try { root = new JSONObject(text); } catch (Exception e) { return; }
        JSONObject data = root.optJSONObject("data");
        if (data == null) return;
        JSONArray list = data.optJSONArray("models");
        if (list == null) return;
        for (int i = 0; i < list.length(); i++) {
            JSONObject e = list.optJSONObject(i);
            if (e == null) continue;
            String id = e.optString("id", "");
            if (id.isEmpty() || out.containsKey(id)) continue;
            if (!isChatModel(id, e)) continue;
            JSONObject nm = new JSONObject();
            nm.put("id", modelPrefix + "/" + id);
            nm.put("object", "model");
            nm.put("owned_by", modelPrefix);
            String name = e.optString("name", "");
            if (!name.isEmpty()) {
                // 计费倍率来自 credits（实测形态 "x0.00 credits" / "0.50x"）。
                // 拼进 name 是刻意的：墨听的模型条目没有倍率字段，展示名是唯一载体，
                // 而用户在模型中心挑选模型时看的就是展示名。解析不出就不加后缀，
                // 不回退成 x1 —— 编造倍率比不显示更糟。
                String suffix = ModelRate.fromCredits(e.opt("credits"));
                String withRate = ModelRate.displayName(name, suffix);
                nm.put("name", withRate == null ? name : withRate);
            }
            out.put(id, nm);
        }
    }

    /** 对齐 isChatModel：排除内嵌/补全/私有前缀、生图、极小输出上限的条目。 */
    private static boolean isChatModel(String id, JSONObject meta) {
        if (id.startsWith("nes-") || id.startsWith("completion-") || id.startsWith("codewise-")) return false;
        if (meta.optBoolean("supportsExtra", false)) return false;
        double maxOutput = meta.optDouble("maxOutputTokens", Double.NaN);
        if (!Double.isNaN(maxOutput) && maxOutput > 0 && maxOutput <= 256) return false;
        JSONArray tags = meta.optJSONArray("tags");
        if (tags != null) for (int i = 0; i < tags.length(); i++) {
            if ("text-to-image".equals(tags.optString(i))) return false;
        }
        return true;
    }

    // ── 浏览器轮询式登录（auth/state → 用户授权 → auth/token 轮询）──

    public static final class LoginSession {
        public String state;
        public String authUrl;
        public long expiresAtMs = System.currentTimeMillis() + 300_000L;
    }

    /** POST /v2/plugin/auth/state?platform=… → {state, authUrl}（无需认证）。 */
    public LoginSession startLogin() throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("X-Domain", apiDomain);
        h.put("X-No-Authorization", "true");
        h.put("X-No-User-Id", "true");
        h.put("X-No-Enterprise-Id", "true");
        h.put("X-No-Department-Info", "true");
        h.put("User-Agent", userAgent);
        HttpFetcher.Response resp = HttpFetcher.fetch("POST",
                endpoint + "/v2/plugin/auth/state?platform=" + variant.platform, h,
                new byte[0], 10_000);
        String text = resp.readText();
        if (resp.status != 200) throw new IllegalStateException(attributionName + ": auth/state HTTP " + resp.status);
        JSONObject o = new JSONObject(text);
        JSONObject data = o.optJSONObject("data");
        if (data == null) throw new IllegalStateException(attributionName + ": auth/state no data");
        LoginSession s = new LoginSession();
        s.state = data.optString("state", "");
        s.authUrl = data.optString("authUrl", "");
        if (s.state.isEmpty() || s.authUrl.isEmpty()) {
            throw new IllegalStateException(attributionName + ": auth/state missing state/authUrl");
        }
        // WorkBuddy：附加 version + loginSessionId（appendSessionParams）
        if (variant.appendSessionParams) {
            StringBuilder u = new StringBuilder(s.authUrl);
            u.append(s.authUrl.indexOf('?') >= 0 ? '&' : '?');
            if (variant.pluginVersion != null && !variant.pluginVersion.isEmpty()) {
                u.append("version=").append(enc(variant.pluginVersion)).append('&');
            }
            u.append("loginSessionId=").append(java.util.UUID.randomUUID().toString());
            s.authUrl = u.toString();
        }
        return s;
    }

    public static final class LoginPollResult {
        public enum Kind { PENDING, DONE, ERROR }
        public Kind kind;
        public String message = "";
    }

    /** GET /v2/plugin/auth/token?state=… 一次；11217=未就绪；DONE 时保存凭据。 */
    public LoginPollResult pollLogin(LoginSession s) throws Exception {
        Map<String, String> h = new HashMap<>();
        h.put("X-No-Authorization", "true");
        h.put("User-Agent", userAgent);
        HttpFetcher.Response resp = HttpFetcher.get(
                endpoint + "/v2/plugin/auth/token?state=" + urlEncode(s.state), h, 30_000);
        String text = resp.readText();
        LoginPollResult r = new LoginPollResult();
        if (resp.status == 200) {
            JSONObject o;
            try { o = new JSONObject(text); } catch (Exception e) { r.kind = LoginPollResult.Kind.PENDING; return r; }
            JSONObject data = o.optJSONObject("data");
            if (data == null) { r.kind = LoginPollResult.Kind.PENDING; return r; }
            String at = data.optString("accessToken", "");
            if (at.isEmpty()) { r.kind = LoginPollResult.Kind.PENDING; return r; }
            Credential c = new Credential();
            c.accessToken = at;
            c.refreshToken = data.optString("refreshToken", "");
            long exp = parseExpiryMs(data.optString("expiresAt", ""),
                    optLongBoxed(data, "expiresIn"), at);
            c.expireTimeMs = exp > 0 ? exp : System.currentTimeMillis() + 3600_000L;
            c.domain = data.optString("domain", variant.apiDomain);
            // 尽力补 enterpriseId/nickname（login/account；失败不阻塞）
            fetchAccountInto(s.state, c);
            saveCredential(c);
            r.kind = LoginPollResult.Kind.DONE;
            return r;
        }
        // {code,message,data} 信封；11217 = token 尚未就绪
        try {
            JSONObject o = new JSONObject(text);
            int code = o.optInt("code", resp.status);
            if (code == 11217) { r.kind = LoginPollResult.Kind.PENDING; return r; }
            r.kind = LoginPollResult.Kind.ERROR;
            r.message = "HTTP " + resp.status + " code=" + code + " " + o.optString("message", "");
            return r;
        } catch (Exception e) {
            r.kind = LoginPollResult.Kind.PENDING;
            return r;
        }
    }

    private void fetchAccountInto(String state, Credential c) {
        try {
            Map<String, String> h = new HashMap<>();
            h.put("X-Domain", c.domain == null || c.domain.isEmpty() ? apiDomain : c.domain);
            h.put("Authorization", "Bearer " + c.accessToken);
            h.put("X-No-User-Id", "true");
            h.put("X-No-Enterprise-Id", "true");
            h.put("User-Agent", userAgent);
            HttpFetcher.Response resp = HttpFetcher.get(
                    endpoint + "/v2/plugin/login/account?state=" + urlEncode(state), h, 10_000);
            if (resp.status != 200) return;
            JSONObject o = new JSONObject(resp.readText());
            JSONObject data = o.optJSONObject("data");
            if (data == null) return;
            c.enterpriseId = data.optString("enterpriseId", c.enterpriseId);
            String nickname = data.optString("nickname", "");
            if (!nickname.isEmpty()) c.nickname = nickname;
        } catch (Exception ignored) { }
    }

    /** expiresAt（绝对：ms/秒/ISO）→ expiresIn（相对，iat 兜底）→ JWT exp 兜底。 */
    private static long parseExpiryMs(String absolute, Long relativeSeconds, String accessToken) {
        if (absolute != null && !absolute.isEmpty()) {
            Long n = null;
            if (absolute.matches("\\d+")) n = Long.parseLong(absolute);
            else { try { n = java.time.Instant.parse(absolute).toEpochMilli(); } catch (Exception ignored) { } }
            if (n != null) return n > 1_000_000_000_000L ? n : n * 1000L;
        }
        long base = jwtClaimMs(accessToken, "iat");
        if (base <= 0) base = System.currentTimeMillis();
        if (relativeSeconds != null && relativeSeconds > 0) return base + relativeSeconds * 1000L;
        return jwtClaimMs(accessToken, "exp");
    }

    private static Long optLongBoxed(JSONObject o, String key) {
        return o.has(key) ? o.optLong(key) : null;
    }

    /** 解 JWT payload 的数值声明（无填充 base64url）。 */
    private static long jwtClaimMs(String jwt, String claim) {
        try {
            String[] parts = jwt.split("\\.");
            if (parts.length < 2) return 0;
            byte[] p = java.util.Base64.getUrlDecoder().decode(parts[1]);
            JSONObject o = new JSONObject(new String(p, java.nio.charset.StandardCharsets.UTF_8));
            long v = o.optLong(claim, 0);
            if (v <= 0) return 0;
            return v > 1_000_000_000_000L ? v : v * 1000L;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    private static String enc(String s) { return urlEncode(s); }

    // ── 对话（标准 OpenAI SSE）──

    public static final String UA_INTL_SUFFIX = "WorkBuddy AI/5.5.2 CLI/5.5.2";
    public static final String UA_CN_SUFFIX = "WorkBuddy/5.5.2 CLI/5.5.2";

    /** 国际版按模型系切 UA：国内系（glm/hy/kimi/minimax）走国内客户端形态。 */
    public String uaForModel(String model) {
        if (!"workbuddy".equals(productCode) || model == null) return userAgent;
        String m = model.toLowerCase();
        if (m.startsWith("gpt-") || m.startsWith("gemini-") || m.startsWith("claude-")) return userAgent;
        if (m.startsWith("glm-") || m.startsWith("hy") || m.startsWith("kimi-") || m.startsWith("minimax-")) {
            return "WorkBuddy/5.5.2 " + UA_CN_SUFFIX;
        }
        return userAgent;
    }

    public Map<String, String> chatHeaders(Credential c, String model) {
        Map<String, String> h = baseHeaders(c);
        h.put("User-Agent", uaForModel(model));
        h.put("Authorization", "Bearer " + c.accessToken);
        h.put("Accept", "text/event-stream");
        h.put("Content-Type", "application/json");
        h.put("X-Product-Code", productCode);
        h.put("X-Product", attributionName);
        h.put("X-Agent-Purpose", "conversation");
        h.put("X-IDE-Name", attributionName);
        h.put("X-IDE-Type", attributionName);
        h.put("X-IDE-Version", clientVersion);
        return h;
    }

    public HttpFetcher.Response chatRaw(Credential c, JSONObject openaiBody) throws Exception {
        String model = openaiBody.optString("model", "");
        return HttpFetcher.fetch("POST", endpoint + CHAT_PATH, chatHeaders(c, model),
                openaiBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), 600_000);
    }

    private static String shortText(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    public static class TokenExpiredException extends IllegalStateException {
        public TokenExpiredException(String msg) { super(msg); }
    }
}
