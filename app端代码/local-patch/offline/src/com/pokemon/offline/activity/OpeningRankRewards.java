package com.pokemon.offline.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.elf.ElfGearService;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.ElfTrainingCatalog;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleBattlePowerService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 开服等级榜、战力榜、充值榜与扭蛋榜的周期奖励结算
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:40
 */
@Service
public class OpeningRankRewards {
    /** 扭蛋排行榜的原运营活动编号。 */
    public static final int TWISTED_EGG_EVENT = 10081;
    /** 原跨服充值榜在单机版使用的独立活动编号。 */
    public static final int LOCAL_RECHARGE_EVENT = 10068;
    /** 春节狂欢上榜及前三名的原版积分门槛。 */
    public static final int CHRISTMAS_RANK_MIN = 2500;
    public static final int CHRISTMAS_TOP_THREE = 5000;
    /** 培养榜底包携带品品质 3 至 6 对应的积分。 */
    public static final List<Integer> CARRY_POINTS = Collections.unmodifiableList(Arrays.asList(1, 3, 5, 15));
    private static final long MAIL_LIFETIME = Duration.ofDays(30).getSeconds();
    private final ActivityService activities;
    private final ActivityMapper activityMapper;
    private final OpeningRankMapper cursor;
    private final RoleService roles;
    private final RoleBattlePowerService power;
    private final InventoryService inventory;
    private final ElfService elfs;
    private final ElfGearService gears;
    private final ChristmasTreeService christmas;

    /**
     * 绑定活动周期、角色分数和系统邮件
     *
     * @param activities 运营活动日历及榜单账号资格
     * @param activityMapper 已确认模拟订单查询
     * @param cursor 榜单周期结算进度
     * @param roles 角色行锁及等级存档
     * @param power 角色实际第一队战力
     * @param inventory 系统邮件与附件写入
     * @param elfs 精灵培养状态
     * @param gears 精灵携带品状态
     * @param christmas 春节狂欢参与积分
     */
    OpeningRankRewards(ActivityService activities, ActivityMapper activityMapper, OpeningRankMapper cursor,
                       RoleService roles, RoleBattlePowerService power, InventoryService inventory, ElfService elfs,
                       ElfGearService gears, ChristmasTreeService christmas) {
        this.activities = activities;
        this.activityMapper = activityMapper;
        this.cursor = cursor;
        this.roles = roles;
        this.power = power;
        this.inventory = inventory;
        this.elfs = elfs;
        this.gears = gears;
        this.christmas = christmas;
    }

    /**
     * 查询底包上榜门槛
     *
     * @param activityId 原版运营活动编号
     * @return 充值以分计，其他榜按原值
     */
    public long threshold(int activityId) {
        return Event.forId(activityId).threshold;
    }

    /**
     * 查询底包榜首奖励
     *
     * @param activityId 原版运营活动编号
     * @return 类型、编号、数量循环排列的原始列表
     */
    public List<Integer> rewards(int activityId) {
        return Event.forId(activityId).reward;
    }

    /**
     * 应用春节狂欢的前三名积分门槛，合格的单人记为第一或第四。
     *
     * @param activityId 原活动编号
     * @param score 当前活动得分
     * @return 实际排名，未达到门槛返回零
     */
    public int rank(int activityId, long score) {
        if (score < threshold(activityId)) {
            return 0;
        }
        return activityId == ChristmasTreeService.ACTIVITY && score < CHRISTMAS_TOP_THREE ? 4 : 1;
    }

    /**
     * 查询单人实际名次对应的底包奖励。
     *
     * @param activityId 原活动编号
     * @param rank 当前名次
     * @return 底包奖励三元组
     */
    public List<Integer> rewardsAtRank(int activityId, int rank) {
        return activityId == ChristmasTreeService.ACTIVITY && rank == 4 ? Arrays.asList(1, 20006, 4)
                : rewards(activityId);
    }

    /**
     * 查询当前周期内用于排行的高级扭蛋次数。
     *
     * @param roleId 角色编号
     * @param period 当前运营活动周期
     * @return 由已结算抽奖流水计算的次数
     */
    public long twistedEggScore(String roleId, ActivityPeriod period) {
        return activityMapper.advancedDrawSum(roleId, period.start, period.end);
    }

    /**
     * 判断是否为当前已接入的精灵培养活动榜。
     *
     * @param id 原运营活动编号
     * @return 是否支持培养榜查询
     */
    public boolean isTrainingEvent(long id) {
        return id >= Event.INTIMACY.id && id <= Event.CARRY.id;
    }

    /**
     * 计算当前角色在活动榜的有效分数。
     *
     * @param role 参与榜单角色
     * @param period 活动统计窗口
     * @return 本榜的分数
     */
    public long currentScore(LocalRole role, ActivityPeriod period) {
        return score(role, Event.forId(period.id), period.start, period.end);
    }

    /**
     * 处理该角色尚未结算的榜单窗口，并返回下一次到期时间
     *
     * @param roleId 当前角色编号
     * @return 下一次需要检查的 Unix 秒
     */
    @Transactional
    public long settle(String roleId) {
        if (!activityMapper.isRankedRole(roleId)) {
            return Long.MAX_VALUE;
        }
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        long next = Long.MAX_VALUE;
        for (Event event : Event.values()) {
            ActivityPeriod current = activities.cycleAt(event.id, now);
            // 下线活动的窗口起止相同，没有可结算的周期，也不应推进旧存档的游标。
            if (current.end <= current.start) {
                continue;
            }
            Optional<Long> saved = cursor.find(roleId, event.id);
            long firstEnd = saved.orElse(current.end);
            long end = firstEnd;
            long length = current.end - current.start;
            while (end <= now) {
                int rank = role.createdAt < end ? rank(event.id, score(role, event, end - length, end)) : 0;
                if (rank > 0) {
                    String id = "rank-" + event.id + "-" + end + "-" + roleId;
                    inventory.createRewardMail(roleId, id, event.subject, "你获得本期排行榜第 " + rank + " 名。",
                            now, now + MAIL_LIFETIME, rewardsAtRank(event.id, rank));
                }
                end += length;
            }
            if (end != firstEnd || !saved.isPresent()) {
                cursor.save(roleId, event.id, end);
            }
            next = Math.min(next, end);
        }
        return next;
    }

    /**
     * 计算该榜结束时保存的角色分数或截至关闭时的实际订单金额
     *
     * @param role 角色锁下读取的上场前存档状态
     * @param event 活动规则
     * @param start 待结算周期起点
     * @param end 待结算周期截止点
     * @return 榜单使用的分数
     */
    private long score(LocalRole role, Event event, long start, long end) {
        switch (event) {
            case LEVEL:
                return role.level;
            case BATTLE_POWER:
                return power.score(role.id);
            case RECHARGE:
            case LOCAL_RECHARGE:
                return activityMapper.rechargeSum(role.id, start * 1000, end * 1000);
            case TWISTED_EGG:
                return activityMapper.advancedDrawSum(role.id, start, end);
            case INTIMACY:
            case STAR:
            case INDIVIDUAL:
                return elfs.list(role.id).stream().filter(elf -> elf.team > 0 && elf.slot > 0)
                        .mapToLong(elf -> trainingScore(elf, event)).sum();
            case CARRY:
                return carryScore(role.id);
            case CHRISTMAS:
                return christmas.score(role.id, start);
            default:
                throw new IllegalArgumentException("无效榜单 " + event);
        }
    }

    /**
     * 按蓝、紫、橙、红的底包系数汇总上阵精灵的携带品积分。
     *
     * @param roleId 本地角色编号
     * @return 上阵精灵实际已穿戴携带品的合计积分
     */
    private long carryScore(String roleId) {
        Set<String> lineup = elfs.list(roleId).stream().filter(elf -> elf.team > 0 && elf.slot > 0)
                .map(elf -> elf.id).collect(Collectors.toSet());
        return gears.snapshot(roleId).stream()
                .filter(gear -> gear.kind == ElfGearService.CARRY && lineup.contains(gear.elfId))
                .mapToInt(gear -> {
                    int index = ElfTrainingCatalog.row("portable", gear.configId).path("quality").asInt() - 3;
                    return index >= 0 && index < CARRY_POINTS.size() ? CARRY_POINTS.get(index) : 0;
                }).sum();
    }

    /**
     * 用单只精灵的真实养成状态计算对应榜的分值。
     *
     * @param elf 当前精灵
     * @param event 培养榜类型
     * @return 单只精灵的亲密度、星级或六项个体值总和
     */
    private int trainingScore(LocalElf elf, Event event) {
        if (event == Event.INTIMACY) {
            return elf.training.path("love").asInt(1);
        }
        if (event == Event.STAR) {
            return elf.training.path("star").asInt(1);
        }
        int total = 0;
        for (JsonNode attribute : elf.training.path("individual")) {
            total += attribute.asInt(0);
        }
        return total;
    }

    /**
     * 原版排行门槛和各活动第一名的奖品
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:40
     */
    private enum Event {
        LEVEL(10003, 50, "等级冲刺榜奖励", 2, 64094, 1, 1, 34031, 100),
        BATTLE_POWER(10015, 80000, "战力榜奖励", 2, 64114, 1, 1, 34408, 1, 1, 20003, 300),
        RECHARGE(10018, 7000000, "充值榜奖励", 1, 21057, 2, 1, 20003, 30),
        LOCAL_RECHARGE(LOCAL_RECHARGE_EVENT, 5000, "本地充值排行奖励", 1, 21059, 1, 1, 21069, 1, 1, 34137, 1),
        TWISTED_EGG(TWISTED_EGG_EVENT, 200, "扭蛋排行榜奖励", 2, 64114, 1, 1, 34408, 1, 1, 20003, 300),
        INTIMACY(10063, 50, "亲密度排行榜奖励", 2, 63007, 1),
        STAR(10064, 6, "升星排行榜奖励", 2, 63007, 1),
        INDIVIDUAL(10065, 2500, "个体值排行榜奖励", 2, 63007, 1),
        CARRY(10066, 20, "携带品排行榜奖励", 2, 63007, 1),
        CHRISTMAS(ChristmasTreeService.ACTIVITY, CHRISTMAS_RANK_MIN, "春节狂欢排行榜奖励", 1, 20003, 1);

        private final int id;
        private final long threshold;
        private final String subject;
        private final List<Integer> reward;

        /**
         * 保存可验证的榜首奖励配置
         *
         * @param id 活动编号
         * @param threshold 排名最低分数
         * @param subject 系统邮件主题
         * @param reward 原版第一名三元组奖励
         */
        Event(int id, long threshold, String subject, Integer... reward) {
            this.id = id;
            this.threshold = threshold;
            this.subject = subject;
            this.reward = Collections.unmodifiableList(Arrays.asList(reward));
        }

        /**
         * 按运营活动编号查找规则
         *
         * @param activityId 原版活动编号
         * @return 对应榜单规则
         */
        static Event forId(int activityId) {
            for (Event event : values()) {
                if (event.id == activityId) {
                    return event;
                }
            }
            throw new IllegalArgumentException("未知榜单 " + activityId);
        }
    }
}
