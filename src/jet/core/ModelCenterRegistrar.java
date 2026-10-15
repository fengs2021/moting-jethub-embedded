package jet.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 把内嵌网关作为「中转账号」合并写入墨听 AI 模型中心。
 *
 * 通过墨听自己的 AiModelCenterBackup.exportJson / restoreJson 完成：
 * 导出现有快照 → org.json 合并（账号/模型/线路/默认用途，全部按 id 幂等）→ restoreJson 校验写回。
 * id 使用墨听 canonical 算法（stableId = prefix + "_" + sha256(parts 用 U+001F 连接)[:20]），
 * 保证用户日后点「获取模型」时原地刷新而非新建重复线路。
 */
public final class ModelCenterRegistrar {

    public static final String PROTOCOL = "OPENAI_COMPATIBLE";
    public static final String CAPABILITY = "TEXT_ANALYSIS";
    public static final String PURPOSE_GLOBAL = "GLOBAL_TEXT_DEFAULT";
    public static final String PURPOSE_CHAT = "ASSISTANT_CHAT";

    private ModelCenterRegistrar() { }

    /** @return null=成功，否则错误信息 */
    public static String register(Context context) {
        try {
            Class<?> backup = Class.forName("io.legado.app.help.audiobook.models.center.AiModelCenterBackup");
            Object instance = backup.getField("INSTANCE").get(null);
            Method export = backup.getMethod("exportJson", Context.class);
            Method restore = backup.getMethod("restoreJson", Context.class, String.class);

            String current = (String) export.invoke(instance, context);
            JSONObject merged = merge(current == null ? "{}" : current);
            restore.invoke(instance, context, merged.toString());
            return null;
        } catch (Throwable t) {
            return String.valueOf(t.getMessage() == null ? t : t.getMessage());
        }
    }

    public static JSONObject merge(String currentJson) throws Exception {
        JSONObject root = new JSONObject(currentJson);
        if (!root.has("schemaVersion")) root.put("schemaVersion", 3);

        String base = "http://127.0.0.1:" + GatewayServer.port() + "/v1";
        String key = store().get("JETHUB_LOCAL_KEY");
        if (key == null || key.isEmpty()) throw new IllegalStateException("网关未启动，请先打开本页");

        // 账号（canonical id：protocol + normalizedUrl + fingerprint(apiKey)）
        String accountId = stableId("account", PROTOCOL, normalizedUrl(base), fingerprint(key));
        JSONArray accounts = root.optJSONArray("accounts");
        if (accounts == null) { accounts = new JSONArray(); root.put("accounts", accounts); }
        if (!jsonArrayOfHas(accounts, "id", accountId)) {
            JSONObject acct = new JSONObject();
            acct.put("id", accountId);
            acct.put("name", "JetHub 内置");
            acct.put("baseUrl", base);
            acct.put("apiKey", key);
            acct.put("protocol", PROTOCOL);
            acct.put("sourceType", "DIRECT_API");
            acct.put("enabled", true);
            acct.put("protocolOptions", new JSONObject().put("center.domain", CAPABILITY));
            accounts.put(acct);
        }

        // 模型 + 线路
        JSONArray models = root.optJSONArray("models");
        if (models == null) { models = new JSONArray(); root.put("models", models); }
        JSONArray routes = root.optJSONArray("routes");
        if (routes == null) { routes = new JSONArray(); root.put("routes", routes); }
        Map<String, String> existingModels = indexBy(models, "id");
        Map<String, String> existingRoutes = indexBy(routes, "id");

        jet.core.GatewayServer gw = jet.core.GatewayServer.active();
        if (gw == null) throw new IllegalStateException("网关未启动，请先打开本页");
        JSONArray data = gw.catalogJson().getJSONArray("data"); // 复用网关目录（失败抛错）
        String firstModelId = null;
        int added = 0;
        for (int i = 0; i < data.length(); i++) {
            JSONObject m = data.getJSONObject(i);
            String upstream = m.getString("id");           // provider/model
            String modelId = stableId("model", CAPABILITY, upstream.toLowerCase(java.util.Locale.ROOT));
            if (firstModelId == null) firstModelId = modelId;
            if (!existingModels.containsKey(modelId)) {
                JSONObject nm = new JSONObject();
                nm.put("id", modelId);
                nm.put("displayName", m.optString("name", m.optString("id", upstream)));
                nm.put("capability", CAPABILITY);
                nm.put("generationModes", new JSONArray());
                nm.put("generationModesVerified", false);
                nm.put("enabled", true);
                nm.put("invocationDefaults", new JSONObject());
                models.put(nm);
                existingModels.put(modelId, "");
                added++;
            }
            String routeId = stableId("route", modelId, accountId, upstream);
            if (!existingRoutes.containsKey(routeId)) {
                JSONObject nr = new JSONObject();
                nr.put("id", routeId);
                nr.put("modelId", modelId);
                nr.put("accountId", accountId);
                nr.put("upstreamModelName", upstream);
                nr.put("priority", existingRoutes.size());
                nr.put("enabled", true);
                nr.put("options", new JSONObject().put("modelDiscoveryManaged", "true"));
                nr.put("legacyRefs", new JSONArray());
                routes.put(nr);
                existingRoutes.put(routeId, "");
                added++;
            }
        }

        // 用途绑定：仅当 GLOBAL_TEXT_DEFAULT / ASSISTANT_CHAT 完全缺失时补默认
        JSONArray bindings = root.optJSONArray("bindings");
        if (bindings == null) { bindings = new JSONArray(); root.put("bindings", bindings); }
        addBindingIfAbsent(bindings, PURPOSE_GLOBAL, firstModelId);
        addBindingIfAbsent(bindings, PURPOSE_CHAT, firstModelId);

        if (!root.has("suppressedRouteKeys")) root.put("suppressedRouteKeys", new JSONArray());
        return root;
    }

    private static void addBindingIfAbsent(JSONArray bindings, String purpose, String modelId) throws Exception {
        for (int i = 0; i < bindings.length(); i++) {
            JSONObject b = bindings.optJSONObject(i);
            if (b != null && purpose.equals(b.optString("purpose"))) return;
        }
        JSONObject b = new JSONObject();
        b.put("purpose", purpose);
        b.put("primaryModelId", modelId);
        b.put("backupModelIds", new JSONArray());
        b.put("allowGlobalFallback", true);
        b.put("selectionMode", "SINGLE");
        b.put("rotationIndex", 0);
        b.put("selectedRouteIds", new JSONArray());
        bindings.put(b);
    }

    private static CredentialStore store() throws Exception {
        Class<?> holder = Class.forName("jet.core.AppContextHolder");
        return (CredentialStore) holder.getMethod("store").invoke(null);
    }

    static Map<String, String> indexBy(JSONArray arr, String key) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null) out.put(o.optString(key), "");
        }
        return out;
    }

    static boolean jsonArrayOfHas(JSONArray arr, String key, String value) throws Exception {
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o != null && value.equals(o.optString(key))) return true;
        }
        return false;
    }

    // ── 墨听 canonical id 算法（AiModelCenterMigration.stableId$app 等价实现）──

    static String stableId(String prefix, String... parts) throws Exception {
        StringBuilder joined = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) joined.append('\u001f');
            joined.append(parts[i]);
        }
        String sha = sha256Hex(joined.toString());
        return prefix + "_" + sha.substring(0, Math.min(20, sha.length()));
    }

    static String sha256Hex(String s) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(d.length * 2);
        for (byte b : d) sb.append(String.format("%02x", Byte.valueOf(b)));
        return sb.toString();
    }

    static String normalizedUrl(String s) {
        String t = s == null ? "" : s.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.toLowerCase(java.util.Locale.ROOT);
    }

    static String fingerprint(String s) {
        try {
            String t = s == null ? "" : s.trim();
            return sha256Hex(t).substring(0, 16);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
