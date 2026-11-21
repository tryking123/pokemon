package com.pokemon.local.role;

import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 同服角色经验补偿 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:11
 */
@Service
public class ExperienceRecoveryService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int[] PRICES = {0, 0, 50, 200, 500};
    private static final int[] RATES = {0, 100, 200, 350, 500};
    private final RoleMapper mapper;
    private final RoleLevels levels;
    private final RoleService roles;
    private final InventoryService inventory;
    private final RoleCosmeticService cosmetics;

    /**
     * 注入本地区服、经验曲线、钱包和展示资料。
     * @param mapper 区服与补偿记录
     * @param levels 原角色升级曲线
     * @param roles 角色事务锁与经验写入
     * @param inventory 钱包
     * @param cosmetics 榜首外观
     */
    ExperienceRecoveryService(RoleMapper mapper, RoleLevels levels, RoleService roles,
                              InventoryService inventory, RoleCosmeticService cosmetics) {
        this.mapper = mapper;
        this.levels = levels;
        this.roles = roles;
        this.inventory = inventory;
        this.cosmetics = cosmetics;
    }

    /**
     * 构建 S2033 当前追赶基准与本日领取状态。
     * @param role 当前角色存档
     * @return 可显示的原协议字段，未开放时为空
     */
    public Map<String, Object> snapshot(LocalRole role) {
        Instant now = Instant.now();
        int status = mapper.recoveryStatus(role.id, now.atZone(ZONE).toLocalDate().toEpochDay());
        LocalRole leader = mapper.highestRole(role.realmId).orElse(role);
        long base = baseExperience(role, leader, now);
        if (base <= 0 && status == 0) {
            return Collections.emptyMap();
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("exp", status == 0 ? base : 0);
        body.put("status", status);
        body.put("name", leader.name);
        body.put("level", leader.level);
        body.put("sex", leader.sex);
        body.put("dress", cosmetics.wornDress(leader.id));
        body.put("title", cosmetics.displayedTitle(leader.id));
        return body;
    }

    /** 核验原变量 3601/3602 的全部开放条件并计算当日基础经验。
     * @param role 当前角色
     * @param leader 同服最高等级的实际角色
     * @param now 当前时间
     * @return 本地数值方案的当级阈值十分之一，条件未达时为零
     */
    private long baseExperience(LocalRole role, LocalRole leader, Instant now) {
        if (role.level < 30 || leader.level < 59 || leader.level - role.level < 4
                || now.getEpochSecond() - role.createdAt < 86400
                || now.getEpochSecond() - mapper.firstRoleTime(role.realmId) < 259200) {
            return 0;
        }
        long threshold = levels.threshold(role.stage, role.level);
        return threshold > 0 ? Math.max(1, threshold / 10) : 0;
    }

    /**
     * 在角色事务内按原选档扣钻并结算训练家经验。
     * @param roleId 当前角色
     * @param choice 原四个互斥领取档位
     * @return 原协议错误码
     */
    @Transactional
    public int claim(String roleId, long choice) {
        if (choice < 1 || choice > 4) {
            return 100021;
        }
        LocalRole role = roles.lockRole(roleId);
        Instant now = Instant.now();
        long day = now.atZone(ZONE).toLocalDate().toEpochDay();
        int previously = mapper.recoveryStatus(roleId, day);
        if (previously != 0) {
            return previously == choice ? 0 : 3;
        }
        long base = baseExperience(role, mapper.highestRole(role.realmId).orElse(role), now);
        if (base <= 0) {
            return 3;
        }
        int tier = (int) choice;
        if (PRICES[tier] != 0 && !inventory.spendMaterials(roleId, Collections.emptyMap(), 0, PRICES[tier])) {
            return 4;
        }
        long earned = Math.multiplyExact(base, RATES[tier]) / 100;
        roles.addCurrency(roleId, 0, 0, earned);
        mapper.saveRecovery(roleId, day, tier, earned);
        return 0;
    }
}
