package com.pokemon.offline.elf;

import com.pokemon.offline.activity.CarnivalProgressMapper;
import com.pokemon.offline.quest.QuestService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.elf.api.entity.ElfGear;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 按底包培养条件更新本地精灵，统一校验材料和持久化训练结果。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:51
 */
@Service
public class ElfTrainingService {
    private static final int INVALID = 100021;
    private static final int LEVEL_LIMIT = 101004;
    private static final int NOT_ENOUGH_EXP = 101002;
    private static final int ELF_NOT_FOUND = 101005;

    /**
     * 计算精灵升到指定等级已消耗的经验池总量。
     * @param level 已达到的等级
     * @return 已跨过的等级所需经验之和
     */
    static long spentExperience(int level) {
        long total = 0;
        for (int i = 0; i < Math.min(level - 1, LEVEL_COST.length); i++) {
            total += LEVEL_COST[i];
        }
        return total;
    }

    /** 底包 jinglinglv_proto 的 exp，索引 0 对应等级 1 至 2。 */
    private static final int[] LEVEL_COST = {
        100, 159, 227, 307, 399, 505, 627, 766, 925, 1107, 1314, 1549,
        1816, 2118, 2462, 2849, 3288, 3783, 4343, 7462, 8527, 9730, 11082, 12606,
        14317, 16243, 18409, 20841, 23571, 29299, 83085, 87095, 91564, 96537, 102074, 108235,
        115090, 122717, 131196, 190624, 201099, 212741, 225672, 240035, 255987, 273694, 293349, 315162,
        339362, 366206, 392500, 416260, 441120, 467100, 494170, 522330, 551530, 581710, 612800, 1076760,
        1100620, 1169990, 1233400, 1291360, 1344300, 1392600, 1436620, 1476660, 1513000, 1819290, 1962740, 2044840,
        2120210, 2189270, 2252370, 2309860, 2362020, 2409160, 2451500, 3307960, 3414780, 3535190, 3645300, 3745560,
        3836390, 3918180, 3991290, 4056050, 4112760, 4738600, 4923240, 5068260, 5194220, 5302310, 5393580, 5468980,
        5529380, 5575530, 5608140, 5618638, 5629153, 5639709, 5650332, 5661047, 5671880, 5682859, 5694013, 5705371,
        5716964, 5728828, 5740998, 5753512, 5766413, 5779748, 5793566, 5807922, 5822879, 5838505, 5854876, 5872078,
        5890208, 5909377, 5929711, 5951356, 5974477, 5999272, 6025968, 6054834, 6086190, 6120421, 6157990, 6199463,
        6245540, 6297095, 6355232, 6421375, 6497380, 6585726, 6689789, 6814297, 6966080, 7155378, 7398285, 7721625,
        8173676, 8850996, 9978884, 12233239, 18993545
    };

    private static final int NEED_MATERIAL = 100004;
    private static final List<String> ATTRIBUTES = Collections.unmodifiableList(Arrays.asList(
            "hp_max", "physical_attack", "special_attack", "physical_defense", "special_defense", "speed"));
    private final CarnivalProgressMapper carnival;
    private final InventoryService inventory;
    private final ElfGearService gears;
    private final ElfMapper elves;
    private final RoleService roles;
    private final HangupService hangup;
    private final QuestService quests;

    /**
     * 按底表合成下一等级力石，原子扣除四的倍数个同模板实例和显示的货币费用。
     *
     * @param roleId 角色编号
     * @param ids 同一模板的材料实例编号
     * @return 新生成的力石实例列表，校验失败时为空列表
     */
    @Transactional
    public List<ElfGear> compoundStones(String roleId, List<?> ids) {
        int quantity = ids.size() / 4;
        if (quantity == 0 || ids.size() % 4 != 0 || ids.size() > ElfGearService.BAG_CAPACITY) {
            return Collections.emptyList();
        }
        roles.lockRole(roleId);
        List<ElfGear> materials = gears.materials(roleId, ids);
        if (materials.isEmpty()) {
            return Collections.emptyList();
        }
        int template = ElfGearService.visibleStone(materials.get(0));
        if (materials.stream().anyMatch(g -> ElfGearService.visibleStone(g) != template)) {
            return Collections.emptyList();
        }
        JsonNode stone = row("gem", template);
        int target = stone.path("synthesis").asInt();
        JsonNode cost = stone.path("cost");
        long price = cost.path("3").asLong() * quantity;
        int currency = cost.path("1").asInt();
        if (target == 0 || row("gem", target).isMissingNode() || price <= 0 || currency != 103 && currency != 104
                || !inventory.spendMaterials(roleId, Collections.emptyMap(),
                        currency == 103 ? price : 0, currency == 104 ? price : 0)) {
            return Collections.emptyList();
        }
        gears.consumeStones(roleId, materials);
        return gears.grantGear(roleId, ElfGearService.POWERSTONE, target, quantity);
    }

    /**
     * 创建精灵训练服务。
     *
     * @param elves 精灵存档
     * @param roles 角色服务
     * @param hangup 已领取的经验池
     * @param carnival 七日目标养成事件记录
     * @param inventory 角色材料和货币
     * @param gears 携带品和 P 力石归还服务
     * @param quests 原主线培养进度
     */
    ElfTrainingService(ElfMapper elves, RoleService roles, HangupService hangup, InventoryService inventory,
                       ElfGearService gears, QuestService quests, CarnivalProgressMapper carnival) {
        this.elves = elves;
        this.roles = roles;
        this.hangup = hangup;
        this.inventory = inventory;
        this.carnival = carnival;
        this.gears = gears;
        this.quests = quests;
    }

    /**
     * 按逐级经验费用完成一次升级，至多执行按钮请求的十级。
     *
     * @param roleId 角色编号
     * @param elfId 精灵编号
     * @param levels 客户端请求提升的等级数
     * @return 成功为新等级，失败为负错误码
     */
    @Transactional
    public int upgrade(String roleId, String elfId, long levels) {
        if (levels != 1 && levels != 10) {
            return -INVALID;
        }
        LocalRole role = roles.lockRole(roleId);
        int current = elves.findOwned(roleId, elfId).map(elf -> elf.level).orElse(0);
        if (current < 1) {
            return -ELF_NOT_FOUND;
        }
        int cap = Math.min(role.level, LEVEL_COST.length + 1);
        if (current >= cap) {
            return -LEVEL_LIMIT;
        }
        int target = Math.min(cap, current + (int) levels);
        long balance = hangup.elfPool(roleId);
        long cost = 0;
        int level = current;
        while (level < target && cost + LEVEL_COST[level - 1] <= balance) {
            cost += LEVEL_COST[level - 1];
            level++;
        }
        if (cost == 0 || !hangup.spendElfPool(roleId, cost)) {
            return -NOT_ENOUGH_EXP;
        }
        if (elves.setLevel(roleId, elfId, level) != 1) {
            throw new IllegalStateException("扣除训练经验后未能更新精灵等级");
        }
        quests.recordTraining(roleId, 24, level - current);
        return level;
    }

    /**
     * 按携带品栏位数量限制保护数，并扣除相应保护器。
     *
     * @param roleId 当前角色编号
     * @param elfId 精灵编号
     * @param gearId 携带品实例编号
     * @param protect 是否启用保护
     * @return 是否成功修改状态
     */
    @Transactional
    public boolean protectCarry(String roleId, String elfId, String gearId, boolean protect) {
        roles.lockRole(roleId);
        if (elfId == null) {
            return false;
        }
        List<ElfGear> equipped = gears.snapshot(roleId).stream().filter(gear -> gear.kind == ElfGearService.CARRY
                && !GoldCarryRules.isGold(gear.configId) && elfId.equals(gear.elfId)).collect(Collectors.toList());
        ElfGear target = equipped.stream().filter(gear -> gear.id.equals(gearId)).findFirst().orElse(null);
        if (target == null || target.locked == protect) {
            return false;
        }
        if (protect) {
            long protectedCount = equipped.stream().filter(gear -> gear.locked).count();
            int max = row("variable", 60 + Math.min(equipped.size(), 9)).path("data2").asInt();
            int count = row("variable", 1867 + (int) protectedCount).path("data1").asInt();
            if (protectedCount >= max || count <= 0 || !inventory.spendMaterials(roleId, 21041, count, 0, 0)) {
                return false;
            }
        }
        if (!gears.protectCarry(roleId, elfId, gearId, protect)) {
            throw new IllegalStateException("携带品保护状态未写入");
        }
        return true;
    }

    /**
     * 在携带品商店按底包金币价格抽取一件蓝色携带品。
     *
     * @param roleId 角色编号
     * @param product 原商店编号
     * @return 发放的携带品实例，失败为空
     */
    @Transactional
    public List<ElfGear> buyRandomCarry(String roleId, long product) {
        roles.lockRole(roleId);
        if (product != 1) {
            return Collections.emptyList();
        }
        int template = ThreadLocalRandom.current().nextInt(10001, 10012);
        if (!gears.canGrantGear(roleId, ElfGearService.CARRY, template, 1)
                || !inventory.spendMaterials(roleId, Collections.emptyMap(), 750000, 0)) {
            return Collections.emptyList();
        }
        return gears.grantGear(roleId, ElfGearService.CARRY, template, 1);
    }


    /**
     * 执行一次精灵培养并返回原协议要求的字段。
     *
     * @param roleId 角色编号
     * @param action 原游戏操作编号
     * @param elfId 目标精灵编号
     * @param body 原游戏请求参数
     * @param receipt 洗炼、进阶、进化和突破的唯一请求回执
     * @return 培养结果或原协议错误码
     */
    @Transactional
    public Map<String, Object> train(String roleId, int action, String elfId, Map<String, Object> body, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        boolean growthRequest = action == 3006 || action == 3007 || action == 270 || action == 271;
        String growthSignature = action + ":" + elfId + ":" + value(body,"sid") + ":" + Boolean.TRUE.equals(body.get("auto"))
                + ":" + body.get("fodder") + ":" + body.get("list") + ":" + value(body,"card") + ":" + value(body,"num");
        if (growthRequest && receipt != null) {
            Map<String,Object> previous = elves.growthReceipt(roleId,receipt,growthSignature);
            if (previous != null) return previous;
        }
        if (action == 3009 && (receipt == null || elves.hasIndividualReceipt(roleId, receipt))) {
            return reply("errno", 100011);
        }
        LocalElf elf = elves.findOwned(roleId, elfId).orElse(null);
        if (elf == null) {
            return reply("errno", ELF_NOT_FOUND);
        }
        JsonNode species = row("species", elf.configId);
        if (species.isMissingNode()) {
            return reply("errno", INVALID);
        }
        ObjectNode state = elf.training;
        int oldLove = state.path("love").asInt(1);
        int effortGain = 0;
        Cost cost = new Cost();
        List<String> consumed = Collections.emptyList();
        int newConfig = elf.configId;
        boolean auto = Boolean.TRUE.equals(body.get("auto"));
        int quality = species.path("quality").asInt();
        int star = state.path("star").asInt(1);
        Map<String, Object> result = reply("errno", 0);
        if ((action >= 3005 && action <= 3011) || action == 3022) {
            result.put("onlyid", elf.id);
        }

        switch (action) {
            case 270:
            case 271:
                int vein = state.path("veinlevel").asInt();
                String assessKey = species.path("assess").asText();
                int veinCap = row("variable", 3801).path("data6").path(assessKey).asInt();
                JsonNode veinRule = row("break", vein + 1);
                if (vein >= veinCap || veinRule.isMissingNode() || elf.configId == 41031
                        || quality <= row("variable", 3802).path("data1").asInt()
                        || elf.level < Math.max(veinRule.path("lv").asInt(), row("variable", 3804).path("data1").asInt())) {
                    return reply("errno", INVALID);
                }
                int resultLevel = vein + 1;
                int veinMaterialQuality = veinRule.path("inject").asInt();
                int expectedSpecies = veinRule.has("inject") ? 0 : species.path("starsprint").asInt(elf.configId);
                int materialCount = veinRule.has("inject") ? 1 : row("veinConsume",species.path("assess").asInt())
                        .path(Integer.toString((vein + 1) / 11)).asInt(1);
                long veinCardCount = action == 271 ? value(body,"num") : 0;
                long cardId = action == 271 ? value(body,"card") : 0;
                consumed = action == 270 ? Collections.singletonList((String)body.get("fodder")) : stringList(body.get("list"));
                if ((action == 270 && !veinRule.has("inject")) || veinCardCount < 0 || veinCardCount > materialCount
                        || consumed.size() + veinCardCount != materialCount
                        || !validFodder(roleId,elf,consumed,"",0,expectedSpecies, true)) return reply("errno",INVALID);
                for (String uid : consumed) {
                    LocalElf material = elves.findOwned(roleId,uid).get();
                    if (veinRule.has("inject") && row("species",material.configId).path("quality").asInt()!=veinMaterialQuality)
                        return reply("errno",INVALID);
                    if (!veinRule.has("inject") && material.training.path("awakenlv").asInt()<veinRule.path("awakening").asInt())
                        return reply("errno",INVALID);
                }
                if (veinCardCount > 0) {
                    int sourceQuality = expectedSpecies > 0 ? row("species",expectedSpecies).path("quality").asInt() : veinMaterialQuality;
                    int[] cards = {0,0,21065,21066,21067,21068,21069,21091};
                    if (row("variable",3807).path("data1").asInt()!=1 || sourceQuality<2 || sourceQuality>=cards.length
                            || cardId!=cards[sourceQuality]) return reply("errno",INVALID);
                    cost.add((int)cardId,veinCardCount);
                }
                applyTypedCost(cost,veinRule.path("lighten"));
                result.put(action==270?"target":"uid",elf.id);
                if(action==270) result.put("fodder",body.get("fodder"));
                if (veinRule.has("successrate")) {
                    int bonus = consumed.isEmpty() ? 0 : veinSuccessBonus(species,elves.findOwned(roleId,consumed.get(0)).get());
                    int rate = Math.min(1000,veinRule.path("successrate").asInt()+bonus);
                    if (ThreadLocalRandom.current().nextInt(1000) >= rate) resultLevel=vein;
                }
                state.put("veinlevel", resultLevel);
                result.put("final", resultLevel);
                break;
            case 3005:
                if (!intimacy(roleId, elf, species, body, cost, result, auto)) {
                    return reply("errno", INVALID);
                }
                break;
            case 3006:
            case 3007:
                JsonNode baseGrowth = row("growth", elf.configId);
                JsonNode growth = growthRule(elf.configId);
                int current = state.path("advanced").asInt();
                int required = growth.path("upgrade_limit").asInt();
                if (growth.isMissingNode() || current < 0) {
                    return reply("errno", INVALID);
                }
                if (action == 3007) {
                    if (current >= required || current >= 9) {
                        return reply("errno", 101008);
                    }
                    JsonNode paidStep = growth.path("cost").path(current);
                    applyGrowthCost(cost, paidStep);
                    state.with("advanceCosts").set(Integer.toString(current), paidStep.deepCopy());
                    state.put("advanced", current + 1);
                } else {
                    newConfig = (int) value(body, "sid");
                    JsonNode targetGrowth = row("growth", newConfig);
                    if (newConfig <= 0 || newConfig == elf.configId || targetGrowth.isMissingNode()
                            || !contains(baseGrowth.path("evolution"), newConfig)) {
                        return reply("errno", INVALID);
                    }
                    if (current < targetGrowth.path("upgrade_limit").asInt()) return reply("errno", 253120);
                    if (elf.level < targetGrowth.path("needlv").asInt()) return reply("errno", 101009);
                    if (state.path("love").asInt(1) < targetGrowth.path("needlove").asInt()) return reply("errno", 101010);
                    applyGrowthCost(cost, targetGrowth.path("cost").path(9));
                    state.put("advanced", 0);
                    state.remove("advanceCosts");
                    state.withArray("old_elfsid").add(elf.configId);
                    result.put("skill", state.has("skillBar") ? ElfSkillBar.equipped(elf) : unlockedSkills(newConfig, elf.level));
                }
                result.put("auto", auto);
                break;
            case 3008:
                Map<?, ?> item = object(body.get("itemnum"));
                int itemId = (int) value(item, "sid");
                int index = itemId - 21017;
                if (index < 0 || index >= ATTRIBUTES.size() || value(item, "num") <= 0
                        || value(body, "category") != index + 1) {
                    return reply("errno", INVALID);
                }
                String attribute = ATTRIBUTES.get(index);
                ObjectNode effort = state.with("effort");
                int previous = effort.path(attribute).asInt();
                int cap = row("variable", 1019 + star).path("data2").asInt()
                        + row("break", state.path("veinlevel").asInt()).path("strive").asInt()
                        + state.path("effort_monopoly_max").path(attribute).asInt();
                long count = Math.min(cap - previous, value(item, "num"));
                if (count <= 0) {
                    return reply("errno", INVALID);
                }
                effort.put(attribute, previous + count);
                effortGain = Math.toIntExact(count);
                ObjectNode effortItems = state.with("effort_item");
                effortItems.put(Integer.toString(itemId), effortItems.path(Integer.toString(itemId)).asLong() + count);
                cost.add(itemId, count);
                break;
            case 3009:
                long payType = value(body, "paytype");
                if (payType != 1 && payType != 2) {
                    return reply("errno", INVALID);
                }
                Map<?, ?> locked = object(body.get("locklist"));
                int numberLocked = 0;
                ObjectNode individual = state.with("individual");
                Map<String, Integer> rolled = new LinkedHashMap<>();
                for (String key : ATTRIBUTES) {
                    int currentValue = individual.path(key).asInt();
                    if (value(locked, key) > 0) {
                        numberLocked++;
                    } else {
                        currentValue = ThreadLocalRandom.current().nextInt(quality >= 7 ? 501 : 311);
                    }
                    rolled.put(key, currentValue);
                }
                if (numberLocked == ATTRIBUTES.size()) {
                    return reply("errno", INVALID);
                }
                JsonNode wash = row("variable", 1013 + numberLocked);
                if (payType == 1) {
                    cost.add(21023, wash.path("data1").asLong());
                } else {
                    cost.diamonds = wash.path("data2").asLong();
                }
                rolled.forEach(individual::put);
                state.put("individualmax", state.path("individualmax").asInt() + wash.path("data3").asInt());
                result.put("individual", rolled);
                break;
            case 3010:
                cost.add(21025, 1);
                int oldNature = state.path("nature").asInt(1006);
                int newNature = 1001 + ThreadLocalRandom.current().nextInt(24);
                if (newNature >= oldNature) {
                    newNature++;
                }
                state.put("nature", newNature);
                result.put("nature", newNature);
                break;
            case 3011:
                int nextStar = star + 1;
                JsonNode starRule = row("variable", 1019 + nextStar);
                if (nextStar > species.path("maxstar").asInt()
                        || elf.level < starRule.path("data1").asInt() || starRule.isMissingNode()) {
                    return reply("errno", INVALID);
                }
                consumed = stringList(body.get("consume_id"));
                if (consumed.size() != starRule.path("data4").asInt()
                        || !validFodder(roleId, elf, consumed, "risingstar", 0)) {
                    return reply("errno", INVALID);
                }
                cost.coins = starRule.path("data3").asLong();
                applyTypedCost(cost, starRule.path("data7"));
                state.put("star", nextStar);
                result.put("auto", auto);
                break;
            case 3022:
                long lock = value(body, "lock");
                if (lock != 0 && lock != 1) {
                    return reply("errno", INVALID);
                }
                state.put("lock", lock);
                result.put("lock", lock);
                break;
            case 3035:
                int awake = state.path("awakenlv").asInt() + 1;
                JsonNode awakeRule = row("variable", 2213 + awake);
                if (awake > species.path("maxawaken").asInt() || star < awakeRule.path("data1").asInt()
                        || awakeRule.isMissingNode()) {
                    return reply("errno", INVALID);
                }
                consumed = stringList(body.get("consume_id"));
                Map<?, ?> substitute = object(body.get("ditem"));
                long cardCount = value(substitute, "n");
                int materialSpecies = species.path("starsprint").asInt();
                int materialQuality = row("species", materialSpecies).path("quality").asInt();
                int card = materialQuality == 7 ? 21090 : materialQuality >= 2 && materialQuality <= 6
                        ? row("variable", 2229 + materialQuality).path("data1").asInt() : 0;
                if (consumed.size() + cardCount != awakeRule.path("data3").asInt() || cardCount < 0
                        || (cardCount > 0 && (card <= 0 || value(substitute, "id") != card))
                        || !validFodder(roleId, elf, consumed, "", 0, materialSpecies)) {
                    return reply("errno", INVALID);
                }
                cost.add(card, cardCount);
                cost.coins = awakeRule.path("data2").asLong();
                ObjectNode awakenStep = state.withArray("awakenHistory").addObject().put("start_standard", star);
                com.fasterxml.jackson.databind.node.ArrayNode fodderSpecies = awakenStep.putArray("consume_elf_num");
                for (int i = 0; i < awakeRule.path("data3").asInt(); i++) {
                    fodderSpecies.add(materialSpecies);
                }
                state.put("awakenlv", awake);
                result.put("star_lv", awake);
                break;
            case 407:
                JsonNode unlock = row("ancient", 0).path("remove");
                consumed = stringList(body.get("list"));
                int assess = row("variable", 4402).path("data1").asInt();
                if (state.has("ancient") || quality < 6 || consumed.size() != unlock.path("1").asInt()
                        || !validFodder(roleId, elf, consumed, "", assess)) {
                    return reply("errno", INVALID);
                }
                cost.coins = unlock.path("2").asLong();
                state.put("ancient", 0);
                result.put("uid", elf.id);
                result.put("list", consumed);
                break;
            case 408:
                int ancient = state.path("ancient").asInt(-1);
                JsonNode ancientRule = row("ancient", ancient + 1);
                consumed = stringList(body.get("list"));
                Map<Integer, Long> materials = itemList(body.get("items"));
                if (ancient < 0 || ancientRule.isMissingNode() || materials == null
                        || materials.keySet().stream().anyMatch(id -> id != 30014)
                        || consumed.size() + materials.getOrDefault(30014, 0L) != ancientRule.path("material").asLong()
                        || !validFodder(roleId, elf, consumed, "", row("variable", 4402).path("data1").asInt())) {
                    return reply("errno", INVALID);
                }
                cost.items.putAll(materials);
                cost.coins = ancientRule.path("gold").asLong();
                state.put("ancient", ancient + 1);
                result.put("uid", elf.id);
                break;
            default:
                return reply("errno", INVALID);
        }
        if (!pay(role, cost, auto && (action == 3005 || action == 3006 || action == 3007 || action == 3011))) {
            return reply("errno", NEED_MATERIAL);
        }
        for (String fodder : consumed) {
            if (action == 270 || action == 271) {
                elves.preserveStarterClaim(roleId, fodder);
            }
            if (!gears.removeElfGear(roleId, fodder) || elves.consumeElf(roleId, fodder) != 1) {
                throw new IllegalStateException("精灵培养材料在结算时已不可用");
            }
        }
        if (action == 3009) {
            elves.recordIndividualReceipt(roleId, receipt);
            carnival.record(roleId, 109, 1);
        }
        if (elves.saveTraining(roleId, elf.id, newConfig, state) != 1) {
            throw new IllegalStateException("未能写入精灵培养状态");
        }
        if (newConfig != elf.configId) {
            elves.discover(roleId, elf.configId);
            elves.discover(roleId, newConfig);
        }
        if (action == 3005) {
            quests.recordTraining(roleId, 25, state.path("love").asInt(1) - oldLove);
        } else if (action == 3006) {
            quests.recordTraining(roleId, 8, 1);
        } else if (action == 3010 || action == 3011) {
            quests.recordTraining(roleId, action == 3010 ? 18 : 12, 1);
        } else if (action == 3008) {
            quests.recordTraining(roleId, 26, effortGain);
        }
        if (growthRequest && receipt != null) elves.saveGrowthReceipt(roleId,receipt,growthSignature,result);
        return result;
    }

    /**
     * 按果实和当前亲密等级计算经验与扣费。
     *
     * @param roleId 角色编号
     * @param elf 待培养精灵
     * @param species 物种底表
     * @param body 请求字段
     * @param cost 培养费用
     * @param result 培养响应字段
     * @param auto 是否允许钻石补足
     * @return 是否满足培养规则
     */
    private boolean intimacy(String roleId, LocalElf elf, JsonNode species, Map<String, Object> body, Cost cost,
                             Map<String, Object> result, boolean auto) {
        ObjectNode state = elf.training;
        int level = state.path("love").asInt(1);
        int max = row("variable", 2501).path("data6").path(species.path("quality").asText()).asInt(100)
                + row("break", state.path("veinlevel").asInt()).path("extra").asInt();
        long previous = state.path("loveexp").asLong();
        long experience = previous;
        long type = value(body, "type");
        Map<Integer, Long> fruit = itemList(body.get("itemnum"));
        if (fruit == null || level >= max || (type != 1 && type != 2)) {
            return false;
        }
        if (type == 2 && fruit.isEmpty()) {
            long needed = row("love", level).path("exp").asLong() - previous;
            long left = needed;
            for (JsonNode item : species.path("loveitem")) {
                int id = item.asInt();
                long exp = row("fruit", id).path("number1").asLong();
                if (exp > 0 && left > 0) {
                    long count = Math.min(inventory.count(roleId, id), (left + exp - 1) / exp);
                    if (count > 0) {
                        fruit.put(id, count);
                        left -= count * exp;
                    }
                }
            }
            if (left > 0 && auto) {
                fruit.merge(22019, (left + 49) / 50, Long::sum);
                left = 0;
            }
            if (left > 0) {
                return false;
            }
        }
        for (Map.Entry<Integer, Long> entry : fruit.entrySet()) {
            int id = entry.getKey();
            JsonNode config = row("fruit", id);
            if (!contains(species.path("loveitem"), id) || config.isMissingNode()) {
                return false;
            }
            experience += config.path("number1").asLong() * entry.getValue();
            cost.coins += config.path("number2").asLong() * entry.getValue();
            cost.add(id, entry.getValue());
        }
        if (experience == previous) {
            return false;
        }
        advanceIntimacy(elf, experience);
        result.put("love", state.path("love").asInt());
        result.put("loveexp", state.path("loveexp").asLong());
        result.put("auto", auto);
        result.put("item", fruit.entrySet().stream().map(entry -> reply("sid", entry.getKey(), "num", entry.getValue()))
                .collect(Collectors.toList()));
        return true;
    }

    /**
     * 按原版亲密度的角色精灵等级和品质门槛更新培养状态。
     *
     * @param elf 当前精灵
     * @param experience 已加上本次收益的累计亲密经验
     * @return 精灵仍可增加亲密度时为真
     */
    static boolean advanceIntimacy(LocalElf elf, long experience) {
        ObjectNode state = elf.training;
        int level = state.path("love").asInt(1);
        JsonNode species = row("species", elf.configId);
        int max = row("variable", 2501).path("data6").path(species.path("quality").asText()).asInt(100)
                + row("break", state.path("veinlevel").asInt()).path("extra").asInt();
        if (level >= max) {
            return false;
        }
        while (level < max) {
            JsonNode rule = row("love", level);
            long required = rule.path("exp").asLong();
            if (experience < required || required <= 0 || elf.level < rule.path("level").asInt()) {
                break;
            }
            experience -= required;
            level++;
        }
        state.put("love", level);
        state.put("loveexp", level == max ? 0 : experience);
        return true;
    }

    /**
     * 检查材料精灵归属、锁定状态及底表分类。
     *
     * @param roleId 角色编号
     * @param target 培养目标
     * @param ids 材料精灵编号
     * @param matchingField 同名模板字段，留空时按评级判断
     * @param minimumAssess 最低材料评级
     * @return 所有材料都可消耗
     */
    private boolean validFodder(String roleId, LocalElf target, List<String> ids, String matchingField, int minimumAssess) {
        return validFodder(roleId, target, ids, matchingField, minimumAssess, 0);
    }

    /**
     * 核对材料精灵的归属、占用及模板限制。
     * @param roleId 角色编号
     * @param target 培养目标
     * @param ids 材料实例编号
     * @param matchingField 必须与目标相同的配置字段，空串表示不限
     * @param minimumAssess 最低评价
     * @param requiredSpecies 指定的材料精灵模板，零表示不限
     * @return 所有材料均可消耗时为真
     */
    private boolean validFodder(String roleId, LocalElf target, List<String> ids, String matchingField,
                                int minimumAssess, int requiredSpecies) {
        return validFodder(roleId, target, ids, matchingField, minimumAssess, requiredSpecies, false);
    }

    /**
     * 按培养入口校验材料实例、模板与突破专用的闲置伙伴规则。
     * @param roleId 当前角色
     * @param target 培养目标
     * @param ids 选择的材料实例
     * @param matchingField 材料与目标须匹配的模板字段
     * @param minimumAssess 材料最低评价
     * @param requiredSpecies 限定模板，零表示不限
     * @param breakthrough 是否为突破注入
     * @return 所有材料均可消耗时为真
     */
    private boolean validFodder(String roleId, LocalElf target, List<String> ids, String matchingField,
                                int minimumAssess, int requiredSpecies, boolean breakthrough) {
        Set<String> distinct = new HashSet<>();
        Set<String> assigned = breakthrough && !ids.isEmpty() ? elves.assignedElves(roleId) : Collections.emptySet();
        Map<String, LocalElf> roster = elves.list(roleId).stream().collect(Collectors.toMap(elf -> elf.id, elf -> elf));
        int matching = row("species", target.configId).path(matchingField).asInt(target.configId);
        for (String id : ids) {
            LocalElf elf = roster.get(id);
            JsonNode species = elf == null ? null : row("species", elf.configId);
            if (elf == null || id.equals(target.id) || !distinct.add(id) || elf.slot != 0
                    || assigned.contains(id) || !elves.isConsumable(roleId, id, breakthrough) || elf.training.path("lock").asInt() != 0
                    || (requiredSpecies != 0 && elf.configId != requiredSpecies)
                    || (!matchingField.isEmpty() && species.path(matchingField).asInt(elf.configId) != matching)
                    || species.path("assess").asInt() < minimumAssess) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在同一事务内扣费，只有补齐价格存在时才允许用钻石补足。
     *
     * @param role 已锁定的角色存档
     * @param cost 原培养费用
     * @param auto 是否自动补齐
     * @return 是否完成扣费
     */
    private boolean pay(LocalRole role, Cost cost, boolean auto) {
        if (auto) {
            for (Map.Entry<Integer, Long> item : cost.items.entrySet()) {
                long available = inventory.count(role.id, item.getKey());
                long price = row("price", item.getKey()).asLong();
                if (available < item.getValue() && price > 0) {
                    cost.diamonds += price * (item.getValue() - available);
                    item.setValue(available);
                }
            }
            if (role.coins < cost.coins) {
                long price = row("price", 1001).asLong();
                cost.diamonds += price * ((cost.coins - role.coins + 99999) / 100000);
                cost.coins = role.coins;
            }
        }
        return inventory.spendMaterials(role.id, cost.items, cost.coins, cost.diamonds);
    }

    /**
     * 按原属性交集和主向性差值计算单材料突破概率加成。
     * @param target 目标精灵原模板
     * @param material 本次材料精灵
     * @return 千分比概率加成
     */
    private static int veinSuccessBonus(JsonNode target,LocalElf material) {
        JsonNode source=row("species",material.configId);
        int bonus=0;
        for(String a:Arrays.asList("attribute1","attribute2"))
            for(String b:Arrays.asList("attribute1","attribute2"))
                if(target.path(a).asInt()==source.path(b).asInt()) bonus=row("variable",3808).path("data1").asInt();
        String maximum="stat_hp";
        for(String name:Arrays.asList("stat_hp","stat_physical_attack","stat_special_attack","stat_physical_defense","stat_special_defense","stat_speed"))
            if(target.path(name).asInt()>target.path(maximum).asInt()) maximum=name;
        int delta=Math.max(0,Math.min(1000,target.path(maximum).asInt()-source.path(maximum).asInt()));
        JsonNode coefficient=row("variable",3809);
        return bonus+Math.max(0,(int)Math.floor(coefficient.path("data2").asDouble()-coefficient.path("data1").asDouble()*delta*delta));
    }
    /**
     * 按原客户端规则选择当前首个进化目标或终形编号乘十的进阶表。
     * @param speciesId 当前精灵模板编号
     * @return 进阶使用的原配置
     */
    private static JsonNode growthRule(int speciesId) {
        return ElfTrainingCatalog.advanceRule(speciesId);
    }
    /**
     * 读取进阶或进化采用的物品与金币。
     *
     * @param cost 培养费用
     * @param config 当前阶段的底表收费
     */
    private static void applyGrowthCost(Cost cost, JsonNode config) {
        cost.coins += config.path("coins").asLong();
        JsonNode items = config.path("items");
        for (int i = 0; i + 1 < items.size(); i += 2) {
            cost.add(items.path(i).asInt(), items.path(i + 1).asLong());
        }
    }

    /**
     * 读取底表以类型、编号、数量三列描述的训练消耗。
     *
     * @param cost 培养费用
     * @param items 消耗列表
     */
    private static void applyTypedCost(Cost cost, JsonNode items) {
        for (int i = 1; i + 2 <= items.size(); i += 3) {
            int type = items.path(Integer.toString(i)).asInt();
            int id = items.path(Integer.toString(i + 1)).asInt();
            long count = items.path(Integer.toString(i + 2)).asLong();
            if (type == 103) {
                cost.coins += count;
            } else if (type == 104) {
                cost.diamonds += count;
            } else if (type == 1) {
                cost.add(id, count);
            }
        }
    }

    /**
     * 读取指定模板已受等级解锁的普通技能。
     *
     * @param speciesId 模板编号
     * @param level 精灵等级
     * @return 当前可用的普通技能编号
     */
    public static List<Integer> unlockedSkills(int speciesId, int level) {
        JsonNode skills = row("species", speciesId).path("skills");
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i + 1 < skills.size(); i += 2) {
            int id = skills.path(i + 1).asInt();
            if (id > 0 && skills.path(i).asInt() <= level) {
                result.add(id);
            }
        }
        return result;
    }

    /**
     * 读取指定培养配置。
     *
     * @param table 底表名
     * @param id 配置编号
     * @return 培养配置行
     */
    private static JsonNode row(String table, int id) {
        return ElfTrainingCatalog.row(table, id);
    }

    /**
     * 判断某编号是否属于底表列出的允许值。
     *
     * @param values 底表数组
     * @param id 待核对编号
     * @return 是否存在
     */
    private static boolean contains(JsonNode values, int id) {
        for (JsonNode value : values) {
            if (value.asInt() == id) {
                return true;
            }
        }
        return false;
    }

    /**
     * 解析协议物品数量列表，遇非法编号或数量时返回空值。
     *
     * @param raw 原协议字段值
     * @return 合并后的物品数量
     */
    private static Map<Integer, Long> itemList(Object raw) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (raw instanceof List<?>) {
            for (Object entry : (List<?>) raw) {
                Map<?, ?> item = object(entry);
                long id = value(item, "sid");
                long count = value(item, "num");
                if (id <= 0 || id > Integer.MAX_VALUE || count <= 0 || count > 1_000_000) {
                    return null;
                }
                result.merge((int) id, count, Long::sum);
            }
        }
        return result;
    }

    /**
     * 取协议子结构。
     *
     * @param raw 原协议字段值
     * @return 子结构映射
     */
    private static Map<?, ?> object(Object raw) {
        return raw instanceof Map<?, ?> ? (Map<?, ?>) raw : Collections.emptyMap();
    }

    /**
     * 取协议字符串数组。
     *
     * @param raw 原协议字段值
     * @return 字符串列表
     */
    private static List<String> stringList(Object raw) {
        return raw instanceof List<?> ? ((List<?>) raw).stream().map(String.class::cast).collect(Collectors.toList())
                : Collections.emptyList();
    }

    /**
     * 按字段名读取协议整数。
     *
     * @param body 协议字段集合
     * @param key 字段名
     * @return 读取的整数或零
     */
    private static long value(Map<?, ?> body, String key) {
        Object value = body.get(key);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /**
     * 组装协议字段与值。
     *
     * @param fields 顺序排列的字段名和值
     * @return 回复字段映射
     */
    private static Map<String, Object> reply(Object... fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < fields.length; i += 2) {
            result.put((String) fields[i], fields[i + 1]);
        }
        return result;
    }

    /**
     * 培养扣费，由角色事务统一提交。
     *
     * @author tongruiyuan
     * @since 2026-11-19 00:18
     */
    private static final class Cost {
        private final Map<Integer, Long> items = new LinkedHashMap<>();
        private long coins;
        private long diamonds;

        /**
         * 汇总一类材料消耗。
         *
         * @param id 材料编号
         * @param count 材料数量
         */
        private void add(int id, long count) {
            if (id > 0 && count > 0) {
                items.merge(id, count, Long::sum);
            }
        }
    }
}
