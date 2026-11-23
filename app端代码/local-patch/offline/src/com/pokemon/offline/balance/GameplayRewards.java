package com.pokemon.offline.balance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.inventory.InventoryService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;

/**
 * 读取全局数值规划中用于道馆扫荡和冒险挂机的奖励配置。
 *
 * @author tongruiyuan
 * @since 2026-11-19 10:09
 */
@Service
public class GameplayRewards {
    private final Map<Integer, Map<Integer, Long>> dojoItems = new HashMap<>();
    private final List<Drop> pool = new ArrayList<>();
    private final int dailyLimitPerDojo;
    private final int intervalSeconds;
    private final int totalWeight;
    private final JsonNode quickenDrop;

    /**
     * 载入奖励档位和概率池，并核对道具模板。
     *
     * @param json 配置读取器
     * @param inventory 普通道具目录
     * @throws IOException 无法读取奖励配置
     */
    GameplayRewards(ObjectMapper json, InventoryService inventory) throws IOException {
        JsonNode root;
        try (InputStream source = new ClassPathResource("balance/local-gameplay-balance.json").getInputStream()) {
            root = json.readTree(source);
        }
        JsonNode dojo = root.path("dojoSweep");
        dailyLimitPerDojo = positive(dojo, "dailyLimitPerDojo");
        for (JsonNode tier : dojo.path("tiers")) {
            int min = positive(tier, "minDojo");
            int max = positive(tier, "maxDojo");
            Map<Integer, Long> items = new LinkedHashMap<>();
            for (JsonNode item : tier.path("items")) {
                int id = positive(item, "id");
                validateItem(inventory, id);
                items.merge(id, (long) positive(item, "count"), Math::addExact);
            }
            if (min > max || items.isEmpty()) {
                throw new IllegalStateException("无效的道馆扫荡奖励档位 " + min);
            }
            Map<Integer, Long> tierItems = Collections.unmodifiableMap(items);
            for (int id = min; id <= max; id++) {
                if (dojoItems.put(id, tierItems) != null) {
                    throw new IllegalStateException("道馆扫荡奖励档位重叠 " + id);
                }
            }
        }
        JsonNode hangup = root.path("hangupDrop");
        quickenDrop = root.path("quickenDrop");
        intervalSeconds = positive(hangup, "intervalSeconds");
        int weight = 0;
        for (JsonNode item : hangup.path("pool")) {
            int id = item.path("id").asInt(-1);
            int count = item.path("count").asInt(-1);
            if (id != 0 || count != 0) {
                validateItem(inventory, positive(item, "id"));
                count = positive(item, "count");
            }
            weight = Math.addExact(weight, positive(item, "weight"));
            pool.add(new Drop(id, count, weight));
        }
        if (dojoItems.isEmpty() || pool.isEmpty()) {
            throw new IllegalStateException("道馆扫荡档位与挂机掉落池均需有配置");
        }
        totalWeight = weight;
    }

    /**
     * 获取角色每日对同一座已通关道馆的扫荡次数上限。
     *
     * @return 单馆日上限
     */
    public int dailyLimitPerDojo() {
        return dailyLimitPerDojo;
    }

    /**
     * 获取一道馆的每次扫荡奖励。
     *
     * @param dojoId 底包道馆编号
     * @return 按物品编号合计的奖励
     */
    public Map<Integer, Long> dojoItems(int dojoId) {
        return dojoItems.getOrDefault(dojoId, Collections.emptyMap());
    }

    /**
     * 使用角色和计时段确定掉落，同一尚未结算的奖励在预览、重试和重启后保持一致。
     *
     * @param roleId 角色编号
     * @param scope 普通挂机或快速战斗自然日的抽取标识
     * @param settledSeconds 已结算累计秒数
     * @param seconds 当前可领取的累计秒数
     * @return 按道具编号合计的掉落，抽中空档时跳过
     */
    public Map<Integer, Long> hangupItems(String roleId, String scope, long settledSeconds, int seconds) {
        long firstRoll = settledSeconds / intervalSeconds;
        long nextRoll = Math.addExact(settledSeconds, seconds) / intervalSeconds;
        Map<Integer, Long> items = new LinkedHashMap<>();
        long playerSeed = ((long) roleId.hashCode() << 32) ^ scope.hashCode();
        for (long roll = firstRoll; roll < nextRoll; roll++) {
            int ticket = new SplittableRandom(playerSeed + roll).nextInt(totalWeight);
            for (Drop drop : pool) {
                if (ticket < drop.weightLimit) {
                    if (drop.id > 0) {
                        items.merge(drop.id, (long) drop.count, Math::addExact);
                    }
                    break;
                }
            }
        }
        return Collections.unmodifiableMap(items);
    }

    /**
     * 按已激活的挂机地图为一笔快速战斗抽取装备、精灵与培养材料。
     * @param roleId 本地角色
     * @param day 结算日
     * @param used 当日已结算次数
     * @param mapId 当前挂机地图索引
     * @param seconds 本次快速战斗时长
     * @return 本笔实例模板和道具数量
     */
    public QuickenDrop quickenDrop(String roleId, String day, int used, int mapId, int seconds) {
        JsonNode map = quickenDrop.path("maps").path(Integer.toString(mapId));
        if (seconds != positive(quickenDrop, "seconds") || map.isMissingNode()) {
            throw new IllegalStateException("缺少快速战斗地图或时长配置 " + mapId);
        }
        long seed = ((long) roleId.hashCode() << 32) ^ (day.hashCode() * 31L + used) * 53L + mapId;
        SplittableRandom equipmentRandom = new SplittableRandom(seed).split();
        SplittableRandom elfRandom = new SplittableRandom(~seed).split();
        QuickenDrop drop = new QuickenDrop();
        drawTemplates(drop.equipment, map.path("equipment"), quickenDrop.path("equipmentCount"), equipmentRandom);
        drawTemplates(drop.elves, map.path("elves"), quickenDrop.path("elfCount"), elfRandom);
        JsonNode stones = quickenDrop.path("stones");
        int total = positive(quickenDrop, "stoneCount");
        int count = stones.size();
        int remainder = total % count;
        int first = (int) ((long) used * remainder % count);
        for (int i = 0; i < count; i++) {
            int id = stones.get((first + i) % count).asInt();
            drop.items.put(id, (long) (total / count + (i < remainder ? 1 : 0)));
        }
        drop.items.put(positive(quickenDrop, "fruitId"), (long) positive(quickenDrop, "fruitCount"));
        return drop;
    }

    /**
     * 在含上下端点的数量区间内均匀取样，再逐件有放回地抽取本地图模板。
     * @param result 接收模板的列表
     * @param templates 当前地图可掉落的模板数组
     * @param range 本次发放数量的上下限
     * @param random 当前结算键对应的随机序列
     */
    private static void drawTemplates(List<Integer> result, JsonNode templates, JsonNode range, SplittableRandom random) {
        int count = random.nextInt(positive(range, "min"), Math.addExact(positive(range, "max"), 1));
        for (int i = 0; i < count; i++) {
            result.add(templates.get(random.nextInt(templates.size())).asInt());
        }
    }

    /**
     * 一次快速战斗按地图抽到的模板与材料，供同一事务创建真实资产。
     * @author tongruiyuan
     * @since 2026-11-22 21:52
     */
    public static final class QuickenDrop {
        /** 训练家装备模板。 */
        public final List<Integer> equipment = new ArrayList<>();
        /** 精灵模板。 */
        public final List<Integer> elves = new ArrayList<>();
        /** 培养材料模板和数量。 */
        public final Map<Integer,Long> items = new LinkedHashMap<>();
    }

    /**
     * 获取配置中的正整数。
     *
     * @param node 所属配置对象
     * @param field 字段名
     * @return 已校验值
     */
    private static int positive(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() <= 0) {
            throw new IllegalStateException("无效的数值规划配置字段 " + field);
        }
        return value.asInt();
    }

    /**
     * 核验奖励能作为普通道具同步和保存。
     *
     * @param inventory 道具模板服务
     * @param id 待验证的道具编号
     */
    private static void validateItem(InventoryService inventory, int id) {
        if (id == 1001 || id == 1002) {
            throw new IllegalStateException("道馆扫荡和挂机掉落只使用背包道具 " + id);
        }
        inventory.itemType(id);
    }

    /** 带累积权重上界的单个掉落。 */
    private static final class Drop {
        final int id;
        final int count;
        final int weightLimit;

        /**
         * 保存抽取区间与命中奖励。
         *
         * @param id 奖励道具编号，零为空奖
         * @param count 道具数量
         * @param weightLimit 抽取区间的互斥上界
         */
        Drop(int id, int count, int weightLimit) {
            this.id = id;
            this.count = count;
            this.weightLimit = weightLimit;
        }
    }
}
