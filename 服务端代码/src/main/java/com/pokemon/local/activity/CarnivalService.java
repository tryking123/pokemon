package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfGearService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.DiamondSpendMapper;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleBattlePowerService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 七日目标的原档位、周进度与单档奖励结算 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 19:38
 */
@Service
public class CarnivalService {
    public static final int ACTIVITY = 10027;
    private final ActivityService calendar;
    private final ActivityMapper activities;
    private final CarnivalProgressMapper training;
    private final InventoryService inventory;
    private final DiamondSpendMapper spending;
    private final RoleService roles;
    private final RoleBattlePowerService power;
    private final ElfService elves;
    private final ElfGearService gear;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode days;

    /**
     * 加载原版七日目标配置和各项持久进度来源。
     * @param calendar 活动日历
     * @param activities 运营动作记录
     * @param training 真实培养事件
     * @param inventory 物品和碎片发放
     * @param spending 钻石消费流水
     * @param roles 角色锁和货币
     * @param power 角色战力
     * @param elves 已拥有的精灵
     * @param gear 实际装备的携带品
     * @param jdbc 洞穴首通记录
     * @param json 档位和原协议数据
     * @throws IOException 随包配置读取失败
     */
    CarnivalService(ActivityService calendar, ActivityMapper activities, CarnivalProgressMapper training,
                    InventoryService inventory, DiamondSpendMapper spending, RoleService roles,
                    RoleBattlePowerService power, ElfService elves, ElfGearService gear, JdbcTemplate jdbc,
                    ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.activities = activities;
        this.training = training;
        this.inventory = inventory;
        this.spending = spending;
        this.roles = roles;
        this.power = power;
        this.elves = elves;
        this.gear = gear;
        this.jdbc = jdbc;
        this.json = json;
        try (InputStream stream = new ClassPathResource("activity/carnival.json").getInputStream()) {
            days = json.readTree(stream);
        }
    }

    /**
     * 汇总原任务对应的本期流水及当前角色养成，未接入的任务不出现在配置中。
     * @param roleId 当前角色
     * @param period 活动周期
     * @return 按原表类别索引的进度
     */
    private Map<Integer, Long> progress(String roleId, ActivityPeriod period) {
        Map<Integer, Long> scores = training.totals(roleId, period.start, period.end);
        List<LocalElf> owned = elves.list(roleId);
        scores.put(102, activities.advancedDrawSum(roleId, period.start, period.end));
        scores.put(104, jdbc.queryForObject("SELECT COALESCE(MAX(layer),0) FROM local_tower_clear WHERE role_id=?",
                Long.class, roleId));
        scores.put(106, owned.stream().mapToLong(elf -> elf.level).sum());
        scores.put(107, owned.stream().mapToLong(elf -> elf.training.path("love").asInt(1)).sum());
        scores.put(108, gear.snapshot(roleId).stream()
                .filter(item -> item.kind == ElfGearService.CARRY && item.elfId != null && !item.elfId.isEmpty()).count());
        scores.put(110, power.score(roleId));
        scores.put(111, owned.stream().mapToLong(elf -> elf.training.path("star").asInt(1)).sum());
        scores.put(113, activities.rechargeSum(roleId, period.start * 1000, period.end * 1000) * 10);
        scores.put(114, spending.totalBetween(roleId, period.start * 1000, period.end * 1000));
        return scores;
    }

    /**
     * 逐日下发原表中有本地进度来源的档位，保留原日序及已领状态。
     * @param roleId 当前角色
     * @param period 面板所属轮次
     * @return 原协议七日列表
     */
    public ObjectNode panel(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return json.createObjectNode().put("errno", 100013);
        }
        Map<Integer, Long> values = progress(roleId, period);
        Map<Integer, Integer> claimed = activities.operatingActions(roleId, period);
        ObjectNode response = json.createObjectNode().put("errno", 0).put("openday", period.day);
        ArrayNode list = response.putArray("list");
        for (int day = 1; day <= days.size(); day++) {
            ArrayNode rows = list.addObject().put("day", day).putArray("list");
            for (JsonNode rule : days.get(day - 1)) {
                int type = rule.get("type").asInt();
                int need = rule.get("need").asInt();
                rows.addObject().put("itype", type).put("need", need)
                        .put("now", Math.min(need, values.getOrDefault(type, 0L)))
                        .put("flag", claimed.containsKey(day * 100 + rule.get("id").asInt()) ? 1 : 0)
                        .set("award", rule.get("award"));
            }
        }
        return response;
    }

    /**
     * 按打开面板时的显示行与原档位映射结算，在同一角色事务内发放并标记。
     * @param roleId 当前角色
     * @param expected 面板轮次起点
     * @param day 选择的日序
     * @param row 当日列表一基行号
     * @return 错误码及直接到账的附件
     */
    @Transactional
    public ObjectNode claim(String roleId, long expected, long day, long row) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        ObjectNode result = json.createObjectNode();
        if (expected != period.start || now < period.start || day > period.day) {
            return result.put("errno", 100013);
        }
        if (day < 1 || day > days.size() || row < 1 || row > days.get((int) day - 1).size()) {
            return result.put("errno", 100021);
        }
        JsonNode rule = days.get((int) day - 1).get((int) row - 1);
        int key = (int) day * 100 + rule.get("id").asInt();
        if (activities.operatingActions(roleId, period).containsKey(key)) {
            return result.put("errno", 100011);
        }
        if (progress(roleId, period).getOrDefault(rule.get("type").asInt(), 0L) < rule.get("need").asLong()) {
            return result.put("errno", 100012);
        }
        ObjectNode adds = result.putObject("adds");
        ArrayNode chips = adds.putArray("elschips");
        Map<Integer, Long> items = new LinkedHashMap<>();
        JsonNode award = rule.get("award");
        for (int i = 0; i < award.size(); i += 3) {
            int type = award.get(i).asInt();
            int id = award.get(i + 1).asInt();
            int count = award.get(i + 2).asInt();
            switch (type) {
                case 1:
                    items.merge(id, (long) count, Long::sum);
                    break;
                case 3:
                    inventory.grantChip(roleId, id, count);
                    chips.addObject().put("id", id).put("n", count);
                    break;
                case 104:
                    roles.addCurrency(roleId, 0, count, 0);
                    adds.put("diamonds", count);
                    break;
                default:
                    throw new IllegalStateException("七日目标存在未配置的奖励类别");
            }
        }
        ArrayNode direct = adds.putArray("items");
        inventory.grantItemsOrMail(roleId, "carnival:" + period.start + ":" + key, items)
                .forEach((id, count) -> direct.addObject().put("id", id).put("n", count).put("type", inventory.itemType(id)));
        activities.recordOperatingAction(roleId, period, key, "claim");
        return result.put("errno", 0);
    }
}
