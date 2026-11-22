package com.pokemon.offline.hangup;

import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.balance.GameplayRewards;
import com.pokemon.offline.battle.AdventureStage;
import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.payment.SpeedCardService;
import com.pokemon.offline.hangup.api.entity.HangupState;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.Map;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Propagation;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 冒险挂机累计与领取 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:15
 */
@Service
public class HangupService {
    // variable_proto[2080]：累计上限 86400 秒，基础奖励每秒结算。
    private static final int MAX_SECONDS = 86400;
    // HookPool.is_can_recv 的数值类领取门槛。
    private static final int MIN_SECONDS = 15;
    private static final int QUICKEN_SECONDS = 7200;
    private static final String NORMAL_DROP = "hangup.normal";
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");
    // variable_proto[1090].data6；超过原表次数后沿用末档价格。
    private static final int[] QUICKEN_COST =
            {200, 400, 800, 1200, 1600, 2000, 2500, 3000, 3000, 4000, 4000, 5000, 7000, 7000, 10000, 10000};
    private final HangupMapper mapper;
    private final BattleMapper battles;
    private final RoleService roles;
    private final AdventureProgressService adventure;
    private final InventoryService inventory;
    private final GamePaymentService payments;
    private final GameplayRewards rewards;
    private final SpeedCardService speed;

    /**
     * 连接挂机收益、角色与冒险进度存档。
     *
     * @param battles 普通副本的真实战斗通关记录
     * @param mapper 挂机存档
     * @param roles 角色交易锁和资产服务
     * @param adventure 冒险进度
     * @param inventory 地图通关奖励背包
     * @param payments 最新 VIP 额度
     * @param rewards 全局挂机掉落时间间隔和概率池
     * @param speed 挂机额外金币和经验权益
     */
    HangupService(HangupMapper mapper, BattleMapper battles, RoleService roles, AdventureProgressService adventure,
                  InventoryService inventory, GamePaymentService payments, GameplayRewards rewards, SpeedCardService speed) {
        this.speed = speed;
        this.mapper = mapper;
        this.battles = battles;
        this.roles = roles;
        this.adventure = adventure;
        this.inventory = inventory;
        this.payments = payments;
        this.rewards = rewards;
    }

    /**
     * 启动挂机或切换展示地图，保留已累计的收益。
     *
     * @param roleId 角色编号
     * @param map 已核实可进入的挂机地图
     */
    @Transactional
    public void activate(String roleId, HangupMap map) {
        roles.lockRole(roleId);
        HangupState state = accrue(roleId, mapper.load(roleId), adventure.nextMission(roleId));
        mapper.save(roleId, new HangupState(map.id, state.creditedAt, state.pendingSeconds, state.coins,
                state.roleExp, state.elfExp, state.elfPool, Collections.emptyMap(), state.dungeonGroup, state.dungeonSeconds));
    }

    /**
     * 读取已经完成全部守卫和首领战的可挂机普通副本。
     * @param roleId 角色编号
     * @return 当前已解锁的副本组
     */
    public List<Integer> dungeonGroups(String roleId) {
        return battles.cleared(roleId).containsAll(FirstBattleData.ENCOUNTERS)
                ? Collections.singletonList(FirstBattleData.GROUP) : Collections.emptyList();
    }

    /**
     * 先按现有来源固化累计秒数，再切换后续收益来源。
     * @param roleId 当前角色
     * @param group 副本组号，零恢复普通冒险
     * @param receipt 连接请求回执
     * @return 是否已保存本次选择
     */
    @Transactional
    public boolean selectDungeon(String roleId,long group,String receipt) {
        roles.lockRole(roleId);
        if(mapper.handled(roleId,receipt) || group!=0
                && (group!=FirstBattleData.GROUP || dungeonGroups(roleId).isEmpty())) return false;
        HangupState saved=mapper.load(roleId);
        if(saved==null && group==0) return true;
        HangupState bank=accrue(roleId,saved,adventure.nextMission(roleId));
        mapper.save(roleId,new HangupState(bank.mapId,bank.creditedAt,bank.pendingSeconds,bank.coins,
                bank.roleExp,bank.elfExp,bank.elfPool,Collections.emptyMap(),(int)group,bank.dungeonSeconds));
        mapper.record(roleId,receipt);
        return true;
    }

    /**
     * 在冒险进度变化前固定旧关卡时段的收益。
     *
     * @param roleId 当前战斗角色
     * @param mission 战斗结算前的冒险关卡
     */
    @Transactional
    public void freezeRate(String roleId, int mission) {
        roles.lockRole(roleId);
        HangupState saved = mapper.load(roleId);
        if (saved != null) {
            mapper.save(roleId, accrue(roleId, saved, mission));
        }
    }

    /**
     * 返回客户端同步使用的当前收益。
     *
     * @param roleId 角色编号
     * @param mission 客户端同步的下一关
     * @return 当前累计和精灵经验池
     */
    @Transactional
    public HangupState snapshot(String roleId, int mission) {
        int scene = roles.lockRole(roleId).sceneId;
        HangupState saved = mapper.load(roleId);
        HangupMap currentMap = HangupMap.byDoor(scene);
        if (saved == null && currentMap != null) {
            saved = new HangupState(currentMap.id, Instant.now().getEpochSecond(), 0, 0, 0, 0, 0);
            mapper.save(roleId, saved);
        }
        HangupState state = accrue(roleId, saved, mission);
        Map<Integer, Long> items = rewards.hangupItems(roleId, NORMAL_DROP,
                mapper.settledSeconds(roleId), state.pendingSeconds-state.dungeonSeconds);
        return withItems(state, items);
    }

    /**
     * 按已实际领取的普通挂机秒数计算累计获得的探险值。
     *
     * @param roleId 角色编号
     * @return 每满六十秒产生一点的累计探险值
     */
    public long earnedChallenge(String roleId) {
        return mapper.settledSeconds(roleId) / 60;
    }

    /**
     * 查询已发放的精灵经验池余额。
     *
     * @param roleId 角色编号
     * @return 已领取的精灵经验
     */
    public long elfPool(String roleId) {
        HangupState saved = mapper.load(roleId);
        return saved == null ? 0 : saved.elfPool;
    }

    /**
     * 将已消耗的经验卡奖励计入精灵经验池。
     *
     * @param roleId 已锁定的角色编号
     * @param amount 本次增加的精灵经验
     * @return 增加后的经验池余额
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long addElfPool(String roleId, long amount) {
        HangupState state = mapper.load(roleId);
        if (state == null) {
            state = accrue(roleId, null, 0);
        }
        long balance = Math.addExact(state.elfPool, amount);
        mapper.save(roleId, new HangupState(state.mapId, state.creditedAt, state.pendingSeconds,
                state.coins, state.roleExp, state.elfExp, balance, Collections.emptyMap(), state.dungeonGroup, state.dungeonSeconds));
        return balance;
    }

    /**
     * 在精灵养成事务内扣除已领取的经验。
     *
     * @param roleId 角色编号
     * @param amount 消耗经验
     * @return 是否已经扣除
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean spendElfPool(String roleId, long amount) {
        roles.lockRole(roleId);
        return amount > 0 && mapper.spendElfPool(roleId, amount) == 1;
    }

    /**
     * 在角色锁事务内按服务器时钟一次性结算并重置挂机收益。
     *
     * @param roleId 角色编号
     * @return 此次结算的收益，尚不能领取时为空
     */
    @Transactional
    public HangupState claim(String roleId) {
        return claim(roleId, false, null);
    }

    /**
     * 以同一时间游标从冒险或副本入口领取收益，到账与回执一起提交。
     * @param roleId 角色编号
     * @param dungeonOnly 是否来自要求已选副本的领取按钮
     * @param receipt 连接请求回执，旧调用可省略
     * @return 本次实际结算的资产；条件不满足或重复时为空
     */
    @Transactional
    public HangupState claim(String roleId, boolean dungeonOnly, String receipt) {
        roles.lockRole(roleId);
        if (receipt != null && mapper.handled(roleId, receipt)) return null;
        HangupState bank = accrue(roleId, mapper.load(roleId), adventure.nextMission(roleId));
        if (dungeonOnly && bank.dungeonGroup == 0) return null;
        long settled = mapper.settledSeconds(roleId);
        int adventureSeconds=bank.pendingSeconds-bank.dungeonSeconds;
        Map<Integer, Long> items = rewards.hangupItems(roleId, NORMAL_DROP, settled, adventureSeconds);
        if (bank.pendingSeconds < MIN_SECONDS && items.isEmpty()) {
            return null;
        }
        Map<Integer, Long> delivered = items.isEmpty() ? Collections.emptyMap() :
                inventory.grantItemsOrMail(roleId, "hangup.normal:" + settled, items);
        roles.addCurrency(roleId, bank.coins, 0, bank.roleExp);
        mapper.save(roleId, new HangupState(bank.mapId, bank.creditedAt, 0, 0, 0, 0,
                Math.addExact(bank.elfPool, bank.elfExp), Collections.emptyMap(), bank.dungeonGroup, 0));
        mapper.saveSettledSeconds(roleId, Math.addExact(settled, adventureSeconds));
        if (receipt != null) mapper.record(roleId,receipt);
        return withItems(bank, delivered);
    }

    /**
     * 查询已领取的冒险地图通关奖励。
     *
     * @param roleId 角色编号
     * @return 领取过的原地图索引
     */
    public List<Integer> claimedMaps(String roleId) {
        return mapper.claimedMaps(roleId);
    }

    /**
     * 在同一事务内发放地图奖励并登记领取记录。
     *
     * @param roleId 角色编号
     * @param map 由原始地图编号解析出的配置
     * @return 实际放入背包的物品，未获领奖资格时为空引用
     */
    @Transactional
    public Map<Integer, Long> claimMap(String roleId, HangupMap map) {
        LocalRole role = roles.lockRole(roleId);
        if (map == null || role.stage < map.requiredWorld || adventure.nextMission(roleId) <= map.rewardMission
                || mapper.claimedMaps(roleId).contains(map.id)) {
            return null;
        }
        Map<Integer, Long> items = new LinkedHashMap<>();
        items.put(HangupMap.STONE, (long) map.stones);
        items.put(HangupMap.GACHA_TICKET, 1L);
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "hangup.map:" + map.id, items);
        roles.addCurrency(roleId, map.coins, map.diamonds, 0);
        mapper.claimMap(roleId, map.id);
        return delivered;
    }

    /**
     * 读取今日已使用的快速战斗次数。
     *
     * @param roleId 角色编号
     * @return 今日结算次数
     */
    public int quickenCount(String roleId) {
        String day = Instant.now().atZone(GAME_ZONE).toLocalDate().toString();
        return mapper.quickenCount(roleId, day, null);
    }

    /**
     * 获取原导航显示的当日超速闯关已用次数。
     * @param roleId 角色编号
     * @return 当日已用次数
     */
    public int fastTimes(String roleId) {
        return mapper.fastTimes(roleId, Instant.now().atZone(GAME_ZONE).toLocalDate().toString());
    }

    /**
     * 检查已提交的超速闯关请求。
     * @param roleId 角色编号
     * @param key 当前连接的请求编号
     * @return 是否已经结算
     */
    public boolean usedFast(String roleId, String key) {
        return mapper.usedFast(roleId, key);
    }

    /**
     * 在持有角色锁的结算事务内记录成功请求，不限制当天使用次数。
     * @param roleId 角色编号
     * @param key 当前连接的请求编号
     * @return 首次登记时为真
     */
    public boolean recordFast(String roleId, String key) {
        String day = Instant.now().atZone(GAME_ZONE).toLocalDate().toString();
        return mapper.recordFast(roleId, day, key);
    }

    /**
     * 不限次数，按当前关卡收益和原阶梯价格结算两小时快速战斗。
     *
     * @param roleId 角色编号
     * @param requestId 连接和请求号组成的结算键
     * @return 本次已到账的奖励，重放时奖励数为零，条件不满足时为空
     */
    @Transactional
    public HangupState quicken(String roleId, String requestId) {
        roles.lockRole(roleId);
        Instant now = Instant.now();
        String day = now.atZone(GAME_ZONE).toLocalDate().toString();
        int mission = adventure.nextMission(roleId);
        HangupState state = accrue(roleId, mapper.load(roleId), mission);
        if (mapper.quickenCount(roleId, day, requestId) > 0) {
            return new HangupState(state.mapId, state.creditedAt, 0, 0, 0, 0, state.elfPool);
        }
        int used = mapper.quickenCount(roleId, day, null);
        Map<Integer, Long> items = rewards.hangupItems(roleId, "hangup.quicken:" + day,
                (long) used * QUICKEN_SECONDS, QUICKEN_SECONDS);
        if (!inventory.spendMaterials(roleId, 0, 0, 0, QUICKEN_COST[Math.min(used, QUICKEN_COST.length - 1)])) {
            return null;
        }
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, "hangup.quicken:" + day + ":" + used, items);
        AdventureStage rate = AdventureStage.hangupRate(mission);
        SpeedCardService.Bonus bonus = speed.bonus(roleId, now.getEpochSecond(), QUICKEN_SECONDS, true);
        long coins = (long) QUICKEN_SECONDS * rate.coins + bonus.coins(rate.coins);
        long roleExp = (long) QUICKEN_SECONDS * rate.roleExp + bonus.experience(rate.roleExp);
        long elfExp = (long) QUICKEN_SECONDS * rate.elfExp + bonus.experience(rate.elfExp);
        roles.addCurrency(roleId, coins, 0, roleExp);
        long pool = Math.addExact(state.elfPool, elfExp);
        mapper.save(roleId, new HangupState(state.mapId, state.creditedAt, state.pendingSeconds,
                state.coins, state.roleExp, state.elfExp, pool, Collections.emptyMap(), state.dungeonGroup, state.dungeonSeconds));
        mapper.recordQuicken(roleId, day, requestId);
        return new HangupState(state.mapId, state.creditedAt, QUICKEN_SECONDS, coins, roleExp, elfExp, pool, delivered);
    }

    /**
     * 合并收益快照与本次计时对应的道具。
     *
     * @param state 数值与计时快照
     * @param items 本次掉落明细
     * @return 同时携带数值和道具的快照
     */
    private HangupState withItems(HangupState state, Map<Integer, Long> items) {
        return new HangupState(state.mapId, state.creditedAt, state.pendingSeconds, state.coins,
                state.roleExp, state.elfExp, state.elfPool, items, state.dungeonGroup, state.dungeonSeconds);
    }

    /**
     * 在原累计上限内只计入尚未结算的服务器时间。
     *
     * @param state 上次记录的收益
     * @param roleId 当前角色，用于核对加成有效时间
     * @param mission 当前有效冒险关卡
     * @return 计算到当前服务器时刻的收益
     */
    private HangupState accrue(String roleId, HangupState state, int mission) {
        long now = Instant.now().getEpochSecond();
        if (state == null) {
            return new HangupState(HangupMap.FIRST.id, now, 0, 0, 0, 0, 0);
        }
        long elapsed = Math.max(0, now - state.creditedAt);
        int seconds = (int) Math.min(MAX_SECONDS - state.pendingSeconds, elapsed);
        if (state.dungeonGroup == FirstBattleData.GROUP) {
            // 桐树林三项原始速率对应角色经验、精灵经验和金币；仅冒险时段产生冒险道具。
            return new HangupState(state.mapId, Math.max(now,state.creditedAt),state.pendingSeconds+seconds,
                    state.coins+3L*seconds,state.roleExp+5L*seconds,state.elfExp+8L*seconds,state.elfPool,
                    Collections.emptyMap(),state.dungeonGroup,state.dungeonSeconds+seconds);
        }
        AdventureStage rate = AdventureStage.hangupRate(mission);
        SpeedCardService.Bonus bonus = speed.bonus(roleId, state.creditedAt, seconds, false);
        return new HangupState(state.mapId, Math.max(now, state.creditedAt), state.pendingSeconds + seconds,
                state.coins + (long) seconds * rate.coins + bonus.coins(rate.coins),
                state.roleExp + (long) seconds * rate.roleExp + bonus.experience(rate.roleExp),
                state.elfExp + (long) seconds * rate.elfExp + bonus.experience(rate.elfExp), state.elfPool,
                Collections.emptyMap(),state.dungeonGroup,state.dungeonSeconds);
    }
}
