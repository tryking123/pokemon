package com.pokemon.local.elf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 奥义石融合、洗练候选和属性保存 Service
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:11
 */
@Service
public class StoneFusionService {
    private static final int[][] RATES = {{100,50,0}, {50,30,30}, {30,20,10}};
    private static final int[] WASH_ITEMS = {1,2,4,6,8,10};
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ObjectMapper json;
    private final JsonNode rules;
    private final ElfMapper elves;

    /**
     * 读取原装备及附加属性配置。
     * @param jdbc 本地存档
     * @param roles 角色行锁
     * @param inventory 背包及钱包结算
     * @param elves 精灵实例及当前穿戴
     * @param json 随包数据解析器
     * @throws IOException 原配置无法读取
     */
    public StoneFusionService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory, ObjectMapper json, ElfMapper elves) throws IOException {
        this.elves = elves;
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.json = json;
        try (InputStream input = new ClassPathResource("elf/stone-attributes.json").getInputStream()) {
            rules = json.readTree(input);
        }
    }

    /**
     * 查询奥义石是否已经绑定当前角色的一只精灵。
     * @param role 角色编号
     * @param sid 奥义石实例
     * @return 是否已穿戴
     */
    public boolean equipped(String role, String sid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM local_secret_stone WHERE role_id=? AND sid=? AND elf_id IS NOT NULL",
                Integer.class, role, sid) != 0;
    }

    /**
     * 在角色锁内穿上、替换或卸下单个奥义石，回传当前精灵和实际卸下的实例。
     * @param role 当前角色编号
     * @param sid 石头实例编号
     * @param elfId 操作的精灵实例编号
     * @param mode 原按钮操作，一为穿上，二为卸下
     * @param key 当前连接和请求号
     * @return 成功时的精灵与卸下石，失败时为空
     */
    @Transactional
    public Wear wear(String role, String sid, String elfId, long mode, String key) {
        roles.lockRole(role);
        com.pokemon.local.elf.api.entity.LocalElf elf = elves.findOwned(role, elfId).orElse(null);
        if (elf == null || sid == null || sid.isEmpty() || mode < 1 || mode > 2
                || jdbc.queryForObject("SELECT COUNT(*) FROM local_stone_action WHERE role_id=? AND receipt=?",
                        Integer.class, role, key) != 0) {
            return null;
        }
        ObjectNode old = elf.stone.deepCopy();
        if (mode == 2) {
            if (!sid.equals(old.path("sid").asText()) || inventory.ordinaryUsed(role) >= inventory.ordinaryCapacity(role)) {
                return null;
            }
            jdbc.update("UPDATE local_secret_stone SET elf_id=NULL WHERE role_id=? AND sid=? AND elf_id=?", role, sid, elfId);
        } else {
            List<ObjectNode> bag = jdbc.query("SELECT template_id,attrs FROM local_secret_stone WHERE role_id=? "
                    + "AND sid=? AND elf_id IS NULL", (row, number) -> {
                ObjectNode stone = json.createObjectNode().put("id", row.getInt(1));
                stone.set("attrs", attributes(row.getInt(1), sid, row.getString(2)));
                return stone;
            }, role, sid);
            if (bag.isEmpty() || elf.level < StoneWearRules.level(bag.get(0).path("id").asInt())) {
                return null;
            }
            if (old.has("sid")) {
                jdbc.update("UPDATE local_secret_stone SET elf_id=NULL WHERE role_id=? AND elf_id=?", role, elfId);
            }
            if (jdbc.update("UPDATE local_secret_stone SET elf_id=?,attrs=? WHERE role_id=? AND sid=? AND elf_id IS NULL",
                    elfId, bag.get(0).path("attrs").toString(), role, sid) != 1) {
                throw new IllegalStateException("穿戴事务中奥义石归属变更");
            }
        }
        jdbc.update("INSERT INTO local_stone_action(role_id,receipt,reply) VALUES(?,?,?)", role, key, sid);
        return new Wear(elves.findOwned(role, elfId).orElseThrow(IllegalStateException::new), old);
    }

    /**
     * 已提交的奥义石穿卸结果。
     * @author tongruiyuan
     * @since 2026-11-20 09:55
     */
    public static final class Wear {
        public final com.pokemon.local.elf.api.entity.LocalElf elf;
        public final ObjectNode removed;

        /**
         * 记录已提交的精灵状态及卸下的石头。
         * @param elf 当前精灵
         * @param removed 原穿戴石，原未穿戴时为空对象
         */
        Wear(com.pokemon.local.elf.api.entity.LocalElf elf, ObjectNode removed) {
            this.elf = elf;
            this.removed = removed;
        }
    }

    /**
     * 还原已保存的属性。旧档按实例标识固定初始抽取，重启可重现。
     * @param template 奥义石模板
     * @param sid 实例标识
     * @param saved 已保存的属性数组
     * @return 原协议属性列表
     */
    public ArrayNode attributes(int template, String sid, String saved) {
        if (saved != null) {
            return (ArrayNode) parse(saved);
        }
        JsonNode rule = rules.path("templates").path(String.valueOf(template));
        ArrayNode attrs = json.createArrayNode();
        Random random = new Random(((long) sid.hashCode() << 32) ^ template);
        for (int i = 0; i < rule.path("initial").asInt(); i++) {
            attrs.add(draw(rule, attrs, random));
        }
        return attrs;
    }

    /**
     * 从尚未占用的原词条范围抽取一项。
     * @param rule 石头模板
     * @param taken 已保留的词条
     * @param random 抽取随机源
     * @return 词条原编号及区间内数值
     */
    private ObjectNode draw(JsonNode rule, ArrayNode taken, Random random) {
        List<Integer> pool = new ArrayList<>();
        Set<Integer> occupied = new HashSet<>();
        for (JsonNode attr : taken) {
            occupied.add(attr.path("attrid").asInt());
        }
        for (JsonNode entry : rule.path("pool")) {
            if (!occupied.contains(entry.asInt())) {
                pool.add(entry.asInt());
            }
        }
        int key = pool.get(random.nextInt(pool.size()));
        JsonNode range = rules.path("ranges").path(Integer.toString(key));
        int low = range.get(0).asInt();
        int value = low + random.nextInt(range.get(1).asInt() - low + 1);
        return json.createObjectNode().put("attrid", key).put("attrnum", value);
    }

    /**
     * 解析已提交的 JSON 存档。
     * @param stored 已保存的属性或回执
     * @return 解析结果
     */
    private JsonNode parse(String stored) {
        try {
            return json.readTree(stored);
        } catch (IOException e) {
            throw new IllegalStateException("奥义石存档无法读取", e);
        }
    }

    /**
     * 锁定角色并执行原融合、洗练、保存或取消动作。
     * @param role 角色标识
     * @param command 原协议编号
     * @param request 原请求字段
     * @param receipt 连接及请求回执编号
     * @return 客户端应答及实际成功结果
     */
    @Transactional
    public ObjectNode act(String role, int command, JsonNode request, String receipt) {
        roles.lockRole(role);
        List<String> prior = jdbc.queryForList("SELECT reply FROM local_stone_action WHERE role_id=? AND receipt=?",
                String.class, role, receipt);
        if (!prior.isEmpty()) {
            return (ObjectNode) parse(prior.get(0));
        }
        ObjectNode result = json.createObjectNode().put("errno", 1);
        String sid = request.path(command == 5012 ? "synthetic_A" : "uid").asText();
        List<ObjectNode> found = jdbc.query("SELECT template_id,stars,lucky,locked,attrs,candidate FROM local_secret_stone WHERE sid=? AND role_id=?",
                (row,index) -> {
                    ObjectNode item = json.createObjectNode().put("id", row.getInt(1)).put("sid", sid).put("n", 1)
                            .put("elfequ_star", row.getInt(2)).put("lucky", row.getInt(3)).put("lock", row.getInt(4) != 0)
                            .put("candidate", row.getString(6));
                    item.set("elfequ_addattr", attributes(row.getInt(1), sid, row.getString(5)));
                    return item;
                }, sid, role);
        if (found.isEmpty()) {
            return result;
        }
        ObjectNode stone = found.get(0);
        ArrayNode attrs = (ArrayNode) stone.path("elfequ_addattr");
        JsonNode template = rules.path("templates").path(stone.path("id").asText());
        if (template.isMissingNode()) {
            return result;
        }
        if (command == 5012) {
            fuse(role, request, stone, template, attrs, result);
        } else if (command == 5014) {
            wash(role, request, sid, template, attrs, result);
        } else if (command == 5016 && !stone.path("candidate").isNull()) {
            long mode = request.path("itype").asLong(-1);
            if (mode == 0 || mode == 1) {
                if (mode == 1) {
                    attrs = (ArrayNode) parse(stone.path("candidate").asText());
                }
                jdbc.update("UPDATE local_secret_stone SET attrs=?,candidate=NULL WHERE sid=? AND role_id=?", attrs.toString(), sid, role);
                result.put("errno", 0).set("attr", attrs);
            }
        }
        if (result.path("errno").asInt() == 0) {
            jdbc.update("INSERT INTO local_stone_action(role_id,receipt,reply) VALUES(?,?,?)", role, receipt, result.toString());
        }
        return result;
    }

    /**
     * 消耗同品质材料石并按原面板概率尝试增加一条附加属性。
     * @param role 角色标识
     * @param req 融合材料及提升道具数量
     * @param stone 主石
     * @param template 主石模板
     * @param attrs 当前附加属性
     * @param reply 原协议结果
     */
    private void fuse(String role, JsonNode req, ObjectNode stone, JsonNode template, ArrayNode attrs, ObjectNode reply) {
        String sid = stone.path("sid").asText();
        String material = req.path("synthetic_B").asText();
        if (sid.equals(material) || attrs.size() >= template.path("max").asInt()) {
            return;
        }
        List<Integer> donors = jdbc.queryForList("SELECT template_id FROM local_secret_stone WHERE role_id=? AND sid=? AND locked=0 AND elf_id IS NULL",
                Integer.class, role, material);
        int quality = template.path("quality").asInt();
        if (donors.isEmpty() || rules.path("templates").path(donors.get(0).toString()).path("quality").asInt() != quality) {
            return;
        }
        int tier = quality - 3;
        int extra = attrs.size() - template.path("initial").asInt();
        int rate = RATES[tier][Math.max(0, Math.min(2, extra))];
        long boosters = req.path("is_useitem").asLong(0);
        if (boosters < 0 || boosters > (100 - rate) / 10
                || !inventory.spendMaterials(role, 21048 + tier, boosters, 100000 * (tier + 1), 0)) {
            return;
        }
        if (jdbc.update("DELETE FROM local_secret_stone WHERE role_id=? AND sid=? AND locked=0 AND elf_id IS NULL", role, material) != 1) {
            throw new IllegalStateException("融合材料状态已变化");
        }
        boolean success = ThreadLocalRandom.current().nextInt(100) < rate + boosters * 10;
        if (success) {
            attrs.add(draw(template, attrs, ThreadLocalRandom.current()));
            jdbc.update("UPDATE local_secret_stone SET attrs=?,candidate=NULL WHERE role_id=? AND sid=?", attrs.toString(), role, sid);
        }
        stone.remove("candidate");
        reply.put("errno", 0).put("flags", success ? 1 : 0).set("info", stone);
    }

    /**
     * 原价洗练并保存候选，保留所有锁定槽位。
     * @param role 角色标识
     * @param req 付费类型和一基槽位列表
     * @param sid 实例标识
     * @param template 主石模板
     * @param attrs 当前属性
     * @param reply 原协议结果
     */
    private void wash(String role, JsonNode req, String sid, JsonNode template, ArrayNode attrs, ObjectNode reply) {
        Set<Integer> locked = new HashSet<>();
        ArrayNode occupied = json.createArrayNode();
        for (JsonNode slot : req.path("lock")) {
            long value = slot.asLong(-1);
            if (value < 1 || value > attrs.size() || !locked.add((int) value)) {
                return;
            }
            occupied.add(attrs.get((int) value - 1));
        }
        long method = req.path("use_itype").asLong(-1);
        if (locked.size() >= attrs.size() || (method != 1 && method != 2)) {
            return;
        }
        int count = WASH_ITEMS[locked.size()];
        if (!inventory.spendMaterials(role, 21061, method == 1 ? count : 0, 0, method == 2 ? count * 30 : 0)) {
            return;
        }
        ArrayNode candidate = attrs.deepCopy();
        for (int i = 0; i < attrs.size(); i++) {
            if (!locked.contains(i + 1)) {
                ObjectNode attr = draw(template, occupied, ThreadLocalRandom.current());
                occupied.add(attr);
                candidate.set(i, attr);
            }
        }
        jdbc.update("UPDATE local_secret_stone SET candidate=? WHERE role_id=? AND sid=?", candidate.toString(), role, sid);
        reply.put("errno", 0).set("attr", candidate);
    }
}
