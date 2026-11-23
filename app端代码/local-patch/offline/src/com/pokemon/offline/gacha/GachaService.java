package com.pokemon.offline.gacha;

import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.quest.QuestService;
import com.pokemon.offline.quest.api.entity.QuestTask;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 原游戏普通、高级和友情扭蛋的本地结算服务
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:01
 */
@Service
public class GachaService {

    static final int NORMAL_LIMIT = 5;
    public static final int COUPON_ITEM = 34013;
    public static final int ELF_REWARD = 2;
    private static final int NORMAL = 1;
    private static final int ADVANCED = 2;
    private static final int ADVANCED_TEN = 3;
    private static final int FRIEND = 4;
    /** 高级扭蛋单抽在无免费次数和扭蛋券时实际花费的钻石。 */
    public static final int ADVANCED_COST = 1500;
    private static final int ADVANCED_TEN_COST = 14000;
    private static final int FRIEND_COST = 50;
    private static final String FRIEND_OPENING_GRANT = "friendship-opening-v1";
    private static final long NORMAL_COOLDOWN = 600;
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");

    private final GachaMapper mapper;
    private final SpecialEggPoolService special;
    private final RoleService roles;
    private final ElfService elfs;
    private final InventoryService inventory;
    private final QuestService quests;
    private final int firstNormalElf;
    private final int[] normalItems;
    private final int[] normalElves;
    private final int[] advancedElves;
    private final int[] friendItems;
    private final long openingFriendPoints;
    private final boolean dailyAdvancedFree;

    /**
     * 初始化原固定首抽和单人替代奖池
     *
     * @param mapper 扭蛋数据访问层
     * @param roles 角色锁服务
     * @param elfs 精灵保存服务
     * @param inventory 普通背包服务
     * @param quests 主线进度服务
     * @param special 超级蛋池幸运值与保底
     * @throws IOException 奖励配置读取失败
     */
    GachaService(GachaMapper mapper, RoleService roles, ElfService elfs,
                 InventoryService inventory, QuestService quests, SpecialEggPoolService special) throws IOException {
        this.mapper = mapper;
        this.special = special;
        this.roles = roles;
        this.elfs = elfs;
        this.inventory = inventory;
        this.quests = quests;
        Properties configuration = new Properties();
        try (InputStream input = new ClassPathResource("gacha/catalog.properties").getInputStream()) {
            configuration.load(input);
        }
        this.firstNormalElf = Integer.parseInt(configuration.getProperty("normal.firstElf"));
        this.normalItems = configuredItems(configuration.getProperty("normal.repeatItems"));
        this.normalElves = Arrays.stream(configuration.getProperty("normal.repeatElves").split(","))
                .mapToInt(Integer::parseInt).toArray();
        for (int id : normalElves) {
            if (FirstBattleData.starter(id) == null) {
                throw new IllegalArgumentException("普通扭蛋精灵模板缺失：" + id);
            }
        }
        this.advancedElves = Arrays.stream(configuration.getProperty("advanced.elves").split(","))
                .mapToInt(Integer::parseInt).toArray();
        this.friendItems = configuredItems(configuration.getProperty("friend.repeatItems"));
        this.openingFriendPoints = Long.parseLong(configuration.getProperty("friend.openingPoints"));
        this.dailyAdvancedFree = "1".equals(configuration.getProperty("advanced.dailyFree"));
        inventory.itemType(COUPON_ITEM);
    }

    /**
     * 查询应显示的抽取次数、冷却和重置时间
     *
     * @param roleId 角色标识
     * @return 本地扭蛋状态
     */
    @Transactional
    public GachaState snapshot(String roleId) {
        roles.lockRole(roleId);
        return ensureFriendshipGrant(roleId, readState(roleId, Instant.now()));
    }

    /**
     * 验证抽取、发放奖励并推进任务，所有变化由同一事务提交
     *
     * @param roleId 角色标识
     * @param requestId 连接及原请求号生成的业务键
     * @param type 原协议抽取类型
     * @return 真实结算或拒绝结果
     */
    @Transactional
    public GachaDraw draw(String roleId, String requestId, long type) {
        roles.lockRole(roleId);
        Instant clock = Instant.now();
        long now = clock.getEpochSecond();
        GachaState old = ensureFriendshipGrant(roleId, readState(roleId, clock));
        if (type < NORMAL || type > FRIEND) {
            return GachaDraw.failure(100031, old);
        }

        Optional<GachaMapper.DrawReceipt> previous = mapper.receipt(roleId, requestId);
        if (previous.isPresent()) {
            GachaMapper.DrawReceipt receipt = previous.get();
            return receipt.type != type ? GachaDraw.failure(100031, old)
                    : new GachaDraw(0, receipt.rewards, receipt.spent, receipt.coupon, false, old, Collections.emptyList());
        }

        boolean normal = type == NORMAL;
        boolean friend = type == FRIEND;
        boolean free = type == ADVANCED && old.advancedFree > 0;
        int quantity = type == ADVANCED_TEN ? 10 : 1;
        if (normal) {
            if (old.normalRemaining <= 0) {
                return GachaDraw.failure(100027, old);
            }
            if (old.normalTime > 0 && now < old.normalTime + NORMAL_COOLDOWN) {
                return GachaDraw.failure(100026, old);
            }
        }
        if (friend && old.friendPoints < FRIEND_COST) {
            return GachaDraw.failure(100029, old);
        }

        List<GachaDraw.Reward> rewards = new ArrayList<>(quantity);
        int drawn = normal ? old.normalTotal : friend ? old.friendTotal : old.advancedTotal;
        SpecialEggPoolService.Pool pool = normal || friend ? null : special.load(roleId);
        for (int i = 0; i < quantity; i++) {
            int alreadyDrawn = Math.addExact(drawn, i);
            GachaDraw.Reward standard = reward(type, alreadyDrawn);
            rewards.add(pool == null ? standard : special.advance(pool, Math.addExact(alreadyDrawn, 1), standard));
        }

        boolean paidAdvanced = !normal && !friend && !free;
        long availableCoupons = paidAdvanced ? inventory.count(roleId, COUPON_ITEM) : 0;
        int coupons = availableCoupons >= quantity ? quantity : 0;
        if (!canStoreRewards(roleId, rewards, coupons != 0 && availableCoupons == coupons)) {
            return GachaDraw.failure(100015, old);
        }
        int diamonds = paidAdvanced && coupons == 0 ? (type == ADVANCED_TEN ? ADVANCED_TEN_COST : ADVANCED_COST) : 0;
        if (diamonds > 0 && mapper.spendDiamonds(roleId, diamonds) != 1) {
            return GachaDraw.failure(100006, old);
        }
        for (int i = 0; i < coupons; i++) {
            if (!inventory.takeOne(roleId, COUPON_ITEM)) {
                throw new IllegalStateException("扭蛋券余额在结算期间变化");
            }
        }
        for (GachaDraw.Reward reward : rewards) {
            if (reward.type == ELF_REWARD) {
                elfs.grant(roleId, reward.id);
            } else {
                inventory.grant(roleId, reward.id, 1);
            }
        }
        GachaState updated = new GachaState(old.day, normal ? old.used + 1 : old.used,
                normal ? now : old.normalTime, normal ? Math.addExact(old.normalTotal, 1) : old.normalTotal,
                normal || friend ? old.advancedTotal : Math.addExact(old.advancedTotal, quantity),
                free ? old.day : old.advancedFreeDay, dailyAdvancedFree,
                friend ? Math.addExact(old.friendTotal, 1) : old.friendTotal,
                friend ? old.friendPoints - FRIEND_COST : old.friendPoints, old.nextReset);
        mapper.save(roleId, updated);
        if (pool != null) {
            special.save(roleId, pool);
        }
        Map<Integer, QuestTask> changes = new LinkedHashMap<>();
        if (!friend) {
            for (int i = 0; i < quantity; i++) {
                quests.recordGacha(roleId, normal ? 2 : 1).forEach(task -> changes.put(task.id, task));
            }
        }
        GachaDraw result = new GachaDraw(0, rewards, diamonds, coupons > 0, true, updated, new ArrayList<>(changes.values()));
        mapper.saveReceipt(roleId, requestId, type, result, now);
        return result;
    }

    /**
     * 确定基础奖励。高级单抽和十连的每个普通奖位均从高级预览名单等概率抽取。
     *
     * @param type 原协议抽取类型
     * @param drawn 历史同类型抽取数字
     * @return 对应的原协议奖励
     */
    private GachaDraw.Reward reward(long type, int drawn) {
        if (type == FRIEND) {
            return new GachaDraw.Reward(1, friendItems[drawn % friendItems.length]);
        }
        if (type != NORMAL) {
            int index = ThreadLocalRandom.current().nextInt(advancedElves.length);
            return new GachaDraw.Reward(ELF_REWARD, advancedElves[index]);
        }
        if (drawn == 0) {
            return new GachaDraw.Reward(ELF_REWARD, firstNormalElf);
        }
        int index = ThreadLocalRandom.current().nextInt(normalItems.length + normalElves.length);
        return index < normalItems.length ? new GachaDraw.Reward(1, normalItems[index])
                : new GachaDraw.Reward(ELF_REWARD, normalElves[index - normalItems.length]);
    }

    /**
     * 在角色锁内提前核对背包容量，避免失败时仍尝试扣费
     *
     * @param roleId 角色标识
     * @param rewards 本次抽取将发放的奖励
     * @param clearsCouponStack 扣券后是否腾出一个背包格
     * @return 奖励能否存入背包
     */
    private boolean canStoreRewards(String roleId, List<GachaDraw.Reward> rewards, boolean clearsCouponStack) {
        long elfCount = rewards.stream().filter(reward -> reward.type == ELF_REWARD).count();
        if (elfCount > 0 && elfs.list(roleId).size() + elfCount > inventory.elfCapacity(roleId)) {
            return false;
        }
        Map<Integer, Long> items = new LinkedHashMap<>();
        rewards.stream().filter(reward -> reward.type != ELF_REWARD)
                .forEach(reward -> items.merge(reward.id, 1L, Long::sum));
        if (items.isEmpty()) {
            return true;
        }
        int slots = inventory.ordinaryUsed(roleId) - (clearsCouponStack ? 1 : 0);
        for (Map.Entry<Integer, Long> item : items.entrySet()) {
            long existing = inventory.count(roleId, item.getKey());
            if (existing + item.getValue() > Integer.MAX_VALUE) {
                return false;
            }
            if (existing == 0) {
                slots++;
            }
        }
        return slots <= inventory.ordinaryCapacity(roleId);
    }

    /**
     * 读取北京时间每日重置后的有效扭蛋状态
     *
     * @param roleId 角色标识
     * @param now 当前时刻
     * @return 当前日期有效状态
     */
    private GachaState readState(String roleId, Instant now) {
        LocalDate day = now.atZone(GAME_ZONE).toLocalDate();
        long nextReset = day.plusDays(1).atStartOfDay(GAME_ZONE).toEpochSecond();
        return mapper.state(roleId, day, day.atStartOfDay(GAME_ZONE).toEpochSecond(), nextReset, dailyAdvancedFree);
    }

    /**
     * 在已锁定角色的事务内补发一次初始友情点，并继承旧档余额及抽取记录。
     *
     * @param roleId 角色标识
     * @param state 当前扭蛋状态
     * @return 已包含初始发放的当前状态
     */
    private GachaState ensureFriendshipGrant(String roleId, GachaState state) {
        if (mapper.hasFriendshipGrant(roleId, FRIEND_OPENING_GRANT)) {
            return state;
        }
        GachaState initialized = new GachaState(state.day, state.used, state.normalTime, state.normalTotal,
                state.advancedTotal, state.advancedFreeDay, dailyAdvancedFree, state.friendTotal,
                Math.addExact(state.friendPoints, openingFriendPoints), state.nextReset);
        mapper.save(roleId, initialized);
        mapper.recordFriendshipGrant(roleId, FRIEND_OPENING_GRANT);
        return initialized;
    }

    /**
     * 验证替代奖池中的道具均存在于同版本配置
     *
     * @param ids 逗号分隔的原道具编号
     * @return 可轮换的道具编号
     */
    private int[] configuredItems(String ids) {
        int[] values = Arrays.stream(ids.split(",")).mapToInt(Integer::parseInt).toArray();
        for (int item : values) {
            inventory.itemType(item);
        }
        return values;
    }
}
