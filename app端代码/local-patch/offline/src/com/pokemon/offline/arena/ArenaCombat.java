package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.battle.FirstBattleData;
import com.pokemon.offline.elf.ElfCombatGrowth;
import com.pokemon.offline.elf.ElfSkillBar;
import com.pokemon.offline.elf.api.entity.LocalElf;
import com.pokemon.offline.role.RoleCosmeticService;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.stereotype.Component;

/**
 * 本地竞技训练使用的阵容快照、底表与回合结算。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:08
 */
@Component
public final class ArenaCombat {
    static final String[] STATS = {"hp_max", "physical_attack", "special_attack",
            "physical_defense", "special_defense", "speed"};
    private static final int MAX_TURNS = 72;
    private final ObjectMapper json;
    private final com.pokemon.offline.home.PetCombatService pets;
    final JsonNode config;
    private final JsonNode damageRules;
    final JsonNode leagueNames;
    private static final String[] ELEMENTS = {"none", "normal", "fire", "water", "grass", "electric",
            "ice", "fight", "poison", "ground", "flying", "psychic", "bug", "rock", "ghost",
            "dragon", "dark", "steel", "fairy"};

    /**
     * 加载随包的 NPC 训练数据。
     *
     * @param json 配置编码器
     * @param pets 普通队伍宠物快照
     * @throws IOException 配置无法读取
     */
    ArenaCombat(ObjectMapper json,com.pokemon.offline.home.PetCombatService pets) throws IOException {
        this.json = json;
        this.pets = pets;
        try (InputStream input = new ClassPathResource("arena/training.json").getInputStream()) {
            config = json.readTree(input);
        }
        try (InputStream input = new ClassPathResource("arena/damage.json").getInputStream()) {
            damageRules = json.readTree(input);
        }
        try (InputStream input = new ClassPathResource("arena/league-names.json").getInputStream()) {
            leagueNames = json.readTree(input);
        }
    }

    /**
     * 按角色精灵及外观属性构建战斗精灵。
     *
     * @param roleId 所属角色
     * @param elf 已拥有的精灵
     * @param position 战斗阵位
     * @param bonuses 该角色的有效外观加成
     * @return 带技能规则的战斗精灵快照
     */
    public ObjectNode player(String roleId, LocalElf elf, int position, RoleCosmeticService.BattleBonuses bonuses) {
        return player(roleId,elf,position,bonuses,true);
    }

    /**
     * 按玩法实际阵位生成玩家成员，专属编队可明确关闭普通队伍宠物。
     * @param roleId 所属角色
     * @param elf 真实精灵
     * @param position 实际阵位
     * @param bonuses 角色共用加成
     * @param ordinaryPet 是否使用该精灵普通队伍的宠物
     * @return 原战斗成员及本地结算快照
     */
    public ObjectNode player(String roleId,LocalElf elf,int position,RoleCosmeticService.BattleBonuses bonuses,boolean ordinaryPet) {
        FirstBattleData.Starter starter = FirstBattleData.starter(elf.configId);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("position", position);
        values.put("element_provide", 1000);
        values.put("hp_max", 240);
        values.put("hp", 240);
        values.put("physical_attack", 130 + starter.physicalAttack);
        values.put("special_attack", 130 + starter.specialAttack);
        values.put("physical_defense", 90 + starter.physicalDefense);
        values.put("special_defense", 90 + starter.specialDefense);
        values.put("speed", 100 + starter.speed);
        ElfCombatGrowth.apply(elf, values);
        if(ordinaryPet)bonuses=bonuses.withPet(elf,position);
        bonuses.apply(values);
        ObjectNode actor = json.valueToTree(values);
        com.pokemon.offline.elf.GoldCarryRules.snapshot(elf, actor);
        initialize(actor, elf.id, roleId, elf.configId, elf.level, position, starter.type,
                starter.secondType, starter.quality, starter.skill);
        actor.put("hit_chance", actor.path("hit_chance").asInt()
                + com.pokemon.offline.elf.StoneWearRules.bonus(elf, "hit_chance"));
        actor.put("gender", com.pokemon.offline.home.BreedingData.gender(elf));
        for (int code : new int[]{3, 12, 22, 23}) {
            if (bonuses.statue(code) != 0) actor.with("homeStatues").put(Integer.toString(code), bonuses.statue(code));
        }
        actor.set("skill_bar", json.valueToTree(ElfSkillBar.battleSkills(elf, starter.skill)));
        int dynaSkill=com.pokemon.offline.elf.DynamaxRules.battleSkill(elf,java.util.concurrent.ThreadLocalRandom.current().nextInt());
        if (dynaSkill>0) { ((ArrayNode)actor.get("skill_bar")).add(dynaSkill); }
        applySkill(actor, starter.skill, starter.targetLimit);
        return actor;
    }

    /**
     * 将固定的本地怪物组属性转成可验证的战斗成员。
     *
     * @param actor 来自本地配置的既有怪物战斗属性
     * @param uid 本场怪物实例编号
     * @param parent 阵营编号
     * @param enemyId 怪物协议编号
     * @param sprite 怪物显示精灵编号
     * @param level 怪物等级
     * @param position 战斗阵位
     * @param type 第一属性
     * @param type2 第二属性
     * @param quality 精灵品质
     * @param skill 配备技能
     * @param targetLimit 配置的技能目标上限
     * @param power 配置的技能威力，零表示查随包底表
     * @return 本场怪物快照
     */
    public ObjectNode fixedActor(ObjectNode actor, String uid, String parent, int enemyId, int sprite, int level,
                                 int position, int type, int type2, int quality, int skill, int targetLimit, int power) {
        initialize(actor, uid, parent, enemyId, level, position, type, type2, quality, skill);
        actor.put("sprite", sprite);
        applySkill(actor, skill, targetLimit);
        if (power > 0) {
            actor.put("baseDamage", power);
        }
        return actor;
    }

    /**
     * 以相同上阵数和难度预算生成展示及战斗用 NPC 队伍。
     *
     * @param rosterId NPC 阵容模板 ID
     * @param parent NPC 阵营编号
     * @param reference 玩家当前出战精灵
     * @param difficulty 难度档序号，从 0 开始
     * @param level 参照精灵等级
     * @return 可进入原版战斗界面的 NPC 快照
     */
    ObjectNode opposition(String rosterId, String parent, ArrayNode reference, int difficulty, int level) {
        JsonNode roster = config.path("rosters").path(rosterId);
        ArrayNode members = json.createArrayNode();
        int slots = Math.min(Math.max(1, reference.size()), roster.path("members").size());
        for (int i = 0; i < slots; i++) {
            JsonNode member = roster.path("members").get(i);
            int sid = member.path("sid").asInt();
            JsonNode species = config.path("species").path(Integer.toString(sid));
            JsonNode stats = species.path("stats");
            ObjectNode actor = json.createObjectNode();
            for (String stat : STATS) {
                actor.put(stat, stats.path(stat).asInt() + ("hp_max".equals(stat) ? 190 : 80));
            }
            JsonNode skill = species.path("skill");
            initialize(actor, parent + "-" + (i + 1), parent, sid, level, member.path("position").asInt(),
                    species.path("type1").asInt(), species.path("type2").asInt(),
                    species.path("quality").asInt(), skill.path("id").asInt());
            actor.put("sprite", species.path("sprite").asInt(sid));
            applySkill(actor, skill.path("id").asInt(), skill.path("targetCount").asInt(1));
            members.add(actor);
        }
        double multiplier = config.path("difficulty").get(difficulty).asDouble();
        for (String stat : STATS) {
            long raw = 0;
            long budget = 0;
            for (JsonNode actor : members) {
                raw += actor.path(stat).asLong();
            }
            for (JsonNode actor : reference) {
                budget += actor.path(stat).asLong();
            }
            if (budget == 0) {
                budget = raw;
            }
            for (JsonNode node : members) {
                ObjectNode actor = (ObjectNode) node;
                actor.put(stat, Math.max(1, Math.round(actor.path(stat).asLong() * multiplier * budget / raw)));
            }
        }
        for (JsonNode row : members) {
            ObjectNode actor = (ObjectNode) row;
            actor.put("hp", actor.path("hp_max").asInt());
            actor.put("power", powerOf(actor));
        }
        return formation(parent, roster.path("formation").asInt(), true, members);
    }

    /**
     * 计算与 NPC 难度使用相同口径的阵容战力。
     *
     * @param actors 精灵快照数组
     * @return 队内精灵战力和
     */
    long power(ArrayNode actors) {
        long sum = 0;
        for (JsonNode actor : actors) {
            sum += powerOf(actor);
        }
        return sum;
    }

    /**
     * 构造原客户端所需的阵容数据。
     *
     * @param id 阵营编号
     * @param formation 阵法配置编号
     * @param npc 是否为本地 NPC
     * @param members 精灵快照
     * @return 原协议队伍数据
     */
    public ObjectNode formation(String id, int formation, boolean npc, ArrayNode members) {
        return formation(id,formation,npc,members,true);
    }

    /**
     * 生成原队伍协议，普通玩家队伍同时携带真实宠物对象。
     * @param id 阵营编号
     * @param formation 阵法编号
     * @param npc 是否为NPC
     * @param members 实际参战成员
     * @param ordinaryPet 是否使用普通队伍宠物
     * @return 原formation_t队伍
     */
    public ObjectNode formation(String id,int formation,boolean npc,ArrayNode members,boolean ordinaryPet) {
        ObjectNode result = json.createObjectNode();
        result.put("identifer", id);
        result.put("power", power(members));
        result.set("pokemon", members);
        result.put("sid", formation);
        result.put("preference", 1);
        result.put("pvp", false);
        result.put("isnpc", npc);
        if(!npc && ordinaryPet && !members.isEmpty()) {
            java.util.Map<String,Object> pet=pets.battlePetForMember(id,members.get(0).path("id").asText());
            if(!pet.isEmpty())result.set("pet",json.valueToTree(pet));
        }
        return result;
    }

    /**
     * 验证玩家行动，并由存档阵容的剩余生命和每回合伤害得出结果。
     *
     * @param battle 已保存的当前战斗
     * @param skill 玩家发送的技能编号
     * @param targets 玩家发送的敌方目标编号
     * @param flag 原操作模式编号
     * @return -1 为无效操作，0 为继续，1 为胜利，2 为失败
     */
    public int action(ObjectNode battle, int skill, List<?> targets, int flag) {
        if (battle.path("arenaDamageVersion").asInt() >= 2) {
            return trainingStep(battle, skill, targets, flag, false);
        }
        return action(battle, skill, targets, flag, null, MAX_TURNS);
    }

    /**
     * 使用同一训练回合引擎推进本地联赛双方的自动行动。
     * @param battle 已冻结阵容、随机种子及当前战况
     * @return 零为继续，一为左方胜，二为右方胜
     */
    int seasonTurn(ObjectNode battle) {
        return trainingStep(battle, 0, java.util.Collections.emptyList(), 0, true);
    }

    /**
     * 将原客户端隐藏的零技能点基础攻击加入战斗快照。
     * @param team 本次训练的玩家或 NPC 队伍
     */
    void addTrainingFallback(ObjectNode team) {
        for (JsonNode member : team.path("pokemon")) {
            if (!hasSkill(member, 40003)) {
                ((ArrayNode) member.get("skill_bar")).add(40003);
            }
        }
    }

    /**
     * 按原 SINGLE 战斗每个 C3001 只推进一个行动单位。敌方行动由空包驱动。
     *
     * @param battle 当前训练存档
     * @param skill 玩家选择的技能，省略时为零
     * @param targets 玩家选择的实际目标
     * @param flag 自动为零、手动为一，敌方省略
     * @param automatic 两支已冻结阵容是否均由赛季自动行动
     * @return 无效、继续、胜利或失败
     */
    private int trainingStep(ObjectNode battle, int skill, List<?> targets, int flag, boolean automatic) {
        ArrayNode allies = (ArrayNode) battle.path("player").path("pokemon");
        ArrayNode enemies = (ArrayNode) battle.path("monster").path("pokemon");
        ObjectNode actor = null;
        for (JsonNode side : new JsonNode[]{allies, enemies}) {
            for (JsonNode member : side) {
                if (member.path("hp").asInt() <= 0) {
                    continue;
                }
                int acted = member.path("actions").asInt();
                if (actor == null || acted < actor.path("actions").asInt() ||
                        acted == actor.path("actions").asInt() && member.path("speed").asInt() > actor.path("speed").asInt()) {
                    actor = (ObjectNode) member;
                }
            }
        }
        if (actor == null || flag < -1 || flag > 1) {
            return -1;
        }
        boolean player = actor.path("parent").equals(battle.path("player").path("identifer"));
        int round = actor.path("actions").asInt();
        int recovery = round > battle.path("round").asInt() ? 25 : 0;
        int available = Math.min(100 + actor.path("add_skill_point_limit").asInt(), actor.path("sp").asInt() + recovery);
        ArrayNode opposition = player ? enemies : allies;
        JsonNode skills = config.path("skills");
        int choice = 0;
        for (JsonNode equipped : actor.path("skill_bar")) {
            int id = equipped.asInt();
            JsonNode candidate = skills.path(Integer.toString(id));
            if (id == 40003) {
                continue;
            }
            if (!candidate.isMissingNode() && candidate.path("sp").asInt() <= available &&
                    (choice == 0 || skills.path(Integer.toString(choice)).path("weili").asInt() == 0 && candidate.path("weili").asInt() > 0)) {
                choice = id;
            }
        }
        if (choice == 0 && hasSkill(actor, 40003)) {
            choice = 40003;
        }
        if (automatic) {
            skill = choice;
        } else if (player) {
            if (skill == 0) {
                if (!targets.isEmpty() || flag < 0 || choice != 0) {
                    return -1;
                }
            } else if (flag < 0 || skill == 40003 && choice != 40003 || !hasSkill(actor, skill) || !skills.has(Integer.toString(skill)) ||
                    skills.path(Integer.toString(skill)).path("sp").asInt() > available) {
                return -1;
            }
        } else {
            if (skill != 0 || !targets.isEmpty() || flag >= 0 && choice != 0) {
                return -1;
            }
            skill = choice;
        }
        JsonNode move = skills.path(Integer.toString(skill));
        int targetType = move.path("targettype").asInt();
        List<ObjectNode> selected = new ArrayList<>();
        for (JsonNode side : new JsonNode[]{allies, enemies}) {
            for (JsonNode target : side) {
                boolean permitted = targetType == 0 && side != opposition || targetType == 1 && side == opposition ||
                        targetType == 2 && target == actor || targetType == 3;
                if (skill != 0 && permitted && target.path("hp").asInt() > 0 &&
                        (player && !automatic ? targets.contains(target.path("id").asText()) : selected.size() < move.path("targetnum").asInt())) {
                    selected.add((ObjectNode) target);
                }
            }
        }
        if (skill != 0 && (selected.isEmpty() || selected.size() > move.path("targetnum").asInt() ||
                player && !automatic && selected.size() != targets.size())) {
            return -1;
        }
        int[] before = automatic ? new int[13] : null;
        if (automatic) {
            for (JsonNode side : new JsonNode[]{allies,enemies}) {
                for (JsonNode member : side) {
                    int slot = member.path("position").asInt() * (side == allies ? 1 : -1);
                    before[slot + 6] = member.path("hp").asInt();
                }
            }
        }
        if (recovery > 0) {
            for (JsonNode side : new JsonNode[]{allies, enemies}) {
                for (JsonNode member : side) {
                    if (member.path("hp").asInt() > 0 && member.path("waterDefenseUntil").asInt() >= round) {
                        int maxHp = member.path("hp_max").asInt();
                        ((ObjectNode) member).put("hp", Math.min(maxHp, member.path("hp").asInt() + maxHp / 20));
                    }
                    ((ObjectNode) member).put("sp", Math.min(100 + member.path("add_skill_point_limit").asInt(),
                            member.path("sp").asInt() + recovery));
                }
            }
            battle.put("round", round);
        }
        actor.put("sp", available - move.path("sp").asInt());
        for (ObjectNode target : selected) {
            if (move.path("weili").asInt() > 0) {
                hit(battle, actor, target, skill, player);
            } else if (skill == 10110) {
                target.put("waterDefenseUntil", round + 3);
            }
        }
        actor.put("actions", round + 1);
        if (automatic) {
            ArrayNode step = battle.withArray("report").addArray();
            step.add(round+1).add((player ? 1 : -1)*actor.path("position").asInt()).add(skill);
            ArrayNode affected = step.addArray();
            for (JsonNode target : selected) {
                int sign = target.path("parent").equals(battle.path("player").path("identifer")) ? 1 : -1;
                affected.add(sign * target.path("position").asInt());
            }
            ArrayNode changes = step.addArray();
            for (JsonNode side : new JsonNode[]{allies,enemies}) {
                for (JsonNode member : side) {
                    int slot = member.path("position").asInt() * (side == allies ? 1 : -1);
                    int hp = member.path("hp").asInt();
                    if (before[slot+6] != hp) changes.add(slot).add(hp - before[slot+6]);
                }
            }
        }
        if (!alive(enemies)) {
            return 1;
        }
        if (!alive(allies)) {
            return 2;
        }
        if (round + 1 >= MAX_TURNS) {
            for (JsonNode side : new JsonNode[]{allies, enemies}) {
                for (JsonNode member : side) {
                    if (member.path("hp").asInt() > 0 && member.path("actions").asInt() < MAX_TURNS) {
                        return 0;
                    }
                }
            }
            return 2;
        }
        return 0;
    }

    /**
     * 使用指定技能点配置和回合上限处理远征动作。
     * @param battle 已锁定的战斗快照
     * @param skill 玩家技能
     * @param targets 敌方实例编号
     * @param flag 原操作模式
     * @param costs 十二宫技能消耗表，常规竞技无需结转技能点
     * @param limit 本玩法回合上限
     * @return 无效、继续、胜利或失败的标记
     */
    int action(ObjectNode battle, int skill, List<?> targets, int flag, JsonNode costs, int limit) {
        if (flag < -1 || flag > 1 || skill <= 0 || targets.isEmpty() || targets.size() > 6) {
            return -1;
        }
        ArrayNode allies = (ArrayNode) battle.path("player").path("pokemon");
        ArrayNode foes = (ArrayNode) battle.path("monster").path("pokemon");
        int turn = battle.path("turn").asInt();
        ObjectNode actor = null;
        int index = 0;
        for (int offset = 0; offset < allies.size(); offset++) {
            int slot = (turn + offset) % allies.size();
            JsonNode ally = allies.get(slot);
            if (ally.path("hp").asInt() > 0 && ally.path("skill_bar").isArray()
                    && (hasSkill(ally, skill) || costs != null && skill == 40001)
                    && (costs == null || costs.has(Integer.toString(skill))
                    && ally.path("sp").asInt() >= costs.path(Integer.toString(skill)).asInt())) {
                actor = (ObjectNode) ally;
                index = slot;
                break;
            }
        }
        if (actor == null) {
            return -1;
        }
        ObjectNode move = actor.deepCopy();
        applySkill(move, skill, actor.path("targetLimit").asInt());
        if (targets.size() > move.path("targetLimit").asInt()) {
            return -1;
        }
        Set<?> unique = new HashSet<>(targets);
        if (unique.size() != targets.size()) {
            return -1;
        }
        int livingTargets = 0;
        for (JsonNode foe : foes) {
            if (foe.path("hp").asInt() > 0 && unique.contains(foe.path("id").asText())) {
                livingTargets++;
            }
        }
        if (livingTargets != targets.size()) {
            return -1;
        }
        if (costs != null) {
            actor.put("sp", actor.path("sp").asInt() - costs.path(Integer.toString(skill)).asInt());
        }
        for (JsonNode foe : foes) {
            if (unique.contains(foe.path("id").asText())) {
                hit(battle, move, (ObjectNode) foe, skill, true);
            }
        }
        if (!alive(foes)) {
            return 1;
        }
        int round = battle.path("round").asInt();
        for (int step = 0; step < foes.size(); step++) {
            JsonNode enemy = foes.get((round + step) % foes.size());
            if (enemy.path("hp").asInt() > 0) {
                int enemySkill = enemy.path("skill_bar").path(0).asInt();
                ObjectNode enemyMove = (ObjectNode) enemy;
                if (costs != null) {
                    int cost = costs.path(Integer.toString(enemySkill)).asInt();
                    if (enemy.path("sp").asInt() < cost) {
                        enemySkill = 40001;
                        cost = 0;
                        enemyMove = enemyMove.deepCopy();
                        applySkill(enemyMove, enemySkill, 1);
                    }
                    ((ObjectNode) enemy).put("sp", enemy.path("sp").asInt() - cost);
                }
                hit(battle, enemyMove, actor, enemySkill, false);
                break;
            }
        }
        battle.put("turn", (index + 1) % allies.size());
        battle.put("round", round + 1);
        return !alive(allies) || round + 1 >= limit ? 2 : 0;
    }

    /**
     * 查询开战时快照中的已装备技能。
     *
     * @param member 参战精灵
     * @param skill 技能编号
     * @return 是否可使用该技能
     */
    private boolean hasSkill(JsonNode member, int skill) {
        for (JsonNode id : member.path("skill_bar")) {
            if (id.asInt() == skill) {
                return true;
            }
        }
        return false;
    }

    /**
     * 写入双方精灵共用的原版战斗字段。
     *
     * @param actor 精灵战斗数据
     * @param id 精灵实例编号
     * @param parent 阵营编号
     * @param sid 原精灵配置编号
     * @param level 本次等级
     * @param position 战斗阵位
     * @param type 第一属性
     * @param secondType 第二属性
     * @param quality 精灵品质
     * @param skill 玩家可出战的底包技能
     */
    private void initialize(ObjectNode actor, String id, String parent, int sid, int level, int position,
                            int type, int secondType, int quality, int skill) {
        actor.put("id", id);
        actor.put("parent", parent);
        actor.put("sid", sid);
        actor.put("sprite", sid);
        actor.put("level", level);
        actor.put("position", position);
        actor.put("type1", type);
        actor.put("type2", secondType);
        actor.put("quality", quality);
        actor.put("hp", actor.path("hp_max").asInt());
        actor.put("power", powerOf(actor));
        actor.put("hit_chance", 10000);
        actor.put("sp", 100);
        actor.put("gender", 1);
        actor.put("element_provide", actor.path("element_provide").asInt(1000));
        actor.put("add_skill_point_limit", actor.path("add_skill_point_limit").asInt());
        actor.set("skill_bar", json.valueToTree(Collections.singletonList(skill)));
        if (!actor.path("trigger").isArray()) {
            actor.putArray("trigger");
        }
    }

    /**
     * 累加精灵拥有的战斗基础属性。
     *
     * @param actor 精灵战斗数据
     * @return 单只精灵的训练预算
     */
    private long powerOf(JsonNode actor) {
        long power = 0;
        for (String stat : STATS) {
            power += actor.path(stat).asLong();
        }
        return power;
    }

    /**
     * 冻结技能在底包中配置的目标上限、威力和攻防类别。
     *
     * @param actor 该技能的使用者
     * @param sid 技能配置编号
     * @param fallback 底包一技能的保底目标数
     */
    private void applySkill(ObjectNode actor, int sid, int fallback) {
        JsonNode skill = config.path("skills").path(Integer.toString(sid));
        actor.put("targetLimit", Math.max(1, skill.path("targetnum").asInt(fallback)));
        actor.put("baseDamage", Math.max(1, skill.path("weili").asInt(60)));
        int type = skill.path("attacktype").asInt();
        actor.put("physical", (type == 6 ? actor.path("dynatype").asInt() : type) == 2);
    }

    /**
     * 由快照内的攻击、对应防御与技能威力计算伤害。
     *
     * @param battle 锁定阵容与当前回合的战斗存档
     * @param attacker 使用技能的精灵
     * @param target 受到攻击的精灵
     * @param skillId 本次实际使用的技能编号
     * @param playerAttack 是否由玩家队伍发起攻击
     */
    private void hit(ObjectNode battle, JsonNode attacker, ObjectNode target, int skillId, boolean playerAttack) {
        boolean physical = attacker.path("physical").asBoolean();
        int attack = attacker.path(physical ? "physical_attack" : "special_attack").asInt();
        int defense = target.path(physical ? "physical_defense" : "special_defense").asInt();
        int damage = Math.max(1, (int) Math.min(Integer.MAX_VALUE,
                attack * (attacker.path("baseDamage").asInt() / 100.0) * attack
                        / (attack + 2.0 * Math.max(1, defense))));
        if (battle.path("arenaDamageVersion").asInt() >= 1) {
            JsonNode move = config.path("skills").path(Integer.toString(skillId));
            damage = trainingDamage(battle, attacker, target, move, playerAttack);
        }
        if (battle.path("arenaDamageVersion").asInt() == 0 && attacker.has("stationDamagePercent")) {
            damage = (int) Math.min(Integer.MAX_VALUE,
                    (long) damage * (100 + attacker.path("stationDamagePercent").asInt()) / 100);
        }
        if (battle.path("arenaDamageVersion").asInt() == 0 && attacker.has("homeStatues")) {
            JsonNode statues = attacker.path("homeStatues");
            double factor = modifier(statues.path("23").asInt());
            if (attacker.path("gender").asInt() != target.path("gender").asInt()) {
                factor *= modifier(statues.path("3").asInt() - target.path("sex_reduce").asInt());
            }
            if ((statues.has("12") || statues.has("22"))
                    && roll(battle) <= (attacker.path("crit_chance").asDouble() - target.path("toughness").asDouble()) / 8) {
                factor *= 1.5 + Math.max(0, attacker.path("crit_damage_bonus").asDouble()
                        - target.path("basic_crit_damage_reduction").asDouble()) / 1000;
            }
            damage = (int) Math.min(Integer.MAX_VALUE, Math.floor(damage * factor));
        }
        if (damage > 0) {
            if (battle.path("arenaDamageVersion").asInt() == 0) {
                int attackType = config.path("skills").path(Integer.toString(skillId)).path("attacktype").asInt();
                if (attackType == 6) attackType = attacker.path("dynatype").asInt();
                JsonNode gold = attacker.path("goldCarry");
                double goldBonus = gold.path("damage_bonus").asDouble();
                if (attackType == 2) goldBonus += gold.path("physical_damage_bonus").asDouble();
                if (attackType == 1) goldBonus += gold.path("special_damage_bonus").asDouble();
                double goldReduction = target.path("goldCarry").path("skill_damage_reduced").asDouble();
                damage = (int)Math.min(Integer.MAX_VALUE, Math.floor(damage * modifier(goldBonus) * modifier(-goldReduction)));
            }
            int direct = com.pokemon.offline.elf.GoldCarryRules.extraDamage(skillId, attacker, target);
            damage = (int)Math.min(Integer.MAX_VALUE, (long)damage + direct);
        }
        // 原究极减伤使用万分比，在普通伤害和固定追加伤害合计后结算一次。
        damage = (int) ((long) damage * Math.max(0L, 10000L - target.path("ultra_reduce").asInt()) / 10000);
        target.put("hp", Math.max(0, target.path("hp").asInt() - damage));
    }

    /**
     * 按底包 stage.handle_damage 的直接攻击分支计算竞技训练伤害。
     *
     * @param battle 战斗存档
     * @param attacker 行动精灵
     * @param target 受击精灵
     * @param move 随包技能配置
     * @param playerAttack 是否为玩家本次行动
     * @return 扣减的生命值
     */
    private int trainingDamage(ObjectNode battle, JsonNode attacker, JsonNode target, JsonNode move, boolean playerAttack) {
        int type = move.path("attacktype").asInt();
        if (type == 6) { type = attacker.path("dynatype").asInt(); }
        int element = move.path("type").asInt();
        if ((type != 1 && type != 2) || move.path("weili").asInt() <= 0 || element < 0 || element >= ELEMENTS.length) {
            return 0;
        }
        String prefix = type == 2 ? "physical" : "special";
        String name = ELEMENTS[element];
        double level = attacker.path("level").asDouble() + attacker.path("damage_lv").asDouble();
        double attack = attacker.path(prefix + "_attack").asDouble();
        double targetDefense = target.path(prefix + "_defense").asDouble();
        if (target.path("waterDefenseUntil").asInt() > battle.path("round").asInt()) {
            targetDefense *= 1.2;
        }
        double defense = Math.max(attack * 0.2, targetDefense);
        double power = move.path("weili").asDouble() * (1 + attacker.path(name + "_power").asDouble() / 100);
        double base = ((level * 0.4 + 2) * power * attack / Math.max(1, defense) / 50 + 2) * 40;
        base *= damageRules.path("levelBase").asDouble() + level / damageRules.path("levelDivisor").asDouble();
        base *= modifier(attacker.path(prefix + "_damage_bonus").asDouble() - target.path(prefix + "_damage_reduced").asDouble());
        base = Math.max(1, base);
        double hitChance = Math.max(30, (800 + attacker.path("hit_chance").asDouble()
                - target.path("dodge_chance").asDouble()) / 8);
        if (roll(battle) > hitChance) {
            return 0;
        }
        if (roll(battle) <= (attacker.path("crit_chance").asDouble() - target.path("toughness").asDouble()) / 8) {
            base *= 1 + Math.max(0.5, 0.5 + (attacker.path("crit_damage_bonus").asDouble()
                    - target.path("basic_crit_damage_reduction").asDouble()) / 1000);
            base *= 1 - target.path("real_crit_damage_reduction").asDouble() / 1000;
        }
        base *= modifier(attacker.path("damage_bonus").asDouble() - target.path("reduced").asDouble());
        if (attacker.path("gender").asInt() != target.path("gender").asInt()) {
            base *= modifier(attacker.path("sex_bonus").asDouble() - target.path("sex_reduce").asDouble());
        }
        base *= modifier(attacker.path(name + "_damage").asDouble() - target.path("skill_damage_reduced").asDouble()
                - target.path(name + "_reduce").asDouble());
        JsonNode chart = damageRules.path("element");
        for (String field : new String[]{"type1", "type2"}) {
            int defenderType = target.path(field).asInt();
            if (defenderType > 0) {
                base *= chart.path(Integer.toString(1010 + 100 * element + defenderType)).asDouble(1);
            }
        }
        base = Math.floor(base * tactics(battle, playerAttack));
        if (!playerAttack && !battle.path("league").asBoolean() && battle.path("monster").path("isnpc").asBoolean()) {
            double difference = Math.max(0, attack - targetDefense);
            base *= damageRules.path("npcBase").asDouble();
            JsonNode bands = damageRules.path("npcBonus");
            for (int i = 0; i + 2 < bands.size(); i += 3) {
                double end = bands.get(i + 1).asDouble();
                if (difference >= bands.get(i).asDouble() && (end == 0 || difference <= end)) {
                    base += Math.min(bands.get(i + 2).asDouble() * difference * power,
                            damageRules.path("npcCap").asDouble() * base);
                    break;
                }
            }
            base = Math.floor(base);
        }
        int round = battle.path("round").asInt() + 1;
        int elapsed = round - damageRules.path("roundStart").asInt();
        if (elapsed >= 0) {
            int steps = elapsed / damageRules.path("roundPeriod").asInt();
            base *= 1 + (damageRules.path("roundInitial").asDouble() + steps * damageRules.path("roundStep").asDouble()) / 100;
        }
        return Math.max(1, (int) base);
    }

    /**
     * 根据开战快照中双方完整阵法编号确定攻方的增减伤倍率。
     *
     * @param battle 双方开战阵容
     * @param playerAttack 是否为玩家行动
     * @return 攻方阵法倍率
     */
    private double tactics(ObjectNode battle, boolean playerAttack) {
        int left = battle.path("player").path("sid").asInt();
        int right = battle.path("monster").path("sid").asInt();
        if (left / 100 == right / 100) {
            return 1;
        }
        JsonNode formations = damageRules.path("formations");
        JsonNode formation = formations.path(Integer.toString(left));
        if (formation.isMissingNode()) {
            return playerAttack ? 0.9 : 1.1;
        }
        if (!formations.has(Integer.toString(right))) {
            return playerAttack ? 1.1 : 0.9;
        }
        boolean advantage = false;
        for (JsonNode group : formation.path("restrain")) {
            if (group.asInt() == right / 100) {
                advantage = true;
                break;
            }
        }
        return advantage == playerAttack ? 1 + formation.path("increase").asDouble() / 1000
                : 1 - formation.path("reduce").asDouble() / 1000;
    }

    /**
     * 沿用底包随机数递推，随回合保存当前种子。
     *
     * @param battle 当前训练战斗
     * @return 1 至 100 的命中或暴击判定值
     */
    private int roll(ObjectNode battle) {
        int seed = battle.path("damageSeed").asInt(battle.path("seed").asInt());
        int next = seed * 214013 + 2531011;
        battle.put("damageSeed", next);
        return 1 + ((next >>> 16) & 32767) * 100 / 32768;
    }

    /**
     * 将底表的千分比增减伤裁剪到原战斗引擎允许的范围。
     *
     * @param perMille 攻守属性差
     * @return 最终倍率
     */
    private static double modifier(double perMille) {
        return Math.max(0.1, Math.min(10, 1 + perMille / 1000));
    }

    /**
     * 检查队伍是否存在仍可行动的精灵。
     *
     * @param actors 出战队伍
     * @return 任一成员有剩余生命
     */
    private boolean alive(ArrayNode actors) {
        for (JsonNode actor : actors) {
            if (actor.path("hp").asInt() > 0) {
                return true;
            }
        }
        return false;
    }
}
