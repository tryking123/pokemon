package com.pokemon.offline.payment;

import com.pokemon.offline.role.RoleService;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本地活动券的每日货架、独立实例及一次性兑换账本。
 * @author tongruiyuan
 * @since 2026-11-20 15:32
 */
@Service
public class LocalCouponService {
    private static final ZoneId BEIJING = ZoneId.of("Asia/Shanghai");
    private static final int[] WON = {1300, 6500, 13000, 26000, 40000, 65000, 130000};
    private static final int[] CENTS = {99, 499, 999, 1999, 2999, 4999, 9999};
    private final JdbcTemplate jdbc;
    private final RoleService roles;

    /** 初始化角色专属券实例与每日唯一来源。
     * @param jdbc 本地角色数据库
     * @param roles 角色写锁及资产服务
     */
    public LocalCouponService(JdbcTemplate jdbc, RoleService roles) {
        this.jdbc = jdbc;
        this.roles = roles;
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_coupon_instance (role_id VARCHAR(36) NOT NULL,"
                + "day_key BIGINT NOT NULL, kind INTEGER NOT NULL, uid VARCHAR(36) NOT NULL UNIQUE,"
                + "state INTEGER NOT NULL, PRIMARY KEY(role_id, day_key, kind))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_coupon_redemption (role_id VARCHAR(36) NOT NULL,"
                + "receipt VARCHAR(80) NOT NULL, uid VARCHAR(36) NOT NULL, PRIMARY KEY(role_id,receipt))");
    }

    /** 检查本地签发目录。
     * @param kind 券模板编号
     * @return 是否为随包或本地发行的有效券
     */
    private boolean valid(long kind) {
        return kind >= 101 && kind <= 107 || kind >= 201 && kind <= 207 || kind == 1004;
    }

    /** 查询券的本地面额。
     * @param kind 合法券模板编号
     * @return 使用一张券发放的钻石
     */
    private int diamonds(int kind) {
        if (kind == 1004) return 6000;
        return kind < 200 ? WON[kind - 101] / 10 : CENTS[kind - 201];
    }

    /** 格式化每日货架名称。
     * @param kind 合法券模板编号
     * @return 货架展示名称
     */
    private String name(int kind) {
        if (kind == 1004) return "本地充值券 1004";
        if (kind < 200) return "₩" + WON[kind - 101] + " 活动券";
        int cents = CENTS[kind - 201];
        return "$" + cents / 100 + "." + cents % 100 + " 活动券";
    }

    /** 读取当日券状态；领取时验证预览日期并生成唯一券号。北京零点后重新发行。
     * @param role 已认证角色
     * @param id 所选券模板
     * @param issue 是否申请发行
     * @param viewedDay 领取前面板展示的北京日期
     * @return 展示及领取结果
     */
    @Transactional
    public Map<String,Object> panel(String role, long id, boolean issue, long viewedDay) {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("errno",1);
        if (!valid(id)) return result;
        int kind = (int) id;
        roles.lockRole(role);
        long day = LocalDate.now(BEIJING).toEpochDay();
        List<Object[]> rows = jdbc.query("SELECT uid,state FROM local_coupon_instance WHERE role_id=? AND day_key=? AND kind=?",
                (rs,n) -> new Object[]{rs.getString(1),rs.getInt(2)},role,day,kind);
        String uid = rows.isEmpty() ? "" : (String) rows.get(0)[0];
        int status = rows.isEmpty() ? 0 : (int) rows.get(0)[1];
        boolean success = !issue || viewedDay == day && status == 0;
        if (issue && success) {
            uid = UUID.randomUUID().toString();
            status = 1;
            jdbc.update("INSERT INTO local_coupon_instance(role_id,day_key,kind,uid,state) VALUES(?,?,?,?,1)",role,day,kind,uid);
        }
        result.put("errno",success ? 0 : 1);
        result.put("kind",kind);
        result.put("day",day);
        result.put("uid",uid);
        result.put("status",status);
        result.put("name",name(kind));
        result.put("redeem",diamonds(kind));
        result.put("coins",kind < 200 ? 100000 : 0);
        result.put("diamonds",kind >= 201 && kind <= 207 ? 10 : 0);
        return result;
    }

    /** 原子消费当日有效的角色券实例并给对应资产入账。
     * @param role 已认证角色
     * @param uid 货架展示的具体券实例
     * @param command 原客户端操作编号
     * @param receipt 充值券请求的连接及会话回执；按实例操作时为空
     * @return 实际新增金币、钻石；无可用券则返回空映射
     */
    @Transactional
    public Map<String,Object> spend(String role, String uid, int command, String receipt) {
        Map<String,Object> award = new LinkedHashMap<>();
        if (uid == null || uid.length() != 36 || command != 377 && command != 378 && command != 10410) return award;
        roles.lockRole(role);
        if (command == 10410 && (receipt == null || jdbc.queryForObject(
                "SELECT COUNT(*) FROM local_coupon_redemption WHERE role_id=? AND receipt=?",Integer.class,role,receipt) > 0)) return award;
        long day = LocalDate.now(BEIJING).toEpochDay();
        List<Integer> kinds = jdbc.queryForList("SELECT kind FROM local_coupon_instance WHERE role_id=? AND day_key=? AND uid=? AND state=1",
                Integer.class,role,day,uid);
        if (kinds.isEmpty()) return award;
        int kind = kinds.get(0);
        if (!valid(kind) || (kind == 1004) != (command == 10410)) return award;
        int changed = jdbc.update("UPDATE local_coupon_instance SET state=? WHERE role_id=? AND day_key=? AND uid=? AND state=1",
                command == 378 ? 3 : 2,role,day,uid);
        if (changed != 1) return award;
        if (command == 10410) {
            jdbc.update("INSERT INTO local_coupon_redemption(role_id,receipt,uid) VALUES(?,?,?)",role,receipt,uid);
        }
        int coins = command == 378 && kind < 200 ? 100000 : 0;
        int gems = command == 378 ? (kind < 200 ? 0 : 10) : diamonds(kind);
        roles.addCurrency(role,coins,gems,0);
        if (coins > 0) award.put("coins",coins);
        if (gems > 0) award.put("diamonds",gems);
        return award;
    }
}
