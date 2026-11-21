package com.pokemon.local.account;

import java.util.List;

import com.pokemon.local.account.api.entity.LocalAccount;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * 本地账号与会话 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
@Repository
public class AccountMapper {

    private static final RowMapper<LocalAccount> ACCOUNT = (row, index) -> new LocalAccount(
            row.getString("id"), row.getString("username"),
            row.getString("password_hash"), row.getLong("balance"));

    private final JdbcTemplate jdbc;

    /**
     * 初始化本地账号 Mapper
     *
     * @param jdbc JDBC 操作模板
     */
    public AccountMapper(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按用户名查询账号
     *
     * @param username 用户名
     * @return 匹配账号，账号不存在时返回空
     */
    public LocalAccount findByUsername(String username) {
        List<LocalAccount> rows = jdbc.query("SELECT * FROM local_account WHERE username = ?", ACCOUNT, username);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 初始化账号
     *
     * @param id 账号主键
     * @param username 用户名
     * @param passwordHash 密码摘要
     * @return 插入行数
     */
    public int insertAccount(String id, String username, String passwordHash) {
        return jdbc.update("INSERT INTO local_account (id, username, password_hash) VALUES (?, ?, ?)",
                id, username, passwordHash);
    }

    /**
     * 持久化会话
     *
     * @param digest 令牌摘要
     * @param accountId 账号主键
     * @param expiresAt 会话过期时间戳，单位毫秒
     * @return 插入行数
     */
    public int insertSession(String digest, String accountId, long expiresAt) {
        return jdbc.update("INSERT INTO local_session (token_digest, account_id, expires_at) VALUES (?, ?, ?)",
                digest, accountId, expiresAt);
    }

    /**
     * 查询有效会话对应的账号
     *
     * @param digest 令牌摘要
     * @param now 当前时间戳，单位毫秒
     * @return 匹配账号，会话无效时返回空
     */
    public LocalAccount findActiveAccount(String digest, long now) {
        List<LocalAccount> rows = jdbc.query("SELECT a.* FROM local_account a JOIN local_session s "
                + "ON s.account_id = a.id WHERE s.token_digest = ? AND s.expires_at > ?", ACCOUNT, digest, now);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 注销尚未过期的会话
     *
     * @param digest 令牌摘要
     * @param now 当前时间戳，单位毫秒
     * @return 删除行数
     */
    public int deleteActiveSession(String digest, long now) {
        return jdbc.update("DELETE FROM local_session WHERE token_digest = ? AND expires_at > ?", digest, now);
    }

    /**
     * 清理已过期的会话
     *
     * @param now 当前时间戳，单位毫秒
     * @return 删除行数
     */
    public int deleteExpiredSessions(long now) {
        return jdbc.update("DELETE FROM local_session WHERE expires_at <= ?", now);
    }
}
