package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 家园矿场、雕像建造、升级、拆除与定时采收。
 * @author tongruiyuan
 * @since 2026-11-19 21:49
 */
@Service
public class HomeBuildingService {
    private final HomeBuildingMapper buildings;
    private final HomeService home;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ObjectMapper json;

    /** 绑定家园、角色和库存事务。
     * @param buildings 地块与建筑存档
     * @param home 家园等级与回执
     * @param roles 角色及钱包
     * @param inventory 真实材料和背包
     * @param json 协议对象转换
     */
    public HomeBuildingService(HomeBuildingMapper buildings, HomeService home, RoleService roles,
                               InventoryService inventory, ObjectMapper json) {
        this.buildings = buildings;
        this.home = home;
        this.roles = roles;
        this.inventory = inventory;
        this.json = json;
    }

    /** 获取原家园地图的建筑列表。
     * @param id 角色编号
     * @return 原地块列表
     */
    public List<ObjectNode> snapshot(String id) {
        return buildings.buildings(id);
    }

    /** 在角色锁内执行一条原建筑操作并保存成功应答。
     * @param id 角色编号
     * @param command 原协议号
     * @param idx 原地块号，采收时零表示全部
     * @param sid 建造请求模板
     * @param key 本连接请求回执
     * @return 原应答和实际获奖
     * @throws IOException 既有回执无法读取
     */
    @Transactional
    public ObjectNode handle(String id, int command, int idx, int sid, String key) throws IOException {
        LocalRole role = roles.lockRole(id);
        String receipt = "matrix:" + command + ":" + key;
        String old = home.reply(id, receipt);
        if (old != null) return ((ObjectNode)json.readTree(old)).put("replayed", true);
        ObjectNode out = json.createObjectNode().put("errno", 100021);
        int level = home.level(id);
        if (role.sceneId != 80011 || level <= 0 || idx < 0) return out;
        List<ObjectNode> rows = buildings.buildings(id);
        ObjectNode row = rows.stream().filter(r -> r.path("index").asInt() == idx).findFirst().orElse(null);
        JsonNode plot = buildings.plot(idx);
        long now = Instant.now().getEpochSecond();
        if (command == 340) {
            if (!harvest(id, rows, idx, now, receipt, out)) return out;
            out.put("now", now);
        } else if (plot.isMissingNode() || plot.path("openlv").asInt() > level) {
            return out;
        } else if (command == 337) {
            boolean allowed = false;
            for (JsonNode option : plot.path("architectureid")) if (option.asInt() == sid) allowed = true;
            long count = rows.stream().filter(r -> r.path("sid").asInt() / 1000 == sid / 1000).count();
            JsonNode cfg = buildings.template(sid);
            if (row != null || !allowed || count >= cfg.path("nummax").asInt()) return out;
            int payment = pay(id, cfg.path("archi"));
            if (payment != 0) return out.put("errno", payment);
            buildings.save(id, idx, sid, now);
            out.put("sid", sid);
        } else if (row == null) {
            return out;
        } else if (command == 338) {
            buildings.dismantle(id, idx);
        } else if (command == 339) {
            int current = row.path("sid").asInt();
            JsonNode cost = buildings.template(current).path("expend");
            if (!cost.isArray() || buildings.template(current + 1).isMissingNode()) return out;
            int payment = pay(id, cost);
            if (payment != 0) return out.put("errno", payment);
            buildings.save(id, idx, current + 1, row.path("lasttime").asLong());
            out.put("aftersid", current + 1);
        } else {
            return out;
        }
        out.put("errno", 0).put("idx", idx);
        home.record(id, receipt, out.toString());
        return out;
    }

    /** 扣除当前建筑配置显示的全部材料、金币及钻石。
     * @param id 角色编号
     * @param cost 三元组费用
     * @return 成功为零，否则为金币、钻石、材料不足或参数错误的原错误码
     */
    private int pay(String id, JsonNode cost) {
        Map<Integer,Long> items = new LinkedHashMap<>();
        long coins = 0, diamonds = 0;
        if (!cost.isArray() || cost.size() == 0) return 100021;
        for (int i=0; i<cost.size(); i+=3) {
            long amount = cost.get(i+2).asLong();
            switch (cost.get(i).asInt()) {
                case 1: items.merge(cost.get(i+1).asInt(), amount, Long::sum); break;
                case 103: coins += amount; break;
                case 104: diamonds += amount; break;
                default: throw new IllegalStateException("未知建筑消耗");
            }
        }
        LocalRole role = roles.findById(id).orElseThrow(() -> new IllegalStateException("家园角色不存在"));
        if (role.coins < coins) return 100005;
        if (role.diamonds < diamonds) return 100006;
        return inventory.spendMaterials(id, items, coins, diamonds) ? 0 : 100004;
    }

    /** 按原矿场面板整段周期计算产量，并一次结清选中的矿场。
     * @param id 角色编号
     * @param rows 已建地块
     * @param idx 指定地块，零为全收
     * @param now 当前秒时间戳
     * @param receipt 发货回执
     * @param out 协议奖励对象
     * @return 是否有实际产出可领取
     */
    private boolean harvest(String id, List<ObjectNode> rows, int idx, long now, String receipt, ObjectNode out) {
        Map<Integer,Long> items = new LinkedHashMap<>();
        long coins = 0, diamonds = 0;
        for (ObjectNode row : rows) {
            if (idx != 0 && row.path("index").asInt() != idx) continue;
            JsonNode cfg = buildings.template(row.path("sid").asInt());
            JsonNode unit = cfg.path("minute");
            if (!unit.isArray()) continue;
            long elapsed = Math.max(0, now-row.path("lasttime").asLong()) / 60;
            long amount = Math.min(elapsed, cfg.path("max").asInt()) / cfg.path("min").asInt() * unit.get(2).asLong();
            if (amount <= 0) continue;
            switch (unit.get(0).asInt()) {
                case 1: items.merge(unit.get(1).asInt(), amount, Long::sum); break;
                case 103: coins += amount; break;
                case 104: diamonds += amount; break;
                default: throw new IllegalStateException("未知矿场产物");
            }
        }
        if (coins == 0 && diamonds == 0 && items.isEmpty()) return false;
        roles.addCurrency(id, coins, diamonds, 0);
        ObjectNode adds = out.putObject("adds");
        if (coins > 0) adds.put("coins", coins);
        if (diamonds > 0) adds.put("diamonds", diamonds);
        inventory.grantItemsOrMail(id, receipt, items).forEach((item,n) ->
                adds.withArray("items").addObject().put("id", item).put("n", n));
        for (ObjectNode row : rows) {
            int slot = row.path("index").asInt(), sid = row.path("sid").asInt();
            if ((idx == 0 || idx == slot) && buildings.template(sid).has("minute")) buildings.save(id, slot, sid, now);
        }
        return true;
    }
}
