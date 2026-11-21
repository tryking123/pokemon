package com.pokemon.local.common;

import java.util.Collections;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地服务存活检查
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
@RestController
public class HealthController {

    /**
     * 返回服务存活状态
     *
     * @return 服务状态
     */
    @GetMapping("/health")
    public Object health() {
        return Collections.singletonMap("status", "UP");
    }
}
