package com.pokemon.offline.save;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 在导入暂存库中重新分配账号与角色编号，同步业务引用、复合键及状态快照。
 *
 * @author tongruiyuan
 * @since 2026-11-22 23:36
 */
public final class SaveIdentityRemapper {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DISPLAY_FIELDS = new HashSet<>(Arrays.asList(
            "name", "username", "nickname", "player_name", "guild_name", "title", "subject", "body",
            "intro", "notice", "text", "password_hash", "token_digest"));
    private static final Set<String> ACCOUNT_FIELDS = new HashSet<>(Arrays.asList(
            "accountid", "account_id", "userid", "user_id"));

    /** 仅提供暂存数据库迁移操作。 */
    private SaveIdentityRemapper() { }

    /**
     * 重新分配整个暂存库的账号和角色主键，调用方在同一事务内启用延迟外键校验并提交。
     *
     * @param db 已完成结构核验并开启写事务的暂存数据库
     */
    public static void regenerate(SQLiteDatabase db) {
        if (!db.inTransaction()) throw new IllegalStateException("存档身份转换需要数据库事务");
        Map<String, String> accounts = identities(db, "local_account", true);
        Map<String, String> roles = identities(db, "local_game_role", false);
        List<String> tables = new ArrayList<>();
        try (Cursor row = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'local_*'", null)) {
            while (row.moveToNext()) tables.add(row.getString(0));
        }
        for (String table : tables) {
            try (Cursor columns = db.rawQuery("PRAGMA table_info(" + quote(table) + ")", null)) {
                while (columns.moveToNext()) {
                    String column = columns.getString(columns.getColumnIndexOrThrow("name"));
                    String field = column.toLowerCase(Locale.ROOT);
                    if (ACCOUNT_FIELDS.contains(field) || (table.equals("local_account") && field.equals("id"))) {
                        replaceAccountIds(db, table, column, accounts);
                    } else if (!DISPLAY_FIELDS.contains(field)) {
                        String type = columns.getString(columns.getColumnIndexOrThrow("type")).toUpperCase(Locale.ROOT);
                        if (type.contains("CHAR") || type.contains("TEXT") || type.contains("CLOB")) {
                            replaceReferences(db, table, column, accounts, roles);
                        }
                    }
                }
            }
        }
        try (Cursor invalid = db.rawQuery("PRAGMA foreign_key_check", null)) {
            if (invalid.moveToFirst()) throw new IllegalStateException("存档身份转换后存在失效的外键引用");
        }
    }

    /**
     * 读取全部旧主键，再生成与原库及同批新编号均不冲突的新主键。
     *
     * @param db 暂存数据库
     * @param table 身份主表
     * @param account 是否生成原协议支持的正整数账号编号
     * @return 旧编号到新编号的映射
     */
    private static Map<String, String> identities(SQLiteDatabase db, String table, boolean account) {
        Map<String, String> values = new LinkedHashMap<>();
        try (Cursor row = db.rawQuery("SELECT id FROM " + quote(table), null)) {
            while (row.moveToNext()) {
                String id = row.getString(0);
                if (id == null || (account ? !id.matches("[1-9][0-9]*")
                        : !UUID.fromString(id).toString().equalsIgnoreCase(id))) {
                    throw new IllegalArgumentException("存档身份主键格式错误");
                }
                values.put(id, null);
            }
        }
        Set<String> reserved = new HashSet<>(values.keySet());
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String id;
            do {
                id = account ? Integer.toString(100000000 + RANDOM.nextInt(1900000000)) : UUID.randomUUID().toString();
            } while (!reserved.add(id));
            entry.setValue(id);
        }
        return values;
    }

    /**
     * 只按完整值更新账号主外键，保留与旧账号数字相同的区服、金额和配置编号。
     * @param db 暂存数据库
     * @param table 所属业务表
     * @param column 账号编号列
     * @param accounts 账号编号映射
     */
    private static void replaceAccountIds(SQLiteDatabase db, String table, String column, Map<String, String> accounts) {
        String target = quote(column);
        String sql = "UPDATE " + quote(table) + " SET " + target + "=? WHERE " + target + "=?";
        for (Map.Entry<String, String> pair : accounts.entrySet()) {
            db.execSQL(sql, new Object[]{pair.getValue(), pair.getKey()});
        }
    }

    /**
     * 检查业务文本中的稳定编号、带角色前后缀的派生键及序列化对象。
     * @param db 暂存数据库
     * @param table 所属业务表
     * @param column 待检查的列
     * @param accounts 账号编号映射
     * @param roles 角色编号映射
     */
    private static void replaceReferences(SQLiteDatabase db, String table, String column,
                                          Map<String, String> accounts, Map<String, String> roles) {
        String col = quote(column);
        String source = quote(table);
        String update = "UPDATE " + source + " SET " + col + "=? WHERE rowid=?";
        String select = "SELECT rowid," + col + " FROM " + source + " WHERE typeof(" + col + ")='text' AND "
                + "(length(" + col + ")>=36 OR substr(" + col + ",1,1) IN ('{','[')) ORDER BY rowid";
        try (Cursor row = db.rawQuery(select, null)) {
            while (row.moveToNext()) {
                String old = row.getString(1);
                String value = old;
                if (old.startsWith("{") || old.startsWith("[")) {
                    if (containsRole(old, roles) || old.toLowerCase(Locale.ROOT).contains("userid")
                            || old.toLowerCase(Locale.ROOT).contains("accountid")
                            || old.contains("_id")) {
                        try {
                            JsonNode tree = JSON.readTree(old);
                            if (rewriteTree(tree, accounts, roles)) value = tree.toString();
                        } catch (IOException failure) {
                            throw new IllegalArgumentException("无法转换存档中的身份快照：" + table + "." + column, failure);
                        }
                    }
                } else {
                    value = replaceRole(old, roles);
                }
                if (!old.equals(value)) db.execSQL(update, new Object[]{value, row.getLong(0)});
            }
        }
    }

    /** 检查快照是否含有需要转换的角色编号。
     * @param value 快照文本
     * @param roles 角色编号映射
     * @return 是否命中旧编号
     */
    private static boolean containsRole(String value, Map<String, String> roles) {
        for (String id : roles.keySet()) if (value.contains(id)) return true;
        return false;
    }

    /** 转换复合键或普通角色引用。
     * @param value 待转换的字符串
     * @param roles 角色编号映射
     * @return 替换后的字符串
     */
    private static String replaceRole(String value, Map<String, String> roles) {
        for (Map.Entry<String, String> pair : roles.entrySet()) {
            value = value.replace(pair.getKey(), pair.getValue());
        }
        return value;
    }

    /** 更新快照字段和以角色编号作索引的排行榜条目。
     * @param tree 已解析的业务快照
     * @param accounts 账号编号映射
     * @param roles 角色编号映射
     * @return 快照是否有改动
     */
    private static boolean rewriteTree(JsonNode tree, Map<String, String> accounts, Map<String, String> roles) {
        boolean changed = false;
        if (tree.isArray()) {
            ArrayNode list = (ArrayNode) tree;
            for (int i = 0; i < list.size(); i++) {
                JsonNode old = list.get(i);
                JsonNode next = remappedValue("", old, accounts, roles);
                if (next != old) { list.set(i, next); changed = true; }
                else changed |= rewriteTree(old, accounts, roles);
            }
        } else if (tree.isObject()) {
            ObjectNode object = (ObjectNode) tree;
            List<String> keys = new ArrayList<>();
            object.fieldNames().forEachRemaining(keys::add);
            for (String key : keys) {
                String field = key.toLowerCase(Locale.ROOT);
                if (DISPLAY_FIELDS.contains(field)) continue;
                JsonNode old = object.get(key);
                JsonNode next = remappedValue(field, old, accounts, roles);
                if (next == old) changed |= rewriteTree(old, accounts, roles);
                String newKey = replaceRole(key, roles);
                if (!newKey.equals(key)) {
                    if (object.has(newKey)) throw new IllegalStateException("角色快照新键重复");
                    object.remove(key);
                }
                if (next != old || !newKey.equals(key)) { object.set(newKey, next); changed = true; }
            }
        }
        return changed;
    }

    /** 按字段语义保留数值和字符串类型。
     * @param field JSON 字段名
     * @param old 原节点
     * @param accounts 账号编号映射
     * @param roles 角色编号映射
     * @return 新节点或无需转换的原节点
     */
    private static JsonNode remappedValue(String field, JsonNode old, Map<String, String> accounts, Map<String, String> roles) {
        if (ACCOUNT_FIELDS.contains(field)) {
            String id = accounts.get(old.asText());
            if (id != null) return old.isIntegralNumber() ? LongNode.valueOf(Long.parseLong(id)) : TextNode.valueOf(id);
        }
        if (old.isTextual()) {
            String text = old.textValue();
            String next = replaceRole(text, roles);
            if (!next.equals(text)) return TextNode.valueOf(next);
        }
        return old;
    }

    /** 安全引用来自 SQLite 元数据的表名或列名。
     * @param name 原始标识符
     * @return 可用于 SQL 的带引号标识符
     */
    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
