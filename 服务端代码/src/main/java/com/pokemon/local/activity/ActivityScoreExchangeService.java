package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.gacha.GachaService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 按本地真实扭蛋支出和已确认订单结算神兽降临、超级返利。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:08
 */
@Service
public class ActivityScoreExchangeService {
    public static final int KYLIN = 10025;
    public static final int REBATE = 10026;
    private static final int NOT_OPEN = 100013;
    private static final int ALREADY_RECEIVED = 100011;
    private static final int NOT_QUALIFIED = 100012;
    private static final int NO_SPACE = 100015;
    private static final int INVALID_INDEX = 100021;
    private static final int SIGN_ACTION_START = 100;
    private static final int ELF = 2;

    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfService elfs;
    private final Rules rules;

    /**
     * 绑定周期、角色与真实资产，并读取随包积分档位。
     *
     * @param calendar 活动周期服务
     * @param mapper 活动行为与真实计分流水
     * @param roles 角色锁
     * @param inventory 本地道具背包
     * @param elfs 本地精灵背包
     * @param json 配置读取器
     * @throws IOException 配置无法读取
     */
    ActivityScoreExchangeService(ActivityService calendar, ActivityMapper mapper, RoleService roles,
                                 InventoryService inventory, ElfService elfs, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
        this.elfs = elfs;
        try (InputStream input = new ClassPathResource("activity/score-exchange.json").getInputStream()) {
            rules = json.readValue(input, Rules.class);
        }
    }

    /**
     * 返回积分、限额、签到和本期展示精灵。
     *
     * @param roleId 当前角色编号
     * @param period 当前查询周期
     * @return 原兑换界面的数据
     */
    public Map<String, Object> status(String roleId, ActivityPeriod period) {
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        List<Map<String, Object>> list = new ArrayList<>();
        List<Offer> offers = offers(period.id);
        int exhibition = period.id == KYLIN ? featuredElf(period) : 0;
        for (int index = 0; index < offers.size(); index++) {
            Offer offer = offers.get(index);
            Map<String, Object> cell = new LinkedHashMap<>();
            cell.put("times", offer.limit);
            cell.put("need", offer.cost);
            cell.put("itype", offer.reward[0]);
            cell.put("itemID", period.id == KYLIN && offer.reward[0] == ELF ? exhibition : offer.reward[1]);
            cell.put("itemNum", offer.reward[2]);
            cell.put("count", actions.getOrDefault(index + 1, 0));
            cell.put("needvip", 0);
            list.add(cell);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("errno", 0);
        view.put("list", list);
        view.put("score", available(roleId, period, actions));
        if (period.id == KYLIN) {
            view.put("normal", 0);
            view.put("expert", GachaService.ADVANCED_COST / rules.gachaDiamondsPerPoint);
            view.put("exhibition", exhibition);
        } else {
            view.put("payScore", rules.rebatePointsPerYuan);
            view.put("signScore", rules.rebateSignPoints);
            view.put("status", actions.containsKey(SIGN_ACTION_START + period.day));
        }
        return view;
    }

    /**
     * 记录本日超级返利签到并返回新的可兑余额。
     *
     * @param roleId 当前角色编号
     * @param viewed 打开活动页面所看到的周期及日序
     * @return 实际签到结果
     */
    @Transactional
    public Result sign(String roleId, ActivityPeriod viewed) {
        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(REBATE);
        if (viewed == null || viewed.start != period.start || viewed.day != period.day) {
            return failure(NOT_OPEN);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        int action = SIGN_ACTION_START + period.day;
        if (actions.containsKey(action)) {
            return failure(ALREADY_RECEIVED);
        }
        mapper.recordOperatingAction(roleId, period, action, "sign");
        actions.put(action, 1);
        return new Result(0, available(roleId, period, actions), 0, 0, null);
    }

    /**
     * 在同一角色事务中核对积分和次数、发放奖励并保存扣分凭据。
     *
     * @param roleId 当前角色编号
     * @param activityId 原运营活动编号
     * @param viewed 当前连接展示的周期
     * @param index 客户端使用的一基档位索引
     * @param requestKey 连接及请求号组成的防重键
     * @return 实际发放的精灵或道具
     */
    @Transactional
    public Result redeem(String roleId, int activityId, ActivityPeriod viewed, long index, String requestKey) {
        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(activityId);
        if (viewed == null || viewed.start != period.start) {
            return failure(NOT_OPEN);
        }
        List<Offer> offers = offers(activityId);
        if (index < 1 || index > offers.size()) {
            return failure(INVALID_INDEX);
        }
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        Offer offer = offers.get((int) index - 1);
        if (mapper.hasOperatingRequest(roleId, activityId, requestKey)
                || offer.limit > 0 && actions.getOrDefault((int) index, 0) >= offer.limit) {
            return failure(ALREADY_RECEIVED);
        }
        long score = available(roleId, period, actions);
        if (score < offer.cost) {
            return failure(NOT_QUALIFIED);
        }
        int quantity = offer.reward[2];
        boolean elfReward = offer.reward[0] == ELF;
        int id = elfReward && activityId == KYLIN ? featuredElf(period) : offer.reward[1];
        if (elfReward && elfs.list(roleId).size() >= inventory.elfCapacity(roleId)
                || !elfReward && !inventory.canReceive(roleId, Collections.singletonMap(id, (long) quantity))) {
            return failure(NO_SPACE);
        }
        LocalElf elf = null;
        if (elfReward) {
            elf = elfs.grant(roleId, id);
        } else {
            inventory.grant(roleId, id, quantity);
        }
        mapper.recordOperatingAction(roleId, period, (int) index, requestKey);
        return new Result(0, score - offer.cost, id, quantity, elf);
    }

    /**
     * 查询当前是否可以签到或兑换至少一档。
     *
     * @param roleId 当前角色编号
     * @param activityId 原活动编号
     * @return 存在可操作档位时为真
     */
    public boolean claimable(String roleId, int activityId) {
        ActivityPeriod period = calendar.cycle(activityId);
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        if (activityId == REBATE && !actions.containsKey(SIGN_ACTION_START + period.day)) {
            return true;
        }
        long score = available(roleId, period, actions);
        List<Offer> offers = offers(activityId);
        for (int index = 0; index < offers.size(); index++) {
            Offer offer = offers.get(index);
            if (score >= offer.cost && (offer.limit == 0 || actions.getOrDefault(index + 1, 0) < offer.limit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 从权威流水求本轮赚取积分，减去已结算兑换的积分。
     *
     * @param roleId 当前角色编号
     * @param period 周期窗口
     * @param actions 本轮行为次数
     * @return 当前可兑积分
     */
    private long available(String roleId, ActivityPeriod period, Map<Integer, Integer> actions) {
        long earned;
        if (period.id == KYLIN) {
            earned = mapper.gachaDiamonds(roleId, period) / rules.gachaDiamondsPerPoint;
        } else {
            long cents = mapper.rechargeSum(roleId, period.start * 1000, period.end * 1000);
            long signs = actions.keySet().stream().filter(action -> action > SIGN_ACTION_START).count();
            earned = cents / 100 * rules.rebatePointsPerYuan + signs * rules.rebateSignPoints;
        }
        List<Offer> offers = offers(period.id);
        for (int index = 0; index < offers.size(); index++) {
            earned -= (long) offers.get(index).cost * actions.getOrDefault(index + 1, 0);
        }
        return Math.max(0, earned);
    }

    /**
     * 按已写入本地档案的首轮日期轮换展示精灵。
     *
     * @param period 当前轮次
     * @return 精灵模板编号
     */
    private int featuredElf(ActivityPeriod period) {
        long anchor = mapper.findAnchor().orElseThrow(IllegalStateException::new);
        long round = (period.start - anchor) / (period.end - period.start);
        return rules.rotatingElfs[(int) (round % rules.rotatingElfs.length)];
    }

    /**
     * 读取对应活动的档位。
     *
     * @param activityId 原活动编号
     * @return 按原页面下标排列的档位
     */
    private List<Offer> offers(int activityId) {
        return activityId == KYLIN ? rules.kylin : rules.rebate;
    }

    /**
     * 创建无资产变更的结算结果。
     *
     * @param code 原协议错误码
     * @return 拒绝结果
     */
    private static Result failure(int code) {
        return new Result(code, 0, 0, 0, null);
    }

    /**
     * 积分兑换与签到的结算结果。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:08
     */
    public static final class Result {
        public final int errno;
        public final long score;
        public final int item;
        public final int quantity;
        public final LocalElf elf;

        /**
         * 保存已提交的结算信息。
         *
         * @param errno 客户端错误码
         * @param score 本轮可兑余额
         * @param item 奖励配置编号
         * @param quantity 发放数量
         * @param elf 新入库精灵
         */
        private Result(int errno, long score, int item, int quantity, LocalElf elf) {
            this.errno = errno;
            this.score = score;
            this.item = item;
            this.quantity = quantity;
            this.elf = elf;
        }
    }

    /**
     * 积分兑换活动的随包配置。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:08
     */
    private static final class Rules {
        public int gachaDiamondsPerPoint;
        public int[] rotatingElfs;
        public int rebatePointsPerYuan;
        public int rebateSignPoints;
        public List<Offer> kylin;
        public List<Offer> rebate;
    }

    /**
     * 一个兑换档位及原客户端识别的奖励三元组。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:08
     */
    private static final class Offer {
        public int cost;
        public int limit;
        public int[] reward;
    }
}
