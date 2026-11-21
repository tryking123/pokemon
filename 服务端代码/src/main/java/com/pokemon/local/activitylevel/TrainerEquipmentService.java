package com.pokemon.local.activitylevel;

import com.pokemon.local.activity.CarnivalProgressMapper;
import com.pokemon.local.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.time.Instant;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 训练家装备部位、强化与费用结算。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:02
 */
@Service
public class TrainerEquipmentService {
    private static final int ENHANCE_STONE = 21000;
    private static final int DIAMONDS_PER_STONE = 200;
    private static final int COINS_PER_BUNDLE = 100000;
    private static final int DIAMONDS_PER_BUNDLE = 100;

    private final Map<Integer, int[]> fees = new HashMap<>();
    private final Map<Integer, int[]> meltRules = new HashMap<>();
    private final LevelGiftMapper mapper;
    private final RoleService roles;
    private final CarnivalProgressMapper carnival;
    private final InventoryService inventory;

    /**
     * 加载当前底包的装备强化费用。
     *
     * @param mapper 独立装备和佩戴状态存档
     * @param roles 角色锁和当前金币
     * @param carnival 七日目标养成事件记录
     * @param inventory 强化材料和资产服务
     * @throws IOException 无法读取原版强化表
     */
    TrainerEquipmentService(LevelGiftMapper mapper, RoleService roles, InventoryService inventory,
                            CarnivalProgressMapper carnival) throws IOException {
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
        this.carnival = carnival;
        ClassPathResource resource = new ClassPathResource("catalog/gear-enhance.csv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] row = line.split(",");
                fees.put(Integer.parseInt(row[0]), new int[]{Integer.parseInt(row[1]), Integer.parseInt(row[2])});
            }
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new ClassPathResource("catalog/gear-melt.csv").getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("#")) {
                    String[] row = line.split(",");
                    meltRules.put(Integer.parseInt(row[0]), new int[]{Integer.parseInt(row[1]), Integer.parseInt(row[2]),
                            Integer.parseInt(row[3]), Integer.parseInt(row[4]), Integer.parseInt(row[5]), Integer.parseInt(row[6]),
                            Integer.parseInt(row[7]), Integer.parseInt(row[8]), Integer.parseInt(row[9])});
                }
            }
        }
    }

    /**
     * 读取角色当前熔炼点数。
     * @param roleId 角色编号
     * @return 已累计的熔炼点数
     */
    public int meltPoints(String roleId) {
        return mapper.meltPoints(roleId);
    }

    /** 打造台当前唯一候选、已用刷新次数、积分及下次刷新次数重置时间。 */
    public static final class Forge {
        public final AdventureEquipment offer;
        public final int refreshes;
        public final int points;
        public final long resetAt;

        /**
         * 生成向客户端展示的打造台。
         * @param offer 当前展示装备
         * @param refreshes 本日主动刷新次数
         * @param points 可用熔炼点
         * @param day 北京时间凌晨四点起算的业务日
         */
        private Forge(AdventureEquipment offer, int refreshes, int points, long day) {
            this.offer = offer;
            this.refreshes = refreshes;
            this.points = points;
            this.resetAt = (day + 1) * 86400L - 14400L;
        }
    }

    /**
     * 获取角色已开启的打造台，跨日只重置主动刷新次数。
     * @param roleId 角色编号
     * @return 展示中的装备，角色未达开放等级时为空
     */
    @Transactional
    public Forge forgeBoard(String roleId) {
        LocalRole role = roles.lockRole(roleId);
        return loadForge(role, Math.floorDiv(Instant.now().getEpochSecond() + 14400L, 86400L));
    }

    /**
     * 刷新打造台或领取已展示装备，结算和更新候选在同一事务内完成。
     * @param roleId 角色编号
     * @param shown 该连接最近展示的候选实例
     * @param refresh 主动刷新为真，使用熔炼点打造为假
     * @param key 原连接请求回执
     * @return 成功后的新打造台，拒绝时为空
     */
    @Transactional
    public Forge changeForge(String roleId, String shown, boolean refresh, String key) {
        LocalRole role = roles.lockRole(roleId);
        long day = Math.floorDiv(Instant.now().getEpochSecond() + 14400L, 86400L);
        Forge board = loadForge(role, day);
        if (board == null || !board.offer.id.equals(shown) || mapper.hasForgeReceipt(roleId, key)) {
            return null;
        }
        int count = board.refreshes;
        int points = board.points;
        if (refresh) {
            if (count >= 3 && !inventory.spendMaterials(roleId, Collections.emptyMap(), 0, 20)) {
                return null;
            }
            count = Math.addExact(count, 1);
        } else {
            int[] rule = meltRules.get(board.offer.configId);
            int cost = rule[4] * rule[3] / 4;
            if (cost <= 0 || cost > points || inventory.ordinaryUsed(roleId) >= inventory.ordinaryCapacity(roleId)) {
                return null;
            }
            mapper.insertEquipment(roleId, board.offer);
            points -= cost;
            mapper.saveMeltPoints(roleId, points);
            carnival.record(roleId, 50015, 1);
        }
        AdventureEquipment next = generateForge(role.level);
        mapper.saveForge(roleId, next, day, count);
        mapper.addForgeReceipt(roleId, key);
        return new Forge(next, count, points, day);
    }

    /**
     * 初始化或恢复已锁定角色的打造存档。
     * @param role 已锁定的角色
     * @param day 当前业务日
     * @return 当前打造台状态
     */
    private Forge loadForge(LocalRole role, long day) {
        if (role.level < 7) {
            return null;
        }
        String[] saved = mapper.forge(role.id);
        int points = mapper.meltPoints(role.id);
        if (saved == null) {
            AdventureEquipment next = generateForge(role.level);
            mapper.saveForge(role.id, next, day, 0);
            return new Forge(next, 0, points, day);
        }
        int config = Integer.parseInt(saved[1]);
        AdventureEquipment offer = new AdventureEquipment(saved[0], config, meltRules.get(config)[0]);
        int count = Integer.parseInt(saved[3]);
        if (Long.parseLong(saved[2]) != day) {
            count = 0;
            mapper.saveForge(role.id, offer, day, count);
        }
        return new Forge(offer, count, points, day);
    }

    /**
     * 按原等级档、部位和本地品质权重生成一件候选。
     * @param level 角色等级
     * @return 尚未入库的候选装备实例
     */
    private AdventureEquipment generateForge(int level) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int slot = random.nextInt(1, 7);
        int roll = random.nextInt(100);
        int quality = roll < 50 ? 1 : roll < 80 ? 2 : roll < 95 ? 3 : roll < 99 ? 4 : 5;
        int tier = Math.min(150, level / 5 * 5);
        for (Map.Entry<Integer,int[]> entry : meltRules.entrySet()) {
            int[] rule = entry.getValue();
            if (rule[0] == slot && rule[4] == tier && rule[5] == quality) {
                return new AdventureEquipment(UUID.randomUUID().toString(), entry.getKey(), slot);
            }
        }
        throw new IllegalStateException("打造装备模板缺失");
    }

    /**
     * 熔炼选中的背包装备，并逐件抽取同部位高品质装备。
     * @param roleId 当前角色编号
     * @param ids 客户端所选装备的实例编号
     * @return 本批增加的装备和最终点数，整批无效时返回空引用
     */
    @Transactional
    public MeltResult melt(String roleId, List<?> ids) {
        roles.lockRole(roleId);
        if (ids.isEmpty() || new HashSet<>(ids).size() != ids.size()) {
            return null;
        }
        Map<String, AdventureEquipment> loose = new HashMap<>();
        for (AdventureEquipment gear : mapper.equipment(roleId)) {
            loose.put(gear.id, gear);
        }
        for (Object id : ids) {
            AdventureEquipment gear = loose.get(id);
            if (gear == null || !meltRules.containsKey(gear.configId)) {
                return null;
            }
        }
        int points = mapper.meltPoints(roleId);
        List<AdventureEquipment> gained = new ArrayList<>();
        Map<Integer,Long> stones = new java.util.LinkedHashMap<>();
        for (Object id : ids) {
            AdventureEquipment gear = loose.get(id);
            int[] rule = meltRules.get(gear.configId);
            if (!mapper.removeLooseEquipment(roleId, gear.id)) {
                throw new IllegalStateException("待熔炼的装备已变更");
            }
            points = Math.addExact(points, rule[3]);
            if (rule[6] > 0 && ThreadLocalRandom.current().nextInt(100) < rule[6]) {
                stones.merge(rule[7],(long)rule[8],Long::sum);
            }
            if (rule[1] > 0 && ThreadLocalRandom.current().nextInt(100) < rule[1]) {
                AdventureEquipment replacement = new AdventureEquipment(UUID.randomUUID().toString(), rule[2],
                        meltRules.get(rule[2])[0]);
                mapper.insertEquipment(roleId, replacement);
                gained.add(replacement);
            }
        }
        mapper.saveMeltPoints(roleId, points);
        carnival.record(roleId, 112, ids.size());
        Map<Integer,Long> received=inventory.grantItemsOrMail(roleId,"equip-stones:"+ids.get(0),stones);
        return new MeltResult(points, gained, received);
    }

    /** 原装备熔炼应答及奖励推送所需的已结算结果。 */
    public static final class MeltResult {
        public final int points;
        public final List<AdventureEquipment> equipment;
        /** 本次直接入包的进阶石。 */
        public final Map<Integer,Long> stones;

        /**
         * 保存同一事务内的熔炼结果。
         * @param points 最终点数
         * @param stones 入包的进阶石
         * @param equipment 新生成的装备
         */
        private MeltResult(int points, List<AdventureEquipment> equipment, Map<Integer,Long> stones) {
            this.stones=stones;
            this.points = points;
            this.equipment = equipment;
        }
    }

    /**
     * 按底包装备模板的品质和需求等级统计已穿戴件数。
     *
     * @param roleId 角色编号
     * @param quality 最低品质
     * @param level 最低模板等级
     * @return 达标的已穿戴件数
     */
    public long qualifyingEquipment(String roleId, int quality, int level) {
        return equipped(roleId).stream().map(item -> meltRules.get(item.configId))
                .filter(rule -> rule != null && rule[5] >= quality && rule[4] >= level).count();
    }

    /**
     * 读取各部位已佩戴装备。
     *
     * @param roleId 角色编号
     * @return 按部位排序的穿戴状态
     */
    public List<AdventureEquipment> equipped(String roleId) {
        return mapper.equipped(roleId);
    }

    /**
     * 装上已持有的装备，原装备回到背包并保留部位的强化等级。
     *
     * @param roleId 角色编号
     * @param equipmentId 选择的装备实例编号
     * @return 是否完成装备替换
     */
    @Transactional
    public boolean wear(String roleId, String equipmentId) {
        roles.lockRole(roleId);
        if (equipmentId == null || equipmentId.length() > 36) {
            return false;
        }
        AdventureEquipment equipment = mapper.ownedEquipment(roleId, equipmentId);
        if (equipment == null) {
            return false;
        }
        AdventureEquipment before = atSlot(roleId, equipment.slot);
        int level = before == null ? 0 : before.level;
        if (before != null && !before.id.equals(equipmentId)) {
            mapper.clearPreview(roleId);
        }
        mapper.saveEquipped(roleId, new AdventureEquipment(equipment.id, equipment.configId, equipment.slot, level));
        return true;
    }

    /**
     * 按底包费用提升装备部位一级。
     *
     * @param roleId 角色编号
     * @param slot 强化部位
     * @param auto 是否按客户端预览用钻石补足费用
     * @return 是否扣费并提升一级
     */
    @Transactional
    public boolean enhance(String roleId, int slot, boolean auto) {
        LocalRole role = roles.lockRole(roleId);
        AdventureEquipment equipment = atSlot(roleId, slot);
        if (equipment == null) {
            return false;
        }
        int next = equipment.level + 1;
        int[] cost = fees.get(next);
        if (cost == null || next > role.level) {
            return false;
        }
        long coins = cost[0];
        long stones = cost[1];
        long diamonds = 0;
        if (auto) {
            long available = Math.min(inventory.count(roleId, ENHANCE_STONE), stones);
            diamonds = (stones - available) * DIAMONDS_PER_STONE;
            stones = available;
            if (role.coins < coins) {
                diamonds += ((coins + COINS_PER_BUNDLE - 1) / COINS_PER_BUNDLE) * DIAMONDS_PER_BUNDLE;
                coins = 0;
            }
        }
        if (!inventory.spendMaterials(roleId, ENHANCE_STONE, stones, coins, diamonds)) {
            return false;
        }
        mapper.saveEquipped(roleId, new AdventureEquipment(equipment.id, equipment.configId, slot, next));
        return true;
    }

    /**
     * 获取指定部位正在使用的装备。
     *
     * @param roleId 角色编号
     * @param slot 装备部位
     * @return 当前装备，无装备时为空
     */
    private AdventureEquipment atSlot(String roleId, int slot) {
        return equipped(roleId).stream().filter(item -> item.slot == slot).findFirst().orElse(null);
    }
}
