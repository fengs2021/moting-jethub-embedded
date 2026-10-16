import jet.core.*;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;

public class RegTest {
    public static void main(String[] a) throws Exception {
        CredentialStore store = new CredentialStore.FileBased(new File("/tmp/reg-test-cred.json"));
        store.set("JETHUB_LOCAL_KEY", "0123456789abcdef0123456789abcdef0123456789abcdef");
        // 直接置 store 字段（绕过 android Context）
        Object ctx = Class.forName("jet.core.AppContextHolder").getDeclaredField("credentialStore");
        ((Field) ctx).setAccessible(true);
        ((Field) ctx).set(null, store);
        // 构造网关实例（不启动端口，只为 INSTANCE 与目录逻辑）
        new GatewayServer(new ClineProvider(store), new TraeProvider(store),
                new BuddyProvider(store, BuddyProvider.Variant.buddy()),
                new BuddyProvider(store, BuddyProvider.Variant.workbuddy()), store);

        JSONObject r1 = ModelCenterRegistrar.merge("{}");
        int ac1 = r1.getJSONArray("accounts").length(), md1 = r1.getJSONArray("models").length(),
            rt1 = r1.getJSONArray("routes").length(), bd1 = r1.getJSONArray("bindings").length();
        System.out.println("合并1: accounts=" + ac1 + " models=" + md1 + " routes=" + rt1 + " bindings=" + bd1);
        if (ac1 != 1 || md1 != 3 || rt1 != 3 || bd1 != 2) throw new AssertionError("首次合并计数错");
        // 幂等：再合并一次不增
        JSONObject r2 = ModelCenterRegistrar.merge(r1.toString());
        int md2 = r2.getJSONArray("models").length(), rt2 = r2.getJSONArray("routes").length();
        System.out.println("幂等: models=" + md2 + " routes=" + rt2);
        if (md2 != 3 || rt2 != 3) throw new AssertionError("幂等性破坏");
        // 用户已有配置：账号/模型保留，JetHub 追加
        String user = "{\"schemaVersion\":3,\"accounts\":[{\"id\":\"acct-user\",\"name\":\"我的\",\"baseUrl\":\"https://x.example/v1\",\"protocol\":\"OPENAI_COMPATIBLE\",\"enabled\":true}],\"models\":[{\"id\":\"model-user\",\"displayName\":\"用户模型\",\"capability\":\"TEXT_ANALYSIS\",\"generationModes\":[],\"generationModesVerified\":false,\"enabled\":true,\"invocationDefaults\":{}}],\"routes\":[{\"id\":\"route-user\",\"modelId\":\"model-user\",\"accountId\":\"acct-user\",\"upstreamModelName\":\"user-model\",\"priority\":0,\"enabled\":true,\"options\":{},\"legacyRefs\":[]}],\"bindings\":[{\"purpose\":\"GLOBAL_TEXT_DEFAULT\",\"primaryModelId\":\"model-user\",\"backupModelIds\":[],\"allowGlobalFallback\":true,\"selectionMode\":\"SINGLE\",\"rotationIndex\":0,\"selectedRouteIds\":[]}],\"suppressedRouteKeys\":[]}";
        JSONObject r3 = ModelCenterRegistrar.merge(user);
        boolean keepAcct=false, keepModel=false, keepPurpose=false;
        for (int i=0;i<r3.getJSONArray("accounts").length();i++) if ("acct-user".equals(r3.getJSONArray("accounts").getJSONObject(i).optString("id"))) keepAcct=true;
        for (int i=0;i<r3.getJSONArray("models").length();i++) if ("model-user".equals(r3.getJSONArray("models").getJSONObject(i).optString("id"))) keepModel=true;
        for (int i=0;i<r3.getJSONArray("bindings").length();i++) if ("GLOBAL_TEXT_DEFAULT".equals(r3.getJSONArray("bindings").getJSONObject(i).optString("purpose")) && "model-user".equals(r3.getJSONArray("bindings").getJSONObject(i).optString("primaryModelId"))) keepPurpose=true;
        System.out.println("用户保留: acct=" + keepAcct + " model=" + keepModel + " purpose不覆盖=" + keepPurpose + " 总模型=" + r3.getJSONArray("models").length());
        if (!keepAcct || !keepModel || !keepPurpose) throw new AssertionError("用户配置被破坏");

        // ── MiniMax 签到面板层级（回归：曾把 claim 的形状套到 status 上） ──
        // 形状一：signin/status 的面板字段直接挂在 data 上（无 panel 层）
        JSONObject flat = new JSONObject(
            "{\"scene\":1,\"days\":[{\"day_no\":1,\"points\":10,\"status\":3,\"is_today\":false},"
            + "{\"day_no\":2,\"points\":20,\"status\":2,\"is_today\":true}]}");
        JSONObject gotFlat = CreditsService.resolveMinimaxStatusPanel(flat);
        if (gotFlat == null) throw new AssertionError("扁平形状（status 真实形状）解析失败");
        if (gotFlat.optJSONArray("days").length() != 2) throw new AssertionError("扁平形状 days 丢失");
        System.out.println("签到面板-扁平形状: days=" + gotFlat.optJSONArray("days").length());

        // 形状二：signin/claim 的面板在 data.panel 下
        JSONObject nested = new JSONObject("{\"points\":30,\"panel\":{\"scene\":1,\"days\":[]}}");
        JSONObject gotNested = CreditsService.resolveMinimaxStatusPanel(nested);
        if (gotNested == null || !gotNested.has("scene")) throw new AssertionError("嵌套形状（claim 真实形状）解析失败");
        System.out.println("签到面板-嵌套形状: 命中 data.panel");

        // 两种形状都不成立时必须返回 null（调用方据此报「签到面板字段缺失」）
        if (CreditsService.resolveMinimaxStatusPanel(new JSONObject("{\"foo\":1}")) != null) {
            throw new AssertionError("无关响应不应被当成面板");
        }
        if (CreditsService.resolveMinimaxStatusPanel(new JSONObject("{}")) != null) {
            throw new AssertionError("空对象不应被当成面板");
        }
        if (CreditsService.resolveMinimaxStatusPanel(null) != null) {
            throw new AssertionError("null 不应被当成面板");
        }
        System.out.println("签到面板-无关响应: 正确返回 null");

        // ── is_today 宽容读取（协议文档陷阱 80：服务端可能回数字 0/1） ──
        JSONObject dayBool = new JSONObject("{\"is_today\":true}");
        JSONObject dayNum1 = new JSONObject("{\"is_today\":1}");
        JSONObject dayNum0 = new JSONObject("{\"is_today\":0}");
        JSONObject dayStr  = new JSONObject("{\"is_today\":\"true\"}");
        JSONObject dayNone = new JSONObject("{}");
        if (!CreditsService.readBool(dayBool, "is_today", false)) throw new AssertionError("is_today=true 应判真");
        if (!CreditsService.readBool(dayNum1, "is_today", false)) throw new AssertionError("is_today=1 应判真");
        if (CreditsService.readBool(dayNum0, "is_today", false))  throw new AssertionError("is_today=0 应判假");
        if (!CreditsService.readBool(dayStr,  "is_today", false)) throw new AssertionError("is_today=\"true\" 应判真");
        if (!CreditsService.readBool(dayNone, "is_today", true))  throw new AssertionError("缺失时应回落 fallback=true");
        if (CreditsService.readBool(dayNone, "is_today", false))  throw new AssertionError("缺失时应回落 fallback=false");
        if (!CreditsService.readBool(new JSONObject("{\"is_today\":\"\"}"), "is_today", true))
            throw new AssertionError("空串应回落 fallback");
        System.out.println("is_today 宽容读取: bool/1/0/字符串/缺失 全部正确");

        // 端到端：扁平形状必须算出「今日已领 + 连续天数」，而不是报字段缺失
        JSONObject realPanel = new JSONObject(
            "{\"scene\":1,\"days\":["
            + "{\"day_no\":1,\"points\":10,\"status\":3,\"is_today\":false},"
            + "{\"day_no\":2,\"points\":20,\"status\":3,\"is_today\":true}]}");
        CreditsService.Checkin ck = CreditsService.minimaxPanelToStatus(realPanel);
        if (ck.error != null) throw new AssertionError("面板映射报错: " + ck.error);
        if (!ck.todayCheckedIn) throw new AssertionError("status=3 应判为今日已领");
        if (ck.streakDays != 2) throw new AssertionError("连续天数应为 2，实际 " + ck.streakDays);
        System.out.println("签到面板-端到端: active=" + ck.active + " todayCheckedIn=" + ck.todayCheckedIn
                + " streak=" + ck.streakDays + " dailyCredit=" + ck.dailyCredit);

        // 数字形态的 is_today 也必须能定位到「今日」，否则面板会静默变空
        JSONObject numPanel = new JSONObject(
            "{\"scene\":1,\"days\":["
            + "{\"day_no\":1,\"points\":10,\"status\":3,\"is_today\":0},"
            + "{\"day_no\":2,\"points\":20,\"status\":3,\"is_today\":1}]}");
        CreditsService.Checkin ck2 = CreditsService.minimaxPanelToStatus(numPanel);
        if (ck2.error != null) throw new AssertionError("数字 is_today 面板报错: " + ck2.error);
        if (ck2.streakDays != 2) throw new AssertionError("数字 is_today 连续天数应为 2，实际 " + ck2.streakDays);
        System.out.println("签到面板-数字 is_today: streak=" + ck2.streakDays + " todayCheckedIn=" + ck2.todayCheckedIn);

        // ── OpenCode 适配器（回归：形状门禁与错误分类都极易写错） ──
        opencodeCases();

        System.out.println("REG PASS");
    }

    private static void opencodeCases() throws Exception {
        // ① session id 形状：ses_ + 12 hex + 14 base62 = 尾段 26。
        //    写成随机 26 位会产出 ses_+38 字符，匿名通道一律 403 FreeTierError。
        for (int i = 0; i < 200; i++) {
            String sid = OpencodeProvider.deriveSessionId();
            if (!sid.matches("ses_[0-9a-f]{12}[0-9A-Za-z]{14}")) {
                throw new AssertionError("session id 形状错: " + sid);
            }
        }
        System.out.println("OpenCode-session id: 形状 ses_+12hex+14base62 正确（200 次）");

        // ② request id：req_ + 32 hex
        String rid = OpencodeProvider.deriveRequestId();
        if (!rid.matches("req_[0-9a-f]{32}")) throw new AssertionError("request id 形状错: " + rid);

        // ③ project id：40 位小写 hex，且同身份稳定、换代次即变
        String p1 = OpencodeProvider.deriveProjectId("sk-abc", 0);
        String p2 = OpencodeProvider.deriveProjectId("sk-abc", 0);
        String p3 = OpencodeProvider.deriveProjectId("sk-abc", 1);
        if (!p1.matches("[0-9a-f]{40}")) throw new AssertionError("project id 形状错: " + p1);
        if (!p1.equals(p2)) throw new AssertionError("project id 对同身份不稳定");
        if (p1.equals(p3)) throw new AssertionError("换代次后 project id 应变化");
        System.out.println("OpenCode-指纹: project id 稳定且随代次变化");

        // ④ 形状门禁：必须补 bash/read，且只在原本无工具时补 tool_choice
        JSONObject bare = new JSONObject("{\"model\":\"m\",\"messages\":[]}");
        OpencodeProvider.ensureFreeLaneShape(bare);
        JSONObject bt = bare.getJSONArray("tools").getJSONObject(0).getJSONObject("function");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < bare.getJSONArray("tools").length(); i++) {
            names.add(bare.getJSONArray("tools").getJSONObject(i).getJSONObject("function").getString("name"));
        }
        if (!names.contains("bash") || !names.contains("read")) {
            throw new AssertionError("门禁工具未补齐: " + names);
        }
        if (!"none".equals(bare.optString("tool_choice"))) {
            throw new AssertionError("原本无工具时应补 tool_choice=none");
        }
        if (!bt.getString("description").contains("do not call")) {
            throw new AssertionError("门禁工具描述不合规");
        }

        // 用户自带工具时：不得注入 tool_choice（否则模型无法调用任何工具）
        JSONObject withTools = new JSONObject(
            "{\"messages\":[],\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"bash\"}}],"
            + "\"tool_choice\":\"auto\"}");
        OpencodeProvider.ensureFreeLaneShape(withTools);
        if (!"auto".equals(withTools.optString("tool_choice"))) {
            throw new AssertionError("已有工具时覆盖了用户的 tool_choice");
        }
        java.util.Set<String> n2 = new java.util.HashSet<>();
        for (int i = 0; i < withTools.getJSONArray("tools").length(); i++) {
            n2.add(withTools.getJSONArray("tools").getJSONObject(i).getJSONObject("function").getString("name"));
        }
        if (!n2.contains("read")) throw new AssertionError("已有 bash 时仍应补 read: " + n2);
        System.out.println("OpenCode-门禁: 补 bash/read，且不覆盖用户 tool_choice");

        // ⑤ body：stream 恒 true（门禁的一半要求）
        JSONObject payload = OpencodeProvider.buildPayload(
            new JSONObject("{\"model\":\"m\",\"messages\":[],\"stream\":false}"), "big-pickle");
        if (!payload.optBoolean("stream", false)) throw new AssertionError("stream 必须恒为 true");
        if (!"big-pickle".equals(payload.getString("model"))) throw new AssertionError("model 未透传");
        System.out.println("OpenCode-body: stream 恒 true，model 透传");

        // ⑥ 错误分类：顺序即优先级，额度语义必须在 auth 之前
        checkKind(429, "{\"error\":\"too many requests\"}", OpencodeProvider.KIND_RATE_LIMIT);
        checkKind(401, "{\"error\":\"FreeUsageLimitError\"}", OpencodeProvider.KIND_FREE_USAGE_LIMIT);
        checkKind(403, "{\"error\":\"GoUsageLimitError\"}", OpencodeProvider.KIND_GO_USAGE_LIMIT);
        checkKind(403, "{\"error\":\"FreeTierError\"}", OpencodeProvider.KIND_FREE_TIER);
        checkKind(402, "{\"error\":{\"type\":\"server_error\",\"message\":\"Insufficient account funds\"}}",
                OpencodeProvider.KIND_QUOTA);
        checkKind(401, "{\"error\":\"unauthorized\"}", OpencodeProvider.KIND_AUTH);
        checkKind(500, "{\"error\":\"boom\"}", OpencodeProvider.KIND_SERVER);
        checkKind(0, "", OpencodeProvider.KIND_TRANSPORT);
        System.out.println("OpenCode-错误分类: 额度/形状/余额/auth/server/transport 全部正确");

        // ⑦ retry-after：0 是合法值（「立即解除」），必须与「未给」区分
        if (OpencodeProvider.parseRetryAfterMs(null) != null) throw new AssertionError("缺头应返回 null");
        if (OpencodeProvider.parseRetryAfterMs("") != null) throw new AssertionError("空串应返回 null");
        Long zero = OpencodeProvider.parseRetryAfterMs("0");
        if (zero == null || zero != 0L) throw new AssertionError("0 必须解析成 0 而不是 null");
        Long sixty = OpencodeProvider.parseRetryAfterMs("60");
        if (sixty == null || sixty != 60000L) throw new AssertionError("60 应解析成 60000ms");
        System.out.println("OpenCode-retry-after: 0 与「未给」正确区分");

        // ⑧ 模型表：表内/免费判定；表外一律不可达也不免费
        if (!OpencodeProvider.isFree("big-pickle")) throw new AssertionError("big-pickle 应免费");
        if (OpencodeProvider.isFree("deepseek-v4-flash")) throw new AssertionError("付费模型不应判为免费");
        if (OpencodeProvider.isFree("claude-sonnet-4")) throw new AssertionError("表外模型不应判为免费");
        if (OpencodeProvider.isReachable("claude-sonnet-4")) throw new AssertionError("表外模型不应可达");
        if (!OpencodeProvider.displayName("big-pickle").contains("免费")) {
            throw new AssertionError("免费模型展示名应带「（免费）」");
        }
        System.out.println("OpenCode-模型表: 免费/可达判定与展示名正确");

        // ⑨ 非流式聚合：标准 OpenAI SSE → 标准非流式响应
        String sse = "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
        JSONObject agg = OpencodeProvider.aggregate("big-pickle", sse);
        String got = agg.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content");
        if (!"你好".equals(got)) throw new AssertionError("聚合内容错: " + got);
        if (!"c1".equals(agg.optString("id"))) throw new AssertionError("聚合 id 未取自首个分片");
        System.out.println("OpenCode-聚合: SSE → 非流式，content=你好");

        // ⑩ 请求头：只发 5 个指纹头，绝不发 x-session-affinity / X-Session-Id
        OpencodeProvider.Credential c = new OpencodeProvider.Credential();
        c.apiKey = OpencodeProvider.ANONYMOUS_KEY;
        java.util.Map<String, String> h = OpencodeProvider.headers(c, "ses_" + "0".repeat(26), rid);
        String[] must = {"x-opencode-project", "x-opencode-session", "x-opencode-request",
                "x-opencode-client", "user-agent"};
        for (String k : must) {
            if (!h.containsKey(k)) throw new AssertionError("缺指纹头: " + k);
        }
        if (!"cli".equals(h.get("x-opencode-client"))) throw new AssertionError("x-opencode-client 应为 cli");
        if (!("Bearer " + OpencodeProvider.ANONYMOUS_KEY).equals(h.get("authorization"))) {
            throw new AssertionError("匿名槽授权头应为 Bearer public");
        }
        for (String k : h.keySet()) {
            String lk = k.toLowerCase();
            if (lk.contains("session-affinity") || lk.equals("x-session-id")) {
                throw new AssertionError("不该发送的头: " + k);
            }
        }
        if (!c.isAnonymous()) throw new AssertionError("public 应判定为匿名槽");
        System.out.println("OpenCode-请求头: 5 个指纹头齐备，无多余会话头");
    }

    private static void checkKind(int status, String body, String expected) {
        OpencodeProvider.ErrorInfo info = OpencodeProvider.classifyError(status, body, null);
        if (!expected.equals(info.kind)) {
            throw new AssertionError("status=" + status + " 应归类为 " + expected + "，实际 " + info.kind);
        }
    }
}
