package com.pokemon.offline.save;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 按 admin 账号及其各区角色划分导入导出数据，分别处理邮件附件和区服共享账本。
 *
 * @author tongruiyuan
 * @since 2026-11-22 23:41
 */
public final class SaveOwnership {
    private static final String ACCOUNT = "SELECT id FROM local_account WHERE username='admin'";
    private static final String ROLES = "SELECT id FROM local_game_role WHERE account_id IN (" + ACCOUNT + ")";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** 仅提供账号存档划分操作。 */
    private SaveOwnership() { }

    /**
     * 从暂存副本剔除其他账号及全服数据，只留下 admin 角色的业务进度及其世界首领榜项。
     * @param db 处于写事务的暂存数据库
     */
    public static void isolateAdmin(SQLiteDatabase db) {
        requireTransaction(db);
        if (!hasRow(db, ACCOUNT)) throw new IllegalArgumentException("存档中没有 admin 账号");
        filter(db, true);
    }

    /**
     * 在导入事务中移除目标设备旧 admin 存档及其榜单贡献。
     * @param db 处于写事务的目标数据库
     */
    public static void removeAdmin(SQLiteDatabase db) {
        requireTransaction(db);
        filter(db, false);
    }

    /**
     * 按直接外键及三类间接主键过滤个人数据，再移除账号主表行。
     * @param db 正在处理的数据库
     * @param isolate 是否只保留 admin 个人数据
     */
    private static void filter(SQLiteDatabase db, boolean isolate) {
        Set<String> tables = new HashSet<>();
        try (Cursor row = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'local_*'", null)) {
            while (row.moveToNext()) tables.add(row.getString(0));
        }
        String membership = isolate ? " NOT IN (" : " IN (";
        removeChildren(db, tables, "local_luxury_gift_order", "order_id", "local_game_payment_order", membership);
        removeChildren(db, tables, "local_system_mail_reward", "mail_id", "local_system_mail", membership);
        removeChildren(db, tables, "local_trainer_attributes", "equipment_id", "local_adventure_equipment_instance", membership);
        if (tables.remove("local_world_boss")) filterBoss(db, isolate);
        if (tables.remove("local_tower_layer")) {
            if (isolate) db.delete("local_tower_layer", null, null);
            else db.execSQL("UPDATE local_tower_layer SET cleared=MAX(0,cleared-(SELECT COUNT(*) FROM local_tower_clear c "
                    + "WHERE c.layer=local_tower_layer.layer AND c.role_id IN (" + ROLES + ")))");
        }
        for (String seed : new String[]{"local_activity_calendar", "local_dojo_station_lock"}) {
            if (tables.remove(seed) && isolate) db.delete(seed, null, null);
        }
        tables.remove("local_game_role");
        tables.remove("local_account");
        if (tables.remove("local_session")) {
            db.delete("local_session", isolate ? null : "account_id IN (" + ACCOUNT + ")", null);
        }
        for (String table : tables) {
            Set<String> columns = new HashSet<>();
            try (Cursor row = db.rawQuery("PRAGMA table_info(" + quote(table) + ")", null)) {
                while (row.moveToNext()) columns.add(row.getString(row.getColumnIndexOrThrow("name")));
            }
            String owner;
            if (columns.contains("role_id")) owner = "role_id";
            else if (columns.contains("account_id")) owner = "account_id";
            else throw new IllegalArgumentException("未识别存档表的账号归属：" + table);
            String condition = owner + membership + (owner.equals("role_id") ? ROLES : ACCOUNT) + ")";
            if (isolate) condition = owner + " IS NULL OR " + condition;
            db.delete(quote(table), condition, null);
        }
        db.delete("local_game_role", "account_id" + membership + ACCOUNT + ")", null);
        db.delete("local_account", isolate ? "username<>'admin'" : "username='admin'", null);
    }

    /**
     * 以所属主记录的角色判定附件或装备扩展行的归属，并在删除主表前完成过滤。
     * @param db 待处理数据库
     * @param tables 剩余待分类的数据表
     * @param child 从表
     * @param key 从表关联字段
     * @param parent 主表
     * @param membership 保留或删除 admin 的 SQL 判定
     */
    private static void removeChildren(SQLiteDatabase db, Set<String> tables, String child, String key,
                                       String parent, String membership) {
        if (tables.remove(child)) {
            db.delete(child, key + membership + "SELECT id FROM " + parent + " WHERE role_id IN (" + ROLES + "))", null);
        }
    }

    /**
     * 只保留当前操作所需的角色榜项，导出时去除全服血量、时段等状态。
     * @param db 待处理数据库
     * @param isolate 是否生成仅含 admin 榜单片段的导出数据
     */
    private static void filterBoss(SQLiteDatabase db, boolean isolate) {
        Set<String> roles = new HashSet<>();
        try (Cursor row = db.rawQuery(ROLES, null)) {
            while (row.moveToNext()) roles.add(row.getString(0));
        }
        try (Cursor row = db.rawQuery("SELECT rowid,state_data FROM local_world_boss ORDER BY rowid", null)) {
            while (row.moveToNext()) {
                ObjectNode state = object(row.getString(1));
                ObjectNode ranks = ranks(state);
                List<String> keys = new ArrayList<>();
                ranks.fieldNames().forEachRemaining(keys::add);
                boolean changed = false;
                for (String key : keys) {
                    if (roles.contains(key) != isolate) {
                        ranks.remove(key);
                        changed = true;
                    }
                }
                if (isolate && ranks.size() == 0) {
                    db.execSQL("DELETE FROM local_world_boss WHERE rowid=?", new Object[]{row.getLong(0)});
                } else if (isolate || changed) {
                    ObjectNode result = isolate ? JSON.createObjectNode() : state;
                    result.set("ranks", ranks);
                    db.execSQL("UPDATE local_world_boss SET state_data=? WHERE rowid=?",
                            new Object[]{result.toString(), row.getLong(0)});
                }
            }
        }
    }

    /**
     * 合并 admin 的世界首领榜项和洞穴层通关人数，供调用方随后插入其他个人数据。
     * @param live 已移除旧 admin 的目标库，处于写事务
     * @param staged 已隔离 admin 且按需转换编号的暂存库，处于写事务
     */
    public static void mergeShared(SQLiteDatabase live, SQLiteDatabase staged) {
        requireTransaction(live);
        requireTransaction(staged);
        try (Cursor row = staged.rawQuery("SELECT realm_id,mode_id,week_id,state_data FROM local_world_boss", null)) {
            while (row.moveToNext()) {
                String[] ids = {row.getString(0), row.getString(1), row.getString(2)};
                ObjectNode imported = ranks(object(row.getString(3)));
                String where = "realm_id=? AND mode_id=? AND week_id=?";
                ObjectNode world;
                boolean exists;
                try (Cursor current = live.rawQuery("SELECT state_data FROM local_world_boss WHERE " + where, ids)) {
                    exists = current.moveToFirst();
                    world = exists ? object(current.getString(0)) : JSON.createObjectNode();
                }
                ObjectNode merged = ranks(world);
                imported.fields().forEachRemaining(pair -> {
                    if (merged.has(pair.getKey())) throw new IllegalArgumentException("世界首领榜项与现有角色编号冲突");
                    merged.set(pair.getKey(), pair.getValue());
                });
                world.set("ranks", merged);
                if (exists) live.execSQL("UPDATE local_world_boss SET state_data=? WHERE " + where,
                        new Object[]{world.toString(), ids[0], ids[1], ids[2]});
                else live.execSQL("INSERT INTO local_world_boss(realm_id,mode_id,week_id,state_data) VALUES(?,?,?,?)",
                        new Object[]{ids[0], ids[1], ids[2], world.toString()});
            }
        }
        staged.delete("local_world_boss", null, null);
        try (Cursor row = staged.rawQuery("SELECT layer,COUNT(*) FROM local_tower_clear GROUP BY layer", null)) {
            while (row.moveToNext()) {
                live.execSQL("INSERT OR IGNORE INTO local_tower_layer(layer,cleared) VALUES(?,0)", new Object[]{row.getInt(0)});
                live.execSQL("UPDATE local_tower_layer SET cleared=cleared+? WHERE layer=?",
                        new Object[]{row.getInt(1), row.getInt(0)});
            }
        }
    }

    /** 解析区服世界首领存档。
     * @param source 序列化状态
     * @return 状态对象
     */
    private static ObjectNode object(String source) {
        try {
            JsonNode node = JSON.readTree(source);
            if (node == null || !node.isObject()) throw new IllegalArgumentException("世界首领状态格式错误");
            return (ObjectNode) node;
        } catch (IOException e) {
            throw new IllegalArgumentException("世界首领状态损坏", e);
        }
    }

    /** 读取含角色索引的世界首领伤害榜。
     * @param world 区服状态
     * @return 当前角色榜项映射
     */
    private static ObjectNode ranks(ObjectNode world) {
        JsonNode value = world.get("ranks");
        if (value == null) return JSON.createObjectNode();
        if (!value.isObject()) throw new IllegalArgumentException("世界首领榜单格式错误");
        return (ObjectNode) value;
    }

    /** 核实查询存在结果。
     * @param db 暂存数据库
     * @param query 只读查询
     * @return 是否存在结果
     */
    private static boolean hasRow(SQLiteDatabase db, String query) {
        try (Cursor row = db.rawQuery(query, null)) { return row.moveToFirst(); }
    }

    /** 确认账号筛选和共享资产写入由外层事务托管。
     * @param db 正在处理的数据库
     */
    private static void requireTransaction(SQLiteDatabase db) {
        if (!db.inTransaction()) throw new IllegalStateException("账号存档操作需要数据库事务");
    }

    /** 引用 SQLite 元数据返回的表名。
     * @param name 数据表名
     * @return 安全引用的标识符
     */
    private static String quote(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }
}
