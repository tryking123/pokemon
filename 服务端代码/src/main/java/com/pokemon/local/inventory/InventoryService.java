package com.pokemon.local.inventory;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.activitydaily.DailyVitalityEvents;
import com.pokemon.local.elf.ElfGearService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.home.HomeRaceService;
import com.pokemon.local.inventory.api.entity.InventoryItem;
import com.pokemon.local.inventory.api.entity.SystemMail;
import com.pokemon.local.inventory.api.entity.SystemMailReward;
import com.pokemon.local.inventory.api.vo.MailClaimResult;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 单玩家背包、普通商城与系统邮件 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 18:38
 */
@Service
public class InventoryService {
    /** 普通背包初始可用格子数 */
    public static final int CAPACITY = 100;
    private static final long OVERFLOW_EXPIRY = 2147483647L;
    private static final int MAX_ORDER_QUANTITY = 999;
    private static final int LIMIT_SHOP = 138;
    private static final int QUICK_SHOP = 3501;
    private static final int HOME_SHOP = 343;
    private static final int HOME_COIN = 118;
    private static final int BALL = 21014;
    private static final int ADVANCED_BALL = 21015;
    // discount_proto，依次为道具、份量、原价、折扣、个人限购、刷新类型。
    private static final int[][] DISCOUNTS = {
            {21035, 10, 10000, 5, 3, 1}, {31022, 5, 5000, 5, 2, 1},
            {20003, 20, 10000, 7, 3, 1}, {34031, 5, 5000, 7, 3, 1}, {34148, 5, 20000, 7, 3, 1}
    };
    // homeshop_proto，依次为道具、家园币价格和限购数量。
    private static final int[][] HOME_GOODS = {
            {30011, 200, 500}, {30012, 100, 1000}, {30013, 20000, 10}, {23033, 3000, 100},
            {23034, 3000, 250}, {23035, 5000, 150}, {23036, 200000, 10}, {23037, 50000, 30},
            {23038, 50000, 30}, {23039, 50000, 30}, {23040, 50000, 30}, {23041, 200000, 10},
            {23042, 1000, 30}, {23043, 1000, 30}, {23044, 100, 200}, {23045, 2000, 30}
    };
    private final InventoryMapper mapper;
    private final BagCapacityService capacity;
    private final ElfService elfs;
    private final ElfGearService gear;
    private final ObjectProvider<HomeRaceService> dressInventory;
    private final DailyVitalityEvents vitalityEvents;
    private final ObjectProvider<com.pokemon.local.guild.PersonalGuildService> guildInventory;
    private final Map<Integer, Map<String, Integer>> items;
    private final Map<Integer, ShopProduct> catalog;
    private final Map<Integer, Integer> synthesisCosts;
    private final Map<Integer, BookRecipe> bookRecipes;

    /**
     * 加载与固定客户端版本一致的道具及商城数据
     *
     * @param mapper 角色库存和邮件访问对象
     * @param capacity 当前角色的已购背包容量
     * @param json 配置解析器
     * @param elfs 邮件精灵发放
     * @param gear 商店携带品发放
     * @param dressInventory 时装碎片账本
     * @param vitalityEvents 当日活跃积分账本
     * @param guildInventory 超古代石盘碎片资产入口
     * @throws IOException 配置无法读取
     */
    InventoryService(InventoryMapper mapper, BagCapacityService capacity, ObjectMapper json,
                     @Lazy ElfService elfs, ElfGearService gear, ObjectProvider<HomeRaceService> dressInventory, DailyVitalityEvents vitalityEvents,
                     ObjectProvider<com.pokemon.local.guild.PersonalGuildService> guildInventory)
            throws IOException {
        this.mapper = mapper;
        this.capacity = capacity;
        this.elfs = elfs;
        this.gear = gear;
        this.dressInventory = dressInventory;
        this.vitalityEvents = vitalityEvents;
        this.guildInventory = guildInventory;
        try (InputStream data = new ClassPathResource("inventory/items.json").getInputStream()) {
            items = json.readValue(data, new TypeReference<Map<Integer, Map<String, Integer>>>() { });
        }
        try (InputStream data = new ClassPathResource("inventory/shop.json").getInputStream()) {
            catalog = json.readValue(data, new TypeReference<Map<Integer, ShopProduct>>() { });
        }
        try (InputStream data = new ClassPathResource("inventory/elf-chip-synthesis.json").getInputStream()) {
            synthesisCosts = json.readValue(data, new TypeReference<Map<Integer, Integer>>() { });
        }
        try (InputStream data = new ClassPathResource("inventory/dynamax-recipes.json").getInputStream()) {
            bookRecipes = json.readValue(data, new TypeReference<Map<Integer, BookRecipe>>() { });
        }
    }

    /**
     * 判断普通背包堆叠是否属于独立的极巨技能书模板。
     * @param id 背包模板编号
     * @return 原客户端需要使用技能书分类时为真
     */
    public boolean isDynamaxBook(int id) {
        return com.pokemon.local.elf.DynamaxRules.isBook(id);
    }

    /** 按消耗后真实背包容量一次性处理极巨化费用和整书入包。
     * @param roleId 角色编号
     * @param materials 需要消耗的材料与原书
     * @param diamonds 钻石消耗
     * @param book 需要放入的新书，零表示仅扣费
     * @return 原协议错误码
     */
    @Transactional
    public int exchangeDynamaxBook(String roleId, Map<Integer,Long> materials, int diamonds, int book) {
        mapper.lockWallet(roleId);
        if (book!=0) {
            if (!isDynamaxBook(book)) { return 100021; }
            long held=count(roleId,book);
            if (held>=Integer.MAX_VALUE) { return 100015; }
            if (held==0) {
                long emptied=materials.entrySet().stream().filter(e->count(roleId,e.getKey())==e.getValue()).count();
                if (ordinaryUsed(roleId)-emptied>=ordinaryCapacity(roleId)) { return 100015; }
            }
        }
        if (!spendMaterials(roleId,materials,0,diamonds)) { return 100012; }
        if (book!=0) { addStack(roleId,book,1); }
        return 0;
    }

    /**
     * 使用圣诞麋鹿宝箱兑换冰系高级技能残片，按同一请求去重。
     * @param roleId 角色编号
     * @param quantity 一次开启的宝箱数量
     * @param option 原客户端选项，直接开箱时为零
     * @param key 当前会话请求标识
     * @return 直接入包数量，负一为已结算，其他负值对应原错误码
     */
    @Transactional
    public long openReindeerGift(String roleId, long quantity, long option, String key) {
        if (quantity <= 0 || quantity > Integer.MAX_VALUE || option != 0) return -100021;
        mapper.lockWallet(roleId);
        String receipt = "reindeer-gift:" + key;
        if (mapper.grantedReward(roleId, receipt)) return -1;
        if (!spendMaterials(roleId, 34700, quantity, 0, 0)) return -100051;
        return grantItemsOrMail(roleId, receipt, Collections.singletonMap(8506, quantity)).getOrDefault(8506, 0L);
    }

    /**
     * 使用配方对应的残卷、进化石和钻石合成一本极巨技能书。
     * @param roleId 当前角色编号
     * @param id 目标技能书编号
     * @param requestKey 同一连接内的请求标识
     * @return 原协议错误码，零表示已合成
     */
    @Transactional
    public int composeBook(String roleId, long id, String requestKey) {
        BookRecipe recipe = id > 0 && id <= Integer.MAX_VALUE ? bookRecipes.get((int) id) : null;
        if (recipe == null) {
            return 100021;
        }
        mapper.lockWallet(roleId);
        String receipt = "dyna-book:" + requestKey;
        if (mapper.grantedReward(roleId, receipt)) {
            return 100011;
        }
        long held = count(roleId, (int) id);
        if (held >= Integer.MAX_VALUE) {
            return 100015;
        }
        if (held == 0) {
            long emptied = recipe.materials.entrySet().stream()
                    .filter(e -> count(roleId, e.getKey()) == e.getValue()).count();
            if (ordinaryUsed(roleId) - emptied >= ordinaryCapacity(roleId)) {
                return 100015;
            }
        }
        if (!spendMaterials(roleId, recipe.materials, 0, recipe.diamonds)) {
            return 100012;
        }
        addStack(roleId, (int) id, 1);
        mapper.recordRewardGrant(roleId, receipt);
        return 0;
    }

    /**
     * 原版极巨技能书单次合成配方。
     * @author tongruiyuan
     * @since 2026-11-20 10:46
     */
    private static final class BookRecipe {
        /** 残卷、进化石的模板编号和所需数量。 */
        public Map<Integer, Long> materials;
        /** 每次合成消耗的钻石。 */
        public long diamonds;
    }

    /**
     * 查询普通背包中的全部堆叠
     *
     * @param roleId 角色编号
     * @return 背包明细
     */
    public List<InventoryItem> snapshot(String roleId) {
        return mapper.items(roleId);
    }

    /**
     * 获取角色的普通背包容量。
     *
     * @param roleId 角色编号
     * @return 服务器保存的总格数
     */
    public int ordinaryCapacity(String roleId) {
        return capacity.ordinary(roleId);
    }

    /**
     * 统计普通堆叠和未穿戴训练家装备占用的普通背包格。
     *
     * @param roleId 角色编号
     * @return 普通背包的当前使用格数
     */
    public int ordinaryUsed(String roleId) {
        return mapper.ordinarySlots(roleId);
    }

    /**
     * 读取当前角色扩容后的精灵背包。
     *
     * @param roleId 角色编号
     * @return 服务器保存的精灵总格数
     */
    public int elfCapacity(String roleId) {
        return capacity.elves(roleId);
    }

    /**
     * 查询已知模板的显示类型
     *
     * @param itemId 道具编号
     * @return 原配置中的道具类型
     */
    public int itemType(int itemId) {
        return template(itemId).get("type");
    }

    /**
     * 查询背包中同类道具的堆叠数量
     *
     * @param roleId 角色编号
     * @param itemId 道具编号
     * @return 存有的道具数
     */
    public long count(String roleId, int itemId) {
        return mapper.item(roleId, itemId).map(item -> item.quantity).orElse(0L);
    }

    /**
     * 核对候选道具确有模板且可以放入普通背包。
     *
     * @param roleId 当前角色编号
     * @param itemId 待发放道具编号
     * @param quantity 待发放数量
     * @return 当前存档可以接收该道具
     */
    public boolean canGrantItem(String roleId, int itemId, long quantity) {
        return canAdd(roleId, itemId, quantity);
    }

    /**
     * 检查消耗礼包后的背包是否能容纳发放的普通道具。
     *
     * @param roleId 角色编号
     * @param spent 已校验持有数量的消耗道具
     * @param amount 消耗数量
     * @param earned 奖励道具编号
     * @param quantity 奖励数量
     * @return 消耗道具后的背包可以接收奖励时为真
     */
    boolean canReceiveAfterUse(String roleId, int spent, long amount, int earned, long quantity) {
        if (!items.containsKey(earned) || quantity <= 0 || quantity > Integer.MAX_VALUE) {
            return false;
        }
        if (earned == 1001 || earned == 1002) {
            return true;
        }
        long current = count(roleId, earned) - (spent == earned ? amount : 0);
        if (current > 0) {
            return current <= Integer.MAX_VALUE - quantity;
        }
        return count(roleId, spent) == amount || ordinaryUsed(roleId) < ordinaryCapacity(roleId);
    }

    /**
     * 在角色锁内判断精灵碎片奖励是否会超出保存上限。
     *
     * @param roleId 角色编号
     * @param configId 精灵配置编号
     * @param quantity 发放件数
     * @return 在有效配置及保存上限内时为真
     */
    public boolean canGrantChip(String roleId, int configId, long quantity) {
        return configId > 0 && quantity > 0 && quantity <= Integer.MAX_VALUE - mapper.chipCount(roleId, configId);
    }

    /**
     * 在角色事务中发放精灵碎片并合并已有堆叠。
     *
     * @param roleId 角色编号
     * @param configId 精灵配置编号
     * @param quantity 发放件数
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantChip(String roleId, int configId, long quantity) {
        mapper.lockWallet(roleId);
        if (!canGrantChip(roleId, configId, quantity)) {
            throw new IllegalArgumentException("无效的精灵碎片奖励");
        }
        mapper.addChips(roleId, configId, quantity);
    }

    /**
     * 从图鉴消耗原表指定数量的精灵碎片，生成一只同模板精灵并登记取得记录。
     * @param roleId 角色编号
     * @param configId 碎片模板编号
     * @param requestKey 当前连接的请求编号
     * @return 原协议错误码，零表示合成成功
     */
    @Transactional
    public int synthesizeElf(String roleId, int configId, String requestKey) {
        Integer need = synthesisCosts.get(configId);
        if (need == null || need <= 0) {
            return 100021;
        }
        mapper.lockWallet(roleId);
        String receipt = "elf-chip:" + requestKey;
        if (mapper.grantedReward(roleId, receipt)) {
            return 100011;
        }
        if (elfs.list(roleId).size() >= elfCapacity(roleId)) {
            return 100015;
        }
        if (!mapper.consumeChips(roleId, configId, need)) {
            return 100012;
        }
        elfs.grant(roleId, configId);
        mapper.recordRewardGrant(roleId, receipt);
        return 0;
    }

    /**
     * 获取角色的家园币余额。
     *
     * @param roleId 角色编号
     * @return 当前可使用的家园币
     */
    public long homeCoins(String roleId) {
        return mapper.currency(roleId, HOME_COIN);
    }

    /**
     * 查询以原货币类型独立保存的余额。
     * @param roleId 当前角色
     * @param type 竞技点、时装币等原资产类型
     * @return 对应钱包余额
     */
    public long currencyBalance(String roleId, int type) {
        return mapper.currency(roleId, type);
    }

    /**
     * 在道具使用事务中将实体时装币计入原时装钱包。
     * @param roleId 已认证角色编号
     * @param quantity 本次增加的时装币数量
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantFashionCoins(String roleId, long quantity) {
        if (quantity <= 0) throw new IllegalArgumentException("时装币数量必须为正数");
        mapper.lockWallet(roleId);
        mapper.saveCurrency(roleId, 125, Math.addExact(mapper.currency(roleId, 125), quantity));
    }

    /**
     * 在玩法或订单事务内发放家园币。
     *
     * @param roleId 角色编号
     * @param quantity 发放数量
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grantHomeCoins(String roleId, long quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("家园币发放数量必须为正数");
        }
        mapper.lockWallet(roleId);
        mapper.saveCurrency(roleId, HOME_COIN, Math.addExact(homeCoins(roleId), quantity));
    }

    /**
     * 原子消耗一件由玩法确认可用的道具
     *
     * @param roleId 角色编号
     * @param itemId 道具编号
     * @return 是否成功消耗
     */
    @Transactional
    public boolean takeOne(String roleId, int itemId) {
        mapper.lockWallet(roleId);
        Optional<InventoryItem> present = mapper.item(roleId, itemId);
        if (!present.isPresent()) {
            return false;
        }
        mapper.updateItem(roleId, itemId, present.get().quantity - 1);
        return true;
    }

    /**
     * 在角色事务内扣除一类材料以及金币和钻石。
     *
     * @param roleId 角色编号
     * @param itemId 材料模板编号
     * @param quantity 需要扣除的材料数
     * @param coins 需要扣除的金币
     * @param diamonds 需要扣除的钻石
     * @return 资产充足且已扣除时为真
     */
    @Transactional
    public boolean spendMaterials(String roleId, int itemId, long quantity, long coins, long diamonds) {
        return spendMaterials(roleId, Collections.singletonMap(itemId, quantity), coins, diamonds);
    }

    /**
     * 同时校验和扣除多类材料与货币，所有条件满足才更新存档。
     *
     * @param roleId 角色编号
     * @param materials 材料模板编号及消耗数量
     * @param coins 需要扣除的金币
     * @param diamonds 需要扣除的钻石
     * @return 资产充足且已扣除时为真
     */
    @Transactional
    public boolean spendMaterials(String roleId, Map<Integer, Long> materials, long coins, long diamonds) {
        long[] wallet = mapper.lockWallet(roleId);
        if (coins < 0 || diamonds < 0 || wallet[0] < coins || wallet[1] < diamonds) {
            return false;
        }
        Map<Integer, Long> remaining = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> material : materials.entrySet()) {
            long quantity = material.getValue();
            if (quantity < 0) {
                return false;
            }
            if (quantity == 0) {
                continue;
            }
            long present = count(roleId, material.getKey());
            if (present < quantity) {
                return false;
            }
            remaining.put(material.getKey(), present - quantity);
        }
        remaining.forEach((item, quantity) -> mapper.updateItem(roleId, item, quantity));
        if (coins != 0 || diamonds != 0) {
            mapper.settleWallet(roleId, wallet[0] - coins, wallet[1] - diamonds, diamonds);
        }
        return true;
    }

    /**
     * 出售底包允许出售的背包道具并立即结算金币。
     *
     * @param roleId 角色编号
     * @param itemId 道具模板编号
     * @param quantity 出售数量
     * @return 成功时的成交数量和结算结果
     */
    @Transactional
    public Optional<Purchase> sell(String roleId, long itemId, long quantity) {
        Map<String, Integer> item = itemId > 0 && itemId <= Integer.MAX_VALUE ? items.get((int) itemId) : null;
        if (item == null || item.get("sell") != 1 || item.getOrDefault("sellCoins", 0) <= 0
                || quantity <= 0 || quantity > Integer.MAX_VALUE) {
            return Optional.empty();
        }
        long[] wallet = mapper.lockWallet(roleId);
        long present = count(roleId, (int) itemId);
        long proceeds = item.get("sellCoins") * quantity;
        if (present < quantity || wallet[0] > Long.MAX_VALUE - proceeds) {
            return Optional.empty();
        }
        mapper.updateItem(roleId, (int) itemId, present - quantity);
        mapper.updateWallet(roleId, wallet[0] + proceeds, wallet[1]);
        return Optional.of(new Purchase((int) itemId, item.get("type"), quantity, wallet[0] + proceeds, wallet[1]));
    }

    /**
     * 在业务事务中发放背包奖励
     *
     * @param roleId 角色编号
     * @param itemId 原版配置中的道具编号
     * @param quantity 授予数量
     */
    @Transactional
    public void grant(String roleId, int itemId, long quantity) {
        long[] money = mapper.lockWallet(roleId);
        if (quantity <= 0) {
            throw new IllegalArgumentException("道具发放数量必须为正数");
        }
        if (itemId == 1001) {
            mapper.updateWallet(roleId, Math.addExact(money[0], quantity), money[1]);
        } else if (itemId == 1002) {
            mapper.updateWallet(roleId, money[0], Math.addExact(money[1], quantity));
        } else {
            addItem(roleId, itemId, quantity);
        }
    }

    /**
     * 按原版商品配置完成普通商城交易
     *
     * @param roleId 角色编号
     * @param index 原版普通商城档位
     * @param quantity 购买份数
     * @return 成功时的成交结果
     */
    @Transactional
    public Optional<Purchase> purchase(String roleId, long index, long quantity) {
        ShopProduct product = index > 0 && index <= Integer.MAX_VALUE ? catalog.get((int) index) : null;
        if (product == null || quantity <= 0 || quantity > MAX_ORDER_QUANTITY || product.costtype != 1
                || product.limit != 0 || product.discount != 0) {
            return Optional.empty();
        }
        return settlePurchase(roleId, mapper.lockWallet(roleId), product.goods, product.goodstype,
                product.num * quantity, 0, product.costnum * quantity);
    }

    /**
     * 返回限时商城商品、折扣和当前周期购买数量。
     *
     * @param roleId 角色编号
     * @param period 当前活动周期起点
     * @return 可由原客户端展示的商品列表
     */
    public List<Map<String, Integer>> discountShop(String roleId, long period) {
        Map<Integer, Long> purchases = mapper.purchases(roleId, LIMIT_SHOP, period);
        List<Map<String, Integer>> result = new ArrayList<>(DISCOUNTS.length);
        for (int index = 1; index <= DISCOUNTS.length; index++) {
            int[] product = DISCOUNTS[index - 1];
            Map<String, Integer> row = new LinkedHashMap<>();
            row.put("goods", product[0]);
            row.put("goodstype", 1);
            row.put("num", product[1]);
            row.put("costnum", product[2]);
            row.put("killmoney", product[3]);
            row.put("personal", product[4]);
            row.put("refresh", product[5]);
            row.put("mytimes", purchases.getOrDefault(index, 0L).intValue());
            result.add(row);
        }
        return result;
    }

    /**
     * 结算一份限时商城商品并记录角色在当前活动周期的已购次数。
     *
     * @param roleId 角色编号
     * @param period 当前活动周期起点
     * @param index 一基商品档位
     * @return 成功时的成交结果
     */
    @Transactional
    public Optional<Purchase> purchaseDiscount(String roleId, long period, long index) {
        if (index < 1 || index > DISCOUNTS.length) {
            return Optional.empty();
        }
        long[] money = mapper.lockWallet(roleId);
        int[] product = DISCOUNTS[(int) index - 1];
        long previous = mapper.purchases(roleId, LIMIT_SHOP, period).getOrDefault((int) index, 0L);
        if (previous >= product[4]) {
            return Optional.empty();
        }
        Optional<Purchase> purchase = settlePurchase(roleId, money, product[0], 1, product[1], 0,
                product[2] * product[3] / 10L);
        if (purchase.isPresent()) {
            mapper.addPurchase(roleId, LIMIT_SHOP, period, (int) index, previous, 1);
        }
        return purchase;
    }

    /**
     * 查询快速购买中两个精灵球商品的当日成交数量。
     *
     * @param roleId 角色编号
     * @param day 北京时间自然日
     * @return 道具编号和已购数量
     */
    public Map<Integer, Long> quickPurchases(String roleId, long day) {
        return mapper.purchases(roleId, QUICK_SHOP, day);
    }

    /**
     * 购买背包快捷入口销售的精灵球并累计当天购买数。
     *
     * @param roleId 角色编号
     * @param day 北京时间自然日
     * @param item 快速购买道具编号
     * @param amount 购买数量
     * @return 成功时的成交结果
     */
    @Transactional
    public Optional<Purchase> purchaseQuick(String roleId, long day, long item, long amount) {
        if ((item != BALL && item != ADVANCED_BALL) || amount <= 0 || amount > MAX_ORDER_QUANTITY) {
            return Optional.empty();
        }
        long[] money = mapper.lockWallet(roleId);
        long previous = quickPurchases(roleId, day).getOrDefault((int) item, 0L);
        if (previous + amount > MAX_ORDER_QUANTITY) {
            return Optional.empty();
        }
        Optional<Purchase> purchase = settlePurchase(roleId, money, (int) item, 1, amount,
                item == BALL ? 400000 * amount : 0, item == ADVANCED_BALL ? 2000 * amount : 0);
        if (purchase.isPresent()) {
            mapper.addPurchase(roleId, QUICK_SHOP, day, (int) item, previous, amount);
        }
        return purchase;
    }

    /**
     * 按 carryitemshop_proto 的 38 个档位购买携带品实例。
     *
     * @param roleId 角色编号
     * @param vip 当前角色的 VIP 等级
     * @param index 一基商品档位
     * @param amount 购买数量
     * @return 已提交的携带品和钱包余额
     */
    @Transactional
    public Optional<Purchase> purchaseCarry(String roleId, int vip, long index, long amount) {
        if (index < 1 || index > 38 || amount <= 0 || amount > MAX_ORDER_QUANTITY || (index > 20 && vip < 3)) {
            return Optional.empty();
        }
        int item = index <= 20 ? 20000 + (int) index : 30000 + (int) index - 20;
        long cost = 999999 * amount;
        long[] money = mapper.lockWallet(roleId);
        if (money[1] < cost || !gear.canGrantGear(roleId, ChoiceGiftService.CARRY, item, amount)) {
            return Optional.empty();
        }
        gear.grantGear(roleId, ChoiceGiftService.CARRY, item, amount);
        mapper.settleWallet(roleId, money[0], money[1] - cost, cost);
        return Optional.of(new Purchase(item, ChoiceGiftService.CARRY, amount, money[0], money[1] - cost));
    }

    /**
     * 查询家园商店各商品的本周购买次数。
     *
     * @param roleId 角色编号
     * @param week 当前周的北京时间周期起点
     * @return 商品档位及其购买数量
     */
    public Map<Integer, Long> homePurchases(String roleId, long week) {
        return mapper.purchases(roleId, HOME_SHOP, week);
    }

    /**
     * 用家园币购买底包商品并记录本周成交量。
     *
     * @param roleId 角色编号
     * @param week 当前周的北京时间周期起点
     * @param index 一基商品档位
     * @param amount 本次购买数量
     * @return 已购买并发放时为真
     */
    @Transactional
    public boolean purchaseHome(String roleId, long week, long index, long amount) {
        if (index < 1 || index > HOME_GOODS.length || amount <= 0 || amount > HOME_GOODS[(int) index - 1][2]) {
            return false;
        }
        mapper.lockWallet(roleId);
        int[] product = HOME_GOODS[(int) index - 1];
        long previous = homePurchases(roleId, week).getOrDefault((int) index, 0L);
        long price = product[1] * amount;
        long balance = homeCoins(roleId);
        if (previous + amount > product[2] || balance < price || !canAdd(roleId, product[0], amount)) {
            return false;
        }
        addItem(roleId, product[0], amount);
        mapper.saveCurrency(roleId, HOME_COIN, balance - price);
        mapper.addPurchase(roleId, HOME_SHOP, week, (int) index, previous, amount);
        return true;
    }

    /**
     * 在角色锁内校验购买成本和背包空间，成交后返回最新钱包。
     *
     * @param roleId 角色编号
     * @param money 当前钱包的金币和钻石
     * @param goods 售出的道具编号
     * @param type 协议奖励类型
     * @param amount 发放数量
     * @param coinCost 应扣金币
     * @param diamondCost 应扣钻石
     * @return 成功时的成交结果
     */
    private Optional<Purchase> settlePurchase(String roleId, long[] money, int goods, int type, long amount,
                                              long coinCost, long diamondCost) {
        if (money[0] < coinCost || money[1] < diamondCost) {
            return Optional.empty();
        }
        long coins = money[0] - coinCost;
        long diamonds = money[1] - diamondCost;
        if (goods == 1001) {
            coins = Math.addExact(coins, amount);
        } else if (goods == 1002) {
            diamonds = Math.addExact(diamonds, amount);
        } else if (canAdd(roleId, goods, amount)) {
            addItem(roleId, goods, amount);
        } else {
            return Optional.empty();
        }
        mapper.settleWallet(roleId, coins, diamonds, diamondCost);
        return Optional.of(new Purchase(goods, type, amount, coins, diamonds));
    }

    /**
     * 获取邮箱角标，避免游戏心跳加载所有历史附件。
     * @param roleId 当前角色
     * @return 尚未读完或领完的邮件数量
     */
    public long pendingMailCount(String roleId) {
        return mapper.pendingMailCount(roleId, Instant.now().getEpochSecond());
    }

    /**
     * 查询当前角色的有效系统邮件
     *
     * @param roleId 角色编号
     * @return 可见的系统邮件
     */
    public List<SystemMail> mail(String roleId) {
        return mapper.mail(roleId, Instant.now().getEpochSecond());
    }

    /**
     * 在业务结算事务中保存系统邮件及可实际领取的原版奖励
     *
     * @param roleId 领取角色
     * @param id 由业务活动确定的唯一邮件编号
     * @param subject 邮件主题
     * @param body 邮件正文
     * @param createdAt 创建 Unix 秒
     * @param expiresAt 过期 Unix 秒
     * @param fields 类型、编号、数量依次重复的原版奖励列表
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void createRewardMail(String roleId, String id, String subject, String body, long createdAt,
                                 long expiresAt, List<Integer> fields) {
        if (fields.isEmpty() || fields.size() % 3 != 0) {
            throw new IllegalArgumentException("邮件奖励配置不完整");
        }
        List<SystemMailReward> rewards = new ArrayList<>();
        for (int index = 0; index < fields.size(); index += 3) {
            SystemMailReward reward = new SystemMailReward(fields.get(index), fields.get(index + 1),
                    fields.get(index + 2));
            boolean item = reward.type == SystemMailReward.ITEM && items.containsKey(reward.configId);
            boolean elf = reward.type == SystemMailReward.ELF && reward.quantity == 1
                    && FirstBattleData.starter(reward.configId) != null;
            if (reward.quantity <= 0 || (!item && !elf)) {
                throw new IllegalArgumentException("未知邮件附件 " + reward.configId);
            }
            rewards.add(reward);
        }
        mapper.createMail(roleId, id, subject, body, createdAt, expiresAt, rewards);
    }

    /**
     * 寄送原世界首领的道具、精灵碎片与幸运奖钻石。
     * @param roleId 收件角色
     * @param id 稳定的事件收据
     * @param subject 来源及奖项
     * @param fields 三元组附件
     * @param now 发信时间
     */
    @Transactional
    public void createWorldBossMail(String roleId,String id,String subject,List<Integer> fields,long now) {
        if (!mapper.grantMailBody(roleId,id).isEmpty()) return;
        List<SystemMailReward> entries=new ArrayList<>();
        long diamonds=0;
        for(int i=0;i<fields.size();i+=3) {
            int type=fields.get(i), sid=fields.get(i+1), qty=fields.get(i+2);
            if(qty<1 || type!=104 && type!=SystemMailReward.ELF_CHIP && (type!=SystemMailReward.ITEM || !items.containsKey(sid))) {
                throw new IllegalArgumentException("无效的世界首领奖励");
            }
            if(type==104) diamonds+=qty;
            else entries.add(new SystemMailReward(type,sid,qty));
        }
        mapper.createMail(roleId,id,subject,"请领取本次世界首领奖励。",now,now+2592000,entries);
        if(diamonds>0) mapper.setGrantWallet(roleId,id,104,diamonds);
    }

    /**
     * 查询玩法奖励是否已经发至背包或邮件。
     * @param roleId 角色编号
     * @param receipt 玩法奖励唯一回执
     * @return 是否已经发放
     */
    public boolean rewardGranted(String roleId, String receipt) {
        return mapper.grantedReward(roleId, receipt);
    }

    /**
     * 按业务回执发放普通道具，超出容量的部分原子转入一封系统邮件。
     *
     * @param roleId 当前角色编号
     * @param receipt 按玩法和奖励批次生成的唯一回执
     * @param rewards 本次应得的普通道具及数量
     * @return 本次实际直接放入背包的道具及数量，重放时为空
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<Integer, Long> grantItemsOrMail(String roleId, String receipt, Map<Integer, Long> rewards) {
        if (receipt == null || receipt.isEmpty() || receipt.length() > 128) {
            throw new IllegalArgumentException("业务回执编号无效");
        }
        if (rewards.isEmpty()) {
            return Collections.emptyMap();
        }
        mapper.lockWallet(roleId);
        if (mapper.grantedReward(roleId, receipt)) {
            return Collections.emptyMap();
        }
        List<SystemMailReward> overflow = new ArrayList<>();
        Map<Integer, Long> delivered = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> reward : rewards.entrySet()) {
            int id = reward.getKey();
            long amount = reward.getValue();
            if (id == 1001 || id == 1002 || !items.containsKey(id) || amount <= 0 || amount > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("无效的普通道具奖励 " + id);
            }
            long accepted = receiveItemQuantity(roleId, id, amount);
            if (accepted > 0) {
                delivered.put(id, accepted);
            }
            if (accepted < amount) {
                overflow.add(new SystemMailReward(SystemMailReward.ITEM, id, (int) (amount - accepted)));
            }
        }
        if (!overflow.isEmpty()) {
            UUID mailKey = UUID.nameUUIDFromBytes((roleId + ":" + receipt).getBytes(StandardCharsets.UTF_8));
            mapper.createMail(roleId, "overflow:" + mailKey, "背包溢出奖励",
                    "背包未能放入的奖励已保存在这里，清理背包后可领取剩余附件。",
                    Instant.now().getEpochSecond(), OVERFLOW_EXPIRY, overflow);
        }
        mapper.recordRewardGrant(roleId, receipt);
        return delivered;
    }

    /**
     * 标记一封有效系统邮件已读
     *
     * @param roleId 角色编号
     * @param id 邮件编号
     * @return 是否存在并可阅读
     */
    public boolean openMail(String roleId, String id) {
        return id != null && id.length() <= 64 && mapper.openMail(roleId, id, Instant.now().getEpochSecond());
    }

    /**
     * 按当前实际容量领取系统邮件附件，并原子保存资产和领取进度。
     *
     * @param roleId 角色编号
     * @param ids 要领取的邮件编号
     * @param requestKey 当前连接的请求编号
     * @return 完结邮件编号及本次实际到账的资产种类
     */
    @Transactional
    public MailClaimResult claimMail(String roleId, Set<String> ids, String requestKey) {
        if (ids.isEmpty()) {
            return new MailClaimResult(Collections.emptyList(), false, false, false, false, false);
        }
        long now = Instant.now().getEpochSecond();
        long[] money = mapper.lockWallet(roleId);
        Optional<String> receipt = mapper.mailClaimReceipt(roleId, requestKey);
        if (receipt.isPresent()) {
            List<String> previous = receipt.get().isEmpty() ? Collections.emptyList()
                    : Arrays.asList(receipt.get().split("\n"));
            return new MailClaimResult(previous, false, false, false, false, false);
        }
        List<String> completed = new ArrayList<>();
        boolean delivered = false;
        boolean elfDelivered = false;
        boolean dressChipDelivered = false;
        boolean gearDelivered = false;
        boolean tabletDelivered = false;
        int freeElfSlots = -1;
        for (SystemMail mail : mapper.mail(roleId, now)) {
            if (!ids.contains(mail.id) || mail.claimed) {
                continue;
            }
            money[0] = Math.addExact(money[0], mail.coins);
            money[1] = Math.addExact(money[1], mail.diamonds);
            long legacy = receiveItemQuantity(roleId, mail.itemId, mail.quantity);
            if (mail.dojoPoints > 0) {
                mapper.addDojoPoints(roleId, mail.dojoPoints);
            }
            boolean changed = mail.coins > 0 || mail.diamonds > 0 || mail.dojoPoints > 0 || legacy > 0;
            boolean complete = legacy == mail.quantity;
            for (SystemMailReward reward : mail.rewards) {
                long accepted;
                if (reward.type == SystemMailReward.ITEM) {
                    accepted = receiveItemQuantity(roleId, reward.configId, reward.quantity);
                } else if (reward.type == SystemMailReward.CARRY || reward.type == SystemMailReward.POWERSTONE) {
                    long used = gear.snapshot(roleId).stream()
                            .filter(item -> item.kind == reward.type && item.elfId == null).count();
                    accepted = Math.min(reward.quantity, Math.max(0, ElfGearService.BAG_CAPACITY - used));
                    if (accepted > 0) {
                        gear.grantGear(roleId, reward.type, reward.configId, accepted);
                        gearDelivered = true;
                    }
                } else if (reward.type == SystemMailReward.TABLET) {
                    guildInventory.getIfAvailable().claimTabletSupply(roleId, reward.configId, reward.quantity);
                    accepted = reward.quantity;
                    tabletDelivered = true;
                } else if (reward.type == SystemMailReward.ELF_CHIP) {
                    accepted = Math.min(reward.quantity, Integer.MAX_VALUE - mapper.chipCount(roleId, reward.configId));
                    if (accepted > 0) mapper.addChips(roleId, reward.configId, accepted);
                } else if (reward.type == SystemMailReward.VITALITY) {
                    vitalityEvents.record(roleId, DailyVitalityEvents.MAIL_POINTS, mail.id + ":" + reward.ordinal, reward.quantity);
                    accepted = reward.quantity;
                } else if (reward.currencyField() != null) {
                    long balance = mapper.currency(roleId, reward.type);
                    mapper.saveCurrency(roleId, reward.type, Math.addExact(balance, reward.quantity));
                    accepted = reward.quantity;
                } else if (reward.type == SystemMailReward.DRESS_CHIP) {
                    accepted = reward.quantity;
                    dressInventory.getIfAvailable().addDressChips(roleId, Collections.singletonMap(reward.configId, accepted));
                    dressChipDelivered = true;
                } else if (reward.type == SystemMailReward.ELF) {
                    if (freeElfSlots < 0) {
                        freeElfSlots = Math.max(0, elfCapacity(roleId) - elfs.list(roleId).size());
                    }
                    accepted = Math.min(reward.quantity, freeElfSlots);
                    for (int index = 0; index < accepted; index++) {
                        elfs.grant(roleId, reward.configId);
                    }
                    freeElfSlots -= (int) accepted;
                    elfDelivered |= accepted > 0;
                } else {
                    throw new IllegalStateException("无效的邮件附件类型");
                }
                if (accepted > 0) {
                    mapper.addAttachmentClaim(mail.id, reward.ordinal, accepted);
                    changed = true;
                }
                complete &= accepted == reward.quantity;
            }
            if (changed || complete) {
                mapper.saveMailClaim(roleId, mail.id, now, legacy, complete);
            }
            if (complete) {
                completed.add(mail.id);
            }
            delivered |= changed;
        }
        if (delivered) {
            mapper.updateWallet(roleId, money[0], money[1]);
        }
        mapper.recordMailClaim(roleId, requestKey, String.join("\n", completed));
        return new MailClaimResult(completed, delivered, elfDelivered, dressChipDelivered, gearDelivered, tabletDelivered);
    }

    /**
     * 将当前可以容纳的普通道具部分写入背包。
     *
     * @param roleId 已加锁的角色
     * @param id 道具编号
     * @param requested 待领取数量
     * @return 实际入库数量
     */
    private long receiveItemQuantity(String roleId, int id, long requested) {
        if (requested <= 0) {
            return 0;
        }
        Optional<InventoryItem> existing = mapper.item(roleId, id);
        long free = existing.map(item -> Integer.MAX_VALUE - item.quantity).orElse((long) Integer.MAX_VALUE);
        long accepted = Math.min(requested, free);
        if (accepted > 0 && canAdd(roleId, id, accepted)) {
            addItem(roleId, id, accepted);
            return accepted;
        }
        return 0;
    }

    /**
     * 在角色事务锁内校验一批道具可同时进入背包
     *
     * @param roleId 角色编号
     * @param grouped 已按编号合计的道具
     * @return 是否符合背包容量与堆叠限制
     */
    public boolean canReceive(String roleId, Map<Integer, Long> grouped) {
        if (grouped.isEmpty()) {
            return true;
        }
        int free = ordinaryCapacity(roleId) - ordinaryUsed(roleId);
        for (Map.Entry<Integer, Long> entry : grouped.entrySet()) {
            if (!canAdd(roleId, entry.getKey(), entry.getValue())) {
                return false;
            }
            if (!mapper.item(roleId, entry.getKey()).isPresent() && --free < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断物品堆叠或空位能否容纳新增道具
     *
     * @param roleId 角色编号
     * @param itemId 道具编号
     * @param add 新增数量
     * @return 是否可以入库
     */
    private boolean canAdd(String roleId, int itemId, long add) {
        if (itemId <= 0 || !items.containsKey(itemId) || add <= 0 || add > Integer.MAX_VALUE) {
            return false;
        }
        Optional<InventoryItem> existing = mapper.item(roleId, itemId);
        return existing.map(item -> item.quantity <= Integer.MAX_VALUE - add)
                .orElseGet(() -> ordinaryUsed(roleId) < ordinaryCapacity(roleId));
    }

    /**
     * 在角色已加锁的事务中增加或放入一组道具
     *
     * @param roleId 角色编号
     * @param itemId 道具编号
     * @param add 增加的道具数
     */
    private void addItem(String roleId, int itemId, long add) {
        if (!canAdd(roleId, itemId, add)) {
            throw new IllegalStateException("背包无法容纳该道具");
        }
        addStack(roleId, itemId, add);
    }

    /**
     * 向已校验容量的普通背包合并或新增一组物品。
     * @param roleId 已锁定的角色编号
     * @param itemId 物品或技能书模板编号
     * @param add 新增件数
     */
    private void addStack(String roleId, int itemId, long add) {
        Optional<InventoryItem> existing = mapper.item(roleId, itemId);
        if (existing.isPresent()) {
            mapper.updateItem(roleId, itemId, existing.get().quantity + add);
            return;
        }
        Set<Integer> occupied = new HashSet<>();
        mapper.items(roleId).forEach(item -> occupied.add(item.position));
        int position = 1;
        while (occupied.contains(position)) {
            position++;
        }
        mapper.insertItem(roleId, itemId, add, position);
    }

    /**
     * 取得已校验道具的模板
     *
     * @param itemId 道具编号
     * @return 配置字段
     */
    private Map<String, Integer> template(int itemId) {
        Map<String, Integer> item = items.get(itemId);
        if (item == null) {
            throw new IllegalArgumentException("未知的固定版本道具编号 " + itemId);
        }
        return item;
    }

    /**
     * 固定版本商城配置条目
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:38
     */
    private static class ShopProduct {
        public int goods;
        public String name;
        public int goodstype;
        public long costnum;
        public int costtype;
        public long num;
        public int limit;
        public int discount;
    }

    /**
     * 普通商城的提交结果和新余额
     *
     * @author tongruiyuan
     * @since 2026-11-18 18:38
     */
    public static final class Purchase {
        public final int item;
        public final int type;
        public final long quantity;
        public final long coins;
        public final long diamonds;

        /**
         * 保存已发生的交易
         *
         * @param item 道具编号
         * @param type 道具类型
         * @param quantity 实际购得数量
         * @param coins 金币余额
         * @param diamonds 钻石余额
         */
        private Purchase(int item, int type, long quantity, long coins, long diamonds) {
            this.item = item;
            this.type = type;
            this.quantity = quantity;
            this.coins = coins;
            this.diamonds = diamonds;
        }
    }
}
