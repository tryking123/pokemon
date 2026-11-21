package com.pokemon.offline.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.activitydaily.RechargePromotionService.Tier;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;

/**
 * 读取本地神秘兑换、消费活动、节日签到和终身累充的统一数值。
 *
 * @author tongruiyuan
 * @since 2026-11-19 10:12
 */
@Service
public class OperatingActivityRules {
    public static final int EXCHANGE = 10008;
    public static final int WEEKLY_SPEND = 10009;
    public static final int DAILY_SPEND = 10032;
    public static final int ATTENDANCE = 10037;
    public static final int LIFETIME_RECHARGE = 10071;
    private final Map<Integer, Rules> entries;

    /**
     * 读取随包的本地运营数值。
     *
     * @param json 配置读取器
     * @throws IOException 配置缺失或格式错误
     */
    OperatingActivityRules(ObjectMapper json) throws IOException {
        try (InputStream input = new ClassPathResource("activity/operating-exchange-recharge.json").getInputStream()) {
            entries = json.readValue(input, new TypeReference<Map<Integer, Rules>>() { });
        }
    }

    /**
     * 查询实际开放活动的规则。
     *
     * @param id 原版运营活动 ID
     * @return 当前本地配置
     */
    public Rules activity(int id) {
        Rules rules = entries.get(id);
        if (rules == null || rules.days <= 0) {
            throw new IllegalStateException("缺少运营活动规则 " + id);
        }
        return rules;
    }

    /**
     * 运营活动时长与各模块数值配置。
     *
     * @author tongruiyuan
     * @since 2026-11-19 10:12
     */
    public static class Rules {
        public int days;
        public int show;
        public List<Offer> offers = Collections.emptyList();
        public List<Tier> tiers = Collections.emptyList();
        public List<AttendanceDay> attendance = Collections.emptyList();
    }

    /**
     * 神秘兑换条目，消耗和奖励为客户端三元组。
     *
     * @author tongruiyuan
     * @since 2026-11-19 10:12
     */
    public static class Offer {
        public List<Integer> redeem;
        public List<Integer> exchange;
        public int num;
    }

    /**
     * 每日三个签到任务，以及可选的累计签到档位。
     *
     * @author tongruiyuan
     * @since 2026-11-19 10:12
     */
    public static class AttendanceDay {
        public List<Integer> login;
        public List<Integer> chongzhi;
        public List<Integer> xiaohao;
        public List<Integer> award = Collections.emptyList();
    }
}
