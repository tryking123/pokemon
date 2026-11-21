package com.pokemon.local;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 守护小精灵本地服务端启动入口
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
@SpringBootApplication
public class PokemonLocalServer {

    /**
     * 启动本地 HTTP 服务
     *
     * @param args 启动参数
     */
    public static void main(String[] args) {
        SpringApplication.run(PokemonLocalServer.class, args);
    }
}
