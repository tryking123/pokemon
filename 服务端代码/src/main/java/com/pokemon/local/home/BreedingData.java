package com.pokemon.local.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.elf.api.entity.LocalElf;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;

/**
 * 饲育屋原蛋组、种族与精灵个体性别。
 * @author tongruiyuan
 * @since 2026-11-19 21:43
 */
public final class BreedingData {
    static final JsonNode CONFIG = load();

    private BreedingData() {
    }

    /** 读取随包原种族及寄存配置。
     * @return 饲育屋固定规则
     */
    private static JsonNode load() {
        try (InputStream in = new ClassPathResource("home/breeding.json").getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            throw new IllegalStateException("饲育屋物种配置无法加载", e);
        }
    }

    /** 从个体已有性别或物种雄性率生成稳定展示值。
     * @param elf 个体记录
     * @return 性别标记
     */
    public static int gender(LocalElf elf) {
        if (elf.training.has("gender"))
            return elf.training.path("gender").asInt() == 0 ? 0 : 1;
        int male = CONFIG.path("species").path(Integer.toString(elf.configId)).path(0).asInt(500);
        return Math.floorMod(elf.id.hashCode(), 1000) < male ? 1 : 0;
    }
}
