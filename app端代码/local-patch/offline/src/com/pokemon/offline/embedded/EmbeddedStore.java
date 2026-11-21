package com.pokemon.offline.embedded;

import android.database.sqlite.SQLiteDatabase;
import java.util.concurrent.Callable;

/** 文件位于应用私有目录，HTTP 桥与 TCP 游戏使用同一事务入口。 */
public final class EmbeddedStore {
    private final SQLiteDatabase database;

    /** 初始化当前应用的唯一数据库。 */
    EmbeddedStore(SQLiteDatabase database) {
        this.database = database;
    }

    /** 执行一次本地操作，异常时回滚本次写入。 */
    public synchronized <T> T write(Callable<T> work) throws Exception {
        boolean started = false;
        try {
            database.beginTransaction();
            started = true;
            T value = work.call();
            database.setTransactionSuccessful();
            return value;
        } finally {
            if (started) {
                database.endTransaction();
            }
        }
    }
}
