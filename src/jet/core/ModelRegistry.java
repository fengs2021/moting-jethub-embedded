package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 模型开关表：每个供应商一份「已关闭模型」黑名单。
 *
 * <h3>为什么是黑名单而不是白名单</h3>
 * 上游随时会新增模型。白名单意味着新模型默认不可用，用户得逐个打开；
 * 黑名单让新模型默认可用，用户只需关掉不想要的。语义与 jethub 的
 * {@code pool.listDisabledModels / disabledModelsFor} 一致 —— **只有显式 true 才算关闭**，
 * 表里没有的 id 一律视为可用。
 *
 * <h3>键用什么</h3>
 * 用网关目录里的**完整 id**（含供应商前缀，如 {@code buddy/hy3}、
 * {@code cline/deepseek-v4.1-flash}），与 UI 展示、与写入模型中心的
 * {@code upstreamModelName} 完全一致 —— 避免三处各有一套 id 映射。
 *
 * <h3>存储</h3>
 * 单 ref 的 JSON 对象 {@code {provider: [modelId, ...]}}，与凭据共用
 * {@link CredentialStore}（同一文件、同一套原子写）。
 */
public final class ModelRegistry {

    static final String REF = "JETHUB_DISABLED_MODELS";

    private final CredentialStore store;

    public ModelRegistry(CredentialStore store) {
        this.store = store;
    }

    /** 某供应商已关闭的模型 id 集合（拷贝，调用方可安全持有）。 */
    public synchronized Set<String> disabled(String provider) {
        Set<String> out = new LinkedHashSet<>();
        JSONArray arr = read().optJSONArray(provider);
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            String s = arr.optString(i, "");
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    public synchronized boolean isDisabled(String provider, String modelId) {
        return modelId != null && disabled(provider).contains(modelId);
    }

    /** 单条开关；返回变更后的关闭集合。 */
    public synchronized Set<String> setDisabled(String provider, String modelId, boolean disabled) {
        Set<String> set = disabled(provider);
        if (modelId != null && !modelId.isEmpty()) {
            if (disabled) set.add(modelId);
            else set.remove(modelId);
        }
        write(provider, set);
        return set;
    }

    /**
     * 批量开关。
     *
     * 单独开这个接口而不是让前端循环调 {@link #setDisabled}：那样会写 N 次文件、
     * 并发下还会互相覆盖（读-改-写不是原子的）。这里一次读、一次改、一次写。
     */
    public synchronized Set<String> setDisabledMany(String provider, List<String> modelIds, boolean disabled) {
        Set<String> set = disabled(provider);
        if (modelIds != null) {
            for (String id : modelIds) {
                if (id == null || id.isEmpty()) continue;
                if (disabled) set.add(id);
                else set.remove(id);
            }
        }
        write(provider, set);
        return set;
    }

    /** 全部恢复默认（清空该供应商的黑名单）。 */
    public synchronized Set<String> clear(String provider) {
        Set<String> empty = new LinkedHashSet<>();
        write(provider, empty);
        return empty;
    }

    private JSONObject read() {
        String raw = store.get(REF);
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            // 损坏即视为空表：宁可全部可用，也不要因为一个坏文件把模型全藏了
            return new JSONObject();
        }
    }

    private void write(String provider, Set<String> ids) {
        JSONObject all = read();
        try {
            JSONArray arr = new JSONArray();
            for (String id : ids) arr.put(id);
            all.put(provider, arr);
            store.set(REF, all.toString());
        } catch (Exception ignored) { }
    }

    /** 供 /jethub/models 回显：{provider: [id, ...]}。 */
    public synchronized JSONObject dump() {
        return read();
    }

    /** 表里出现过的供应商（调试用）。 */
    public synchronized Set<String> providers() {
        Set<String> out = new LinkedHashSet<>();
        Iterator<String> it = read().keys();
        while (it.hasNext()) out.add(it.next());
        return out;
    }
}
