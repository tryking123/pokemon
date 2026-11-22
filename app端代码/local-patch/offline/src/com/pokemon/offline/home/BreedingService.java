package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 饲育屋的异性寄存、产蛋、宠物互动及原资产消耗。
 * @author tongruiyuan
 * @since 2026-11-19 21:48
 */
@Service
public class BreedingService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ElfService elves;
    private final HomeService home;
    private final RoleService roles;
    private final InventoryService inventory;
    private final JsonNode cfg = BreedingData.CONFIG;

    /** 绑定寄存个体、饲育屋及真实道具钱包。
     * @param jdbc 角色存档
     * @param json 结构化存档工具
     * @param elves 精灵所有权
     * @param home 饲育屋等级、心情与回执
     * @param roles 角色锁和钱包
     * @param inventory 道具收支
     */
    public BreedingService(JdbcTemplate jdbc, ObjectMapper json, ElfService elves, HomeService home, RoleService roles,
            InventoryService inventory) {
        this.jdbc = jdbc;
        this.json = json;
        this.elves = elves;
        this.home = home;
        this.roles = roles;
        this.inventory = inventory;
    }

    /** 读取寄存及宠物存档。
     * @param id 角色编号
     * @return 状态
     */
    private ObjectNode state(String id) {
        List<String> rows = jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?",
                String.class, id);
        if (rows.isEmpty())
            return json.createObjectNode();
        try {
            ObjectNode saved = (ObjectNode) json.readTree(rows.get(0));
            for (JsonNode row : saved.path("pets")) {
                ObjectNode pet = (ObjectNode) row;
                JsonNode template = cfg.path("pets").path(pet.path("id").asText());
                if (!template.isMissingNode()) {
                    Random legacy = new Random(UUID.nameUUIDFromBytes(pet.path("uid").asText()
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8)).getLeastSignificantBits());
                    ensureTalent(pet, template, "talent", "talent_pos", legacy);
                    if (!saved.path("petCustomizations").path(pet.path("uid").asText())
                            .path("talent2Disabled").asBoolean()) {
                        ensureTalent(pet, template, "talent_2", "talent_2_pos", legacy);
                    }
                }
            }
            return saved;
        } catch (IOException e) {
            throw new IllegalStateException("饲育屋存档不能解析", e);
        }
    }

    /** 自最近一次记账起按完整小时计算健康损耗。
     * @param pet 宠物
     * @param now 当前秒
     */
    private void health(ObjectNode pet, long now) {
        long last = pet.path("lastcheck").asLong(now), hours = Math.max(0, (now - last) / 3600);
        long before = Math.min(hours, Math.max(0, (pet.path("lastshit").asLong() + 14400 - last) / 3600));
        long current = Math.max(0, pet.path("health").asInt() - before * 4 - (hours - before) * 8);
        pet.put("health", current).put("lastcheck", last + hours * 3600);
    }

    /** 获取饲育屋 S818，服务器时间与当前寄存进度一致。
     * @param id 角色编号
     * @return 原饲育快照
     */
    public ObjectNode feedlot(String id) {
        ObjectNode s = state(id), r = json.createObjectNode().put("level", home.level(id))
                .put("degree", s.path("origin").asInt())
                .put("count", s.path("eggs").size()).put("flag", false);
        // 原客户端以字段是否存在判断寄存状态，零在 Lua 中也会被视为真。
        if (s.has("pair"))
            r.put("entrustime", s.path("start").asLong());
        r.set("deposit", s.has("pair") ? s.path("pair").deepCopy() : json.createArrayNode());
        ArrayNode list = r.putArray("list");
        s.path("eggs").elements().forEachRemaining(list::add);
        return r;
    }

    /** 获取当前角色真实孵出的完整宠物集合。
     * @param id 角色编号
     * @return 原 S2047 宠物列表
     */
    public ArrayNode pets(String id) {
        ArrayNode list = json.createArrayNode();
        long now = Instant.now().getEpochSecond();
        for (JsonNode row : state(id).path("pets")) {
            ObjectNode pet = row.deepCopy();
            health(pet, now);
            pet.put("use_times", pet.path("use_times").asInt());
            pet.put("wash_times", pet.path("wash_times").asInt());
            list.add(pet);
        }
        return list;
    }

    /** 处理饲育屋原业务消息。
     * @param id 角色编号
     * @param type 协议号
     * @param pair 待寄存的精灵或升星选用的宠物实例
     * @param uid 宠物或蛋的实例编号
     * @param egg 是否对蛋操作
     * @param option 互动项目
     * @param receipt 请求回执
     * @return 原响应以及实得奖励
     */
    @Transactional
    public ObjectNode handle(String id, int type, List<?> pair, String uid, boolean egg, int option, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode out = json.createObjectNode().put("errno", 100021);
        String key = (type >= 10301 ? "pet:" + type + ":" : "breeding:") + receipt, prior = home.reply(id, key);
        if (prior != null)
            try {
                return ((ObjectNode) json.readTree(prior)).put("replayed", true);
            } catch (IOException e) {
                throw new IllegalStateException("寄存回执损坏", e);
            }
        boolean growth = type >= 10301;
        if (growth ? role.level < cfg.path("growthGate").asInt()
                : (role.sceneId != 80011 && role.sceneId != 80012) || home.level(id) == 0)
            return out;
        ObjectNode s = state(id);
        long now = Instant.now().getEpochSecond();
        boolean ok;
        switch (type) {
        case 10304:
            ok = wash(id, s, uid, pair, out);
            break;
        case 10309:
            ok = confirmWash(s, uid);
            break;
        case 10307:
            ok = expandWash(id, s, uid);
            break;
        case 10308:
            ok = releasePets(id, s, pair, out, key);
            break;
        case 10301:
        case 10302:
            ok = growPet(id, s, uid, type == 10302, pair, out);
            break;
        case 362:
            ok = foster(id, pair, s, out, now);
            break;
        case 363:
            ok = s.has("pair");
            if (ok)
                clear(s);
            break;
        case 364:
            ok = collect(s, out, now);
            break;
        case 365:
            ok = interact(id, s, out, uid, egg, option, now);
            break;
        case 369:
            int missing = 100 - degree(s, now);
            ok = s.has("pair") && missing > 0;
            if (ok && !inventory.spendMaterials(id, Collections.emptyMap(), 0, missing * 200L))
                return out.put("errno", 100006);
            if (ok) {
                s.put("origin", 100).put("start", now);
                out.put("degree", 100);
            }
            break;
        default:
            return out;
        }
        if (!ok)
            return out;
        jdbc.update("MERGE INTO local_home_breeding(role_id,state_json) KEY(role_id) VALUES(?,?)", id, s.toString());
        out.put("errno", 0);
        ObjectNode wire = out.deepCopy();
        wire.remove(Arrays.asList("adds", "loses", "mailed"));
        home.record(id, key, wire.toString());
        return out;
    }

    /**
     * 校验本宠与材料宠的蛋组、占用状态及洗炼额度，生成待确认的属性。
     * @param id 角色编号
     * @param state 饲育存档
     * @param uid 目标宠物编号
     * @param selected 选中的同蛋组材料宠物
     * @param out 返回原实例编号和候选宠物
     * @return 是否完成扣费并保存候选
     */
    private boolean wash(String id, ObjectNode state, String uid, List<?> selected, ObjectNode out) {
        JsonNode pets = state.path("pets");
        if (!pets.path(uid).isObject() || selected.size() != 1 || !(selected.get(0) instanceof String))
            return false;
        String source = (String) selected.get(0);
        ObjectNode pet = (ObjectNode) pets.get(uid);
        JsonNode material = pets.path(source);
        JsonNode template = cfg.path("pets").path(pet.path("id").asText());
        JsonNode sourceTemplate = cfg.path("pets").path(material.path("id").asText());
        int aptitude = pet.path("aptitudes").asInt(), sourceAptitude = material.path("aptitudes").asInt();
        int count = pet.path("aureole_id").size(), offered = material.path("aureole_id").size();
        if (!material.isObject() || uid.equals(source) || deployed(material) || template.isMissingNode()
                || sourceTemplate.path("type").asInt() != template.path("qualified").asInt()
                || aptitude < 1 || aptitude > 4 || sourceAptitude < 1 || sourceAptitude > 4
                || count < 1 || count > 4 || offered < 1 || offered > 4
                || pet.path("wash_times").asLong() >= cfg.path("washLimit").path("base").asLong() + pet.path("use_times").asLong())
            return false;
        Map<Integer, Long> items = new LinkedHashMap<>();
        JsonNode cost = template.path("item_id");
        for (int i = 1; i <= cost.size(); i += 3)
            items.merge(cost.path(Integer.toString(i + 1)).asInt(), cost.path(Integer.toString(i + 2)).asLong(), Long::sum);
        if (!inventory.spendMaterials(id, items, 0, 0))
            return false;
        JsonNode odds = cfg.path("washAptitude").get(Integer.compare(sourceAptitude, aptitude) + 1);
        int roll = ThreadLocalRandom.current().nextInt(10000);
        int change = roll < odds.get(0).asInt() ? -1 : roll < odds.get(0).asInt() + odds.get(1).asInt() ? 0 : 1;
        ObjectNode pending = json.createObjectNode().put("aptitudes", Math.max(1, Math.min(4, aptitude + change)));
        int extra = offered > count ? draw(cfg.path("washAuras").path(Integer.toString(10 * offered + count))) : 0;
        List<Integer> retained = new ArrayList<>();
        for (JsonNode aura : pet.path("aureole_id"))
            retained.add(aura.asInt());
        Collections.shuffle(retained);
        Set<Integer> families = new HashSet<>();
        ArrayNode auras = pending.putArray("aureole_id");
        for (int i = 0; i < count - offered; i++) {
            auras.add(retained.get(i));
            families.add(retained.get(i) / 1000);
        }
        fillAuras(pending, count + extra, families, auras);
        ensureTalent(pending, template, "talent", "talent_pos", ThreadLocalRandom.current());
        ensureTalent(pending, template, "talent_2", "talent_2_pos", ThreadLocalRandom.current());
        pet.put("wash_times", pet.path("wash_times").asInt() + 1);
        ObjectNode preview = pet.deepCopy();
        preview.setAll(pending);
        state.with("washPending").set(uid, pending);
        state.with("washPending").remove(source);
        out.put("id", uid).set("data", preview);
        out.putObject("loses").putArray("pet").add(state.with("pets").remove(source));
        return true;
    }

    /**
     * 按物种的天赋权重和天赋阵位表为候选生成天赋及生效位置。
     * @param pending 待确认的养成属性
     * @param template 原物种数据
     * @param field 天赋编号字段
     * @param positions 阵位字段
     * @param random 新实例随机源或旧档的稳定补全随机源
     */
    private void ensureTalent(ObjectNode pending, JsonNode template, String field, String positions, Random random) {
        int talentId = pending.path(field).asInt();
        if (!cfg.path("talentSlots").has(Integer.toString(talentId)))
            talentId = draw(template.path("talent"), random);
        pending.put(field, talentId);
        JsonNode rule = cfg.path("talentSlots").path(Integer.toString(talentId));
        if (pending.path(positions).size() == rule.get(0).asInt())
            return;
        List<Integer> slots = new ArrayList<>();
        rule.get(1).forEach(slot -> slots.add(slot.asInt()));
        Collections.shuffle(slots, random);
        ArrayNode active = pending.putArray(positions);
        for (int i = 0; i < rule.get(0).asInt(); i++)
            active.add(slots.get(i));
    }

    /**
     * 只替换候选属性，保留洗炼后独立发生的亲密、等级和额度变化。
     * @param state 饲育屋存档
     * @param uid 待确认的宠物编号
     * @return 是否有可提交的候选
     */
    private boolean confirmWash(ObjectNode state, String uid) {
        JsonNode pet = state.path("pets").path(uid), pending = state.path("washPending").path(uid);
        if (!pet.isObject() || !pending.isObject())
            return false;
        ((ObjectNode) pet).setAll((ObjectNode) pending);
        state.with("washPending").remove(uid);
        return true;
    }

    /**
     * 消耗洗炼石，增加当前宠物的额外洗炼上限。
     * @param id 角色编号
     * @param state 饲育屋存档
     * @param uid 宠物实例
     * @return 是否增加额度
     */
    private boolean expandWash(String id, ObjectNode state, String uid) {
        JsonNode pet = state.path("pets").path(uid);
        JsonNode rules = cfg.path("washLimit");
        int extra = rules.path("extra").asInt();
        if (!pet.isObject() || pet.path("use_times").asLong() + extra + rules.path("base").asInt() > Integer.MAX_VALUE)
            return false;
        if (!inventory.spendMaterials(id, rules.path("item").asInt(), rules.path("cost").asLong(), 0, 0))
            return false;
        ((ObjectNode) pet).put("use_times", pet.path("use_times").asInt() + extra);
        return true;
    }

    /**
     * 依据原放生预览返还升级和升星投入的两类培养道具。
     * @param id 角色编号
     * @param state 饲育屋存档
     * @param selected 批量放生的宠物实例编号
     * @param out 实际删除的宠物与入包物品
     * @param receipt 本批次发放回执
     * @return 是否按完整选择完成放生
     */
    private boolean releasePets(String id, ObjectNode state, List<?> selected, ObjectNode out, String receipt) {
        JsonNode pets = state.path("pets");
        if (selected.isEmpty() || selected.size() > pets.size())
            return false;
        Set<String> unique = new LinkedHashSet<>();
        Map<Integer, Long> refund = new LinkedHashMap<>();
        for (Object value : selected) {
            if (!(value instanceof String) || !unique.add((String) value))
                return false;
            JsonNode pet = pets.path((String) value);
            if (!pet.isObject() || deployed(pet))
                return false;
            int level = pet.path("level").asInt();
            int star = pet.path("star").asInt();
            if (level < 1 || level > cfg.path("growth").size() || star < 0 || star > 5)
                return false;
            for (int n = 1; n <= level; n++) {
                JsonNode row = cfg.path("growth").path(Integer.toString(n));
                if (n < level)
                    addRefund(refund, row.path("cost1"));
                if (row.has("stat_cost") && row.path("star").asInt() <= star)
                    addRefund(refund, row.path("stat_cost"));
            }
        }
        for (long amount : refund.values())
            if (amount > Integer.MAX_VALUE)
                return false;
        Map<Integer, Long> delivered = inventory.grantItemsOrMail(id, receipt, refund);
        out.put("mailed", !refund.equals(delivered));
        ArrayNode items = out.putObject("adds").putArray("items");
        delivered.forEach((item, count) -> items.addObject().put("id", item).put("n", count));
        ArrayNode consumed = out.putObject("loses").putArray("pet");
        for (String pet : unique) {
            consumed.add(state.with("pets").remove(pet));
            state.with("washPending").remove(pet);
        }
        return true;
    }

    /**
     * 累加原界面升级或升星时实际列出的材料。
     * @param refund 按道具编号汇总的返还
     * @param cost 本档消耗
     */
    private void addRefund(Map<Integer, Long> refund, JsonNode cost) {
        refund.merge(30011, cost.get(0).asLong(), Long::sum);
        refund.merge(30012, cost.get(1).asLong(), Long::sum);
    }

    /**
     * 核对宠物的队伍或玩法占用状态。
     * @param pet 待检查的实例
     * @return 是否存在出战或派遣占用
     */
    private boolean deployed(JsonNode pet) {
        for (JsonNode status : pet.path("status"))
            if (status.asInt() >= 10)
                return true;
        return false;
    }

    /**
     * 依当前等级底表消费材料，升级或用已选的同类宠物突破星级。
     * @param id 角色编号
     * @param state 饲育屋存档
     * @param uid 养成目标的实例编号
     * @param starUp 是否执行升星
     * @param selected 本次选择消耗的宠物实例
     * @param out 返回等级或星级及已扣除的宠物
     * @return 本次是否已结算
     */
    private boolean growPet(String id, ObjectNode state, String uid, boolean starUp, List<?> selected, ObjectNode out) {
        JsonNode target = state.path("pets").path(uid);
        if (!target.isObject())
            return false;
        ObjectNode pet = (ObjectNode) target;
        int level = pet.path("level").asInt();
        JsonNode levels = cfg.path("growth");
        JsonNode step = levels.path(Integer.toString(level));
        if (!levels.has(Integer.toString(level + 1)) || pet.path("love").asInt() < step.path("love").asInt())
            return false;
        boolean needsStar = step.has("stat_cost") && pet.path("star").asInt() < step.path("star").asInt();
        if (starUp != needsStar)
            return false;

        Set<String> materials = new LinkedHashSet<>();
        if (starUp) {
            if (selected.size() != step.path("count").asInt())
                return false;
            int requiredType = cfg.path("pets").path(pet.path("id").asText()).path("qualified").asInt();
            for (Object value : selected) {
                if (!(value instanceof String) || uid.equals(value) || !materials.add((String) value))
                    return false;
                JsonNode candidate = state.path("pets").path((String) value);
                JsonNode species = cfg.path("pets").path(candidate.path("id").asText());
                if (!candidate.isObject() || deployed(candidate) || species.path("type").asInt(-1) != requiredType)
                    return false;
            }
        }
        JsonNode cost = step.path(starUp ? "stat_cost" : "cost1");
        Map<Integer, Long> items = new LinkedHashMap<>();
        items.put(30011, cost.get(0).asLong());
        items.put(30012, cost.get(1).asLong());
        if (!inventory.spendMaterials(id, items, cost.get(2).asLong(), 0))
            return false;
        if (starUp) {
            ArrayNode consumed = out.putObject("loses").putArray("pet");
            for (String material : materials) {
                consumed.add(state.with("pets").remove(material));
                state.with("washPending").remove(material);
            }
            int nextStar = step.path("star").asInt();
            pet.put("star", nextStar);
            out.put("newstar", nextStar);
        } else {
            pet.put("level", level + 1);
            out.put("newlevel", level + 1);
        }
        out.put("id", uid);
        return true;
    }

    /** 按原组别与愉悦公式寄存两个拥有的个体。
     * @param id 角色编号
     * @param pair 选择的实例编号
     * @param s 状态
     * @param out 原协议结果
     * @param now 当前秒
     * @return 寄存成功
     */
    private boolean foster(String id, List<?> pair, ObjectNode s, ObjectNode out, long now) {
        if (s.has("pair") || pair.size() != 2 || !(pair.get(0) instanceof String) || !(pair.get(1) instanceof String)
                || pair.get(0).equals(pair.get(1)))
            return false;
        LocalElf first = null, second = null;
        for (LocalElf elf : elves.list(id)) {
            if (elf.id.equals(pair.get(0)))
                first = elf;
            else if (elf.id.equals(pair.get(1)))
                second = elf;
        }
        if (first == null || second == null || BreedingData.gender(first) == BreedingData.gender(second))
            return false;
        JsonNode a = cfg.path("species").path(Integer.toString(first.configId)),
                b = cfg.path("species").path(Integer.toString(second.configId));
        if (!a.isArray() || !b.isArray() || a.get(1).asInt() != b.get(1).asInt())
            return false;
        int group = a.get(1).asInt(), item = cfg.path("groups").path(Integer.toString(group)).get(0).asInt();
        if (!inventory.spendMaterials(id, item, 1, 0, 0)) {
            out.put("errno", 100004);
            return false;
        }
        int diff = Math.abs(a.get(2).asInt() - b.get(2).asInt());
        double rate = (Math.min(10, a.get(3).asInt()) + Math.min(10, b.get(3).asInt())) / 20.0;
        int degree = Math.max(0, (int) Math.floor((90 - Math.pow(diff, 1.54) * .002) * rate));
        s.put("group", group).put("origin", degree).put("start", now).set("pair", json.valueToTree(pair));
        out.put("origin", degree).put("now", now).set("list", s.path("pair").deepCopy());
        return true;
    }

    /** 当前寄存愉悦度。
     * @param s 寄存状态
     * @param now 当前秒
     * @return 0 至 100
     */
    private int degree(ObjectNode s, long now) {
        return (int) Math.min(100, s.path("origin").asInt() + Math.max(0, now - s.path("start").asLong(now)) / 600);
    }

    /** 结束当前寄存并释放配对。
     * @param s 存档
     */
    private void clear(ObjectNode s) {
        s.remove(Arrays.asList("pair", "origin", "start", "group"));
    }

    /** 取得原组别的宠物蛋。
     * @param s 存档
     * @param out 结算结果
     * @param now 当前秒
     * @return 取蛋成功
     */
    private boolean collect(ObjectNode s, ObjectNode out, long now) {
        if (!s.has("pair") || degree(s, now) < 100 || s.path("eggs").size() >= 100)
            return false;
        String uid = UUID.randomUUID().toString();
        ObjectNode egg = s.with("eggs").putObject(uid).put("uid", uid).put("id", s.path("group").asInt())
                .put("level", 1).put("love", 0).put("loveexp", 0);
        ArrayNode cooldown = egg.putArray("cooldown");
        for (int i = 0; i < 6; i++)
            cooldown.add(0);
        out.putObject("adds").putArray("egg").add(egg.deepCopy());
        clear(s);
        return true;
    }

    /** 从原权重和结果交替数组抽取一个属性结果。
     * @param table 权重和编号
     * @return 抽中的原编号
     */
    private int draw(JsonNode table) {
        return draw(table, ThreadLocalRandom.current());
    }

    /**
     * 从万分比配置中按指定的随机序列抽取。
     * @param table 权重与编号
     * @param random 随机源
     * @return 抽中的配置编号
     */
    private int draw(JsonNode table, Random random) {
        int n = random.nextInt(10000);
        for (int i = 1; i <= table.size(); i += 2) {
            n -= table.path(Integer.toString(i)).asInt();
            if (n < 0)
                return table.path(Integer.toString(i + 1)).asInt();
        }
        return 0;
    }

    /** 依据原光环数量及资质对应光环库生成不同类型的初生光环。
     * @param pet 孵化宠物
     * @param template 原宠物配置
     */
    private void auras(ObjectNode pet, JsonNode template) {
        int count = draw(template.path("aureole_num"));
        fillAuras(pet, count, new HashSet<>(), pet.putArray("aureole_id"));
    }

    /**
     * 从宠物资质对应的底表中抽取指定数量的不同系光环。
     * @param pet 宠物资质
     * @param count 目标光环总数
     * @param families 已保留的光环系别
     * @param result 写入光环编号的数组
     */
    private void fillAuras(ObjectNode pet, int count, Set<Integer> families, ArrayNode result) {
        for (int slot = result.size(); slot < count; slot++) {
            int pool = draw(cfg.path("poolWeights").path(pet.path("aptitudes").asText()));
            JsonNode choices = cfg.path("auras").path(Integer.toString(pool));
            int total = 0, selected = 0;
            for (int n = 1; n <= choices.size(); n += 2) {
                int id = choices.path(Integer.toString(n + 1)).asInt();
                if (families.contains(id / 1000))
                    continue;
                int weight = choices.path(Integer.toString(n)).asInt();
                total += weight;
                if (weight > 0 && ThreadLocalRandom.current().nextInt(total) < weight)
                    selected = id;
            }
            if (selected != 0) {
                result.add(selected);
                families.add(selected / 1000);
            }
        }
    }

    /**
     * 将筹码兑换的成宠写入当前角色饲育屋，与孵化沿用同一套天赋和光环规则。
     * @param id 角色编号
     * @param template 原宠物模板
     * @param quantity 发放数量
     * @return 新增的宠物实例
     */
    @Transactional
    public ArrayNode grantPets(String id, int template, int quantity) {
        roles.lockRole(id);
        if (quantity <= 0 || !cfg.path("pets").has(Integer.toString(template)))
            throw new IllegalArgumentException("兑换成宠的模板或数量无效");
        ObjectNode state = state(id);
        ArrayNode added = json.createArrayNode();
        long now = Instant.now().getEpochSecond();
        for (int n = 0; n < quantity; n++)
            added.add(createPet(state, template, now));
        savePets(id, state);
        return added;
    }

    /**
     * 更新牌局专用的宠物出战位置，保留其余占用标记。
     * @param id 角色编号
     * @param uid 解散或新选的宠物实例
     * @param deploy 是否出战
     * @return 有效调整后的状态，非法或不存在时返回空值
     */
    @Transactional
    public ArrayNode casinoPet(String id, String uid, boolean deploy) {
        roles.lockRole(id);
        ObjectNode state = state(id);
        JsonNode selected = state.path("pets").path(uid);
        if (!selected.isObject()) return null;
        ArrayNode old = ((ObjectNode) selected).withArray("status");
        if (deploy && old.size() != 0 || !deploy && !hasCasinoStatus(old)) return null;
        for (JsonNode row : state.path("pets")) {
            ArrayNode statuses = ((ObjectNode) row).withArray("status");
            for (int i = statuses.size()-1; i>=0; i--)
                if (statuses.get(i).asInt() == 14) statuses.remove(i);
        }
        if (deploy) old.add(14);
        savePets(id, state);
        return old;
    }

    /** 判断是否占用牌局位置。
     * @param statuses 宠物占用列表
     * @return 已在牌桌上
     */
    private boolean hasCasinoStatus(ArrayNode statuses) {
        for (JsonNode entry : statuses) if (entry.asInt() == 14) return true;
        return false;
    }

    /** 保存当前锁定角色的宠物仓库。
     * @param id 角色编号
     * @param state 饲育屋状态
     */
    private void savePets(String id, ObjectNode state) {
        jdbc.update("MERGE INTO local_home_breeding(role_id,state_json) KEY(role_id) VALUES(?,?)", id, state.toString());
    }

    /** 生成并登记一只全新的成宠。
     * @param s 饲育屋存档
     * @param petId 原模板编号
     * @param now 领到成宠的时间
     * @return 完整宠物实例
     */
    private ObjectNode createPet(ObjectNode s, int petId, long now) {
        JsonNode def = cfg.path("pets").path(Integer.toString(petId));
        ObjectNode pet = json.createObjectNode().put("uid", UUID.randomUUID().toString()).put("id", petId)
                .put("level", 1).put("itype", def.path("type").asInt()).put("health", 100).put("love", 1)
                .put("loveexp", 0).put("lastcheck", now).put("lastshit", now)
                .put("aptitudes", draw(def.path("aptitudes"))).put("talent", draw(def.path("talent")))
                .put("star", 0).put("wash_times", 0).put("use_times", 0);
        pet.putArray("status");
        ensureTalent(pet, def, "talent", "talent_pos", ThreadLocalRandom.current());
        ensureTalent(pet, def, "talent_2", "talent_2_pos", ThreadLocalRandom.current());
        auras(pet, def);
        pet.putArray("cooldown");
        s.with("pets").set(pet.path("uid").asText(), pet);
        return pet;
    }

    /** 支付互动、维护蛋或宠物状态并在孵化时新增真实宠物。
     * @param id 角色编号
     * @param s 存档
     * @param out 结果
     * @param uid 原实例编号
     * @param egg 是否为蛋
     * @param option 原项目
     * @param now 当前秒
     * @return 操作成功
     */
    private boolean interact(String id, ObjectNode s, ObjectNode out, String uid, boolean egg, int option, long now) {
        JsonNode data = s.path(egg ? "eggs" : "pets").path(uid),
                spec = cfg.path("interactions").path(Integer.toString(option));
        if (!data.isObject() || spec.isMissingNode() || egg != (option >= 5))
            return false;
        ObjectNode target = (ObjectNode) data;
        JsonNode cost = spec.path("expend");
        if (egg) {
            int slot = option - 1;
            ArrayNode cooldown = target.withArray("cooldown");
            if (cooldown.size() == 2) {
                long old5 = cooldown.get(0).asLong(), old6 = cooldown.get(1).asLong();
                cooldown.removeAll();
                for (int n = 0; n < 4; n++)
                    cooldown.add(0);
                cooldown.add(old5).add(old6);
            }
            while (cooldown.size() < 6)
                cooldown.add(0);
            if (target.path("cooldown").path(slot).asLong() > now)
                return false;
            if (!inventory.spendMaterials(id, Collections.emptyMap(), cost.path("3").asLong(), 0)) {
                out.put("errno", 100005);
                return false;
            }
            ((ArrayNode) target.path("cooldown")).set(slot,
                    json.getNodeFactory().numberNode(now + spec.path("cd").asInt()));
            int affection = Math.min(100, target.path("loveexp").asInt() + spec.path("intimacy").asInt());
            target.put("loveexp", affection);
            if (affection == 100) {
                int petId = cfg.path("groups").path(target.path("id").asText()).get(1).asInt();
                ObjectNode pet = createPet(s, petId, now);
                s.with("eggs").remove(uid);
                out.put("ishatch", true).putObject("adds").putArray("pet").add(pet.deepCopy());
                target = pet;
            }
        } else {
            health(target, now);
            int health = target.path("health").asInt();
            boolean dirty = now - target.path("lastshit").asLong() >= 14400;
            if ((option == 1 || option == 2) && (health == 0 || dirty) || option == 3 && !dirty)
                return false;
            if (!inventory.spendMaterials(id, cost.path("2").asInt(), cost.path("3").asLong(), 0, 0)) {
                out.put("errno", 100004);
                return false;
            }
            if (option == 2)
                jdbc.update("UPDATE local_home_state SET mood=mood+? WHERE role_id=?", spec.path("mood").asInt(), id);
            if (option == 3)
                target.put("lastshit", now);
            target.put("health", Math.min(100,
                    health + spec.path(option == 3 || option == 4 && health == 0 ? "lshealth" : "health").asInt()));
            int level = target.path("love").asInt(), exp = target.path("loveexp").asInt()
                    + spec.path(option == 4 && health == 0 ? "lsintimacy" : "intimacy").asInt();
            while (cfg.path("love").has(Integer.toString(level))
                    && exp >= cfg.path("love").path(Integer.toString(level)).asInt()) {
                exp -= cfg.path("love").path(Integer.toString(level)).asInt();
                level++;
            }
            target.put("love", level).put("loveexp", exp);
        }
        out.put("uid", uid).put("isegg", egg).put("option", option).set("target", target.deepCopy());
        return true;
    }
}
