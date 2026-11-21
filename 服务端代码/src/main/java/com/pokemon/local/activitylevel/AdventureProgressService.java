package com.pokemon.local.activitylevel;

import com.pokemon.local.activitylevel.api.entity.AdventureEquipment;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 游戏入口和闯关活动共用的冒险关卡存档读取服务。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:30
 */
@Service
public class AdventureProgressService {
    private static final int INITIAL_MISSION = 10001;
    private final LevelGiftMapper mapper;

    /**
     * 初始化冒险进度查询。
     *
     * @param mapper 冒险关卡存档
     */
    AdventureProgressService(LevelGiftMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 获取角色接下来挑战的冒险关卡号。
     *
     * @param roleId 角色编号
     * @return 下一关编号，新角色为第 1 关
     */
    public int nextMission(String roleId) {
        Integer next = mapper.nextMission(roleId);
        return next == null ? INITIAL_MISSION : next;
    }

    /**
     * 查询冒险排行榜显示的实际通关关数。
     *
     * @param roleId 角色编号
     * @return 已通过的关数
     */
    public int clearedCount(String roleId) {
        return nextMission(roleId) - INITIAL_MISSION;
    }

    /**
     * 在角色战斗结算事务内推进刚刚确认胜利的关卡。
     *
     * @param roleId 已锁定的角色
     * @param cleared 本场击败的关卡
     * @param next 该关底包配置中的下一关
     * @return 是否写入下一关
     */
    public boolean recordClear(String roleId, int cleared, int next) {
        if (cleared != nextMission(roleId) || next <= cleared) {
            return false;
        }
        mapper.saveNextMission(roleId, next);
        return true;
    }

    /**
     * 保存本次通关得到的装备实例，与进度推进在同一事务提交。
     *
     * @param roleId 已锁定的角色编号
     * @param configId 底包装备模板编号
     * @param slot 底包装备部位
     */
    public void grantEquipment(String roleId, int configId, int slot) {
        mapper.insertEquipment(roleId, new AdventureEquipment(UUID.randomUUID().toString(), configId, slot));
    }

    /**
     * 查询角色通过冒险获得的装备。
     *
     * @param roleId 角色编号
     * @return 装备背包视图
     */
    public List<AdventureEquipment> equipment(String roleId) {
        return mapper.equipment(roleId);
    }
}
