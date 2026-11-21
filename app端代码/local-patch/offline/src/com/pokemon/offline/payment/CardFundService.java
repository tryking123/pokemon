package com.pokemon.offline.payment;

import com.pokemon.offline.common.ApiException;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.pokemon.offline.platform.http.HttpStatus;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 福利卡和成长基金本地激活、奖励及领取 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:49
 */
@Service
public class CardFundService {
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");
    /** 成长基金的原版充值档位。 */
    public static final int FUND = 4;
    private static final int NOT_ELIGIBLE = 100012;
    private static final int ALREADY_CLAIMED = 100011;
    private static final int BAD_INDEX = 100021;
    private static final int[] FUND_LEVELS = {10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 70, 80};
    private static final int[] FUND_DIAMONDS = {
            20000, 20000, 20000, 20000, 20000, 25000, 25000, 30000, 30000, 35000, 35000, 45000, 100000
    };

    private final CardFundMapper mapper;
    private final RoleService roles;
    private final InventoryService inventory;

    /**
     * 访问确认订单、角色钱包和背包奖励。
     *
     * @param mapper 领取记录与确认订单访问
     * @param roles 角色余额与等级
     * @param inventory 游戏内背包
     */
    CardFundService(CardFundMapper mapper, RoleService roles, InventoryService inventory) {
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
    }

    /**
     * 确认角色当前可以激活新福利卡或基金。
     *
     * @param roleId 角色编号
     * @param paysid 原充值档位
     * @param now 当前毫秒时间戳
     */
    void requireAvailable(String roleId, int paysid, long now) {
        if (paysid < 1 || paysid > FUND) {
            return;
        }
        LocalDate day = businessDay(now);
        boolean active = paysid == FUND ? fundActive(roleId)
                : daysLeft(roleId, paysid, day) > 0;
        if (active) {
            throw new ApiException(HttpStatus.CONFLICT, "BENEFIT_ALREADY_ACTIVE", "该福利已激活");
        }
    }

    /**
     * 提供原周卡、月卡与终身卡 UI 所需的预览和真实生效状态。
     *
     * @param roleId 角色编号
     * @return 按原档位顺序排列的福利卡列表
     */
    public List<Map<String, Object>> cardInfo(String roleId) {
        LocalDate day = businessDay(System.currentTimeMillis());
        Set<Integer> claimed = mapper.cardClaims(roleId, day);
        List<Map<String, Object>> info = new ArrayList<>();
        info.add(card(roleId, 1, day, claimed, 7, Arrays.asList(104, 0, 5000, 1, 34768, 1),
                Arrays.asList(104, 0, 14000), Collections.emptyList()));
        info.add(card(roleId, 2, day, claimed, 30, Arrays.asList(104, 0, 6666, 1, 32005, 1),
                Arrays.asList(104, 0, 50000), Collections.emptyList()));
        info.add(card(roleId, 3, day, claimed, 0, Arrays.asList(104, 0, 15000, 1, 31022, 5, 1, 21062, 20),
                Collections.emptyList(), Arrays.asList(1, 34563, 1)));
        return info;
    }

    /**
     * 判断超速闯关所需的月卡与终身卡是否同时生效。
     * @param roleId 角色编号
     * @return 两种卡均有效时为真
     */
    public boolean speedAdventureActive(String roleId) {
        LocalDate today = businessDay(System.currentTimeMillis());
        return daysLeft(roleId, 2, today) > 0 && daysLeft(roleId, 3, today) > 0;
    }

    /**
     * 查询基金是否有已确认激活订单。
     *
     * @param roleId 角色编号
     * @return 基金是否激活
     */
    public boolean fundActive(String roleId) {
        return mapper.lastPayment(roleId, FUND) > 0;
    }

    /**
     * 查询全部原基金等级档位及已领状态。
     *
     * @param roleId 角色编号
     * @return 按原底包编号顺序排列的档位
     */
    public List<Map<String, Object>> fundInfo(String roleId) {
        Set<Integer> claimed = mapper.fundClaims(roleId);
        List<Map<String, Object>> tiers = new ArrayList<>();
        for (int index = 0; index < FUND_LEVELS.length; index++) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("grade", FUND_LEVELS[index]);
            fields.put("rew", Arrays.asList(104, 0, FUND_DIAMONDS[index]));
            fields.put("flag", claimed.contains(index + 1) ? 1 : 0);
            tiers.add(fields);
        }
        return tiers;
    }

    /**
     * 查询已领取全部基金档位的状态。
     *
     * @param roleId 角色编号
     * @return 是否领取完毕
     */
    public boolean fundFinished(String roleId) {
        return mapper.fundClaims(roleId).size() == FUND_LEVELS.length;
    }

    /**
     * 领取当前北京时间日的生效卡奖励。
     *
     * @param roleId 角色编号
     * @param index 原福利卡编号
     * @return 真实到账结果或原版拒绝码
     */
    @Transactional
    public Claim claimCard(String roleId, long index) {
        if (index < 1 || index > 3) {
            return new Claim(BAD_INDEX, 0, 0);
        }
        roles.lockRole(roleId);
        int paysid = (int) index;
        LocalDate day = businessDay(System.currentTimeMillis());
        if (daysLeft(roleId, paysid, day) <= 0) {
            return new Claim(NOT_ELIGIBLE, 0, 0);
        }
        if (mapper.cardClaims(roleId, day).contains(paysid)) {
            return new Claim(ALREADY_CLAIMED, 0, 0);
        }
        int diamonds = paysid == 1 ? 5000 : paysid == 2 ? 6666 : 15000;
        int item = paysid == 1 ? 34768 : paysid == 2 ? 32005 : 0;
        if (item != 0) {
            long quantity = inventory.count(roleId, item);
            if (quantity == Integer.MAX_VALUE
                    || (quantity == 0 && inventory.ordinaryUsed(roleId) >= inventory.ordinaryCapacity(roleId))) {
                return new Claim(100015, 0, 0);
            }
        }
        roles.addCurrency(roleId, 0, diamonds, 0);
        if (item != 0) {
            inventory.grant(roleId, item, 1);
        }
        mapper.insertCardClaim(roleId, paysid, day);
        return new Claim(0, diamonds, item);
    }

    /**
     * 领取已激活且满足角色等级的基金档位。
     *
     * @param roleId 角色编号
     * @param index 原基金档位编号
     * @return 真实到账结果或原版拒绝码
     */
    @Transactional
    public Claim claimFund(String roleId, long index) {
        if (index < 1 || index > FUND_LEVELS.length) {
            return new Claim(BAD_INDEX, 0, 0);
        }
        int tier = (int) index;
        LocalRole role = roles.lockRole(roleId);
        if (!fundActive(roleId) || role.level < FUND_LEVELS[tier - 1]) {
            return new Claim(NOT_ELIGIBLE, 0, 0);
        }
        if (mapper.fundClaims(roleId).contains(tier)) {
            return new Claim(ALREADY_CLAIMED, 0, 0);
        }
        int diamonds = FUND_DIAMONDS[tier - 1];
        roles.addCurrency(roleId, 0, diamonds, 0);
        mapper.insertFundClaim(roleId, tier);
        return new Claim(0, diamonds, 0);
    }

    /**
     * 计算短期卡的准确剩余业务日，终身卡始终有效。
     *
     * @param roleId 角色编号
     * @param paysid 原福利卡档位
     * @param day 查询日期
     * @return 剩余天数，未激活或过期返回 -1
     */
    private int daysLeft(String roleId, int paysid, LocalDate day) {
        long last = mapper.lastPayment(roleId, paysid);
        if (last <= 0) {
            return -1;
        }
        long elapsed = ChronoUnit.DAYS.between(businessDay(last), day);
        if (elapsed < 0) {
            return -1;
        }
        if (paysid == 3) {
            return 1;
        }
        long remaining = (paysid == 1 ? 7 : 30) - elapsed;
        return remaining <= 0 ? -1 : (int) remaining;
    }

    /**
     * 编组一张卡的原协议显示字段。
     *
     * @param roleId 角色编号
     * @param index 福利卡档位
     * @param day 查询日期
     * @param claimed 当天已领取的卡
     * @param duration 原定有效日数
     * @param preview 每日奖励与激活展示
     * @param activation 短期卡激活立送
     * @param optional 终身卡额外激活预览
     * @return 原月卡信息结构
     */
    private Map<String, Object> card(String roleId, int index, LocalDate day, Set<Integer> claimed, int duration,
                                     List<Integer> preview, List<Integer> activation, List<Integer> optional) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("idx", index);
        fields.put("dayNum", daysLeft(roleId, index, day));
        fields.put("flag", claimed.contains(index) ? 1 : 0);
        fields.put("specialreward", duration);
        fields.put("rew1", preview);
        fields.put("rew2", activation);
        fields.put("rew3", optional);
        return fields;
    }

    /**
     * 将订单和请求时间转换为统一的业务日。
     *
     * @param epochMillis 原订单毫秒整数
     * @return 北京时间日历日期
     */
    private static LocalDate businessDay(long epochMillis) {
        return Instant.ofEpochMilli(epochMillis).atZone(GAME_ZONE).toLocalDate();
    }

    /**
     * 已领取的钻石和可选道具。
     *
     * @author tongruiyuan
     * @since 2026-11-18 22:49
     */
    public static final class Claim {
        public final int errno;
        public final int diamonds;
        public final int item;

        /**
         * 表示原协议领奖结果。
         *
         * @param errno 原协议结果码
         * @param diamonds 已发放的钻石
         * @param item 已发放的道具，零表示无道具
         */
        Claim(int errno, int diamonds, int item) {
            this.errno = errno;
            this.diamonds = diamonds;
            this.item = item;
        }
    }
}
