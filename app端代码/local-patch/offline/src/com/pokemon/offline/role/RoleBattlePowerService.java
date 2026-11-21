package com.pokemon.offline.role;

import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.elf.ElfCombatGrowth;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.platform.stereotype.Service;

/**
 * 单玩家活动榜与客户端顶部复用的本地队伍战力
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:15
 */
@Service
public final class RoleBattlePowerService {
    private final BattleMapper battles;
    private final ElfService elfs;
    private final RoleCosmeticService cosmetics;

    /**
     * 使用精灵服务读取该角色实际保存的阵容
     *
     * @param battles 普通编队与默认队存档
     * @param elfs 精灵和队伍服务
     * @param cosmetics 本地称号持有和时装属性
     */
    public RoleBattlePowerService(ElfService elfs, RoleCosmeticService cosmetics, BattleMapper battles) {
        this.battles = battles;
        this.elfs = elfs;
        this.cosmetics = cosmetics;
    }

    /** 查询战力及查看队伍所用的默认普通编队。
     * @param roleId 角色编号
     * @return 当前默认队号
     */
    public int defaultTeam(String roleId) { return battles.choice(roleId,"default"); }

    /**
     * 统计第一队实际可参战精灵六项基础属性之和，按精灵等级累加
     *
     * @param roleId 本地角色主键
     * @return 可解释的本地战力，未上阵时为零
     */
    public long score(String roleId) {
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(roleId);
        int team = defaultTeam(roleId);
        long total = 0;
        int placed = 0;
        java.util.List<LocalElf> fallback = new java.util.ArrayList<>();
        for (LocalElf elf : elfs.list(roleId)) {
            if (elf.team != team) continue;
            if (elf.slot >= 1 && elf.slot <= 6) {
                total += elfScore(elf, bonuses);
                placed++;
            } else if (elf.slot <= 0) {
                fallback.add(elf);
            }
        }
        // 新角色与自动编队的精灵可能尚未写入具体站位，此时按等级取前六只补足，
        // 避免整队战力被算成零、强化装备后也没有任何变化。
        if (placed == 0) {
            fallback.sort((a, b) -> Integer.compare(b.level, a.level));
            for (int i = 0; i < fallback.size() && i < 6; i++) {
                total += elfScore(fallback.get(i), bonuses);
            }
        }
        return total;
    }

    /**
     * 按原六项基础属性和持久化等级计算单只精灵的本地战力
     *
     * @param elf 实际持有的精灵
     * @param bonuses 当前角色属性加成
     * @return 本地战力，缺少战斗配置时为零
     */
    public long elfScore(LocalElf elf, RoleCosmeticService.BattleBonuses bonuses) {
        bonuses=bonuses.withPet(elf);
        FirstBattleData.Starter stats = FirstBattleData.starter(elf.configId);
        if (stats == null || elf.level < 1) {
            return 0;
        }
        long base = (long) bonuses.attribute("hp_max", ElfCombatGrowth.powerAttribute(elf, "hp_max", stats.life), elf.position())
                + bonuses.attribute("physical_attack", ElfCombatGrowth.powerAttribute(elf, "physical_attack", stats.physicalAttack), elf.position())
                + bonuses.attribute("special_attack", ElfCombatGrowth.powerAttribute(elf, "special_attack", stats.specialAttack), elf.position())
                + bonuses.attribute("physical_defense", ElfCombatGrowth.powerAttribute(elf, "physical_defense", stats.physicalDefense), elf.position())
                + bonuses.attribute("special_defense", ElfCombatGrowth.powerAttribute(elf, "special_defense", stats.specialDefense), elf.position())
                + bonuses.attribute("speed", ElfCombatGrowth.powerAttribute(elf, "speed", stats.speed), elf.position());
        return base * elf.level + ElfCombatGrowth.researchValue(elf, 0);
    }
}
