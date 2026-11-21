package com.pokemon.local.activitydaily;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.inventory.DiamondSpendMapper;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 北京自然日活跃统计及原版档位奖励 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:05
 */
@Service
public class DailyVitalityService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int DIAMONDS_TASK = 2;
    private static final int FAST_BATTLE_TASK = 3;
    private static final int GACHA_TASK = 8;
    private static final int ITEM = 1;
    private static final int COINS = 103;
    private static final int DIAMONDS = 104;

    private final DailyVitalityEvents events;
    private final DiamondSpendMapper spending;
    private final RoleService roles;
    private final InventoryService inventory;
    private final JsonNode tasks;
    private final JsonNode tiers;

    /**
     * 读取底包活跃任务和档位，核对所有发放类型与模板。
     *
     * @param events 活跃事件及领取存档
     * @param spending 游戏实际钻石扣费流水
     * @param roles 本地角色事务及货币服务
     * @param inventory 普通道具及溢出邮件
     * @param json 配置解析器
     * @throws IOException 无法读取奖励配置
     */
    DailyVitalityService(DailyVitalityEvents events, DiamondSpendMapper spending, RoleService roles,
                         InventoryService inventory, ObjectMapper json) throws IOException {
        this.events = events;
        this.spending = spending;
        this.roles = roles;
        this.inventory = inventory;
        try (InputStream input = new ClassPathResource("activity/daily-vitality.json").getInputStream()) {
            JsonNode config = json.readTree(input);
            tasks = config.required("tasks");
            tiers = config.required("tiers");
        }
        for (JsonNode tier : tiers) {
            JsonNode reward = tier.required("reward");
            if (tier.path("minimum").asInt() <= 0 || !reward.isArray() || reward.isEmpty() || reward.size() % 3 != 0) {
                throw new IllegalArgumentException("活跃档位奖励配置无效");
            }
            for (int index = 0; index < reward.size(); index += 3) {
                int kind = reward.get(index).asInt();
                int id = reward.get(index + 1).asInt();
                if (reward.get(index + 2).asLong() <= 0 || kind != ITEM && kind != COINS && kind != DIAMONDS) {
                    throw new IllegalArgumentException("活跃奖品类型或数量无效");
                }
                if (kind == ITEM) {
                    inventory.itemType(id);
                }
            }
        }
    }

    /**
     * 查询当前角色的今日真实活跃及已领档位。
     *
     * @param roleId 当前角色编号
     * @return 可用于原任务面板的快照
     */
    public Status snapshot(String roleId) {
        return read(roleId, LocalDate.now(ZONE));
    }

    /**
     * 按每天的原任务计分和每日上限汇总本周真实活跃。
     * @param roleId 角色编号
     * @param monday 本轮的北京时间周一
     * @return 从周一至今天的活跃总数
     */
    public long weeklyPoints(String roleId, LocalDate monday) {
        LocalDate today = LocalDate.now(ZONE);
        long total = 0;
        for (int i = 0; i < 7 && !monday.plusDays(i).isAfter(today); i++) {
            total += read(roleId, monday.plusDays(i)).points;
        }
        return total;
    }

    /**
     * 按今日活跃结算单个档位，到账、附件及已领记录同一事务提交。
     *
     * @param roleId 当前角色编号
     * @param index 原协议从一开始的奖励索引
     * @return 是否实际完成领取
     */
    @Transactional
    public boolean claim(String roleId, long index) {
        if (index < 1 || index > tiers.size()) {
            return false;
        }
        roles.lockRole(roleId);
        int tier = (int) index;
        Status status = read(roleId, LocalDate.now(ZONE));
        JsonNode config = tiers.get(tier - 1);
        if (status.points < config.path("minimum").asInt() || status.claimed.contains(tier)) {
            return false;
        }
        JsonNode reward = config.path("reward");
        long coins = 0;
        long diamonds = 0;
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int position = 0; position < reward.size(); position += 3) {
            int kind = reward.get(position).asInt();
            long number = reward.get(position + 2).asLong();
            if (kind == ITEM) {
                items.merge(reward.get(position + 1).asInt(), number, Math::addExact);
            } else if (kind == COINS) {
                coins = Math.addExact(coins, number);
            } else {
                diamonds = Math.addExact(diamonds, number);
            }
        }
        inventory.grantItemsOrMail(roleId, "vitality:" + status.dayStart + ":" + tier, items);
        if (coins != 0 || diamonds != 0) {
            roles.addCurrency(roleId, coins, diamonds, 0);
        }
        events.claim(roleId, status.dayStart, tier);
        return true;
    }

    /**
     * 从历史结算与已接入任务的原子事件累加本日活跃。
     *
     * @param roleId 当前角色编号
     * @param day 统一读取使用的北京时间自然日
     * @return 真实流水经原配置换算的积分和领奖状态
     */
    private Status read(String roleId, LocalDate day) {
        long start = day.atStartOfDay(ZONE).toEpochSecond();
        long end = day.plusDays(1).atStartOfDay(ZONE).toEpochSecond();
        long[] base=events.baseCounts(roleId,day.toString(),start,end);
        long legacy=score(DIAMONDS_TASK,spending.totalBetween(roleId,start*1000,end*1000)/tasks.path("2").path("amount").asInt(50))
                +score(FAST_BATTLE_TASK,base[0])+score(GACHA_TASK,base[1]);
        for(long[] row:events.counts(roleId,start,end)) {
            int task=(int)row[0];
            if(task!=DailyVitalityEvents.MAIL_POINTS && task!=DIAMONDS_TASK && task!=FAST_BATTLE_TASK && task!=GACHA_TASK)
                legacy+=score(task,row[1]);
        }
        long points = events.preservePoints(roleId,start,legacy);
        for (long[] row : events.counts(roleId, start, end)) {
            if (row[0] == DailyVitalityEvents.MAIL_POINTS) {
                points += row[1];
            }
        }
        return new Status(start, points, events.claims(roleId, start));
    }

    /**
     * 使用底包单次分值和次数上限计算积分。
     *
     * @param task 活跃任务编号
     * @param completed 当前自然日真实完成次数
     * @return 本任务产生的积分
     */
    private long score(int task, long completed) {
        JsonNode config = tasks.path(Integer.toString(task));
        return config.path("points").asInt() * Math.min(completed, config.path("limit").asInt());
    }

    /**
     * 每日活跃进度和对应档位记录。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:05
     */
    public static final class Status {
        private final long dayStart;
        public final long points;
        public final List<Integer> claimed;

        /**
         * 保存同一个业务日的积分和领取记录。
         *
         * @param dayStart 北京自然日零点的 Unix 秒
         * @param points 当前实际积分
         * @param claimed 已领档位
         */
        private Status(long dayStart, long points, List<Integer> claimed) {
            this.dayStart = dayStart;
            this.points = points;
            this.claimed = claimed;
        }
    }
}
