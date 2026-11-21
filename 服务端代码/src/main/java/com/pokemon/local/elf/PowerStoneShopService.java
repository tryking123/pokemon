package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.local.elf.api.entity.ElfGear;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * P 力石碎屑商城 Service，管理角色货架、剩余购买次数及刷新费用。
 *
 * @author tongruiyuan
 * @since 2026-11-19 15:30
 */
@Service
public class PowerStoneShopService {
    private static final JsonNode STORE = ElfTrainingCatalog.load("elf/gemstore.json");
    private static final JsonNode RULE = ElfTrainingCatalog.load("elf/pstone-shop-rule.json");
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfGearService gears;
    private final ElfGearMapper mapper;

    /**
     * 创建力石商城。
     *
     * @param jdbc 货架数据库
     * @param roles 角色事务锁
     * @param inventory 钻石扣费
     * @param gears 力石实例发放
     * @param mapper 碎屑存档
     */
    PowerStoneShopService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory,
                          ElfGearService gears, ElfGearMapper mapper) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.gears = gears;
        this.mapper = mapper;
    }

    /** 角色最近一批货架的存档。 */
    public static final class Board {
        /** 货架版本，用于拒绝旧会话对已刷新位置的购买。 */
        public final long version;
        /** 下次自动刷新时间戳。 */
        public final long next;
        /** 北京时间自然日。 */
        final long day;
        /** 当天付费刷新次数。 */
        public final int manual;
        /** 各位置的底表商品编号。 */
        public final List<Integer> ids;
        /** 已买货位位图。 */
        public final int sold;

        /**
         * 装入已保存的货架。
         *
         * @param version 货架版本
         * @param next 自动刷新时间戳
         * @param day 北京时间自然日
         * @param manual 已付费刷新次数
         * @param ids 位置顺序的模板
         * @param sold 已购买位置
         */
        Board(long version, long next, long day, int manual, List<Integer> ids, int sold) {
            this.version = version;
            this.next = next;
            this.day = day;
            this.manual = manual;
            this.ids = ids;
            this.sold = sold;
        }
    }

    /**
     * 登录、心跳或操作时检查货架周期，按角色锁保存新批次。
     *
     * @param roleId 角色编号
     * @return 该角色当前有效货架
     */
    @Transactional
    public Board board(String roleId) {
        roles.lockRole(roleId);
        List<Board> boards = jdbc.query("SELECT * FROM local_pstone_shop WHERE role_id=?", (row, index) -> {
            List<Integer> ids = new ArrayList<>();
            for (String id : row.getString("goods").split(",")) {
                ids.add(Integer.parseInt(id));
            }
            return new Board(row.getLong("generation"), row.getLong("next_refresh"), row.getLong("counter_day"),
                    row.getInt("manual_count"), ids, row.getInt("sold"));
        }, roleId);
        long now = Instant.now().getEpochSecond();
        long interval = 3600L * RULE.path("data2").asInt();
        long day = (now + 8 * 3600) / 86400;
        Board old = boards.isEmpty() ? null : boards.get(0);
        if (old != null && now < old.next) {
            return old;
        }
        long next = (now / interval + 1) * interval;
        return save(roleId, new Board(old == null ? 1 : old.version + 1, next, day,
                old != null && old.day == day ? old.manual : 0, draw(), 0));
    }

    /**
     * 从原商店表依配置权重无放回抽取八个不同商品。
     *
     * @return 按客户端货位排序的商品编号
     */
    private List<Integer> draw() {
        List<Integer> pool = new ArrayList<>();
        Iterator<String> names = STORE.fieldNames();
        while (names.hasNext()) {
            pool.add(Integer.valueOf(names.next()));
        }
        List<Integer> goods = new ArrayList<>();
        while (goods.size() < RULE.path("data4").asInt()) {
            int weight = 0;
            for (int id : pool) {
                weight += STORE.path(Integer.toString(id)).path("probability").asInt();
            }
            int pick = ThreadLocalRandom.current().nextInt(weight);
            Iterator<Integer> candidates = pool.iterator();
            while (candidates.hasNext()) {
                int id = candidates.next();
                pick -= STORE.path(Integer.toString(id)).path("probability").asInt();
                if (pick < 0) {
                    goods.add(id);
                    candidates.remove();
                    break;
                }
            }
        }
        return goods;
    }

    /**
     * 保存一个批次及该批次剩余货位。
     *
     * @param roleId 角色编号
     * @param state 待保存状态
     * @return 已写入状态
     */
    private Board save(String roleId, Board state) {
        StringBuilder ids = new StringBuilder();
        for (int id : state.ids) {
            if (ids.length() > 0) {
                ids.append(',');
            }
            ids.append(id);
        }
        Object[] args = {state.version, state.next, state.day, state.manual, ids.toString(), state.sold, roleId};
        if (jdbc.update("UPDATE local_pstone_shop SET generation=?,next_refresh=?,counter_day=?,manual_count=?,"
                + "goods=?,sold=? WHERE role_id=?", args) == 0) {
            jdbc.update("INSERT INTO local_pstone_shop(generation,next_refresh,counter_day,manual_count,goods,sold,"
                    + "role_id) VALUES (?,?,?,?,?,?,?)", args);
        }
        return state;
    }

    /**
     * 在当前批次内付费换货，次数超过费用表长度后使用最后一档。
     *
     * @param roleId 角色编号
     * @param version 调用者看到的批次
     * @return 新批次，失败时为空
     */
    @Transactional
    public Board refresh(String roleId, long version) {
        Board current = board(roleId);
        JsonNode prices = RULE.path("data6");
        long price = prices.path(Integer.toString(Math.min(current.manual + 1, prices.size()))).asLong();
        if (version != current.version || current.manual >= RULE.path("data1").asInt() || price <= 0
                || !inventory.spendMaterials(roleId, Collections.emptyMap(), 0, price)) {
            return null;
        }
        return save(roleId, new Board(version + 1, current.next, current.day, current.manual + 1, draw(), 0));
    }

    /**
     * 购买当前货位，在同一角色事务内扣碎屑、发实例并记录售罄。
     *
     * @param roleId 角色编号
     * @param version 操作端看到的批次
     * @param index 从一开始的货位
     * @return 已发放的力石，失败时为空
     */
    @Transactional
    public List<ElfGear> buy(String roleId, long version, long index) {
        Board current = board(roleId);
        if (current.version != version || index < 1 || index > current.ids.size()) {
            return Collections.emptyList();
        }
        int bit = 1 << ((int) index - 1);
        int id = current.ids.get((int) index - 1);
        JsonNode entry = STORE.path(Integer.toString(id));
        int price = entry.path("needpowder").asInt();
        int quantity = entry.path("number").asInt();
        long balance = mapper.debris(roleId);
        if ((current.sold & bit) != 0 || price <= 0 || balance < price
                || !gears.canGrantGear(roleId, ElfGearService.POWERSTONE, id, quantity)) {
            return Collections.emptyList();
        }
        mapper.setDebris(roleId, balance - price);
        List<ElfGear> added = gears.grantGear(roleId, ElfGearService.POWERSTONE, id, quantity);
        save(roleId, new Board(version, current.next, current.day, current.manual, current.ids, current.sold | bit));
        return added;
    }
}
