package com.pokemon.local.payment;

import com.pokemon.local.common.ApiException;
import com.pokemon.local.role.RoleService;
import java.util.Collections;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 保存代金券批次、有效期、抵扣流水和每日特惠的购买资格。
 * @author tongruiyuan
 * @since 2026-11-20 12:22
 */
@Service
public class VoucherService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    /** 本地数字方案中每个北京自然日可手动领取的限时券面额。 */
    public static final int DAILY_FREE_AMOUNT = 60;
    private final JdbcTemplate jdbc;
    private final RoleService roles;

    /** 初始化代金券账本。
     * @param jdbc 本机存档数据库
     * @param roles 角色事务锁
     */
    public VoucherService(JdbcTemplate jdbc, RoleService roles) {
        this.roles = roles;
        this.jdbc = jdbc;
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_voucher_entry(role_id VARCHAR(36) NOT NULL,"
                + "receipt VARCHAR(100) NOT NULL,expiry BIGINT NOT NULL,amount BIGINT NOT NULL,"
                + "PRIMARY KEY(role_id,receipt,expiry))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_voucher_daygift(role_id VARCHAR(36) NOT NULL,"
                + "day_key BIGINT NOT NULL,paysid INTEGER NOT NULL,PRIMARY KEY(role_id,day_key,paysid))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_voucher_purchase(role_id VARCHAR(36) NOT NULL,"
                + "receipt VARCHAR(100) NOT NULL,paysid INTEGER NOT NULL,order_id VARCHAR(36) NOT NULL,"
                + "PRIMARY KEY(role_id,receipt))");
        correctLegacyDailyExpiry();
    }

    /** 将旧版七日免费批次尚未使用的余额转到原界面承诺的次日零点，保留原交易凭据。 */
    private void correctLegacyDailyExpiry() {
        // 七日试发版的免费批次按领取日分开；该版同到期时间的扣款先冲抵免费部分。
        List<Object[]> rows = jdbc.query("SELECT role_id,receipt,expiry,amount FROM local_voucher_entry"
                + " WHERE receipt LIKE 'dayfree:%' AND amount>0",
                (rs, index) -> new Object[]{rs.getString(1), rs.getString(2), rs.getLong(3), rs.getLong(4)});
        for (Object[] row : rows) {
            String role = (String) row[0];
            String source = (String) row[1];
            long claimedDay = Long.parseLong(source.substring("dayfree:".length()));
            long oldEnd = (long) row[2];
            long end = midnight(claimedDay);
            if (oldEnd != end + 6L * 86400000) continue;
            String marker = "expiry-correction:" + claimedDay;
            if (jdbc.queryForObject("SELECT COUNT(*) FROM local_voucher_entry WHERE role_id=? AND receipt=?",
                    Integer.class, role, marker) > 0) continue;
            long spent = jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM local_voucher_entry"
                    + " WHERE role_id=? AND expiry=? AND amount<0", Long.class, role, oldEnd);
            long remaining = Math.max(0, (long) row[3] + spent);
            jdbc.update("INSERT INTO local_voucher_entry(role_id,receipt,expiry,amount) VALUES(?,?,?,?),(?,?,?,?)",
                    role, marker, oldEnd, -remaining, role, marker, end, remaining);
        }
    }

    /** 获取北京时间自然日结束时刻。
     * @param now 当前毫秒时间
     * @return 次日零点毫秒时间
     */
    private long midnight(long now) {
        return Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate().plusDays(1)
                .atStartOfDay(BEIJING).toInstant().toEpochMilli();
    }

    /** 取得北京自然日的起点，供同一连接识别跨日。
     * @param now 当前毫秒时间
     * @return 当天零点的毫秒时间
     */
    public long today(long now) {
        return Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate().atStartOfDay(BEIJING).toInstant().toEpochMilli();
    }

    /** 查询指定日期是否已发放免费限时券。花完后仍保留领取凭据。
     * @param role 角色编号
     * @param day 当天北京时间零点毫秒
     * @return 当日领取状态
     */
    public boolean claimedFree(String role, long day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_voucher_entry WHERE role_id=? AND receipt=?",
                Integer.class, role, "dayfree:" + day) > 0;
    }

    /** 生成原每日领券面板使用的奖励预览和领取状态。
     * @param role 角色编号
     * @param day 当天北京时间零点毫秒
     * @return 原面板响应
     */
    public Map<String, Object> dailyFree(String role, long day) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errno", 0);
        result.put("info", Collections.singletonList(Collections.singletonMap("award", Arrays.asList(130, 0, DAILY_FREE_AMOUNT))));
        result.put("got", claimedFree(role, day));
        return result;
    }

    /** 按角色和自然日一次发放当天有效的限时券。
     * @param role 角色编号
     * @param viewedDay 最近展示的北京时间日期
     * @return 本次是否实际新增券额
     */
    @Transactional
    public boolean claimFree(String role, long viewedDay) {
        roles.lockRole(role);
        long day = today(System.currentTimeMillis());
        if (day != viewedDay || claimedFree(role, day)) return false;
        long expiry = midnight(day);
        entry(role, "dayfree:" + day, expiry, DAILY_FREE_AMOUNT);
        return true;
    }

    /** 查询两个原每日档位和已确认记录。
     * @param role 角色编号
     * @return 每日特惠原协议面板
     */
    public Map<String, Object> dailyShop(String role) {
        List<Map<String, Object>> goods = new ArrayList<>();
        for (int id : new int[]{2001, 2002}) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("paysid", id);
            row.put("award", Arrays.asList(131, 0, dailyAmount(id)));
            goods.add(row);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("errno", 0);
        result.put("info", goods);
        result.put("record", jdbc.queryForList("SELECT paysid FROM local_voucher_daygift WHERE role_id=? AND day_key=? ORDER BY paysid",
                Integer.class, role, midnight(System.currentTimeMillis())));
        return result;
    }

    /** 获取原 chongzhi 表每日商品的赠券面值。
     * @param sku 原商品编号
     * @return 永久代金券数额
     */
    private int dailyAmount(int sku) {
        return sku == 2001 ? 3000 : sku == 2002 ? 15800 : 0;
    }

    /** 校验已确认购买和当日限购时间。
     * @param role 角色编号
     * @param sku 原商品编号
     * @param now 当前毫秒时间
     * @return 原商品最晚确认时刻
     */
    long deadline(String role, int sku, long now) {
        if (dailyAmount(sku) == 0) {
            return Long.MAX_VALUE;
        }
        long end = midnight(now);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_voucher_daygift WHERE role_id=? AND day_key=? AND paysid=?",
                Integer.class, role, end, sku) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "DAILY_VOUCHER_BOUGHT", "今日已购买该档代金券");
        }
        return end;
    }

    /** 在支付确认事务中发放每日特惠并保存当日记录。
     * @param role 角色编号
     * @param sku 原商品编号
     * @param order 确认订单号
     * @param now 确认毫秒时间
     */
    void deliver(String role, int sku, String order, long now) {
        int amount = dailyAmount(sku);
        if (amount > 0) {
            jdbc.update("INSERT INTO local_voucher_daygift(role_id,day_key,paysid) VALUES(?,?,?)", role, midnight(now), sku);
            entry(role, order, 0, amount);
        }
    }

    /** 读取原钱包的限时券和永久券余额。
     * @param role 角色编号
     * @return 当前有效两类余额
     */
    public Map<String, Object> wallet(String role) {
        long now = System.currentTimeMillis();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("limit_voucher", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM local_voucher_entry WHERE role_id=? AND expiry>?",
                Long.class, role, now));
        result.put("real_voucher", jdbc.queryForObject("SELECT COALESCE(SUM(amount),0) FROM local_voucher_entry WHERE role_id=? AND expiry=0",
                Long.class, role));
        return result;
    }

    /**
     * 在道具消费事务中将原面额计入永久代金券，回执由消费方统一核验。
     * @param role 角色编号
     * @param receipt 唯一消费回执
     * @param amount 永久券面额，单位与商品抵扣面额一致
     */
    @Transactional
    public void grantPermanent(String role, String receipt, long amount) {
        roles.lockRole(role);
        if (amount <= 0) {
            throw new IllegalArgumentException("永久券面额必须为正数");
        }
        entry(role, receipt, 0, amount);
    }

    /** 按最近到期优先、永久券在后的顺序抵扣本笔商品。
     * @param role 角色编号
     * @param receipt 已绑定本次结算的凭据
     * @param amount 商品的原 ifvoucher 面值
     * @param now 当前毫秒时间
     * @return 有效余额是否足够
     */
    boolean spend(String role, String receipt, long amount, long now) {
        Map<Long, Long> batches = new LinkedHashMap<>();
        jdbc.query("SELECT expiry,SUM(amount) FROM local_voucher_entry WHERE role_id=? AND (expiry=0 OR expiry>?) "
                + "GROUP BY expiry ORDER BY CASE WHEN expiry=0 THEN 1 ELSE 0 END,expiry",
                rs -> { batches.put(rs.getLong(1), rs.getLong(2)); }, role, now);
        long total = 0;
        for (long available : batches.values()) {
            total = Math.addExact(total, available);
        }
        if (amount <= 0 || total < amount) {
            return false;
        }
        long remaining = amount;
        for (Map.Entry<Long, Long> batch : batches.entrySet()) {
            long used = Math.min(remaining, batch.getValue());
            if (used > 0) {
                entry(role, receipt, batch.getKey(), -used);
                remaining -= used;
            }
        }
        return true;
    }

    /** 在当前事务中登记某个到期批次的实际变动。
     * @param role 角色编号
     * @param receipt 来源或支出凭据
     * @param expires 批次到期时间，零为永久
     * @param amount 券的实际增减
     */
    private void entry(String role, String receipt, long expires, long amount) {
        jdbc.update("INSERT INTO local_voucher_entry(role_id,receipt,expiry,amount) VALUES(?,?,?,?)",
                role, receipt, expires, amount);
    }

    /** 查询同一操作此前成功兑换的商品。
     * @param role 角色编号
     * @param receipt 原会话凭据
     * @return 已购买的商品编号，首次请求为零
     */
    int purchased(String role, String receipt) {
        List<Integer> result = jdbc.queryForList("SELECT paysid FROM local_voucher_purchase WHERE role_id=? AND receipt=?",
                Integer.class, role, receipt);
        return result.isEmpty() ? 0 : result.get(0);
    }

    /** 将已消费的代金券绑定到对应已确认的游戏订单。
     * @param role 角色编号
     * @param receipt 原会话凭据
     * @param sku 已兑换的原商品编号
     * @param order 已确认订单标识
     */
    void record(String role, String receipt, int sku, String order) {
        jdbc.update("INSERT INTO local_voucher_purchase(role_id,receipt,paysid,order_id) VALUES(?,?,?,?)",
                role, receipt, sku, order);
    }
}
