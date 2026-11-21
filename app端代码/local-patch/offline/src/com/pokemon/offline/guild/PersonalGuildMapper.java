package com.pokemon.offline.guild;

import com.pokemon.offline.guild.api.entity.LocalGuild;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.context.annotation.DependsOn;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Repository;

/**
 * 个人公会与贡献流水 Mapper。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:07
 */
@Repository
@DependsOn("roleMapper")
class PersonalGuildMapper {
    private final JdbcTemplate jdbc;

    /**
     * 注入本地存档模板。
     *
     * @param jdbc 数据库操作模板
     */
    PersonalGuildMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 为新档和旧档建立公会及流水表。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("guild/personal-guild.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取当前角色的活动公会。
     *
     * @param roleId 角色主键
     * @return 活动公会，没有时为空
     */
    LocalGuild active(String roleId) {
        return jdbc.query("SELECT * FROM local_personal_guild WHERE role_id=? AND closed_at IS NULL",
                (row, i) -> new LocalGuild(row), roleId).stream().findFirst().orElse(null);
    }

    /**
     * 保存首任会长及公会信息。
     *
     * @param id 公会主键
     * @param roleId 会长角色主键
     * @param name 公会名称
     * @param intro 公会初始简介和公告
     * @param now 创建时间
     * @return 更新行数
     */
    int create(String id, String roleId, String name, String intro, long now) {
        return jdbc.update("INSERT INTO local_personal_guild(id,role_id,guild_name,intro,notice,created_at)"
                + " VALUES (?,?,?,?,?,?)", id, roleId, name, intro, intro, now);
    }

    /**
     * 保存活动公会的公告或简介。
     *
     * @param id 公会主键
     * @param content 新文本
     * @param intro 是否修改简介
     * @return 更新行数
     */
    int updateText(String id, String content, boolean intro) {
        return jdbc.update(intro ? "UPDATE local_personal_guild SET intro=? WHERE id=?"
                : "UPDATE local_personal_guild SET notice=? WHERE id=?", content, id);
    }

    /**
     * 保存会长的审批开关。
     *
     * @param id 公会主键
     * @param enabled 是否启用自动审批
     * @return 更新行数
     */
    int audit(String id, boolean enabled) {
        return jdbc.update("UPDATE local_personal_guild SET auto_audit=? WHERE id=?", enabled, id);
    }

    /**
     * 标记公会关闭并保留历史。
     *
     * @param id 公会主键
     * @param now 关闭时间
     * @return 更新行数
     */
    int close(String id, long now) {
        return jdbc.update("UPDATE local_personal_guild SET closed_at=? WHERE id=?", now, id);
    }

    /**
     * 查询角色操作是否已结算。
     *
     * @param roleId 角色主键
     * @param receipt 原请求回执
     * @return 已有回执时为真
     */
    boolean hasAction(String roleId, String receipt) {
        return !jdbc.queryForList("SELECT receipt FROM local_personal_guild_receipt WHERE role_id=? AND receipt=?",
                String.class, roleId, receipt).isEmpty();
    }

    /**
     * 保存已完成的公会操作回执。
     *
     * @param roleId 角色主键
     * @param receipt 原请求回执
     * @param guildId 公会主键
     * @param action 操作类型
     * @param now 操作时间
     * @return 更新行数
     */
    int action(String roleId, String receipt, String guildId, String action, long now) {
        return jdbc.update("INSERT INTO local_personal_guild_receipt(role_id,receipt,guild_id,action_name,created_at)"
                + " VALUES (?,?,?,?,?)", roleId, receipt, guildId, action, now);
    }

    /**
     * 查询会长日志供原客户端查看。
     *
     * @param guildId 公会主键
     * @return 由新到旧的最近操作
     */
    List<Map<String, Object>> history(String guildId) {
        return jdbc.query("SELECT action_name,created_at FROM local_personal_guild_receipt WHERE guild_id=?"
                        + " ORDER BY created_at DESC,receipt DESC LIMIT 30",
                (row, i) -> fields("action", row.getString(1), "time", row.getLong(2)), guildId);
    }

    /**
     * 统计角色当天真实提交的建设次数，仅用于记录。
     *
     * @param roleId 角色主键
     * @param day 北京日期的纪元日
     * @return 完成次数
     */
    int donations(String roleId, long day) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_personal_guild_donation WHERE role_id=? AND business_day=?",
                Integer.class, roleId, day);
    }

    /**
     * 按原建设货币统计真实提交次数，不用于限制后续建设。
     * @param roleId 角色编号
     * @param day 北京日期的纪元日
     * @param currency 建设货币类型
     * @return 实际提交次数
     */
    int donations(String roleId, long day, String currency) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_personal_guild_donation"
                + " WHERE role_id=? AND business_day=? AND currency=?", Integer.class, roleId, day, currency);
    }

    /**
     * 汇总当前公会真实建设产生的活跃，旧金币建设每笔按原10活跃迁移。
     * @param guildId 公会编号
     * @return 建设产生的活跃总额
     */
    long donatedVitality(String guildId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(vitality),0) FROM local_personal_guild_donation"
                + " WHERE guild_id=?", Long.class, guildId);
    }

    /**
     * 同时保存捐献凭据与公会建设经验。
     *
     * @param roleId 角色主键
     * @param guildId 公会主键
     * @param day 北京日期的纪元日
     * @param receipt 原请求回执
     * @param now 捐献时间
     * @param xp 增加的建设经验
     * @param amount 增加的可用贡献
     * @param currency 本次建设货币
     * @param vitality 本次真实活跃产出
     * @return 公会经验更新行数
     */
    int donate(String roleId, String guildId, long day, String receipt, long now, int xp, int amount,
               String currency, int vitality) {
        jdbc.update("INSERT INTO local_personal_guild_donation"
                + "(role_id,guild_id,business_day,receipt,created_at,contribution,currency,vitality)"
                + " VALUES (?,?,?,?,?,?,?,?)", roleId, guildId, day, receipt, now, amount, currency, vitality);
        return jdbc.update("UPDATE local_personal_guild SET xp=xp+? WHERE id=?", xp, guildId);
    }

    /**
     * 汇总角色历次捐献所得的贡献。
     *
     * @param roleId 角色主键
     * @return 历史获得的贡献总额
     */
    long earned(String roleId) {
        return jdbc.queryForObject("SELECT COALESCE(SUM(contribution),0)+(SELECT COALESCE(SUM(contribution),0) FROM local_guild_run_rank WHERE role_id=?) FROM local_personal_guild_donation WHERE role_id=?",
                Long.class, roleId, roleId);
    }

    /**
     * 汇总角色已获得并未支付的贡献。
     *
     * @param roleId 角色主键
     * @return 同一存档快照中可使用的贡献
     */
    long balance(String roleId) {
        return jdbc.queryForObject("SELECT COALESCE((SELECT SUM(contribution) FROM local_personal_guild_donation"
                        + " WHERE role_id=?),0)+(SELECT COALESCE(SUM(contribution),0) FROM local_guild_run_rank WHERE role_id=?)-COALESCE((SELECT SUM(spent) FROM local_personal_guild_purchase"
                        + " WHERE role_id=?),0)", Long.class, roleId, roleId, roleId);
    }

    /**
     * 汇总当前重置周期内各商品的已购份数。
     *
     * @param roleId 角色主键
     * @param period 周期起始纪元日
     * @return 各原版商品索引的购买数量
     */
    List<Map<String, Object>> bought(String roleId, long period) {
        return jdbc.query("SELECT offer_id,SUM(bundles) FROM local_personal_guild_purchase WHERE role_id=?"
                        + " AND reset_period=? GROUP BY offer_id ORDER BY offer_id",
                (row, i) -> fields("index", row.getInt(1), "buy_num", row.getInt(2)), roleId, period);
    }

    /**
     * 获取同一购买请求的已提交数量。
     *
     * @param roleId 角色主键
     * @param receipt 原请求回执
     * @return 商品索引及数量，回执不存在时为空
     */
    int[] purchaseReceipt(String roleId, String receipt) {
        return jdbc.query("SELECT offer_id,bundles FROM local_personal_guild_purchase WHERE role_id=? AND receipt=?",
                (row, i) -> new int[] { row.getInt(1), row.getInt(2) }, roleId, receipt).stream().findFirst().orElse(null);
    }

    /**
     * 保存购买回执及扣除贡献的流水。
     *
     * @param roleId 角色主键
     * @param receipt 原请求回执
     * @param period 重置周期起始日
     * @param offerId 原商品索引
     * @param bundles 成功购买的份数
     * @param spent 扣除的贡献
     * @return 更新行数
     */
    int purchase(String roleId, String receipt, long period, int offerId, int bundles, long spent) {
        return jdbc.update("INSERT INTO local_personal_guild_purchase(role_id,receipt,reset_period,offer_id,bundles,spent)"
                + " VALUES (?,?,?,?,?,?)", roleId, receipt, period, offerId, bundles, spent);
    }

    /**
     * 组装查询投影中的两列。
     *
     * @param key 第一列的业务字段名
     * @param value 第一列的值
     * @param otherKey 第二列的业务字段名
     * @param otherValue 第二列的值
     * @return 包含两列的查询投影
     */
    private Map<String, Object> fields(String key, Object value, String otherKey, Object otherValue) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(key, value);
        values.put(otherKey, otherValue);
        return values;
    }
}
