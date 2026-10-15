package jet.core;

import org.json.JSONObject;

import java.util.Locale;

/**
 * 计费倍率解析与展示名合成。
 *
 * <h3>为什么倍率必须拼进 name</h3>
 * 墨听的模型条目（{@code AiModelEntry}）**没有倍率字段**，只有
 * {@code displayName}。而用户在「AI 模型中心」看到的、以及在用途绑定里挑选模型时
 * 看到的，就是 {@code displayName}。倍率若不拼进去就等于没写。
 *
 * <h3>为什么解析不出就返回 null，而不是回退成 x1</h3>
 * 「没有倍率信息」与「倍率恰好是 1」是两件事：前者是上游没给（例如 Buddy 的
 * {@code auto} 条目），后者是一个真实的计费事实。把前者显示成 x1 等于编造数据，
 * 比不显示更糟。故此处宁可返回 null（不加后缀）。
 *
 * <h3>为什么倍率 0 也要显示</h3>
 * {@code x0.00} 是「免费」这一合法计费结果，与「没有倍率信息」语义不同，
 * 不能因为「0 是假值」就过滤掉。
 */
public final class ModelRate {

    /** 展示名与倍率之间的分隔符：间隔号（U+00B7）。 */
    public static final String SEP = " · ";

    private ModelRate() { }

    /**
     * 合成展示名。
     *
     * @param baseName 裸模型名（不含倍率）
     * @param suffix   倍率后缀，如 {@code "x0.29"}、{@code "x0.00→免费"}、{@code "免费"}；null/空表示无
     * @return 带后缀的展示名；baseName 为空时返回 null（调用方自行回退到 id）
     */
    public static String displayName(String baseName, String suffix) {
        if (baseName == null || baseName.trim().isEmpty()) return null;
        String name = baseName.trim();
        if (suffix == null || suffix.trim().isEmpty()) return name;
        // 幂等：上游偶尔已经把倍率写进 name 了（如 jethub 主机网关的产物），
        // 再加一次会变成「Hy3 · x0.29 · x0.29」。
        if (name.contains(SEP)) return name;
        return name + SEP + suffix.trim();
    }

    // ══════════════════════════════════════════════════════════════
    //  Buddy / WorkBuddy：data.models[].credits
    // ══════════════════════════════════════════════════════════════

    /**
     * 解析 Buddy/WorkBuddy 的 {@code credits} 字段。
     *
     * 实测形态（腾讯网关）：
     * <pre>
     *   "x0.00 credits"   ← 前缀写法，带 " credits" 尾巴
     *   "x0.29"
     *   "0.50x"           ← 后缀写法（促销字段 discountedCredits 是这种）
     *   ""                ← 无倍率信息（如 auto / codewise-*）
     * </pre>
     */
    public static String fromCredits(Object credits) {
        if (credits == null) return null;
        String raw = String.valueOf(credits).trim();
        if (raw.isEmpty()) return null;
        String num = numberIn(raw);
        return num == null ? null : "x" + num;
    }

    /**
     * 从任意文本里抽出倍率数字，兼容 {@code x0.29} / {@code 0.50x} / {@code x0.00 credits}。
     * 抽不出返回 null（**不回退成 1**）。
     */
    public static String numberIn(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        // 前缀：x 在前（x0.29 / x0.00 credits）
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^x\\s*(\\d+(?:\\.\\d+)?)").matcher(s);
        if (m.find()) return trimZeros(m.group(1));
        // 后缀：x 在后（0.50x）
        m = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)\\s*x\\b").matcher(s);
        if (m.find()) return trimZeros(m.group(1));
        return null;
    }

    /** 去掉无意义的小数尾零：0.50 → 0.5，1.00 → 1。 */
    static String trimZeros(String n) {
        if (n == null || n.indexOf('.') < 0) return n;
        String t = n;
        while (t.endsWith("0")) t = t.substring(0, t.length() - 1);
        if (t.endsWith(".")) t = t.substring(0, t.length() - 1);
        return t.isEmpty() ? "0" : t;
    }

    // ══════════════════════════════════════════════════════════════
    //  Trae：display_contact_config（是 JSON 字符串，要二次 parse）
    // ══════════════════════════════════════════════════════════════

    /**
     * 解析 Trae 的 {@code display_contact_config}。
     *
     * ⚠️ 这个字段是**字符串**（内容又是一段 JSON），不是对象 —— 直接
     * {@code entry.optJSONObject("display_contact_config").optJSONObject("consumption_rate")}
     * 永远拿到 null。必须先取字符串再 parse。
     *
     * <p>三条判据（缺一不可）：
     * <ol>
     *   <li>{@code consumption_rate.enable !== false} —— 显式关闭时视为无倍率信息
     *       （不是当成 0，那样会显示「免费」）；</li>
     *   <li>{@code consumption_rate.data.rate} 是有限非负数；</li>
     *   <li>{@code rate: 0} 是合法值（免费），**不能用 > 0 过滤掉**。</li>
     * </ol>
     *
     * <p>活动折扣：{@code activity_discount.data.current.before_consumption_rate} 是原价，
     * 只有当它是有限正数且**严格大于**现价时才算真降价，输出 {@code x原价→x现价}。
     */
    public static String fromTraeEntry(JSONObject entry) {
        if (entry == null) return null;
        Object rawCfg = entry.opt("display_contact_config");
        if (rawCfg == null) return null;
        JSONObject cfg;
        if (rawCfg instanceof JSONObject) {
            cfg = (JSONObject) rawCfg;
        } else {
            String text = String.valueOf(rawCfg).trim();
            if (text.isEmpty()) return null;
            try {
                cfg = new JSONObject(text);
            } catch (Exception e) {
                return null;
            }
        }
        Double now = traeRate(cfg.optJSONObject("consumption_rate"));
        if (now == null) return null;
        Double before = traePromoBefore(cfg, now);
        if (before != null) {
            return "x" + trimZeros(before.toString()) + "→x" + trimZeros(now.toString());
        }
        if (now == 0d) return "x0→免费";
        return "x" + trimZeros(now.toString());
    }

    private static Double traeRate(JSONObject block) {
        if (block == null) return null;
        if (block.has("enable") && !block.optBoolean("enable", true)) return null;
        JSONObject data = block.optJSONObject("data");
        if (data == null) return null;
        if (!data.has("rate")) return null;
        double v = data.optDouble("rate", Double.NaN);
        if (Double.isNaN(v) || Double.isInfinite(v) || v < 0) return null;
        return v;
    }

    private static Double traePromoBefore(JSONObject cfg, double now) {
        JSONObject disc = cfg.optJSONObject("activity_discount");
        if (disc == null) return null;
        JSONObject data = disc.optJSONObject("data");
        JSONObject current = data == null ? null : data.optJSONObject("current");
        if (current == null || !current.has("before_consumption_rate")) return null;
        double before = current.optDouble("before_consumption_rate", Double.NaN);
        if (Double.isNaN(before) || Double.isInfinite(before) || before <= 0) return null;
        // 只有严格大于才算降价：相等时显示「x0.5→x0.5」是噪音
        return before > now ? before : null;
    }

    // ══════════════════════════════════════════════════════════════
    //  Cline：免费模型
    // ══════════════════════════════════════════════════════════════

    public static final String FREE_ID_SUFFIX = ":free";
    public static final String FREE_ID_PREFIX = "cline-free/";

    /**
     * Cline 的免费判定：id 以 {@code :free} 结尾、或以 {@code cline-free/} 开头，
     * 或上游显式给了 {@code isFree: true}。
     */
    public static boolean clineIsFree(String id, JSONObject meta) {
        if (meta != null && meta.optBoolean("isFree", false)) return true;
        if (id == null) return false;
        return id.endsWith(FREE_ID_SUFFIX) || id.startsWith(FREE_ID_PREFIX);
    }

    /** 给裸模型名加「· 免费」后缀（已有后缀则不加）。 */
    public static String clineDisplayName(String baseName, boolean free) {
        return displayName(baseName, free ? "免费" : null);
    }
}
