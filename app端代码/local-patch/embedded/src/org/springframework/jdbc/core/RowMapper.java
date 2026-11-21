package org.springframework.jdbc.core;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 从 Android 数据库结果映射共享 Mapper 所需的实体。
 *
 * @param <T> 映射结果类型
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
@FunctionalInterface
public interface RowMapper<T> {
    /**
     * 映射当前行。
     *
     * @param row 当前结果行
     * @param rowNum 从零开始的行号
     * @return 映射后的对象
     * @throws SQLException 读取行失败
     */
    T mapRow(ResultSet row, int rowNum) throws SQLException;
}
