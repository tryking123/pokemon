package com.pokemon.local.activity;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
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
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 皮卡丘乐园的游乐券购买、五层闯关与周期累计奖励。
 *
 * @author tongruiyuan
 * @since 2026-11-19 18:38
 */
@Service
public class PikaParkService {
    public static final int ACTIVITY = 10036;
    public static final int TICKET = 34038;
    private static final long TICKET_PRICE = 2000;
    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final InventoryService inventory;
    private final RoleService roles;
    private final ElfService elves;
    private final JdbcTemplate jdbc;
    private final List<Layer> layers;

    /**
     * 装载五层原版关卡和奖品配置。
     *
     * @param calendar 运营活动周期
     * @param mapper 活动回执与累计奖记录
     * @param inventory 角色背包
     * @param roles 角色事务锁
     * @param elves 整只精灵发放
     * @param jdbc 乐园楼层进度
     * @param json 配置读取器
     * @throws IOException 运行配置读取失败
     */
    PikaParkService(ActivityService calendar, ActivityMapper mapper, InventoryService inventory, RoleService roles,
                    ElfService elves, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.mapper = mapper;
        this.inventory = inventory;
        this.roles = roles;
        this.elves = elves;
        this.jdbc = jdbc;
        try (InputStream stream = new ClassPathResource("activity/pika-park.json").getInputStream()) {
            layers = json.readValue(stream, new TypeReference<List<Layer>>() {});
        }
        for (Layer layer : layers) {
            int weight = 0;
            for (int i = 1; i < layer.award.size(); i += 6) {
                weight += layer.award.get(i);
            }
            if (weight != 10000 || layer.award2.size() != 4) {
                throw new IllegalArgumentException("皮卡丘乐园奖励配置错误");
            }
        }
    }

    /**
     * 读取本周期的五层配置、尝试次数及累计档回执。
     *
     * @param roleId 当前角色
     * @param period 页面当前轮次
     * @return 原乐园查询应答
     */
    public Result status(String roleId, ActivityPeriod period) {
        if (period.start > Instant.now().getEpochSecond()) {
            return Result.error(100013);
        }
        Progress stage = progress(roleId, period.start);
        List<Map<String, Object>> info = new ArrayList<>();
        List<Integer> record = new ArrayList<>();
        Map<Integer, Integer> actions = mapper.operatingActions(roleId, period);
        for (int i = 0; i < layers.size(); i++) {
            Layer layer = layers.get(i);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("award", layer.award);
            row.put("award2", layer.award2);
            row.put("xiaohao1", layer.xiaohao1);
            if (layer.xiaohao2 != null) {
                row.put("xiaohao2", layer.xiaohao2);
            }
            info.add(row);
            if (actions.containsKey(-i - 1)) {
                record.add(i + 1);
            }
        }
        Result result = Result.success();
        result.response.put("info", info);
        result.response.put("total", stage.total);
        result.response.put("layer", stage.layer);
        result.response.put("record", record);
        return result;
    }

    /**
     * 在同一角色事务内处理购券、闯关或累计奖领取。
     *
     * @param roleId 当前角色
     * @param viewed 已展示的周期起点
     * @param viewedLayer 连接最近显示的楼层
     * @param protocol 原客户端入口
     * @param value 闯关选项、累计奖档或购买张数
     * @param auto 是否勾选缺券自动购买
     * @param requestKey 当前连接中的请求防重键
     * @return 应答及实际资产变更
     */
    @Transactional
    public Result operate(String roleId, long viewed, int viewedLayer, int protocol, long value, boolean auto,
                          String requestKey) {
        roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (viewed != period.start || now < period.start) {
            return Result.error(100013);
        }
        if (mapper.hasOperatingRequest(roleId, ACTIVITY, requestKey)) {
            return Result.error(100011);
        }
        if (protocol == 218) {
            return buy(roleId, period, value, requestKey);
        }
        Progress stage = progress(roleId, period.start);
        if (protocol == 216 && viewedLayer != stage.layer) {
            return Result.error(100021);
        }
        return protocol == 217 ? claim(roleId, period, stage.total, value, requestKey)
                : play(roleId, period, stage, value, auto, requestKey);
    }

    /**
     * 按快捷购买界面原价购买游乐券。
     *
     * @param roleId 当前角色
     * @param period 轮次
     * @param quantity 购买数量
     * @param key 网关请求唯一键
     * @return 购券结果
     */
    private Result buy(String roleId, ActivityPeriod period, long quantity, String key) {
        if (quantity < 1 || quantity > Integer.MAX_VALUE) {
            return Result.error(100021);
        }
        if (!inventory.canGrantItem(roleId, TICKET, quantity)) {
            return Result.error(100015);
        }
        long cost = quantity * TICKET_PRICE;
        if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, cost)) {
            return Result.error(100012);
        }
        inventory.grant(roleId, TICKET, quantity);
        mapper.recordOperatingAction(roleId, period, 218, key);
        Result result = Result.success();
        result.spentDiamonds = cost;
        result.items.put(TICKET, quantity);
        result.response.put("num", quantity);
        return result;
    }

    /**
     * 用实际券闯当前层，在勾选自动购买时按缺口扣钻，整批抽取后切换楼层。
     *
     * @param roleId 当前角色
     * @param period 轮次
     * @param stage 原楼层进度
     * @param option 单次为一，十连为二
     * @param auto 是否自动购买缺券
     * @param key 防重键
     * @return 当次逐项奖励与新楼层
     */
    private Result play(String roleId, ActivityPeriod period, Progress stage, long option, boolean auto, String key) {
        Layer layer = layers.get(stage.layer - 1);
        if ((option != 1 && option != 2) || (option == 2 && layer.xiaohao2 == null)) {
            return Result.error(100021);
        }
        int count = option == 1 ? 1 : 10;
        int cost = option == 1 ? layer.xiaohao1 : layer.xiaohao2;
        long owned = Math.min(cost, inventory.count(roleId, TICKET));
        long shortage = cost - owned;
        if ((!auto && shortage > 0) || !inventory.spendMaterials(roleId,
                owned == 0 ? Collections.emptyMap() : Collections.singletonMap(TICKET, owned), 0, shortage * TICKET_PRICE)) {
            return Result.error(100012);
        }
        Result result = Result.success();
        result.spentDiamonds = shortage * TICKET_PRICE;
        result.spentTickets = owned;
        List<Integer> drawn = new ArrayList<>();
        boolean advance = false;
        if (stage.guaranteed == 0 && layer.time1 > 0) {
            stage.guaranteed = ThreadLocalRandom.current().nextInt(layer.time1, layer.time2 + 1);
        }
        for (int i = 0; i < count; i++) {
            stage.tries++;
            boolean guaranteed = !advance && stage.guaranteed > 0 && stage.tries >= stage.guaranteed;
            int offset = guaranteed ? 0 : layer.pick();
            drawn.add(layer.award.get(offset));
            drawn.add(layer.award.get(offset + 2));
            drawn.add(layer.award.get(offset + 3));
            advance |= offset == 0;
        }
        deliver(roleId, key, drawn, result);
        stage.total = Math.addExact(stage.total, count);
        if (advance || stage.layer == layers.size()) {
            stage.layer = stage.layer % layers.size() + 1;
            stage.tries = 0;
            stage.guaranteed = 0;
        }
        jdbc.update("MERGE INTO local_pika_progress (role_id,period_start,current_layer,total_attempts,layer_attempts,"
                        + "guaranteed_at) KEY(role_id,period_start) VALUES(?,?,?,?,?,?)",
                roleId, period.start, stage.layer, stage.total, stage.tries, stage.guaranteed);
        mapper.recordOperatingAction(roleId, period, 216, key);
        result.response.put("list", drawn);
        result.response.put("total", stage.total);
        result.response.put("layer", stage.layer);
        result.response.put("option", option);
        result.response.put("auto", auto);
        return result;
    }

    /**
     * 按本轮实际闯关次数领取原累计奖励。
     *
     * @param roleId 当前角色
     * @param period 活动轮次
     * @param total 本期尝试次数
     * @param index 累计行序
     * @param key 当前请求防重键
     * @return 发放结果
     */
    private Result claim(String roleId, ActivityPeriod period, int total, long index, String key) {
        if (index < 1 || index > layers.size()) {
            return Result.error(100021);
        }
        if (mapper.operatingActions(roleId, period).containsKey(-(int) index)) {
            return Result.error(100011);
        }
        List<Integer> milestone = layers.get((int) index - 1).award2;
        if (total < milestone.get(0)) {
            return Result.error(100012);
        }
        Result result = Result.success();
        deliver(roleId, key, milestone.subList(1, 4), result);
        mapper.recordOperatingAction(roleId, period, -(int) index, key);
        result.response.put("idx", index);
        return result;
    }

    /**
     * 发放原通关或累计奖励，精灵和道具分别按各自容量入包或入信。
     *
     * @param roleId 当前角色
     * @param key 请求唯一键
     * @param rewards 原种类、模板、数量三元组
     * @param result 实际入账回执
     */
    private void deliver(String roleId, String key, List<Integer> rewards, Result result) {
        Map<Integer, Long> items = new LinkedHashMap<>();
        for (int i = 0; i < rewards.size(); i += 3) {
            int type = rewards.get(i);
            int id = rewards.get(i + 1);
            int amount = rewards.get(i + 2);
            if (type == 2) {
                if (elves.list(roleId).size() < inventory.elfCapacity(roleId)) {
                    result.elves.add(elves.grant(roleId, id));
                } else {
                    long now = Instant.now().getEpochSecond();
                    inventory.createRewardMail(roleId, UUID.randomUUID().toString(), "皮卡丘乐园通关奖励",
                            "精灵背包已满，请在附件中领取。", now, now + 30 * 86400L, Arrays.asList(2, id, amount));
                    result.mailed = true;
                }
            } else {
                items.merge(id, (long) amount, Long::sum);
            }
        }
        result.items.putAll(inventory.grantItemsOrMail(roleId, "pika:" + key, items));
        result.mailed |= !items.equals(result.items);
    }

    /**
     * 读取角色当轮楼层，首次访问从第一层、累计零次开始。
     *
     * @param roleId 当前角色
     * @param period 当前周期起点
     * @return 已保存或初始进度
     */
    private Progress progress(String roleId, long period) {
        return jdbc.query("SELECT current_layer,total_attempts,layer_attempts,guaranteed_at FROM local_pika_progress "
                        + "WHERE role_id=? AND period_start=?", (row, n) ->
                        new Progress(row.getInt(1), row.getInt(2), row.getInt(3), row.getInt(4)), roleId, period)
                .stream().findFirst().orElseGet(() -> new Progress(1, 0, 0, 0));
    }

    /**
     * 底包逐层权重、消耗及累计奖。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:38
     */
    public static final class Layer {
        /** 单次闯关的券数。 */
        public int xiaohao1;
        /** 该层保底范围下限。 */
        public int time1;
        /** 该层保底范围上限。 */
        public int time2;
        /** 十连所需券数，原第五层无此入口。 */
        public Integer xiaohao2;
        /** 当前层六元组奖池。 */
        public List<Integer> award;
        /** 此行累计门槛及奖励。 */
        public List<Integer> award2;

        /** 按万分权重选取六元组起始位置。
         * @return 原池中的偏移
         */
        private int pick() {
            int roll = ThreadLocalRandom.current().nextInt(10000);
            for (int i = 0; i < award.size(); i += 6) {
                roll -= award.get(i + 1);
                if (roll < 0) {
                    return i;
                }
            }
            throw new IllegalStateException("无效乐园权重");
        }
    }

    /**
     * 角色当前层与本轮计数。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:38
     */
    private static final class Progress {
        private int layer;
        private int total;
        private int tries;
        private int guaranteed;

        /**
         * 还原当前层和进度。
         *
         * @param layer 楼层
         * @param total 累计尝试
         * @param tries 当前层尝试
         * @param guaranteed 当前层保底触发次数
         */
        Progress(int layer, int total, int tries, int guaranteed) {
            this.layer = layer;
            this.total = total;
            this.tries = tries;
            this.guaranteed = guaranteed;
        }
    }

    /**
     * 乐园原应答与已结算资产变化。
     *
     * @author tongruiyuan
     * @since 2026-11-19 18:38
     */
    public static final class Result {
        public final Map<String, Object> response = new LinkedHashMap<>();
        public final Map<Integer, Long> items = new LinkedHashMap<>();
        public final List<LocalElf> elves = new ArrayList<>();
        public long spentDiamonds;
        public long spentTickets;
        public boolean mailed;

        /**
         * 创建成功动作的应答。
         *
         * @return 可填充的原协议结果
         */
        private static Result success() {
            return error(0);
        }

        /**
         * 构造带原协议状态的回执。
         *
         * @param errno 协议状态码
         * @return 待发送回执
         */
        private static Result error(int errno) {
            Result result = new Result();
            result.response.put("errno", errno);
            return result;
        }
    }
}
