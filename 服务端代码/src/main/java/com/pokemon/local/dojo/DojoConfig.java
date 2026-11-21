package com.pokemon.local.dojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 底包场景、传送、道馆和怪物队伍配置。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:05
 */
@Service
public class DojoConfig {
    private final JsonNode root;

    /**
     * 载入从底包提取并合并的静态配置。
     *
     * @param json 原配置读取器
     * @throws IOException 配置文件无法读取
     */
    DojoConfig(ObjectMapper json) throws IOException {
        try (InputStream source = new ClassPathResource("dojo-stage.json").getInputStream()) {
            root = json.readTree(source);
        }
    }

    /**
     * 查询当前已接入的原始道馆场景及格点尺寸。
     *
     * @return 场景编号到尺寸的映射
     */
    public JsonNode dimensions() {
        return root.get("dimensions");
    }

    /**
     * 查询道馆传送配置。
     *
     * @return 传送编号、来源、目标位置和道馆门槛的序列
     */
    public JsonNode portals() {
        return root.get("portals");
    }

    /**
     * 查询一道馆的场景、等级门槛、后继及怪物组列表。
     *
     * @param dojoId 道馆编号
     * @return 静态道馆配置，未接入时为空
     */
    public JsonNode stage(int dojoId) {
        return root.get("stages").get(Integer.toString(dojoId));
    }

    /**
     * 为指定道馆中的怪物组生成稳定实例编号。
     *
     * @param dojoId 道馆编号
     * @param groupId 怪物组编号
     * @return 用于场景点击、挑战和移除同步的编号
     */
    public String groupId(int dojoId, int groupId) {
        return "dojo-" + dojoId + "-" + groupId;
    }
}
