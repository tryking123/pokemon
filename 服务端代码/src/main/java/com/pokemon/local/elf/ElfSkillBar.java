package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.pokemon.local.elf.api.entity.LocalElf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 精灵主动技能栏。底包 SpriteHandle 提供四个槽，种族技能按等级解锁，觉醒技能按觉醒等级解锁。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:09
 */
public final class ElfSkillBar {
    private static final JsonNode RULES = ElfTrainingCatalog.load("elf/skill-bar.json");
    static final JsonNode UPGRADE = ElfTrainingCatalog.load("elf/skill-upgrade.json");
    private static final int CAPACITY = 4;

    /** 防止实例化。 */
    private ElfSkillBar() {
    }

    /**
     * 读取装备的基础技能编号，旧档沿用原展示顺序。
     *
     * @param elf 所属精灵
     * @return 装备技能的有序列表
     */
    public static List<Integer> equipped(LocalElf elf) {
        List<Integer> result = new ArrayList<>();
        if (elf.training.path("skillBar").isArray()) {
            elf.training.path("skillBar").forEach(id -> result.add(id.asInt()));
        } else {
            result.addAll(ElfTrainingService.unlockedSkills(elf.configId, elf.level));
        }
        return result;
    }

    /**
     * 读取实际装备的出战技能，缺少展示技能的旧实例使用其初始战斗技能。
     *
     * @param elf 所属精灵
     * @param defaultSkill 原默认出战技能
     * @return 战斗快照中的技能
     */
    public static List<Integer> battleSkills(LocalElf elf, int defaultSkill) {
        List<Integer> skills = equipped(elf);
        if (skills.isEmpty()) skills = new ArrayList<>(Collections.singletonList(defaultSkill));
        skills.replaceAll(skill -> battleId(elf, skill));
        return skills;
    }

    /** 将已保存的技能等级映射为底包战斗技能编号。
     * @param elf 出战精灵
     * @param skill 基础技能编号
     * @return 对应等级的真实战斗技能编号
     */
    private static int battleId(LocalElf elf, int skill) {
        int level = elf.training.path("skill_lv").path(Integer.toString(skill)).asInt(1);
        return level > 1 ? skill * 10 + level - 1 : skill;
    }

    /** 查找已解锁技能对应的升级表，包括进化前已学技能。
     * @param elf 精灵实例
     * @param skill 基础技能编号
     * @return 一为种族、二为觉醒，零为不可升级
     */
    static int upgradeKind(LocalElf elf, int skill) {
        Set<Integer> racial = new LinkedHashSet<>();
        Set<Integer> awake = new LinkedHashSet<>();
        Set<Integer> seen = new LinkedHashSet<>();
        int awaken = elf.training.path("awakenlv").asInt();
        collect(elf.configId, elf.level, awaken, racial, awake, seen);
        elf.training.path("old_elfsid").forEach(old -> collect(old.asInt(), elf.level, awaken, racial, awake, seen));
        return racial.contains(skill) ? 1 : awake.contains(skill) ? 2 : 0;
    }

    /**
     * 根据这只精灵的战斗技能栏和底包技能表核对目标数量。
     *
     * @param elf 战斗成员
     * @param defaultSkill 旧档默认战斗技能
     * @param defaultLimit 默认技能的目标数
     * @param skill 玩家所选技能
     * @return 可命中的目标上限，零表示未装备
     */
    public static int targetLimit(LocalElf elf, int defaultSkill, int defaultLimit, int skill) {
        if (!battleSkills(elf, defaultSkill).contains(skill)) {
            return 0;
        }
        JsonNode upgradedTargets = UPGRADE.path("targetChanges").path(Integer.toString(skill));
        if (upgradedTargets.isInt()) return upgradedTargets.asInt();
        int base = skill >= 100000 && battleId(elf, skill / 10) == skill ? skill / 10 : skill;
        return base == defaultSkill ? defaultLimit : RULES.path("targets").path(String.valueOf(base)).asInt(1);
    }

    /**
     * 从原技能表读取本场已选中的极巨技能允许命中的目标数。
     * @param skill 本场普通或隐藏极巨技能编号
     * @return 实际目标上限，未知技能返回零
     */
    public static int dynamaxTargetLimit(int skill) {
        return RULES.path("targets").path(Integer.toString(skill)).asInt();
    }

    /**
     * 验证归属精灵可用的技能来源，并更新该实例的技能栏。
     *
     * @param elf 角色锁下读取的实例
     * @param equip 是否执行上阵
     * @param skill 操作的基础技能编号
     * @param replaced 被替换的已装备技能编号，零表示添加
     * @return 原客户端错误码，零表示成功
     */
    static int change(LocalElf elf, boolean equip, int skill, int replaced) {
        List<Integer> bar = equipped(elf);
        Set<Integer> racial = new LinkedHashSet<>();
        Set<Integer> awake = new LinkedHashSet<>();
        Set<Integer> seen = new LinkedHashSet<>();
        int awakened = elf.training.path("awakenlv").asInt();
        collect(elf.configId, elf.level, awakened, racial, awake, seen);
        elf.training.path("old_elfsid").forEach(old -> collect(old.asInt(), elf.level, awakened, racial, awake, seen));
        if (!equip) {
            if (!bar.remove(Integer.valueOf(skill))) {
                return 101029;
            }
        } else {
            if (!racial.contains(skill) && !awake.contains(skill)) {
                return 101020;
            }
            if (bar.contains(skill)) {
                return 101022;
            }
            if (replaced != 0) {
                int index = bar.indexOf(replaced);
                if (index < 0) {
                    return 101029;
                }
                bar.set(index, skill);
            } else if (bar.size() < CAPACITY) {
                bar.add(skill);
            } else {
                return 101021;
            }
        }
        if (bar.isEmpty()) {
            return 101028;
        }
        if (Collections.disjoint(bar, racial)) {
            return 101045;
        }
        ArrayNode saved = elf.training.putArray("skillBar");
        bar.forEach(saved::add);
        return 0;
    }

    /**
     * 汇总当前或进化前精灵的种族和觉醒技能。
     *
     * @param species 精灵模板
     * @param level 有效种族技能解锁等级
     * @param awakened 有效觉醒等级
     * @param racial 已解锁种族技能集合
     * @param awake 已解锁觉醒技能集合
     * @param seen 当前形态优先匹配的技能编号
     */
    private static void collect(int species, int level, int awakened, Set<Integer> racial, Set<Integer> awake,
                                Set<Integer> seen) {
        JsonNode base = ElfTrainingCatalog.row("species", species).path("skills");
        for (int i = 0; i + 1 < base.size(); i += 2) {
            int id = base.path(i + 1).asInt();
            if (seen.add(id) && base.path(i).asInt() <= level) {
                racial.add(id);
            }
        }
        JsonNode skills = RULES.path("awaken").path(String.valueOf(species));
        for (int i = 0; i + 1 < skills.size(); i += 2) {
            int id = skills.path(i + 1).asInt();
            if (seen.add(id) && skills.path(i).asInt() <= awakened) {
                awake.add(id);
            }
        }
    }
}
