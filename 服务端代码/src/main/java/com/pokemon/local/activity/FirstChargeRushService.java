package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
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
 * 首冲来袭的三日累计充值、顺序领取和本地背包到账服务。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:22
 */
@Service
public class FirstChargeRushService {
    public static final int ACTIVITY = 10055;
    private static final int NOT_OPEN = 100013;
    private static final int ALREADY_RECEIVED = 100011;
    private static final int NOT_QUALIFIED = 100012;
    private static final long DIAMOND_DISPLAY_PER_CENT = 10;

    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final RoleService roles;
    private final InventoryService inventory;
    private final JsonNode tiers;

    /**
     * 连接运营周期、角色及资产数据，校验随包档位模板。
     *
     * @param calendar 共享活动周期
     * @param mapper 真实订单和领取存档
     * @param roles 角色事务入口
     * @param inventory 本地背包与邮件
     * @param json 数值解析器
     * @throws IOException 档位资源无法读取
     */
    FirstChargeRushService(ActivityService calendar, ActivityMapper mapper, RoleService roles,
                           InventoryService inventory, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
        try (InputStream input = new ClassPathResource("activity/first-charge-rush.json").getInputStream()) {
            tiers = json.readTree(input);
        }
        for (JsonNode tier : tiers) {
            for (JsonNode entry : tier.required("rewards")) {
                inventory.itemType(entry.get(0).asInt());
            }
        }
    }

    /**
     * 查询本轮的实际充值与档位顺序。
     *
     * @param roleId 当前角色
     * @param period 本期运营窗口
     * @return 可直接编码的原活动页面字段
     */
    public Map<String, Object> status(String roleId, ActivityPeriod period) {
        int index = claimed(roleId, period);
        List<Map<String, Object>> info = new ArrayList<>();
        for (JsonNode tier : tiers) {
            Map<String, Object> row = new LinkedHashMap<>();
            List<Integer> rewards = new ArrayList<>();
            for (JsonNode entry : tier.path("rewards")) {
                rewards.add(1);
                rewards.add(entry.get(0).asInt());
                rewards.add(entry.get(1).asInt());
            }
            row.put("rew", rewards);
            row.put("show", rewards.subList(0, 3));
            row.put("mun", tier.path("cents").asLong() * DIAMOND_DISPLAY_PER_CENT);
            info.add(row);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("errno", 0);
        response.put("info", info);
        response.put("amass", paid(roleId, period) * DIAMOND_DISPLAY_PER_CENT);
        response.put("index", index);
        response.put("flag", index == tiers.size() ? 1 : 0);
        return response;
    }

    /**
     * 在同一事务中领取当前档，保存防重记录，并把背包溢出部分寄到系统邮件。
     *
     * @param roleId 当前角色
     * @param viewed 页面展示的运营周期
     * @param index 当前连接展示的已领档数
     * @param requestKey 网关连接和请求号组成的重放凭据
     * @return 实际领取结果
     */
    @Transactional
    public Result claim(String roleId, ActivityPeriod viewed, int index, String requestKey) {
        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        if (viewed == null || viewed.start != period.start) {
            return failure(NOT_OPEN);
        }
        if (index != claimed(roleId, period) || index >= tiers.size()
                || mapper.hasOperatingRequest(roleId, ACTIVITY, requestKey)) {
            return failure(ALREADY_RECEIVED);
        }
        JsonNode tier = tiers.get(index);
        if (paid(roleId, period) < tier.path("cents").asLong()) {
            return failure(NOT_QUALIFIED);
        }
        Map<Integer, Long> rewards = new LinkedHashMap<>();
        for (JsonNode entry : tier.path("rewards")) {
            rewards.put(entry.get(0).asInt(), entry.get(1).asLong());
        }
        int next = index + 1;
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(roleId,
                "activity:" + ACTIVITY + ":" + period.start + ":" + next, rewards);
        mapper.recordOperatingAction(roleId, period, next, requestKey);
        return new Result(0, next == tiers.size() ? 1 : 0, delivered, !rewards.equals(delivered));
    }

    /**
     * 返回是否已达下一档周期开启的门槛。
     *
     * @param roleId 当前角色
     * @return 有可领奖档时为真
     */
    public boolean claimable(String roleId) {
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        int next = claimed(roleId, period);
        return next < tiers.size() && paid(roleId, period) >= tiers.get(next).path("cents").asLong();
    }

    /**
     * 返回本期已领取的档位数。
     *
     * @param roleId 当前角色
     * @param period 本期运营窗口
     * @return 按顺序完成的档位数量
     */
    private int claimed(String roleId, ActivityPeriod period) {
        return mapper.operatingActions(roleId, period).size();
    }

    /**
     * 读取已确认、且结算时间在本期的实际订单金额。
     *
     * @param roleId 当前角色
     * @param period 本期运营窗口
     * @return 实付分数
     */
    private long paid(String roleId, ActivityPeriod period) {
        return mapper.rechargeSum(roleId, period.start * 1000, period.end * 1000);
    }

    /**
     * 创建没有资产变化的错误结果。
     *
     * @param errno 原客户端错误码
     * @return 拒绝结果
     */
    private static Result failure(int errno) {
        return new Result(errno, 0, Collections.emptyMap(), false);
    }

    /**
     * 首冲来袭的发货结果和下一页状态。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:22
     */
    public static final class Result {
        public final int errno;
        public final int flag;
        public final Map<Integer, Long> delivered;
        public final boolean mailed;

        /**
         * 保存实际入库与溢出结果。
         *
         * @param errno 原客户端错误码
         * @param flag 原页面全部领取完成标记
         * @param delivered 已直接进入背包的道具
         * @param mailed 是否另有奖励经系统邮件送达
         */
        private Result(int errno, int flag, Map<Integer, Long> delivered, boolean mailed) {
            this.errno = errno;
            this.flag = flag;
            this.delivered = delivered;
            this.mailed = mailed;
        }
    }
}

