package jet.core;

import org.json.JSONObject;

/**
 * Cline 供应商（jethub cline-product.ts / cline-auth.ts / cline-oauth.ts 的 Java 移植）。
 *
 * 协议要点（均来自逆向实测，勿凭直觉改动）：
 * - 设备码登录：POST {workOsBase}/user_management/authorize/device，
 *   轮询 POST {workOsBase}/user_management/authenticate
 *   （form：grant_type=urn:ietf:params:oauth:grant-type:device_code&device_code=…&client_id=…），
 *   slow_down 时 interval 累积 +1s；
 * - 注册：POST {apiBase}/api/v1/auth/register，body 驼峰 {accessToken, refreshToken}（WorkOS 令牌）；
 * - 续期：POST {apiBase}/api/v1/auth/refresh，body 驼峰 {refreshToken, grantType:"refresh_token"}；
 * - 响应信封判据是 success && data.accessToken（兼容裸响应）；
 * - access_token 存储时带 workos: 前缀，请求头原样使用，**不可剥前缀**；
 * - 鉴权头：Authorization: Bearer workos:<jwt> + 4 个官方身份头；
 * - 过期时间：ISO8601 字符串或秒/毫秒数字（<1e12 视为秒）。
 */
public final class ClineProvider {

    public static final String ID = "cline";
    public static final String CREDENTIAL_REF = "JETHUB_CLINE_TOKEN";

    public static final String API_BASE = "https://api.cline.bot";
    public static final String WORK_OS_BASE = "https://api.workos.com";
    public static final String WORK_OS_CLIENT_ID = "client_01K3A541FN8TA3EPPHTD2325AR";

    public static final String DEVICE_AUTHORIZATION_PATH = "/user_management/authorize/device";
    public static final String DEVICE_AUTHENTICATE_PATH = "/user_management/authenticate";
    public static final String REGISTER_PATH = "/api/v1/auth/register";
    public static final String REFRESH_PATH = "/api/v1/auth/refresh";
    public static final String CHAT_PATH = "/api/v1/chat/completions";
    public static final String MODELS_PATH = "/api/v1/models";
    public static final String RECOMMENDED_MODELS_PATH = "/api/v1/ai/cline/recommended-models";
    public static final String ME_PATH = "/api/v1/users/me";

    public static final int HTTP_TIMEOUT_MS = 30_000;

    private static final String[][] CLIENT_HEADERS = {
            {"HTTP-Referer", "https://cline.bot"},
            {"X-Title", "Cline"},
            {"X-IS-MULTIROOT", "false"},
            {"X-CLIENT-TYPE", "cline-sdk"},
    };

    private final CredentialStore store;

    public ClineProvider(CredentialStore store) {
        this.store = store;
    }

    // ── 身份头 ──

    public static void addClientHeaders(java.util.Map<String, String> headers) {
        for (String[] h : CLIENT_HEADERS) headers.put(h[0], h[1]);
    }

    // ── 凭据 ──

    /** 凭据 JSON 字段：access_token / refresh_token / expire_time / account_id / email / display_name。 */
    public static final class Credential {
        public String accessToken = "";
        public String refreshToken;
        public Long expireTimeMs;
        public String accountId;
        public String email;
        public String displayName;

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("access_token", accessToken);
            if (refreshToken != null) o.put("refresh_token", refreshToken);
            if (expireTimeMs != null) o.put("expire_time", expireTimeMs.longValue());
            if (accountId != null) o.put("account_id", accountId);
            if (email != null) o.put("email", email);
            if (displayName != null) o.put("display_name", displayName);
            return o;
        }

        public static Credential fromJson(JSONObject o) throws org.json.JSONException {
            Credential c = new Credential();
            c.accessToken = o.optString("access_token", "");
            if (o.has("refresh_token")) c.refreshToken = o.getString("refresh_token");
            if (o.has("expire_time")) c.expireTimeMs = o.getLong("expire_time");
            if (o.has("account_id")) c.accountId = o.optString("account_id", null);
            if (o.has("email")) c.email = o.optString("email", null);
            if (o.has("display_name")) c.displayName = o.optString("display_name", null);
            return c;
        }
    }

    public Credential loadCredential() {
        String raw = store.get(CREDENTIAL_REF);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return Credential.fromJson(new JSONObject(raw));
        } catch (Exception e) {
            return null;
        }
    }

    private void saveCredential(Credential c) {
        try {
            store.set(CREDENTIAL_REF, c.toJson().toString());
        } catch (org.json.JSONException e) {
            throw new IllegalStateException("凭据序列化失败", e);
        }
    }

    public void logout() {
        store.remove(CREDENTIAL_REF);
    }

    public boolean isRefreshable(Credential c) {
        return c != null && c.refreshToken != null && !c.refreshToken.isEmpty();
    }

    public boolean isExpired(Credential c) {
        return c != null && c.expireTimeMs != null && c.expireTimeMs <= System.currentTimeMillis();
    }

    /** 过期时间解析：ISO8601 / 秒 / 毫秒。 */
    public static Long parseTimestamp(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            if (d <= 0) return null;
            return d < 1e12 ? Math.round(d * 1000) : Math.round(d);
        }
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try {
                long ms = androidSafeParseIso(((String) value).trim());
                return ms;
            } catch (Exception e) {
                return null;
            }
        }
        return null;
    }

    /** ISO8601 → 毫秒（不依赖 java.time，Android API<26 也能跑）。 */
    static long androidSafeParseIso(String iso) throws Exception {
        // 形如 2026-09-25T05:23:47.000Z；容错无毫秒/无 Z/带偏移的常见形态
        String s = iso;
        int frac = -1;
        // 统一处理：找日期时间主体
        if (s.endsWith("Z")) s = s.substring(0, s.length() - 1) + "+0000";
        // 取出偏移
        String offset = "+0000";
        int signIdx = -1;
        for (int i = s.length() - 1; i >= 10; i--) {
            char ch = s.charAt(i);
            if (ch == '+' || ch == '-') { signIdx = i; break; }
        }
        if (signIdx > 10) {
            offset = s.substring(signIdx).replace(":", "");
            s = s.substring(0, signIdx);
        }
        // 拆毫秒
        int dot = s.indexOf('.');
        long fracMs = 0;
        if (dot >= 0) {
            String fracStr = s.substring(dot + 1);
            s = s.substring(0, dot);
            while (fracStr.length() < 3) fracStr += "0";
            fracMs = Long.parseLong(fracStr.substring(0, 3));
        }
        java.util.Calendar cal = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        cal.clear();
        cal.set(Integer.parseInt(s.substring(0, 4)), Integer.parseInt(s.substring(5, 7)) - 1,
                Integer.parseInt(s.substring(8, 10)), Integer.parseInt(s.substring(11, 13)),
                Integer.parseInt(s.substring(14, 16)), Integer.parseInt(s.substring(17, 19)));
        long ms = cal.getTimeInMillis() + fracMs;
        // 应用偏移（如 +0800）
        if (offset.length() >= 5 && !offset.equals("+0000")) {
            int oh = Integer.parseInt(offset.substring(1, 3));
            int om = Integer.parseInt(offset.substring(3, 5));
            long delta = oh * 3600_000L + om * 60_000L;
            ms += offset.charAt(0) == '+' ? -delta : delta;
        }
        return ms;
    }

    // ── 登录：设备码 ──

    public static final class DeviceAuthorization {
        public String deviceCode;
        public String userCode;
        public String verificationUri;
        public String verificationUriComplete;
        public long intervalMs = 5000;
        public long expiresAtMs;
    }

    /** 步骤 1：申请设备码。 */
    public DeviceAuthorization startDeviceLogin() throws Exception {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        addClientHeaders(headers);
        JSONObject body = new JSONObject();
        body.put("client_id", WORK_OS_CLIENT_ID);
        HttpFetcher.Response resp = HttpFetcher.postJson(
                WORK_OS_BASE + DEVICE_AUTHORIZATION_PATH, headers, body.toString(), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status != 200) {
            throw new IllegalStateException("设备码申请失败（HTTP " + resp.status + "）：" + snippet(text));
        }
        JSONObject o = new JSONObject(text);
        DeviceAuthorization a = new DeviceAuthorization();
        a.deviceCode = o.optString("device_code", "");
        a.userCode = o.optString("user_code", "");
        a.verificationUri = o.optString("verification_uri", "");
        a.verificationUriComplete = o.optString("verification_uri_complete", "");
        a.intervalMs = Math.max(1000, o.optLong("interval", 5) * 1000L);
        a.expiresAtMs = System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000L;
        if (a.deviceCode.isEmpty() || a.userCode.isEmpty() || a.verificationUri.isEmpty()) {
            throw new IllegalStateException("设备码响应缺少必要字段：" + snippet(text));
        }
        return a;
    }

    /** 轮询结果。 */
    public static final class PollResult {
        public static final int PENDING = 0;
        public static final int SLOW_DOWN = 1;
        public static final int DONE = 2;
        public static final int EXPIRED = 3;
        public static final int DENIED = 4;
        public final int kind;
        public PollResult(int kind) { this.kind = kind; }
    }

    /** 步骤 2：单次轮询。返回 kind；SLOW_DOWN 时调用方须把 interval +1s。 */
    public PollResult pollDeviceLogin(DeviceAuthorization a) throws Exception {
        StringBuilder form = new StringBuilder();
        form.append("grant_type=").append(enc("urn:ietf:params:oauth:grant-type:device_code"));
        form.append("&device_code=").append(enc(a.deviceCode));
        form.append("&client_id=").append(enc(WORK_OS_CLIENT_ID));
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/x-www-form-urlencoded");
        headers.put("Accept", "application/json");
        addClientHeaders(headers);
        HttpFetcher.Response resp = HttpFetcher.fetch("POST",
                WORK_OS_BASE + DEVICE_AUTHENTICATE_PATH, headers,
                form.toString().getBytes("UTF-8"), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        JSONObject o = new JSONObject(text);
        String error = o.optString("error", "");
        if ("authorization_pending".equals(error)) return new PollResult(PollResult.PENDING);
        if ("slow_down".equals(error)) return new PollResult(PollResult.SLOW_DOWN);
        if ("expired_token".equals(error) || "access_denied".equals(error)) {
            return new PollResult("access_denied".equals(error) ? PollResult.DENIED : PollResult.EXPIRED);
        }
        if (resp.status != 200 || o.optString("access_token", "").isEmpty()) {
            throw new IllegalStateException("设备码轮询失败（HTTP " + resp.status + "）：" + snippet(text));
        }
        // 步骤 3：WorkOS 令牌换 Cline 令牌
        registerTokens(o.getString("access_token"), o.getString("refresh_token"));
        return new PollResult(PollResult.DONE);
    }

    private void registerTokens(String workOsAccess, String workOsRefresh) throws Exception {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        addClientHeaders(headers);
        JSONObject body = new JSONObject();
        body.put("accessToken", workOsAccess);
        body.put("refreshToken", workOsRefresh);
        HttpFetcher.Response resp = HttpFetcher.postJson(
                API_BASE + REGISTER_PATH, headers, body.toString(), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status != 200) {
            throw new IllegalStateException("Cline 注册失败（HTTP " + resp.status + "）：" + snippet(text));
        }
        Credential c = parseTokenEnvelope(text);
        if (c == null || c.accessToken.isEmpty()) {
            throw new IllegalStateException("Cline 注册响应缺少访问令牌：" + snippet(text));
        }
        saveCredential(c);
    }

    // ── 续期 ──

    /** 续期。返回新凭据；refresh_token 失效时抛 TokenExpiredException。 */
    public Credential refresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException("未配置凭据，请先登录");
        if (!isRefreshable(c)) throw new TokenExpiredException("无 refresh_token，请重新登录");
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        addClientHeaders(headers);
        JSONObject body = new JSONObject();
        body.put("refreshToken", c.refreshToken);
        body.put("grantType", "refresh_token");
        HttpFetcher.Response resp = HttpFetcher.postJson(
                API_BASE + REFRESH_PATH, headers, body.toString(), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status == 401 || resp.status == 403) {
            throw new TokenExpiredException("Cline 续期被拒绝（HTTP " + resp.status + "），请重新登录");
        }
        if (resp.status != 200) {
            throw new IllegalStateException("Cline 续期失败（HTTP " + resp.status + "）：" + snippet(text));
        }
        Credential next = parseTokenEnvelope(text);
        if (next == null || next.accessToken.isEmpty()) {
            throw new TokenExpiredException("续期响应缺少访问令牌，请重新登录");
        }
        saveCredential(next);
        return next;
    }

    /** 确保有可用令牌：过期（且可续期）时先续期。 */
    public Credential ensureFresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new TokenExpiredException("未登录 Cline");
        if (isExpired(c) && isRefreshable(c)) {
            try {
                c = refresh();
            } catch (TokenExpiredException e) {
                throw e;
            } catch (Exception e) {
                // 网络抖动等：仍尝试用现有令牌
            }
        }
        return c;
    }

    // ── 响应信封解析 ──

    /** 判据是 success && data.accessToken（兼容裸响应）。 */
    static Credential parseTokenEnvelope(String text) throws Exception {
        JSONObject o = new JSONObject(text);
        JSONObject inner = o;
        if (o.has("data") && o.optJSONObject("data") != null && o.optBoolean("success", true)) {
            inner = o.getJSONObject("data");
        }
        String access = firstString(inner, "accessToken", "access_token");
        if (access == null || access.isEmpty()) return null;
        Credential c = new Credential();
        // access token 原样保留（可能已带 workos: 前缀；无前缀则补上）
        c.accessToken = access.startsWith("workos:") ? access : "workos:" + access;
        c.refreshToken = firstString(inner, "refreshToken", "refresh_token");
        c.expireTimeMs = parseTimestamp(inner.opt("expiresAt") != null ? inner.opt("expiresAt")
                : inner.opt("expires_at") != null ? inner.opt("expires_at") : inner.opt("expire_time"));
        JSONObject userInfo = inner.optJSONObject("userInfo");
        if (userInfo != null) {
            c.accountId = userInfo.optString("clineUserId", null);
            c.email = userInfo.optString("email", null);
            String first = userInfo.optString("firstName", "");
            String last = userInfo.optString("lastName", "");
            String name = (first + " " + last).trim();
            if (!name.isEmpty()) c.displayName = name;
        }
        return c;
    }

    private static String firstString(JSONObject o, String... keys) {
        for (String k : keys) {
            String v = o.optString(k, "");
            if (!v.trim().isEmpty()) return v.trim();
        }
        return null;
    }

    // ── 请求头 ──

    public java.util.Map<String, String> authHeaders(Credential c) {
        java.util.Map<String, String> headers = new java.util.HashMap<>();
        headers.put("Authorization", "Bearer " + c.accessToken);
        headers.put("Accept", "application/json");
        addClientHeaders(headers);
        return headers;
    }

    public String modelsUrl() { return API_BASE + MODELS_PATH; }
    public String chatUrl() { return API_BASE + CHAT_PATH; }
    public String recommendedModelsUrl() { return API_BASE + RECOMMENDED_MODELS_PATH; }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; }
    }

    private static String snippet(String s) {
        if (s == null) return "";
        s = s.replace('\n', ' ');
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** refresh_token 失效（终态，需重新登录）。 */
    public static final class TokenExpiredException extends Exception {
        public TokenExpiredException(String message) { super(message); }
    }
}
