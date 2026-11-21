package org.springframework.jdbc.core;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * 逐行消费查询结果，供共享邮件 Mapper 聚合主表和附件。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
@FunctionalInterface
public interface RowCallbackHandler {
    /**
     * 消费当前行。
     *
     * @param row 当前结果行
     * @throws SQLException 读取行失败
     */
    void processRow(ResultSet row) throws SQLException;
}
