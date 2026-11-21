package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 限时神兽抽取、积分宝箱、本地排行与展示期结算。
 *
 * @author tongruiyuan
 * @since 2026-11-19 18:23
 */
@Service
public class LimitedBeastService {
    public static final int ACTIVITY = 10029;
    private static final int RANK_MAIL = -100;
    private static final long DAY = 86400;
    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final InventoryService inventory;
    private final RoleService roles;
    private final JdbcTemplate jdbc;
    private final Rules rules;

    /**
     * 加载与客户端匹配的原表并核查奖池总权重。
     *
     * @param calendar 活动时钟
     * @param mapper 活动动作凭据
     * @param inventory 道具、精灵碎片及邮件
     * @param roles 角色事务锁
     * @param jdbc 轮末结算查询
     * @param json 原表读取器
     * @throws IOException 配置读取失败
     */
    LimitedBeastService(ActivityService calendar, ActivityMapper mapper, InventoryService inventory,
                        RoleService roles, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.mapper = mapper;
        this.inventory = inventory;
        this.roles = roles;
        this.jdbc = jdbc;
        try (InputStream input = new ClassPathResource("activity/limited-beast.json").getInputStream()) {
            rules = json.readValue(input, Rules.class);
        }
        int weight = 0;
        for (int i = 0; i < rules.box.size(); i += 5) {
            weight += rules.box.get(i + 3);
        }
        if (weight != 10000 || rules.integralrew.size() % 4 != 0 || rules.paimin.isEmpty()) {
            throw new IllegalArgumentException("限时神兽配置的权重、积分档或名次错误");
        }
    }

    /**
     * 提供原页面需要的奖池、积分档、排行奖与角色进度。
     *
     * @param roleId 当前角色
     * @param period 玩家当前查看的周期
     * @return 原限时神兽查询应答
     */
    public Map<String, Object> status(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return fields("errno", 100013);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        List<Integer> record = new ArrayList<>();
        for (int i = 1; i <= rules.integralrew.size() / 4; i++) {
            if (actions.containsKey(-i)) {
                record.add(i);
            }
        }
        List<Map<String, Object>> ranks = new ArrayList<>();
        for (List<Integer> rewards : rules.paimin) {
            ranks.add(fields("info", rewards));
        }
        Map<String, Object> info = fields("jifen", rules.jifen, "paimin", ranks, "cost1", rules.cost1,
                "cost2", rules.cost2, "integralmin", rules.integralmin, "integralrew", rules.integralrew,
                "box", rules.box, "show", rules.show);
        return fields("errno", 0, "info", info, "myscore", score(actions), "record", record);
    }

    /**
     * 按已完成单抽和十连动作计算本期积分。
     *
     * @param actions 本期动作次数
     * @return 已持久化积分
     */
    private long score(Map<Integer, Integer> actions) {
        return (actions.getOrDefault(1, 0) + 10L * actions.getOrDefault(10, 0)) * rules.jifen;
    }

    /**
     * 按原万分权重抽取一条奖励三元组。
     *
     * @return 本次抽取的种类、模板、数量
     */
    private List<Integer> drawReward() {
        int random = ThreadLocalRandom.current().nextInt(10000);
        for (int i = 0; i < rules.box.size(); i += 5) {
            random -= rules.box.get(i + 3);
            if (random < 0) {
                return rules.box.subList(i, i + 3);
            }
        }
        throw new IllegalStateException("限时神兽奖池权重不足");
    }

    /**
     * 在参与期用真实钻石单抽或十连，并将奖励和积分同事务提交。
     *
     * @param roleId 当前角色
     * @param viewed 客户端已展示的周期起点
     * @param ten 是否使用十连价格
     * @param request 当前协议会话防重编号
     * @return 实际奖励与抽后积分
     */
    @Transactional
    public Result draw(String roleId, long viewed, boolean ten, String request) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (viewed != period.start || now < period.start || now >= period.end) {
            return Result.error(100013);
        }
        if (mapper.hasOperatingRequest(roleId, ACTIVITY, request)) {
            return Result.error(100011);
        }
        int count = ten ? 10 : 1;
        int cost = ten ? rules.cost2 : rules.cost1;
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, cost)) {
            return Result.error(100012);
        }
        List<Integer> rewards = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            rewards.addAll(drawReward());
        }
        Result delivered = grant(roleId, "limitdraw:" + request, rewards);
        mapper.recordOperatingAction(roleId, period, count, request);
        delivered.response.put("list", rewards);
        delivered.response.put("myscore", score(mapper.operatingActions(roleId, period)));
        return delivered;
    }

    /**
     * 领取本期已达标的一个积分档，结果展示日仍可领取。
     *
     * @param roleId 当前角色
     * @param viewed 面板周期起点
     * @param index 从一开始的原积分宝箱编号
     * @return 实际发货结果
     */
    @Transactional
    public Result claim(String roleId, long viewed, long index) {
        if (index < 1 || index > rules.integralrew.size() / 4) {
            return Result.error(100021);
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (viewed != period.start || now < period.start) {
            return Result.error(100013);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int action = -(int) index;
        if (actions.containsKey(action)) {
            return Result.error(100011);
        }
        int offset = ((int) index - 1) * 4;
        if (score(actions) < rules.integralrew.get(offset)) {
            return Result.error(100012);
        }
        Result result = grant(roleId, "limitclaim:" + viewed + ":" + index, rules.integralrew.subList(offset + 1, offset + 4));
        mapper.recordOperatingAction(roleId, period, action, "claim");
        result.response.put("idx", index);
        return result;
    }

    /**
     * 合并同次抽取的奖励，道具满包转入邮件，精灵碎片进入专属账户。
     *
     * @param roleId 当前角色
     * @param receipt 本次奖励的唯一凭证
     * @param triples 原表逐件奖励三元组
     * @return 直接到账及转入邮件的结果
     */
    private Result grant(String roleId, String receipt, List<Integer> triples) {
        Map<Integer, Long> items = new LinkedHashMap<>();
        Map<Integer, Long> chips = new LinkedHashMap<>();
        for (int i = 0; i < triples.size(); i += 3) {
            int type = triples.get(i);
            Map<Integer, Long> target = type == 3 ? chips : items;
            if (type != 1 && type != 3) {
                throw new IllegalStateException("限时神兽未知奖励类型 " + type);
            }
            target.merge(triples.get(i + 1), (long) triples.get(i + 2), Long::sum);
        }
        chips.forEach((id, amount) -> inventory.grantChip(roleId, id, amount));
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId, receipt, items);
        return new Result(fields("errno", 0), delivered, chips, !items.equals(delivered));
    }

    /**
     * 读取仅含达到真实上榜门槛的本地榜单。
     *
     * @param role 登录角色
     * @param period 当前轮次
     * @return 当前榜单及本人的名次
     */
    public Map<String, Object> rank(LocalRole role, ActivityPeriod period) {
        long score = score(mapper.operatingActions(role.id, period));
        boolean ranked = mapper.isRankedRole(role.id) && score >= rules.integralmin;
        List<Map<String, Object>> rows = ranked ? Collections.singletonList(fields("uid", role.id, "name", role.name,
                "sex", role.sex, "score", score, "rank", 1, "icon", role.avatar, "svrid", role.realmId))
                : Collections.emptyList();
        return fields("errno", 0, "ranklist", rows, "myrank", ranked ? 1 : 0);
    }

    /**
     * 首次进入榜单展示期或错过整轮后补发达到门槛的榜首邮件。
     *
     * @param roleId 当前角色
     * @return 新发放的邮件数
     */
    @Transactional
    public int settle(String roleId) {
        if (!mapper.isRankedRole(roleId)) {
            return 0;
        }
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod current = calendar.cycleAt(ACTIVITY, now);
        long lastClosedStart = now >= current.end ? current.start : current.start - (current.showUntil - current.start);
        List<Long> periods = jdbc.query("SELECT period_start FROM local_operating_activity_action "
                        + "WHERE role_id=? AND activity_id=? AND period_start<=? GROUP BY period_start HAVING "
                        + "SUM(CASE WHEN action_index IN(1,10) THEN action_index ELSE 0 END)>=? "
                        + "AND MIN(action_index)>?", (row, n) -> row.getLong(1), roleId, ACTIVITY, lastClosedStart,
                (rules.integralmin + rules.jifen - 1) / rules.jifen, RANK_MAIL);
        for (long start : periods) {
            List<Integer> first = rules.paimin.get(0);
            inventory.createRewardMail(roleId, "limit-rank:" + start + ":" + roleId, "限时神兽排行奖励",
                    "本期积分达到上榜条件，获得第 1 名奖励。", now, now + 30 * DAY, first.subList(1, first.size()));
            mapper.recordOperatingAction(roleId, calendar.cycleAt(ACTIVITY, start), RANK_MAIL, "rank");
        }
        return periods.size();
    }

    /**
     * 构造协议字段。
     *
     * @param pairs 字段名及对应值
     * @return 保留原字段名称的映射
     */
    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    /**
     * 原限时神兽表随包字段。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:23
     */
    public static final class Rules {
        /** 主界面展示的精灵模板。 */
        public int show;
        /** 单抽钻石成本。 */
        public int cost1;
        /** 十连钻石成本。 */
        public int cost2;
        /** 每次抽取得分。 */
        public int jifen;
        /** 上榜所需真实积分。 */
        public int integralmin;
        /** 奖池五元组。 */
        public List<Integer> box;
        /** 积分宝箱四元组。 */
        public List<Integer> integralrew;
        /** 各名次及其奖励三元组。 */
        public List<List<Integer>> paimin;
    }

    /**
     * 原应答与真实入包、碎片和邮件变更。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:23
     */
    public static final class Result {
        public final Map<String, Object> response;
        public final Map<Integer, Long> items;
        public final Map<Integer, Long> chips;
        public final boolean mailed;

        /**
         * 保存本次动作提交结果。
         *
         * @param response 协议回复
         * @param items 直接入包的道具
         * @param chips 已入账的精灵碎片
         * @param mailed 是否有背包溢出到信件
         */
        private Result(Map<String, Object> response, Map<Integer, Long> items, Map<Integer, Long> chips, boolean mailed) {
            this.response = response;
            this.items = items;
            this.chips = chips;
            this.mailed = mailed;
        }

        /**
         * 构造无资产变更的拒绝应答。
         *
         * @param errno 原协议错误码
         * @return 拒绝结果
         */
        private static Result error(int errno) {
            return new Result(fields("errno", errno), Collections.emptyMap(), Collections.emptyMap(), false);
        }
    }
}
