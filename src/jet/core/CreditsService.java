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
    //  CodeArts —— 余额（积分包）与每日签到，全套走 SDK-HMAC-SHA256 签名
    // ══════════════════════════════════════════════════════════════

    /** snap-access 网关基址（与模型目录端点的 SNAP_MODEL_BUILTIN_URL 同域）。 */
    public static final String CODEARTS_SNAP_ENGINE_URL =
            "https://snap-access.cn-north-4.myhuaweicloud.com";
    /** 账户/套餐信息端点 —— **积分账户检测的唯一真相源**。返回裸对象（无 data 信封）。 */
    public static final String CODEARTS_PACKAGE_INFO_PATH = "/snap-manager/v1/statistics/plugin";
    /** 活动列表端点。返回 {@code {code,message,data}} 信封。 */
    public static final String CODEARTS_OPS_DELIVERY_PATH = "/v1/ops/delivery";
    public static final String CODEARTS_OPS_CLAIM_PATH = "/v1/ops/claim";
    public static final String CODEARTS_OPS_CONFIRM_PATH = "/v1/ops/confirm";
    /** 渠道标识：声明请求来自 IDE 形态（对齐 IDE 的 channel=IDE）。 */
    public static final String CODEARTS_OPS_CHANNEL = "IDE";
    /** 「每日签到得积分」在活动列表里的 type 取值。 */
    public static final String CODEARTS_DAILY_LOGIN_TYPE = "USER_LOGIN";

    /**
     * 活动 status 中表示「已领取/已确认/已核销」的取值。
     *
     * <p>用它将「今天已领」与「活动未开始/无资格」区分开 —— 两者对用户的
     * 含义不同，合并成一句会让人以为签到坏了。
     */
    private static final String[] CODEARTS_CLAIMED_STATUSES = {"CLAIMED", "CONFIRMED", "CONSUMED"};

    /**
     * 所有 snap-access 请求共用的附加头 —— **必须在签名之后追加，不参与签名**。
     *
     * <p>⚠️ 这两个头绝不能作为 {@code signRequestHuawei} 的 extraSignedHeaders 传入。
     * 实测（2026-09-18，真实凭据）：一旦 {@code Agent-Type} 进入 canonical request
     * 与 SignedHeaders，服务端回
     * {@code 401 {"error_code":"APIG.0301","error_msg":"...verify ak sk signature fail"}}；
     * 同一个头在**签名之后**追加则请求成功（200 并返回真实数据）。
     *
     * <p>{@code Agent-Type} 本身仍是服务端路由所需（{@code /v1/model/builtin} 等
     * 端点靠它选择响应形态），只是不该被签名。
     */
    public static final Map<String, String> CODEARTS_SNAP_EXTRA_HEADERS = codeartsExtraHeaders();

    private static Map<String, String> codeartsExtraHeaders() {
        Map<String, String> m = new HashMap<>();
        m.put("Agent-Type", "PromptCenter");
        m.put("X-Language", "zh-cn");
        return m;
    }

    /** 积分 metric 名 → 展示名（IDE 的 accountInfoPane.apply 读这四项）。 */
    private static final Map<String, String> CODEARTS_CREDIT_METRIC_LABELS =
            codeartsMetricLabels();

    private static Map<String, String> codeartsMetricLabels() {
        Map<String, String> m = new HashMap<>();
        m.put("usageTotalPackageCredit", "总积分包");
        m.put("usageBasicPackageCredit", "基础积分包");
        m.put("usageOnDemandPackageCredit", "按需积分包");
        m.put("usageBonusPackageCredit", "赠送积分包");
        return m;
    }

    /** 总额 metric 名（其余分类明细不参与总额计算，避免重复累加）。 */
    private static final String CODEARTS_TOTAL_CREDIT_METRIC = "usageTotalPackageCredit";

    /**
     * 账户/套餐信息（{@code statistics/plugin} 的解析结果）。
     *
     * <p>{@code credit} 为 null 表示「这个账户没有积分口径」，与
     * {@code total==0}（有口径但当前为 0）严格不同。
     */
    public static final class CodeartsAccountInfo {
        public boolean isCreditPackage;
        public boolean isTokenPackage;
        public String specCode = "";
        public String packageName = "";
        public String packageStatus = "";
        public Balance credit;

        /**
         * 账户类型的一句话说明（失败与「类型不符」的措辞必须分开）。
         *
         * <p>用户看到「Token 计费账户，不在积分活动范围」会知道自己不用折腾；
         * 看到「账户信息查询失败」才会去查网络或重新登录。
         */
        public String describe() {
            if (isCreditPackage) {
                return packageName.isEmpty() ? "积分计费账户" : "积分计费账户 · " + packageName;
            }
            if (isTokenPackage) return "Token 计费账户，不在积分活动范围";
            return "非积分计费账户，不在积分活动范围";
        }
    }

    /** 活动列表中的一项（只保留签到所需字段）。 */
    public static final class CodeartsActivity {
        /**
         * 活动 ID，领取时回传。
         *
         * <p>⚠️ **服务端下发的是数字**（实测 {@code campaignId = 1}），不是字符串。
         * 用只接受字符串的解析会得到空串，领取流程据此判「缺少 campaignId」而
         * 失败 —— 用户点了「一键领取」却看到「1 个失败」而积分实际没领到。
         */
        public String campaignId = "";
        public String type = "";
        public String title = "";
        /** 当前是否可领取 —— 领取判定的权威依据。 */
        public boolean claimable;
        public String status = "";
        /**
         * 该活动可领积分。
         *
         * <p>字段名是 **{@code benefitAmount}**（实测 1000），不是 amount。
         */
        public double amount;
    }

    /** 查询一次的结果：ok=false 时 message 带底层原因。 */
    public static final class CodeartsSnap {
        public boolean ok;
        public JSONObject data;
        public double code;
        public String message = "";
    }

    /**
     * 发起一次带 SDK-HMAC-SHA256 签名的 snap-access 请求。
     *
     * <p>⚠️ 与模型目录的 {@code fetchSignedGet} 同款取舍：SNAP_EXTRA_HEADERS 在
     * **签名之后**追加。把它们传进签名会进 canonical request、服务端验签失败（401）。
     *
     * <p>失败时带**服务端原因**而非笼统文案：华为网关的错误体里带着真正的原因，
     * 例如 {@code {"error_code":"APIG.0301","error_msg":"...verify ak sk signature
     * fail"}}。丢掉它会让「签名头位置不对」「凭据过期」「AK 无权限」这些**处置
     * 方式完全不同**的问题看起来一模一样。
     */
    public static CodeartsSnap codeartsSignedRequest(
            CodeartsProvider.Credential credential, String method, String url, String body) {
        CodeartsSnap r = new CodeartsSnap();
        if (credential == null
                || isBlank(credential.accessKeyId) || isBlank(credential.secretAccessKey)) {
            r.message = "凭据缺少 AK/SK";
            return r;
        }
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        try {
            HttpFetcher.Response resp = CodeartsProvider.signedFetch(
                    credential, method, url, payload, null, CODEARTS_SNAP_EXTRA_HEADERS,
                    HTTP_TIMEOUT_MS);
            String text = resp.readText();
            if (resp.status < 200 || resp.status >= 300) {
                r.message = describeCodeartsHttpFailure(resp.status, text);
                return r;
            }
            JSONObject raw;
            try {
                raw = new JSONObject(text);
            } catch (Exception e) {
                r.message = "响应无法解析为 JSON：" + snippet(text, 200);
                return r;
            }
            return unwrapCodeartsEnvelope(raw);
        } catch (Exception e) {
            r.message = "网络请求失败：" + describe(e);
            return r;
        }
    }

    /**
     * 把非 2xx 响应整理成**带服务端原因**的说明。
     *
     * <p>华为网关的错误体里有 {@code error_code}/{@code error_msg}，非 JSON 时
     * （网关 HTML 错误页）截断原文，避免把整页 HTML 塞进界面。
     */
    static String describeCodeartsHttpFailure(int status, String text) {
        String detail = "";
        try {
            JSONObject o = new JSONObject(text);
            String code = o.optString("error_code", "");
            String msg = o.optString("error_msg", "");
            detail = code.isEmpty() ? msg : (msg.isEmpty() ? code : code + " " + msg);
        } catch (Exception e) {
            detail = text == null ? "" : text.trim();
            if (detail.length() > 200) detail = detail.substring(0, 200);
        }
        return detail.isEmpty() ? "HTTP " + status : "HTTP " + status + "：" + detail;
    }

    /**
     * 解包响应信封 —— **必须兼容两种形态**，因为两个端点的信封结构不同：
     *
     * <ul>
     *   <li>{@code ops/*} 返回 {@code {code, message, data}}，且 {@code code !== 0}
     *       即业务失败；</li>
     *   <li>{@code statistics/plugin} **直接返回裸对象**，没有 data 解包步骤。</li>
     * </ul>
     *
     * <p>判定顺序：先看 code（存在且非 0 即失败），再取 data，没有 data 就认为
     * body 本身即数据。这样两种形态都能解析，且不会把业务失败当成成功 —— 那会
     * 让「活动未开始」被误报成「领取成功」。
     */
    static CodeartsSnap unwrapCodeartsEnvelope(JSONObject raw) {
        CodeartsSnap r = new CodeartsSnap();
        Object codeVal = raw.opt("code");
        if (codeVal instanceof Number) {
            double code = ((Number) codeVal).doubleValue();
            if (code != 0) {
                String message = raw.optString("message", "");
                if (message.isEmpty()) message = raw.optString("msg", "");
                r.code = code;
                r.message = message.isEmpty() ? ("业务码 " + trimNum(code)) : message;
                return r;
            }
            JSONObject data = raw.optJSONObject("data");
            if (data == null) {
                r.code = code;
                r.message = "响应缺少 data 字段";
                return r;
            }
            r.ok = true;
            r.data = data;
            return r;
        }
        // 无 code 字段：裸对象形态（statistics/plugin）。
        r.ok = true;
        r.data = raw;
        return r;
    }

    /**
     * 查询账户/套餐信息（含积分账户检测）。
     *
     * <p>查询失败时 {@code ok=false} 且 message 带原因；成功但非积分账户时
     * {@code ok=true} + {@code isCreditPackage=false}。两者必须分开：前者要用户
     * 排查网络或重新登录，后者是正常的账户类型差异。
     */
    public static CodeartsSnap fetchCodeartsAccountInfo(CodeartsProvider.Credential c) {
        return codeartsSignedRequest(c, "GET",
                CODEARTS_SNAP_ENGINE_URL + CODEARTS_PACKAGE_INFO_PATH, null);
    }

    /** 从 {@code statistics/plugin} 的裸对象解析账户信息。 */
    public static CodeartsAccountInfo parseCodeartsAccountInfo(JSONObject data) {
        CodeartsAccountInfo info = new CodeartsAccountInfo();
        JSONObject pkg = data.optJSONObject("package");
        if (pkg == null) pkg = new JSONObject();
        info.isCreditPackage = readBool(pkg, "is_credit_package", false);
        info.isTokenPackage = readBool(pkg, "is_token_package", false);
        info.specCode = pkg.optString("spec_code", "");
        String cn = pkg.optString("package_name_cn", "");
        String en = pkg.optString("package_name_en", "");
        info.packageName = cn.isEmpty() ? en : cn;
        info.packageStatus = pkg.optString("status", "");
        info.credit = parseCodeartsCreditBalance(data.opt("metrics"));
        return info;
    }

    /**
     * 从 {@code metrics[]} 解析积分余额。
     *
     * <p>只有认得的 metric 名才参与；一项都不认得时返回 null（表示「这个账户
     * 没有积分口径」），而不是 {@code total=0}（那是「有口径但为 0」）。
     *
     * <p>总额优先取 {@code usageTotalPackageCredit} 的 remain；缺失时回退为各
     * 分项 remain 之和 —— 但**不把分项与总额同时相加**，否则会重复累加。
     */
    public static Balance parseCodeartsCreditBalance(Object metrics) {
        JSONArray arr = metrics instanceof JSONArray ? (JSONArray) metrics : null;
        if (arr == null) return null;
        Balance b = new Balance();
        b.unit = "credit";
        boolean sawAny = false;
        Double totalRemain = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name", "");
            String label = CODEARTS_CREDIT_METRIC_LABELS.get(name);
            if (label == null) continue;
            sawAny = true;
            double amount = readNumber(item, "package_credit_amount", 0);
            double used = readNumber(item, "package_credit_used", 0);
            double remain = readNumber(item, "package_credit_remain", 0);
            if (CODEARTS_TOTAL_CREDIT_METRIC.equals(name)) totalRemain = remain;
            // 额度为 0 的分类不列为资源包：列出来只会让「N 个资源包」虚高。
            if (amount <= 0 && remain <= 0) continue;
            Package p = new Package();
            p.name = label;
            p.unit = "credit";
            p.remaining = remain;
            p.total = amount;
            p.used = used;
            // statistics/plugin 不下发资源包的有效性/周期字段（那是腾讯侧形态）。
            // 如实置为有效、周期留空，而不是臆造一个到期时间。
            p.active = true;
            b.packages.add(p);
        }
        if (!sawAny) return null;
        if (totalRemain != null) {
            b.total = totalRemain.doubleValue();
        } else {
            double sum = 0;
            for (Package p : b.packages) sum += p.remaining;
            b.total = sum;
        }
        b.total = round(b.total);
        return b;
    }

    /** 查询活动列表。ok=false 时带原因；ok=true 时 activities 可能为空。 */
    public static CodeartsSnap fetchCodeartsOpsActivities(CodeartsProvider.Credential c) {
        return codeartsSignedRequest(c, "GET",
                CODEARTS_SNAP_ENGINE_URL + CODEARTS_OPS_DELIVERY_PATH
                        + "?channel=" + CODEARTS_OPS_CHANNEL, null);
    }

    /** 把活动列表的一项解析为 {@link CodeartsActivity}。 */
    public static CodeartsActivity parseCodeartsActivity(JSONObject item) {
        CodeartsActivity a = new CodeartsActivity();
        // 用 readIdentifier 而非 optString：实测 campaignId 是**数字** 1。
        a.campaignId = readIdentifier(item, "campaignId");
        a.type = item.optString("type", "");
        a.title = item.optString("title", "");
        a.claimable = readBool(item, "claimable", false);
        a.status = item.optString("status", "");
        // 实测字段名是 benefitAmount（值 1000）；另两个候选名作兼容回退。
        double amount = readNumber(item, "benefitAmount", 0);
        if (amount == 0) amount = readNumber(item, "amount", 0);
        if (amount == 0) amount = readNumber(item, "creditAmount", 0);
        a.amount = amount;
        return a;
    }

    /** 在活动列表里找「每日签到」那一项；没有则返回 null。 */
    public static CodeartsActivity findCodeartsDailyCheckin(List<CodeartsActivity> activities) {
        if (activities == null) return null;
        for (CodeartsActivity a : activities) {
            if (CODEARTS_DAILY_LOGIN_TYPE.equals(a.type)) return a;
        }
        return null;
    }

    /** 读活动列表（失败返回 ok=false）。 */
    public static CodeartsSnap readCodeartsActivities(CodeartsProvider.Credential c) {
        CodeartsSnap r = fetchCodeartsOpsActivities(c);
        if (!r.ok) return r;
        Object items = r.data.opt("items");
        JSONArray arr = items instanceof JSONArray ? (JSONArray) items : null;
        if (arr == null) {
            r.ok = false;
            r.message = "响应缺少 items 字段";
            return r;
        }
        JSONArray out = new JSONArray();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item != null) out.put(item);
        }
        JSONObject wrapped = new JSONObject();
        try { wrapped.put("items", out); } catch (Exception ignored) { }
        r.data = wrapped;
        return r;
    }

    /** 余额：账户信息里的积分口径（非积分账户返回 error 说明而非 0）。 */
    public static Balance codeartsBalance(CodeartsProvider.Credential c) {
        CodeartsSnap r = fetchCodeartsAccountInfo(c);
        if (!r.ok) return Balance.failed("账户信息查询失败：" + r.message);
        CodeartsAccountInfo info = parseCodeartsAccountInfo(r.data);
        if (info.credit == null) {
            return Balance.failed(info.isCreditPackage
                    ? "账户为积分计费模式，但响应没有积分口径字段"
                    : info.describe());
        }
        return info.credit;
    }

    /**
     * 签到状态：只报「能否领、今天领没领」，**不触发领取**。
     *
     * <p>先判账户类型（Token 计费账户不在活动范围内），再查活动列表。
     * 两处失败都如实带原因。
     */
    public static Checkin codeartsCheckinStatus(CodeartsProvider.Credential c) {
        Checkin st = new Checkin();
        CodeartsSnap infoR = fetchCodeartsAccountInfo(c);
        if (!infoR.ok) {
            st.error = "账户信息查询失败：" + infoR.message;
            return st;
        }
        CodeartsAccountInfo info = parseCodeartsAccountInfo(infoR.data);
        if (!info.isCreditPackage) {
            st.error = info.describe();
            return st;
        }
        CodeartsSnap actR = readCodeartsActivities(c);
        if (!actR.ok) {
            st.error = "活动列表查询失败：" + actR.message;
            return st;
        }
        List<CodeartsActivity> list = new ArrayList<>();
        JSONArray items = actR.data.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) list.add(parseCodeartsActivity(item));
            }
        }
        CodeartsActivity activity = findCodeartsDailyCheckin(list);
        if (activity == null) {
            st.error = "未找到每日签到活动";
            return st;
        }
        st.active = true;
        st.todayCheckedIn = !activity.claimable && isCodeartsClaimedStatus(activity.status);
        st.activityName = activity.title.isEmpty() ? "每日签到" : activity.title;
        st.dailyCredit = activity.amount;
        // CodeArts 的活动不下发连续签到天数概念（那是 CodeBuddy 的机制），如实记 0。
        st.streakDays = 0;
        return st;
    }

    /**
     * 执行每日签到领取（完整流程）。
     *
     * <p>步骤与判定顺序（每一步都对应一个**对用户含义不同**的结果）：
     * <ol>
     *   <li>查账户类型 —— 查询失败 → failed；非积分账户 → inactive
     *       （活动范围明确限定「已升级到积分计费模式的用户」，Token 账户不该被
     *       报成「领取失败」）；</li>
     *   <li>查活动列表 —— 查询失败 → failed；无 USER_LOGIN 活动 → inactive；</li>
     *   <li>活动不可领取且状态属已领取态 → already-claimed；其余不可领取 → inactive；</li>
     *   <li>POST {@code /v1/ops/claim} —— 失败 → failed；</li>
     *   <li>响应 {@code id != null} 时补 POST {@code /v1/ops/confirm}
     *       （漏掉会让积分停在待确认而不入账）；</li>
     *   <li>成功 → claimed。</li>
     * </ol>
     *
     * <p>注意第 3 步是**唯一的幂等保护**：本协议没有幂等键，也没有服务端
     * 「今天已签到」业务码可依赖，因此预检不能省。
     */
    public static Claim codeartsClaim(CodeartsProvider.Credential c) {
        CodeartsSnap infoR = fetchCodeartsAccountInfo(c);
        if (!infoR.ok) {
            return Claim.of(KIND_FAILED, "账户信息查询失败：" + infoR.message);
        }
        CodeartsAccountInfo info = parseCodeartsAccountInfo(infoR.data);
        if (!info.isCreditPackage) return Claim.of(KIND_INACTIVE, info.describe());

        CodeartsSnap actR = readCodeartsActivities(c);
        if (!actR.ok) {
            return Claim.of(KIND_FAILED, "活动列表查询失败：" + actR.message);
        }
        List<CodeartsActivity> list = new ArrayList<>();
        JSONArray items = actR.data.optJSONArray("items");
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item != null) list.add(parseCodeartsActivity(item));
            }
        }
        CodeartsActivity activity = findCodeartsDailyCheckin(list);
        if (activity == null) return Claim.of(KIND_INACTIVE, "未找到每日签到活动");
        if (!activity.claimable) {
            return isCodeartsClaimedStatus(activity.status)
                    ? Claim.of(KIND_ALREADY, "今天已领取")
                    : Claim.of(KIND_INACTIVE, "当前不可领取（status=" + activity.status + "）");
        }
        if (activity.campaignId.isEmpty()) {
            return Claim.of(KIND_FAILED, "活动缺少 campaignId，无法领取");
        }

        String claimBody;
        try {
            JSONObject b = new JSONObject();
            b.put("campaignId", activity.campaignId);
            b.put("channel", CODEARTS_OPS_CHANNEL);
            claimBody = b.toString();
        } catch (Exception e) {
            return Claim.of(KIND_FAILED, "领取请求构造失败");
        }
        CodeartsSnap claimR = codeartsSignedRequest(c, "POST",
                CODEARTS_SNAP_ENGINE_URL + CODEARTS_OPS_CLAIM_PATH, claimBody);
        if (!claimR.ok) {
            Claim out = Claim.of(KIND_FAILED, claimR.message);
            out.code = (int) claimR.code;
            return out;
        }

        // 服务端要求确认时才补 confirm：IDE 的判据是 benefit.id !== null。
        // confirm 失败**不**把整体判为失败 —— 积分已进入待确认态，报 failed 会让
        // 用户以为没领到而重复点击；这里如实返回 claimed，把确认异常留在文案层。
        String benefitId = readIdentifier(claimR.data, "id");
        String confirmNote = "";
        if (!benefitId.isEmpty()) {
            String confirmBody;
            try {
                JSONObject b = new JSONObject();
                b.put("campaignId", activity.campaignId);
                confirmBody = b.toString();
            } catch (Exception e) {
                confirmBody = null;
            }
            if (confirmBody != null) {
                CodeartsSnap confirmR = codeartsSignedRequest(c, "POST",
                        CODEARTS_SNAP_ENGINE_URL + CODEARTS_OPS_CONFIRM_PATH, confirmBody);
                if (!confirmR.ok) confirmNote = "（确认未完成：" + confirmR.message + "）";
            }
        }

        // 积分取多级回退：领取响应 → 活动条目。两处都没有时如实记 0，
        // 不臆造「1000」—— 文档里的 1000 是活动规则，不是本次发放的实测值。
        double claimCredit = readNumber(claimR.data, "benefitAmount", 0);
        if (claimCredit == 0) claimCredit = readNumber(claimR.data, "credit", 0);
        if (claimCredit == 0) claimCredit = readNumber(claimR.data, "credits", 0);
        if (claimCredit == 0) claimCredit = readNumber(claimR.data, "creditAmount", 0);
        if (claimCredit == 0) claimCredit = readNumber(claimR.data, "amount", 0);
        Claim out = Claim.of(KIND_CLAIMED, confirmNote);
        out.credit = claimCredit > 0 ? claimCredit : activity.amount;
        // CodeArts 的活动不下发连续签到天数概念（那是 CodeBuddy 的机制），如实记 0。
        out.streakDays = 0;
        return out;
    }

    static boolean isCodeartsClaimedStatus(String status) {
        if (status == null) return false;
        for (String s : CODEARTS_CLAIMED_STATUSES) {
            if (s.equalsIgnoreCase(status.trim())) return true;
        }
        return false;
    }

    /**
     * 读一个**标识符类**字段并统一成字符串（兼容数字与字符串两种形态）。
     *
     * <p>华为服务端对同一语义字段的类型并不一致 —— 实测 {@code campaignId} 下发
     * 的是**数字** {@code 1}，而 {@code status} 下发的是字符串（不可领取时甚至为
     * null）。用只接受字符串的读取方式会得到空串，领取流程据此判「缺少
     * campaignId」而失败（真实缺陷：用户点「一键领取积分」后看到「1 个失败」）。
     *
     * <p>数字转字符串用整数形态：{@code 1} → {@code "1"}，正是回传服务端所需的
     * 形态。对象/数组/布尔等非标量一律返回空串，避免把 {@code [object Object]}
     * 当作 ID 发出去。
     */
    static String readIdentifier(JSONObject o, String key) {
        Object v = o.opt(key);
        if (v instanceof String) return (String) v;
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (!Double.isFinite(d)) return "";
            if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) {
                return String.valueOf((long) d);
            }
            return trimNum(d);
        }
        return "";
    }

    /** 数字去尾零：1.0 → "1"，1.5 → "1.5"。 */
    static String trimNum(double d) {
        if (d == Math.rint(d) && Math.abs(d) < 9.007199254740992E15) return String.valueOf((long) d);
        return String.valueOf(d);
    }

    static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    static String snippet(String s, int max) {
        if (s == null) return "";
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
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
