package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Trae 供应商适配器（移植自 jethub trae.ts / trae-auth.ts / trae-adapter.ts）。
 *
 * - 认证：Authorization: Cloud-IDE-JWT <token>；refresh_token 经 ExchangeToken 轮换（旧值即刻失效，必须回写）
 * - 模型：POST /api/ide/v1/batch_get_detail_param（一次拉全部 22 个通道目录）
 * - 对话：POST /api/agent/v3/llm_utils_chat（SOLO 自定义 body + 自定义 SSE，由网关转成 OpenAI 形状）
 */
public final class TraeProvider {

    public static final String ID = "trae";
    public static final String CREDENTIAL_REF = "JETHUB_TRAE_TOKEN";

    public static final String AGENT_HOST = "https://trae-api-cn.mchost.guru";
    public static final String OAUTH_HOST = "https://api.trae.com.cn";
    public static final String CLIENT_ID = "en1oxy7wnw8j9n";
    public static final String EXCHANGE_PATH = "/cloudide/api/v3/trae/oauth/ExchangeToken";
    public static final String BATCH_MODELS_PATH = "/api/ide/v1/batch_get_detail_param";
    public static final String CHAT_PATH = "/api/agent/v3/llm_utils_chat";
    public static final int HTTP_TIMEOUT_MS = 30_000;

    /** 对齐 trae-product.ts 的客户端身份（版本过低会被上游 4001 拒绝）。 */
    public static final String IDE_VERSION = "0.1.52";
    public static final String IDE_VERSION_CODE = "20260811";
    public static final String USER_AGENT = "Trae/0.1.52";
    public static final String APP_ID = "6eefa01c-1036-4c7e-9ca5-d891f63bfcd8";
    public static final String DEVICE_BRAND = "Apple";
    public static final String OS_VERSION = "macOS 15.7.4";
    public static final String DEFAULT_FUNCTION = "solo_work_lite";
    public static final String PLUGIN_VERSION = "2.3.62834";
    public static final String CONSOLE_HOST = "https://www.trae.cn";
    public static final String DEVICE_REF = "JETHUB_TRAE_DEVICE";

    private final CredentialStore store;
    /** config_name → 通道（function），列模型时记录，对话时取用。 */
    private final Map<String, String> channelByModel = new HashMap<>();

    public TraeProvider(CredentialStore store) {
        this.store = store;
    }

    // ── 凭据 ──

    public static final class Credential {
        public String accessToken = "";
        public String refreshToken = "";
        public Long expireTimeMs;
        public String uid = "";
        public String machineId = "";
        public String deviceId = "";
        public String nickname = "";
        public String email = "";

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("access_token", accessToken);
            if (refreshToken != null) o.put("refresh_token", refreshToken);
            if (expireTimeMs != null) o.put("expire_time", expireTimeMs.longValue());
            o.put("uid", uid);
            o.put("machine_id", machineId);
            o.put("device_id", deviceId);
            o.put("nickname", nickname);
            o.put("email", email);
            return o;
        }

        public static Credential fromJson(JSONObject o) {
            Credential c = new Credential();
            c.accessToken = o.optString("access_token", "");
            c.refreshToken = o.optString("refresh_token", "");
            if (o.has("expire_time")) c.expireTimeMs = o.optLong("expire_time");
            c.uid = o.optString("uid", "");
            c.machineId = o.optString("machine_id", "");
            c.deviceId = o.optString("device_id", "");
            c.nickname = o.optString("nickname", "");
            c.email = o.optString("email", "");
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

    public void saveCredential(Credential c) throws Exception {
        store.set(CREDENTIAL_REF, c.toJson().toString());
    }

    public boolean isExpired(Credential c) {
        return c.expireTimeMs != null && System.currentTimeMillis() >= c.expireTimeMs.longValue();
    }

    /**
     * ExchangeToken 轮换续期（对齐 applyTraeRefresh）：
     * TokenExpireAt 秒级（>1e12 视为毫秒）；RefreshToken 仅新值非空才更新。
     */
    public synchronized Credential refresh(Credential c) throws Exception {
        if (c.refreshToken == null || c.refreshToken.isEmpty()) {
            throw new IllegalStateException("trae: no refresh token, re-login required");
        }
        JSONObject body = new JSONObject();
        body.put("ClientID", CLIENT_ID);
        body.put("RefreshToken", c.refreshToken);
        body.put("ClientSecret", "-");
        body.put("UserID", "");
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("Accept", "application/json");
        headers.put("User-Agent", USER_AGENT);
        HttpFetcher.Response resp = HttpFetcher.fetch("POST", OAUTH_HOST + EXCHANGE_PATH, headers,
                body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status == 401 || resp.status == 403) {
            throw new TokenExpiredException("trae: refresh rejected HTTP " + resp.status);
        }
        if (resp.status < 200 || resp.status >= 300) {
            throw new IllegalStateException("trae: refresh HTTP " + resp.status + " " + shortText(text));
        }
        JSONObject o = new JSONObject(text);
        JSONObject result = o.optJSONObject("Result");
        if (result == null) result = o.optJSONObject("result");
        if (result == null) {
            throw new TokenExpiredException("trae: refresh response has no Result (token likely expired)");
        }
        String token = result.optString("Token", result.optString("token", ""));
        if (token.isEmpty()) {
            throw new TokenExpiredException("trae: refresh response has no accessToken");
        }
        c.accessToken = token;
        long expireAt = result.optLong("TokenExpireAt", result.optLong("tokenExpireAt", 0));
        if (expireAt > 0 && expireAt < 1_000_000_000_000L) expireAt *= 1000L;
        if (expireAt <= 0) {
            long duration = result.optLong("TokenExpireDuration", result.optLong("tokenExpireDuration", 0));
            if (duration > 0) expireAt = System.currentTimeMillis() + duration * 1000L;
        }
        if (expireAt > 0) c.expireTimeMs = expireAt;
        String newRefresh = result.optString("RefreshToken", result.optString("refreshToken", ""));
        if (!newRefresh.isEmpty()) c.refreshToken = newRefresh;
        saveCredential(c);
        return c;
    }

    /** 保证 access_token 新鲜（过期即续期一次）。 */
    public Credential ensureFresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException("trae: not logged in");
        if (isExpired(c)) c = refresh(c);
        return c;
    }

    // ── 请求头（对齐 traeSOLOHeaders）──

    public Map<String, String> soloHeaders(Credential c, boolean stream) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", stream ? "text/event-stream" : "application/json");
        h.put("User-Agent", USER_AGENT);
        h.put("Authorization", "Cloud-IDE-JWT " + c.accessToken);
        h.put("X-Cloudide-Token", c.accessToken);
        h.put("X-Ide-Token", c.accessToken);
        h.put("X-Uid", c.uid);
        h.put("X-App-Id", APP_ID);
        h.put("X-App-Version", "default");
        h.put("X-Ide-Version", IDE_VERSION);
        h.put("X-Ide-Version-Code", IDE_VERSION_CODE);
        h.put("X-App-Version-Code", IDE_VERSION_CODE);
        h.put("X-Ide-Version-Type", "stable");
        h.put("X-Device-Type", "macos");
        h.put("X-OS-Version", OS_VERSION);
        h.put("X-Device-Brand", DEVICE_BRAND);
        h.put("Request-Traffic-Type", "prod");
        if (!c.machineId.isEmpty()) h.put("X-Machine-Id", c.machineId);
        if (!c.deviceId.isEmpty()) h.put("X-Device-Id", c.deviceId);
        return h;
    }

    // ── 模型目录（batch_get_detail_param，22 通道一次拉全）──

    public static final String[] ALL_FUNCTIONS = {
            "ui_builder_v2", "solo_coder", "chat_v3", "solo_builder",
            "builder_v3", "builder", "chat", "inline_chat", "git_ai",
            "custom_agent_generation", "utils", "code_reviewer",
            "code_review_summary", "solo_agent", "solo_agent_remote",
            "solo_work_remote", "solo_agent_lite", "solo_work_lite",
            "solo_design_lite", "solo_design_remote", "multimodal",
            "system_diagnosis",
    };

    /** 返回标准 OpenAI models JSON（id = config_name，owned_by=trae，元数据带 channel）。 */
    public JSONObject listModels() throws Exception {
        Credential c = ensureFresh();
        JSONObject body = new JSONObject();
        JSONArray fns = new JSONArray();
        for (String f : ALL_FUNCTIONS) fns.put(f);
        body.put("functions", fns);
        body.put("agent_type", "");
        body.put("current_config_info", new JSONObject()
                .put("config_name", "").put("is_custom_model", false));
        body.put("mode_type", 0);
        body.put("access_type", 0);
        body.put("ab_force_vids", "");
        body.put("ab_autotest_advanced_mode", 0);
        body.put("show_custom_model", true);

        HttpFetcher.Response resp = HttpFetcher.fetch("POST", AGENT_HOST + BATCH_MODELS_PATH,
                soloHeaders(c, false), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                HTTP_TIMEOUT_MS);
        String text = resp.readText();
        if (resp.status != 200) {
            throw new IllegalStateException("trae: models HTTP " + resp.status + " " + shortText(text));
        }

        synchronized (channelByModel) { channelByModel.clear(); }
        JSONArray outData = new JSONArray();
        JSONObject root = new JSONObject(text);
        JSONArray configs = root.optJSONArray("function_configs");
        if (configs != null) {
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (int i = 0; i < configs.length(); i++) {
                JSONObject fc = configs.optJSONObject(i);
                if (fc == null) continue;
                String fn = fc.optString("function", "");
                JSONArray list = fc.optJSONArray("config_info_list");
                if (list == null) continue;
                for (int j = 0; j < list.length(); j++) {
                    JSONObject e = list.optJSONObject(j);
                    if (e == null) continue;
                    String id = e.optString("config_name", "");
                    if (id.isEmpty() || !seen.add(id)) continue;
                    // is_custom_model=true 仅可见不可调用（流内 4001）；isHidden 隐藏
                    if (e.optBoolean("is_custom_model", false)) continue;
                    if (e.optBoolean("isHidden", e.optBoolean("is_hidden", false))) continue;
                    String usage = e.optString("usage", "chat_completion");
                    if (!"chat_completion".equals(usage)) continue;
                    synchronized (channelByModel) {
                        if (!fn.isEmpty()) channelByModel.put(id, fn);
                    }
                    JSONObject nm = new JSONObject();
                    nm.put("id", id);
                    nm.put("object", "model");
                    nm.put("owned_by", "trae");
                    String display = e.optJSONObject("display_config") == null
                            ? "" : e.optJSONObject("display_config").optString("display_name", "");
                    if (display.isEmpty()) display = id;
                    // 计费倍率在 display_contact_config 里，且该字段是**字符串**
                    // （内容又是一段 JSON），必须二次 parse —— 见 ModelRate.fromTraeEntry。
                    // 拼进 name 而非另设字段：墨听模型条目只有 displayName 一个展示位。
                    String rate = ModelRate.fromTraeEntry(e);
                    String shown = ModelRate.displayName(display, rate);
                    nm.put("name", shown == null ? display : shown);
                    outData.put(nm);
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", outData);
        return result;
    }

    private String channelFor(String model) {
        synchronized (channelByModel) {
            String fn = channelByModel.get(model);
            if (fn != null && !fn.isEmpty()) return fn;
        }
        return DEFAULT_FUNCTION;
    }

    // ── 对话（SOLO body 转换 + 自定义 SSE）──

    /** OpenAI 请求体 → SOLO 请求体（对齐 transformToSOLOBody）。 */
    public JSONObject toSoloBody(JSONObject openaiBody, String model) throws org.json.JSONException {
        JSONObject body = new JSONObject(openaiBody.toString());
        body.put("stream", true);
        body.put("function", channelFor(model));
        JSONArray msgs = body.optJSONArray("messages");
        if (msgs != null) {
            JSONArray converted = new JSONArray();
            for (int i = 0; i < msgs.length(); i++) {
                JSONObject m = msgs.optJSONObject(i);
                if (m == null) { converted.put(msgs.get(i)); continue; }
                JSONObject nm = new JSONObject(m.toString());
                // assistant tool_calls: function → function_call（墨听不发 tools，做兼容即可）
                if ("assistant".equals(nm.optString("role"))) {
                    JSONArray tcs = nm.optJSONArray("tool_calls");
                    if (tcs != null) {
                        for (int k = 0; k < tcs.length(); k++) {
                            JSONObject tc = tcs.optJSONObject(k);
                            if (tc != null && tc.has("function")) {
                                tc.put("function_call", tc.get("function"));
                                tc.remove("function");
                            }
                        }
                    }
                }
                Object content = nm.has("content") ? nm.get("content") : null;
                if (content instanceof String) {
                    JSONArray arr = new JSONArray();
                    arr.put(new JSONObject().put("type", "text").put("text", (String) content));
                    nm.put("content", arr);
                }
                converted.put(nm);
            }
            body.put("messages", converted);
        }
        body.put("config_name", model);
        body.put("model", model);
        // 墨听不会发 tool_choice/tools，保持缺省
        return body;
    }

    public HttpFetcher.Response chatRaw(Credential c, JSONObject soloBody) throws Exception {
        return HttpFetcher.fetch("POST", AGENT_HOST + CHAT_PATH,
                soloHeaders(c, true), soloBody.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                600_000);
    }

    private static String shortText(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    /** 终态：需要重新登录。 */
    public static class TokenExpiredException extends IllegalStateException {
        public TokenExpiredException(String msg) { super(msg); }
    }

    // ── 浏览器登录（console 授权页 + 回调到本机网关）──

    /** 取或生成设备身份（machine_id 32hex / device_id 16 位数字），持久化避免每次换指纹触发风控。 */
    public synchronized Credential deviceIdentity() throws Exception {
        String raw = store.get(DEVICE_REF);
        if (raw != null && !raw.isEmpty()) {
            try { return Credential.fromJson(new JSONObject(raw)); } catch (Exception ignored) { }
        }
        Credential c = new Credential();
        StringBuilder m = new StringBuilder();
        java.security.SecureRandom r = new java.security.SecureRandom();
        for (int i = 0; i < 32; i++) m.append("0123456789abcdef".charAt(r.nextInt(16)));
        c.machineId = m.toString();
        StringBuilder d = new StringBuilder();
        for (int i = 0; i < 16; i++) d.append((char) ('0' + r.nextInt(10)));
        c.deviceId = d.toString();
        store.set(DEVICE_REF, c.toJson().toString());
        return c;
    }

    /** 构造 console 授权页 URL，回调指向本机网关（手机浏览器与网关同机，127.0.0.1 可达）。 */
    public String buildLoginUrl(Credential device, String callbackUrl) throws Exception {
        StringBuilder u = new StringBuilder(CONSOLE_HOST).append("/authorization?");
        u.append("login_version=1");
        u.append("&auth_from=solo");
        u.append("&login_channel=native_ide");
        u.append("&plugin_version=").append(enc(PLUGIN_VERSION));
        u.append("&auth_type=local");
        u.append("&client_id=").append(enc(CLIENT_ID));
        u.append("&redirect=0");
        u.append("&login_trace_id=").append(enc(machineTraceId(device.machineId, device.deviceId)));
        u.append("&auth_callback_url=").append(enc(callbackUrl));
        u.append("&machine_id=").append(enc(device.machineId));
        u.append("&device_id=").append(enc(device.deviceId));
        u.append("&x_device_id=").append(enc(device.deviceId));
        u.append("&x_machine_id=").append(enc(device.machineId));
        u.append("&x_device_brand=PC");
        u.append("&x_device_type=PC");
        u.append("&x_os_version=1.0");
        u.append("&x_app_version=").append(enc(IDE_VERSION));
        u.append("&x_app_type=stable");
        return u.toString();
    }

    /** machineTraceId：sha256(machine_id + deviceId)[:32]（hex）。 */
    private static String machineTraceId(String machineId, String deviceId) {
        try {
            String in = machineId + deviceId;
            byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(in.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.substring(0, 32);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 处理授权回调 query（userInfo=URL 编码 JSON，refreshToken 或 userJwt.RefreshToken）。
     * 用 refreshToken 走 ExchangeToken 换正式凭据并落盘，返回 displayName。
     */
    public synchronized String handleLoginCallback(String rawQuery) throws Exception {
        JSONObject q = parseQuery(rawQuery);
        Credential device = deviceIdentity();
        Credential c = new Credential();
        c.machineId = device.machineId;
        c.deviceId = device.deviceId;
        String refreshToken = q.optString("refreshToken", "");
        String userInfo = q.optString("userInfo", "");
        String userJwt = q.optString("userJwt", "");
        if (!userInfo.isEmpty()) {
            try {
                JSONObject ui = new JSONObject(userInfo);
                c.uid = ui.optString("UserID", "");
                c.nickname = ui.optString("ScreenName", "");
            } catch (Exception ignored) { }
        }
        if (refreshToken.isEmpty() && !userJwt.isEmpty()) {
            try {
                JSONObject jw = new JSONObject(userJwt);
                refreshToken = jw.optString("RefreshToken", "");
            } catch (Exception ignored) { }
        }
        if (refreshToken.isEmpty()) throw new IllegalStateException("trae: callback has no refreshToken");
        c.refreshToken = refreshToken;
        refresh(c); // ExchangeToken → access token + 轮换后的 refresh token + 过期时间
        saveCredential(c);
        return c.nickname;
    }

    /** 极简 query 解析（值为 URL 编码，可含 JSON）。 */
    private static JSONObject parseQuery(String rawQuery) throws org.json.JSONException {
        JSONObject out = new JSONObject();
        if (rawQuery == null) return out;
        if (rawQuery.startsWith("/")) rawQuery = rawQuery.substring(rawQuery.indexOf('?') + 1);
        for (String kv : rawQuery.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = urlDecode(kv.substring(0, eq));
            String v = urlDecode(kv.substring(eq + 1));
            out.put(k, v);
        }
        return out;
    }

    private static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException e) { return s; }
    }
}
