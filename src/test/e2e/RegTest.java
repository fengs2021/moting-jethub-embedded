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
        System.out.println("REG PASS");
    }
}
