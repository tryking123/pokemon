package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.elf.ElfGearService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 十二宫积分商城 Service，维护本地积分货架、库存及钻石刷新次数。
 *
 * @author tongruiyuan
 * @since 2026-11-19 17:43
 */
@Service
public class ZodiacShopService {
    private final ArenaMapper mapper;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfGearService gears;
    private final JsonNode config;

    /**
     * 加载十二宫原表、货架存档和资产服务。
     *
     * @param mapper 角色十二宫存档
     * @param roles 角色锁及余额
     * @param inventory 道具发放与扣钻
     * @param gears 独立力石实例
     * @param json 配置解析器
     * @throws IOException 随包配置读取失败
     */
    ZodiacShopService(ArenaMapper mapper, RoleService roles, InventoryService inventory, ElfGearService gears,
                      ObjectMapper json) throws IOException {
        this.mapper = mapper;
        this.roles = roles;
        this.inventory = inventory;
        this.gears = gears;
        try (InputStream in = new ClassPathResource("arena/zodiac-shop.json").getInputStream()) {
            config = json.readTree(in);
        }
    }

    /**
     * 生成原 S810 的货架字段。
     *
     * @param roleId 当前角色编号
     * @return 含积分、自动刷新时间、付费刷新次数和当批商品的快照
     */
    @Transactional
    public Map<String, Object> snapshot(String roleId) {
        Map<String, Object> fields = board(roleId);
        fields.remove("version");
        return fields;
    }

    /**
     * 读取原货架字段和用于拒绝旧请求的批次。
     *
     * @param roleId 当前角色编号
     * @return 带本地批次编号的协议字段表
     */
    @Transactional
    public Map<String, Object> board(String roleId) {
        roles.lockRole(roleId);
        ObjectNode board = current(roleId);
        Map<String, Object> reply = view(board);
        reply.put("score", mapper.load(roleId, "zodiac:wallet").path("score").asLong());
        return reply;
    }

    /**
     * 获取当前批次，自动刷新时轮换剩余四个货位。
     *
     * @param roleId 持锁角色
     * @return 当前货架存档
     */
    private ObjectNode current(String roleId) {
        long now = Instant.now().getEpochSecond();
        ObjectNode state = mapper.load(roleId, "zodiac:shop");
        if (state.path("next").asLong() <= now) {
            long span = config.path("hours").asLong() * 3600;
            long day = (now + 28800) / 86400;
            if (day != state.path("day").asLong(-1)) {
                state.put("manual", 0);
            }
            state.put("day", day).put("next", (now / span + 1) * span);
            nextShelf(roleId, state);
        }
        return state;
    }

    /**
     * 保留四种入门力石，将剩余四格轮换为固定商品表的其他档。
     *
     * @param roleId 当前角色
     * @param board 待更新货架
     */
    private void nextShelf(String roleId, ObjectNode board) {
        long version = board.path("version").asLong() + 1;
        int[] rest = {1, 2, 3, 4, 9, 10, 11, 12, 13, 14, 15};
        board.put("version", version).put("sold", 0);
        ArrayNode ids = board.putArray("ids");
        for (int i = 5; i <= 8; i++) {
            ids.add(i);
        }
        for (int i = 0; i < config.path("size").asInt() - 4; i++) {
            ids.add(rest[(int) (((version - 1) * 4 + i) % rest.length)]);
        }
        mapper.save(roleId, "zodiac:shop", board);
    }

    /**
     * 构造原协议使用的货位列表和刷新状态。
     *
     * @param board 当前批次
     * @return 原货架字段及供本地连接比对的版本号
     */
    private Map<String, Object> view(ObjectNode board) {
        List<Map<String, Object>> cargo = new ArrayList<>();
        int sold = board.path("sold").asInt();
        for (JsonNode id : board.path("ids")) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id.intValue());
            item.put("times", (sold & (1 << cargo.size())) == 0 ? 1 : 0);
            cargo.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", board.path("version").asLong());
        result.put("autotime", board.path("next").asLong());
        result.put("manaual", board.path("manual").asInt());
        result.put("shop", cargo);
        return result;
    }

    /**
     * 按原确认框显示的付费档购买新一批货位。
     *
     * @param roleId 当前角色编号
     * @param displayed 连接看到的批次
     * @return 成功时为新货架，失败时为空
     */
    @Transactional
    public Map<String, Object> refresh(String roleId, long displayed) {
        roles.lockRole(roleId);
        ObjectNode board = current(roleId);
        int count = board.path("manual").asInt();
        JsonNode prices = config.path("prices");
        long price = prices.get(Math.min(count, prices.size() - 1)).longValue();
        if (displayed != board.path("version").asLong()
                || !inventory.spendMaterials(roleId, Collections.emptyMap(), 0, price)) {
            return Collections.emptyMap();
        }
        board.put("manual", count + 1);
        nextShelf(roleId, board);
        return view(board);
    }

    /**
     * 按玩家当前看到的货位扣十二宫积分并发放普通物品或独立力石。
     *
     * @param roleId 当前角色编号
     * @param displayed 玩家看到的批次
     * @param idx 一开始的货位序号
     * @return 实际购买的原商品类型，失败时为零
     */
    @Transactional
    public int buy(String roleId, long displayed, long idx) {
        roles.lockRole(roleId);
        ObjectNode board = current(roleId);
        JsonNode ids = board.path("ids");
        if (displayed != board.path("version").asLong() || idx < 1 || idx > ids.size()) {
            return 0;
        }
        int bit = 1 << ((int) idx - 1);
        if ((board.path("sold").asInt() & bit) != 0) {
            return 0;
        }
        JsonNode offer = config.path("offers").path(ids.get((int) idx - 1).asText());
        int kind = offer.path("goodstype").asInt(), id = offer.path("goods").asInt();
        int n = offer.path("num").asInt(), price = offer.path("price").asInt();
        ObjectNode wallet = mapper.load(roleId, "zodiac:wallet");
        long balance = wallet.path("score").asLong();
        if (price <= 0 || price > balance || n <= 0) {
            return 0;
        }
        if (kind == ElfGearService.POWERSTONE) {
            if (!gears.canGrantGear(roleId, kind, id, n)) {
                return 0;
            }
            gears.grantGear(roleId, kind, id, n);
        } else if (kind == 1 && inventory.canGrantItem(roleId, id, n)) {
            inventory.grant(roleId, id, n);
        } else {
            return 0;
        }
        mapper.save(roleId, "zodiac:wallet", wallet.put("score", balance - price));
        mapper.save(roleId, "zodiac:shop", board.put("sold", board.path("sold").asInt() | bit));
        return kind;
    }
}
