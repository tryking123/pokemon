package org.springframework.jdbc.core;

import android.database.Cursor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;

/**
 * 用 Android 游标当前行实现共享 Mapper 实际使用的 ResultSet 读操作。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
final class CursorResultSet implements InvocationHandler {
    private final Cursor cursor;
    private boolean wasNull;

    /**
     * 绑定查询期间使用的游标。
     *
     * @param cursor 当前查询游标
     */
    private CursorResultSet(Cursor cursor) {
        this.cursor = cursor;
    }

    /**
     * 创建只在当前查询生命周期内有效的行视图。
     *
     * @param cursor 当前查询游标
     * @return JDBC 兼容行视图
     */
    static ResultSet view(Cursor cursor) {
        return (ResultSet) Proxy.newProxyInstance(CursorResultSet.class.getClassLoader(),
                new Class<?>[]{ResultSet.class}, new CursorResultSet(cursor));
    }

    /**
     * 直接使用底层游标读取指定类型，兼容较旧的 Android JDBC 接口。
     *
     * @param row 当前查询的兼容行
     * @param column 从一开始的列编号
     * @param type 目标类型
     * @param <T> 返回类型
     * @return 指定类型的列值，数据库空值返回空
     * @throws SQLException 字段不存在或目标类型不受支持
     */
    static <T> T read(ResultSet row, int column, Class<T> type) throws SQLException {
        CursorResultSet result = (CursorResultSet) Proxy.getInvocationHandler(row);
        int index = result.column(column);
        result.wasNull = result.cursor.isNull(index);
        return result.wasNull ? null : type.cast(result.object(index, type));
    }

    /**
     * 将共享 Mapper 的读取方法和 Java 类型转换成游标读取。
     *
     * @param proxy JDBC 行代理
     * @param method 调用的方法
     * @param args 方法参数
     * @return 当前字段按 JDBC 约定转换后的值
     * @throws SQLException 字段不存在或操作不受支持
     */
    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws SQLException {
        String name = method.getName();
        switch (name) {
            case "wasNull":
                return wasNull;
            case "findColumn":
                return column(args[0]) + 1;
            case "isClosed":
                return cursor.isClosed();
            case "toString":
                return "Android SQLite ResultSet";
            case "hashCode":
                return System.identityHashCode(proxy);
            case "equals":
                return proxy == args[0];
            default:
                if (args == null || args.length == 0) {
                    throw new SQLFeatureNotSupportedException(name);
                }
        }
        int index = column(args[0]);
        wasNull = cursor.isNull(index);
        switch (name) {
            case "getString":
                return wasNull ? null : cursor.getString(index);
            case "getInt":
                return wasNull ? 0 : cursor.getInt(index);
            case "getLong":
                return wasNull ? 0L : cursor.getLong(index);
            case "getBoolean":
                return !wasNull && cursor.getLong(index) != 0;
            case "getDate":
                return wasNull ? null : Date.valueOf(cursor.getString(index));
            case "getObject":
                return wasNull ? null : object(index, args.length == 2 ? (Class<?>) args[1] : Object.class);
            default:
                throw new SQLFeatureNotSupportedException(name);
        }
    }

    /**
     * 按 JDBC 的列名或从一开始的索引定位游标列。
     *
     * @param column 编号或查询结果标签
     * @return Android 游标中从零开始的列索引
     * @throws SQLException 字段缺失或下标无效
     */
    private int column(Object column) throws SQLException {
        if (column instanceof Integer) {
            int index = (Integer) column - 1;
            if (index >= 0 && index < cursor.getColumnCount()) {
                return index;
            }
        } else if (column instanceof String) {
            int index = cursor.getColumnIndex((String) column);
            if (index >= 0) {
                return index;
            }
        }
        throw new SQLException("查询结果没有列：" + column);
    }

    /**
     * 获取标量或 JDBC 指定类型的值，保持数值和空值语义。
     *
     * @param index 游标列索引
     * @param type 期望的 Java 类型
     * @return 转换后的非空字段值
     * @throws SQLException 无法转换字段值
     */
    private Object object(int index, Class<?> type) throws SQLException {
        if (type == Integer.class) {
            return cursor.getInt(index);
        }
        if (type == Long.class) {
            return cursor.getLong(index);
        }
        if (type == Boolean.class) {
            return cursor.getLong(index) != 0;
        }
        if (type == String.class) {
            return cursor.getString(index);
        }
        if (type == Date.class) {
            return Date.valueOf(cursor.getString(index));
        }
        if (type == Object.class) {
            switch (cursor.getType(index)) {
                case Cursor.FIELD_TYPE_INTEGER:
                    return cursor.getLong(index);
                case Cursor.FIELD_TYPE_FLOAT:
                    return cursor.getDouble(index);
                case Cursor.FIELD_TYPE_BLOB:
                    return cursor.getBlob(index);
                default:
                    return cursor.getString(index);
            }
        }
        throw new SQLFeatureNotSupportedException("查询结果不支持转换成 " + type.getName());
    }
}
