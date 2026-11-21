package com.pokemon.local.role;

import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.elf.ElfCombatGrowth;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import org.springframework.stereotype.Service;

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
        for (LocalElf elf : elfs.list(roleId)) {
            if (elf.team == team && elf.slot >= 1 && elf.slot <= 6) {
                total += elfScore(elf, bonuses);
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
        FirstBattleData.Starter stats = FirstBattleData.starter(elf.configId);
        if (stats == null || elf.level < 1) {
            return 0;
        }
        long base = (long) bonuses.attribute("hp_max", ElfCombatGrowth.attribute(elf, "hp_max", stats.life))
                + bonuses.attribute("physical_attack", ElfCombatGrowth.attribute(elf, "physical_attack", stats.physicalAttack))
                + bonuses.attribute("special_attack", ElfCombatGrowth.attribute(elf, "special_attack", stats.specialAttack))
                + bonuses.attribute("physical_defense", ElfCombatGrowth.attribute(elf, "physical_defense", stats.physicalDefense))
                + bonuses.attribute("special_defense", ElfCombatGrowth.attribute(elf, "special_defense", stats.specialDefense))
                + bonuses.attribute("speed", ElfCombatGrowth.attribute(elf, "speed", stats.speed));
        return base * elf.level + ElfCombatGrowth.researchValue(elf, 0);
    }
}
