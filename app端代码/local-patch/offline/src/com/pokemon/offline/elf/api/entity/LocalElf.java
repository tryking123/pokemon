package com.pokemon.offline.elf.api.entity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 已领取的初始精灵与队伍存档
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:42
 */
public final class LocalElf {

    /**
     * 读取当前战斗阵位，兼容未单独保存阵位的旧档。
     *
     * @return 第一队的战斗阵位，未上阵时为零
     */
    public int position() {
        return slot == 0 ? 0 : training.path("formationPosition").asInt(slot);
    }

    /** 精灵实例标识，用于阵容和战斗。 */
    public final String id;
    /** 底包精灵配置编号。 */
    public final int configId;
    /** 精灵当前等级。 */
    public final int level;
    /** 已进入的队伍编号。 */
    public final int team;
    /** 精灵的上阵展示槽位，零为休息。 */
    public final int slot;
    /** 角色存档中的培养状态，旧存档使用原客户端的展示初值。 */
    public final ObjectNode training;
    /** 当前装配的黄金携带品模板和星级，仅取自真正的装备记录。 */
    public final ObjectNode goldCarry = JsonNodeFactory.instance.objectNode();
    /** 当前穿戴的奥义石，未穿戴时为空对象。 */
    public final ObjectNode stone = JsonNodeFactory.instance.objectNode();
    /** 当前普通队伍已出战宠物的只读快照，不写入精灵培养字段。 */
    public final ObjectNode battlePet = JsonNodeFactory.instance.objectNode();

    /**
     * 读取存档中显式保存的特性编号覆盖值（training_data.character 整数数组）。
     * 旧存档未保存该字段时返回空列表，由规则层回落到物种模板配置。
     *
     * @return 已持久化的特性编号，去重且排除零值；缺失或非数组时为空列表
     */
    public List<Integer> persistedCharacters() {
        JsonNode saved = training.path("character");
        if (!saved.isArray()) {
            return Collections.emptyList();
        }
        List<Integer> result = new ArrayList<>();
        for (JsonNode node : saved) {
            int value = node.asInt();
            if (value > 0 && !result.contains(value)) {
                result.add(value);
            }
        }
        return result;
    }

    /**
     * 创建精灵存档对象。
     *
     * @param id 精灵实例标识
     * @param configId 精灵配置编号
     * @param level 精灵等级
     * @param team 队伍编号
     * @param slot 阵法位置
     */
    public LocalElf(String id, int configId, int level, int team, int slot) {
        this(id, configId, level, team, slot, JsonNodeFactory.instance.objectNode());
    }

    /**
     * 从数据库读取精灵及独立培养状态。
     *
     * @param id 精灵实例标识
     * @param configId 当前精灵模板编号
     * @param level 精灵等级
     * @param team 队伍编号
     * @param slot 队伍位置
     * @param training 持久化培养状态
     */
    public LocalElf(String id, int configId, int level, int team, int slot, ObjectNode training) {
        this.id = id;
        this.configId = configId;
        this.level = level;
        this.team = team;
        this.slot = slot;
        this.training = training;
    }
}
