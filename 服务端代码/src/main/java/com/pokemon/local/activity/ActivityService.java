package com.pokemon.local.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * 固定底包运营活动周期及单玩家排行榜 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:04
 */
@Service
@DependsOnDatabaseInitialization
public class ActivityService {
    private static final long DAY_SECONDS = 86_400;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Map<Integer, Rule> operating;
    private final Map<Integer, Rule> overview;
    private final ActivityMapper mapper;
    private final RoleService roles;
    private final String configuredAnchor;
    private long anchor;

    /**
     * 加载底包运营活动及本地化的周期配置
     *
     * @param mapper 活动数据库访问
     * @param roles 本地角色服务
     * @param json JSON 配置解析器
     * @param localRules 全局数值规划提供的运营周期
     * @param configuredAnchor 新库活动起始自然日
     * @throws IOException 配置不可读
     */
    ActivityService(ActivityMapper mapper, RoleService roles, ObjectMapper json, OperatingActivityRules localRules,
                    @Value("${POKEMON_ACTIVITY_START:}") String configuredAnchor) throws IOException {
        this.mapper = mapper;
        this.roles = roles;
        this.configuredAnchor = configuredAnchor;
        this.operating = readRules(json, "periods");
        this.overview = readRules(json, "overview");
        for (int id : new int[]{OperatingActivityRules.EXCHANGE, OperatingActivityRules.WEEKLY_SPEND,
                OperatingActivityRules.DAILY_SPEND, OperatingActivityRules.ATTENDANCE,
                OperatingActivityRules.LIFETIME_RECHARGE}) {
            Rule rule = operating.get(id);
            rule.days = localRules.activity(id).days;
            rule.enabled = true;
        }
    }

    /**
     * 保存稳定的北京自然日周期起点
     */
    @PostConstruct
    void initializeCalendar() {
        Optional<Long> stored = mapper.findAnchor();
        if (stored.isPresent()) {
            anchor = stored.get();
            return;
        }
        LocalDate day = configuredAnchor.trim().isEmpty() ? LocalDate.now(ZONE) : LocalDate.parse(configuredAnchor);
        anchor = day.atStartOfDay(ZONE).toEpochSecond();
        mapper.insertAnchor(anchor);
    }

    /**
     * 查询一个运营活动的当前周期
     *
     * @param activityId 原活动编号
     * @return 基于服务端时钟计算的活动窗口
     */
    public ActivityPeriod cycle(int activityId) {
        return cycleAt(activityId, Instant.now().getEpochSecond());
    }

    /**
     * 以同一业务时间查询活动周期，避免运行跨午夜时读取不一致的周期。
     *
     * @param activityId 原活动编号
     * @param now 当前 Unix 秒
     * @return 该时刻的活动窗口
     */
    public ActivityPeriod cycleAt(int activityId, long now) {
        Rule rule = operating.get(activityId);
        if (rule == null) {
            throw new IllegalArgumentException("没有运营活动 " + activityId);
        }
        return cycle(activityId, rule, now);
    }

    /**
     * 构造完整运营活动窗口列表
     *
     * @return 当前窗口和下一次变化时间使用的快照
     */
    public List<ActivityPeriod> operatingPeriods() {
        return periods(operating);
    }

    /**
     * 构造限时活动面板当前窗口列表
     *
     * @return 限时活动窗口
     */
    public List<ActivityPeriod> overviewPeriods() {
        long now = Instant.now().getEpochSecond();
        List<ActivityPeriod> result = new ArrayList<>(overview.size());
        overview.forEach((id, rule) -> {
            if (rule.enabled) {
                ActivityPeriod period = rule.days > 0 ? cycle(id, rule, now) : timeSlot(id, rule, now);
                if (period != null) {
                    result.add(period);
                }
            }
        });
        return result;
    }

    /** 根据概览卡片的同一份规则校验服务器动作时间。
     * @param id 原冒险卡片编号
     * @param now 待校验的 Unix 秒
     * @return 是否处于参与窗口
     */
    public boolean overviewOpenAt(int id, long now) {
        Rule rule = overview.get(id);
        if (rule == null || !rule.enabled) {
            return false;
        }
        ActivityPeriod period = rule.days > 0 ? cycle(id, rule, now) : timeSlot(id, rule, now);
        return period != null && period.start <= now && now < period.end;
    }

    /**
     * 选择原限时配置中正在参与、仍展示或最近一次将开始的活动。
     *
     * @param id 概览编号
     * @param rule 以北京时间分钟描述的窗口配置
     * @param now 当前秒数
     * @return 客户端所需的当前或最近活动窗口
     */
    private ActivityPeriod timeSlot(int id, Rule rule, long now) {
        LocalDate date = Instant.ofEpochSecond(Math.max(anchor, now)).atZone(ZONE).toLocalDate();
        ActivityPeriod upcoming = null;
        ActivityPeriod showing = null;
        for (int offset = -1; offset <= 7; offset++) {
            LocalDate day = date.plusDays(offset);
            if (rule.weekdays.length > 0
                    && Arrays.stream(rule.weekdays).noneMatch(value -> value == day.getDayOfWeek().getValue())) {
                continue;
            }
            long midnight = day.atStartOfDay(ZONE).toEpochSecond();
            for (int minute : rule.openMinutes) {
                long start = midnight + 60L * minute;
                long end = start + 60L * rule.durationMinutes;
                long showUntil = end + 60L * rule.showMinutes;
                if (start < anchor || showUntil <= now) {
                    continue;
                }
                ActivityPeriod window = new ActivityPeriod(id, start, end, showUntil, 1);
                if (start <= now && now < end) {
                    return window;
                }
                if (end <= now) {
                    showing = window;
                } else if (upcoming == null || start < upcoming.start) {
                    upcoming = window;
                }
            }
        }
        return showing != null ? showing : upcoming;
    }

    /**
     * 获取参与排名的 admin 的最新角色数据
     *
     * @param roleId 请求角色编号
     * @return 单玩家排行榜中的真实角色
     */
    public Optional<LocalRole> rankedRole(String roleId) {
        return mapper.isRankedRole(roleId) ? roles.findById(roleId) : Optional.empty();
    }

    /**
     * 统计本周期内角色游戏模拟订单的累计成交价
     *
     * @param roleId 当前角色编号
     * @return 已确认模拟充值金额，单位分
     */
    public long rechargeScore(String roleId) {
        ActivityPeriod period = cycle(10018);
        return mapper.rechargeSum(roleId, period.start * 1000, period.end * 1000);
    }

    /**
     * 读取一类活动配置，按 ID 固定输出顺序
     *
     * @param json JSON 配置解析器
     * @param file 配置文件名
     * @return 按编号索引的周期规则
     * @throws IOException 配置不可读
     */
    private Map<Integer, Rule> readRules(ObjectMapper json, String file) throws IOException {
        try (InputStream content = new ClassPathResource("activity/" + file + ".json").getInputStream()) {
            return new TreeMap<>(json.readValue(content, new TypeReference<Map<Integer, Rule>>() { }));
        }
    }

    /**
     * 对同一时间点计算一组活动的周期
     *
     * @param rules 当前分组全部规则
     * @return 活动列表
     */
    private List<ActivityPeriod> periods(Map<Integer, Rule> rules) {
        long now = Instant.now().getEpochSecond();
        List<ActivityPeriod> result = new ArrayList<>();
        rules.forEach((id, rule) -> {
            if (rule.enabled) {
                result.add(cycle(id, rule, now));
            }
        });
        return result;
    }

    /**
     * 以稳定起点和原持续天数计算半开时间窗口
     *
     * @param id 原活动编号
     * @param rule 活动周期规则
     * @param now 当前 Unix 秒
     * @return 当前活动时间窗
     */
    private ActivityPeriod cycle(int id, Rule rule, long now) {
        long length = rule.days * DAY_SECONDS;
        long start = anchor + Math.max(0, Math.floorDiv(now - anchor, length)) * length;
        if (rule.monday) {
            LocalDate today = Instant.ofEpochSecond(now).atZone(ZONE).toLocalDate();
            start = today.minusDays(today.getDayOfWeek().getValue() - 1L).atStartOfDay(ZONE).toEpochSecond();
        }
        int day = (int) (Math.max(0, now - start) / DAY_SECONDS) + 1;
        long next = start + length;
        return new ActivityPeriod(id, start, next - 60L * rule.showMinutes, next, day);
    }

    /**
     * 活动运行周期配置
     *
     * @author tongruiyuan
     * @since 2026-11-18 19:04
     */
    private static class Rule {
        public boolean enabled;
        /** 按北京时间自然周循环。 */
        public boolean monday;
        public int days;
        public int durationMinutes;
        public int showMinutes;
        public int[] openMinutes = new int[0];
        public int[] weekdays = new int[0];
    }
}
