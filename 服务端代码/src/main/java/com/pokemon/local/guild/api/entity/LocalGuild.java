package com.pokemon.local.guild.api.entity;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 个人公会数据库实体，用于显示本地会长及建设进度。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:07
 */
public final class LocalGuild {
    /** 本地公会主键 */
    public final String id;
    /** 会长角色主键 */
    public final String roleId;
    /** 公会名称 */
    public final String name;
    /** 列表显示的公会简介 */
    public final String intro;
    /** 公会面板显示的公告 */
    public final String notice;
    /** 建会以来累计公会经验 */
    public final long xp;
    /** 会长保存的审批偏好 */
    public final boolean autoAudit;
    /** 建会时间，UTC 秒 */
    public final long createdAt;

    /**
     * 将活动公会存档读取到实体。
     *
     * @param row 当前查询行
     * @throws SQLException 读取存档失败
     */
    public LocalGuild(ResultSet row) throws SQLException {
        id = row.getString("id");
        roleId = row.getString("role_id");
        name = row.getString("guild_name");
        intro = row.getString("intro");
        notice = row.getString("notice");
        xp = row.getLong("xp");
        autoAudit = row.getBoolean("auto_audit");
        createdAt = row.getLong("created_at");
    }
}
