package com.pokemon.offline.activitylogin;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.activitylogin.api.entity.LoginActivityState;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Propagation;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 七日登录活动 Service，按北京自然日累计登录并在同一事务发放奖励
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:03
 */
@Service
public class LoginActivityService {
    private static final int ACTIVITY_ID = 10005;
    private static final long DAY_SECONDS = 86_400;
    public static final int ITEM = 1;
    public static final int ELF = 2;
    public static final int DRESS = 6;
    public static final int DIAMONDS = 104;
    private static final int DIAMOND_ITEM_ID = 1002;
    // 底包 land_proto 的七行奖品，每三个整数依次为类型、配置编号和数量。
    private static final List<List<Integer>> REWARDS = Collections.unmodifiableList(Arrays.asList(
            reward(ELF, 41024, 1, ITEM, 31022, 10),
            reward(ELF, 41030, 1, ITEM, 21024, 1000),
            reward(DIAMONDS, 0, 5000, ITEM, 34003, 5),
            reward(DRESS, 40009, 1, ITEM, 22019, 100),
            reward(ELF, 41001, 1, ITEM, 21025, 5),
            reward(ITEM, 32002, 1, ITEM, 20003, 100),
            reward(ELF, 43020, 1, DIAMONDS, 0, 10000)
    ));
    private final ActivityService activity;
    private final RoleService roles;
    private final LoginActivityMapper mapper;
    private final InventoryService inventory;
    private final ElfService elves;

    /**
     * 绑定周期规则与角色资产服务
     *
     * @param activity 活动日历
     * @param roles 角色存档服务
     * @param mapper 登录活动数据访问
     * @param inventory 角色背包和钻石服务
     * @param elves 角色精灵服务
     */
    LoginActivityService(ActivityService activity, RoleService roles, LoginActivityMapper mapper,
                         InventoryService inventory, ElfService elves) {
        this.activity = activity;
        this.roles = roles;
        this.mapper = mapper;
        this.inventory = inventory;
        this.elves = elves;
    }

    /**
     * 返回客户端按天展示的原版奖励
     *
     * @return 奖励行，列表索引从零开始
     */
    public List<List<Integer>> rewards() {
        return REWARDS;
    }

    /**
     * 登记角色本日到访并读取本期领取记录
     *
     * @param roleId 当前认证角色
     * @return 七日登录状态
     */
    @Transactional
    public LoginActivityState visit(String roleId) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = activity.cycle(ACTIVITY_ID);
        if (now < period.start) {
            return new LoginActivityState(0, Collections.emptyList(), period.start, period.start);
        }
        mapper.registerDay(roleId, period.start, period.day);
        long next = Math.min(period.end, period.start + period.day * DAY_SECONDS);
        return new LoginActivityState(mapper.countDays(roleId, period.start),
                mapper.findClaims(roleId, period.start), period.start, next);
    }

    /**
     * 一次性领取已达成的奖励，失败时不改变资产
     *
     * @param roleId 当前认证角色
     * @param expectedPeriodStart 客户端已同步的周期起点
     * @param index 原协议中的奖励序号，从一开始
     * @return 是否完成本次奖励发放
     */
    @Transactional
    public boolean claim(String roleId, long expectedPeriodStart, long index) {
        if (index < 1 || index > REWARDS.size()) {
            return false;
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = activity.cycle(ACTIVITY_ID);
        if (expectedPeriodStart != period.start || now < period.start
                || mapper.countDays(roleId, period.start) < index
                || mapper.findClaims(roleId, period.start).contains((int) index)) {
            return false;
        }

        List<Integer> reward = REWARDS.get((int) index - 1);
        String receipt = "seven-day:" + period.start + ":" + index;
        // 每一档可能同时有两份道具，批次回执必须按奖品位拆开，否则第二份会被幂等检查吞掉。
        grant(roleId, reward.get(0), reward.get(1), reward.get(2), receipt + ":a");
        grant(roleId, reward.get(3), reward.get(4), reward.get(5), receipt + ":b");
        mapper.insertClaim(roleId, period.start, (int) index);
        return true;
    }

    /**
     * 读取活动已授予的永久时装
     *
     * @param roleId 当前认证角色
     * @return 时装配置编号
     */
    public List<Integer> dresses(String roleId) {
        return mapper.dresses(roleId);
    }

    /**
     * 在角色奖励事务内增加一件永久时装
     *
     * @param roleId 当前已加锁的角色
     * @param dressId 底包时装配置编号
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantDress(String roleId, int dressId) {
        mapper.grantDress(roleId, dressId);
    }

    /**
     * 按原版奖品类型写入角色资产
     *
     * @param roleId 当前认证角色
     * @param type 奖品类型
     * @param id 奖品配置编号
     * @param quantity 奖品数量
     * @param receipt 本轮对应日档的唯一编号
     */
    private void grant(String roleId, int type, int id, int quantity, String receipt) {
        switch (type) {
            case ITEM:
                inventory.grantItemsOrMail(roleId, receipt, Collections.singletonMap(id, (long) quantity));
                break;
            case ELF:
                if (elves.list(roleId).size() < inventory.elfCapacity(roleId)) {
                    elves.grant(roleId, id);
                } else {
                    long now = Instant.now().getEpochSecond();
                    inventory.createRewardMail(roleId, java.util.UUID.nameUUIDFromBytes(
                            (roleId + ":" + receipt + ":elf").getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString(),
                            "七日登录奖励", "精灵背包已满，腾出位置后可领取。", now, now + 30L * DAY_SECONDS,
                            Arrays.asList(ELF, id, quantity));
                }
                break;
            case DRESS:
                mapper.grantDress(roleId, id);
                break;
            case DIAMONDS:
                inventory.grant(roleId, DIAMOND_ITEM_ID, quantity);
                break;
            default:
                throw new IllegalArgumentException("未知的七日奖品类型 " + type);
        }
    }

    /**
     * 保存一条不可变的奖品配置
     *
     * @param fields 奖品类型、编号和数量
     * @return 原客户端奖励行
     */
    private static List<Integer> reward(Integer... fields) {
        return Collections.unmodifiableList(Arrays.asList(fields));
    }
}
