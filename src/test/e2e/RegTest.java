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

        // ── CodeArts 适配器（回归：签名 / 错误分类 / 模型归一化都极易写错） ──
        codeartsCases();

        System.out.println("REG PASS");
    }

    /**
     * CodeArts（华为云）适配器回归。
     *
     * <p>这里锁的都是「写错后症状极隐蔽」的点：签名 canonical 的字段顺序、
     * {@code 429} 的锚定匹配、日期后缀归一化、benefit 记录的「未改写才记」规则。
     */
    private static void codeartsCases() throws Exception {
        // ① 模型 id 归一化：末尾 -NNNN 日期后缀必须剥离（chat 端点只认无后缀 id）
        if (!"deepseek-v4-flash".equals(CodeartsProvider.normalizeModelId("deepseek-v4-flash-0731"))) {
            throw new AssertionError("日期后缀未剥离: " + CodeartsProvider.normalizeModelId("deepseek-v4-flash-0731"));
        }
        if (!"deepseek-v4.1-flash".equals(CodeartsProvider.normalizeModelId("deepseek-v4.1-flash"))) {
            throw new AssertionError("无后缀 id 不应被改写");
        }
        if (!"glm-5.3-flash".equals(CodeartsProvider.normalizeModelId("glm-5.3-flash"))) {
            throw new AssertionError("glm-5.3-flash 不应被改写");
        }
        // 只有 4 位数字才算日期后缀：5 位、非数字都不能剥
        if (!"model-12345".equals(CodeartsProvider.normalizeModelId("model-12345"))) {
            throw new AssertionError("5 位数字不应被当日期后缀");
        }
        if (!"model-abc".equals(CodeartsProvider.normalizeModelId("model-abc"))) {
            throw new AssertionError("非数字后缀不应被剥离");
        }
        System.out.println("CodeArts-模型归一化: -NNNN 后缀剥离正确（含边界）");

        // ② 视觉模型过滤：-VL- 中缀与 -VL 后缀
        if (!CodeartsProvider.isVisionModel("Qwen2.5-VL-72B")) throw new AssertionError("-VL- 应判为视觉模型");
        if (!CodeartsProvider.isVisionModel("glm-4v-VL")) throw new AssertionError("-VL 后缀应判为视觉模型");
        if (CodeartsProvider.isVisionModel("deepseek-v4-flash")) throw new AssertionError("普通模型不应判为视觉");
        System.out.println("CodeArts-视觉过滤: -VL-/-VL 正确识别");

        // ③ 排队码：429 必须锚定为独立数字。
        //    ⚠️ 额度码 InferHub.4291.200 里的 4291 曾被子串 429 命中，
        //    导致误判为排队 → 静默重试 30 分钟、界面零输出（真实报障）。
        if (!CodeartsProvider.isQueueErrorCode("InferHub.ModelArts.81111.429")) {
            throw new AssertionError("81111.429 应判为排队");
        }
        if (!CodeartsProvider.isQueueErrorCode("TM.00001041")) throw new AssertionError("TM.00001041 应判为排队");
        if (CodeartsProvider.isQueueErrorCode("InferHub.4291.200")) {
            throw new AssertionError("额度码 4291 绝不能被 429 子串命中成排队");
        }
        if (!CodeartsProvider.isQueueErrorCode("rate limit exceeded")) {
            throw new AssertionError("rate limit 应判为排队");
        }
        if (!CodeartsProvider.isQueueErrorCode("当前排队中，请稍候")) throw new AssertionError("中文「排队」应判为排队");
        // 独立数字 429 本身仍要命中
        if (!CodeartsProvider.isQueueErrorCode("HTTP 429 Too Many Requests")) {
            throw new AssertionError("独立 429 应判为排队");
        }
        System.out.println("CodeArts-排队码: 429 锚定为独立数字（4291 不误判）");

        // ④ 额度耗尽：不可重试，且必须与排队分支分开
        if (!CodeartsProvider.isQuotaExhaustedCode("InferHub.4291.200", "insufficient quota")) {
            throw new AssertionError("4291 应判为额度耗尽");
        }
        if (!CodeartsProvider.isQuotaExhaustedCode("InferHub.4291.999", "")) {
            throw new AssertionError("4291 其它尾号应同样判为额度耗尽（上游可能改尾号）");
        }
        if (!CodeartsProvider.isQuotaExhaustedCode("", "Insufficient_Quota")) {
            throw new AssertionError("文案兜底 insufficient quota 不可省");
        }
        if (CodeartsProvider.isQuotaExhaustedCode("TM.00001041", "peak usage")) {
            throw new AssertionError("排队码不应被判成额度耗尽");
        }
        System.out.println("CodeArts-额度: 4291/文案兜底命中，且不与排队混淆");

        // ⑤ 错误分类：额度语义必须先于 auth（额度错误常带 401/403/429）
        checkCodeartsKind(200, "{\"error_code\":\"InferHub.4291.200\",\"error_msg\":\"insufficient quota\"}",
                CodeartsProvider.KIND_QUOTA);
        checkCodeartsKind(200, "{\"error_code\":\"InferHub.ModelArts.81111.429\"}", CodeartsProvider.KIND_QUEUE);
        checkCodeartsKind(200, "{\"error_code\":\"TM.00001041\",\"error_msg\":\"并发会话数已达上限\"}",
                CodeartsProvider.KIND_QUEUE);
        checkCodeartsKind(401, "{\"error_code\":\"APIG.0602\",\"error_msg\":\"Invalid token\"}",
                CodeartsProvider.KIND_AUTH);
        checkCodeartsKind(500, "{\"error_msg\":\"boom\"}", CodeartsProvider.KIND_SERVER);
        checkCodeartsKind(0, "", CodeartsProvider.KIND_TRANSPORT);
        System.out.println("CodeArts-错误分类: 额度/排队/auth/server/transport 顺序正确");

        // ⑥ 鉴权文本判据
        if (!CodeartsProvider.isAuthErrorText("{\"error_code\":\"APIG.0602\"}")) {
            throw new AssertionError("APIG.0602 应判为鉴权失败");
        }
        if (!CodeartsProvider.isAuthErrorText("token expired")) throw new AssertionError("token expired 应判鉴权");
        if (CodeartsProvider.isAuthErrorText("insufficient quota")) {
            throw new AssertionError("额度文案不应判为鉴权失败");
        }
        if (!CodeartsProvider.isAuthError(401) || !CodeartsProvider.isAuthError(403)) {
            throw new AssertionError("401/403 应判为鉴权失败");
        }
        System.out.println("CodeArts-鉴权: 401/403 与文本判据正确");

        // ⑦ benefit not found：去掉 maas_type 头重试一次即可
        if (!CodeartsProvider.isBenefitNotFound("data:{\"error_code\":\"InferHub.4004.200\"}")) {
            throw new AssertionError("InferHub.4004.200 应识别为 benefit 缺失");
        }
        if (CodeartsProvider.isBenefitNotFound("InferHub.4291.200")) {
            throw new AssertionError("额度码不应识别为 benefit 缺失");
        }
        System.out.println("CodeArts-benefit: InferHub.4004.200 正确识别");

        // ⑧ SSE 内嵌错误：HTTP 200 + error_code 是 CodeArts 的常见形态，
        //    只看状态码会让界面停在「运行中」零输出
        if (!"InferHub.4291.200".equals(CodeartsProvider.sseErrorCode(
                "{\"error_code\":\"InferHub.4291.200\",\"error_msg\":\"insufficient quota\"}"))) {
            throw new AssertionError("SSE error_code 未抽出");
        }
        if (CodeartsProvider.sseErrorCode("{\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}") != null) {
            throw new AssertionError("正常分片不应抽出 error_code");
        }
        String sseText = CodeartsProvider.sseErrorText(
                "{\"error_code\":\"InferHub.4291.200\",\"error_msg\":\"insufficient quota\"}");
        if (!sseText.contains("4291") || !sseText.contains("insufficient quota")) {
            throw new AssertionError("SSE 错误文本应含码与文案: " + sseText);
        }
        // OpenAI 风格 error 对象也要能抽
        if (!"invalid_request".equals(CodeartsProvider.sseErrorCode(
                "{\"error\":{\"code\":\"invalid_request\",\"message\":\"bad\"}}"))) {
            throw new AssertionError("OpenAI 风格 error.code 未抽出");
        }
        System.out.println("CodeArts-SSE 错误: error_code/error 两种形态均可抽出");

        // ⑨ body：stream 恒 true、prompt_cache_key=sessionId、tool_stream、
        //    max_tokens 默认 65536（131072 实测触发空流）、thinking 顶层且只在关思考时补
        JSONObject req = new JSONObject("{\"model\":\"m\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        JSONObject body = CodeartsProvider.buildPayload(req, "deepseek-v4-flash", "sess-1");
        if (!body.optBoolean("stream", false)) throw new AssertionError("stream 必须恒为 true");
        if (!"sess-1".equals(body.optString("prompt_cache_key"))) {
            throw new AssertionError("prompt_cache_key 应等于 sessionId");
        }
        if (!body.optBoolean("tool_stream", false)) throw new AssertionError("tool_stream 应为 true");
        if (body.optInt("max_tokens", 0) != 65536) throw new AssertionError("max_tokens 默认应为 65536");
        if (!body.has("include")) throw new AssertionError("应带 include reasoning.encrypted_content");
        if (!"auto".equals(body.optString("reasoning_summary"))) throw new AssertionError("reasoning_summary 应为 auto");
        if (body.has("thinking")) throw new AssertionError("未显式关思考时不应发 thinking");

        // 显式关思考：顶层 thinking:{type:disabled}，且 reasoning_effort 要删掉
        JSONObject off = CodeartsProvider.buildPayload(
                new JSONObject("{\"messages\":[],\"reasoning_effort\":\"none\"}"), "m", "s");
        JSONObject th = off.optJSONObject("thinking");
        if (th == null || !"disabled".equals(th.optString("type"))) {
            throw new AssertionError("关思考时应发顶层 thinking:{type:'disabled'}");
        }
        if (off.has("reasoning_effort")) throw new AssertionError("reasoning_effort 应被移除（后端不认）");

        // 调用方自带 max_tokens 时不得覆盖
        JSONObject custom = CodeartsProvider.buildPayload(
                new JSONObject("{\"messages\":[],\"max_tokens\":4096}"), "m", "s");
        if (custom.optInt("max_tokens", 0) != 4096) throw new AssertionError("不应覆盖调用方的 max_tokens");
        System.out.println("CodeArts-body: stream/cache/工具流/max_tokens/thinking 全部正确");

        // ⑩ session id 稳定推导：同一会话的「首条 user 消息」恒定 ⇒ 缓存命中
        String s1 = CodeartsProvider.sessionIdFor(
                new JSONObject("{\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},"
                        + "{\"role\":\"user\",\"content\":\"你好\"},{\"role\":\"assistant\",\"content\":\"a\"}]}"));
        String s2 = CodeartsProvider.sessionIdFor(
                new JSONObject("{\"messages\":[{\"role\":\"system\",\"content\":\"sys\"},"
                        + "{\"role\":\"user\",\"content\":\"你好\"},{\"role\":\"assistant\",\"content\":\"a\"},"
                        + "{\"role\":\"user\",\"content\":\"再来\"}]}"));
        if (s1 == null || s1.isEmpty()) throw new AssertionError("session id 不应为空");
        if (!s1.equals(s2)) {
            throw new AssertionError("同一会话（多轮追加）应推出同一个 session id: " + s1 + " vs " + s2);
        }
        String s3 = CodeartsProvider.sessionIdFor(
                new JSONObject("{\"messages\":[{\"role\":\"user\",\"content\":\"另一个会话\"}]}"));
        if (s1.equals(s3)) throw new AssertionError("不同会话不应推出同一个 session id");
        System.out.println("CodeArts-会话 id: 同会话稳定、跨会话变化");

        // ⑪ 额度文案：给出按 UTC+8 自然日结算的重置时间，且不写成「网关 bug」
        long now = System.currentTimeMillis();
        String msg = CodeartsProvider.quotaExhaustedMessage("deepseek-v4-flash", now);
        if (!msg.contains("deepseek-v4-flash")) throw new AssertionError("额度文案应含模型名: " + msg);
        if (!msg.contains("额度")) throw new AssertionError("额度文案应说明是额度问题: " + msg);
        if (!msg.contains("重置")) throw new AssertionError("额度文案应给出重置时间: " + msg);
        long reset = CodeartsProvider.nextUtc8DayStartMs(now);
        if (reset <= now) throw new AssertionError("重置时刻必须在未来");
        if (reset - now > 25 * 3600_000L) throw new AssertionError("重置时刻不应超过 25 小时（UTC+8 当日 24:00）");
        System.out.println("CodeArts-额度文案: 含模型名与重置时间（" + msg.replace("\n", " ") + "）");

        // ⑫ 非流式聚合：CodeArts 是标准 OpenAI SSE，可直接聚合
        String caSse = "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"你\"}}]}\n\n"
                + "data: {\"id\":\"c1\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"好\"}}]}\n\n"
                + "data: [DONE]\n\n";
        JSONObject agg = CodeartsProvider.aggregateSse("deepseek-v4-flash", caSse);
        String content = agg.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").optString("content", "");
        if (!"你好".equals(content)) throw new AssertionError("SSE 聚合内容错: " + content);
        System.out.println("CodeArts-SSE 聚合: content=" + content);

        // ⑬ 聚合遇 SSE 内嵌额度错误必须抛「不可重试」异常，而不是返回半截正文
        boolean threw = false;
        try {
            CodeartsProvider.aggregateSse("m",
                    "data: {\"error_code\":\"InferHub.4291.200\",\"error_msg\":\"insufficient quota\"}\n\n");
        } catch (CodeartsProvider.QuotaExhaustedException e) {
            threw = true;
        }
        if (!threw) throw new AssertionError("SSE 内嵌额度错误应抛 QuotaExhaustedException");
        System.out.println("CodeArts-SSE 聚合: 内嵌额度错误正确抛不可重试异常");

        // ⑭ 签名 canonical：字段顺序与 signedHeaders 排序是协议硬要求，
        //    写错只会在真实请求上表现为 APIG.0301 verify ak sk signature fail
        java.util.Map<String, String> h = CodeartsProvider.signRequestHuawei(
                "AKTEST", "SKTEST", "STTEST", "GET",
                "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin", new byte[0], null);
        String auth = h.get("Authorization");
        if (auth == null || !auth.startsWith("SDK-HMAC-SHA256 Access=AKTEST,")) {
            throw new AssertionError("Authorization 头形态错: " + auth);
        }
        if (!auth.contains("SignedHeaders=") || !auth.contains("Signature=")) {
            throw new AssertionError("Authorization 缺少 SignedHeaders/Signature: " + auth);
        }
        if (!h.containsKey("x-sdk-date") || !h.containsKey("x-sdk-content-sha256")) {
            throw new AssertionError("缺少 x-sdk-date / x-sdk-content-sha256 头");
        }
        if (!h.containsKey("host")) throw new AssertionError("签名结果应含 host（发送时跳过）");
        // 签名头名单必须排序且包含 host / x-sdk-date / x-security-token
        String sh = auth.substring(auth.indexOf("SignedHeaders=") + "SignedHeaders=".length(),
                auth.indexOf(",Signature="));
        String[] names = sh.split(";");
        for (int i = 1; i < names.length; i++) {
            if (names[i - 1].compareTo(names[i]) > 0) {
                throw new AssertionError("SignedHeaders 未排序: " + sh);
            }
        }
        if (!sh.contains("host") || !sh.contains("x-sdk-date") || !sh.contains("x-security-token")) {
            throw new AssertionError("SignedHeaders 缺少必需头: " + sh);
        }
        // GET 不带 content-type（协议规定只在非 GET 时加）
        if (sh.contains("content-type")) throw new AssertionError("GET 不应签 content-type");
        java.util.Map<String, String> h2 = CodeartsProvider.signRequestHuawei(
                "AKTEST", "SKTEST", "STTEST", "POST",
                "https://snap-access.cn-north-4.myhuaweicloud.com/api/v2/chat/completions",
                "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), null);
        String sh2 = h2.get("Authorization");
        sh2 = sh2.substring(sh2.indexOf("SignedHeaders=") + "SignedHeaders=".length(), sh2.indexOf(",Signature="));
        if (!sh2.contains("content-type")) throw new AssertionError("POST 必须签 content-type");
        // 同一输入必须得到同一签名（否则说明有时间/随机成分混进 canonical）
        java.util.Map<String, String> h3 = CodeartsProvider.signRequestHuawei(
                "AKTEST", "SKTEST", "STTEST", "GET",
                "https://snap-access.cn-north-4.myhuaweicloud.com/v1/model/builtin", new byte[0], null);
        // x-sdk-date 会变，故比较签名头名单与 payload 摘要
        if (!h.get("x-sdk-content-sha256").equals(h3.get("x-sdk-content-sha256"))) {
            throw new AssertionError("空 body 的 payload 摘要应稳定");
        }
        System.out.println("CodeArts-签名: canonical 排序/必需头/GET-POST 差异正确");

        // ⑮ benefit 判定：兜底集合必须包含实测的两个（它们决定是否带 maas_type 头）
        if (!CodeartsProvider.isBenefitModel("glm-5.3-flash")) {
            throw new AssertionError("glm-5.3-flash 应为 benefit 模型");
        }
        if (!CodeartsProvider.isBenefitModel("deepseek-v4.1-flash")) {
            throw new AssertionError("deepseek-v4.1-flash 应为 benefit 模型");
        }
        if (CodeartsProvider.isBenefitModel("deepseek-v4-pro")) {
            throw new AssertionError("deepseek-v4-pro 不应判为 benefit");
        }
        System.out.println("CodeArts-benefit 判定: 兜底集合正确");
    }

    private static void checkCodeartsKind(int status, String body, String expected) {
        CodeartsProvider.ErrorInfo info = CodeartsProvider.classifyError(status, body, null);
        if (!expected.equals(info.kind)) {
            throw new AssertionError("错误分类错: status=" + status + " body=" + body
                    + " 期望 " + expected + " 实际 " + info.kind);
        }
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
        // ⚠️ 2026-10-16 实测已从远端 /v1/models 下架的两条必须移除，否则用户会点到必然 404 的模型
        if (OpencodeProvider.isReachable("mimo-v2.5-free")) {
            throw new AssertionError("mimo-v2.5-free 已被远端下架，不应再暴露");
        }
        if (OpencodeProvider.isReachable("nemotron-3-ultra-free")) {
            throw new AssertionError("nemotron-3-ultra-free 已被远端下架，不应再暴露");
        }
        System.out.println("OpenCode-模型表: 免费/可达判定与展示名正确（含下架项已剔除）");

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
