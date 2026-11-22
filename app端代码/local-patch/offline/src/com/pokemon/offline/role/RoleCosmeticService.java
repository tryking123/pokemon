package com.pokemon.offline.role;

import com.pokemon.offline.activitylevel.TrainerRuneService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.role.api.entity.RoleCosmeticState;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 本地称号及时装 Service，统一提供拥有状态、展示选择和生效属性。
 *
 * @author tongruiyuan
 * @since 2026-11-19 13:35
 */
@Service
public class RoleCosmeticService {
    private final com.pokemon.offline.guild.GuildEncounterMapper archaeology;
    private final com.pokemon.offline.guild.GuildResearchBonuses research;
    private final com.pokemon.offline.platform.beans.factory.ObjectProvider<TrainerRuneService> runes;
    private final com.pokemon.offline.activitylevel.TrainerRefinementService refinement;
    private final com.pokemon.offline.home.HomeBuildingMapper buildings;
    private final JsonNode stationRules;
    private final com.pokemon.offline.dojo.DojoStationMapper stations;
    private final RoleService roles;
    private final RoleCosmeticMapper mapper;
    private final JsonNode titles;
    private final JsonNode dresses;
    private final JsonNode dressShop;
    private final JsonNode dressChips;
    private final Map<Integer, Integer> ownedCosmeticAttributes;

    /**
     * 载入来自固定底包的称号、时装及其入口编号。
     *
     * @param roles 角色与资产事务锁
     * @param mapper 外观存档
     * @param json 运行配置读取组件
     * @param archaeology 角色石板研究存档
     * @param runes 装备刻印套装属性
     * @param refinement 训练家装备进阶属性
     * @param buildings 家园在建雕像
     * @param stations 当前道馆驻防记录
     * @throws IOException 随包配置无法读取
     */
    public RoleCosmeticService(RoleService roles, RoleCosmeticMapper mapper, ObjectMapper json,
                               com.pokemon.offline.guild.GuildEncounterMapper archaeology,
                               com.pokemon.offline.dojo.DojoStationMapper stations,
                               com.pokemon.offline.home.HomeBuildingMapper buildings,
                               com.pokemon.offline.activitylevel.TrainerRefinementService refinement,
                               com.pokemon.offline.platform.beans.factory.ObjectProvider<TrainerRuneService> runes) throws IOException {
        this.runes=runes;
        this.refinement=refinement;
        this.buildings = buildings;
        this.stations = stations;
        try (InputStream stream = new ClassPathResource("dojo-garrison.json").getInputStream()) {
            stationRules = json.readTree(stream);
        }
        this.archaeology = archaeology;
        research = new com.pokemon.offline.guild.GuildResearchBonuses(json);
        this.roles = roles;
        this.mapper = mapper;
        try (InputStream stream = new ClassPathResource("role/cosmetics.json").getInputStream()) {
            JsonNode source = json.readTree(stream);
            titles = source.required("titles");
            dresses = source.required("dresses");
            dressShop = source.required("dressShop");
            dressChips = source.required("dressChips");
        }
        Map<Integer, Integer> totals = new HashMap<>();
        for (JsonNode owned : titles) {
            addAttributes(totals, owned);
        }
        for (JsonNode owned : dresses) {
            addAttributes(totals, owned.path("attributes"));
        }
        ownedCosmeticAttributes = Collections.unmodifiableMap(totals);
    }

    /**
     * 返回角色当前已提交的外观。
     *
     * @param roleId 当前角色编号
     * @return 当前佩戴、展示和时装状态
     */
    public RoleCosmeticState state(String roleId) {
        return mapper.find(roleId);
    }

    /**
     * 返回原客户端默认拥有的称号列表。
     *
     * @return 按配置排序的称号编号
     */
    public List<Integer> ownedTitles() {
        return ids(titles);
    }

    /**
     * 返回原客户端默认拥有的时装列表。
     *
     * @return 按配置排序的时装编号
     */
    public List<Integer> ownedDresses() {
        return ids(dresses);
    }

    /**
     * 读取场景和角色名片上显示的称号。
     *
     * @param roleId 当前角色编号
     * @return 当前展示编号，零表示不显示
     */
    public int displayedTitle(String roleId) {
        return mapper.find(roleId).displayedTitle;
    }

    /**
     * 读取角色当前穿戴的时装。
     *
     * @param roleId 当前角色编号
     * @return 时装编号，零表示默认外观
     */
    public int wornDress(String roleId) {
        return mapper.find(roleId).wornDress;
    }

    /**
     * 按原界面语义切换佩戴或展示，同一请求的重放只读取保存的结果。
     *
     * @param roleId 当前角色编号
     * @param titleId 目标称号编号，零表示清除本项选择
     * @param display 是否操作展示栏
     * @param requestId 同一连接内的去重标识
     * @return 原协议结果码，零表示成功
     */
    @Transactional
    public int changeTitle(String roleId, int titleId, boolean display, String requestId) {
        if (titleId != 0 && !titles.has(Integer.toString(titleId))) {
            return 3;
        }
        roles.lockRole(roleId);
        if (!mapper.hasReceipt(roleId, requestId)) {
            RoleCosmeticState current = mapper.find(roleId);
            int previous = display ? current.displayedTitle : current.equippedTitle;
            int next = previous == titleId ? 0 : titleId;
            mapper.save(roleId, new RoleCosmeticState(display ? current.equippedTitle : next,
                    display ? next : current.displayedTitle, current.wornDress));
            mapper.record(roleId, requestId);
        }
        return 0;
    }

    /**
     * 保存原客户端提交的单一时装槽位。
     *
     * @param roleId 当前角色编号
     * @param dressId 原时装配置编号
     * @param takeOff 是否卸下请求指明的时装
     * @param requestId 同一连接内的去重标识
     * @return 原协议结果码，零表示成功
     */
    @Transactional
    public int changeDress(String roleId, int dressId, boolean takeOff, String requestId) {
        LocalRole role = roles.lockRole(roleId);
        JsonNode dress = dresses.path(Integer.toString(dressId));
        int sex = dress.path("sex").asInt(-1);
        if (sex != 2 && sex != role.sex) {
            return 3;
        }
        if (!mapper.hasReceipt(roleId, requestId)) {
            RoleCosmeticState current = mapper.find(roleId);
            if (!takeOff || current.wornDress == dressId) {
                mapper.save(roleId, new RoleCosmeticState(current.equippedTitle, current.displayedTitle,
                        takeOff ? 0 : dressId));
            }
            mapper.record(roleId, requestId);
        }
        return 0;
    }

    /**
     * 确认购买入口对应的时装已由本地目录提供。
     *
     * @param shopPosition 原时装货架的位置编号
     * @return 原协议结果码，零表示已持有
     */
    public int ownedForShop(int shopPosition) {
        return dressShop.has(Integer.toString(shopPosition)) ? 0 : 3;
    }

    /**
     * 确认合成入口的时装已拥有。
     *
     * @param chipId 原时装碎片编号
     * @return 原协议结果码，目录内目标已持有时为零
     */
    public int ownedForChip(int chipId) {
        String target = dressChips.path(Integer.toString(chipId)).asText();
        return dresses.has(target) ? 0 : 3;
    }

    /**
     * 冻结该角色持有的称号与时装提供的属性。
     *
     * @param roleId 当前角色编号
     * @return 可供整支队伍使用的同一份属性快照
     */
    public BattleBonuses battleBonuses(String roleId) {
        return bonusesAtStation(roleId, stations.activeStation(roleId, java.time.Instant.now().getEpochSecond()));
    }

    /** 计算角色守备指定道馆时的属性，便于冻结易主后的完整守方。
     * @param roleId 角色编号
     * @param station 驻防馆，零表示无驻防
     * @return 包含驻防生命和伤害加成的战斗属性
     */
    public BattleBonuses bonusesAtStation(String roleId, int station) {
        Map<Integer, Integer> attributes = new HashMap<>(ownedCosmeticAttributes);
        JsonNode bonus = stationRules.path(String.valueOf(station)).path("instructions1");
        if (bonus.isArray() && bonus.size() == 2) {
            attributes.merge(24, 10 * bonus.get(0).asInt(), Math::addExact);
            attributes.merge(23, 10 * bonus.get(1).asInt(), Math::addExact);
        }
        // 所有词条先计原值，再追加进阶前三条的倍率增量与部位强化加成，各部分只结算一次。
        refinement.addRefinementBonuses(roleId,attributes);
        refinement.addAdvanceBonuses(roleId,attributes);
        refinement.addEnhanceBonuses(roleId,attributes);
        TrainerRuneService runeService = runes.getIfAvailable();
        if (runeService != null) {
            runeService.addBonuses(roleId, attributes);
        }
        Map<Integer,Integer> statues = new HashMap<>();
        buildings.addStatues(roleId, statues);
        return new BattleBonuses(attributes, statues, bonus.isArray() ? bonus.get(1).asInt() : 0,
                research.snapshot(archaeology.researchState(roleId)));
    }

    /**
     * 读取字典中的原配置编号。
     *
     * @param table 运行配置字典
     * @return 编号列表
     */
    private static List<Integer> ids(JsonNode table) {
        List<Integer> ids = new ArrayList<>();
        table.fieldNames().forEachRemaining(id -> ids.add(Integer.parseInt(id)));
        return ids;
    }

    /**
     * 累加原属性代码及其整数数值。
     *
     * @param totals 属性代码的累计值
     * @param source 已持有的称号或时装的原属性
     */
    private static void addAttributes(Map<Integer, Integer> totals, JsonNode source) {
        Iterator<Map.Entry<String, JsonNode>> attributes = source.fields();
        while (attributes.hasNext()) {
            Map.Entry<String, JsonNode> value = attributes.next();
            totals.merge(Integer.parseInt(value.getKey()), value.getValue().intValue(), Math::addExact);
        }
    }

    /**
     * 同一角色当次使用的外观战斗属性。
     *
     * @author tongruiyuan
     * @since 2026-11-19 13:35
     */
    public static final class BattleBonuses {
        private static final String[] BASE = {
                "hp_max", "physical_attack", "special_attack", "physical_defense", "special_defense", "speed"
        };
        private static final int[] FLAT = {5, 6, 7, 8, 9, 14};
        private static final int[] RELATIVE = {24, 26, 28, 27, 29, 25};
        private static final String[] DIRECT = {"hit_chance", "dodge_chance", "crit_chance", "toughness"};
        private static final String[] RATES = {
                "water_damage", "fire_damage", "grass_damage", "dark_damage", "fairy_damage", "dragon_damage",
                "ice_damage", "ghost_damage", "skill_damage_reduced", "water_reduce", "fire_reduce", "grass_reduce",
                "dark_reduce", "fairy_reduce", "dragon_reduce", "ice_reduce", "ghost_reduce"
        };
        private static final String[] PHYSICAL_RATES = {
                "normal_damage", "bug_damage", "poison_damage", "electric_damage", "flying_damage", "ground_damage",
                "fight_damage", "psychic_damage", "rock_damage", "steel_damage", "normal_reduce", "bug_reduce",
                "poison_reduce", "electric_reduce", "flying_reduce", "ground_reduce", "fight_reduce", "psychic_reduce",
                "rock_reduce", "steel_reduce"
        };
        private final Map<Integer, Integer> attributes;
        private final Map<Integer, Integer> statues;
        private final Map<Integer, Map<Integer, Integer>> research;
        /** 当前普通队伍宠物六维百分比，仅作用于本次精灵快照。 */
        private Map<Integer,Integer> petPercent=Collections.emptyMap();
        /** 宠物次级属性保留原协议数值。 */
        private Map<String,Integer> petDirect=Collections.emptyMap();
        /** 命中当前实际阵位的宠物天赋触发。 */
        private java.util.List<Integer> petTriggers=Collections.emptyList();
        /** 有效道馆驻防的原伤害百分比。 */
        public final int stationDamagePercent;

        /**
         * 保存由本地外观持有目录及角色状态计算出的原属性累计值。
         *
         * @param attributes 原属性代码及其累计值
         * @param statues 当前家园雕像的原属性
         * @param stationDamagePercent 驻扎带来的伤害百分比
         * @param research 六个阵位的考古属性
         */
        private BattleBonuses(Map<Integer, Integer> attributes, Map<Integer, Integer> statues, int stationDamagePercent,
                              Map<Integer, Map<Integer, Integer>> research) {
            this.research = research;
            this.statues = statues;
            this.stationDamagePercent = stationDamagePercent;
            this.attributes = attributes;
        }

        /**
         * 根据精灵所属普通队伍及实际阵位派生宠物加成，不修改共用角色快照。
         * @param elf 当前精灵
         * @return 绑定普通队伍宠物后的加成
         */
        public BattleBonuses withPet(com.pokemon.offline.elf.api.entity.LocalElf elf) {
            return withPet(elf,elf.position());
        }

        /**
         * 按玩法最终采用的阵位绑定普通队伍宠物。
         * @param elf 当前精灵及其队伍宠物
         * @param position 实际战斗阵位
         * @return 独立的单精灵加成快照
         */
        public BattleBonuses withPet(com.pokemon.offline.elf.api.entity.LocalElf elf,int position) {
            BattleBonuses result=new BattleBonuses(attributes,statues,stationDamagePercent,research);
            Map<Integer,Integer> percent=new HashMap<>();
            Map<String,Integer> direct=new HashMap<>();
            com.pokemon.offline.home.PetCombatRules.attributes(elf.battlePet).forEach((code,value)->{
                if(com.pokemon.offline.home.PetCombatRules.percent(code))percent.put(code,value);
                else direct.merge(com.pokemon.offline.home.PetCombatRules.field(code),value,Math::addExact);
            });
            result.petPercent=percent;result.petDirect=direct;
            result.petTriggers=com.pokemon.offline.home.PetCombatRules.triggers(elf.battlePet,position);
            return result;
        }

        /**
         * 计算基础属性及原表的千分比加成。
         *
         * @param field 原战斗协议的属性名
         * @param base 精灵现有的本地战斗属性
         * @return 应用外观加成的本地属性
         */
        public int attribute(String field, int base) {
            return attribute(field, base, 0);
        }

        /**
         * 按当前阵位计算精灵基础属性。
         * @param field 原战斗属性名
         * @param base 精灵已有属性
         * @param position 实际战斗阵位，一至六
         * @return 叠加角色及适用考古加成后的属性
         */
        public int attribute(String field, int base, int position) {
            for (int i = 0; i < BASE.length; i++) {
                if (BASE[i].equals(field)) {
                    Map<Integer, Integer> row = research.getOrDefault(position, Collections.emptyMap());
                    return Math.toIntExact(Math.round((base + (long) raw(FLAT[i]) + row.getOrDefault(FLAT[i], 0))
                            * (1000L + raw(RELATIVE[i]) + row.getOrDefault(RELATIVE[i], 0)) / 1000.0));
                }
            }
            return base;
        }

        /** 获取当前在建雕像对指定原属性的加成。
         * @param code 原属性编号
         * @return 原表数值
         */
        public int statue(int code) {
            return statues.getOrDefault(code, 0);
        }

        /**
         * 将快照属性应用到当前参战精灵。
         * 原属性表的百分比原值已经是千分值（原界面以原值除以十显示百分数），
         * 与战斗协议使用相同单位；不可再次乘十。技能点上限等固定属性沿用原值。
         *
         * @param fighter 已完成精灵培养计算的原战报属性
         */
        public void apply(Map<String, Object> fighter) {
            int position = ((Number) fighter.getOrDefault("position", 0)).intValue();
            Map<Integer, Integer> combined = new HashMap<>(attributes);
            research.getOrDefault(position, Collections.emptyMap()).forEach((code, value) -> combined.merge(code, value, Math::addExact));
            petPercent.forEach((code,value)->combined.merge(code,value,Math::addExact));
            new BattleBonuses(combined, statues, stationDamagePercent, Collections.emptyMap()).applyCombined(fighter);
            petDirect.forEach((field,value)->increment(fighter,field,value));
            if(!petTriggers.isEmpty()) {
                java.util.Set<Integer> triggers=new java.util.LinkedHashSet<>();
                Object original=fighter.get("trigger");
                if(original instanceof Iterable)for(Object value:(Iterable<?>)original)
                    if(value instanceof Number)triggers.add(((Number)value).intValue());
                triggers.addAll(petTriggers);
                fighter.put("trigger",new java.util.ArrayList<>(triggers));
            }
        }

        /**
         * 应用已经按阵位汇总的单份属性。
         * @param fighter 当前参战精灵
         */
        private void applyCombined(Map<String, Object> fighter) {
            for (String field : BASE) {
                fighter.put(field, attribute(field, ((Number) fighter.get(field)).intValue()));
            }
            fighter.put("hp", fighter.get("hp_max"));
            increment(fighter, "damage_bonus", statues.getOrDefault(23, 0));
            increment(fighter, "crit_chance", statues.getOrDefault(12, 0));
            increment(fighter, "crit_damage_bonus", statues.getOrDefault(22, 0));
            increment(fighter, "sex_bonus", statues.getOrDefault(3, 0));
            increment(fighter, "real_damage_reduction", statues.getOrDefault(101, 0));
            increment(fighter, "add_skill_point_limit", statues.getOrDefault(63, 0));
            for (int i = 0; i < DIRECT.length; i++) {
                increment(fighter, DIRECT[i], raw(10 + i));
            }
            increment(fighter, "reduced", raw(20));
            increment(fighter, "crit_damage_bonus", raw(22));
            increment(fighter, "damage_bonus", raw(23));
            increment(fighter, "sex_bonus", raw(3));
            increment(fighter, "sex_reduce", raw(4));
            increment(fighter, "prowess_damage_bonus", raw(111));
            increment(fighter,"physical_damage_bonus",raw(59));
            increment(fighter,"special_damage_bonus",raw(60));
            increment(fighter,"physical_damage_reduced",raw(61));
            increment(fighter,"special_damage_reduced",raw(62));
            increment(fighter,"add_skill_point_limit",raw(63));
            increment(fighter,"ultra_reduce",raw(108));
            increment(fighter,"rage_provide",raw(109));
            increment(fighter,"element_provide",raw(110));
            increment(fighter,"real_damage_reduction",raw(101));
            increment(fighter,"burning_bonus",raw(15));
            increment(fighter,"poison_bonus",raw(16));
            increment(fighter,"poisondot_reduce",raw(17));
            increment(fighter,"firedot_reduce",raw(18));
            for (int i = 0; i < RATES.length; i++) {
                increment(fighter, RATES[i], raw(34 + i));
            }
            for (int i = 0; i < PHYSICAL_RATES.length; i++) {
                increment(fighter, PHYSICAL_RATES[i], raw(71 + i));
            }
        }

        /**
         * 取出原属性累计值。
         *
         * @param code 原属性代码
         * @return 该属性的累计值
         */
        private int raw(int code) {
            return Math.addExact(attributes.getOrDefault(code, 0),petPercent.getOrDefault(code,0));
        }

        /**
         * 在已有战斗字段上追加原单位增量。
         *
         * @param fighter 目标战斗数据
         * @param field 原协议属性名
         * @param amount 转换成战斗协议单位的属性增量
         */
        private static void increment(Map<String, Object> fighter, String field, int amount) {
            if (amount != 0) {
                fighter.put(field, ((Number) fighter.getOrDefault(field, 0)).intValue() + amount);
            }
        }
    }
}
