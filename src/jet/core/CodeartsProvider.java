package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPrivateKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * CodeArts（华为云）适配器。
 *
 * <h3>与其余六家最大的不同：请求要签名，凭据要自己换</h3>
 * 它不走 Bearer token，而是华为云的 <b>SDK-HMAC-SHA256</b> 请求签名：每个请求
 * 用 AK/SK/SecurityToken 现算 Authorization 头，且 {@code maas_type: benefit}
 * 这类头一旦参与签名就必须原样随请求发出。凭据本身由 DPoP（ES256 JWS）+ PKCE
 * 的 OAuth 流程签发，有效期约 8 小时，靠 refresh_token 静默续期。
 *
 * <h3>登录走浏览器 OAuth（PKCE + DPoP），不是设备码</h3>
 * 与 Cline/MiniMax 的设备码不同：这里要起一个 <b>本地回调服务器</b>（端口须
 * ≥ 10000，portal 会拒绝低端口），把 {@code https://codearts.huaweicloud.com/portal/authorize}
 * 交给浏览器。回调可能收到 {@code code}（新式 OAuth）或 {@code secret}
 * （portal 回退旧 ticket 流程），两条路都必须支持 —— 只做一条会在
 * portal 判定"OAuth 不可用"时彻底登不上。
 *
 * <h3>三个必须懂否则必错的地方</h3>
 * <ol>
 *   <li><b>benefit 模型必须带 {@code maas_type: benefit}</b>，且该头参与签名。
 *       不带 → {@code InferHub.002002009.404 The model is not registered}；
 *       而普通模型带上反而 {@code unsupported model}。判定集合来自
 *       {@code opengw gateway/config}（实测返回 4 条），静态兜底见
 *       {@link #BENEFIT_FALLBACK}。</li>
 *   <li><b>额度耗尽与排队限流是两件事</b>，绝不能合并。排队（{@code TM.00001041}
 *       / {@code 81111} / 独立的 {@code 429}）等一会儿就通，额度耗尽
 *       （{@code InferHub.4291.200} {@code insufficient quota}）等多久都没用。
 *       早期把 {@code 429} 当裸子串匹配，于是额度码 {@code 4291} 命中它
 *       → 被当成排队 → 每 10 秒重试、上限 30 分钟的<b>静默</b>循环，
 *      界面完全无输出。故 {@code 429} 必须锚定为独立数字。</li>
 *   <li><b>模型 id 的日期后缀必须去掉</b>：gateway/config 下发
 *       {@code deepseek-v4-flash-0731}，但 chat 端点只认
 *       {@code deepseek-v4-flash}。同时"该 id 是否为 benefit"要以<b>去后缀后</b>
 *       的 id 记录 —— 两者在后端是不同模型、benefit 属性相反。</li>
 * </ol>
 *
 * <h3>SSE 的两个超时不是随便定的</h3>
 * 华为 APIG 网关对 SSE 连接有 ~60 秒无数据即断开的策略，而 deepseek-v4 这类
 * 推理模型在长思考/大工具参数时可能静默数十秒。对齐 CodeArts Agent IDE 的
 * 实测值拆成两个窗口：首个 token 300 秒、chunk 间隔 600 秒（收到即重置）。
 * 用单一 55 秒会把这类"思考型长生成"反复掐断。
 */
public final class CodeartsProvider {

    public static final String ID = "codearts";
    public static final String DISPLAY_NAME = "CodeArts";

    /** 对话端点（OpenAI 兼容形状）。 */
    public static final String CHAT_API_BASE = "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2";
    public static final String CHAT_PATH = "/chat/completions";
    /** 并发排队状态端点。 */
    public static final String QUEUE_STATUS_BASE = "https://snap-access.cn-north-4.myhuaweicloud.com/api/v1/queue/status";
    /** benefit（免费额度）模型清单 —— 决定是否带 maas_type 头。 */
    public static final String OPENGW_GATEWAY_CONFIG_URL = "https://opengw.developer.huaweicloud.com/api/v1/gateway/config";
    /** 常规模型清单。 */
    public static final String SNAP_MODEL_BUILTIN_URL = "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin";
    /** 旧 ticket 流程的凭据端点。 */
    public static final String CREDENTIAL_ENDPOINT = "https://snap-access.cn-north-4.myhuaweicloud.com/snap-manager/v1/login/ticket";
    /** 华为 STS，OAuth 换取 / refresh 都在这里。 */
    public static final String STS_TOKEN_ENDPOINT = "https://sts.cn-north-4.myhuaweicloud.com/v1/oauth2/tokens";
    /** portal 授权端点。 */
    public static final String PORTAL_AUTHORIZE_BASE = "https://codearts.huaweicloud.com/portal/authorize";
    /** portal 登录结果页（授权完成后浏览器被重定向到这里）。 */
    public static final String PORTAL_LOGIN_BASE = "https://codearts.huaweicloud.com/portal/login";

    /** OAuth client_id（即 URI scheme）。 */
    public static final String CLIENT_ID = "codearts-agent";
    /** 本地回调路径。 */
    public static final String REDIRECT_PATH = "/oauth/callback";
    /** portal 期望的插件名（逆向常量，勿改）。 */
    public static final String LOGIN_PLUGIN_NAME = "snap_AIIDE";
    /** portal 期望的插件版本（逆向常量，须是真实扩展版本，勿用本项目版本）。 */
    public static final String LOGIN_PLUGIN_VERSION = "5.2.0";
    /** 回调端口下限：portal 拒绝 < 10000 的端口。 */
    public static final int MIN_CALLBACK_PORT = 10_000;

    public static final String CREDENTIAL_REF = "JETHUB_CODEARTS_TOKEN";
    public static final int HTTP_TIMEOUT_MS = 60_000;
    public static final int CHAT_TIMEOUT_MS = 600_000;
    /** 首个 token 等待窗口（对齐 IDE 的 firstTokenTimeout）。 */
    public static final int FIRST_TOKEN_TIMEOUT_MS = 300_000;

    /**
     * benefit 模型兜底集合。
     *
     * <p>远端 gateway/config 是权威来源（实测返回 4 条：{@code deepseek-v4-flash-0731}
     * / {@code glm-5.3-flash} / {@code deepseek-v4-pro-0813} / {@code deepseek-v4.1-flash}），
     * 但首次启动、未登录或拉取失败时拿不到。注意 {@code deepseek-v4-flash} 与
     * {@code deepseek-v4-pro}（<b>无</b>日期后缀）不在其中 —— 后端另有注册，
     * 带上 maas_type 会报 unsupported model。
     */
    public static final String[] BENEFIT_FALLBACK = {"glm-5.3-flash", "deepseek-v4.1-flash"};

    /** 无凭据时的静态模型表（远端可用时以远端为准）。 */
    public static final String[] FALLBACK_MODELS = {
            "GLM-5.2", "GLM-5.1", "GLM-5",
            "glm-5.3-flash",
            "openpangu-2.0-flash", "openpangu-2.0-pro",
            "deepseek-v4-flash", "deepseek-v4-pro",
            "deepseek-v4.1-flash",
    };

    /** 上下文窗口（对得上 IDE 下发的模型卡；未列出的留空让后端裁剪）。 */
    private static final Map<String, Integer> CONTEXT_WINDOWS = new LinkedHashMap<>();
    static {
        CONTEXT_WINDOWS.put("GLM-5.2", 202_752);
        CONTEXT_WINDOWS.put("glm-5.3-flash", 1_048_576);
        CONTEXT_WINDOWS.put("deepseek-v4-flash", 1_048_576);
        CONTEXT_WINDOWS.put("deepseek-v4-pro", 1_048_576);
        CONTEXT_WINDOWS.put("deepseek-v4.1-flash", 1_000_000);
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] B62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();

    /** benefit 模型集合（内存缓存；远端拉到后覆盖兜底）。 */
    private static volatile LinkedHashSet<String> benefitCache = new LinkedHashSet<>();
    static { for (String m : BENEFIT_FALLBACK) benefitCache.add(m); }

    private final CredentialStore store;

    public CodeartsProvider(CredentialStore store) { this.store = store; }

    // ══════════════════════════════════════════════════════════════
    //  凭据
    // ══════════════════════════════════════════════════════════════

    /**
     * 一份 CodeArts 凭据。
     *
     * <p>与其余家不同：没有 access_token，而是 AK/SK/SecurityToken 三件套；
     * 续期需要 {@code refresh_token} + {@code code_verifier} + DPoP 私钥
     * 三样一起才能换 —— 少任何一样都只能重新登录。
     */
    public static final class Credential {
        public String accessKeyId = "";
        public String secretAccessKey = "";
        public String securityToken = "";
        /** ISO8601（如 2026-10-17T01:32:05.126Z）。 */
        public String expiresAt = "";
        public String refreshToken = "";
        public String codeVerifier = "";
        /** DPoP ES256 私钥 JWK；续期时签发 dpop+jwt。 */
        public JSONObject dpopPrivateKeyJwk;
        public String domainId = "";
        public String userId = "";
        public String userName = "";
        public String nickname = "";

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("access_key_id", accessKeyId);
            o.put("secret_access_key", secretAccessKey);
            o.put("security_token", securityToken);
            o.put("expires_at", expiresAt);
            if (notEmpty(refreshToken)) o.put("refresh_token", refreshToken);
            if (notEmpty(codeVerifier)) o.put("code_verifier", codeVerifier);
            if (dpopPrivateKeyJwk != null) o.put("dpop_private_key_jwk", dpopPrivateKeyJwk);
            if (notEmpty(domainId)) o.put("domain_id", domainId);
            if (notEmpty(userId)) o.put("user_id", userId);
            if (notEmpty(userName)) o.put("user_name", userName);
            if (notEmpty(nickname)) o.put("nickname", nickname);
            return o;
        }

        public static Credential fromJson(JSONObject o) {
            Credential c = new Credential();
            c.accessKeyId = o.optString("access_key_id", "");
            c.secretAccessKey = o.optString("secret_access_key", "");
            c.securityToken = o.optString("security_token", "");
            c.expiresAt = o.optString("expires_at", "");
            c.refreshToken = o.optString("refresh_token", "");
            c.codeVerifier = o.optString("code_verifier", "");
            c.dpopPrivateKeyJwk = o.optJSONObject("dpop_private_key_jwk");
            c.domainId = o.optString("domain_id", "");
            c.userId = o.optString("user_id", "");
            c.userName = o.optString("user_name", "");
            c.nickname = o.optString("nickname", "");
            return c;
        }

        /** 能否静默续期（三样齐备才行）。 */
        public boolean isRefreshable() {
            return notEmpty(refreshToken) && notEmpty(codeVerifier) && dpopPrivateKeyJwk != null;
        }
    }

    public Credential loadCredential() {
        String raw = store.get(CREDENTIAL_REF);
        if (raw == null || raw.isEmpty()) return null;
        try { return Credential.fromJson(new JSONObject(raw)); } catch (Exception e) { return null; }
    }

    public void saveCredential(Credential c) throws Exception {
        store.set(CREDENTIAL_REF, c.toJson().toString());
    }

    /** 是否已过期（留 5 分钟余量，避免卡在边界上）。 */
    public boolean isExpired(Credential c) {
        if (c == null) return true;
        long ms = parseIsoMs(c.expiresAt);
        if (ms <= 0) return false;   // 解析不出就不预判，让请求自己失败并触发续期
        return System.currentTimeMillis() >= ms - 300_000L;
    }

    /**
     * 解析 STS 的 ISO8601 到期时间。
     *
     * <p>故意<b>不用</b> {@code java.time}：Android 低版本上 {@code Instant.parse}
     * 不总是可用，而本项目要同时跑在 host JVM 与 APK 里。故只认 STS 实际下发的
     * 两种形态，其余一律返回 0（表示"不知道"，交给请求失败时的续期逻辑兜底）。
     */
    static long parseIsoMs(String iso) {
        if (iso == null || iso.isEmpty()) return 0L;
        String[] patterns = {
                "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                "yyyy-MM-dd'T'HH:mm:ss'Z'",
                "yyyyMMdd'T'HHmmss'Z'",
        };
        for (String p : patterns) {
            try {
                java.text.SimpleDateFormat f = new java.text.SimpleDateFormat(p, java.util.Locale.US);
                f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
                f.setLenient(false);
                return f.parse(iso).getTime();
            } catch (Exception ignored) { }
        }
        return 0L;
    }

    /**
     * 未过期直接返回；过期则用 refresh_token 续期。
     *
     * <p>续期失败时<b>不回退成 null</b> —— 保留旧凭据往上抛，让调用方看到
     * 真实原因（refresh_token 失效需要重新登录，而不是"未登录"）。
     */
    public Credential ensureFresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException("未登录 CodeArts");
        if (!isExpired(c)) return c;
        if (!c.isRefreshable()) {
            throw new IllegalStateException("CodeArts 凭据已过期且无法静默续期，请重新登录");
        }
        return refresh(c);
    }

    /** 用 refresh_token + PKCE + DPoP 换一份新凭据并落库。 */
    public synchronized Credential refresh(Credential c) throws Exception {
        if (!c.isRefreshable()) throw new IllegalStateException("凭据缺少 refresh_token / code_verifier / DPoP 私钥");
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", CLIENT_ID);
        form.put("code_verifier", c.codeVerifier);
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", c.refreshToken);
        JSONObject token = requestToken(form, c.dpopPrivateKeyJwk);
        Credential next = credentialFromToken(token, c.codeVerifier, c.dpopPrivateKeyJwk);
        // 账号信息沿用旧的（STS 不回传 domain/user）
        next.domainId = c.domainId;
        next.userId = c.userId;
        next.userName = c.userName;
        next.nickname = c.nickname;
        saveCredential(next);
        return next;
    }

    // ══════════════════════════════════════════════════════════════
    //  华为云 SDK-HMAC-SHA256 签名（sign.ts 的等价实现）
    // ══════════════════════════════════════════════════════════════

    static String sha256Hex(byte[] data) throws Exception {
        return hexLower(MessageDigest.getInstance("SHA-256").digest(data));
    }

    static String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return hexLower(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hexLower(byte[] b) {
        char[] hex = "0123456789abcdef".toCharArray();
        char[] out = new char[b.length * 2];
        for (int i = 0; i < b.length; i++) {
            out[i * 2] = hex[(b[i] >> 4) & 0xF];
            out[i * 2 + 1] = hex[b[i] & 0xF];
        }
        return new String(out);
    }

    /** ISO8601 基础格式：20261016T233107Z（UTC）。 */
    static String sdkDateNow() {
        java.text.SimpleDateFormat f =
                new java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return f.format(new java.util.Date());
    }

    /**
     * 签名一个华为请求，返回<b>需随请求发送的全部头</b>。
     *
     * <p>{@code extraHeaders}（如 {@code maas_type: benefit}）会一并参与 canonical
     * 计算并出现在 SignedHeaders 里 —— 因此调用方<b>必须</b>把它发出去，否则
     * 服务端验签失败。{@code host} 由 HTTP 客户端自动设置，故返回值里可含它，
     * 但发送时应跳过（Java 的 HttpURLConnection 不允许手工设 Host）。
     *
     * @param method       HTTP 方法
     * @param urlStr       完整 URL
     * @param body         请求体（GET 传空数组）
     * @param extraHeaders 额外参与签名的头
     */
    public static Map<String, String> signRequestHuawei(
            String ak, String sk, String securityToken,
            String method, String urlStr, byte[] body,
            Map<String, String> extraHeaders) throws Exception {

        java.net.URL u = new java.net.URL(urlStr);
        String uri = u.getPath();
        if (!uri.endsWith("/")) uri += "/";
        String query = u.getQuery() == null ? "" : u.getQuery();
        String dateStamp = sdkDateNow();
        String payloadHash = sha256Hex(body == null ? new byte[0] : body);

        Map<String, String> headers = new HashMap<>();
        headers.put("host", u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : ""));
        headers.put("x-sdk-date", dateStamp);
        headers.put("x-sdk-content-sha256", payloadHash);
        headers.put("x-security-token", securityToken);
        if (extraHeaders != null) headers.putAll(extraHeaders);
        // GET 无请求体，故不带 content-type。
        if (!"GET".equalsIgnoreCase(method)) headers.put("content-type", "application/json");

        List<String> signedHeaders = new ArrayList<>(headers.keySet());
        java.util.Collections.sort(signedHeaders);
        StringBuilder headerLines = new StringBuilder();
        for (int i = 0; i < signedHeaders.size(); i++) {
            String k = signedHeaders.get(i);
            if (i > 0) headerLines.append('\n');
            headerLines.append(k).append(':').append(headers.get(k));
        }
        String canonicalRequest = method + "\n" + uri + "\n" + query + "\n"
                + headerLines + "\n\n" + String.join(";", signedHeaders) + "\n" + payloadHash;
        String canonicalHash = sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        String stringToSign = "SDK-HMAC-SHA256\n" + dateStamp + "\n" + canonicalHash;
        String signature = hmacSha256Hex(sk, stringToSign);

        headers.put("Authorization", "SDK-HMAC-SHA256 Access=" + ak
                + ",SignedHeaders=" + String.join(";", signedHeaders)
                + ",Signature=" + signature);
        return headers;
    }

    /** 用签名头发起一次请求（自动跳过 host）。 */
    public static HttpFetcher.Response signedFetch(
            Credential c, String method, String url, byte[] body,
            Map<String, String> extraSignedHeaders, Map<String, String> extraUnsignedHeaders,
            int timeoutMs) throws Exception {
        Map<String, String> h = signRequestHuawei(
                c.accessKeyId, c.secretAccessKey, c.securityToken, method, url, body, extraSignedHeaders);
        h.remove("host");
        if (extraUnsignedHeaders != null) h.putAll(extraUnsignedHeaders);
        return HttpFetcher.fetch(method, url, h, body, timeoutMs);
    }

    // ══════════════════════════════════════════════════════════════
    //  DPoP（ES256 JWS）
    // ══════════════════════════════════════════════════════════════

    private static ECParameterSpec p256() throws Exception {
        java.security.AlgorithmParameters ap = java.security.AlgorithmParameters.getInstance("EC");
        ap.init(new ECGenParameterSpec("secp256r1"));
        return ap.getParameterSpec(ECParameterSpec.class);
    }

    /**
     * 签发 dpop+jwt（{@code signDpopJws} 的等价实现）。
     *
     * <p>头部含<b>公钥</b> JWK（服务端据此验证绑定），载荷含 htm/htu/iat/jti。
     * 签名用 ES256，输出必须是 JOSE 的 <b>raw r||s</b>（各 32 字节）——
     * Java 的 SHA256withECDSA 出的是 DER，故此处手工拆解重排。
     */
    public static String signDpopJws(JSONObject privateJwk, String htm, String htu) throws Exception {
        byte[] d = b64urlDecode(privateJwk.optString("d", ""));
        ECPrivateKeySpec spec = new ECPrivateKeySpec(new java.math.BigInteger(1, d), p256());
        ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(spec);

        JSONObject pub = new JSONObject();
        pub.put("kty", "EC");
        pub.put("crv", "P-256");
        pub.put("x", privateJwk.optString("x", ""));
        pub.put("y", privateJwk.optString("y", ""));

        JSONObject protectedHeader = new JSONObject();
        protectedHeader.put("alg", "ES256");
        protectedHeader.put("typ", "dpop+jwt");
        protectedHeader.put("jwk", pub);

        JSONObject payload = new JSONObject();
        payload.put("htm", htm);
        payload.put("htu", htu);
        payload.put("iat", System.currentTimeMillis() / 1000L);
        byte[] jti = new byte[32];
        RANDOM.nextBytes(jti);
        payload.put("jti", hexLower(jti));

        String signingInput = b64url(protectedHeader.toString().getBytes(StandardCharsets.UTF_8))
                + "." + b64url(payload.toString().getBytes(StandardCharsets.UTF_8));

        Signature sig = Signature.getInstance("SHA256withECDSA");
        sig.initSign(key);
        sig.update(signingInput.getBytes(StandardCharsets.UTF_8));
        byte[] der = sig.sign();

        return signingInput + "." + b64url(derToRaw(der));
    }

    /** DER(SEQUENCE{INTEGER r, INTEGER s}) → 固定 64 字节 r||s。 */
    static byte[] derToRaw(byte[] der) {
        int idx = 0;
        if (der[idx++] != 0x30) throw new IllegalArgumentException("ECDSA 签名不是 SEQUENCE");
        int seqLen = der[idx++] & 0xFF;
        if ((seqLen & 0x80) != 0) idx += (seqLen & 0x7F);   // 长形式长度，跳过
        idx++;                                              // INTEGER 标记
        int rLen = der[idx++] & 0xFF;
        byte[] r = new byte[rLen];
        System.arraycopy(der, idx, r, 0, rLen);
        idx += rLen;
        idx++;                                              // INTEGER 标记
        int sLen = der[idx++] & 0xFF;
        byte[] s = new byte[sLen];
        System.arraycopy(der, idx, s, 0, sLen);

        byte[] out = new byte[64];
        copyTrimmed(r, out, 0);
        copyTrimmed(s, out, 32);
        return out;
    }

    /** 把大整数（可能带前导 0、也可能不足 32 字节）右对齐拷进 32 字节槽。 */
    private static void copyTrimmed(byte[] src, byte[] dst, int offset) {
        int start = 0;
        while (start < src.length - 1 && src[start] == 0) start++;
        int len = Math.min(src.length - start, 32);
        System.arraycopy(src, src.length - len, dst, offset + 32 - len, len);
    }

    static byte[] b64urlDecode(String s) {
        String v = s.replace('-', '+').replace('_', '/');
        while (v.length() % 4 != 0) v += "=";
        return Base64.getDecoder().decode(v);
    }

    static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** 生成 PKCE 配对：verifier 为 48 字节 base64url，challenge 为 S256。 */
    public static String[] generatePkce() throws Exception {
        byte[] v = new byte[48];
        RANDOM.nextBytes(v);
        String verifier = b64url(v);
        String challenge = b64url(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        return new String[]{verifier, challenge};
    }

    /** 生成 P-256 密钥对，返回 {@code [privateJwk, publicJwk]}（JWK 形状）。 */
    public static JSONObject[] generateDpopKeyPair() throws Exception {
        java.security.KeyPairGenerator kpg = java.security.KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        java.security.KeyPair kp = kpg.generateKeyPair();
        java.security.interfaces.ECPublicKey pub = (java.security.interfaces.ECPublicKey) kp.getPublic();
        ECPrivateKey priv = (ECPrivateKey) kp.getPrivate();

        String x = b64url(toFixed(pub.getW().getAffineX(), 32));
        String y = b64url(toFixed(pub.getW().getAffineY(), 32));
        String d = b64url(toFixed(priv.getS(), 32));

        JSONObject pubJwk = new JSONObject();
        pubJwk.put("kty", "EC").put("crv", "P-256").put("x", x).put("y", y);
        JSONObject privJwk = new JSONObject();
        privJwk.put("kty", "EC").put("crv", "P-256").put("x", x).put("y", y).put("d", d);
        return new JSONObject[]{privJwk, pubJwk};
    }

    private static byte[] toFixed(java.math.BigInteger v, int len) {
        byte[] raw = v.toByteArray();
        byte[] out = new byte[len];
        int start = 0;
        while (start < raw.length - 1 && raw[start] == 0) start++;
        int n = Math.min(raw.length - start, len);
        System.arraycopy(raw, raw.length - n, out, len - n, n);
        return out;
    }

    // ══════════════════════════════════════════════════════════════
    //  STS 换取（authorization_code / refresh_token）
    // ══════════════════════════════════════════════════════════════

    /**
     * 向 STS 发起一次带 DPoP 的 token 请求。
     *
     * <p>只有 {@code invalid_grant} 与 {@code ExpiredRefreshToken} 被视作
     * <b>终态</b>（refresh_token 真的没用了）。{@code InvalidDPoPHeader} 明确
     * <b>不</b>算 —— 它说的是"这次 proof 没通过"（时钟偏差、重放、网关抖动），
     * 与"凭据还能不能用"无关；把它当终态会把材料完好的账号一步标死。
     */
    public JSONObject requestToken(Map<String, String> form, JSONObject dpopPrivateJwk) throws Exception {
        String dpop = signDpopJws(dpopPrivateJwk, "POST", STS_TOKEN_ENDPOINT);
        StringBuilder body = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (body.length() > 0) body.append('&');
            body.append(urlEncode(e.getKey())).append('=').append(urlEncode(e.getValue()));
        }
        Map<String, String> h = new LinkedHashMap<>();
        h.put("DPoP", dpop);
        h.put("Content-Type", "application/x-www-form-urlencoded");
        HttpFetcher.Response resp = HttpFetcher.fetch("POST", STS_TOKEN_ENDPOINT, h,
                body.toString().getBytes(StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
        String text = resp.readText();

        JSONObject o;
        try { o = new JSONObject(text); } catch (Exception e) { o = null; }
        if (resp.status != 200 || o == null || o.optJSONObject("credentials") == null) {
            String code = o == null ? "" : o.optString("error_code", "");
            String err = o == null ? "" : o.optString("error", "");
            if ("invalid_grant".equals(err) || code.contains("ExpiredRefreshToken")) {
                throw new RefreshTokenExpiredException("CodeArts refresh_token 已失效，请重新登录");
            }
            throw new IllegalStateException("CodeArts token 换取失败：HTTP " + resp.status
                    + (text.isEmpty() ? "" : " " + snippet(text)));
        }
        return o;
    }

    /** refresh_token 已失效（终态，调度器应停止续期）。 */
    public static final class RefreshTokenExpiredException extends Exception {
        public RefreshTokenExpiredException(String m) { super(m); }
    }

    /** 把 STS 响应组装成持久化凭据。 */
    static Credential credentialFromToken(JSONObject token, String codeVerifier, JSONObject dpsJwk) throws Exception {
        JSONObject creds = token.optJSONObject("credentials");
        Credential c = new Credential();
        c.accessKeyId = creds.optString("access_key_id", "");
        c.secretAccessKey = creds.optString("secret_access_key", "");
        c.securityToken = creds.optString("security_token", "");
        c.expiresAt = creds.optString("expiration", "");
        c.refreshToken = token.optString("refresh_token", "");
        c.codeVerifier = codeVerifier;
        c.dpopPrivateKeyJwk = dpsJwk;
        if (c.accessKeyId.isEmpty() || c.securityToken.isEmpty()) {
            throw new IllegalStateException("CodeArts token 响应缺少凭据字段");
        }
        return c;
    }

    // ══════════════════════════════════════════════════════════════
    //  登录（浏览器 OAuth：PKCE + DPoP，起本地回调服务器）
    // ══════════════════════════════════════════════════════════════

    /** 一次进行中的登录。 */
    public static final class LoginSession {
        public String ticketId = "";
        public String loginUrl = "";
        public String pkceVerifier = "";
        public JSONObject dpopPrivateJwk;
        public ServerSocket server;
        public volatile String status = "pending";   // pending / done / error
        public volatile String message = "";
        public long expiresAtMs = System.currentTimeMillis() + 300_000L;
    }

    /**
     * 起回调服务器并返回要交给浏览器的登录 URL。
     *
     * <p>端口必须在 [10000, 65535]：portal 会拒绝更低的端口，而
     * {@code bind(0)} 拿到的随机端口常低于它，故这里自己挑并重试。
     */
    public LoginSession startLogin() throws Exception {
        String[] pkce = generatePkce();
        JSONObject[] kp = generateDpopKeyPair();
        String ticketId = randomHex(32);

        ServerSocket ss = bindCallbackPort();
        LoginSession s = new LoginSession();
        s.ticketId = ticketId;
        s.pkceVerifier = pkce[0];
        s.dpopPrivateJwk = kp[0];
        s.server = ss;
        int port = ss.getLocalPort();
        s.loginUrl = PORTAL_AUTHORIZE_BASE
                + "?theme=2&locale=zh-cn"
                + "&uri_scheme=" + CLIENT_ID
                + "&client_id=" + CLIENT_ID
                + "&port=" + port
                // 注意是 SHA-256 而不是 RFC 标准的 S256：portal 靠这个字面量
                // 识别"这是新式 OAuth"，写错会静默回退旧 ticket 流程。
                + "&code_challenge=" + urlEncode(pkce[1])
                + "&code_challenge_method=SHA-256"
                + "&ticket_id=" + urlEncode(ticketId)
                + "&plugin-name=" + LOGIN_PLUGIN_NAME
                + "&plugin-version=" + LOGIN_PLUGIN_VERSION;

        Thread t = new Thread(() -> acceptLoop(ss, s), "jethub-codearts-callback");
        t.setDaemon(true);
        t.start();
        return s;
    }

    private static ServerSocket bindCallbackPort() throws IOException {
        for (int i = 0; i < 40; i++) {
            int port = MIN_CALLBACK_PORT + RANDOM.nextInt(65_536 - MIN_CALLBACK_PORT);
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new java.net.InetSocketAddress("127.0.0.1", port));
                return ss;
            } catch (IOException ignored) { }
        }
        throw new IOException("找不到可用的回调端口（需 ≥ " + MIN_CALLBACK_PORT + "）");
    }

    private void acceptLoop(ServerSocket ss, LoginSession s) {
        while (s.status.equals("pending") && System.currentTimeMillis() < s.expiresAtMs) {
            try {
                ss.setSoTimeout(2000);
                Socket sock = ss.accept();
                handleCallback(sock, s);
            } catch (java.net.SocketTimeoutException e) {
                // 正常：继续等
            } catch (Exception e) {
                if (s.status.equals("pending")) { s.status = "error"; s.message = shortErr(e); }
            }
        }
        closeQuietly(ss);
    }

    private void handleCallback(Socket sock, LoginSession s) throws Exception {
        BufferedReader in = new BufferedReader(
                new InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
        String requestLine = in.readLine();
        if (requestLine == null) { sock.close(); return; }
        String[] parts = requestLine.split(" ");
        String method = parts.length > 0 ? parts[0] : "GET";
        String path = parts.length > 1 ? parts[1] : "/";

        int q = path.indexOf('?');
        String pathOnly = q >= 0 ? path.substring(0, q) : path;
        Map<String, String> params = parseQuery(q >= 0 ? path.substring(q + 1) : "");

        if (!pathOnly.startsWith(REDIRECT_PATH)) {
            writeResponse(sock, 404, "Not found", null);
            return;
        }
        if ("OPTIONS".equals(method)) { writeResponse(sock, 204, "", null); return; }

        // 旧流程回退：portal 判定 OAuth 不可用时以 secret 回调。
        String callbackSecret = params.get("secret");
        if (notEmpty(callbackSecret)) {
            writeResponse(sock, 307, "", portalLoginResultUrl(true));
            try {
                Credential c = pollForTicket(s.ticketId, callbackSecret);
                finishLogin(s, c);
            } catch (Exception e) {
                s.status = "error";
                s.message = shortErr(e);
            }
            return;
        }

        // 新式 OAuth：code 换 token。
        String code = params.get("code");
        if (notEmpty(code)) {
            try {
                Map<String, String> form = new LinkedHashMap<>();
                form.put("client_id", CLIENT_ID);
                form.put("code", code);
                form.put("code_verifier", s.pkceVerifier);
                form.put("grant_type", "authorization_code");
                form.put("redirect_uri", "http://127.0.0.1:" + s.server.getLocalPort() + REDIRECT_PATH);
                JSONObject token = requestToken(form, s.dpopPrivateJwk);
                Credential c = credentialFromToken(token, s.pkceVerifier, s.dpopPrivateJwk);
                writeResponse(sock, 307, "", portalLoginResultUrl(true));
                finishLogin(s, c);
            } catch (Exception e) {
                writeResponse(sock, 307, "", portalLoginResultUrl(false));
                s.status = "error";
                s.message = shortErr(e);
            }
            return;
        }
        writeResponse(sock, 400, "Missing authorization code or secret", null);
    }

    private void finishLogin(LoginSession s, Credential c) {
        try {
            saveCredential(c);
            s.status = "done";
        } catch (Exception e) {
            s.status = "error";
            s.message = shortErr(e);
        }
    }

    /** portal 登录结果页 URL（浏览器授权完成后跳这里）。 */
    static String portalLoginResultUrl(boolean succeeded) {
        return PORTAL_LOGIN_BASE + "?login_succeed=" + succeeded
                + "&uri_scheme=" + CLIENT_ID + "&locale=zh-cn";
    }

    /**
     * 轮询旧 ticket 端点直到拿到完整凭据。
     *
     * <p>响应有两种形状：{@code credential:{access,secret,securitytoken,...}}
     * 与 {@code result:{accessKeyId,secretAccessKey,securityToken,...}}，都要认。
     * 瞬时失败跳过而不当致命错误（portal 侧还没写就绪是常态）。
     */
    Credential pollForTicket(String ticketId, String secret) throws Exception {
        String url = CREDENTIAL_ENDPOINT + "?ticket_id=" + urlEncode(ticketId)
                + "&secret=" + urlEncode(secret);
        Map<String, String> h = new LinkedHashMap<>();
        h.put("Content-Type", "application/json;charset=UTF-8");
        h.put("plugin-name", LOGIN_PLUGIN_NAME);
        h.put("plugin-version", LOGIN_PLUGIN_VERSION);
        for (int i = 0; i < 120; i++) {
            if (i > 0) Thread.sleep(1000);
            try {
                HttpFetcher.Response r = HttpFetcher.get(url, h, 25_000);
                if (r.status != 200) { r.readText(); continue; }
                String text = r.readText();
                JSONObject o;
                try { o = new JSONObject(text); } catch (Exception e) { continue; }
                Credential c = parseTicketCredential(o);
                if (c != null) return c;
            } catch (Exception ignored) { }
        }
        throw new IllegalStateException("CodeArts 登录超时（ticket 未就绪）");
    }

    /** 认两种 ticket 响应形状。 */
    static Credential parseTicketCredential(JSONObject o) {
        Credential c = new Credential();
        JSONObject cred = o.optJSONObject("credential");
        if (cred != null) {
            String access = cred.optString("access", "");
            String st = cred.optString("securitytoken", cred.optString("securityToken", ""));
            if (!access.isEmpty() && !st.isEmpty()) {
                c.accessKeyId = access;
                c.secretAccessKey = cred.optString("secret", "");
                c.securityToken = st;
                c.expiresAt = cred.optString("expires_at", cred.optString("expiresAt", ""));
                c.domainId = o.optString("domain_id", "");
                c.userId = o.optString("user_id", "");
                c.userName = o.optString("user_name", "");
                return c;
            }
        }
        JSONObject res = o.optJSONObject("result");
        if (res != null) {
            String ak = res.optString("accessKeyId", "");
            String st = res.optString("securityToken", "");
            if (!ak.isEmpty() && !st.isEmpty()) {
                c.accessKeyId = ak;
                c.secretAccessKey = res.optString("secretAccessKey", "");
                c.securityToken = st;
                c.expiresAt = res.optString("expiration", res.optString("expiresAt", ""));
                return c;
            }
        }
        return null;
    }

    /** 关闭回调服务器（幂等）。 */
    public static void closeLogin(LoginSession s) {
        if (s != null) closeQuietly(s.server);
    }

    // ══════════════════════════════════════════════════════════════
    //  模型目录
    // ══════════════════════════════════════════════════════════════

    /** 去掉末尾日期版本后缀：{@code deepseek-v4-flash-0731} → {@code deepseek-v4-flash}。 */
    public static String normalizeModelId(String id) {
        if (id == null || id.length() <= 5) return id == null ? "" : id;
        String suffix = id.substring(id.length() - 5);
        if (suffix.startsWith("-") && suffix.substring(1).matches("\\d{4}")) {
            return id.substring(0, id.length() - 5);
        }
        return id;
    }

    /** 该模型是否为 benefit（调用时必须带 maas_type 头）。 */
    public static boolean isBenefitModel(String model) {
        if (model == null) return false;
        String m = normalizeModelId(model);
        LinkedHashSet<String> cache = benefitCache;
        for (String b : cache) if (b.equalsIgnoreCase(m) || b.equals(m)) return true;
        return false;
    }

    /** 列出模型：优先远端（顺带刷新 benefit 集合），失败回退静态表。 */
    public JSONObject listModels() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException("未登录 CodeArts");
        if (isExpired(c)) {
            try { c = refresh(c); } catch (RefreshTokenExpiredException e) { throw e; }
        }

        LinkedHashSet<String> ids = new LinkedHashSet<>();
        LinkedHashSet<String> benefit = new LinkedHashSet<>();

        // 1. gateway/config → benefit 模型（权威来源）
        try {
            HttpFetcher.Response r = signedFetch(c, "GET", OPENGW_GATEWAY_CONFIG_URL,
                    new byte[0], null, null, 10_000);
            if (r.status == 200) {
                JSONObject o = new JSONObject(r.readText());
                JSONArray arr = o.optJSONObject("result") == null ? null
                        : o.getJSONObject("result").optJSONArray("models");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m == null) continue;
                        String rawId = m.optString("model_id", "");
                        if (rawId.isEmpty()) continue;
                        String id = normalizeModelId(rawId);
                        if (isVisionModel(id)) continue;
                        ids.add(id);
                        // ⚠️ 只记录"去后缀后未变"的 id：带日期后缀的是另一个模型，
                        //    且我们根本不会发送那个 id，据它标记无后缀模型会反向出错。
                        if (id.equals(rawId)) benefit.add(id);
                    }
                }
            } else {
                r.readText();
            }
        } catch (Exception ignored) { }

        // 2. /v1/model/builtin → 常规模型
        try {
            Map<String, String> unsigned = new LinkedHashMap<>();
            unsigned.put("Content-Type", "application/json");
            unsigned.put("Agent-Type", "PromptCenter");
            unsigned.put("X-Language", "zh-cn");
            HttpFetcher.Response r = signedFetch(c, "GET", SNAP_MODEL_BUILTIN_URL,
                    new byte[0], null, unsigned, 10_000);
            if (r.status == 200) {
                JSONObject o = new JSONObject(r.readText());
                JSONArray arr = o.optJSONArray("builtinModels");
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i);
                        if (m == null) continue;
                        String rawId = m.optString("model_id", "");
                        if (rawId.isEmpty()) continue;
                        String id = normalizeModelId(rawId);
                        if (isVisionModel(id)) continue;
                        ids.add(id);
                    }
                }
            } else {
                r.readText();
            }
        } catch (Exception ignored) { }

        // 远端拿到的 benefit 集合覆盖兜底（非空才覆盖，避免把可用缓存清空）
        if (!benefit.isEmpty()) benefitCache = benefit;

        if (ids.isEmpty()) {
            for (String m : FALLBACK_MODELS) ids.add(m);
        }

        JSONArray data = new JSONArray();
        for (String id : ids) {
            JSONObject e = new JSONObject();
            e.put("id", id);
            e.put("object", "model");
            e.put("owned_by", ID);
            Integer ctx = CONTEXT_WINDOWS.get(id);
            if (ctx != null) e.put("context_window", ctx);
            e.put("benefit", isBenefitModel(id));
            data.put(e);
        }
        JSONObject out = new JSONObject();
        out.put("object", "list");
        out.put("data", data);
        return out;
    }

    /** 视觉（VL）多模态模型：上下文小、不支持工具调用，不作为 agent 主模型暴露。 */
    public static boolean isVisionModel(String id) {
        return id.contains("-VL-") || id.endsWith("-VL");
    }

    // ══════════════════════════════════════════════════════════════
    //  对话请求
    // ══════════════════════════════════════════════════════════════

    /**
     * 请求体加工：补上 CodeArts 认识而标准 OpenAI 没有的字段。
     *
     * <ul>
     *   <li>{@code prompt_cache_key} 让服务端启用前缀缓存并在 usage 里回
     *       {@code cached_tokens}；缺了它缓存命中恒为 0。</li>
     *   <li>{@code tool_stream: true} 让超大工具参数（大文件写入）分段传输，
     *       避免单个 SSE 事件过大把连接掐断。</li>
     *   <li>{@code max_tokens} 默认 65536：沿用后端默认值会让长参数 JSON
     *       中途被截断成非法 JSON。实测 131072 反而触发空流。</li>
     *   <li>{@code thinking:{type:'disabled'}} 只在显式关思考时发；"开启"档
     *       与不传等价（服务端默认就开着），发了反而多此一举。</li>
     * </ul>
     */
    public static JSONObject buildPayload(JSONObject req, String model, String sessionId) throws Exception {
        JSONObject body = new JSONObject(req.toString());
        body.put("model", model);
        body.put("stream", true);
        if (!body.has("prompt_cache_key") && sessionId != null && !sessionId.isEmpty()) {
            body.put("prompt_cache_key", sessionId);
        }
        body.put("tool_stream", true);
        if (!body.has("max_tokens")) body.put("max_tokens", 65536);
        JSONArray include = new JSONArray();
        include.put("reasoning.encrypted_content");
        if (!body.has("include")) body.put("include", include);
        if (!body.has("reasoning_summary")) body.put("reasoning_summary", "auto");
        String effort = body.optString("reasoning_effort", "");
        if ("off".equals(effort) || "none".equals(effort)) {
            JSONObject thinking = new JSONObject();
            thinking.put("type", "disabled");
            body.put("thinking", thinking);
        }
        body.remove("reasoning_effort");
        return body;
    }

    /**
     * 发一次对话请求。自动处理：benefit 头、签名、鉴权失败续期一次。
     *
     * @return HTTP 响应（调用方负责读流）
     */
    public HttpFetcher.Response chatRaw(JSONObject req, String model, String sessionId) throws Exception {
        return chatRaw(req, model, sessionId, true);
    }

    /**
     * 发一次对话请求。自动处理：benefit 头、签名、鉴权失败续期一次。
     *
     * @param benefitHeader 是否带 {@code maas_type: benefit}。账号里没有 benefit 包时
     *        后端会以 HTTP 200 + SSE {@code InferHub.4004.200} 拒绝，此时调用方
     *        传 {@code false} 重试一次即可走普通通道。
     * @return HTTP 响应（调用方负责读流）
     */
    public HttpFetcher.Response chatRaw(
            JSONObject req, String model, String sessionId, boolean benefitHeader) throws Exception {
        Credential c = ensureFresh();
        String url = CHAT_API_BASE + CHAT_PATH;
        String sid = (sessionId == null || sessionId.isEmpty()) ? randomHex(16) : sessionId;
        byte[] body = buildPayload(req, model, sid).toString().getBytes(StandardCharsets.UTF_8);

        HttpFetcher.Response resp = doChat(c, url, body, model, sid, benefitHeader);
        if (isAuthError(resp.status)) {
            // SecurityToken 可能被后端提前吊销或本地时钟有偏差：静默续期一次再试。
            resp.readText();
            if (c.isRefreshable()) {
                c = refresh(c);
                resp = doChat(c, url, body, model, sid, benefitHeader);
            }
        }
        return resp;
    }

    /**
     * 发一次已签名对话请求。
     *
     * <p>三个非签名头是 IDE 的形态要求：{@code Chat-Id}（每次请求一次的随机
     * id）、{@code Session-Id}（与 {@code prompt_cache_key} 同一个值，缓存命中
     * 靠它）、{@code lang: en}。它们**不参与签名**（放进签名会让 canonical
     * request 与服务端不一致而验签失败），故走 extraUnsignedHeaders。
     */
    private HttpFetcher.Response doChat(
            Credential c, String url, byte[] body, String model, String sessionId,
            boolean benefitHeader) throws Exception {
        Map<String, String> extra = null;
        if (benefitHeader && isBenefitModel(model)) {
            // ⚠️ 该头参与 SDK-HMAC-SHA256 签名，缺了后端会按非 benefit 通道处理该模型，
            //    返回 InferHub.002002009.404 "model is not registered" 或直接报余额不足。
            //    判定必须是动态的（不能硬编码某个模型名）—— 账号可见的 benefit 集合
            //    由 opengw gateway/config 下发。
            extra = new LinkedHashMap<>();
            extra.put("maas_type", "benefit");
        }
        Map<String, String> unsigned = new LinkedHashMap<>();
        // ⚠️ 不加 Content-Type：签名流程在非 GET 时已放入 `content-type`，
        // 再以另一种大小写追加一个同名头会让 HttpURLConnection 出现两个取值。
        unsigned.put("Chat-Id", randomHex(16));
        unsigned.put("Session-Id", sessionId);
        unsigned.put("lang", "en");
        return signedFetch(c, "POST", url, body, extra, unsigned, CHAT_TIMEOUT_MS);
    }

    /** 排队状态查询（GET，无请求体故不带 content-type）。 */
    public JSONObject queueStatus(Credential c, String sessionId) throws Exception {
        String url = QUEUE_STATUS_BASE + "?session_id=" + urlEncode(sessionId);
        HttpFetcher.Response r = signedFetch(c, "GET", url, new byte[0], null, null, 20_000);
        String text = r.readText();
        try { return new JSONObject(text); } catch (Exception e) { return new JSONObject(); }
    }

    // ══════════════════════════════════════════════════════════════
    //  会话 id 与非流式聚合
    // ══════════════════════════════════════════════════════════════

    /**
     * 由请求推导一个<b>会话内稳定</b>的 session id。
     *
     * <p>为什么要稳定：{@code Session-Id} 与 {@code prompt_cache_key} 是同一个值，
     * 后端按它命中前缀缓存，并把它当作<b>并发会话</b>的标识（并发上限报错
     * {@code TM.00001041} 就是按会话计的）。若每轮都换新 id，缓存命中恒为 0，
     * 且同一会话的并发计数被打散。
     *
     * <p>怎么做到稳定而不依赖客户端额外字段：OpenAI 客户端<b>每轮都会把完整
     * 历史重发</b>，因此「第一条 user 消息」在整个会话里是恒定不变的 —— 拿它的
     * 哈希当会话键，既不需要墨听传 session 字段，也不会把两个不同会话混进同一
     * 个并发额度里。
     */
    public static String sessionIdFor(JSONObject req) {
        try {
            JSONArray msgs = req.optJSONArray("messages");
            if (msgs != null) {
                for (int i = 0; i < msgs.length(); i++) {
                    JSONObject m = msgs.optJSONObject(i);
                    if (m == null || !"user".equals(m.optString("role"))) continue;
                    String content = m.optString("content", "");
                    if (content.isEmpty()) continue;
                    return sha1Hex("codearts-session|" + content).substring(0, 32);
                }
            }
        } catch (Exception ignored) { }
        return randomHex(16);
    }

    static String sha1Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-1");
            return hexLower(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return randomHex(20);
        }
    }

    /**
     * 把 CodeArts 的 SSE 聚合成标准非流式响应。
     *
     * <p>CodeArts 的流是标准 OpenAI 形状（{@code choices[0].delta}），
     * 差别只在两点：① 错误可以内嵌在 200 流里（{@code error_code}），
     * ② {@code tool_calls} 的 {@code function.arguments} 是分片下发的，
     * 必须按 {@code index} 拼接。这从 {@link OpencodeProvider#aggregate}
     * 直接复用会丢掉工具调用，故这里自带一份。
     */
    public static JSONObject aggregateSse(String model, String sse) throws Exception {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        JSONObject toolAcc = new JSONObject();     // index -> {id,name,args}
        String finishReason = "stop";
        String id = null;
        for (String line : sse.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            String code = sseErrorCode(payload);
            if (code != null) {
                String text = sseErrorText(payload);
                if (isQuotaExhaustedCode(text, payload)) {
                    throw new QuotaExhaustedException(quotaExhaustedMessage(model, System.currentTimeMillis()));
                }
                throw new StreamError(code, text);
            }
            JSONObject frame;
            try { frame = new JSONObject(payload); } catch (Exception e) { continue; }
            if (id == null) {
                String fid = frame.optString("id", "");
                if (!fid.isEmpty()) id = fid;
            }
            JSONArray choices = frame.optJSONArray("choices");
            if (choices == null || choices.length() == 0) continue;
            JSONObject ch = choices.optJSONObject(0);
            if (ch == null) continue;
            String fr = ch.optString("finish_reason", "");
            if (!fr.isEmpty() && !"null".equals(fr)) finishReason = fr;
            JSONObject delta = ch.optJSONObject("delta");
            if (delta == null) delta = ch.optJSONObject("message");
            if (delta == null) continue;
            String c = delta.optString("content", "");
            if (!c.isEmpty()) content.append(c);
            String r = delta.optString("reasoning_content", "");
            if (r.isEmpty()) r = delta.optString("reasoning", "");
            if (!r.isEmpty()) reasoning.append(r);
            JSONArray tcs = delta.optJSONArray("tool_calls");
            if (tcs != null) {
                for (int i = 0; i < tcs.length(); i++) {
                    JSONObject tc = tcs.optJSONObject(i);
                    if (tc == null) continue;
                    int idx = tc.optInt("index", i);
                    JSONObject slot = toolAcc.optJSONObject(String.valueOf(idx));
                    if (slot == null) {
                        slot = new JSONObject();
                        slot.put("id", tc.optString("id", "call_" + randomHex(8)));
                        slot.put("name", "");
                        slot.put("args", "");
                        toolAcc.put(String.valueOf(idx), slot);
                    }
                    if (slot.optString("id", "").isEmpty()) {
                        String tid = tc.optString("id", "");
                        if (!tid.isEmpty()) slot.put("id", tid);
                    }
                    JSONObject fn = tc.optJSONObject("function");
                    if (fn != null) {
                        String nm = fn.optString("name", "");
                        if (!nm.isEmpty()) slot.put("name", nm);
                        slot.put("args", slot.optString("args", "") + fn.optString("arguments", ""));
                    }
                }
            }
        }
        JSONObject result = new JSONObject();
        result.put("id", id == null ? "chatcmpl-jethub-codearts" : id);
        result.put("object", "chat.completion");
        result.put("model", model);
        JSONObject message = new JSONObject();
        message.put("role", "assistant");
        message.put("content", content.length() == 0 ? JSONObject.NULL : content.toString());
        if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
        if (toolAcc.length() > 0) {
            JSONArray arr = new JSONArray();
            // 注意：本项目的 org.json 是 Android 自带的旧版，JSONObject 只有
            // keys()（Iterator），没有 keySet()。
            java.util.List<String> keys = new java.util.ArrayList<>();
            java.util.Iterator<String> it = toolAcc.keys();
            while (it.hasNext()) keys.add(it.next());
            java.util.Collections.sort(keys, (a, b) -> Integer.compare(parseIntSafe(a), parseIntSafe(b)));
            for (String k : keys) {
                JSONObject slot = toolAcc.getJSONObject(k);
                JSONObject fn = new JSONObject();
                fn.put("name", slot.optString("name", ""));
                fn.put("arguments", slot.optString("args", ""));
                JSONObject o = new JSONObject();
                o.put("id", slot.optString("id", ""));
                o.put("type", "function");
                o.put("function", fn);
                arr.put(o);
            }
            message.put("tool_calls", arr);
            if ("stop".equals(finishReason)) finishReason = "tool_calls";
        }
        JSONArray choices = new JSONArray();
        JSONObject choice = new JSONObject();
        choice.put("index", 0);
        choice.put("message", message);
        choice.put("finish_reason", finishReason);
        choices.put(choice);
        result.put("choices", choices);
        return result;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return Integer.MAX_VALUE; }
    }

    /** 流内错误（HTTP 200 但 SSE 里带 error_code）。 */
    public static final class StreamError extends Exception {
        public final String code;
        public StreamError(String code, String detail) {
            super(detail == null || detail.isEmpty() ? code : detail);
            this.code = code;
        }
    }

    /** 流内额度耗尽（不可重试）。 */
    public static final class QuotaExhaustedException extends Exception {
        public QuotaExhaustedException(String message) { super(message); }
    }


    // ══════════════════════════════════════════════════════════════
    //  错误分类
    // ══════════════════════════════════════════════════════════════

    /** 一次失败的性质。 */
    public static final class ErrorInfo {
        public final String kind;
        public final boolean retryable;
        public final String detail;
        public ErrorInfo(String kind, boolean retryable, String detail) {
            this.kind = kind; this.retryable = retryable; this.detail = detail;
        }
    }

    /** 鉴权失败（可续期后重试）。 */
    public static boolean isAuthError(int status) {
        return status == 401 || status == 403;
    }

    public static boolean isAuthErrorText(String body) {
        if (body == null) return false;
        return body.contains("APIG.0602")
                || body.toLowerCase().matches(".*invalid\\s+token.*")
                || body.toLowerCase().matches(".*token\\s+expired.*")
                || body.toLowerCase().matches(".*token\\s+is\\s+invalid.*");
    }

    /**
     * 是否为<b>可重试</b>的排队/限流错误码。
     *
     * <p>⚠️ {@code 429} 必须锚定为独立数字：早期用裸子串匹配，额度码
     * {@code InferHub.4291.200} 里的 {@code 4291} 命中了 {@code 429}，
     * 被误判成排队 → 静默重试 30 分钟、界面零输出。
     */
    public static boolean isQueueErrorCode(String code) {
        if (code == null) return false;
        return code.contains("TM.00001041")
                || code.contains("81111")
                || code.contains("TPM")
                || code.matches("(?s).*(^|[^0-9])429([^0-9]|$).*")
                || code.toLowerCase().matches(".*rate.?limit.*")
                || code.toLowerCase().matches(".*too many requests.*")
                || code.contains("排队") || code.contains("限流");
    }

    /**
     * 是否为<b>不可重试</b>的额度耗尽。
     *
     * <p>判据用子串 {@code 4291}（而非全等）：该码由服务端下发，若上游改用
     * {@code InferHub.4291.xxx} 的其它尾号表达同一语义，只认全等会漏判。
     * 文案兜底 {@code insufficient quota} 不可省 —— 上游换码值时仍能命中。
     */
    public static boolean isQuotaExhaustedCode(String code, String message) {
        String c = code == null ? "" : code;
        String m = message == null ? "" : message;
        return c.contains("4291") || m.toLowerCase().matches("(?s).*insufficient[\\s_-]+quota.*");
    }

    /** benefit 包缺失（去掉 maas_type 头重试一次即可）。 */
    public static final String BENEFIT_NOT_FOUND_CODE = "InferHub.4004.200";

    public static boolean isBenefitNotFound(String text) {
        return text != null && text.contains(BENEFIT_NOT_FOUND_CODE);
    }

    // 错误分类词汇（供网关翻成给用户看的话）。
    public static final String KIND_QUOTA = "quota";
    public static final String KIND_QUEUE = "queue";
    public static final String KIND_AUTH = "auth";
    public static final String KIND_CONTEXT_WINDOW = "context_window";
    public static final String KIND_RATE_LIMIT = "rate_limit";
    public static final String KIND_INVALID_REQUEST = "invalid_request";
    public static final String KIND_SERVER = "server";
    public static final String KIND_TRANSPORT = "transport";
    public static final String KIND_BENEFIT_NOT_FOUND = "benefit_not_found";

    /**
     * 从一行 SSE 里抽出内嵌错误码。
     *
     * <p>CodeArts 的排队/限流/额度耗尽常常以 <b>HTTP 200 + SSE</b> 下发
     * （形如 {@code data:{"error_code":"InferHub.4291.200","error_msg":"insufficient quota"}}），
     * 不是 4xx —— 只看 HTTP 状态码会把它当成正常流，界面就会一直停在
     * 「运行中」且零输出。
     *
     * @return 错误码（无错误返回 null）
     */
    public static String sseErrorCode(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(jsonLine);
            String code = o.optString("error_code", "");
            if (!code.isEmpty()) return code;
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                String c = err.optString("code", "");
                if (!c.isEmpty()) return c;
                String t = err.optString("type", "");
                if (!t.isEmpty()) return t;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 取 SSE 内嵌错误的完整文本（error_code + error_msg）。 */
    public static String sseErrorText(String jsonLine) {
        if (jsonLine == null || jsonLine.isEmpty()) return "";
        try {
            JSONObject o = new JSONObject(jsonLine);
            StringBuilder sb = new StringBuilder();
            addPart(sb, o.optString("error_code", ""));
            addPart(sb, o.optString("error_msg", ""));
            if (sb.length() == 0) addPart(sb, o.optString("message", ""));
            return sb.toString();
        } catch (Exception ignored) { }
        return "";
    }

    /** 判断 HTTP 错误体是否为并发排队限流。 */
    public static boolean isQueueError(int status, String body) {
        if (status != 400 || body == null) return false;
        return body.contains("TM.00001041")
                || body.toLowerCase().matches("(?s).*(peak\\s+usage|try\\s+again\\s+after|peak\\s+hours).*")
                || body.toLowerCase().matches("(?s).*(high\\s+demand|too\\s+many\\s+requests).*");
    }

    /** 从错误体提取可分类的文本（OpenAI 风格 error 或 CodeArts error_code/error_msg）。 */
    public static String errorDetail(String body) {
        if (body == null) return "";
        try {
            JSONObject o = new JSONObject(body);
            StringBuilder sb = new StringBuilder();
            JSONObject err = o.optJSONObject("error");
            if (err != null) {
                addPart(sb, err.optString("code", ""));
                addPart(sb, err.optString("type", ""));
                addPart(sb, err.optString("message", ""));
            }
            addPart(sb, o.optString("error_code", ""));
            addPart(sb, o.optString("error_msg", ""));
            addPart(sb, o.optString("message", ""));
            if (sb.length() > 0) return sb.toString();
        } catch (Exception ignored) { }
        return body;
    }

    private static void addPart(StringBuilder sb, String v) {
        if (v != null && !v.isEmpty()) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(v);
        }
    }

    /** 归一到本项目网关的错误分类词汇。 */
    public static ErrorInfo classifyError(int status, String body, Map<String, List<String>> headers) {
        String detail = errorDetail(body);
        if (isQuotaExhaustedCode(detail, body)) {
            return new ErrorInfo("quota", false, detail);
        }
        if (isQueueErrorCode(detail)) {
            return new ErrorInfo("queue", true, detail);
        }
        if (isAuthError(status) || isAuthErrorText(body == null ? "" : body)) {
            return new ErrorInfo("auth", false, detail);
        }
        if (isContextWindowExceeded(detail)) {
            return new ErrorInfo("context_window", false, detail);
        }
        if (status == 429 || isRateLimited(detail)) {
            return new ErrorInfo("rate_limit", true, detail);
        }
        if (status == 400) {
            return new ErrorInfo("invalid_request", false, detail);
        }
        if (status >= 500) {
            return new ErrorInfo("server", true, detail);
        }
        if (status == 0) {
            return new ErrorInfo("transport", true, detail);
        }
        return new ErrorInfo("server", true, detail);
    }

    static boolean isContextWindowExceeded(String detail) {
        if (detail == null) return false;
        String d = detail.toLowerCase();
        return d.contains("context length") || d.contains("context window")
                || d.contains("maximum context") || d.contains("too many tokens")
                || d.contains("exceed") && d.contains("token");
    }

    static boolean isRateLimited(String detail) {
        if (detail == null) return false;
        String d = detail.toLowerCase();
        return d.contains("rate limit") || d.contains("too many requests") || d.contains("tpm");
    }

    /**
     * 额度耗尽给用户看的完整说明（含解禁时间）。
     *
     * <p>CodeArts 的 benefit 额度按<b>UTC+8 自然日</b>结算（IDE 模型卡标注
     * "每日 1000 万免费 Tokens"），而 {@code InferHub.4291.200} 的报文里
     * <b>不给</b>重置时刻，故按 UTC+8 当日 24:00 自行推算。措辞对"这是我们的
     * 推算"保持诚实：写"预计"，不谎称是服务端给的时间。
     */
    public static String quotaExhaustedMessage(String model, long nowMs) {
        long resetAt = nextUtc8DayStartMs(nowMs);
        long diff = resetAt - nowMs;
        String when;
        if (diff <= 0) when = "即将重置";
        else if (diff < 60_000) when = "不到 1 分钟";
        else if (diff < 3_600_000) when = Math.round(diff / 60_000.0) + " 分钟";
        else when = (Math.round(diff / 360_000.0) / 10.0) + " 小时";
        return "codearts: 模型 " + model + " 的免费额度（benefit）已用尽，预计 " + when
                + "后重置（按 UTC+8 自然日结算）。可改用其它模型，或在管理页切换/添加账号。";
    }

    /** UTC+8 的次日 00:00（毫秒）。 */
    public static long nextUtc8DayStartMs(long nowMs) {
        final long DAY = 86_400_000L;
        final long TZ = 8 * 3_600_000L;
        long shifted = nowMs + TZ;
        long dayStart = (shifted / DAY) * DAY;
        return dayStart + DAY - TZ;
    }

    // ══════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════

    /** 一次性签发 DPoP（供探针/测试用）。 */
    public JSONObject probeToken(Map<String, String> form, JSONObject jwk) throws Exception {
        return requestToken(form, jwk);
    }

    static String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return hexLower(b);
    }

    static String urlEncode(String s) {
        try {
            return java.net.URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            return s == null ? "" : s;
        }
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                out.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
                out.put(k, v);
            }
        }
        return out;
    }

    private static void writeResponse(Socket sock, int status, String body, String location) throws IOException {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(' ').append(reasonPhrase(status)).append("\r\n");
        head.append("Content-Type: text/html; charset=utf-8\r\n");
        head.append("Access-Control-Allow-Origin: *\r\n");
        head.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
        head.append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Requested-With\r\n");
        if (location != null) head.append("Location: ").append(location).append("\r\n");
        head.append("Content-Length: ").append(payload.length).append("\r\n");
        head.append("Connection: close\r\n\r\n");
        try (OutputStream os = sock.getOutputStream()) {
            os.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            if (payload.length > 0) os.write(payload);
            os.flush();
        } finally {
            try { sock.close(); } catch (IOException ignored) { }
        }
    }

    private static String reasonPhrase(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 307: return "Temporary Redirect";
            case 400: return "Bad Request";
            case 404: return "Not Found";
            default: return "OK";
        }
    }

    static void closeQuietly(Closeable c) {
        if (c == null) return;
        try { c.close(); } catch (IOException ignored) { }
    }

    static boolean notEmpty(String s) { return s != null && !s.isEmpty(); }

    static String snippet(String s) {
        if (s == null) return "";
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    static String shortErr(Exception e) {
        String m = e.getMessage();
        return (m == null || m.trim().isEmpty()) ? e.getClass().getSimpleName() : snippet(m);
    }
}
