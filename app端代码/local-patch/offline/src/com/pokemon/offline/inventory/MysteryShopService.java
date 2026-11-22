package com.pokemon.offline.inventory;

import com.pokemon.offline.role.RoleService;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 神秘商店轮换、计费和存档 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:55
 */
@Service
@DependsOnDatabaseInitialization
public class MysteryShopService {
    private static final ZoneId GAME_ZONE = ZoneId.of("Asia/Shanghai");
    private static final long FREE_REFRESH_INTERVAL = 4 * 3600;
    private static final int REFRESH_LIMIT = 100;
    private static final int REFRESH_TICKET = 30008;
    private static final int BOARD_SIZE = 12;
    private static final int[] VIP_SLOTS = {6,6,6,6,7,7,8,8,8,9,9,10,10,10,11,11,11,12};
    private static final int[] REFRESH_COST = {50, 100, 200, 400, 800, 1000};
    private static final int BAD_INDEX = 100021;
    private static final int ALREADY_BOUGHT = 100011;
    private static final int DIAMONDS_NOT_ENOUGH = 100006;
    private static final int COINS_NOT_ENOUGH = 100005;
    private static final int BACKPACK_FULL = 100015;
    private static final int REFRESHES_EXHAUSTED = 250001;
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.payment.GamePaymentService> payments;
    private final InventoryService inventory;
    private final List<Offer> catalog = new ArrayList<>();

    /**
     * 装载现有底包商品数据。
     *
     * @param jdbc 神秘商店数据库访问
     * @param roles 角色资产与锁
     * @param inventory 背包与道具定义
     * @param payments 角色真实 VIP 档位
     * @throws IOException 原始商品配置无法读取
     */
    MysteryShopService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
                       com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.payment.GamePaymentService> payments) throws IOException {
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.payments = payments;
        ClassPathResource resource = new ClassPathResource("inventory/mystery.tsv");
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] value = line.split("\\t");
                int item = Integer.parseInt(value[0]);
                inventory.itemType(item);
                catalog.add(new Offer(item, Integer.parseInt(value[1]), Integer.parseInt(value[2]),
                        Integer.parseInt(value[3]), Integer.parseInt(value[4]), false));
            }
        }
        inventory.itemType(REFRESH_TICKET);
    }

    /**
     * 初始化商店当前轮次和请求回执结构。
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("inventory/mystery-schema.sql")).execute(jdbc.getDataSource());
    }

    /**
     * 读取当前商品与刷新次数并为首次进入角色保存初始轮次。
     *
     * @param roleId 当前角色
     * @return 实际可展示的神秘商店状态
     */
    @Transactional
    public Board snapshot(String roleId) {
        roles.lockRole(roleId);
        return board(roleId, load(roleId, Instant.now()));
    }

    /**
     * 在当前轮次内购买一份货品，已购槽位禁止重复发放。
     *
     * @param roleId 当前角色
     * @param requestId 同连接请求回执编号
     * @param selected 原客户端货品下标
     * @return 当前商店状态与实际操作结果
     */
    @Transactional
    public Result buy(String roleId, String requestId, long selected) {
        roles.lockRole(roleId);
        State state = load(roleId, Instant.now());
        if (selected < 1 || selected > unlockedSlots(roleId)) {
            return result(roleId, state, BAD_INDEX, false);
        }
        int slot = (int) selected;
        Integer used = receipt(roleId, requestId);
        if (used != null) {
            return result(roleId, state, used == slot ? 0 : BAD_INDEX, false);
        }
        if (state.bought(slot)) {
            return result(roleId, state, ALREADY_BOUGHT, false);
        }
        Offer offer = offer(roleId, state, slot);
        if (offer.item != 1001) {
            long owned = inventory.count(roleId, offer.item);
            if (owned > Integer.MAX_VALUE - offer.quantity
                    || (owned == 0 && inventory.ordinaryUsed(roleId) >= inventory.ordinaryCapacity(roleId))) {
                return result(roleId, state, BACKPACK_FULL, false);
            }
        }
        if (!inventory.spendMaterials(roleId, 0, 0, offer.currency == 2 ? offer.price : 0,
                offer.currency == 1 ? offer.price : 0)) {
            return result(roleId, state, offer.currency == 1 ? DIAMONDS_NOT_ENOUGH : COINS_NOT_ENOUGH, false);
        }
        inventory.grant(roleId, offer.item, offer.quantity);
        state.mask |= 1 << (slot - 1);
        save(roleId, state);
        record(roleId, requestId, slot);
        return result(roleId, state, 0, true);
    }

    /**
     * 按原客户端所示方式使用免费时间、刷新券或钻石刷新。
     *
     * @param roleId 当前角色
     * @param requestId 同连接请求回执编号
     * @return 当前商店状态与实际操作结果
     */
    @Transactional
    public Result refresh(String roleId, String requestId) {
        roles.lockRole(roleId);
        Instant now = Instant.now();
        State state = load(roleId, now);
        Integer used = receipt(roleId, requestId);
        if (used != null) {
            return result(roleId, state, used == 0 ? 0 : BAD_INDEX, false);
        }
        if (now.getEpochSecond() >= state.refreshAt) {
            state.refreshAt = now.getEpochSecond() + FREE_REFRESH_INTERVAL;
        } else if (inventory.count(roleId, REFRESH_TICKET) > 0) {
            if (state.itemRefreshes >= REFRESH_LIMIT) {
                return result(roleId, state, REFRESHES_EXHAUSTED, false);
            }
            if (!inventory.takeOne(roleId, REFRESH_TICKET)) {
                throw new IllegalStateException("刷新券存档在结算期间变化");
            }
            state.itemRefreshes++;
        } else {
            if (state.diamondRefreshes >= REFRESH_LIMIT) {
                return result(roleId, state, REFRESHES_EXHAUSTED, false);
            }
            int price = REFRESH_COST[Math.min(REFRESH_COST.length - 1, state.diamondRefreshes)];
            if (!inventory.spendMaterials(roleId, 0, 0, 0, price)) {
                return result(roleId, state, DIAMONDS_NOT_ENOUGH, false);
            }
            state.diamondRefreshes++;
        }
        state.generation++;
        state.mask = 0;
        save(roleId, state);
        record(roleId, requestId, 0);
        return result(roleId, state, 0, true);
    }

    /**
     * 读取真实保存轮次并按北京时间四点重置刷新次数。
     *
     * @param roleId 当前角色
     * @param now 当前时刻
     * @return 可变商店存档
     */
    private State load(String roleId, Instant now) {
        String day = now.atZone(GAME_ZONE).minusHours(4).toLocalDate().toString();
        List<State> existing = jdbc.query("SELECT * FROM local_mystery_shop WHERE role_id=?", (row, index) -> {
            boolean sameDay = day.equals(row.getString("counter_day"));
            return new State(row.getLong("generation"), row.getLong("refresh_at"), day,
                    sameDay ? row.getInt("item_refreshes") : 0,
                    sameDay ? row.getInt("diamond_refreshes") : 0, row.getInt("purchased_mask"));
        }, roleId);
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        State initial = new State(0, now.getEpochSecond() + FREE_REFRESH_INTERVAL, day, 0, 0, 0);
        save(roleId, initial);
        return initial;
    }

    /**
     * 在已持有角色锁的交易中保存当前轮次。
     *
     * @param roleId 当前角色
     * @param state 要写入的轮次
     */
    private void save(String roleId, State state) {
        int rows = jdbc.update("UPDATE local_mystery_shop SET generation=?,refresh_at=?,counter_day=?,"
                        + "item_refreshes=?,diamond_refreshes=?,purchased_mask=? WHERE role_id=?",
                state.generation, state.refreshAt, state.day, state.itemRefreshes, state.diamondRefreshes, state.mask, roleId);
        if (rows == 0) {
            jdbc.update("INSERT INTO local_mystery_shop (generation,refresh_at,counter_day,item_refreshes,"
                            + "diamond_refreshes,purchased_mask,role_id) VALUES (?,?,?,?,?,?,?)",
                    state.generation, state.refreshAt, state.day, state.itemRefreshes, state.diamondRefreshes, state.mask, roleId);
        }
    }

    /**
     * 查找已结算的同连接请求。
     *
     * @param roleId 当前角色
     * @param requestId 请求回执编号
     * @return 购买下标或零，未提交返回空
     */
    private Integer receipt(String roleId, String requestId) {
        return jdbc.query("SELECT selected_slot FROM local_mystery_shop_receipt WHERE role_id=? AND request_id=?",
                (row, index) -> row.getInt(1), roleId, requestId).stream().findFirst().orElse(null);
    }

    /**
     * 记录结算操作以使同会话请求可安全重试。
     *
     * @param roleId 当前角色
     * @param requestId 请求回执编号
     * @param slot 购买下标，零对应刷新
     */
    private void record(String roleId, String requestId, int slot) {
        jdbc.update("INSERT INTO local_mystery_shop_receipt (role_id,request_id,selected_slot) VALUES (?,?,?)",
                roleId, requestId, slot);
    }

    /**
     * 按角色和持久轮次从固定原货品目录中确定下标。
     *
     * @param roleId 当前角色
     * @param state 保存的轮次
     * @param slot 商品下标
     * @return 原价格和数量的货品状态
     */
    private Offer offer(String roleId, State state, int slot) {
        int position = (int) Math.floorMod(roleId.hashCode() + state.generation * 7 + (slot - 1) * 11, catalog.size());
        Offer offer = catalog.get(position);
        return new Offer(offer.item, offer.type, offer.currency, offer.price, offer.quantity, state.bought(slot));
    }

    /**
     * 按原 VIP 表返回当前角色开放的货架数量。
     * @param roleId 角色编号
     * @return 可见且可购买的货架数
     */
    private int unlockedSlots(String roleId) {
        return VIP_SLOTS[Math.max(0, Math.min(17, payments.getIfAvailable().vip(roleId).level))];
    }

    /**
     * 构造当前商店快照。
     * @param roleId 当前角色
     * @param state 保存的轮次
     * @return 客户端当前轮次
     */
    private Board board(String roleId, State state) {
        List<Offer> entries = new ArrayList<>(BOARD_SIZE);
        for (int slot = 1; slot <= unlockedSlots(roleId); slot++) {
            entries.add(offer(roleId, state, slot));
        }
        return new Board(state.refreshAt, state.itemRefreshes, state.diamondRefreshes, entries);
    }

    /**
     * 返回结算结果和实际当前快照。
     *
     * @param roleId 当前角色
     * @param state 保存的轮次
     * @param errno 原协议错误码
     * @param changed 本次是否新提交
     * @return 业务结果
     */
    private Result result(String roleId, State state, int errno, boolean changed) {
        return new Result(errno, changed, board(roleId, state));
    }

    /**
     * 角色保存的神秘商店轮次。
     *
     * @author tongruiyuan
     * @since 2026-11-18 22:55
     */
    private static final class State {
        long generation;
        long refreshAt;
        final String day;
        int itemRefreshes;
        int diamondRefreshes;
        int mask;

        State(long generation, long refreshAt, String day, int itemRefreshes, int diamondRefreshes, int mask) {
            this.generation = generation;
            this.refreshAt = refreshAt;
            this.day = day;
            this.itemRefreshes = itemRefreshes;
            this.diamondRefreshes = diamondRefreshes;
            this.mask = mask;
        }

        boolean bought(int slot) {
            return (mask & 1 << (slot - 1)) != 0;
        }
    }

    /**
     * 商店货品快照。
     *
     * @author tongruiyuan
     * @since 2026-11-18 22:55
     */
    public static final class Offer {
        public final int item;
        public final int type;
        public final int currency;
        public final int price;
        public final int quantity;
        public final boolean bought;

        Offer(int item, int type, int currency, int price, int quantity, boolean bought) {
            this.item = item;
            this.type = type;
            this.currency = currency;
            this.price = price;
            this.quantity = quantity;
            this.bought = bought;
        }
    }

    /**
     * 包括货品和计费次数的商店快照。
     *
     * @author tongruiyuan
     * @since 2026-11-18 22:55
     */
    public static final class Board {
        public final long refreshAt;
        public final int itemRefreshes;
        public final int diamondRefreshes;
        public final List<Offer> offers;

        Board(long refreshAt, int itemRefreshes, int diamondRefreshes, List<Offer> offers) {
            this.refreshAt = refreshAt;
            this.itemRefreshes = itemRefreshes;
            this.diamondRefreshes = diamondRefreshes;
            this.offers = offers;
        }
    }

    /**
     * 一次购买或刷新及其当前状态。
     *
     * @author tongruiyuan
     * @since 2026-11-18 22:55
     */
    public static final class Result {
        public final int errno;
        public final boolean changed;
        public final Board board;

        Result(int errno, boolean changed, Board board) {
            this.errno = errno;
            this.changed = changed;
            this.board = board;
        }
    }
}
