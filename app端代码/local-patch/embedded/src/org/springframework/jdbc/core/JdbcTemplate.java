package org.springframework.jdbc.core;

import android.database.Cursor;
import android.database.sqlite.SQLiteConstraintException;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteProgram;
import android.database.sqlite.SQLiteStatement;
import com.pokemon.local.embedded.SQLiteDataSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;

/**
 * 将已复用的共享 Mapper 调用绑定到 Android 的本地 SQLite 数据库。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
public final class JdbcTemplate {
    private static final Pattern FOR_UPDATE = Pattern.compile("(?i)\\s+FOR\\s+UPDATE\\s*;?\\s*$");
    private static final Pattern ADD_COLUMN = Pattern.compile(
            "(?is)^ALTER\\s+TABLE\\s+(\\w+)\\s+ADD\\s+COLUMN\\s+IF\\s+NOT\\s+EXISTS\\s+(\\w+)(\\s+.+)$");
    private static final Pattern MERGE = Pattern.compile("(?is)^MERGE\\s+INTO\\s+(\\w+)\\s*"
            + "(?:\\(([\\w,\\s]+)\\)\\s*)?KEY\\s*\\(([\\w,\\s]+)\\)\\s*VALUES\\s*\\(([?,\\s]+)\\)$");

    private final SQLiteDataSource dataSource;
    private final SQLiteDatabase database;

    /**
     * 创建使用同一本地数据库的 Mapper 适配器。
     *
     * @param dataSource 应用 Android SQLite 数据源
     */
    public JdbcTemplate(DataSource dataSource) {
        if (!(dataSource instanceof SQLiteDataSource)) {
            throw new IllegalArgumentException("需要应用本地 SQLiteDataSource");
        }
        this.dataSource = (SQLiteDataSource) dataSource;
        this.database = this.dataSource.database();
    }

    /**
     * 获取脚本初始化使用的数据源。
     *
     * @return 当前嵌入式数据源
     */
    public DataSource getDataSource() {
        return dataSource;
    }

    /**
     * 执行一条兼容 SQLite 的结构语句。
     *
     * @param sql 共享工程的数据库结构语句
     */
    public void execute(String sql) {
        String source = sql.trim();
        Matcher column = ADD_COLUMN.matcher(source);
        if (column.matches()) {
            if (!columns(column.group(1)).contains(column.group(2).toLowerCase(Locale.ROOT))) {
                database.execSQL(translate("ALTER TABLE " + column.group(1) + " ADD COLUMN "
                        + column.group(2) + column.group(3)));
            }
            return;
        }
        database.execSQL(translate(source));
    }

    /**
     * 按 JDBC 约定绑定参数并返回写入行数。
     *
     * @param sql 待执行的写入语句
     * @param args SQL 占位参数
     * @return 影响的行数
     */
    public int update(String sql, Object... args) {
        try {
            Matcher merge = MERGE.matcher(sql.trim());
            return merge.matches() ? merge(merge, args) : write(translate(sql), args);
        } catch (SQLiteConstraintException failure) {
            String message = String.valueOf(failure.getMessage()).toUpperCase(Locale.ROOT);
            if (message.contains("UNIQUE") || message.contains("PRIMARY KEY") || message.contains("PRIMARYKEY")) {
                throw new DuplicateKeyException("SQLite 唯一键冲突", failure);
            }
            throw failure;
        }
    }

    /**
     * 按行映射并收集查询结果。
     *
     * @param sql 待读取的查询语句
     * @param mapper 行映射器
     * @param args SQL 占位参数
     * @param <T> 行结果类型
     * @return 表示所有查询行的列表
     */
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        List<T> results = new ArrayList<>();
        query(sql, (RowCallbackHandler) row -> results.add(mapper.mapRow(row, results.size())), args);
        return results;
    }

    /**
     * 以数据库游标的生命周期逐行消费查询结果。
     *
     * @param sql 待读取的查询语句
     * @param handler 行消费函数
     * @param args SQL 占位参数
     */
    public void query(String sql, RowCallbackHandler handler, Object... args) {
        String readSql = FOR_UPDATE.matcher(translate(sql)).replaceFirst("");
        try (Cursor cursor = database.rawQueryWithFactory((db, driver, table, query) -> {
            bind(query, args);
            return new SQLiteCursor(driver, table, query);
        }, readSql, null, null)) {
            ResultSet row = CursorResultSet.view(cursor);
            while (cursor.moveToNext()) {
                handler.processRow(row);
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("读取 SQLite 查询结果失败", failure);
        }
    }

    /**
     * 获取单列查询的结果列表。
     *
     * @param sql 单列查询语句
     * @param type 期望的单列类型
     * @param args SQL 占位参数
     * @param <T> 返回元素类型
     * @return 单列映射的全部结果
     */
    public <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
        return query(sql, (row, index) -> CursorResultSet.read(row, 1, type), args);
    }

    /**
     * 获取恰好一行的标量查询结果。
     *
     * @param sql 单列查询语句
     * @param type 期望的标量类型
     * @param args SQL 占位参数
     * @param <T> 返回元素类型
     * @return 单行的字段值
     */
    public <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return queryForObject(sql, (row, index) -> CursorResultSet.read(row, 1, type), args);
    }

    /**
     * 获取恰好一行的完整映射结果。
     *
     * @param sql 查询语句
     * @param mapper 单行映射器
     * @param args SQL 占位参数
     * @param <T> 返回对象类型
     * @return 单行结果
     */
    public <T> T queryForObject(String sql, RowMapper<T> mapper, Object... args) {
        List<T> results = query(sql, mapper, args);
        if (results.size() != 1) {
            throw new IllegalStateException("查询应当返回一行，实际为 " + results.size() + " 行");
        }
        return results.get(0);
    }

    /**
     * 读取真实列顺序，供 H2 省略字段列表的 MERGE 和兼容迁移使用。
     *
     * @param table 内部 SQL 表名
     * @return 按结构定义顺序排列的列名
     */
    private List<String> columns(String table) {
        return query("PRAGMA table_info(" + table + ")",
                (row, index) -> row.getString("name").toLowerCase(Locale.ROOT));
    }

    /**
     * 先更新匹配行，没有匹配时再插入，保持旧行及其外键关系。
     *
     * @param merge 已校验 SQL 语法的匹配结果
     * @param args 原始值，按 MERGE 字段顺序排列
     * @return 实际影响行数
     */
    private int merge(Matcher merge, Object[] args) {
        String table = merge.group(1);
        List<String> columns = merge.group(2) == null ? columns(table) : identifiers(merge.group(2));
        List<String> keys = identifiers(merge.group(3));
        if (columns.isEmpty() || columns.size() != args.length || !columns.containsAll(keys)
                || !merge.group(4).replaceAll("\\s+", "").equals(placeholders(args.length))) {
            throw new IllegalArgumentException("MERGE 字段与参数不匹配：" + table);
        }
        List<String> updates = new ArrayList<>();
        List<String> conditions = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (int index = 0; index < columns.size(); index++) {
            String column = columns.get(index);
            if (!keys.contains(column)) {
                updates.add(column + "=?");
                values.add(args[index]);
            }
        }
        for (String key : keys) {
            conditions.add(key + "=?");
            values.add(args[columns.indexOf(key)]);
        }
        if (updates.isEmpty()) {
            // 纯键 MERGE 已存在时也需要返回一行。
            updates.add(keys.get(0) + "=" + keys.get(0));
        }
        boolean ownTransaction = !database.inTransaction();
        if (ownTransaction) {
            database.beginTransactionNonExclusive();
        }
        try {
            int rows = write("UPDATE " + table + " SET " + String.join(",", updates) + " WHERE "
                    + String.join(" AND ", conditions), values.toArray());
            if (rows == 0) {
                rows = write("INSERT INTO " + table + "(" + String.join(",", columns) + ") VALUES ("
                        + placeholders(args.length) + ")", args);
            }
            if (ownTransaction) {
                database.setTransactionSuccessful();
            }
            return rows;
        } finally {
            if (ownTransaction) {
                database.endTransaction();
            }
        }
    }

    /**
     * 执行普通的已绑定写入语句。
     *
     * @param sql SQLite SQL
     * @param args 绑定参数
     * @return SQLite 受影响行数
     */
    private int write(String sql, Object[] args) {
        try (SQLiteStatement statement = database.compileStatement(sql)) {
            bind(statement, args);
            return statement.executeUpdateDelete();
        }
    }

    /**
     * 保留整数、布尔、日期及空值的原始 SQL 类型。
     *
     * @param statement SQLite 已编译命令或查询
     * @param args 有序绑定值
     */
    private static void bind(SQLiteProgram statement, Object[] args) {
        for (int index = 0; index < args.length; index++) {
            Object value = args[index];
            int position = index + 1;
            if (value == null) {
                statement.bindNull(position);
            } else if (value instanceof Boolean) {
                statement.bindLong(position, (Boolean) value ? 1L : 0L);
            } else if (value instanceof Float || value instanceof Double) {
                statement.bindDouble(position, ((Number) value).doubleValue());
            } else if (value instanceof Number) {
                statement.bindLong(position, ((Number) value).longValue());
            } else if (value instanceof byte[]) {
                statement.bindBlob(position, (byte[]) value);
            } else if (value instanceof CharSequence || value instanceof java.sql.Date) {
                statement.bindString(position, value.toString());
            } else {
                throw new IllegalArgumentException("SQL 参数类型不受支持：" + value.getClass().getName());
            }
        }
    }

    /**
     * 迁移共享查询使用的布尔常量和多参数比较函数。
     *
     * @param sql 共享 SQL 语句
     * @return Android SQLite 可识别的写法
     */
    private static String translate(String sql) {
        return sql.replaceAll("(?i)\\bGREATEST\\s*\\(", "MAX(")
                .replaceAll("(?i)\\bLEAST\\s*\\(", "MIN(")
                .replaceAll("(?i)\\bTRUE\\b", "1")
                .replaceAll("(?i)\\bFALSE\\b", "0");
    }

    /**
     * 读取由内部 SQL 模板生成的标识符列表。
     *
     * @param values 原逗号分隔列名
     * @return 小写列名序列
     */
    private static List<String> identifiers(String values) {
        return Arrays.asList(values.trim().toLowerCase(Locale.ROOT).split("\\s*,\\s*"));
    }

    /**
     * 生成一行 SQL 绑定占位符。
     *
     * @param count 值的个数
     * @return 逗号分隔问号
     */
    private static String placeholders(int count) {
        return String.join(",", Collections.nCopies(count, "?"));
    }
}
