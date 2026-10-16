package jet.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 积分余额与每日签到（移植自 jethub 的 *-credits.ts）。
 *
 * 五家的能力矩阵（**不是每家的每一项都存在**，勿凭直觉补全）：
 * <pre>
 *          余额   签到   说明
 *   cline   ✓     ✗     全仓扫描无任何签到端点（campaign 命中的是 PostHog UTM）
 *   trae    ✓     ✓     签到与余额都要完整客户端头 + 确定性设备身份
 *   buddy   ✓     ✓     中国版有签到；checkin-activity-status 才是权威源
 *   workbuddy ✓   ✗     国际版内核里连 checkin-status 字面量都不存在
 *   minimax ✓     ✓     签到按客户端上报时区结算（timezone_id 查询参数）
 * </pre>
 *
 * 统一约定：
 * - 所有方法**不抛异常**，失败返回带 error 的结果对象 —— 批量签到不能因单账号中断；
 * - 余额查不到时返回 error，**不把「查不到」显示成 0**（那是「已用光」的语义）；
 * - 签到结果四分类：claimed / already-claimed / inactive / failed。
 */
public final class CreditsService {

    public static final int HTTP_TIMEOUT_MS = 30_000;

    /** 签到结果分类（与网关 JSON 的 kind 字段一一对应）。 */
    public static final String KIND_CLAIMED = "claimed";
    public static final String KIND_ALREADY = "already-claimed";
    public static final String KIND_INACTIVE = "inactive";
    public static final String KIND_FAILED = "failed";

    /** 服务端标记「该资源包已过期」的 Status 值（实测 3；有效包为 0）。 */
    private static final int PACKAGE_STATUS_EXPIRED = 3;

    private CreditsService() { }

    // ══════════════════════════════════════════════════════════════
    //  统一结果类型
    // ══════════════════════════════════════════════════════════════

    /** 余额查询结果。error 非 null 表示查询失败，调用方应显示原因而非 0。 */
    public static final class Balance {
        public double total;
        public String unit = "credits";
        public double expiredTotal;
        public final List<Package> packages = new ArrayList<>();
        public String error;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("total", total);
                o.put("unit", unit);
                o.put("expiredTotal", expiredTotal);
                if (error != null) o.put("error", error);
                JSONArray arr = new JSONArray();
                for (Package p : packages) arr.put(p.toJson());
                o.put("packages", arr);
            } catch (Exception ignored) { }
            return o;
        }

        static Balance failed(String message) {
            Balance b = new Balance();
            b.error = message;
            return b;
        }
    }

    public static final class Package {
        public String name = "资源包";
        public String unit = "credits";
        public double remaining;
        public double total;
        public double used;
        public boolean active = true;
        public String expiredTime = "";
        public Long deductionEndTimeMs;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("name", name);
                o.put("unit", unit);
                o.put("remaining", remaining);
                o.put("total", total);
                o.put("used", used);
                o.put("active", active);
                o.put("expiredTime", expiredTime);
                if (deductionEndTimeMs != null) o.put("deductionEndTime", deductionEndTimeMs.longValue());
            } catch (Exception ignored) { }
            return o;
        }
    }

    /** 签到状态。 */
    public static final class Checkin {
        public boolean active;
        public boolean todayCheckedIn;
        public int streakDays;
        public double dailyCredit;
        public double totalCredits;
        public String activityName = "";
        public String error;

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("active", active);
                o.put("todayCheckedIn", todayCheckedIn);
                o.put("streakDays", streakDays);
                o.put("dailyCredit", dailyCredit);
                o.put("totalCredits", totalCredits);
                o.put("activityName", activityName);
                if (error != null) o.put("error", error);
            } catch (Exception ignored) { }
            return o;
        }
    }

    /** 领取结果。 */
    public static final class Claim {
        public String kind = KIND_FAILED;
        public int code;
        public double credit;
        public int streakDays;
        public String message = "";

        public JSONObject toJson() {
            JSONObject o = new JSONObject();
            try {
                o.put("kind", kind);
                o.put("code", code);
                o.put("credit", credit);
                o.put("streakDays", streakDays);
                o.put("message", message);
            } catch (Exception ignored) { }
            return o;
        }

        static Claim of(String kind, String message) {
            Claim c = new Claim();
            c.kind = kind;
            c.message = message == null ? "" : message;
            return c;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Cline —— 只有余额，无签到
    // ══════════════════════════════════════════════════════════════

    /**
     * ⚠️ 换算系数是全模块唯一的不确定点：实测 balance=500000，
     * 按 1e-5 解释 = $5.00（与 Cline 公开的新账号赠额量级吻合）。
     * 若核对后单位不同，**只改这一个常量**。
     */
    public static final double CLINE_BALANCE_SCALE = 100_000d;

    /** Cline 余额：GET /api/v1/users/{account_id}/balance（**不是** JWT 的 sub）。 */
    public static Balance clineBalance(ClineProvider provider, JSONObject payload) {
        ClineProvider.Credential cred;
        try {
            cred = ClineProvider.Credential.fromJson(payload);
        } catch (Exception e) {
            return Balance.failed("凭据解析失败");
        }
        String userId = cred.accountId;
        if (userId == null || userId.trim().isEmpty()) {
            return Balance.failed("凭据缺少账号 id，无法查询余额");
        }
        try {
            Map<String, String> headers = provider.authHeaders(cred);
            headers.put("Accept", "application/json");
            HttpFetcher.Response resp = HttpFetcher.get(
                    ClineProvider.API_BASE + "/api/v1/users/"
                            + java.net.URLEncoder.encode(userId.trim(), "UTF-8") + "/balance",
                    headers, HTTP_TIMEOUT_MS);
            String text = resp.readText();
            JSONObject o = new JSONObject(text);
            // 失败形态有两种，必须都认：{success:false,error} 与 HTTP 401 的 {error}（无 success 字段）
            String serverError = o.optString("error", "").trim();
            boolean successFalse = o.has("success") && !o.optBoolean("success", true);
            if (successFalse || (!serverError.isEmpty() && !o.optBoolean("success", false))) {
                String msg = serverError.isEmpty() ? "服务端返回失败" : serverError;
                return Balance.failed("余额查询失败（HTTP " + resp.status + "）：" + msg);
            }
            JSONObject data = o.optJSONObject("data");
            if (data == null) return Balance.failed("响应缺少 data 字段");
            if (!data.has("balance")) return Balance.failed("响应缺少 balance 字段");
            double raw = readNumber(data, "balance", Double.NaN);
            if (Double.isNaN(raw)) return Balance.failed("balance 不是有效数值");
            Balance b = new Balance();
            b.total = round(raw / CLINE_BALANCE_SCALE);
            b.unit = "USD";
            Package p = new Package();
            p.name = "Cline 账户余额";
            p.unit = "USD";
            p.remaining = b.total;
            p.total = b.total;
            b.packages.add(p);
            return b;
        } catch (Exception e) {
            return Balance.failed("余额查询失败：" + describe(e));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  Trae —— 余额 + 签到（都要完整客户端头与确定性设备身份）
    // ══════════════════════════════════════════════════════════════

    public static final String TRAE_CHECKIN_STATUS_PATH = "/trae/api/v2/ug/checkin_credits/status";
    public static final String TRAE_CHECKIN_CLAIM_PATH = "/trae/api/v2/ug/checkin_credits/claim";
    public static final String TRAE_ENT_USAGE_PATH = "/trae/api/v2/pay/ide_user_ent_usage";
    public static final String TRAE_API_HOST = "https://api.trae.cn";

    /**
     * Trae 签到/余额专用请求头。
     *
     * ⚠️ 与对话用的 SOLO 头**不是同一套**：这里约 20 个头，且设备身份
     * （X-Device-Id / X-Market-User-Id / Vscode-Sessionid）必须基于 user_id
     * **确定性派生** —— 每个账号永远同一套设备标识，才能规避服务端
     * 「每设备每天一次」的配额。随机设备号会导致签到被判重复。
     */
    public static Map<String, String> traeCheckinHeaders(String accessToken, String userId) {
        Map<String, String> h = new HashMap<>();
        h.put("Content-Type", "application/json");
        h.put("Accept", "*/*");
        h.put("Accept-Language", "zh-CN");
        h.put("User-Agent", "VSCode 1.107.1 (TRAE SOLO CN)");
        h.put("Authorization", "Cloud-IDE-JWT " + accessToken);
        h.put("X-Market-Client-Id", "VSCode 1.107.1");
        h.put("X-Market-User-Id", deriveMarketUserId(userId));
        h.put("X-User-Region", "CN");
        h.put("X-Device-Id", deriveDeviceId15(userId));
        h.put("X-Lgw-Req-Sdk-Type", "3");
        h.put("Package-Type", "stable_cn");
        h.put("X-Lscbd-Aid", "787976");
        h.put("X-Lscbd-Platform", "windows");
        h.put("App-Version", TraeProvider.IDE_VERSION);
        h.put("X-Tt-Trace-Id", "00-" + randomHex(16) + "-01");
        h.put("Vscode-Sessionid", deriveSessionId(userId));
        h.put("X-Request-Id", uuidV4());
        h.put("Sec-Fetch-Dest", "empty");
        h.put("Sec-Fetch-Mode", "no-cors");
        h.put("Sec-Fetch-Site", "none");
        return h;
    }

    /** SHA-256 确定性伪随机流：SHA256(utf8(salt:seed) ++ counterBE32) 串联。 */
    static byte[] seededStream(String seed, String salt, int nbytes) {
        byte[] out = new byte[nbytes];
        int filled = 0;
        int counter = 0;
        byte[] prefix = (salt + ":" + seed).getBytes(StandardCharsets.UTF_8);
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            while (filled < nbytes) {
                md.reset();
                md.update(prefix);
                md.update(new byte[]{
                        (byte) ((counter >> 24) & 0xFF), (byte) ((counter >> 16) & 0xFF),
                        (byte) ((counter >> 8) & 0xFF), (byte) (counter & 0xFF)});
                byte[] d = md.digest();
                for (byte b : d) {
                    if (filled >= nbytes) break;
                    out[filled++] = b;
                }
                counter++;
            }
        } catch (Exception e) {
            // 摘要不可用时返回全零，调用方仍能构造请求（服务端会拒，但不崩）
        }
        return out;
    }

    /** 15 位数字设备号（每字节 % 10）。 */
    static String deriveDeviceId15(String userId) {
        byte[] bs = seededStream(userId, "devid", 15);
        StringBuilder sb = new StringBuilder();
        for (byte b : bs) sb.append(Math.abs(b) % 10);
        return sb.toString();
    }

    /** UUID v4 形态的 market user id（固定 version/variant 位）。 */
    static String deriveMarketUserId(String userId) {
        byte[] bs = seededStream(userId, "market", 16);
        bs[6] = (byte) ((bs[6] & 0x0F) | 0x40);
        bs[8] = (byte) ((bs[8] & 0x3F) | 0x80);
        StringBuilder hex = new StringBuilder();
        for (byte b : bs) hex.append(String.format("%02x", b));
        String s = hex.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20, 32);
    }

    /** 64 位 hex 会话号。 */
    static String deriveSessionId(String userId) {
        byte[] bs = seededStream(userId, "sess", 32);
        StringBuilder sb = new StringBuilder();
        for (byte b : bs) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    static String randomHex(int n) {
        byte[] buf = new byte[(n + 1) / 2];
        new java.security.SecureRandom().nextBytes(buf);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        return sb.substring(0, n);
    }

    static String uuidV4() {
        byte[] buf = new byte[16];
        new java.security.SecureRandom().nextBytes(buf);
        buf[6] = (byte) ((buf[6] & 0x0F) | 0x40);
        buf[8] = (byte) ((buf[8] & 0x3F) | 0x80);
        StringBuilder sb = new StringBuilder();
        for (byte b : buf) sb.append(String.format("%02x", b));
        String s = sb.toString();
        return s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                + "-" + s.substring(16, 20) + "-" + s.substring(20, 32);
    }

    /** Trae 签到状态查询。 */
    public static Checkin traeCheckinStatus(String accessToken, String uid) {
        Checkin st = new Checkin();
        JSONObject body = traePost(TRAE_CHECKIN_STATUS_PATH, accessToken, uid, "{}");
        if (body == null) {
            st.error = "签到状态查询失败";
            return st;
        }
        int code = readCode(body);
        if (code != 0) {
            st.error = body.optString("message", body.optString("msg", "业务错误 " + code));
            return st;
        }
        st.active = body.optBoolean("enable", false);
        st.todayCheckedIn = body.optBoolean("checked_in", false);
        st.streakDays = (int) readNumber(body, "streak_days", 0);
        st.dailyCredit = readNumber(body, "credits", 0);
        st.totalCredits = readNumber(body, "total_credits", 0);
        return st;
    }

    /** Trae 每日签到领取。 */
    public static Claim traeClaim(String accessToken, String uid) {
        JSONObject body = traePost(TRAE_CHECKIN_CLAIM_PATH, accessToken, uid, "{}");
        if (body == null) {
            return Claim.of(KIND_FAILED, "签到请求失败（网络不可达或响应非 JSON）");
        }
        int code = readCode(body);
        String msg = body.optString("message", body.optString("msg", ""));
        if (code == 9074) {
            return Claim.of(KIND_FAILED, msg.isEmpty() ? "签到人数过多，请稍后再试" : msg);
        }
        if (code == 1005) {
            return Claim.of(KIND_INACTIVE, msg.isEmpty() ? "当前套餐不支持签到" : msg);
        }
        if (code != 0) {
            return Claim.of(KIND_FAILED, msg.isEmpty() ? "签到失败（code=" + code + "）" : msg);
        }
        // ⚠️ claim 响应**不含积分数**（实测就是 {"code":0,"message":"success"}），
        // 真实数值只在 status 端点里 —— 补查一次，否则界面会显示「领取成功 +0」
        Checkin st = traeCheckinStatus(accessToken, uid);
        Claim c = Claim.of(KIND_CLAIMED, "");
        c.credit = st.error == null ? st.dailyCredit : 0;
        c.streakDays = st.error == null ? st.streakDays : 0;
        if (st.error != null && st.todayCheckedIn) {
            // 领取成功但状态查询失败：如实说明，不谎报 0
            c.message = "领取成功（积分数暂不可查）";
        }
        return c;
    }

    /** Trae 积分余额：POST /trae/api/v2/pay/ide_user_ent_usage。 */
    public static Balance traeBalance(String accessToken, String uid) {
        JSONObject body = traePost(TRAE_ENT_USAGE_PATH, accessToken, uid,
                "{\"require_usage\":true,\"req_source\":2}");
        if (body == null) return Balance.failed("余额查询失败（网络不可达或响应非 JSON）");
        JSONArray packList = body.optJSONArray("user_entitlement_pack_list");
        // ⚠️ 「字段缺失」与「确认为空」必须分开：服务端没有独立总额字段，
        // total 完全由 packList 累加 —— 空列表在 Trae 侧**确凿等于 0 余额**
        if (packList == null) return Balance.failed("余额响应缺少 user_entitlement_pack_list");
        Balance b = new Balance();
        b.unit = "credits";
        for (int i = 0; i < packList.length(); i++) {
            JSONObject entry = packList.optJSONObject(i);
            if (entry == null) continue;
            JSONObject base = entry.optJSONObject("entitlement_base_info");
            if (base == null) continue;
            JSONObject quota = base.optJSONObject("quota");
            if (quota == null) continue;
            double limit = readNumber(quota, "credits_limit", 0);
            if (limit <= 0) continue;
            JSONObject usage = entry.optJSONObject("usage");
            double used = usage == null ? 0 : readNumber(usage, "credits_amount", 0);
            Package p = new Package();
            // ⚠️ 包名用 display_desc（实测「每月登录赠送」「签到奖励」「免费」），
            // base.name 实测为 undefined
            String desc = base.optString("display_desc", entry.optString("display_desc", ""));
            p.name = desc.isEmpty() ? "资源包" : desc;
            p.unit = "credits";
            p.remaining = limit - used;
            p.total = limit;
            p.used = used;
            // ⚠️ expire_time 是**秒级**时间戳，不 ×1000 会显示到 1970 年
            double expireSec = readNumber(entry, "expire_time", 0);
            if (expireSec > 0) p.deductionEndTimeMs = (long) (expireSec * 1000);
            b.packages.add(p);
            b.total += p.remaining;
        }
        b.total = round(b.total);
        return b;
    }

    /** Trae 的 POST 小工具；失败返回 null。 */
    private static JSONObject traePost(String path, String accessToken, String uid, String body) {
        try {
            Map<String, String> headers = traeCheckinHeaders(accessToken, uid);
            HttpFetcher.Response resp = HttpFetcher.fetch("POST", TRAE_API_HOST + path, headers,
                    body.getBytes(StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
            String text = resp.readText();
            if (resp.status < 200 || resp.status >= 300) return null;
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    /** 读业务码，兼容数字与字符串（后端部分网关上以 "9074" 返回）。 */
    private static int readCode(JSONObject body) {
        Object raw = body.opt("code");
        if (raw instanceof Number) return ((Number) raw).intValue();
        if (raw instanceof String) {
            String s = ((String) raw).trim();
            if (s.matches("-?\\d+")) return Integer.parseInt(s);
        }
        return raw == null ? 0 : -1;
    }

    // ══════════════════════════════════════════════════════════════
    //  Buddy / WorkBuddy —— 中国版有签到，国际版只有余额
    // ══════════════════════════════════════════════════════════════

    public static final String CHECKIN_ACTIVITY_STATUS_PATH = "/v2/billing/meter/checkin-activity-status";
    public static final String DAILY_CHECKIN_PATH = "/v2/billing/meter/daily-checkin";
    public static final String USER_RESOURCE_PATH = "/v2/billing/meter/get-user-resource";

    private static final int CODE_ALREADY_CLAIMED = 10001;
    private static final int CODE_ALREADY_CLAIMED_ALT = 1001;
    private static final int CODE_NO_QUALIFICATION = 1002;
    private static final int CODE_ACTIVITY_ENDED = 1003;

    /**
     * Buddy 系列签到/余额请求头。
     *
     * ⚠️ X-Domain 以**产品配置**为准，不用凭据里的 domain：凭据的 domain 是
     * 登录时的快照，跨产品迁移后会指向错误区域。
     */
    static Map<String, String> buddyCheckinHeaders(BuddyProvider.Variant variant,
                                                   String accessToken, String enterpriseId, String userId) {
        Map<String, String> h = new HashMap<>();
        h.put("Authorization", "Bearer " + accessToken);
        h.put("Accept", "application/json");
        h.put("Content-Type", "application/json");
        h.put("X-Domain", variant.apiDomain);
        h.put("X-Product", "SaaS");
        h.put("X-Product-Code", variant.productCode);
        if (userId != null && !userId.isEmpty()) h.put("X-User-Id", userId);
        if (enterpriseId != null && !enterpriseId.isEmpty()) {
            h.put("X-Enterprise-Id", enterpriseId);
            h.put("X-Tenant-Id", enterpriseId);
        }
        h.put("User-Agent", variant.userAgent);
        return h;
    }

    /**
     * Buddy 签到状态。
     *
     * ⚠️ 必须用 checkin-activity-status，**不能用 checkin-status**：后者返回
     * 占位数据（active:false、checkin_dates:null），会让人误判成「活动未开启」。
     */
    public static Checkin buddyCheckinStatus(BuddyProvider.Variant variant, String accessToken,
                                             String enterpriseId, String userId) {
        Checkin st = new Checkin();
        JSONObject body = buddyPost(variant, CHECKIN_ACTIVITY_STATUS_PATH, accessToken, enterpriseId, userId);
        if (body == null) {
            st.error = "签到状态查询失败";
            return st;
        }
        if (body.optInt("code", -1) != 0) {
            st.error = body.optString("msg", body.optString("message", "业务错误"));
            return st;
        }
        JSONObject data = body.optJSONObject("data");
        if (data == null) {
            st.error = "签到响应缺少 data 字段";
            return st;
        }
        st.active = data.optBoolean("active", false);
        st.todayCheckedIn = data.optBoolean("today_checked_in", false);
        st.streakDays = (int) readNumber(data, "streak_days", 0);
        st.dailyCredit = readNumber(data, "daily_credit", 0);
        st.totalCredits = readNumber(data, "total_credits", 0);
        st.activityName = data.optString("activity_name", "");
        return st;
    }

    /**
     * Buddy 每日签到领取。
     *
     * 幂等：重复领取返回 HTTP 400 + code 10001，**判定以响应体 code 为准** ——
     * 只看 HTTP 状态码会把幂等情况误报为失败。
     */
    public static Claim buddyClaim(BuddyProvider.Variant variant, String accessToken,
                                   String enterpriseId, String userId) {
        JSONObject body = buddyPost(variant, DAILY_CHECKIN_PATH, accessToken, enterpriseId, userId);
        if (body == null) {
            return Claim.of(KIND_FAILED, "领取请求失败（凭据失效或网络不可达）");
        }
        int code = body.optInt("code", -1);
        String msg = body.optString("msg", body.optString("message", ""));
        if (code == CODE_ALREADY_CLAIMED || code == CODE_ALREADY_CLAIMED_ALT) {
            return Claim.of(KIND_ALREADY, msg.isEmpty() ? "今天已签到" : msg);
        }
        if (code == CODE_NO_QUALIFICATION || code == CODE_ACTIVITY_ENDED) {
            return Claim.of(KIND_INACTIVE, msg.isEmpty() ? "当前无领取资格" : msg);
        }
        if (code != 0) {
            return Claim.of(KIND_FAILED, msg.isEmpty() ? "领取失败（code=" + code + "）" : msg);
        }
        JSONObject data = body.optJSONObject("data");
        if (data == null) {
            return Claim.of(KIND_FAILED, "领取响应缺少 data 字段");
        }
        Claim c = Claim.of(KIND_CLAIMED, data.optString("message", ""));
        c.credit = readNumber(data, "credit", 0);
        c.streakDays = (int) readNumber(data, "streak_days", 0);
        return c;
    }

    /**
     * Buddy 系列积分余额：GET /v2/billing/meter/get-user-resource。
     *
     * ⚠️ 两个产品**通用**（实测），只有 baseURL 不同。不要因为「国际版没签到」
     * 就推断它查不到余额 —— 两者是彼此独立的能力。
     */
    public static Balance buddyBalance(BuddyProvider.Variant variant, String accessToken,
                                       String enterpriseId, String userId) {
        JSONObject body = buddyPost(variant, USER_RESOURCE_PATH, accessToken, enterpriseId, userId);
        if (body == null) return Balance.failed("余额查询失败（凭据失效或网络不可达）");
        int code = body.optInt("code", -1);
        if (code != 0) {
            return Balance.failed(body.optString("msg", body.optString("message", "业务错误 " + code)));
        }
        // ⚠️ data 是**双层嵌套**：data.Response.Data.Accounts[]。与签到端点的单层
        // data 结构不同，这是本接口最容易解析错的地方（曾因此报「结构无法识别」）。
        JSONObject outer = body.optJSONObject("data");
        if (outer == null) return Balance.failed("余额响应缺少 data 字段");
        JSONObject response = outer.optJSONObject("Response");
        if (response == null) return Balance.failed("余额响应缺少 Response 层");
        JSONObject inner = response.optJSONObject("Data");
        if (inner == null) return Balance.failed("余额响应缺少 Data 层");
        JSONArray accounts = inner.optJSONArray("Accounts");
        if (accounts == null) return Balance.failed("余额响应缺少 Accounts 数组");

        Balance b = new Balance();
        b.unit = "credits";
        for (int i = 0; i < accounts.length(); i++) {
            JSONObject item = accounts.optJSONObject(i);
            if (item == null) continue;
            Package p = new Package();
            // 包名回退链：PackageName → SubProductName → PackageCode
            p.name = firstNonEmpty(item.optString("PackageName", ""),
                    firstNonEmpty(item.optString("SubProductName", ""), item.optString("PackageCode", "资源包")));
            p.unit = normalizeUnit(firstNonEmpty(item.optString("CapacityUnit", ""),
                    item.optString("OriginUnit", "credits")));
            // 余额取**本周期口径** CycleCapacityRemain，而非终身口径 CapacityRemain
            p.remaining = preciseNumber(item, "CycleCapacityRemain");
            p.total = preciseNumber(item, "CycleCapacitySize");
            p.used = preciseNumber(item, "CycleCapacityUsed");
            p.active = packageActive(item);
            String exp = item.optString("ExpiredTime", "");
            if (!exp.isEmpty()) p.expiredTime = exp;
            double ded = readNumber(item, "DeductionEndTime", 0);
            if (ded > 0) p.deductionEndTimeMs = Long.valueOf((long) ded);
            b.packages.add(p);
        }
        // 只累加**有效**包的本周期余额：失效包里的额度服务端仍会返回，但不可用，
        // 并进总额会让数字虚高（实测某账号因此从 155.67 虚高到 655.67）。
        double activeSum = 0, expiredSum = 0;
        for (Package p : b.packages) {
            if (p.active) activeSum += p.remaining; else expiredSum += p.remaining;
        }
        b.total = round(activeSum);
        b.expiredTotal = round(expiredSum);
        return b;
    }

    /**
     * 读取数值，优先取带 {@code Precise} 后缀的精确版本。
     *
     * 实测：{@code CapacityRemain} = 247（整数、截断），
     * {@code CapacityRemainPrecise} = "247.87"（字符串、两位小数）。
     * IDE 显示的是后者，故精确值优先；缺失或不可解析时回退整数版。
     */
    private static double preciseNumber(JSONObject o, String baseKey) {
        Object precise = o.opt(baseKey + "Precise");
        double v = looseAmount(precise);
        if (!Double.isNaN(v)) return v;
        return readNumber(o, baseKey, 0);
    }

    /**
     * 资源包是否仍然有效。
     *
     * 判据（与 jethub parseCreditPackage 一致）：
     * Status 显式为已过期，或存在已过去的 ExpiredTime，或扣费截止已过。
     * 未知 Status 一律当成有效 —— 宁可多显示一个额度，也不要把能用的额度藏起来。
     */
    private static boolean packageActive(JSONObject item) {
        if (item.optInt("Status", 0) == PACKAGE_STATUS_EXPIRED) return false;
        String exp = item.optString("ExpiredTime", "");
        if (!exp.isEmpty()) {
            long at = parseLocalTimestamp(exp);
            if (at > 0 && System.currentTimeMillis() >= at) return false;
        }
        double ded = readNumber(item, "DeductionEndTime", 0);
        return !(ded > 0 && System.currentTimeMillis() >= (long) ded);
    }

    /** 解析服务端 "2026-10-01 09:50:14" 形态的本地时间；失败返回 0。 */
    private static long parseLocalTimestamp(String text) {
        try {
            java.text.SimpleDateFormat fmt =
                    new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT);
            java.util.Date d = fmt.parse(text.trim());
            return d == null ? 0L : d.getTime();
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String firstNonEmpty(String a, String b) {
        return a == null || a.isEmpty() ? b : a;
    }

    private static String normalizeUnit(String unit) {
        if (unit == null) return "credits";
        String u = unit.trim().toLowerCase();
        if (u.contains("token")) return "token";
        return "credits";
    }

    /** Buddy 系列 POST 小工具；失败返回 null。 */
    private static JSONObject buddyPost(BuddyProvider.Variant variant, String path, String accessToken,
                                        String enterpriseId, String userId) {
        try {
            Map<String, String> headers = buddyCheckinHeaders(variant, accessToken, enterpriseId, userId);
            HttpFetcher.Response resp = HttpFetcher.fetch("POST", variant.endpoint + path, headers,
                    "{}".getBytes(StandardCharsets.UTF_8), HTTP_TIMEOUT_MS);
            String text = resp.readText();
            // ⚠️ 不用 response.json()：凭据失效时腾讯网关返回 HTML 错误页
            return new JSONObject(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MiniMax —— 余额 + 签到（按客户端上报时区结算）
    // ══════════════════════════════════════════════════════════════

    public static final String MINIMAX_SIGNIN_STATUS_PATH = "/minimax-cloud/api/v1/signin/status";
    public static final String MINIMAX_SIGNIN_CLAIM_PATH = "/minimax-cloud/api/v1/signin/claim";
    public static final String MINIMAX_CREDIT_DETAILS_PATH = "/minimax-cloud/api/v1/credit/details";

    /** 单日状态（asar SigninDayStatus）。 */
    private static final int MM_DAY_UPCOMING = 1;
    private static final int MM_DAY_CLAIMABLE = 2;
    private static final int MM_DAY_CLAIMED = 3;

    /** 领取结果：1=本次真领取，2=已领过（**幂等判据是它，不是 HTTP 状态码**）。 */
    private static final int MM_CLAIM_CLAIMED = 1;
    private static final int MM_CLAIM_ALREADY = 2;

    /**
     * MiniMax 签到状态。
     *
     * ⚠️ 签到按**客户端上报的时区**结算，故 URL 必须带 timezone_id。
     * 本方法用 UTC 偏移量形式的时区名（Android 的 TimeZone.getDefault().getID()
     * 在部分设备上返回 "GMT+08:00" 这类非 IANA 名，服务端会拒）。
     */
    public static Checkin minimaxCheckinStatus(String accessToken, String timezoneId) {
        Checkin st = new Checkin();
        JSONObject payload = minimaxRequest(MINIMAX_SIGNIN_STATUS_PATH, accessToken, timezoneId, "GET", null);
        if (payload == null) {
            st.error = "签到状态查询失败";
            return st;
        }
        JSONObject data = unwrapEnvelope(payload);
        JSONObject panel = resolveMinimaxStatusPanel(data);
        if (panel == null) {
            st.error = "签到面板字段缺失";
            return st;
        }
        return minimaxPanelToStatus(panel);
    }

    /**
     * MiniMax 每日签到领取。
     *
     * ⚠️ 幂等判据是 claim_result==2（已领过），**不是 HTTP 状态码**
     *（重复领取同样返回 200）。
     */
    public static Claim minimaxClaim(String accessToken, String timezoneId) {
        JSONObject payload = minimaxRequest(MINIMAX_SIGNIN_CLAIM_PATH, accessToken, timezoneId, "POST", "{}");
        if (payload == null) return Claim.of(KIND_FAILED, "领取请求失败（凭据失效或网络不可达）");
        JSONObject data = unwrapEnvelope(payload);
        double claimResult = readNumber(data, "claim_result", Double.NaN);
        if (Double.isNaN(claimResult)) {
            return Claim.of(KIND_FAILED, "签到响应缺少有效的 claim_result");
        }
        if ((int) claimResult == MM_CLAIM_ALREADY) {
            return Claim.of(KIND_ALREADY, "今日已签到");
        }
        if ((int) claimResult != MM_CLAIM_CLAIMED) {
            return Claim.of(KIND_FAILED, "签到未成功（claim_result=" + (int) claimResult + "）");
        }
        Claim c = Claim.of(KIND_CLAIMED, "");
        // ⚠️ credit 取 points（总数，已含 bonus_points）—— 不加 bonus_points，
        // 后者是右上角「额外 400」角标，相加会虚高一倍
        c.credit = readNumber(data, "points", 0);
        JSONObject panel = data.optJSONObject("panel");
        if (panel != null) {
            Checkin st = minimaxPanelToStatus(panel);
            c.streakDays = st.streakDays;
        }
        return c;
    }

    /**
     * MiniMax 积分余额：GET /minimax-cloud/api/v1/credit/details。
     *
     * ⚠️ **余额 = Σ details[].remaining_amount**，**不是 total_count** ——
     * 后者是 details 的记录条数。账号余额为 0 时两者偶然都是 0，会掩盖误读。
     */
    public static Balance minimaxBalance(String accessToken) {
        JSONObject payload = minimaxRequest(MINIMAX_CREDIT_DETAILS_PATH, accessToken, null, "GET", null);
        if (payload == null) return Balance.failed("余额查询失败（凭据失效或网络不可达）");
        JSONObject data = unwrapEnvelope(payload);
        JSONArray details = data.optJSONArray("details");
        if (details == null) {
            // ⚠️ details 缺失视为空数组 ⇒ 余额 0（真的为 0），不是查询失败。
            // 但连 total_count 都没有说明形状不对 —— 那才是失败。
            if (!data.has("total_count")) return Balance.failed("余额响应结构无法识别");
            Balance zero = new Balance();
            zero.total = 0;
            zero.unit = "credits";
            return zero;
        }
        Balance b = new Balance();
        b.unit = "credits";
        for (int i = 0; i < details.length(); i++) {
            JSONObject item = details.optJSONObject(i);
            if (item == null) continue;
            // ⚠️ remaining_amount 实测是**字符串**（"800.00"），必须宽容解析
            double remain = looseAmount(item.opt("remaining_amount"));
            if (Double.isNaN(remain)) continue;
            b.total += remain;
            Package p = new Package();
            p.name = "积分余额";
            p.unit = "credits";
            p.remaining = remain;
            p.total = looseAmount(item.opt("granted_amount"));
            p.used = looseAmount(item.opt("consumed_amount"));
            if (Double.isNaN(p.total)) p.total = remain;
            if (Double.isNaN(p.used)) p.used = 0;
            double expireMs = readNumber(item, "expire_at_ms", 0);
            if (expireMs > 0) p.deductionEndTimeMs = (long) expireMs;
            b.packages.add(p);
        }
        b.total = round(b.total);
        return b;
    }

    /**
     * 从 `signin/status` 拆包后的 data 里取出签到面板。
     *
     * ⚠️ **status 与 claim 的面板层级不同，勿互相套用**：
     *   signin/status → 面板字段（scene / days）**直接挂在 data 上**
     *   signin/claim  → 面板在 data.panel 下
     *
     * 早期版本对 status 也取 `data.panel`，于是永远拿到 null：余额照常显示
     * （走 credit/details，另一个端点），签到块却报「签到面板字段缺失」。
     * 这里两种形状都接受：`data.panel` 优先，回落到 data 本身。
     *
     * @return 面板对象；两种形状都不成立时返回 null。
     */
    public static JSONObject resolveMinimaxStatusPanel(JSONObject data) {
        if (data == null) return null;
        JSONObject nested = data.optJSONObject("panel");
        if (nested != null) return nested;
        if (data.has("days") || data.has("scene")) return data;
        return null;
    }

    /** 把签到面板映射成状态。 */
    public static Checkin minimaxPanelToStatus(JSONObject panel) {
        Checkin st = new Checkin();
        JSONArray days = panel.optJSONArray("days");
        // ⚠️ 拿到响应即 active=true，不按「有可领项」判 ——
        // 否则会把「今天已领」误报成「签到活动未开启」
        st.active = true;
        if (days == null || days.length() == 0) {
            st.error = "签到面板为空";
            return st;
        }
        JSONObject today = null;
        int todayIndex = -1;
        List<JSONObject> sorted = new ArrayList<>();
        for (int i = 0; i < days.length(); i++) {
            JSONObject d = days.optJSONObject(i);
            if (d == null) continue;
            sorted.add(d);
        }
        // 按 day_no 排序
        final Map<JSONObject, Integer> dayNo = new HashMap<>();
        for (JSONObject d : sorted) dayNo.put(d, (int) readNumber(d, "day_no", 0));
        java.util.Collections.sort(sorted, new java.util.Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Integer.compare(dayNo.get(a), dayNo.get(b));
            }
        });
        for (int i = 0; i < sorted.size(); i++) {
            JSONObject d = sorted.get(i);
            // ⚠️ is_today 必须宽容读：协议文档陷阱 80 记载服务端可能回数字 0/1，
            // 参考实现遇到非 boolean 会**整块丢弃面板**（面板整体消失）。
            // 这里改成接受 boolean 与 0/1 数字，宁可容错也不让签到块变空。
            if (readBool(d, "is_today", false)) {
                today = d;
                todayIndex = i;
                break;
            }
        }
        if (today != null) {
            st.dailyCredit = readNumber(today, "points", 0);
            int status = (int) readNumber(today, "status", 0);
            st.todayCheckedIn = status == MM_DAY_CLAIMED;
            // 连续天数：从今日起向前数连续 Claimed
            int streak = 0;
            if (todayIndex >= 0) {
                int idx = status == MM_DAY_CLAIMED ? todayIndex : todayIndex - 1;
                while (idx >= 0) {
                    int s = (int) readNumber(sorted.get(idx), "status", 0);
                    if (s != MM_DAY_CLAIMED) break;
                    streak++;
                    idx--;
                }
            }
            st.streakDays = streak;
        }
        // 总积分：所有已领天数之和
        double total = 0;
        for (JSONObject d : sorted) {
            if ((int) readNumber(d, "status", 0) == MM_DAY_CLAIMED) {
                total += readNumber(d, "points", 0);
            }
        }
        st.totalCredits = total;
        return st;
    }

    /** MiniMax 请求（带 timezone_id 查询参数 + 信封拆解）。 */
    private static JSONObject minimaxRequest(String path, String accessToken, String timezoneId,
                                             String method, String body) {
        try {
            StringBuilder url = new StringBuilder(MinimaxProvider.API_HOST).append(path);
            if (timezoneId != null && !timezoneId.isEmpty()) {
                url.append(url.indexOf("?") >= 0 ? "&" : "?").append("timezone_id=")
                        .append(java.net.URLEncoder.encode(timezoneId, "UTF-8"));
            }
            Map<String, String> h = new HashMap<>();
            h.put("Authorization", "Bearer " + accessToken);
            h.put("Accept", "application/json");
            byte[] payload = null;
            if (body != null) {
                h.put("Content-Type", "application/json");
                payload = body.getBytes(StandardCharsets.UTF_8);
            }
            HttpFetcher.Response resp = "GET".equals(method)
                    ? HttpFetcher.get(url.toString(), h, HTTP_TIMEOUT_MS)
                    : HttpFetcher.fetch("POST", url.toString(), h, payload, HTTP_TIMEOUT_MS);
            String text = resp.readText();
            JSONObject o = new JSONObject(text);
            // ⚠️ 业务码在 base_resp.status_code，且 invalid timezone_id 也是 HTTP 200 ——
            // 只看 HTTP 状态码会把它当成成功
            JSONObject baseResp = o.optJSONObject("base_resp");
            if (baseResp != null) {
                double statusCode = readNumber(baseResp, "status_code", 0);
                if (!Double.isNaN(statusCode) && (int) statusCode != 0) return null;
            }
            return o;
        } catch (Exception e) {
            return null;
        }
    }

    /** 拆信封：data 是对象则取它，否则原样返回顶层。 */
    static JSONObject unwrapEnvelope(JSONObject payload) {
        JSONObject nested = payload.optJSONObject("data");
        return nested == null ? payload : nested;
    }

    // ══════════════════════════════════════════════════════════════
    //  工具
    // ══════════════════════════════════════════════════════════════

    /** 读数字，接受数字与数字字符串；缺失返回 fallback。 */
    static double readNumber(JSONObject o, String key, double fallback) {
        Object v = o.opt(key);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isFinite(d) ? d : fallback;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return fallback;
            try {
                double d = Double.parseDouble(s);
                return Double.isFinite(d) ? d : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 读布尔，宽容接受 boolean 与数字/数字字符串 0/1。
     *
     * ⚠️ MiniMax 的 `is_today` 协议文档记载必须是 boolean，但服务端实际可能回
     * 数字 0/1；参考实现遇到非 boolean 会把**整个签到面板丢弃**（面板消失）。
     * 这里宁宽容勿丢弃：只有明确的 0/false 才算 false。
     */
    public static boolean readBool(JSONObject o, String key, boolean fallback) {
        Object v = o.opt(key);
        if (v instanceof Boolean) return ((Boolean) v).booleanValue();
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isFinite(d)) return d != 0;
            return fallback;
        }
        if (v instanceof String) {
            String s = ((String) v).trim();
            if (s.isEmpty()) return fallback;
            if ("true".equalsIgnoreCase(s)) return true;
            if ("false".equalsIgnoreCase(s)) return false;
            try {
                double d = Double.parseDouble(s);
                return Double.isFinite(d) ? d != 0 : fallback;
            } catch (NumberFormatException e) {
                return fallback;
            }
        }
        return fallback;
    }

    /**
     * 宽容解析金额。
     *
     * ⚠️ `Double.parseDouble("")` 抛异常、`Number("")` 是 0 —— 必须先把空串挡掉，
     * 否则「空字符串」会被误读成「0 积分」。
     */
    static double looseAmount(Object value) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return Double.isFinite(d) ? d : Double.NaN;
        }
        if (value instanceof String) {
            String s = ((String) value).trim();
            if (s.isEmpty()) return Double.NaN;
            try {
                double d = Double.parseDouble(s);
                return Double.isFinite(d) ? d : Double.NaN;
            } catch (NumberFormatException e) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    static String describe(Exception e) {
        String m = e.getMessage();
        return (m == null || m.isEmpty()) ? e.getClass().getSimpleName() : m;
    }
}
