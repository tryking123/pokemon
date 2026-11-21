package com.pokemon.offline.elf;

import com.pokemon.offline.elf.api.entity.ElfGear;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.role.RoleService;
import java.util.ArrayList;
import java.util.List;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 普通携带品整组继承，沿用原携带界面的来源和接收方向。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:42
 */
@Service
public class ElfCarryInheritanceService {
    private final RoleService roles;
    private final ElfMapper elves;
    private final ElfGearMapper gears;
    private final JdbcTemplate jdbc;

    /** 连接角色锁、精灵、携带品及操作回执。
     * @param roles 角色事务锁
     * @param elves 精灵实例
     * @param gears 已装备的携带品
     * @param jdbc 操作回执读写
     */
    public ElfCarryInheritanceService(RoleService roles, ElfMapper elves, ElfGearMapper gears, JdbcTemplate jdbc) {
        this.roles = roles;
        this.elves = elves;
        this.gears = gears;
        this.jdbc = jdbc;
    }

    /** 将来源精灵的普通携带品原样转给接收者，替换其原普通携带品。
     * @param role 认证角色
     * @param sourceId 未上阵的来源精灵
     * @param targetId 当前界面打开的接收精灵
     * @param receipt 连接和原请求号
     * @return 是否已经完成该次继承
     */
    @Transactional
    public boolean inherit(String role, String sourceId, String targetId, String receipt) {
        roles.lockRole(role);
        if (sourceId == null || targetId == null || sourceId.equals(targetId)) return false;
        List<Boolean> previous = jdbc.query("SELECT source_elf,target_elf FROM local_elf_carry_inherit WHERE role_id=? AND receipt=?",
                (row, index) -> sourceId.equals(row.getString(1)) && targetId.equals(row.getString(2)), role, receipt);
        if (!previous.isEmpty()) return previous.get(0);
        LocalElf source = elves.findOwned(role, sourceId).orElse(null);
        if (source == null || source.slot != 0 || !elves.findOwned(role, targetId).isPresent()) return false;
        List<ElfGear> transfer = new ArrayList<>();
        List<ElfGear> replace = new ArrayList<>();
        for (ElfGear gear : gears.list(role)) {
            if (gear.kind != ElfGearService.CARRY || (!sourceId.equals(gear.elfId) && !targetId.equals(gear.elfId))) continue;
            int quality = ElfTrainingCatalog.row("portable", gear.configId).path("quality").asInt();
            if (quality <= 0) return false;
            if (quality >= 6) continue;
            if (sourceId.equals(gear.elfId)) transfer.add(gear);
            else {
                if (gear.locked) return false;
                replace.add(gear);
            }
        }
        if (transfer.isEmpty()) return false;
        for (ElfGear gear : replace) {
            if (gears.remove(role, gear.id) != 1) throw new IllegalStateException("被替换的普通携带品已变化");
        }
        for (ElfGear gear : transfer) {
            if (gears.equip(role, gear.id, targetId, gear.position) != 1) throw new IllegalStateException("来源携带品已变化");
        }
        jdbc.update("INSERT INTO local_elf_carry_inherit(role_id,receipt,source_elf,target_elf) VALUES(?,?,?,?)",
                role,receipt,sourceId,targetId);
        return true;
    }
}
