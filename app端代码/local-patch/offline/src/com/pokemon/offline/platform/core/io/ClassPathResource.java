package com.pokemon.offline.platform.core.io;

import android.content.res.AssetManager;
import java.io.IOException;
import java.io.InputStream;

/** 在安装包内定位共享游戏数据。 */
public final class ClassPathResource {
    private static AssetManager assets;
    private final String path;

    public ClassPathResource(String path) {
        this.path = "pokemon-local/" + path;
    }

    public static void install(AssetManager manager) {
        assets = manager;
    }

    public InputStream getInputStream() throws IOException {
        return assets.open(path);
    }

    public boolean exists() {
        try (InputStream input = getInputStream()) {
            return true;
        } catch (IOException unavailable) {
            return false;
        }
    }
}
