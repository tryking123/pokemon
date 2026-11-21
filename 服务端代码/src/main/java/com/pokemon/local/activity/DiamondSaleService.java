package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超级钻石抢购的单机开团、下架积分结转和兑换业务。
 * @author tongruiyuan
 * @since 2026-11-19 19:21
 */
@Service
public class DiamondSaleService {
    private static final int ACTIVITY = 10057;
    private final JsonNode rules;
    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final InventoryService inventory;
    private final RoleService roles;
    private final ElfService elves;
    private final JdbcTemplate jdbc;

    /**
     * 读取底包商品和离线场次、商品限额。
     * @param calendar 活动周期
     * @param mapper 原请求回执
     * @param inventory 角色资产
     * @param roles 角色锁
     * @param elves 精灵发放
     * @param jdbc 抢购进度
     * @param json 配置解析器
     * @throws IOException 读取失败
     */
    DiamondSaleService(ActivityService calendar, ActivityMapper mapper, InventoryService inventory,
                       RoleService roles, ElfService elves, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.mapper = mapper;
        this.inventory = inventory;
        this.roles = roles;
        this.elves = elves;
        this.jdbc = jdbc;
        try (InputStream stream = new ClassPathResource("activity/diamond-sale.json").getInputStream()) {
            rules = json.readTree(stream);
        }
    }

    /**
     * 读取本期活动或处理本次实际投入和兑换。
     * @param roleId 角色
     * @param protocol 原协议号
     * @param value 兑换序号或本次增加的积分
     * @param viewed 客户端展示的期次和场次
     * @param key 唯一请求回执
     * @return 活动结果与真实资产变更
     */
    @Transactional
    public Result process(String roleId, int protocol, long value, long viewed, String key) {
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (now < period.start || now >= period.end) {
            return new Result(100013);
        }
        int lotCount = rules.get("hotTemplates").size();
        int duration = rules.get("lotSeconds").asInt();
        int lot = Math.min(lotCount, (int) ((now - period.start) / duration));
        long version = period.start + (long) lot * duration;
        State state = load(roleId, period.start, lot);
        Map<Integer, Integer> actions = mapper.operatingActions(role.id, period);
        Result result = new Result(0);
        result.version = version;
        switch (protocol) {
            case 10050:
                List<Map<String, Object>> goods = new ArrayList<>();
                for (int i = 0; i < lotCount; i++) {
                    goods.add(hot(i));
                }
                result.response.put("goods", goods);
                break;
            case 10051:
                current(result.response, role, period, lot, now, state, actions);
                break;
            case 10052:
                result.response.put("shop", shop(actions));
                break;
            case 10053:
            case 10054:
                if (mapper.hasOperatingRequest(roleId, ACTIVITY, key)) {
                    return new Result(100011);
                }
                if (viewed != (protocol == 10053 ? period.start : version)) {
                    return new Result(100013);
                }
                if (role.level < rules.get("minLevel").asInt()) {
                    return new Result(100014);
                }
                if (protocol == 10053) {
                    result = redeem(role, period, state, actions, value, key);
                } else {
                    result = invest(role, period, state, value, lotCount, key, now);
                }
                break;
            default:
                throw new IllegalArgumentException("不支持的抢购协议");
        }
        result.period = period.start;
        return result;
    }

    /**
     * 按原角色投入上限开团，真实扣钻达到开团分数即交付商品。
     * @param role 角色
     * @param period 活动轮次
     * @param state 本地场次及余额
     * @param value 增加的积分
     * @param lots 热点场数
     * @param key 回执
     * @param now 当前秒
     * @return 投入和发货结果
     */
    private Result invest(LocalRole role, ActivityPeriod period, State state, long value, int lots, String key, long now) {
        int target = rules.get("target").asInt();
        if (state.lot >= lots || state.committed == target) {
            return new Result(100013);
        }
        if (value < 1 || value > Math.min(target - state.committed, rules.get("weeklyLimit").asInt() - state.spent)) {
            return new Result(100021);
        }
        if (!inventory.spendMaterials(role.id, Collections.emptyMap(), 0, value)) {
            return new Result(100012);
        }
        Result result = new Result(0);
        state.committed += (int) value;
        state.spent += (int) value;
        result.diamonds = (int) value;
        boolean won = state.committed == target;
        if (won) {
            int elf = rules.get("hotTemplates").get(state.lot).asInt();
            if (elves.list(role.id).size() < inventory.elfCapacity(role.id)) {
                result.elf = elves.grant(role.id, elf);
            } else {
                inventory.createRewardMail(role.id, UUID.randomUUID().toString(), "超级钻石抢购中奖",
                        "精灵背包已满，请在附件中领取。", now, now + 30 * 86400L, Arrays.asList(2, elf, 1));
                result.mailed = true;
            }
        }
        save(role.id, period.start, state);
        mapper.recordOperatingAction(role.id, period, won ? 100 + state.lot : -1, key);
        result.response.put("my_intgeral", won ? 0 : state.committed);
        result.response.put("all_intgeral", state.committed);
        result.response.put("my_all_intgeral", state.historical);
        result.won = won;
        return result;
    }

    /**
     * 使用下架时结转的历史积分兑换本期货架。
     * @param role 角色
     * @param period 轮次
     * @param state 当前余额
     * @param actions 本期回执数
     * @param value 一开始的兑换序号
     * @param key 唯一请求
     * @return 入包或邮件结果
     */
    private Result redeem(LocalRole role, ActivityPeriod period, State state, Map<Integer, Integer> actions,
                          long value, String key) {
        if (value < 1 || value > rules.get("shop").size()) {
            return new Result(100021);
        }
        int index = (int) value;
        JsonNode row = rules.get("shop").get(index - 1);
        if (actions.getOrDefault(index, 0) >= row.get("limit").intValue()) {
            return new Result(100011);
        }
        int cost = row.get("cost").asInt();
        if (state.historical < cost) {
            return new Result(100012);
        }
        state.historical -= cost;
        Map<Integer, Long> items = new LinkedHashMap<>();
        JsonNode goods = row.get("goods");
        for (int i = 0; i < goods.size(); i += 3) {
            items.put(goods.get(i + 1).asInt(), goods.get(i + 2).longValue());
        }
        Result result = new Result(0);
        result.items = inventory.grantItemsOrMail(role.id, "snapup:" + key, items);
        result.mailed = !items.equals(result.items);
        save(role.id, period.start, state);
        mapper.recordOperatingAction(role.id, period, index, key);
        return result;
    }

    /**
     * 转换原精灵预览商品字段。
     * @param lot 零起点场次
     * @return 原热点礼包
     */
    private Map<String, Object> hot(int lot) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("item_type", 2);
        item.put("item_id", rules.get("hotTemplates").get(lot).asInt());
        item.put("item_num", 1);
        item.put("item_part", 1);
        return item;
    }

    /**
     * 生成当前场次、倒计时和已实际发奖的记录。
     * @param out 原应答
     * @param role 本地角色
     * @param period 轮次
     * @param lot 当前场次
     * @param now 当前秒
     * @param state 进度
     * @param actions 已保存操作
     */
    private void current(Map<String, Object> out, LocalRole role, ActivityPeriod period, int lot, long now,
                         State state, Map<Integer, Integer> actions) {
        int lots = rules.get("hotTemplates").size();
        int target = rules.get("target").asInt();
        boolean won = state.committed == target;
        boolean active = lot < lots && !won && state.spent < rules.get("weeklyLimit").asInt();
        if (lot < lots) {
            out.putAll(hot(lot));
        }
        int allowance = Math.min(target, rules.get("weeklyLimit").asInt() - state.spent + state.committed);
        out.put("status", active ? 2 : 1);
        out.put("my_intgeral", won ? 0 : state.committed);
        out.put("all_intgeral", state.committed);
        out.put("my_all_intgeral", state.historical);
        out.put("open_integal", target);
        out.put("person_integal", allowance);
        long next = lot < lots ? period.start + (lot + 1L) * rules.get("lotSeconds").asInt() : period.end;
        out.put("limit_time", next - now);
        if (won) { out.put("my_hit", 1); }
        List<Map<String, Object>> winners = new ArrayList<>();
        for (int i = 0; i < lots; i++) {
            if (actions.containsKey(100 + i)) {
                Map<String, Object> record = new LinkedHashMap<>();
                record.put("item_type", 2);
                record.put("item_id", rules.get("hotTemplates").get(i).asInt());
                record.put("item_num", 1);
                record.put("name", role.name);
                record.put("serverid", role.realmId);
                record.put("intgeral", target);
                winners.add(record);
            }
        }
        out.put("lucky_data", winners);
    }

    /**
     * 序列化本期三档可兑换物品。
     * @param actions 本期实际兑换记录
     * @return 原积分商店
     */
    private List<Map<String, Object>> shop(Map<Integer, Integer> actions) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (JsonNode rule : rules.get("shop")) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("need_intgeral", rule.get("cost").asInt());
            row.put("exchange_num", rule.get("limit").asInt());
            row.put("use_num", actions.getOrDefault(result.size() + 1, 0));
            List<Integer> goods = new ArrayList<>();
            rule.get("goods").forEach(item -> goods.add(item.asInt()));
            row.put("item_info", goods);
            result.add(row);
        }
        return result;
    }

    /**
     * 换场时将未成团的实际投入一次结转为本周可兑换余额。
     * @param roleId 角色
     * @param period 周期
     * @param lot 当前场次
     * @return 本期存档
     */
    private State load(String roleId, long period, int lot) {
        List<State> loaded = jdbc.query("SELECT lot,committed,historical,spent FROM local_diamond_sale "
                + "WHERE role_id=? AND period_start=?", (r, number) ->
                new State(r.getInt(1), r.getInt(2), r.getInt(3), r.getInt(4)), roleId, period);
        if (loaded.isEmpty()) {
            return new State(lot, 0, 0, 0);
        }
        State state = loaded.get(0);
        if (state.lot < lot) {
            if (state.committed < rules.get("target").asInt()) {
                state.historical += state.committed;
            }
            state.committed = 0;
            state.lot = lot;
            save(roleId, period, state);
        }
        return state;
    }

    /**
     * 保存单周投入上限、场次与可用积分。
     * @param roleId 角色
     * @param period 周期
     * @param state 当前进度
     */
    private void save(String roleId, long period, State state) {
        jdbc.update("MERGE INTO local_diamond_sale(role_id,period_start,lot,committed,historical,spent) "
                + "KEY(role_id,period_start) VALUES(?,?,?,?,?,?)", roleId, period, state.lot, state.committed,
                state.historical, state.spent);
    }

    /**
     * 角色在一周内的参与记录。
     * @author tongruiyuan
     * @since 2026-11-19 19:21
     */
    private static final class State {
        int lot;
        int committed;
        int historical;
        int spent;

        /**
         * @param lot 当前场
         * @param committed 本场已投
         * @param historical 结转余额
         * @param spent 周累计
         */
        State(int lot, int committed, int historical, int spent) {
            this.lot = lot;
            this.committed = committed;
            this.historical = historical;
            this.spent = spent;
        }
    }

    /**
     * 面板信息、资产与中奖通知。
     * @author tongruiyuan
     * @since 2026-11-19 19:21
     */
    public static final class Result {
        public final Map<String, Object> response = new LinkedHashMap<>();
        public long period;
        public long version;
        public int diamonds;
        public boolean won;
        public boolean mailed;
        public LocalElf elf;
        public Map<Integer, Long> items = Collections.emptyMap();

        /** @param errno 原协议错误码 */
        Result(int errno) { response.put("errno", errno); }
    }
}
