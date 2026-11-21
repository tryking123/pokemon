package com.qk.local;

import android.content.Context;
import android.system.ErrnoException;
import android.system.Os;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 在 Unity 启动前迁移已知未修改的 IL2CPP metadata 缓存
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:53
 */
final class UnityMetadataCache {
    private static final String TAG = "PokemonLocalBridge";
    private static final String ASSET_METADATA = "bin/Data/Managed/Metadata/global-metadata.dat";
    private static final String CACHE_METADATA = "il2cpp/Metadata/global-metadata.dat";
    private static final int METADATA_SIZE = 4_075_200;
    private static final String ORIGINAL_SHA256 = "1e91f55802c661e6eea8f8cdef0509814360b0504a89abb7bc3989075adf4062";
    private static final String PREVIOUS_LOCAL_SHA256 = "c93d1bcaf01f16fda09d3dc47ef79da623ee35b5b33ec4e50647191d4ea4b14f";
    private static final String LOCAL_SHA256 = "0c65e42b86ef8711799f79f186e061aaf624fb7ce2f5e1d7cbb968ffea9c2f1a";

    private UnityMetadataCache() {
    }

    /**
     * 迁移已知原版或电脑联调版缓存。没有缓存的新安装交给 Unity 从 APK 提取。
     *
     * @param context 当前应用上下文
     * @throws IOException 缓存或 APK 文件内容无效，或无法安全写入
     */
    static synchronized void prepare(Context context) throws IOException {
        File externalFiles = context.getExternalFilesDir(null);
        if (externalFiles == null) {
            return;
        }
        File cache = new File(externalFiles.getCanonicalFile(), CACHE_METADATA);
        if (!cache.exists()) {
            return;
        }
        if (!cache.equals(cache.getCanonicalFile()) || !cache.isFile() || cache.length() != METADATA_SIZE) {
            throw new IOException("Unity 配置缓存文件不符合当前版本");
        }

        String previous;
        try (InputStream input = new FileInputStream(cache)) {
            previous = metadataHash(input, null);
        }
        if (LOCAL_SHA256.equals(previous)) {
            return;
        }
        if (!ORIGINAL_SHA256.equals(previous) && !PREVIOUS_LOCAL_SHA256.equals(previous)) {
            throw new IOException("检测到其他版本的 Unity 配置缓存，无法自动迁移");
        }

        File incoming = File.createTempFile("local-metadata-", ".tmp", cache.getParentFile());
        try {
            try (InputStream input = context.getAssets().open(ASSET_METADATA);
                 FileOutputStream output = new FileOutputStream(incoming)) {
                if (!LOCAL_SHA256.equals(metadataHash(input, output))) {
                    throw new IOException("安装包内的 Unity 配置文件校验失败");
                }
                output.getFD().sync();
            }
            Os.rename(incoming.getAbsolutePath(), cache.getAbsolutePath());
            Log.i(TAG, "Migrated original Unity metadata cache");
        } catch (ErrnoException failure) {
            throw new IOException("无法更新 Unity 配置缓存", failure);
        } finally {
            if (incoming.exists() && !incoming.delete()) {
                Log.w(TAG, "Could not remove temporary Unity metadata");
            }
        }
    }

    private static String metadataHash(InputStream input, FileOutputStream output) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        byte[] buffer = new byte[32768];
        int total = 0;
        int count;
        while ((count = input.read(buffer)) != -1) {
            total += count;
            if (total > METADATA_SIZE) {
                throw new IOException("Unity 配置文件长度不符");
            }
            digest.update(buffer, 0, count);
            if (output != null) {
                output.write(buffer, 0, count);
            }
        }
        if (total != METADATA_SIZE) {
            throw new IOException("Unity 配置文件不完整");
        }

        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }
}
