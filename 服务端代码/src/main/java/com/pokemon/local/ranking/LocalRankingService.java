package com.pokemon.local.ranking;

import com.pokemon.local.activitylevel.AdventureProgressService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.role.RoleBattlePowerService;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * 普通排行榜的单玩家真实存档数据
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:28
 */
@Service
public final class LocalRankingService {

    private final RoleService roles;
    private final RoleBattlePowerService power;
    private final ElfService elfs;
    private final AdventureProgressService adventure;
    private final RoleCosmeticService cosmetics;

    /**
     * 初始化普通榜的角色与精灵数据来源
     *
     * @param roles 最新角色存档
     * @param power 统一队伍和精灵战力算法
     * @param elfs 已持有精灵查询
     * @param adventure 冒险通关存档
     * @param cosmetics 参与精灵战力计算的外观属性
     */
    public LocalRankingService(RoleService roles, RoleBattlePowerService power, ElfService elfs,
                               AdventureProgressService adventure, RoleCosmeticService cosmetics) {
        this.roles = roles;
        this.power = power;
        this.elfs = elfs;
        this.adventure = adventure;
        this.cosmetics = cosmetics;
    }

    /**
     * 为当前角色读取有确定事实来源的单人榜单条目
     *
     * @param roleId 已认证角色编号
     * @param index 原版榜单编号
     * @return 当前角色条目，缺少此类实际进度时为空
     */
    public Optional<RankingEntry> current(String roleId, int index) {
        Optional<LocalRole> found = roles.findById(roleId);
        if (!found.isPresent()) {
            return Optional.empty();
        }
        LocalRole role = found.get();
        switch (index) {
            case 1:
                long score = power.score(roleId);
                return score > 0 ? Optional.of(new RankingEntry(role, score, 0)) : Optional.empty();
            case 2:
                return Optional.of(new RankingEntry(role, role.coins, 0));
            case 3:
                return strongestElf(role);
            case 4:
                return Optional.of(new RankingEntry(role, role.level, 0));
            case 7:
                int cleared = adventure.clearedCount(roleId);
                return cleared > 0 ? Optional.of(new RankingEntry(role, cleared, 0)) : Optional.empty();
            default:
                return Optional.empty();
        }
    }

    /**
     * 读取该角色首个可用于真实排名的最高战力精灵
     *
     * @param role 最新角色存档
     * @return 最强已持有精灵和实际战力
     */
    private Optional<RankingEntry> strongestElf(LocalRole role) {
        long best = 0;
        int sid = 0;
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(role.id);
        for (LocalElf elf : elfs.list(role.id)) {
            long score = power.elfScore(elf, bonuses);
            if (score > best || (score > 0 && score == best && elf.configId < sid)) {
                best = score;
                sid = elf.configId;
            }
        }
        return sid != 0 ? Optional.of(new RankingEntry(role, best, sid)) : Optional.empty();
    }
}
