package com.pokemon.local.guild;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 公会研究员石板补给邮件 Mapper，复用系统邮件表及原888附件类型。
 *
 * @author tongruiyuan
 * @since 2026-11-20 23:32
 */
@Repository
class GuildTabletMailMapper {
    private final JdbcTemplate jdbc;

    /**
     * 绑定已有本地邮件存档。
     * @param jdbc 数据库操作模板
     */
    GuildTabletMailMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 检查固定编号邮件是否已经寄给当前角色。
     * @param roleId 收件角色
     * @param mailId 固定供给邮件编号
     * @return 是否已经创建邮件
     */
    boolean exists(String roleId, String mailId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_system_mail WHERE role_id=? AND id=?",
                Integer.class, roleId, mailId) > 0;
    }

    /**
     * 在业务事务内创建可实际领取的八块原石板邮件。
     * @param roleId 当前角色
     * @param mailId 按角色确定的唯一邮件编号
     * @param now 创建UTC秒
     * @param rewards 原888石板附件配置
     */
    void create(String roleId, String mailId, long now, JsonNode rewards) {
        jdbc.update("INSERT INTO local_system_mail(id,role_id,subject,body,created_at,expires_at) VALUES(?,?,?,?,?,?)",
                mailId, roleId, "超古代石盘解锁材料", "八种石板已送达。领取后请前往公会研究员的超古代石盘界面逐块激活，研究升级仍按原条件消耗。",
                now, now + 30L * 86400);
        int ordinal = 0;
        for (JsonNode reward : rewards) {
            jdbc.update("INSERT INTO local_system_mail_reward(mail_id,ordinal,reward_type,config_id,quantity,asset_type)"
                    + " VALUES(?,?,?,?,?,?)", mailId, ordinal++, 1, reward.path("id").asInt(),
                    reward.path("quantity").asInt(), 888);
        }
    }
}
