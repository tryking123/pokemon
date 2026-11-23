package com.pokemon.offline.hangup.api.entity;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.offline.elf.api.entity.LocalElf;

/**
 * 冒险挂机状态，用于持久化待领奖励、计时和已领取的精灵经验池。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:15
 */
public final class HangupState {
    /** 当前冒险展示地图。 */
    public final int mapId;
    /** 当前副本挂机组，零为普通冒险。 */
    public final int dungeonGroup;
    /** 待领时段中按副本速率累计的秒数，不计入冒险掉落和探险值。 */
    public final int dungeonSeconds;
    public final long creditedAt;
    public final int pendingSeconds;
    public final long coins;
    public final long roleExp;
    public final long elfExp;
    public final long elfPool;
    /** 当前快照中待领取或本次结算已经入账的掉落。 */
    public final Map<Integer, Long> items;
    /** 快速战斗中实际进入普通背包的训练家装备实例。 */
    public final List<AdventureEquipment> equipment;
    /** 快速战斗中实际进入精灵背包的精灵实例。 */
    public final List<LocalElf> elves;
    /** 快速战斗前置校验错误码，零表示成功。 */
    public final int error;

    /**
     * 记录该角色的挂机收益。
     *
     * @param mapId 当前挂机地图索引
     * @param creditedAt 已结算时间段终点
     * @param pendingSeconds 已累计的未领取时间
     * @param coins 未领取金币
     * @param roleExp 未领取角色经验
     * @param elfExp 未领取精灵经验
     * @param elfPool 已领取的精灵经验池总量
     */
    public HangupState(int mapId, long creditedAt, int pendingSeconds, long coins, long roleExp,
                       long elfExp, long elfPool) {
        this(mapId, creditedAt, pendingSeconds, coins, roleExp, elfExp, elfPool, Collections.emptyMap());
    }

    /**
     * 记录挂机收益及其对应的道具掉落。
     *
     * @param mapId 当前挂机地图索引
     * @param creditedAt 已结算时间段终点
     * @param pendingSeconds 本次快照或结算对应的挂机秒数
     * @param coins 快照或结算中的金币
     * @param roleExp 快照或结算中的角色经验
     * @param elfExp 快照或结算中的精灵经验
     * @param elfPool 精灵经验池总量
     * @param items 配置掉落中实际抽取的物品
     */
    public HangupState(int mapId, long creditedAt, int pendingSeconds, long coins, long roleExp,
                       long elfExp, long elfPool, Map<Integer, Long> items) {
        this(mapId, creditedAt, pendingSeconds, coins, roleExp, elfExp, elfPool, items, 0, 0);
    }

    /**
     * 构造同时保留冒险与副本挂机来源的收益记录。
     * @param mapId 冒险地图
     * @param creditedAt 待领时间段终点
     * @param pendingSeconds 共用待领秒数
     * @param coins 金币
     * @param roleExp 角色经验
     * @param elfExp 精灵经验
     * @param elfPool 已到账精灵经验池
     * @param items 已确定的冒险道具
     * @param dungeonGroup 当前副本组
     * @param dungeonSeconds 待领时段中的副本秒数
     */
    public HangupState(int mapId, long creditedAt, int pendingSeconds, long coins, long roleExp,
                       long elfExp, long elfPool, Map<Integer,Long> items, int dungeonGroup, int dungeonSeconds) {
        this(mapId, creditedAt, pendingSeconds, coins, roleExp, elfExp, elfPool, items, dungeonGroup,
                dungeonSeconds, Collections.emptyList(), Collections.emptyList(), 0);
    }

    /**
     * 建立包含本次实际入包实例的结算记录。
     * @param mapId 冒险地图
     * @param creditedAt 结算时刻
     * @param pendingSeconds 本次收益秒数
     * @param coins 金币
     * @param roleExp 角色经验
     * @param elfExp 精灵经验
     * @param elfPool 精灵经验池余额
     * @param items 实际发放道具
     * @param dungeonGroup 当前副本组
     * @param dungeonSeconds 副本收益秒数
     * @param equipment 实际入包的装备
     * @param elves 实际入包的精灵
     * @param error 前置校验错误码
     */
    public HangupState(int mapId, long creditedAt, int pendingSeconds, long coins, long roleExp,
                       long elfExp, long elfPool, Map<Integer,Long> items, int dungeonGroup, int dungeonSeconds,
                       List<AdventureEquipment> equipment, List<LocalElf> elves, int error) {
        this.equipment = Collections.unmodifiableList(equipment);
        this.elves = Collections.unmodifiableList(elves);
        this.error = error;
        this.dungeonGroup = dungeonGroup;
        this.dungeonSeconds = dungeonSeconds;
        this.mapId = mapId;
        this.creditedAt = creditedAt;
        this.pendingSeconds = pendingSeconds;
        this.coins = coins;
        this.roleExp = roleExp;
        this.elfExp = elfExp;
        this.elfPool = elfPool;
        this.items = items;
    }
}
