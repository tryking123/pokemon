package com.pokemon.local.activity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
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
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 神兽商店按等级轮换原版商品，处理刷新和兑换。
 *
 * @author tongruiyuan
 * @since 2026-11-19 19:08
 */
@Service
public class MythShopService {
    private static final int ACTIVITY = 10046;
    private static final int[] FEES = {50, 50, 150, 150, 150, 300, 300, 300, 300, 500};
    private static final int[] TICKETS = {0, 34135, 34136, 34137};
    private static final int SLOTS = 8;
    private final List<Integer> prices = new ArrayList<>();
    private final int[][] goods;
    private final ActivityService calendar;
    private final ActivityMapper mapper;
    private final InventoryService inventory;
    private final RoleService roles;
    private final ElfService elves;
    private final JdbcTemplate jdbc;

    /**
     * 装载三个原版商店的等级、精灵与刷新定价。
     * @param calendar 活动日历
     * @param mapper 请求回执
     * @param inventory 券、钻石与奖励邮件
     * @param roles 角色锁
     * @param elves 精灵仓库
     * @param jdbc 货架存档
     * @param json 原表读取器
     * @throws IOException 配置读取失败
     */
    MythShopService(ActivityService calendar, ActivityMapper mapper, InventoryService inventory, RoleService roles,
                    ElfService elves, JdbcTemplate jdbc, ObjectMapper json) throws IOException {
        this.calendar = calendar;
        this.mapper = mapper;
        this.inventory = inventory;
        this.roles = roles;
        this.elves = elves;
        this.jdbc = jdbc;
        try (InputStream stream = new ClassPathResource("activity/myth-shop.json").getInputStream()) {
            goods = json.readValue(stream, int[][].class);
        }
        for (int i = 0; i < FEES.length; i++) {
            prices.add(i + 1);
            prices.add(FEES[i]);
        }
    }

    /**
     * 打开三栏货架，按当前会话展示版本刷新或兑换。
     * @param roleId 当前角色
     * @param protocol 原请求编号
     * @param shop 客户端栏目
     * @param index 页面内从一开始的槽位
     * @param observed 连接上次看到的货架版本
     * @param key 请求防重键
     * @return 原界面应答与资产增减
     */
    @Transactional
    public Result process(String roleId, int protocol, long shop, long index, String observed, String key) {
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ActivityPeriod period = calendar.cycleAt(ACTIVITY, now);
        if (now < period.start || now >= period.end) {
            return new Result(100013);
        }
        Result result = new Result(0);
        if (protocol == 246) {
            List<Map<String, Object>> info = new ArrayList<>();
            for (int type = 1; type < TICKETS.length; type++) {
                Shelf shelf = load(role, type, period.start, now);
                info.add(view(role, shelf));
                result.versions.put(type, shelf.version());
            }
            result.response.put("info", info);
            return result;
        }
        if (shop < 1 || shop >= TICKETS.length || (protocol != 247 && protocol != 248)) {
            return new Result(100021);
        }
        if (mapper.hasOperatingRequest(roleId, ACTIVITY, key)) {
            return new Result(100011);
        }
        int type = (int) shop;
        Shelf shelf = load(role, type, period.start, now);
        if (!shelf.version().equals(observed)) {
            return new Result(100013);
        }
        result.response.put("shoptype", type);
        if (protocol == 247) {
            int fee = FEES[Math.min(shelf.count, FEES.length - 1)];
            if (!inventory.spendMaterials(roleId, Collections.emptyMap(), 0, fee)) {
                return new Result(100012);
            }
            shelf.count++;
            shelf.revision++;
            shelf.level = role.level;
            shelf.sold = 0;
            result.diamonds = fee;
            result.response.put("list", view(role, shelf).get("list"));
            result.response.put("times", shelf.count);
        } else {
            List<Integer> stock = selection(role, shelf);
            if (index < 1 || index > stock.size()) {
                return new Result(100021);
            }
            int bit = 1 << ((int) index - 1);
            if ((shelf.sold & bit) != 0) {
                return new Result(100011);
            }
            if (!inventory.spendMaterials(roleId, Collections.singletonMap(TICKETS[type], 1L), 0, 0)) {
                return new Result(100012);
            }
            int elf = stock.get((int) index - 1);
            if (elves.list(roleId).size() < inventory.elfCapacity(roleId)) {
                result.elf = elves.grant(roleId, elf);
            } else {
                inventory.createRewardMail(roleId, UUID.randomUUID().toString(), "神兽商店兑换",
                        "精灵背包已满，请在附件中领取。", now, now + 30 * 86400L, Arrays.asList(2, elf, 1));
                result.mailed = true;
            }
            shelf.sold |= bit;
            result.ticket = TICKETS[type];
            result.response.put("index", index);
        }
        save(roleId, shelf);
        mapper.recordOperatingAction(roleId, period, protocol, key);
        result.versions.put(type, shelf.version());
        return result;
    }

    /**
     * 构建从一开始连续编号的原 Sproto 货架，适配原列表长度与索引。
     * @param role 当前角色
     * @param shelf 当前存档
     * @return 客户端栏目数据
     */
    private Map<String, Object> view(LocalRole role, Shelf shelf) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int elf : selection(role, shelf)) {
            int slot = list.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", slot + 1);
            row.put("convert", Arrays.asList(2, elf, 1));
            row.put("cost", Arrays.asList(1, TICKETS[shelf.type], 1));
            // 原界面以字段是否存在判断售罄，未售出时必须省略该字段。
            if ((shelf.sold & (1 << slot)) != 0) {
                row.put("flag", true);
            }
            list.add(row);
        }
        Map<String, Object> tab = new LinkedHashMap<>();
        tab.put("shoptype", shelf.type);
        tab.put("times", shelf.count);
        tab.put("price", prices);
        tab.put("list", list);
        return tab;
    }

    /**
     * 按生成时的等级过滤原表，从本次轮换起点取连续八个不同商品。
     * @param role 当前角色
     * @param shelf 当前货架
     * @return 展示顺序的精灵模板
     */
    private List<Integer> selection(LocalRole role, Shelf shelf) {
        List<Integer> eligible = new ArrayList<>();
        for (int[] row : goods) {
            if (row[1] == shelf.type && row[2] <= shelf.level) {
                eligible.add(row[3]);
            }
        }
        int offset = (int) Math.floorMod(role.id.hashCode() + shelf.batch * 7 + (long) shelf.revision * SLOTS,
                (long) eligible.size());
        List<Integer> selected = new ArrayList<>();
        for (int i = 0; i < Math.min(eligible.size(), SLOTS); i++) {
            selected.add(eligible.get((offset + i) % eligible.size()));
        }
        return selected;
    }

    /**
     * 恢复同批库存，跨自动刷新边界换货，跨北京时间四点清理计价次数。
     * @param role 当前角色
     * @param type 栏目
     * @param period 活动轮次
     * @param now 当前秒
     * @return 有效货架
     */
    private Shelf load(LocalRole role, int type, long period, long now) {
        Shelf shelf = new Shelf(type);
        jdbc.query("SELECT period_start,batch,revision,fee_day,refresh_count,shelf_level,sold_mask "
                + "FROM local_myth_shop WHERE role_id=? AND shop_type=?", row -> {
            shelf.period = row.getLong(1);
            shelf.batch = row.getLong(2);
            shelf.revision = row.getInt(3);
            shelf.day = row.getLong(4);
            shelf.count = row.getInt(5);
            shelf.level = row.getInt(6);
            shelf.sold = row.getInt(7);
        }, role.id, type);
        long day = Math.floorDiv(now + 14400, 86400);
        long batch = Math.floorDiv(now + 14400, 21600);
        if (shelf.day != day || shelf.period != period) {
            shelf.count = 0;
        }
        if (shelf.batch != batch || shelf.period != period || shelf.day != day) {
            shelf.level = role.level;
            shelf.revision = 0;
            shelf.sold = 0;
            shelf.period = period;
            shelf.batch = batch;
            shelf.day = day;
            save(role.id, shelf);
        }
        return shelf;
    }

    /**
     * 保存售罄掩码、刷新版本和定价进度。
     * @param roleId 当前角色
     * @param shelf 当前货架
     */
    private void save(String roleId, Shelf shelf) {
        jdbc.update("MERGE INTO local_myth_shop (role_id,shop_type,period_start,batch,revision,fee_day,refresh_count,"
                + "shelf_level,sold_mask) KEY(role_id,shop_type) VALUES(?,?,?,?,?,?,?,?,?)", roleId, shelf.type,
                shelf.period, shelf.batch, shelf.revision, shelf.day, shelf.count, shelf.level, shelf.sold);
    }

    /**
     * 一栏神兽商店的持久状态。
     * @author tongruiyuan
     * @since 2026-11-19 19:08
     */
    private static final class Shelf {
        final int type;
        long period = Long.MIN_VALUE;
        long batch;
        int revision;
        long day;
        int count;
        int level;
        int sold;

        /** @param type 栏目编号 */
        Shelf(int type) { this.type = type; }

        /** @return 连接上必须匹配的库存版本 */
        String version() { return period + ":" + batch + ":" + revision; }
    }

    /**
     * 神兽商店原协议应答与事务结果。
     * @author tongruiyuan
     * @since 2026-11-19 19:08
     */
    public static final class Result {
        public final Map<String, Object> response = new LinkedHashMap<>();
        public final Map<Integer, String> versions = new LinkedHashMap<>();
        public int diamonds;
        public int ticket;
        public LocalElf elf;
        public boolean mailed;

        /** @param errno 客户端错误码 */
        Result(int errno) { response.put("errno", errno); }
    }
}
