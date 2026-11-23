package com.pokemon.offline.save;

import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 离线存档文件。按表保存 SQLite 原始值和行号，导入时仅向应用已知的数据表写入。
 *
 * @author tongruiyuan
 * @since 2026-11-22 23:36
 */
public final class SaveArchive {
    private static final String FORMAT = "pokemon-local-admin/1";
    private static final String END = "pokemon-save-complete";

    /** 禁止创建工具实例。 */
    private SaveArchive() { }

    /**
     * 仅从一致快照中选出 admin 账号及其关联存档。
     * @param live 当前存档数据库
     * @param directory 应用私有缓存目录
     * @param destination 用户选择的输出文件流
     * @throws IOException 写入失败
     */
    public static void exportAdmin(SQLiteDatabase live, File directory, OutputStream destination) throws IOException {
        requireTransaction(live);
        File file = File.createTempFile("admin-save-", ".db", directory);
        try (SQLiteDatabase staged = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            staged.beginTransaction();
            try {
                createSchema(live, staged);
                copyRows(live, staged);
                SaveOwnership.isolateAdmin(staged);
                checkAdmin(staged);
                write(staged, destination);
            } finally {
                staged.endTransaction();
            }
        } finally {
            SQLiteDatabase.deleteDatabase(file);
        }
    }

    /**
     * 在私有暂存库校验、隔离并可选重编身份，最后仅替换目标设备的 admin 记录。
     * @param live 当前存档数据库
     * @param directory 应用私有缓存目录
     * @param source 系统选择的文件流
     * @param regenerate 是否生成新的身份编号
     * @throws IOException 存档损坏或不兼容
     */
    public static void importAdmin(SQLiteDatabase live, File directory, InputStream source, boolean regenerate) throws IOException {
        requireTransaction(live);
        File file = File.createTempFile("admin-save-", ".db", directory);
        try (SQLiteDatabase staged = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READWRITE)) {
            staged.beginTransaction();
            try {
                createSchema(live, staged);
                read(staged, source);
                checkAdmin(staged);
                SaveOwnership.isolateAdmin(staged);
                if (regenerate) SaveIdentityRemapper.regenerate(staged);
                live.execSQL("PRAGMA defer_foreign_keys=ON");
                SaveOwnership.removeAdmin(live);
                SaveOwnership.mergeShared(live, staged);
                copyRows(staged, live);
                checkReferences(live);
            } finally {
                staged.endTransaction();
            }
        } finally {
            SQLiteDatabase.deleteDatabase(file);
        }
    }

    /** 用本机应用的可信建表语句创建私有暂存库。 */
    private static void createSchema(SQLiteDatabase live, SQLiteDatabase staged) {
        try (Cursor rows = live.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name GLOB 'local_*'", null)) {
            while (rows.moveToNext()) staged.execSQL(rows.getString(0));
        }
    }

    /** 按原行序复制业务数据。目标库分配本地行号，显式主键保留原值。 */
    private static void copyRows(SQLiteDatabase source, SQLiteDatabase target) {
        for (Map.Entry<String,List<String>> table : schema(source).entrySet()) {
            String name = quote(table.getKey());
            List<String> columns = table.getValue();
            String insert = "INSERT INTO " + name + " VALUES(" + String.join(",", Collections.nCopies(columns.size(), "?")) + ")";
            try (Cursor rows = source.rawQuery("SELECT * FROM " + name + " ORDER BY rowid", null);
                 SQLiteStatement stmt = target.compileStatement(insert)) {
                while (rows.moveToNext()) {
                    for (int i = 0; i < columns.size(); i++) {
                        switch (rows.getType(i)) {
                            case Cursor.FIELD_TYPE_NULL: stmt.bindNull(i + 1); break;
                            case Cursor.FIELD_TYPE_INTEGER: stmt.bindLong(i + 1, rows.getLong(i)); break;
                            case Cursor.FIELD_TYPE_FLOAT: stmt.bindDouble(i + 1, rows.getDouble(i)); break;
                            case Cursor.FIELD_TYPE_STRING: stmt.bindString(i + 1, rows.getString(i)); break;
                            case Cursor.FIELD_TYPE_BLOB: stmt.bindBlob(i + 1, rows.getBlob(i)); break;
                            default: throw new IllegalStateException("数据库字段类型无效");
                        }
                    }
                    stmt.executeInsert();
                }
            }
        }
    }

    /** 校验存档归属。 */
    private static void checkAdmin(SQLiteDatabase staged) throws IOException {
        if (DatabaseUtils.queryNumEntries(staged, "local_account") != 1
                || DatabaseUtils.queryNumEntries(staged, "local_account", "username=?", new String[]{"admin"}) != 1) {
            throw new IOException("导入导出仅支持 admin 的存档");
        }
    }

    /** 在提交前检查数据库引用。 */
    private static void checkReferences(SQLiteDatabase db) throws IOException {
        try (Cursor invalid = db.rawQuery("PRAGMA foreign_key_check", null)) {
            if (invalid.moveToFirst()) throw new IOException("存档存在失效的数据关联：" + invalid.getString(0));
        }
    }

    /**
     * 将已隔离的 admin 存档写出，登录令牌仅在本机有效。
     * @param db 当前存档数据库
     * @param destination 输出流
     * @throws IOException 文件写入失败
     */
    private static void write(SQLiteDatabase db, OutputStream destination) throws IOException {
        requireTransaction(db);
        Map<String,List<String>> schema = schema(db);
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new GZIPOutputStream(destination)))) {
            out.writeUTF(FORMAT);
            out.writeInt(schema.size());
            for (Map.Entry<String,List<String>> table : schema.entrySet()) {
                String name = table.getKey();
                out.writeUTF(name);
                out.writeInt(table.getValue().size());
                for (String column : table.getValue()) out.writeUTF(column);
                try (Cursor rows = db.rawQuery("SELECT rowid,* FROM " + quote(name) + " ORDER BY rowid", null)) {
                    while (rows.moveToNext()) {
                        out.writeByte(1);
                        for (int i = 0; i < rows.getColumnCount(); i++) {
                            int type = rows.getType(i);
                            out.writeByte(type);
                            switch (type) {
                                case Cursor.FIELD_TYPE_NULL: break;
                                case Cursor.FIELD_TYPE_INTEGER: out.writeLong(rows.getLong(i)); break;
                                case Cursor.FIELD_TYPE_FLOAT: out.writeDouble(rows.getDouble(i)); break;
                                case Cursor.FIELD_TYPE_STRING: writeBytes(out, rows.getString(i).getBytes(StandardCharsets.UTF_8)); break;
                                case Cursor.FIELD_TYPE_BLOB: writeBytes(out, rows.getBlob(i)); break;
                                default: throw new IOException("未知的存档字段类型");
                            }
                        }
                    }
                }
                out.writeByte(0);
            }
            out.writeUTF(END);
        }
    }

    /**
     * 在外层事务中恢复存档。结构、文件校验或关联检查失败时由调用方回滚整次导入。
     * @param db 目标数据库
     * @param source 用户选择的存档文件流
     * @throws IOException 存档损坏或格式不兼容
     */
    private static void read(SQLiteDatabase db, InputStream source) throws IOException {
        requireTransaction(db);
        Map<String,List<String>> remaining = schema(db);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(source)))) {
            if (!FORMAT.equals(in.readUTF())) {
                throw new IOException("存档格式或数据表版本与此安装包不兼容");
            }
            int tableCount = in.readInt();
            if (tableCount < 2 || tableCount > remaining.size()) throw new IOException("当前版本无法读取存档中的数据表");
            for (int tableIndex = 0; tableIndex < tableCount; tableIndex++) {
                String name = in.readUTF();
                List<String> columns = remaining.remove(name);
                if (columns == null) throw new IOException("存档数据表不匹配：" + name);
                int count = in.readInt();
                if (count < 1 || count > columns.size()) throw new IOException("存档字段不匹配：" + name);
                Set<String> available = new HashSet<>(columns);
                List<String> fields = new ArrayList<>();
                fields.add("rowid");
                for (int index = 0; index < count; index++) {
                    String column = in.readUTF();
                    if (!available.remove(column)) throw new IOException("存档字段不匹配：" + name);
                    fields.add(quote(column));
                }
                String sql = "INSERT INTO " + quote(name) + "(" + String.join(",", fields) + ") VALUES("
                        + String.join(",", Collections.nCopies(fields.size(), "?")) + ")";
                try (SQLiteStatement row = db.compileStatement(sql)) {
                    int marker;
                    while ((marker = in.readUnsignedByte()) == 1) {
                        for (int i = 1; i <= fields.size(); i++) {
                            int type = in.readUnsignedByte();
                            if (i == 1 && type != Cursor.FIELD_TYPE_INTEGER) throw new IOException("存档行号无效");
                            switch (type) {
                                case Cursor.FIELD_TYPE_NULL: row.bindNull(i); break;
                                case Cursor.FIELD_TYPE_INTEGER: row.bindLong(i, in.readLong()); break;
                                case Cursor.FIELD_TYPE_FLOAT: row.bindDouble(i, in.readDouble()); break;
                                case Cursor.FIELD_TYPE_STRING: row.bindString(i, new String(readBytes(in), StandardCharsets.UTF_8)); break;
                                case Cursor.FIELD_TYPE_BLOB: row.bindBlob(i, readBytes(in)); break;
                                default: throw new IOException("存档字段类型无效");
                            }
                        }
                        row.executeInsert();
                    }
                    if (marker != 0) throw new IOException("存档行标记无效");
                }
            }
            if (remaining.containsKey("local_account") || remaining.containsKey("local_game_role")) {
                throw new IOException("存档缺少账号或角色表");
            }
            if (!END.equals(in.readUTF()) || in.read() != -1) throw new IOException("存档结束标记无效");
            checkReferences(db);
        }
    }

    /** 读取应用实际初始化的本地表和列，排除临时登录令牌。 */
    private static Map<String,List<String>> schema(SQLiteDatabase db) {
        Map<String,List<String>> tables = new LinkedHashMap<>();
        try (Cursor names = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name GLOB 'local_*' "
                + "AND name <> 'local_session' ORDER BY name", null)) {
            while (names.moveToNext()) {
                String name = names.getString(0);
                try (Cursor rows = db.rawQuery("SELECT * FROM " + quote(name) + " LIMIT 0", null)) {
                    tables.put(name, Arrays.asList(rows.getColumnNames()));
                }
            }
        }
        return tables;
    }

    /** 转义当前应用数据库中的标识符。 */
    private static String quote(String name) { return "\"" + name.replace("\"", "\"\"") + "\""; }

    /** 拒绝在事务以外操作存档。 */
    private static void requireTransaction(SQLiteDatabase db) {
        if (!db.inTransaction()) throw new IllegalStateException("存档操作需要独立事务");
    }

    /** 写入变长内容及其字节数。 */
    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    /** 读取单元格内容，拒绝损坏文件中的异常长度。 */
    private static byte[] readBytes(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) throw new IOException("存档字段长度无效");
        byte[] value = new byte[length];
        in.readFully(value);
        return value;
    }
}
