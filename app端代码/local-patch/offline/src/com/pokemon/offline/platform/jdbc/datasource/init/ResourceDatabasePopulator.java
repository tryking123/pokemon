package com.pokemon.offline.platform.jdbc.datasource.init;

import com.pokemon.offline.embedded.SQLiteDataSource;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;

/**
 * 在同一 Android 事务中执行 UTF-8 共享 SQL 初始化脚本。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:49
 */
public final class ResourceDatabasePopulator {
    private final ClassPathResource[] scripts;

    /**
     * 保存待执行的结构脚本列表。
     *
     * @param scripts 已打入应用的数据表资源
     */
    public ResourceDatabasePopulator(ClassPathResource... scripts) {
        this.scripts = scripts.clone();
    }

    /**
     * 执行依赖有序的结构脚本。任一结构失败时回滚本次执行。
     *
     * @param source 嵌入式数据库数据源
     */
    public void execute(DataSource source) {
        JdbcTemplate jdbc = new JdbcTemplate(source);
        android.database.sqlite.SQLiteDatabase database = ((SQLiteDataSource) source).database();
        boolean own = !database.inTransaction();
        if (own) {
            database.beginTransactionNonExclusive();
        }
        try {
            for (ClassPathResource script : scripts) {
                for (String statement : readStatements(script)) {
                    jdbc.execute(statement);
                }
            }
            if (own) {
                database.setTransactionSuccessful();
            }
        } finally {
            if (own) {
                database.endTransaction();
            }
        }
    }

    /**
     * 读取 SQL 语句，保留引号内的文本并跳过行注释和块注释。
     *
     * @param script 数据表脚本
     * @return 按声明顺序独立执行的结构语句
     */
    private List<String> readStatements(ClassPathResource script) {
        StringBuilder body = new StringBuilder();
        try (Reader reader = new InputStreamReader(script.getInputStream(), StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            for (int size; (size = reader.read(buffer)) != -1; ) {
                body.append(buffer, 0, size);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("读取 SQLite 初始化脚本失败", failure);
        }
        List<String> statements = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < body.length(); index++) {
            char ch = body.charAt(index);
            char next = index + 1 < body.length() ? body.charAt(index + 1) : 0;
            if (quote != 0) {
                sql.append(ch);
                if (ch == quote) {
                    if (next == quote) {
                        sql.append(next);
                        index++;
                    } else {
                        quote = 0;
                    }
                }
            } else if (ch == '\'' || ch == '"') {
                quote = ch;
                sql.append(ch);
            } else if (ch == '-' && next == '-') {
                while (index + 1 < body.length() && body.charAt(index + 1) != '\n') {
                    index++;
                }
                sql.append(' ');
            } else if (ch == '/' && next == '*') {
                int end = body.indexOf("*/", index + 2);
                if (end < 0) {
                    throw new IllegalArgumentException("SQLite 初始化脚本的注释未闭合");
                }
                index = end + 1;
                sql.append(' ');
            } else if (ch == ';') {
                addStatement(statements, sql);
            } else if (ch != '\ufeff') {
                sql.append(ch);
            }
        }
        if (quote != 0) {
            throw new IllegalArgumentException("SQLite 初始化脚本的引号未闭合");
        }
        addStatement(statements, sql);
        return statements;
    }

    /**
     * 加入已完成的非空语句并重置当前缓冲区。
     *
     * @param statements 目标语句列表
     * @param sql 当前语句
     */
    private void addStatement(List<String> statements, StringBuilder sql) {
        String statement = sql.toString().trim();
        if (!statement.isEmpty()) {
            statements.add(statement);
        }
        sql.setLength(0);
    }
}
