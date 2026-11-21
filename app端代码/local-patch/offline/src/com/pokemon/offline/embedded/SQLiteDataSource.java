package com.pokemon.offline.embedded;

import android.database.sqlite.SQLiteDatabase;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Objects;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * 为嵌入式数据库适配层和桌面 Mapper 共用构造签名保存同一 Android 数据库。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
public final class SQLiteDataSource implements DataSource {
    private final SQLiteDatabase database;

    /**
     * 绑定应用私有数据库。
     *
     * @param database 已打开的 Android 数据库
     */
    public SQLiteDataSource(SQLiteDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    /**
     * 获取事务管理器和 Mapper 共用的 Android 数据库。
     *
     * @return 当前数据库
     */
    public SQLiteDatabase database() {
        return database;
    }

    /** {@inheritDoc} */
    @Override
    public Connection getConnection() throws SQLException {
        throw new SQLFeatureNotSupportedException("应用只使用 Android SQLiteDatabase");
    }

    /** {@inheritDoc} */
    @Override
    public Connection getConnection(String user, String password) throws SQLException {
        return getConnection();
    }

    /** {@inheritDoc} */
    @Override
    public PrintWriter getLogWriter() {
        return null;
    }

    /** {@inheritDoc} */
    @Override
    public void setLogWriter(PrintWriter writer) {
        throw new UnsupportedOperationException("由应用配置数据库日志");
    }

    /** {@inheritDoc} */
    @Override
    public void setLoginTimeout(int seconds) {
        throw new UnsupportedOperationException("本地数据库无需登录");
    }

    /** {@inheritDoc} */
    @Override
    public int getLoginTimeout() {
        return 0;
    }

    /** {@inheritDoc} */
    @Override
    public Logger getParentLogger() {
        return Logger.getLogger(SQLiteDataSource.class.getName());
    }

    /** {@inheritDoc} */
    @Override
    public <T> T unwrap(Class<T> type) throws SQLException {
        if (!isWrapperFor(type)) {
            throw new SQLException("无可展开的数据源类型：" + type.getName());
        }
        return type.cast(this);
    }

    /** {@inheritDoc} */
    @Override
    public boolean isWrapperFor(Class<?> type) {
        return type.isInstance(this);
    }
}
