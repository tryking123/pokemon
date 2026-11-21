package com.qk.local;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import com.pokemon.offline.embedded.EmbeddedRuntime;

/**
 * 为 Unity 生成指向当前用户 App 私有文件的离线引导配置。
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:53
 */
final class UnityMetadataCache {
    private static final String ASSET_METADATA = "bin/Data/Managed/Metadata/global-metadata.dat";
    private static final String CACHE_METADATA = "il2cpp/Metadata/global-metadata.dat";
    private static final int CONFIG_URI_INDEX = 6438;
    private UnityMetadataCache() { }

    /**
     * 在 Unity 加载前准备资源和当前用户对应的 metadata 缓存。
     * @param context 应用上下文
     * @throws IOException 资源或缓存写入失败
     */
    static synchronized void prepare(Context context) throws IOException {
        EmbeddedRuntime.get(context).prepareUnity(context);
        File external = context.getExternalFilesDir(null);
        if (external == null) throw new IOException("Unity 私有配置目录不可用");
        File cache = new File(external.getCanonicalFile(), CACHE_METADATA);
        if (!cache.getCanonicalPath().startsWith(external.getCanonicalPath() + File.separator)) throw new IOException("Unity 缓存路径无效");
        File parent = cache.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建 Unity 私有配置目录");
        byte[] original;
        try (InputStream stream = context.getAssets().open(ASSET_METADATA)) { original = read(stream); }
        String uri = "file:///sdcard/Android/data/" + context.getPackageName() + "/files/offline-bootstrap";
        verifyConfigUri(original, uri);
        byte[] patched = original;
        if (cache.isFile()) {
            try (InputStream stream = new FileInputStream(cache)) {
                if (Arrays.equals(patched, read(stream))) return;
            }
        }
        File temporary = File.createTempFile("offline-metadata-", ".tmp", parent);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(patched);
                output.getFD().sync();
            }
            Os.rename(temporary.getAbsolutePath(), cache.getAbsolutePath());
        } catch (ErrnoException failure) {
            throw new IOException("无法更新 Unity 离线配置", failure);
        } finally { if (temporary.exists() && !temporary.delete()) android.util.Log.w("PokemonOffline", "临时 metadata 未清理"); }
    }

    /**
     * 核对安装包中固化的文件 URI 属于当前应用，不在运行时改变 metadata 长度。
     * @param original 安装包中的 metadata
     * @param uri 当前应用的私有外部文件入口
     * @throws IOException 结构或入口不符合预期
     */
    static void verifyConfigUri(byte[] original, String uri) throws IOException {
        if (original.length < 24) throw new IOException("Unity metadata 结构不符");
        ByteBuffer header = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int table = header.getInt(8), tableSize = header.getInt(12), blob = header.getInt(16), blobSize = header.getInt(20);
        long entry = (long) table + CONFIG_URI_INDEX * 8L;
        if (header.getInt(0) != 0xFAB11BAF || header.getInt(4) != 27 || table < 24 || tableSize <= 0
                || tableSize % 8 != 0 || entry + 8 > (long) table + tableSize || table + (long) tableSize != blob
                || blobSize < 0 || blob + (long) blobSize > original.length) throw new IOException("Unity metadata 结构不符");
        int length = header.getInt((int) entry), offset = header.getInt((int) entry + 4);
        if (length <= 0 || offset < 0 || offset + (long) length > blobSize) throw new IOException("Unity 入口字面量越界");
        String current = new String(original, blob + offset, length, StandardCharsets.UTF_8);
        if (!current.equals(uri)) throw new IOException("Unity 文件入口与当前应用包名不一致");
    }

    /**
     * 读取有界的配置数据。
     * @param input 输入数据
     * @return 配置内容
     * @throws IOException 文件读取失败或超出上限
     */
    private static byte[] read(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32768];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (output.size() + count > 8 * 1024 * 1024) throw new IOException("Unity metadata 超出上限");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
