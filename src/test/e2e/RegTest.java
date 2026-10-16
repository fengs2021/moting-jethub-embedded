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

        System.out.println("REG PASS");
    }
}
