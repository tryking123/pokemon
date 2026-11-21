package com.qk.local;

import android.app.Application;
import android.util.Log;
import com.pokemon.local.embedded.EmbeddedRuntime;
import java.io.IOException;

/** 在 App 进程中装配独立单机运行环境。 */
public final class LocalApplication extends Application {
    /** 在工作台打开时启动回环服务，登录请求与启动线程共用同一个运行实例。 */
    @Override
    public void onCreate() {
        super.onCreate();
        LocalApi.installInProcessTransport((method, path, json, auth) ->
                EmbeddedRuntime.get(this).request(method, path, json, auth));
        new Thread(() -> {
            try {
                EmbeddedRuntime.get(this);
            } catch (IOException failure) {
                Log.w("PokemonOffline", "启动未完成，下一次本地请求将重新初始化", failure);
            }
        }, "pokemon-offline-start").start();
    }
}
