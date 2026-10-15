import jet.core.*;
import org.json.*;

/**
 * 倍率解析 + 模型开关表 + 模型管理端点的 host 测试。
 * 不依赖任何上游凭据：ModelRate 是纯函数，ModelRegistry 用临时文件，
 * 网关端点用内存目录（未登录时 catalogAll 各家会跳过，只验兜底段与开关语义）。
 */
public class ModelMgmtTest {

    static int pass = 0, fail = 0;

    static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.println("  PASS  " + name); }
        else { fail++; System.out.println("  FAIL  " + name + "  → " + detail); }
    }

    static void eq(String name, Object expect, Object actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        check(name, ok, "expect=" + expect + " actual=" + actual);
    }

    public static void main(String[] args) throws Exception {
        System.out.println("── ModelRate：Buddy/WorkBuddy credits ──");
        eq("x0.29 credits", "x0.29", ModelRate.fromCredits("x0.29 credits"));
        eq("x0.00 credits", "x0", ModelRate.fromCredits("x0.00 credits"));
        eq("x1.62", "x1.62", ModelRate.fromCredits("x1.62"));
        eq("0.50x（后缀写法）", "x0.5", ModelRate.fromCredits("0.50x"));
        eq("空串=无倍率", null, ModelRate.fromCredits(""));
        eq("null=无倍率", null, ModelRate.fromCredits(null));
        eq("auto 的 credits 缺失", null, ModelRate.fromCredits(null));
        eq("垃圾文本不回退成 x1", null, ModelRate.fromCredits("credits"));
        // ⚠️ 倍率 0 是合法值（免费），不能当成「没有倍率」
        eq("x0 必须保留", "x0", ModelRate.fromCredits("x0.00"));

        System.out.println("── ModelRate：Trae display_contact_config（字符串，需二次 parse）──");
        JSONObject t1 = new JSONObject()
                .put("display_contact_config", "{\"consumption_rate\":{\"enable\":true,\"data\":{\"rate\":0.08}}}");
        eq("rate 0.08", "x0.08", ModelRate.fromTraeEntry(t1));
        JSONObject t2 = new JSONObject()
                .put("display_contact_config", "{\"consumption_rate\":{\"enable\":false,\"data\":{\"rate\":0.5}}}");
        eq("enable=false 视为无倍率", null, ModelRate.fromTraeEntry(t2));
        JSONObject t3 = new JSONObject()
                .put("display_contact_config", "{\"consumption_rate\":{\"enable\":true,\"data\":{\"rate\":0}}}");
        eq("rate 0 = 免费（不能过滤）", "x0→免费", ModelRate.fromTraeEntry(t3));
        JSONObject t4 = new JSONObject().put("display_contact_config", "not json");
        eq("坏 JSON 不抛异常", null, ModelRate.fromTraeEntry(t4));
        eq("字段缺失", null, ModelRate.fromTraeEntry(new JSONObject()));
        // 折扣：before 严格大于 after 才算降价
        JSONObject t5 = new JSONObject().put("display_contact_config",
                "{\"consumption_rate\":{\"enable\":true,\"data\":{\"rate\":0.1}},"
                        + "\"activity_discount\":{\"data\":{\"current\":{\"before_consumption_rate\":0.2}}}}");
        eq("折扣 before→after", "x0.2→x0.1", ModelRate.fromTraeEntry(t5));
        JSONObject t6 = new JSONObject().put("display_contact_config",
                "{\"consumption_rate\":{\"enable\":true,\"data\":{\"rate\":0.2}},"
                        + "\"activity_discount\":{\"data\":{\"current\":{\"before_consumption_rate\":0.2}}}}");
        eq("before==after 不算降价", "x0.2", ModelRate.fromTraeEntry(t6));
        // 也兼容字段已是对象的形态（部分接口直接给对象）
        JSONObject t7 = new JSONObject().put("display_contact_config",
                new JSONObject().put("consumption_rate",
                        new JSONObject().put("enable", true)
                                .put("data", new JSONObject().put("rate", 1.5))));
        eq("对象形态也能解析", "x1.5", ModelRate.fromTraeEntry(t7));

        System.out.println("── ModelRate：Cline 免费判定 ──");
        eq(":free 结尾", true, ModelRate.clineIsFree("mimo-v2.6-flash:free", null));
        eq("cline-free/ 前缀", true, ModelRate.clineIsFree("cline-free/x", null));
        eq("isFree 显式", true, ModelRate.clineIsFree("x", new JSONObject().put("isFree", true)));
        eq("普通模型", false, ModelRate.clineIsFree("deepseek-v4.1-flash", null));
        eq("免费拼后缀", "MiMo-V2.6-Flash · 免费", ModelRate.clineDisplayName("MiMo-V2.6-Flash", true));
        eq("非免费不加后缀", "Hy3", ModelRate.clineDisplayName("Hy3", false));

        System.out.println("── 展示名合成（幂等）──");
        eq("普通拼接", "Hy3 · x0.29", ModelRate.displayName("Hy3", "x0.29"));
        eq("无后缀", "Hy3", ModelRate.displayName("Hy3", null));
        eq("空后缀", "Hy3", ModelRate.displayName("Hy3", "  "));
        eq("裸名空返回 null", null, ModelRate.displayName("", "x1"));
        // 上游（如 jethub 主机网关）已经把倍率拼进 name 时不能拼第二次
        eq("已含倍率不重复拼", "Hy3 · x0.29", ModelRate.displayName("Hy3 · x0.29", "x0.29"));

        System.out.println("── ModelRegistry：黑名单语义 ──");
        java.io.File tmp = java.io.File.createTempFile("jethub-models", ".json");
        tmp.delete();
        CredentialStore.FileBased store = new CredentialStore.FileBased(
                new java.io.File(tmp.getParentFile(), tmp.getName() + ".store.json"));
        ModelRegistry reg = new ModelRegistry(store);
        eq("初始为空", 0, reg.disabled("buddy").size());
        eq("表里没有=可用", false, reg.isDisabled("buddy", "buddy/hy3"));
        reg.setDisabled("buddy", "buddy/hy3", true);
        eq("关闭后生效", true, reg.isDisabled("buddy", "buddy/hy3"));
        eq("只影响该供应商", false, reg.isDisabled("trae", "buddy/hy3"));
        reg.setDisabled("buddy", "buddy/hy3", false);
        eq("重新打开", false, reg.isDisabled("buddy", "buddy/hy3"));
        java.util.List<String> many = new java.util.ArrayList<>();
        many.add("buddy/a"); many.add("buddy/b"); many.add("");
        reg.setDisabledMany("buddy", many, true);
        eq("批量关闭（空串被忽略）", 2, reg.disabled("buddy").size());
        reg.clear("buddy");
        eq("重置清空", 0, reg.disabled("buddy").size());
        // 持久化：新实例读同一文件
        ModelRegistry reg2 = new ModelRegistry(new CredentialStore.FileBased(
                new java.io.File(tmp.getParentFile(), tmp.getName() + ".store.json")));
        reg2.setDisabled("trae", "trae/x", true);
        ModelRegistry reg3 = new ModelRegistry(new CredentialStore.FileBased(
                new java.io.File(tmp.getParentFile(), tmp.getName() + ".store.json")));
        eq("跨实例持久化", true, reg3.isDisabled("trae", "trae/x"));
        // 损坏文件不致命
        store.set("JETHUB_DISABLED_MODELS", "{not json");
        eq("损坏文件视为空表", 0, new ModelRegistry(store).disabled("buddy").size());

        System.out.println("── 网关端点：/jethub/models + 开关动作 ──");
        java.io.File f = java.io.File.createTempFile("jethub-gw", ".json");
        f.delete();
        CredentialStore.FileBased gs = new CredentialStore.FileBased(
                new java.io.File(f.getParentFile(), f.getName() + ".store.json"));
        AccountManager am = new AccountManager(gs);
        GatewayServer gw = new GatewayServer(am);
        gw.start();
        String base = "http://127.0.0.1:" + GatewayServer.port();
        try {
            // 无凭据时各家跳过，只剩 Cline 兜底 3 条（它们现在必须带 name）
            JSONObject models = new JSONObject(HttpFetcher.get(base + "/jethub/models", null, 10000).readText());
            JSONArray groups = models.optJSONArray("groups");
            check("groups 是数组", groups != null, String.valueOf(models));
            JSONObject clineGroup = null;
            for (int i = 0; groups != null && i < groups.length(); i++) {
                if ("cline".equals(groups.optJSONObject(i).optString("provider"))) clineGroup = groups.optJSONObject(i);
            }
            check("含 cline 分组", clineGroup != null, "groups=" + groups);
            if (clineGroup != null) {
                check("cline 分组有模型", clineGroup.optInt("count", 0) >= 3, clineGroup.toString());
                JSONArray arr = clineGroup.optJSONArray("models");
                boolean allNamed = true, freeMarked = false;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i);
                    String nm = m.optString("name", "");
                    if (nm.isEmpty() || nm.startsWith("model_")) allNamed = false;
                    if (nm.contains("免费")) freeMarked = true;
                }
                check("兜底模型都带展示名", allNamed, arr.toString());
                check("免费模型带「· 免费」", freeMarked, arr.toString());
                eq("分组 label 用中文名", "Cline", clineGroup.optString("label"));
            }
            // 关掉一个 cline 模型 → /v1/models 里消失，/jethub/models 里 enabled=false
            String victim = "cline/stealth/space-bunny-alpha";
            String key = gw.localKey();
            JSONObject req = new JSONObject().put("provider", "cline").put("modelId", victim).put("enabled", false);
            java.util.Map<String, String> h = new java.util.HashMap<>();
            h.put("Content-Type", "application/json");
            JSONObject r = new JSONObject(HttpFetcher.postJson(base + "/jethub/models/set", h, req.toString(), 10000).readText());
            eq("set 返回 ok", true, r.optBoolean("ok"));

            JSONObject after = new JSONObject(HttpFetcher.get(base + "/jethub/models", null, 10000).readText());
            boolean stillListed = false, markedOff = false;
            JSONArray g2 = after.optJSONArray("groups");
            for (int i = 0; i < g2.length(); i++) {
                JSONObject g = g2.optJSONObject(i);
                if (!"cline".equals(g.optString("provider"))) continue;
                JSONArray arr = g.optJSONArray("models");
                for (int j = 0; j < arr.length(); j++) {
                    JSONObject m = arr.optJSONObject(j);
                    if (victim.equals(m.optString("id"))) { stillListed = true; markedOff = !m.optBoolean("enabled", true); }
                }
            }
            check("设置页仍显示已关闭模型", stillListed, after.toString());
            check("已关闭模型标记 enabled=false", markedOff, after.toString());

            java.util.Map<String, String> auth = new java.util.HashMap<>();
            auth.put("Authorization", "Bearer " + key);
            JSONObject served = new JSONObject(HttpFetcher.get(base + "/v1/models", auth, 10000).readText());
            JSONArray data = served.optJSONArray("data");
            boolean gone = true;
            for (int i = 0; i < data.length(); i++) {
                if (victim.equals(data.optJSONObject(i).optString("id"))) gone = false;
            }
            check("/v1/models 已剔除关闭的模型", gone, served.toString());

            // 重新打开 → 回到 /v1/models
            req.put("enabled", true);
            HttpFetcher.postJson(base + "/jethub/models/set", h, req.toString(), 10000);
            JSONObject back = new JSONObject(HttpFetcher.get(base + "/v1/models", auth, 10000).readText());
            boolean returned = false;
            JSONArray d2 = back.optJSONArray("data");
            for (int i = 0; i < d2.length(); i++) {
                if (victim.equals(d2.optJSONObject(i).optString("id"))) returned = true;
            }
            check("重新打开后回到 /v1/models", returned, back.toString());

            // 批量关闭 + reset
            JSONArray ids = new JSONArray().put(victim).put("cline/cline-free/mimo-v2.6-flash");
            JSONObject manyReq = new JSONObject().put("provider", "cline").put("modelIds", ids).put("enabled", false);
            JSONObject mr = new JSONObject(HttpFetcher.postJson(base + "/jethub/models/set-many", h, manyReq.toString(), 10000).readText());
            eq("set-many 计数", 2, mr.optInt("count"));
            eq("set-many 后关闭数", 2, mr.optJSONArray("disabled").length());
            JSONObject rr = new JSONObject(HttpFetcher.postJson(base + "/jethub/models/reset", h, new JSONObject().put("provider", "cline").toString(), 10000).readText());
            eq("reset 后清空", 0, rr.optJSONArray("disabled").length());

            JSONObject bad = new JSONObject(HttpFetcher.postJson(base + "/jethub/models/set", h, new JSONObject().put("provider", "").toString(), 10000).readText());
            eq("缺 provider 报错", false, bad.optBoolean("ok"));
        } finally {
            gw.stop();
        }

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        System.out.println(fail == 0 ? "MODEL-MGMT PASS" : "MODEL-MGMT FAIL");
        if (fail != 0) System.exit(1);
    }
}
