package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.ElfTrainingCatalog;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;

import com.pokemon.offline.platform.stereotype.Service;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 地下城委托的属性筛选、派遣、计时及赛季加成。
 *
 * @author tongruiyuan
 * @since 2026-11-20 14:49
 */
@Service
class DnfTasks {
    private final ElfService elves;
    private final InventoryService inventory;
    private final DnfRewards rewards;
    private final ObjectMapper json;

    /**
     * 绑定委托的队员、消耗与发奖服务。
     *
     * @param elves 精灵背包
     * @param inventory 刷新消耗
     * @param rewards 委托奖励
     * @param json 消息编码器
     */
    DnfTasks(ElfService elves, InventoryService inventory, DnfRewards rewards, ObjectMapper json) {
        this.elves = elves;
        this.inventory = inventory;
        this.rewards = rewards;
        this.json = json;
    }

    /**
     * 按当前层生成原任务档及已确定的奖励。
     *
     * @param config 地下城配置
     * @param floor 当前层
     * @param index 难度编号
     * @return 任务预览
     */
    private ObjectNode create(JsonNode config, JsonNode floor, int index) {
        JsonNode demand = config.path("task").path(String.valueOf(index));
        JsonNode raw = demand.path("task");
        JsonNode drops = config.path("reward").path(floor.path("taskrew").asText());
        ObjectNode task = json.createObjectNode().put("index", index);
        ArrayNode need = task.putArray("need");
        java.util.List<Integer> candidates = new java.util.ArrayList<>();
        for (int off = 0; off < raw.size(); off += 4) candidates.add(off);
        Collections.shuffle(candidates);
        for (int i = 0; i < demand.path("tasknum").asInt(); i++) {
            int off = candidates.get(i);
            need.add(raw.get(off).asInt())
                    .add(
                            ThreadLocalRandom.current()
                                    .nextInt(
                                            raw.get(off + 2).asInt(),
                                            raw.get(off + 3).asInt() + 1));
        }
        task.set("award", drops.path("innrew" + index));
        JsonNode choices = drops.path("rewbuff" + index);
        JsonNode buff = choices.get(ThreadLocalRandom.current().nextInt(choices.size()));
        task.putArray("att").add(buff.get(1).asInt()).add(buff.get(2).asInt());
        task.putArray("elfs");
        return task;
    }

    /**
     * 重建当前层的五个委托位置。
     *
     * @param config 地下城配置
     * @param floor 当前层
     * @param s 当季存档
     */
    void generate(JsonNode config, JsonNode floor, ObjectNode s) {
        ArrayNode list = s.putArray("tasklist");
        for (JsonNode index : floor.path("taskbox")) list.add(create(config, floor, index.asInt()));
    }

    /**
     * 校验一只精灵是否满足对应的条件。
     *
     * @param elf 精灵
     * @param type 条件类型
     * @param value 原属性或下限
     * @return 是否满足
     */
    private boolean matches(LocalElf elf, int type, int value) {
        if (type == 1) {
            JsonNode species = ElfTrainingCatalog.row("species", elf.configId);
            return species.path("attribute1").asInt() == value
                    || species.path("attribute2").asInt() == value;
        }
        if (type == 2) return elf.training.path("star").asInt(1) >= value;
        if (type != 5) return false;
        int sum = 0;
        for (JsonNode stat : elf.training.path("individual")) sum += stat.asInt();
        return sum >= value;
    }

    /**
     * 执行刷新、派遣或到期领奖，调用方持有角色事务锁。
     *
     * @param role 角色
     * @param cmd 请求编号
     * @param input 原请求
     * @param config 地下城配置
     * @param floor 当前层
     * @param s 当季存档
     * @param key 幂等键
     * @param now 当前时间
     * @return 原应答及资产变动
     */
    ObjectNode act(
            String role,
            int cmd,
            JsonNode input,
            JsonNode config,
            JsonNode floor,
            ObjectNode s,
            String key,
            long now) {
        ObjectNode result = json.createObjectNode().put("errno", 1);
        ArrayNode list = (ArrayNode) s.get("tasklist");
        if (cmd == 299) {
            for (JsonNode task : list) if (task.has("deadline")) return result;
            int used = s.path("refreshtimes").asInt();
            boolean item = input.path("useitem").asBoolean();
            if (used > 0
                    && !inventory.spendMaterials(
                            role,
                            item ? Collections.singletonMap(21092, 1L) : Collections.emptyMap(),
                            0,
                            item ? 0 : 100)) return result;
            generate(config, floor, s);
            s.put("refreshtimes", used + 1);
            result.put("errno", 0)
                    .put("useitem", item)
                    .put("refreshtimes", used + 1)
                    .set("tasklist", s.get("tasklist"));
            return result;
        }
        int slot = input.path("idx").asInt();
        if (slot < 1 || slot > list.size()) return result;
        ObjectNode task = (ObjectNode) list.get(slot - 1);
        int index = task.path("index").asInt();
        if (cmd == 301) {
            if (!task.has("deadline") || now < task.path("deadline").asLong()) return result;
            result.set("adds", rewards.grant(role, s, task.path("award"), key));
            JsonNode att = task.path("att");
            ObjectNode buffs = s.with("buffs");
            String attr = att.get(0).asText();
            buffs.put(attr, buffs.path(attr).asLong() + att.get(1).asLong());
            ObjectNode next = create(config, floor, index);
            list.set(slot - 1, next);
            result.put("errno", 0).put("idx", slot).set("task", next);
            return result;
        }
        if (task.has("deadline")) return result;
        JsonNode demand = config.path("task").path(String.valueOf(index)),
                need = task.path("need"),
                ids = input.path("list");
        if (!ids.isArray()
                || ids.isEmpty()
                || ids.size() > need.size() / 2
                || s.path("score").asInt() < demand.path("spend").asInt()) return result;
        List<LocalElf> owned = elves.list(role);
        Set<String> occupied = new HashSet<>();
        for (JsonNode other : list)
            for (JsonNode id : other.path("elfs")) occupied.add(id.asText());
        java.util.List<LocalElf> party = new java.util.ArrayList<>();
        for (JsonNode id : ids) {
            String uid = id.asText();
            LocalElf elf = owned.stream().filter(e -> e.id.equals(uid)).findFirst().orElse(null);
            if (elf == null || !occupied.add(uid)) return result;
            party.add(elf);
        }
        for (int i = 0; i < need.size(); i += 2) {
            boolean found = false;
            for (LocalElf elf : party)
                if (matches(elf, need.get(i).asInt(), need.get(i + 1).asInt())) found = true;
            if (!found) return result;
        }
        long deadline = now + demand.path("time").asInt() * 60L;
        s.put("score", s.path("score").asInt() - demand.path("spend").asInt());
        task.put("deadline", deadline).set("elfs", ids.deepCopy());
        return result.put("errno", 0).put("idx", slot).put("deadline", deadline);
    }
}
