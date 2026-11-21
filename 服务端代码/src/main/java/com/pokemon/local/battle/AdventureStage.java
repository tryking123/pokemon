package com.pokemon.local.battle;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/**
 * 底包冒险关卡的世界门槛、阵法、怪物和奖励。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:42
 */
public final class AdventureStage {
    /** 原始普通物品奖励类型。 */
    public static final int ITEM = 1;
    /** 原始训练家装备奖励类型。 */
    public static final int EQUIPMENT = 10;
    private static final int FIRST_MISSION = 10001;

    private static final Map<Integer, AdventureStage> STAGES = new HashMap<>();
    private static final Map<Integer, FirstBattleData.Monster> MONSTERS = new HashMap<>();
    private static final Map<Integer, Integer> EQUIPMENT_POSITION = new HashMap<>();

    static {
        load();
    }

    /** 下一冒险关卡。 */
    public final int next;
    /** 原关卡表的最低训练师阶段。 */
    public final int requiredWorld;
    /** 奖励原始三元组，依次为类型、配置编号和数量。 */
    public final List<Integer> rewards;
    /** 每秒挂机金币。 */
    public final int coins;
    /** 每秒挂机角色经验。 */
    public final int roleExp;
    /** 每秒挂机精灵经验。 */
    public final int elfExp;
    final FirstBattleData.Encounter encounter;

    /**
     * 保存底包关卡配置。
     *
     * @param row 原始关卡、收益、队伍及奖励字段
     */
    private AdventureStage(String[] row) {
        int mission = value(row, 1);
        // copy_proto 与 copy2_proto 的 needworld 在这两个关卡号切换。
        this.requiredWorld = mission < 11850 ? 0 : mission < 15149 ? 1 : 2;
        this.next = value(row, 2);
        this.coins = value(row, 4);
        this.roleExp = value(row, 5);
        this.elfExp = value(row, 6);
        int count = value(row, 7);
        int[] monsters = new int[count];
        int[] positions = new int[count];
        int field = 8;
        for (int i = 0; i < count; i++) {
            monsters[i] = value(row, field++);
            positions[i] = value(row, field++);
        }
        this.encounter = new FirstBattleData.Encounter(value(row, 3), monsters, positions);
        List<Integer> rewards = new ArrayList<>();
        for (; field < row.length; field++) {
            rewards.add(value(row, field));
        }
        this.rewards = Collections.unmodifiableList(rewards);
    }

    /**
     * 读取已接入的原版冒险关卡。
     *
     * @param mission 原版冒险关卡号
     * @return 关卡配置，未接入的区域为空
     */
    public static AdventureStage find(int mission) {
        return STAGES.get(mission);
    }

    /**
     * 读取当前可结算挂机收益的配置。处于区域门槛时沿用最近通关地图的收益。
     *
     * @param mission 客户端当前看到的下一关编号
     * @return 实际生效的挂机收益配置
     */
    public static AdventureStage hangupRate(int mission) {
        AdventureStage stage = STAGES.get(mission);
        if (stage == null) {
            stage = STAGES.get(mission - 1);
        }
        return stage == null ? STAGES.get(FIRST_MISSION) : stage;
    }

    /**
     * 读取原怪物的对应精灵外观和技能。
     *
     * @param monster 怪物配置编号
     * @return 底包怪物配置
     */
    static FirstBattleData.Monster monster(int monster) {
        return MONSTERS.get(monster);
    }

    /**
     * 读取通关奖励装备原版槽位。
     *
     * @param equipment 奖励的装备模板编号
     * @return 装备部位
     */
    public static int equipmentPosition(int equipment) {
        return EQUIPMENT_POSITION.get(equipment);
    }

    /**
     * 加载从底包提取并验证过引用关系的关卡、怪物和装备目录。
     */
    private static void load() {
        ClassPathResource resource = new ClassPathResource("catalog/adventure.csv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] row = line.split(",");
                int id = value(row, 1);
                switch (row[0]) {
                    case "S":
                        STAGES.put(id, new AdventureStage(row));
                        break;
                    case "M":
                        MONSTERS.put(id, new FirstBattleData.Monster(
                                value(row, 2), value(row, 3), value(row, 4), value(row, 5)));
                        break;
                    case "E":
                        EQUIPMENT_POSITION.put(id, value(row, 2));
                        break;
                    default:
                        throw new IllegalStateException("未知冒险配置类型 " + row[0]);
                }
            }
        } catch (IOException ex) {
            throw new IllegalStateException("读取冒险配置失败", ex);
        }
    }

    /**
     * 读取配置中的整数。
     *
     * @param row 当前配置行
     * @param index 字段列号
     * @return 对应整数
     */
    private static int value(String[] row, int index) {
        return Integer.parseInt(row[index]);
    }
}

