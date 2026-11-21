package com.pokemon.offline.inventory;

import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.elf.ElfGearService;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.activitylogin.LoginActivityService;
import com.pokemon.offline.battle.AdventureStage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 结算有明确配置的背包自选礼包、经验卡和钻石券。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:05
 */
@Service
public class ChoiceGiftService {
    private static final int INVALID = 100021;
    private static final int LEVEL_TOO_LOW = 100048;
    private static final int NOT_ENOUGH_ITEMS = 100051;
    private static final int BAG_FULL = 100015;
    private static final int CANNOT_USE = 250503;
    public static final int ITEM = 1;
    public static final int ELF = 2;
    public static final int CHIP = 3;
    public static final int CARRY = 4;
    public static final int DIAMONDS = 104;

    /** 底包 item_proto 中 21033 至 21035 的单张精灵经验。 */
    private static final int[] ELF_EXP = {5000, 25000, 100000};
    private static final int FIRST_EXP_CARD = 21033;
    private final RoleService roles;
    private final InventoryService inventory;
    private final HangupService hangup;
    private final ElfService elfs;
    private final ElfGearService gear;
    private final AdventureProgressService adventure;
    private final LoginActivityService dresses;
    private final JsonNode randomGifts;
    private final com.pokemon.offline.home.BreedingService breeding;
    private final ObjectMapper json;
    private final Map<Integer, Map<Integer, int[]>> gifts = new HashMap<>();

    /**
     * 加载礼包模板和已接通的奖励选项。
     *
     * @param roles 角色锁和等级查询
     * @param inventory 材料扣除和奖励发放
     * @param hangup 精灵经验池
     * @param elfs 完整精灵的发放和容量校验
     * @param gear 原携带品实例的发放和容量校验
     * @param adventure 训练家装备发放
     * @param dresses 永久时装发放
     * @param json 随包随机礼包配置解析
     * @param breeding 宠物礼包发放
     * @throws IOException 无法读取自选礼包配置
     */
    ChoiceGiftService(RoleService roles, InventoryService inventory, HangupService hangup, ElfService elfs,
                      ElfGearService gear, AdventureProgressService adventure, LoginActivityService dresses,
                      ObjectMapper json, com.pokemon.offline.home.BreedingService breeding) throws IOException {
        this.roles = roles;
        this.inventory = inventory;
        this.hangup = hangup;
        this.elfs = elfs;
        this.gear = gear;
        this.adventure = adventure;
        this.dresses = dresses;
        this.breeding = breeding;
        this.json = json;
        try (java.io.InputStream input = new ClassPathResource("catalog/item-random.json").getInputStream()) {
            this.randomGifts = json.readTree(input);
        }
        ClassPathResource resource = new ClassPathResource("catalog/item-choice.csv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] row = line.split(",");
                int item = Integer.parseInt(row[0]);
                int option = Integer.parseInt(row[1]);
                gifts.computeIfAbsent(item, ignored -> new HashMap<>()).put(option, new int[]{
                        Integer.parseInt(row[2]), Integer.parseInt(row[3]), Integer.parseInt(row[4]), Integer.parseInt(row[5])});
            }
        }
    }

    /**
     * 校验原版道具使用请求，在同一事务内消耗库存及发放奖励。
     *
     * @param roleId 角色编号
     * @param item 道具模板编号
     * @param option 原版自选奖励编号，直接使用时为零
     * @param amount 本次使用数量
     * @return 已发放奖励或原客户端错误码
     */
    @Transactional
    public Grant use(String roleId, long item, long option, long amount) {
        if (item <= 0 || item > Integer.MAX_VALUE || option < 0 || option > Integer.MAX_VALUE
                || amount <= 0 || amount > Integer.MAX_VALUE) {
            return Grant.rejected(INVALID);
        }
        int itemId = (int) item;
        JsonNode random = randomGifts.path(Integer.toString(itemId));
        if (!random.isMissingNode()) {
            return option == 0 ? openRecipe(roleId, itemId, amount, random) : Grant.rejected(INVALID);
        }
        if (itemId >= FIRST_EXP_CARD && itemId < FIRST_EXP_CARD + ELF_EXP.length) {
            if (option != 0) {
                return Grant.rejected(INVALID);
            }
            if (!inventory.spendMaterials(roleId, itemId, amount, 0, 0)) {
                return Grant.rejected(NOT_ENOUGH_ITEMS);
            }
            long experience = amount * ELF_EXP[itemId - FIRST_EXP_CARD];
            long balance = hangup.addElfPool(roleId, experience);
            return new Grant(0, 0, 0, experience, balance, Collections.emptyList(), 0);
        }
        if (itemId == 31006 || itemId == 64159 || itemId == 64168) {
            if (option != 0) {
                return Grant.rejected(INVALID);
            }
            long diamonds = amount * (itemId == 31006 ? 1000 : 500);
            return grant(roleId, itemId, amount, DIAMONDS, 1002, diamonds, 0);
        }
        Map<Integer, int[]> choices = gifts.get(itemId);
        if (choices == null) {
            return Grant.rejected(CANNOT_USE);
        }
        int[] reward = choices.get((int) option);
        if (reward == null) {
            return Grant.rejected(INVALID);
        }
        return grant(roleId, itemId, amount, reward[0], reward[1], amount * reward[2], reward[3]);
    }

    /**
     * 校验指定奖励并在当前事务中扣除礼包和发放实物。
     *
     * @param roleId 当前角色编号
     * @param itemId 被消耗的道具编号
     * @param amount 消耗件数
     * @param type 奖励的底包类型
     * @param rewardId 奖励模板编号
     * @param quantity 奖励数量
     * @param requiredLevel 使用要求的角色等级
     * @return 已发放结果或库存、配置校验错误
     */
    private Grant grant(String roleId, int itemId, long amount, int type, int rewardId, long quantity,
                        int requiredLevel) {
        int level = roles.lockRole(roleId).level;
        if (level < requiredLevel) {
            return Grant.rejected(LEVEL_TOO_LOW);
        }
        if (inventory.count(roleId, itemId) < amount) {
            return Grant.rejected(NOT_ENOUGH_ITEMS);
        }
        if ((type == ELF || type == CHIP) && FirstBattleData.starter(rewardId) == null) {
            return Grant.rejected(CANNOT_USE);
        }
        if ((type == ITEM && !inventory.canReceiveAfterUse(roleId, itemId, amount, rewardId, quantity))
                || (type == CHIP && !inventory.canGrantChip(roleId, rewardId, quantity))
                || ((type == CARRY || type == 13) && !gear.canGrantGear(roleId, type, rewardId, quantity))
                || (type == 10 && quantity > inventory.ordinaryCapacity(roleId)
                    - inventory.snapshot(roleId).size() - adventure.equipment(roleId).size()
                    + (inventory.count(roleId, itemId) == amount ? 1 : 0))
                || (type == ELF && quantity > inventory.elfCapacity(roleId) - elfs.list(roleId).size())) {
            return Grant.rejected(BAG_FULL);
        }
        if (!inventory.spendMaterials(roleId, itemId, amount, 0, 0)) {
            return Grant.rejected(NOT_ENOUGH_ITEMS);
        }
        List<LocalElf> received = new ArrayList<>();
        if (type == CHIP) {
            inventory.grantChip(roleId, rewardId, quantity);
        } else if (type == CARRY || type == 13) {
            gear.grantGear(roleId, type, rewardId, quantity);
        } else if (type == 6) {
            dresses.grantDress(roleId, rewardId);
        } else if (type == 10) {
            for (int i = 0; i < quantity; i++) {
                adventure.grantEquipment(roleId, rewardId, AdventureStage.equipmentPosition(rewardId));
            }
        } else if (type == ELF) {
            for (int i = 0; i < quantity; i++) {
                received.add(elfs.grant(roleId, rewardId));
            }
        } else {
            inventory.grant(roleId, rewardId, quantity);
        }
        return new Grant(type, rewardId, quantity, 0, 0, received, 0);
    }

    /**
     * 根据原说明或已核定本地主题配方批量开箱，先整体检查再原子扣包和发奖。
     * @param roleId 当前角色编号
     * @param itemId 被使用的道具模板
     * @param amount 请求实际消耗数量
     * @param rule 原说明、多奖励及权重配方
     * @return 全部已到账奖励或失败原因
     */
    private Grant openRecipe(String roleId, int itemId, long amount, JsonNode rule) {
        com.pokemon.offline.role.api.entity.LocalRole role = roles.lockRole(roleId);
        long unit = rule.path("consumeUnit").asLong(1);
        if (unit <= 0 || amount % unit != 0 || amount / unit > 10000) return Grant.rejected(INVALID);
        if (role.level < rule.path("level").asInt()) return Grant.rejected(LEVEL_TOO_LOW);
        if (inventory.count(roleId, itemId) < amount) return Grant.rejected(NOT_ENOUGH_ITEMS);
        Map<String, long[]> plan = new java.util.LinkedHashMap<>();
        JsonNode outcomes = rule.path("outcomes");
        long totalWeight = 0;
        for (JsonNode entry : outcomes) totalWeight = Math.addExact(totalWeight, entry.path("weight").asLong());
        for (long box = 0; box < amount / unit; box++) {
            addPlan(plan, rule.path("fixed"));
            addPlan(plan, rule.path("sexRewards").path(Integer.toString(role.sex)));
            for (int draw = 0; draw < rule.path("draws").asInt(1) && totalWeight > 0; draw++) {
                long chosen = java.util.concurrent.ThreadLocalRandom.current().nextLong(totalWeight);
                for (JsonNode entry : outcomes) {
                    chosen -= entry.path("weight").asLong();
                    if (chosen < 0) { addPlan(plan, entry.path("rewards")); break; }
                }
            }
        }
        if (plan.isEmpty()) return Grant.rejected(CANNOT_USE);
        long slots = inventory.ordinaryUsed(roleId) - (inventory.count(roleId, itemId) == amount ? 1 : 0);
        Map<Integer, Long> gearTotals = new HashMap<>();
        Map<Integer, Integer> gearSamples = new HashMap<>();
        long elfTotal = 0;
        for (long[] entry : plan.values()) {
            int kind = (int) entry[0], id = (int) entry[1]; long n = entry[2];
            if (n <= 0 || kind != 103 && kind != 104 && n > Integer.MAX_VALUE) return Grant.rejected(INVALID);
            if (kind == ITEM) {
                if (id != 34278 && !inventory.canReceiveAfterUse(roleId, itemId, amount, id, n)) return Grant.rejected(BAG_FULL);
                if (id != 1001 && id != 1002 && id != 34278 && inventory.count(roleId, id) == (id == itemId ? amount : 0)) slots++;
            } else if (kind == 10) slots += n;
            else if (kind == ELF) { if (FirstBattleData.starter(id) == null) return Grant.rejected(CANNOT_USE); elfTotal += n; }
            else if (kind == CHIP && !inventory.canGrantChip(roleId, id, n)) return Grant.rejected(BAG_FULL);
            else if (kind == CARRY || kind == 13) {
                gearTotals.put(kind, gearTotals.getOrDefault(kind, 0L) + n); gearSamples.put(kind, id);
            } else if (kind != CHIP && kind != 6 && kind != 35 && kind != 103 && kind != 104 && kind != 125) return Grant.rejected(CANNOT_USE);
        }
        if (slots > inventory.ordinaryCapacity(roleId)
                || elfTotal > inventory.elfCapacity(roleId) - elfs.list(roleId).size()) return Grant.rejected(BAG_FULL);
        for (int kind : gearTotals.keySet())
            if (!gear.canGrantGear(roleId, kind, gearSamples.get(kind), gearTotals.get(kind))) return Grant.rejected(BAG_FULL);
        if (!inventory.spendMaterials(roleId, itemId, amount, 0, 0)) return Grant.rejected(NOT_ENOUGH_ITEMS);
        List<LocalElf> added = new ArrayList<>();
        Grant result = new Grant(0, 0, 0, 0, 0, added, 0);
        for (long[] entry : plan.values()) {
            int kind = (int) entry[0], id = (int) entry[1]; long n = entry[2];
            if (kind == ITEM && id != 34278) inventory.grant(roleId, id, n);
            else if (kind == 125 || kind == ITEM && id == 34278) {
                inventory.grantFashionCoins(roleId, n);
                result.currencies.put("fashescoins", result.currencies.getOrDefault("fashescoins", 0L) + n);
                continue;
            }
            else if (kind == CHIP) inventory.grantChip(roleId, id, n);
            else if (kind == CARRY || kind == 13) gear.grantGear(roleId, kind, id, n);
            else if (kind == 6) dresses.grantDress(roleId, id);
            else if (kind == 10) for (long i = 0; i < n; i++) adventure.grantEquipment(roleId, id, AdventureStage.equipmentPosition(id));
            else if (kind == ELF) for (long i = 0; i < n; i++) added.add(elfs.grant(roleId, id));
            else if (kind == 35) result.petRewards.addAll(json.convertValue(breeding.grantPets(roleId, id, (int)n), java.util.List.class));
            else if (kind == 103 || kind == 104) {
                roles.addCurrency(roleId, kind == 103 ? n : 0, kind == 104 ? n : 0, 0);
                String field = kind == 103 ? "coins" : "diamonds";
                result.currencies.put(field, result.currencies.getOrDefault(field, 0L) + n);
                continue;
            }
            result.randomRewards.add(new int[]{kind, id, (int)n});
        }
        return result;
    }

    /**
     * 合并一组已选中的奖励，保留每个资产类型和模板的总量。
     * @param plan 本次完整发放计划
     * @param rewards 原三元组数组
     */
    private static void addPlan(Map<String, long[]> plan, JsonNode rewards) {
        for (JsonNode row : rewards) {
            int kind = row.get(0).asInt(), id = row.get(1).asInt(); long n = row.get(2).asLong();
            long[] entry = plan.computeIfAbsent(kind + ":" + id, ignored -> new long[]{kind, id, 0});
            entry[2] = Math.addExact(entry[2], n);
        }
    }

    /**
     * 道具使用结果及已入账奖励。
     *
     * @author tongruiyuan
     * @since 2026-11-18 23:05
     */
    public static final class Grant {
        /** 发放的原版奖励类型。 */
        public final int type;
        /** 奖励道具的模板编号。 */
        public final int item;
        /** 按使用礼包数量发放的奖励数量。 */
        public final long quantity;
        /** 计入精灵经验池的经验。 */
        public final long elfExperience;
        /** 经验卡使用后的精灵经验池余额。 */
        public final long elfPool;
        /** 领取的精灵实例及对应配置。 */
        public final List<LocalElf> elfs;
        /** 原客户端使用错误码，零表示成功。 */
        public final int errno;
        /** 随机开箱逐件实际抽中的类型、模板和数量。 */
        public final List<int[]> randomRewards = new ArrayList<>();
        /** 多奖励配方中实际到账的金币与钻石。 */
        public final Map<String, Long> currencies = new HashMap<>();
        /** 多奖励配方中实际新增的宠物实例。 */
        public final List<Map<String, Object>> petRewards = new ArrayList<>();

        /**
         * 保存已实际发放的道具和数量。
         *
         * @param type 原版奖励类型
         * @param item 道具模板编号
         * @param quantity 发放数
         * @param elfExperience 本次发放的精灵经验
         * @param elfPool 发放后的经验池余额
         * @param elfs 实际领取的精灵
         * @param errno 原客户端错误码
         */
        private Grant(int type, int item, long quantity, long elfExperience, long elfPool, List<LocalElf> elfs, int errno) {
            this.type = type;
            this.item = item;
            this.quantity = quantity;
            this.elfExperience = elfExperience;
            this.elfPool = elfPool;
            this.elfs = elfs;
            this.errno = errno;
        }

        /**
         * 返回未修改库存的拒绝结果。
         *
         * @param errno 原客户端错误码
         * @return 拒绝结果
         */
        private static Grant rejected(int errno) {
            return new Grant(0, 0, 0, 0, 0, Collections.emptyList(), errno);
        }
    }
}
