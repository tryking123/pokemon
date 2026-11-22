package com.pokemon.offline.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import com.pokemon.offline.platform.core.io.ClassPathResource;

/**
 * 精灵培养所用的同版本底包配置，数据源包括 jingling、jinhua、qinmidu、variable 等表。
 *
 * @author tongruiyuan
 * @since 2026-11-19 00:16
 */
public final class ElfTrainingCatalog {
    private static final JsonNode TABLES = load("elf/training.json");

    /** 防止创建配置读取对象。 */
    private ElfTrainingCatalog() {
    }

    /**
     * 读取指定底表的一行配置。
     *
     * @param table 表名
     * @param id 配置编号
     * @return 配置行，缺失时为缺失节点
     */
    public static JsonNode row(String table, int id) {
        return TABLES.path(table).path(Integer.toString(id));
    }

    /**
     * 读取原页面进阶使用的成长表：非终形取首个进化目标，终形取自身编号乘十。
     * @param speciesId 当前物种模板编号
     * @return 当前进阶费用与上限对应的原成长行
     */
    public static JsonNode advanceRule(int speciesId) {
        JsonNode source = row("growth", speciesId);
        int firstTarget = source.path("evolution").path(0).asInt();
        return row("growth", firstTarget > 0 ? firstTarget : speciesId * 10);
    }

    /**
     * 返回原携带品中指定品质的模板编号。
     * @param quality 原品质编号
     * @return 当前底包符合条件的模板
     */
    static java.util.List<Integer> portablePool(int quality) {
        java.util.List<Integer> pool = new java.util.ArrayList<>();
        TABLES.path("portable").fields().forEachRemaining(entry -> {
            if (entry.getValue().path("quality").asInt() == quality) {
                pool.add(Integer.parseInt(entry.getKey()));
            }
        });
        return pool;
    }

    /**
     * 加载打包在本地 App 和 Java 服务中的培养配置。
     *
     * @param path 随包配置路径
     * @return 底表映射
     */
    static JsonNode load(String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return new ObjectMapper().readTree(input);
        } catch (IOException e) {
            throw new IllegalStateException("精灵培养配置无法解析", e);
        }
    }
}
