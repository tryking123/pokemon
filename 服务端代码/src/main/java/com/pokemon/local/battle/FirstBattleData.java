package com.pokemon.local.battle;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.elf.ElfTrainingCatalog;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 新手教学与首个单人副本使用的底包编号及本地战斗参数。
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:44
 */
public final class FirstBattleData {

    public static final int GUIDE_SCENE = 71001;
    public static final int GUIDE = 3;
    public static final int GROUP = 100001;
    public static final int SCENE = 20020;
    public static final int BOSS = 110012;
    public static final List<Integer> ENCOUNTERS =
            Collections.unmodifiableList(Arrays.asList(110007, 110008, 110009, 110010, BOSS));

    private static final Map<Integer, Starter> STARTERS = new LinkedHashMap<>();
    private static final Map<Integer, Monster> MONSTERS = new LinkedHashMap<>();
    private static final Map<Integer, Encounter> GROUPS = new LinkedHashMap<>();

    static {
        // jingling_proto 中的属性、品质和一级技能。
        STARTERS.put(21006, new Starter(2, 0, 2, 39, 50, 60, 43, 50, 65, 10052, 3));
        STARTERS.put(21007, new Starter(3, 0, 2, 44, 48, 50, 65, 64, 43, 10057, 9));
        STARTERS.put(21008, new Starter(4, 8, 2, 45, 49, 65, 49, 65, 45, 10022, 2));
        STARTERS.put(31016, new Starter(5, 0, 3, 35, 55, 50, 40, 50, 90, 10084, 1));
        STARTERS.put(11004, new Starter(12, 0, 1, 45, 30, 20, 35, 20, 45, 10033, 1));
        STARTERS.put(31026, new Starter(9, 0, 3, 60, 80, 50, 110, 80, 45, 10125, 5));
        // 七日登录奖励可在同一个精灵背包中查看和上阵。
        STARTERS.put(41024, new Starter(2, 0, 4, 65, 100, 80, 70, 80, 105, 10023, 1));
        STARTERS.put(41030, new Starter(5, 0, 4, 60, 120, 90, 60, 80, 110, 10085, 3));
        STARTERS.put(41001, new Starter(10, 15, 4, 91, 134, 100, 95, 100, 80, 10177, 4));
        STARTERS.put(43020, new Starter(3, 6, 5, 140, 95, 95, 90, 105, 70, 10054, 4));
        // 开服等级榜与战力榜第一名精灵，来源同为底包精灵与技能表。
        STARTERS.put(64094, new Starter(2, 11, 5, 150, 160, 160, 130, 130, 150, 10284, 1));
        STARTERS.put(64114, new Starter(11, 16, 6, 156, 120, 264, 160, 200, 180, 10299, 4));
        // VIP 福利奖励精灵的底包属性和一级技能。
        STARTERS.put(21009, new Starter(1, 0, 3, 70, 75, 70, 60, 75, 70, 10098, 1));
        STARTERS.put(63006, new Starter(17, 11, 6, 165, 95, 200, 135, 150, 135, 10129, 4));
        STARTERS.put(63007, new Starter(9, 15, 5, 108, 180, 81, 97, 98, 122, 10175, 1));
        // 闯关礼包精灵的原始种族值、属性与一级技能。
        STARTERS.put(54062, new Starter(16, 15, 5, 95, 130, 85, 90, 90, 90, 10199, 1));
        STARTERS.put(43008, new Starter(6, 18, 5, 90, 90, 98, 95, 115, 130, 10008, 3));
        STARTERS.put(43010, new Starter(4, 15, 5, 115, 140, 125, 90, 90, 70, 10075, 3));
        // guaiwu_proto 的怪物编号与 jingling_proto 的外观、属性和技能。
        MONSTERS.put(100014, new Monster(32012, 11, 0, 10149));
        MONSTERS.put(100015, new Monster(31030, 13, 9, 10021));
        MONSTERS.put(100016, new Monster(33003, 4, 6, 10008));
        MONSTERS.put(100017, new Monster(33016, 1, 0, 10154));
        MONSTERS.put(100018, new Monster(31003, 8, 10, 10017));
        MONSTERS.put(100019, new Monster(31025, 1, 18, 10001));
        MONSTERS.put(100020, new Monster(42023, 3, 13, 10002));
        MONSTERS.put(100021, new Monster(43024, 3, 17, 10001));
        MONSTERS.put(100022, new Monster(43023, 2, 9, 10166));
        MONSTERS.put(100023, new Monster(43027, 3, 0, 10014));
        MONSTERS.put(100024, new Monster(31013, 2, 0, 10023));
        MONSTERS.put(100025, new Monster(32010, 4, 0, 10071));
        MONSTERS.put(100026, new Monster(32003, 5, 0, 10086));
        MONSTERS.put(100027, new Monster(32008, 2, 0, 10168));
        MONSTERS.put(100028, new Monster(32010, 4, 0, 10071));
        MONSTERS.put(100029, new Monster(41002, 2, 0, 10168));
        MONSTERS.put(100030, new Monster(43028, 4, 0, 10098));
        MONSTERS.put(100031, new Monster(43004, 4, 6, 10008));
        // 教学原阵容由旧服务提供，此处复用本底包同等级怪物作为本地对手。
        GROUPS.put(GUIDE, new Encounter(101, new int[]{100014}, new int[]{1}));
        // guaiwuteam_proto 中首副本的五组阵容与阵法位置。
        GROUPS.put(110007, new Encounter(101, new int[]{100014, 100015, 100016, 100017, 100018}, new int[]{3, 5, 1, 6, 2}));
        GROUPS.put(110008, new Encounter(101, new int[]{100017, 100018, 100019, 100020, 100021}, new int[]{6, 5, 3, 2, 1}));
        GROUPS.put(110009, new Encounter(701, new int[]{100020, 100021, 100022, 100023, 100024}, new int[]{6, 4, 2, 1, 3}));
        GROUPS.put(110010, new Encounter(701, new int[]{100023, 100024, 100025, 100026, 100027}, new int[]{4, 5, 1, 6, 3}));
        GROUPS.put(BOSS, new Encounter(601, new int[]{100027, 100028, 100029, 100030, 100031}, new int[]{1, 4, 2, 5, 3}));
    }

    /** 仅用于读取首战参数。 */
    private FirstBattleData() {
    }

    /**
     * 查找初始精灵的底包属性。
     *
     * @param configId 精灵配置编号
     * @return 保留在本地单人流程的初始精灵配置
     */
    public static Starter starter(int configId) {
        Starter known = STARTERS.get(configId);
        if (known != null) {
            return known;
        }
        JsonNode config = ElfTrainingCatalog.row("species", configId);
        int skill = config.path("skills").path(1).asInt();
        if (config.isMissingNode() || skill <= 0) {
            return null;
        }
        return new Starter(config.path("attribute1").asInt(), config.path("attribute2").asInt(),
                config.path("quality").asInt(), config.path("stat_hp").asInt(),
                config.path("stat_physical_attack").asInt(), config.path("stat_special_attack").asInt(),
                config.path("stat_physical_defense").asInt(), config.path("stat_special_defense").asInt(),
                config.path("stat_speed").asInt(), skill, 1);
    }

    /**
     * 读取预设遭遇的阵法和怪物编号。
     *
     * @param encounter 战斗使用的遭遇编号
     * @return 遭遇配置
     */
    static Encounter encounter(int encounter) {
        return GROUPS.get(encounter);
    }

    /**
     * 查找怪物的技能和外观。
     *
     * @param monsterId 原始怪物编号
     * @return 怪物配置
     */
    static Monster monster(int monsterId) {
        return MONSTERS.get(monsterId);
    }

    /**
     * 生成场景内怪组的稳定对象编号。
     *
     * @param encounter 怪物组配置编号
     * @return 对应的场景对象标识
     */
    public static String monsterUid(int encounter) {
        return "dungeon-" + SCENE + "-" + encounter;
    }

    /**
     * 初始精灵的配置和基础属性。
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:44
     */
    public static final class Starter {
        public final int type;
        public final int secondType;
        public final int quality;
        public final int life;
        public final int physicalAttack;
        public final int specialAttack;
        public final int physicalDefense;
        public final int specialDefense;
        public final int speed;
        public final int skill;
        public final int targetLimit;

        /**
         * 初始化精灵基础属性。
         *
         * @param type 第一属性
         * @param secondType 第二属性
         * @param quality 品质
         * @param life 生命种族值
         * @param physicalAttack 物攻种族值
         * @param specialAttack 特攻种族值
         * @param physicalDefense 物防种族值
         * @param specialDefense 特防种族值
         * @param speed 速度种族值
         * @param skill 一级技能
         * @param targetLimit 技能的目标上限
         */
        private Starter(int type, int secondType, int quality, int life, int physicalAttack, int specialAttack,
                        int physicalDefense, int specialDefense, int speed, int skill, int targetLimit) {
            this.type = type;
            this.secondType = secondType;
            this.quality = quality;
            this.life = life;
            this.physicalAttack = physicalAttack;
            this.specialAttack = specialAttack;
            this.physicalDefense = physicalDefense;
            this.specialDefense = specialDefense;
            this.speed = speed;
            this.skill = skill;
            this.targetLimit = targetLimit;
        }
    }

    /**
     * 首副本怪物的客户端外观和技能。
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:44
     */
    static final class Monster {
        final int sprite;
        final int type;
        final int secondType;
        final int skill;

        /**
         * 初始化怪物的客户端属性。
         *
         * @param sprite 底包精灵外观编号
         * @param type 第一属性
         * @param secondType 第二属性
         * @param skill 已装备的能力
         */
        Monster(int sprite, int type, int secondType, int skill) {
            this.sprite = sprite;
            this.type = type;
            this.secondType = secondType;
            this.skill = skill;
        }
    }

    /**
     * 首副本的阵法与敌方队伍。
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:44
     */
    static final class Encounter {
        final int formation;
        final int[] ids;
        final int[] positions;

        /**
         * 初始化遭遇队伍。
         *
         * @param formation 原阵法编号
         * @param ids 怪物配置编号
         * @param positions 怪物在阵法中的位置
         */
        Encounter(int formation, int[] ids, int[] positions) {
            this.formation = formation;
            this.ids = ids;
            this.positions = positions;
        }
    }
}
