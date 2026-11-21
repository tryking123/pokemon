package com.pokemon.offline.inventory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.arena.ArenaService;
import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 竞技积分和道馆积分商城按各自货架兑换普通物品与精灵碎片。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:16
 */
@Service
public class ArenaPointShopService {
    private static final int ITEM = 1;
    private static final int CHIP = 3;
    private static final int INVALID = 100021;
    private static final int NO_POINTS = 100012;
    private static final int NO_SPACE = 100015;
    private static final int REPEATED = 100011;
    private static final int MAX_BUNDLES = 999;

    private final ArenaService arena;
    private final RoleService roles;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;
    private final JsonNode goods;
    private final JsonNode dojoGoods;

    /**
     * 读取原版竞技积分货架，校验物品和碎片模板。
     *
     * @param arena 本地竞技训练的实际累计积分
     * @param roles 角色锁
     * @param inventory 背包和碎片库存
     * @param jdbc 竞技积分消费回执
     * @param json 配置解析器
     * @throws IOException 货架资源无法读取
     */
    ArenaPointShopService(ArenaService arena, RoleService roles, InventoryService inventory, JdbcTemplate jdbc,
                          ObjectMapper json) throws IOException {
        this.arena = arena;
        this.roles = roles;
        this.inventory = inventory;
        this.jdbc = jdbc;
        goods = loadGoods(json, "inventory/arena-point-shop.json");
        dojoGoods = loadGoods(json, "inventory/dojo-point-shop.json");
    }

    /**
     * 校验货架引用的本地道具及精灵模板。
     * @param json 配置解析器
     * @param resource 随包货架路径
     * @return 有效商品数据
     * @throws IOException 配置不可读
     */
    private JsonNode loadGoods(ObjectMapper json, String resource) throws IOException {
        JsonNode products;
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            products = json.readTree(input);
        }
        for (JsonNode product : products) {
            int type = product.path("goodstype").asInt();
            int item = product.path("goods").asInt();
            if (product.path("costnum").asInt() <= 0 || product.path("num").asInt() <= 0
                    || (type != ITEM && type != CHIP) || (type == CHIP && FirstBattleData.starter(item) == null)) {
                throw new IllegalArgumentException("竞技商城商品无有效模板 " + item);
            }
            if (type == ITEM) {
                inventory.itemType(item);
            }
        }
        return products;
    }

    /** 建立独立的竞技积分消费账，历史竞技结果继续作为可用积分来源。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("inventory/arena-point-shop.sql"))
                .execute(jdbc.getDataSource());
    }

    /**
     * 读取当前竞技购物余额。
     *
     * @param roleId 角色编号
     * @return 已结算累计竞技积分扣除商城花费后的数量
     */
    public long balance(String roleId) {
        long earned = Math.addExact(arena.exchangePointsEarned(roleId), inventory.currencyBalance(roleId,
                com.pokemon.offline.inventory.api.entity.SystemMailReward.ARENA_POINTS));
        Long spent = jdbc.queryForObject("SELECT COALESCE(SUM(points),0) FROM local_arena_point_purchase WHERE role_id=?",
                Long.class, roleId);
        return Math.max(0L, earned - (spent == null ? 0L : spent));
    }

    /**
     * 按原版价格兑换当前商品份数，资产入库和积分账同一事务提交。
     *
     * @param roleId 角色编号
     * @param index 原货架序号
     * @param quantity 购买份数
     * @param dojo 是否使用道馆货架和道馆积分钱包
     * @param key 当前连接及协议请求识别键
     * @return 已发放的道具或碎片信息
     */
    @Transactional
    public Purchase buy(String roleId, long index, long quantity, String key, boolean dojo) {
        JsonNode product = (dojo ? dojoGoods : goods).path(Long.toString(index));
        if (product.isMissingNode() || quantity < 1 || quantity > MAX_BUNDLES) {
            return failure(INVALID);
        }
        long dojoPoints = roles.lockRole(roleId).dojoPoints;
        String table = dojo ? "local_dojo_point_purchase" : "local_arena_point_purchase";
        Integer found = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE role_id=? AND request_key=?",
                Integer.class, roleId, key);
        if (found != null && found > 0) {
            return failure(REPEATED);
        }
        long price = quantity * product.path("costnum").asInt();
        long available = dojo ? dojoPoints : balance(roleId);
        if (available < price) {
            return failure(NO_POINTS);
        }
        int item = product.path("goods").asInt();
        int type = product.path("goodstype").asInt();
        long amount = quantity * product.path("num").asInt();
        boolean fits = type == ITEM ? inventory.canReceive(roleId, Collections.singletonMap(item, amount))
                : inventory.canGrantChip(roleId, item, amount);
        if (!fits) {
            return failure(NO_SPACE);
        }
        if (dojo && jdbc.update("UPDATE local_game_role SET dojo_points=dojo_points-? WHERE id=? AND dojo_points>=?",
                price, roleId, price) != 1) {
            return failure(NO_POINTS);
        }
        if (type == ITEM) {
            inventory.grant(roleId, item, amount);
        } else {
            inventory.grantChip(roleId, item, amount);
        }
        jdbc.update("INSERT INTO " + table + "(role_id,request_key,product_id,quantity,points) VALUES(?,?,?,?,?)",
                roleId, key, index, quantity, price);
        return new Purchase(0, item, type, amount, available - price);
    }

    /**
     * 生成不扣积分也不动背包的拒绝结果。
     *
     * @param errno 原客户端错误码
     * @return 结算结果
     */
    private static Purchase failure(int errno) {
        return new Purchase(errno, 0, 0, 0, 0);
    }

    /**
     * 竞技积分兑换后的资产和余额信息。
     *
     * @author tongruiyuan
     * @since 2026-11-19 15:16
     */
    public static final class Purchase {
        public final int errno;
        public final int item;
        public final int type;
        public final long quantity;
        public final long balance;

        /**
         * 记录实际兑换结果。
         *
         * @param errno 原协议错误码
         * @param item 商品配置编号
         * @param type 原版道具或精灵碎片类型
         * @param quantity 实际发放数量
         * @param balance 兑换后的竞技积分余额
         */
        private Purchase(int errno, int item, int type, long quantity, long balance) {
            this.errno = errno;
            this.item = item;
            this.type = type;
            this.quantity = quantity;
            this.balance = balance;
        }
    }
}
