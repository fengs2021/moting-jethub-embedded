package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * OpenCode Zen 适配器（{@code https://opencode.ai/zen}）。
 *
 * <h3>与其余五家最大的不同：它是标准 OpenAI 兼容</h3>
 * 请求体就是 OpenAI chat 形状，响应就是标准 OpenAI SSE，**不需要协议转换**。
 * 但有两道「形状门禁」必须原样复刻，否则匿名通道一律 403：
 *
 * <ol>
 *   <li><b>指纹头</b>：{@code x-opencode-project} / {@code x-opencode-session} /
 *       {@code x-opencode-request} / {@code x-opencode-client} / {@code user-agent}。
 *       真实 CLI（1.18.22）只发这五项；<b>不发</b> {@code x-session-affinity} 与
 *       {@code X-Session-Id}（那是同函数里「非 opencode provider」分支的头）。</li>
 *   <li><b>FreeTier 形状门禁</b>：匿名通道（{@code Bearer public}）拒绝不带
 *       agent 形状的 body，回 403 FreeTierError —— 除非 {@code stream:true}
 *       <b>且</b> {@code tools} 里存在名为 {@code bash} 与 {@code read} 的
 *       function 工具（描述/参数/请求头都不检查）。见 {@link #ensureFreeLaneShape}。</li>
 * </ol>
 *
 * <h3>session id 的形状不是随意选的</h3>
 * 门禁正则 {@code ses_[0-9a-f]{12}[0-9A-Za-z]{14}}。官方 {@code id/index.ts} 的
 * {@code const LENGTH = 26} 是<b>整段尾部长度</b>（{@code randomBase62(LENGTH - 12)}），
 * 随机段是 <b>14</b>。误读成「随机 26 位」会产出 {@code ses_} + 38 字符，
 * 形状不对 ⇒ 匿名通道全线 403。
 *
 * <h3>身份结论（本适配器设计的基石）</h3>
 * opencode CLI 发往 Zen 的请求<b>没有任何机器级指纹</b>（无 machine-id / deviceId /
 * 安装 ID / 遥测）。身份只有三维度：<b>出口 IP、API key、随机会话 id</b>。
 * 指纹派生的作用是防关联与满足形状门禁，不参与配额计算。
 *
 * <h3>错误分类必须按「错误类型名」而非状态码</h3>
 * Zen 的额度错误可能带 400/401/403/429 任一状态码，唯一稳定信号是响应体里的
 * 错误类型名（{@code FreeUsageLimitError} / {@code GoUsageLimitError} /
 * {@code FreeTierError}）。特别是 <b>402 必须单独归类成 quota</b>：付费 key
 * 余额耗尽时上游回 402，若归成「服务端故障请重试」会让用户反复重试一个
 * 永远不会成功的请求。
 */
public final class OpencodeProvider {

    public static final String ID = "opencode";
    public static final String DISPLAY_NAME = "OpenCode";
    public static final String BASE_URL = "https://opencode.ai/zen";
    public static final String CHAT_PATH = "/v1/chat/completions";
    public static final String MODELS_PATH = "/v1/models";
    /** 匿名凭据是<b>字面量</b> {@code public}：官方 CLI 在没有 key 时自己就用它。 */
    public static final String ANONYMOUS_KEY = "public";
    public static final String DEFAULT_USER_AGENT = "opencode/1.18.22";
    public static final String CREDENTIAL_REF = "JETHUB_OPENCODE_TOKEN";
    public static final int HTTP_TIMEOUT_MS = 60_000;
    /** 对话超时：与网关其余家同档（流式长回复）。 */
    public static final int CHAT_TIMEOUT_MS = 600_000;

    /** 门禁工具名（服务端只认这两个名字）。 */
    public static final String[] FREE_LANE_GATE_TOOLS = {"bash", "read"};

    private static final String BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    /** session id 尾段总长度（12 位 hex 时间戳 + 随机段）。 */
    private static final int SESSION_TAIL_LENGTH = 26;
    /** 随机段长度 = 26 - 12 = 14。 */
    private static final int SESSION_RANDOM_LENGTH = SESSION_TAIL_LENGTH - 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CredentialStore store;

    public OpencodeProvider(CredentialStore store) { this.store = store; }

    // ══════════════════════════════════════════════════════════════
    //  凭据
    // ══════════════════════════════════════════════════════════════

    /**
     * 一份 OpenCode 凭据。
     *
     * <p>{@code projectId} 首次添加时派生并持久化 —— 同一账号的指纹必须稳定，
     * 每次现派生会让同一账号在服务端看起来像不同项目。
     */
    public static final class Credential {
        /** Zen API key（{@code sk-…}）；匿名通道用字面量 {@code public}。 */
        public String apiKey = "";
        public String nickname = "";
        /** 40 位小写 hex；空则由 apiKey 现派生。 */
        public String projectId = "";
        /** 指纹轮换代次；+1 后 project id 整体变化。 */
        public int generation = 0;

        public JSONObject toJson() throws org.json.JSONException {
            JSONObject o = new JSONObject();
            o.put("api_key", apiKey);
            o.put("nickname", nickname);
            if (projectId != null && !projectId.isEmpty()) o.put("project_id", projectId);
            o.put("generation", generation);
            return o;
        }

        public static Credential fromJson(JSONObject o) {
            Credential c = new Credential();
            c.apiKey = o.optString("api_key", "");
            c.nickname = o.optString("nickname", "");
            c.projectId = o.optString("project_id", "");
            c.generation = o.optInt("generation", 0);
            return c;
        }

        /** 是否为匿名通道（字面量 {@code public}）。 */
        public boolean isAnonymous() { return ANONYMOUS_KEY.equals(apiKey); }
    }

    public Credential loadCredential() {
        String raw = store.get(CREDENTIAL_REF);
        if (raw == null || raw.isEmpty()) return null;
        try { return Credential.fromJson(new JSONObject(raw)); } catch (Exception e) { return null; }
    }

    public void saveCredential(Credential c) throws Exception {
        store.set(CREDENTIAL_REF, c.toJson().toString());
    }

    /**
     * API key 不会过期 —— 恒 false。
     *
     * <p>与其余家不同：它们有 access_token + refresh_token 的续期循环，
     * OpenCode 的 key 是长期凭据，失效只能靠用户换 key（表现为 401/403）。
     */
    public boolean isExpired(Credential c) { return false; }

    public Credential ensureFresh() throws Exception {
        Credential c = loadCredential();
        if (c == null) throw new IllegalStateException("opencode: 未配置 API key");
        return c;
    }

    // ══════════════════════════════════════════════════════════════
    //  指纹派生（对齐 opencode.ts）
    // ══════════════════════════════════════════════════════════════

    /** 40 位小写 hex 的 project id：{@code sha1("git-remote:opencode/" + sha256_hex(identity + " " + generation))}。 */
    public static String deriveProjectId(String identity, int generation) {
        String inner = sha256Hex(identity + " " + generation);
        return sha1Hex("git-remote:opencode/" + inner);
    }

    /**
     * 派生 session id：{@code ses_} + 12 位小写 hex（6 字节时间戳）+ <b>14</b> 位 base62。
     *
     * <p>⚠️ 尾段随机部分是 14 而不是 26（见类注释）。时间戳按官方 id 模块的
     * 字节序写入：低字节在前，再整体取 hex。
     */
    public static String deriveSessionId() {
        byte[] time = new byte[6];
        long now = System.currentTimeMillis();
        for (int i = 0; i < 6; i++) {
            time[i] = (byte) (now & 0xff);
            now >>= 8;
        }
        StringBuilder sb = new StringBuilder("ses_");
        for (byte b : time) sb.append(String.format("%02x", b));
        sb.append(randomBase62(SESSION_RANDOM_LENGTH));
        return sb.toString();
    }

    /** 派生 request id：{@code req_} + 32 位 hex（16 随机字节）。 */
    public static String deriveRequestId() {
        byte[] buf = new byte[16];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder("req_");
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String randomBase62(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) sb.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        return sb.toString();
    }

    private static String sha256Hex(String s) { return digestHex("SHA-256", s); }

    private static String sha1Hex(String s) { return digestHex("SHA-1", s); }

    private static String digestHex(String algo, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance(algo);
            byte[] out = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 算法必然存在（JDK 保证）；退化成空串让上层看出异常而不是静默用错值
            return "";
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  会话 id 缓存
    // ══════════════════════════════════════════════════════════════

    /**
     * 进程内缓存的 session id。
     *
     * <p>每次现随机会让同一会话在服务端被看成多个独立会话，破坏亲和性。
     * 官方 CLI 也是「一个会话一个 id 反复用」。
     */
    private volatile String sessionId;

    /** 取本进程的 session id（首次生成后固定复用）。 */
    public String sessionId() {
        String s = sessionId;
        if (s == null) {
            synchronized (this) {
                if (sessionId == null) sessionId = deriveSessionId();
                s = sessionId;
            }
        }
        return s;
    }

    /** 强制换一个新会话（用户点「新会话」时用）。 */
    public synchronized String newSession() {
        sessionId = deriveSessionId();
        return sessionId;
    }

    // ══════════════════════════════════════════════════════════════
    //  请求头
    // ══════════════════════════════════════════════════════════════

    /** 该凭据的 project id（缺失时按 apiKey 现派生，不写回）。 */
    public static String projectIdOf(Credential c) {
        if (c.projectId != null && !c.projectId.isEmpty()) return c.projectId;
        return deriveProjectId(c.apiKey, c.generation);
    }

    /**
     * 构造发往 Zen 的完整头集。
     *
     * <p>只发真实 CLI 的 5 个指纹头 + content-type + accept + authorization。
     * <b>绝不发</b> {@code x-session-affinity} / {@code X-Session-Id}。
     */
    public static Map<String, String> headers(Credential c, String sessionId, String requestId) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-opencode-project", projectIdOf(c));
        h.put("x-opencode-session", sessionId);
        h.put("x-opencode-request", requestId);
        h.put("x-opencode-client", "cli");
        h.put("user-agent", DEFAULT_USER_AGENT);
        h.put("content-type", "application/json");
        h.put("accept", "text/event-stream");
        h.put("authorization", "Bearer " + c.apiKey);
        return h;
    }

    // ══════════════════════════════════════════════════════════════
    //  FreeTier 形状门禁
    // ══════════════════════════════════════════════════════════════

    /** 门禁桩工具：描述固定为「不要调用」，参数为空对象。 */
    private static JSONObject gateTool(String name) throws org.json.JSONException {
        JSONObject fn = new JSONObject();
        fn.put("name", name);
        fn.put("description", "Reserved for the host runtime; do not call it.");
        fn.put("parameters", new JSONObject().put("type", "object").put("properties", new JSONObject()));
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", fn);
        return tool;
    }

    /**
     * 注入门禁工具，必要时补 {@code tool_choice}。
     *
     * <p>⚠️ {@code tool_choice:"none"} <b>只在原本一个工具都没有时</b>补：
     * 真实工具在列时覆盖用户的自选 tool_choice 会让模型无法调用任何工具。
     *
     * <p>账号槽（带 API key）<b>同样注入</b>：免费通道与认证通道的服务端策略
     * 可能共用同一层形状检查，多注入一层对认证通道无害，缺了它匿名槽可能整体失效。
     *
     * @return 满足门禁的 body（就地修改后返回同一对象）
     */
    public static JSONObject ensureFreeLaneShape(JSONObject payload) throws org.json.JSONException {
        if (!payload.has("messages") || payload.optJSONArray("messages") == null) return payload;
        JSONArray tools = payload.optJSONArray("tools");
        boolean hadTools = tools != null && tools.length() > 0;
        java.util.Set<String> names = new java.util.HashSet<>();
        if (tools != null) {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject t = tools.optJSONObject(i);
                if (t == null) continue;
                JSONObject fn = t.optJSONObject("function");
                String n = fn == null ? "" : fn.optString("name", "");
                if (!n.isEmpty()) names.add(n);
            }
        }
        JSONArray out = tools == null ? new JSONArray() : tools;
        boolean missing = false;
        for (String g : FREE_LANE_GATE_TOOLS) {
            if (names.contains(g)) continue;
            out.put(gateTool(g));
            missing = true;
        }
        if (!missing) return payload;
        payload.put("tools", out);
        // 仅当原本一个工具都没有时才补 tool_choice（否则会覆盖用户自选）
        if (!hadTools) payload.put("tool_choice", "none");
        return payload;
    }

    // ══════════════════════════════════════════════════════════════
    //  请求体
    // ══════════════════════════════════════════════════════════════

    /**
     * OpenAI 请求 → Zen 请求体。
     *
     * <p>⚠️ {@code stream} <b>恒 true</b>：这是门禁的一半要求。客户端要非流式时
     * 由网关侧聚合（见 {@link #aggregate}）。
     */
    public static JSONObject buildPayload(JSONObject openai, String model) throws org.json.JSONException {
        JSONObject p = new JSONObject();
        p.put("model", model);
        JSONArray messages = openai.optJSONArray("messages");
        p.put("messages", messages == null ? new JSONArray() : messages);
        p.put("stream", true);
        JSONArray tools = openai.optJSONArray("tools");
        p.put("tools", tools == null ? new JSONArray() : tools);
        if (openai.has("temperature") && !openai.isNull("temperature")) {
            p.put("temperature", openai.optDouble("temperature"));
        }
        // ⚠️ 只放行安全正整数：0 / 负数会让上游报错，把整轮对话搞崩
        int maxTokens = openai.optInt("max_tokens", 0);
        if (maxTokens > 0) p.put("max_tokens", maxTokens);
        String effort = openai.optString("reasoning_effort", "");
        if (!effort.isEmpty()) p.put("reasoning_effort", effort);
        return ensureFreeLaneShape(p);
    }

    // ══════════════════════════════════════════════════════════════
    //  模型目录
    // ══════════════════════════════════════════════════════════════

    /**
     * 兜底模型表（以真机实测为准）。
     *
     * <p>每条 {@code {id, 展示名, 是否免费}}。{@code 是否免费}的含义是
     * 「<b>匿名通道</b>（{@code Bearer public}）能否使用」，不是官方定价表的 Free 标记。
     *
     * <p>⚠️ {@code ling-3.0-flash-fin-free} 已移除：它走 {@code /v1/messages}
     * （Anthropic 协议，我们未实现），chat 端点 404 ⇒ 两条通道都不可用，
     * 留在目录里只会让用户点到一个必然失败的模型。
     */
    static final String[][] FALLBACK_MODELS = {
            // ── 匿名可用（chat 端点，无需 key）──
            {"big-pickle", "Big Pickle", "1"},
            {"space-bunny-free", "Space Bunny Free", "1"},
            {"longcat-2.5-preview-free", "LongCat 2.5 Preview Free", "1"},
            {"mimo-v2.6-flash-free", "MiMo-V2.6-Flash Free", "1"},
            {"mimo-v2.5-free", "MiMo-V2.5 Free", "1"},
            // 官方 docs 标注走 /v1/models/{id}（Gemini 风格），但实测 chat 端点也通
            {"nemotron-3-ultra-free", "Nemotron 3 Ultra Free", "1"},
            {"nemotron-3.5-lightning-free", "Nemotron 3.5 Lightning Free", "1"},
            // ── 需付费 key（chat 端点；实测匿名为 401）──
            {"deepseek-v4-flash", "DeepSeek V4 Flash", "0"},
            {"deepseek-v4.1-flash", "DeepSeek V4.1 Flash", "0"},
            {"glm-5.2", "GLM 5.2", "0"},
            {"kimi-k2.5", "Kimi K2.5", "0"},
            {"minimax-m2.7", "minimax M2.7", "0"},
            {"minimax-m3", "minimax M3", "0"},
            {"qwen3.8-max", "Qwen3.8 Max", "0"},
    };

    /**
     * 该模型是否属免费（匿名槽只接受免费模型）。
     *
     * <p>⚠️ <b>表外一律 false</b>：远端目录含大量表外模型，未实测前放给匿名槽
     * 只会换来 403/500；宁可要求用户配一个付费账号。
     */
    public static boolean isFree(String id) {
        for (String[] m : FALLBACK_MODELS) if (m[0].equals(id)) return "1".equals(m[2]);
        return false;
    }

    /**
     * 该模型是否在我们<b>已实现并实测可达</b>的通道里存在。
     *
     * <p>⚠️ 远端 {@code /v1/models} 返回全部 84+ 个模型且<b>不含协议信息</b>
     * （字段只有 id/object/created/owned_by）。直接暴露会让用户点到
     * {@code claude-*}（走 messages 协议，我们未实现）、{@code gpt-*} 等
     * 必然失败的模型 ⇒ 目录只暴露本表内的模型。
     */
    public static boolean isReachable(String id) {
        for (String[] m : FALLBACK_MODELS) if (m[0].equals(id)) return true;
        return false;
    }

    /** 展示名（免费模型加「（免费）」后缀，让用户一眼看出哪个不花钱）。 */
    public static String displayName(String id) {
        for (String[] m : FALLBACK_MODELS) {
            if (m[0].equals(id)) return "1".equals(m[2]) ? m[1] + "（免费）" : m[1];
        }
        return id;
    }

    /**
     * 列出可用模型。
     *
     * <p>拉远端 {@code /v1/models} 并用可达性过滤；失败或解析为空则回退兜底表。
     * 远端不下发「免费」标记也不下发展示名，故两者都按本地表判定。
     */
    public JSONObject listModels() throws Exception {
        Credential c = ensureFresh();
        java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
        try {
            HttpFetcher.Response resp = HttpFetcher.get(BASE_URL + MODELS_PATH,
                    modelsHeaders(c), HTTP_TIMEOUT_MS);
            String text = resp.readText();
            if (resp.status != 200) {
                throw new IllegalStateException("opencode: models HTTP " + resp.status + " " + shortText(text));
            }
            JSONArray data = new JSONObject(text).optJSONArray("data");
            if (data == null || data.length() == 0) throw new IllegalStateException("opencode: 空目录");
            for (int i = 0; i < data.length(); i++) {
                JSONObject m = data.optJSONObject(i);
                if (m == null) continue;
                String id = m.optString("id", "");
                if (id.isEmpty() || !isReachable(id)) continue;
                ids.add(id);
            }
            if (ids.isEmpty()) throw new IllegalStateException("opencode: 过滤后为空");
        } catch (Exception e) {
            // 远端不可用（未联网 / 被墙 / 上游故障）→ 兜底表；目录永远可用
            ids.clear();
            for (String[] m : FALLBACK_MODELS) ids.add(m[0]);
        }
        JSONArray out = new JSONArray();
        for (String id : ids) {
            JSONObject nm = new JSONObject();
            nm.put("id", ID + "/" + id);
            nm.put("object", "model");
            nm.put("owned_by", ID);
            nm.put("name", displayName(id));
            out.put(nm);
        }
        JSONObject result = new JSONObject();
        result.put("object", "list");
        result.put("data", out);
        return result;
    }

    /** 目录请求头：只需指纹 + 授权（不需要 SSE accept）。 */
    private static Map<String, String> modelsHeaders(Credential c) {
        Map<String, String> h = new LinkedHashMap<>();
        h.put("x-opencode-project", projectIdOf(c));
        h.put("x-opencode-session", deriveSessionId());
        h.put("x-opencode-request", deriveRequestId());
        h.put("x-opencode-client", "cli");
        h.put("user-agent", DEFAULT_USER_AGENT);
        h.put("accept", "application/json");
        h.put("authorization", "Bearer " + c.apiKey);
        return h;
    }

    // ══════════════════════════════════════════════════════════════
    //  对话
    // ══════════════════════════════════════════════════════════════

    /** 发一次对话请求（恒流式；body 由调用方读完/关闭）。 */
    public HttpFetcher.Response chatRaw(Credential c, JSONObject payload, String sessionId) throws Exception {
        Map<String, String> h = headers(c, sessionId, deriveRequestId());
        return HttpFetcher.fetch("POST", BASE_URL + CHAT_PATH, h,
                payload.toString().getBytes(StandardCharsets.UTF_8), CHAT_TIMEOUT_MS);
    }

    // ══════════════════════════════════════════════════════════════
    //  错误分类
    // ══════════════════════════════════════════════════════════════

    public static final class ErrorInfo {
        public final String kind;
        public final Long retryAfterMs;
        public final String detail;

        ErrorInfo(String kind, Long retryAfterMs, String detail) {
            this.kind = kind; this.retryAfterMs = retryAfterMs; this.detail = detail;
        }
    }

    /** 免费通道当日额度用尽（{@code FreeUsageLimitError}）。 */
    public static final String KIND_FREE_USAGE_LIMIT = "free_usage_limit";
    /** 付费通道额度用尽（{@code GoUsageLimitError}）。 */
    public static final String KIND_GO_USAGE_LIMIT = "go_usage_limit";
    /** 通用限流（429 / rate limit）。 */
    public static final String KIND_RATE_LIMIT = "rate_limit";
    /** 形状门禁拒绝（伪装形态被拒；重试与换槽都无意义）。 */
    public static final String KIND_FREE_TIER = "free_tier";
    /** 余额不足（402）—— 唯一有效动作是去充值。 */
    public static final String KIND_QUOTA = "quota";
    public static final String KIND_AUTH = "auth";
    public static final String KIND_SERVER = "server";
    public static final String KIND_TRANSPORT = "transport";

    /**
     * 把一次失败的 HTTP 响应归类为语义化错误。
     *
     * <p>⚠️ <b>顺序即优先级</b>：限流/额度语义必须在 auth 之前判 —— 上游的额度
     * 错误经常带 401/403 状态码，若先判状态码会把「额度用尽」误报成「key 失效」，
     * 用户唯一有效的动作（等窗口恢复 / 换账号）会被完全掩盖。
     *
     * @param status      HTTP 状态码；传输层失败传 0
     * @param body        响应体原文（调用方<b>必须先读一次体</b>）
     * @param retryAfter  响应头 {@code retry-after} 原文，可为 null
     */
    public static ErrorInfo classifyError(int status, String body, String retryAfter) {
        Long ra = parseRetryAfterMs(retryAfter);
        String detail = readableDetail(body);
        String lower = body == null ? "" : body.toLowerCase();
        String raw = body == null ? "" : body;

        if (raw.contains("FreeUsageLimitError")) return new ErrorInfo(KIND_FREE_USAGE_LIMIT, ra, detail);
        if (raw.contains("GoUsageLimitError")) return new ErrorInfo(KIND_GO_USAGE_LIMIT, ra, detail);
        if (raw.contains("FreeTierError")) return new ErrorInfo(KIND_FREE_TIER, null, detail);
        if (status == 429 || lower.contains("too many requests") || lower.contains("rate limit")) {
            return new ErrorInfo(KIND_RATE_LIMIT, ra, detail);
        }
        if (status == 0 || lower.contains("fetch failed") || lower.contains("terminated")
                || lower.contains("econnreset") || lower.contains("socket hang up")
                || lower.contains("getaddrinfo") || lower.contains("econnrefused")) {
            return new ErrorInfo(KIND_TRANSPORT, null, detail);
        }
        // ⚠️ 402 必须单独归类：付费 key 余额耗尽时上游回
        // 402 {"error":{"type":"server_error","message":"...Insufficient account funds"}}。
        // 归成 SERVER 会让用户看到「服务端故障请重试」——而重试永远不会成功。
        if (status == 402 || lower.contains("insufficient account funds")
                || lower.contains("insufficient funds") || lower.contains("insufficient balance")) {
            return new ErrorInfo(KIND_QUOTA, null, detail);
        }
        if (status == 401 || status == 403) return new ErrorInfo(KIND_AUTH, null, detail);
        if (status >= 500) return new ErrorInfo(KIND_SERVER, null, detail);
        return new ErrorInfo(KIND_AUTH, null, detail);
    }

    /**
     * 从 {@code retry-after} 头解析毫秒。
     *
     * <p>⚠️ <b>0 是合法值</b>（「立即解除」），故返回 {@code null} 才表示「未给」。
     * 支持秒数与 HTTP 日期两种形态。
     */
    public static Long parseRetryAfterMs(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        raw = raw.trim();
        try {
            double seconds = Double.parseDouble(raw);
            if (!Double.isNaN(seconds) && seconds >= 0) return (long) Math.ceil(seconds * 1000);
        } catch (NumberFormatException ignored) { }
        // HTTP 日期形态
        try {
            java.text.SimpleDateFormat[] fmts = {
                    new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US),
                    new java.text.SimpleDateFormat("EEEE, dd-MMM-yy HH:mm:ss zzz", java.util.Locale.US),
                    new java.text.SimpleDateFormat("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US),
            };
            for (java.text.SimpleDateFormat f : fmts) {
                try {
                    java.util.Date d = f.parse(raw);
                    if (d != null) return Math.max(0L, d.getTime() - System.currentTimeMillis());
                } catch (java.text.ParseException ignored) { }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** 从错误体里抽出人可读片段（禁止把整坨 JSON 抛给用户）。 */
    public static String readableDetail(String body) {
        if (body == null) return "（上游未返回错误详情）";
        String text = body.trim();
        if (text.isEmpty()) return "（上游未返回错误详情）";
        try {
            JSONObject o = new JSONObject(text);
            StringBuilder sb = new StringBuilder();
            String m = o.optString("message", "");
            if (!m.isEmpty()) sb.append(m);
            Object err = o.opt("error");
            String nested = "";
            if (err instanceof JSONObject) nested = ((JSONObject) err).optString("message", "");
            else if (err instanceof String) nested = (String) err;
            if (!nested.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(nested);
            }
            String msg = o.optString("msg", "");
            if (!msg.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(msg);
            }
            if (sb.length() > 0) return sb.toString();
        } catch (Exception ignored) { }
        return text.length() > 500 ? text.substring(0, 500) + "…" : text;
    }

    // ══════════════════════════════════════════════════════════════
    //  非流式聚合（OpenAI SSE → 标准非流式响应）
    // ══════════════════════════════════════════════════════════════

    /**
     * 把 OpenAI 形状的 SSE 文本聚合成标准非流式响应。
     *
     * <p>Zen 恒流式（门禁要求 {@code stream:true}），客户端要非流式时由这里收口。
     */
    public static JSONObject aggregate(String model, String sse) {
        StringBuilder content = new StringBuilder();
        StringBuilder reasoning = new StringBuilder();
        String finishReason = "stop";
        String id = null;
        for (String line : sse.split("\n")) {
            line = line.trim();
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || "[DONE]".equals(payload)) continue;
            try {
                JSONObject frame = new JSONObject(payload);
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
            } catch (Exception ignored) { }
        }
        JSONObject result = new JSONObject();
        try {
            result.put("id", id == null ? "chatcmpl-jethub-opencode" : id);
            result.put("object", "chat.completion");
            result.put("model", model);
            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", content.toString());
            if (reasoning.length() > 0) message.put("reasoning_content", reasoning.toString());
            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("message", message);
            choice.put("finish_reason", finishReason);
            choices.put(choice);
            result.put("choices", choices);
        } catch (Exception ignored) { }
        return result;
    }

    private static String shortText(String s) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ").trim();
        return s.length() > 180 ? s.substring(0, 180) : s;
    }

    /** 未登录等终态（与 MinimaxProvider.TokenExpiredException 同语义）。 */
    public static class TokenExpiredException extends IllegalStateException {
        public TokenExpiredException(String msg) { super(msg); }
    }
}
