package com.pokemon.local.role.api.entity;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 本地角色数据库实体，用于读取角色状态和场景存档
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
public final class LocalRole {

    /** 角色唯一标识 */
    public final String id;
    /** 所属本地账号 */
    public final String accountId;
    /** 所属区服 */
    public final int realmId;
    /** 角色名称 */
    public final String name;
    /** 已成功改名的次数 */
    public final int renameCount;
    /** 当前佩戴的头像，旧档缺省为性别对应的初始头像 */
    public final int avatar;
    /** 角色性别，0 男、1 女 */
    public final int sex;
    /** 当前等级 */
    public final int level;
    /** 当前经验 */
    public final long exp;
    /** 原版训练师晋升等阶，取值 0 至 2 */
    public final int stage;
    /** 角色金币 */
    public final long coins;
    /** 角色钻石 */
    public final long diamonds;
    /** 已从驻防邮件领入的道馆积分。 */
    public final long dojoPoints;
    /** 存档场景编号 */
    public final int sceneId;
    /** 场景横坐标 */
    public final int x;
    /** 场景纵坐标 */
    public final int y;
    /** 建角时的 UTC 秒数 */
    public final long createdAt;
    /** 从副本离开时返回的场景编号，可为空 */
    public final Integer returnSceneId;
    /** 返回点横坐标，可为空 */
    public final Integer returnX;
    /** 返回点纵坐标，可为空 */
    public final Integer returnY;

    /**
     * 从持久记录读取角色属性
     *
     * @param row 查询结果中的当前记录
     * @throws SQLException 读取记录失败
     */
    public LocalRole(ResultSet row) throws SQLException {
        id = row.getString("id");
        accountId = row.getString("account_id");
        realmId = row.getInt("realm_id");
        name = row.getString("name");
        renameCount = row.getInt("rename_count");
        sex = row.getInt("sex");
        int storedAvatar = row.getInt("avatar");
        avatar = storedAvatar == 0 ? sex + 1 : storedAvatar;
        level = row.getInt("level");
        exp = row.getLong("exp");
        stage = row.getInt("stage");
        coins = row.getLong("coins");
        diamonds = row.getLong("diamonds");
        dojoPoints = row.getLong("dojo_points");
        sceneId = row.getInt("scene_id");
        x = row.getInt("x");
        y = row.getInt("y");
        createdAt = row.getLong("created_at");
        returnSceneId = optionalInt(row, "return_scene_id");
        returnX = optionalInt(row, "return_x");
        returnY = optionalInt(row, "return_y");
    }

    /**
     * 读取可空坐标，兼容 Android 的 JDBC 接口。
     *
     * @param row 当前存档行
     * @param column 返回点字段名
     * @return 保存的坐标，未保存时为空
     * @throws SQLException 字段读取失败
     */
    private static Integer optionalInt(ResultSet row, String column) throws SQLException {
        int value = row.getInt(column);
        return row.wasNull() ? null : value;
    }
}
