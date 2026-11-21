package com.pokemon.local.secret;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.inventory.InventoryService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 冻结秘境商人货物、镜像宝箱牌面并结算本局机遇。
 * @author tongruiyuan
 * @since 2026-11-20 11:39
 */
@Service
class SecretEncounterService {
    private final ObjectMapper json;
    private final InventoryService inventory;
    private final JdbcTemplate jdbc;
    private final JsonNode rules;

    /**
     * 读取四项首层委托共用的离线机遇配置。
     * @param json 存档序列化器
     * @param inventory 背包与统一资产服务
     * @param jdbc 当前事务的数据库连接
     * @throws IOException 机遇配置读取失败
     */
    SecretEncounterService(ObjectMapper json, InventoryService inventory, JdbcTemplate jdbc) throws IOException {
        this.json = json;
        this.inventory = inventory;
        this.jdbc = jdbc;
        try (InputStream in = new ClassPathResource("secret/encounters.json").getInputStream()) {
            rules = json.readTree(in);
        }
    }

    /** 获取委托的机遇和普通怪掉落配置。
     * @param sid 委托编号
     * @return 委托配置
     */
    public JsonNode task(long sid) {
        return rules.path("tasks").path(Long.toString(sid));
    }

    /**
     * 为本次委托生成唯一商人及镜像箱，兼容更新前已开始的存档。
     * @param run 本次秘境存档
     */
    void initialize(ObjectNode run) {
        if (run.has("contacts") || !rules.path("tasks").has(run.path("task").asText())) {
            return;
        }
        ObjectNode contacts = run.putObject("contacts");
        JsonNode shop = rules.path("merchant");
        ObjectNode merchant = createContact(contacts, "merchant", shop.path("npc").asInt(), 9, 8);
        JsonNode goods = shop.path("goods");
        ObjectNode product = goods.get(ThreadLocalRandom.current().nextInt(goods.size())).deepCopy();
        merchant.set("item", product.put("species", shop.path("species").asInt())
                .put("killmoney", shop.path("killmoney").asInt()).put("buy_flag", 0));
        JsonNode box = rules.path("mirror");
        ObjectNode mirror = createContact(contacts, "mirror", box.path("npc").asInt(), 10, 8);
        List<JsonNode> cards = new ArrayList<>();
        for (JsonNode row : box.path("rewards")) {
            for (int i = 0; i < row.path("slots").asInt(); i++) {
                ObjectNode reward = row.deepCopy();
                reward.remove("slots");
                cards.add(reward);
            }
        }
        if (cards.size() != box.path("count").asInt()) {
            throw new IllegalStateException("镜像箱卡片数量与面板不符");
        }
        Collections.shuffle(cards);
        ArrayNode deck = mirror.putArray("deck");
        for (JsonNode reward : cards) {
            deck.addObject().set("reward", reward);
        }
    }

    /**
     * 创建场景中有实际位置和身份的原版 NPC。
     * @param contacts 本局机遇存档
     * @param kind 机遇类型
     * @param template 原 NPC 模板编号
     * @param x 地图横向格
     * @param y 地图纵向格
     * @return 对应机遇存档
     */
    private ObjectNode createContact(ObjectNode contacts, String kind, int template, int x, int y) {
        ObjectNode contact = contacts.putObject(kind);
        ObjectNode actor = contact.putObject("actor").put("sid", template)
                .put("uid", UUID.randomUUID().toString()).put("face", 0).put("state", 0).put("iscommon", true);
        actor.putObject("pos").put("x", x * 32).put("y", y * 32);
        return contact;
    }

    /**
     * 从已揭开的本局位置定位指定 NPC。
     * @param run 本局地图
     * @param kind 机遇类型
     * @param npc NPC 模板号
     * @param x 原对话发送的横向格号
     * @param y 原对话发送的纵向格号
     * @return 校验成功的机遇，失败返回空
     */
    private ObjectNode find(ObjectNode run, String kind, long npc, long x, long y) {
        JsonNode contact = run.path("contacts").path(kind);
        JsonNode actor = contact.path("actor");
        JsonNode pos = actor.path("pos");
        if (!pos.isObject() || actor.path("sid").asLong(-1) != npc
                || pos.path("x").asInt() / 32 != x || pos.path("y").asInt() / 32 != y) {
            return null;
        }
        for (JsonNode tile : run.path("open")) {
            if (tile.equals(pos)) {
                return (ObjectNode) contact;
            }
        }
        return null;
    }

    /**
     * 查询冻结的商品或购买独立奥义石。背包满时保留商品和钻石。
     * @param role 当前角色
     * @param run 本局存档
     * @param npc 商人模板
     * @param x 横向格号
     * @param y 纵向格号
     * @param purchase 是否实际购买
     * @param receipt 客户端请求凭据
     * @return 成交或查询的商品，失败返回空
     */
    ObjectNode merchant(String role, ObjectNode run, long npc, long x, long y, boolean purchase, String receipt) {
        ObjectNode merchant = find(run, "merchant", npc, x, y);
        if (merchant == null) {
            return null;
        }
        ObjectNode item = (ObjectNode) merchant.get("item");
        if (!purchase || receipt.equals(merchant.path("receipt").asText())) {
            return item;
        }
        if (merchant.has("receipt") || inventory.ordinaryUsed(role) >= inventory.ordinaryCapacity(role)
                || !inventory.spendMaterials(role, Collections.emptyMap(), 0, item.path("number").asInt())) {
            return null;
        }
        jdbc.update("INSERT INTO local_secret_stone(sid,role_id,template_id) VALUES(?,?,?)",
                UUID.randomUUID().toString(), role, item.path("prop").asInt());
        merchant.put("receipt", receipt);
        item.put("buy_flag", 1);
        return item;
    }

    /**
     * 查询镜像箱，或按原单开及全开价原子结算尚未领取的牌面。
     * @param role 当前角色
     * @param run 本局存档
     * @param npc 镜像 NPC 模板
     * @param x 横向格号
     * @param y 纵向格号
     * @param command 原请求编号
     * @param index 单开牌位，从一开始
     * @param receipt 本连接请求标识
     * @return 最新 S2032 数据，失败返回空
     */
    ObjectNode mirror(String role, ObjectNode run, long npc, long x, long y, int command, long index, String receipt) {
        ObjectNode mirror = find(run, "mirror", npc, x, y);
        if (mirror == null) {
            return null;
        }
        ArrayNode deck = (ArrayNode) mirror.get("deck");
        int opened = mirror.path("opened").asInt();
        JsonNode task = rules.path("tasks").path(run.path("task").asText());
        if (command != 5082) {
            boolean single = command == 5080;
            if (single && (index < 1 || index > deck.size())) {
                return null;
            }
            long key = single ? index : -1;
            JsonNode previous = mirror.path("receipts").path(receipt);
            if (previous.isMissingNode()) {
                if ((!single && opened != 0) || opened == deck.size() || single && deck.get((int) index - 1).has("index")) {
                    return null;
                }
                int paidIndex = opened - rules.path("mirror").path("free").asInt();
                int price = single ? (paidIndex < 0 ? 0 : task.path("singleDiamonds").get(paidIndex).asInt())
                        : task.path("allDiamonds").asInt();
                if (!inventory.spendMaterials(role, Collections.emptyMap(), 0, price)) {
                    return null;
                }
                Map<Integer, Long> awards = new LinkedHashMap<>();
                for (int i = 0; i < deck.size(); i++) {
                    ObjectNode card = (ObjectNode) deck.get(i);
                    if (!card.has("index") && (!single || i + 1 == index)) {
                        JsonNode reward = card.path("reward");
                        awards.merge(reward.path("id").asInt(), reward.path("num").asLong(), Long::sum);
                        card.put("index", i + 1);
                        opened++;
                    }
                }
                String source = "mirror:" + mirror.path("actor").path("uid").asText() + ":" + key;
                inventory.grantItemsOrMail(role, source, awards);
                mirror.put("opened", opened).with("receipts").put(receipt, key);
            } else if (previous.asLong() != key) {
                return null;
            }
        }
        ObjectNode result = json.createObjectNode().put("sid", task.path("boxSid").asInt()).put("num", opened);
        ArrayNode info = result.putArray("info");
        if (opened == 0) {
            // 首次展示奖池构成，实际卡位只在翻开时公开。
            for (JsonNode row : rules.path("mirror").path("rewards")) {
                ObjectNode reward = row.deepCopy();
                reward.remove("slots");
                for (int i = 0; i < row.path("slots").asInt(); i++) {
                    info.addObject().set("reward", reward);
                }
            }
        } else {
            for (JsonNode card : deck) {
                if (card.has("index")) {
                    info.add(card.deepCopy());
                }
            }
        }
        return result;
    }
}
