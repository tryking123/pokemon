package com.pokemon.offline.platform.dao;

/**
 * SQLite 唯一键冲突，供共享角色存档业务捕获。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
public class DuplicateKeyException extends RuntimeException {
    /**
     * 保留唯一键冲突的底层原因。
     *
     * @param message 错误说明
     * @param cause 数据库错误
     */
    public DuplicateKeyException(String message, Throwable cause) {
        super(message, cause);
    }
}
