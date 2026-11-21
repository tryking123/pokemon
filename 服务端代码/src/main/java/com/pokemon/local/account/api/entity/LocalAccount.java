package com.pokemon.local.account.api.entity;

/**
 * 本地账户数据库实体，保存账号标识、凭据摘要和钻石余额
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
public class LocalAccount {

    /** 账户主键 */
    public final String id;

    /** 用户名 */
    public final String username;

    /** 包含盐值和迭代次数的密码摘要 */
    public final String passwordHash;

    /** 当前模拟钻石余额 */
    public final long balance;

    /**
     * 从数据库记录构造本地账户
     *
     * @param id 账户主键
     * @param username 用户名
     * @param passwordHash 密码摘要
     * @param balance 模拟钻石余额
     */
    public LocalAccount(String id, String username, String passwordHash, long balance) {
        this.id = id;
        this.username = username;
        this.passwordHash = passwordHash;
        this.balance = balance;
    }
}
