package com.pokemon.offline.dojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;

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
     * 按道馆内的配置顺序区分同模板的多个实例，保留首次出现时的既有编号。
     *
     * @param dojoId 道馆编号
     * @param groupIndex 怪物组在本道馆的配置序号，从零开始
     * @return 用于场景点击、挑战和移除同步的编号
     */
    public String groupInstanceId(int dojoId, int groupIndex) {
        JsonNode groups = stage(dojoId).get(3);
        int template = groups.get(groupIndex).get(0).asInt();
        String id = "dojo-" + dojoId + "-" + template;
        for (int i = 0; i < groupIndex; i++) {
            if (groups.get(i).get(0).asInt() == template) {
                return id + "-" + groupIndex;
            }
        }
        return id;
    }
}
