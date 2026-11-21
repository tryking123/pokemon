package com.pokemon.local.role;

import com.pokemon.local.activitylevel.TrainerRuneService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.role.api.entity.RoleCosmeticState;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本地称号及时装 Service，统一提供拥有状态、展示选择和生效属性。
 *
 * @author tongruiyuan
 * @since 2026-11-19 13:35
 */
@Service
public class RoleCosmeticService {
    private final com.pokemon.local.guild.GuildEncounterMapper archaeology;
    private final JsonNode tabletAttributes;
    private final org.springframework.beans.factory.ObjectProvider<TrainerRuneService> runes;
    private final com.pokemon.local.activitylevel.TrainerRefinementService refinement;
    private final com.pokemon.local.home.HomeBuildingMapper buildings;
    private final JsonNode stationRules;
    private final com.pokemon.local.dojo.DojoStationMapper stations;
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
                               com.pokemon.local.guild.GuildEncounterMapper archaeology,
                               com.pokemon.local.dojo.DojoStationMapper stations,
                               com.pokemon.local.home.HomeBuildingMapper buildings,
                               com.pokemon.local.activitylevel.TrainerRefinementService refinement,
                               org.springframework.beans.factory.ObjectProvider<TrainerRuneService> runes) throws IOException {
        this.runes=runes;
        this.refinement=refinement;
        this.buildings = buildings;
        this.stations = stations;
        try (InputStream stream = new ClassPathResource("dojo-garrison.json").getInputStream()) {
            stationRules = json.readTree(stream);
        }
        this.archaeology = archaeology;
        try (InputStream stream = new ClassPathResource("guild/tablet-attributes.json").getInputStream()) {
            tabletAttributes = json.readTree(stream);
        }
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
        addAttributes(attributes, tabletAttributes.path(String.valueOf(archaeology.tabletLevel(roleId))));
        refinement.addAdvanceBonuses(roleId,attributes);
        TrainerRuneService runeService = runes.getIfAvailable();
        if (runeService != null) {
            runeService.addBonuses(roleId, attributes);
        }
        Map<Integer,Integer> statues = new HashMap<>();
        buildings.addStatues(roleId, statues);
        return new BattleBonuses(attributes, statues, bonus.isArray() ? bonus.get(1).asInt() : 0);
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
        /** 有效道馆驻防的原伤害百分比。 */
        public final int stationDamagePercent;

        /**
         * 保存由本地外观持有目录及角色状态计算出的原属性累计值。
         *
         * @param attributes 原属性代码及其累计值
         * @param statues 当前家园雕像的原属性
         * @param stationDamagePercent 驻扎带来的伤害百分比
         */
        private BattleBonuses(Map<Integer, Integer> attributes, Map<Integer, Integer> statues, int stationDamagePercent) {
            this.statues = statues;
            this.stationDamagePercent = stationDamagePercent;
            this.attributes = attributes;
        }

        /**
         * 计算基础属性及原表的千分比加成。
         *
         * @param field 原战斗协议的属性名
         * @param base 精灵现有的本地战斗属性
         * @return 应用外观加成的本地属性
         */
        public int attribute(String field, int base) {
            for (int i = 0; i < BASE.length; i++) {
                if (BASE[i].equals(field)) {
                    return Math.toIntExact(Math.round((base + (long) raw(FLAT[i])) * (1000 + raw(RELATIVE[i])) / 1000.0));
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
         *
         * @param fighter 已完成精灵培养计算的原战报属性
         */
        public void apply(Map<String, Object> fighter) {
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
            increment(fighter, "reduced", 10 * raw(20));
            increment(fighter, "crit_damage_bonus", 10 * raw(22));
            increment(fighter, "damage_bonus", 10 * raw(23));
            increment(fighter,"physical_damage_bonus",10*raw(59));
            increment(fighter,"special_damage_bonus",10*raw(60));
            increment(fighter,"physical_damage_reduced",10*raw(61));
            increment(fighter,"special_damage_reduced",10*raw(62));
            increment(fighter,"add_skill_point_limit",raw(63));
            for (int i = 0; i < RATES.length; i++) {
                increment(fighter, RATES[i], 10 * raw(34 + i));
            }
            for (int i = 0; i < PHYSICAL_RATES.length; i++) {
                increment(fighter, PHYSICAL_RATES[i], 10 * raw(71 + i));
            }
        }

        /**
         * 取出原属性累计值。
         *
         * @param code 原属性代码
         * @return 该属性的累计值
         */
        private int raw(int code) {
            return attributes.getOrDefault(code, 0);
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
