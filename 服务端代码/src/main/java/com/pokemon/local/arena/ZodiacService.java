package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.battle.api.entity.BattleAttempt;
import com.pokemon.local.dojo.DojoService;
import com.pokemon.local.dojo.DojoCombatService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.ElfGearService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.payment.GamePaymentService;
import java.util.concurrent.ThreadLocalRandom;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.guild.GuildEncounterService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.RoleCosmeticService;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 十二宫远征阵容、跨宫生命技能点与首胜积分 Service。
 * @author tongruiyuan
 * @since 2026-11-19 17:45
 */
@Service
public class ZodiacService {
    private final ArenaMapper store;
    private final com.pokemon.local.activity.LuckyBagService luckyBag;
    private final ArenaCombat combat;
    private final ArenaService arena;
    private final BattleMapper normal;
    private final GuildEncounterService guild;
    private final DojoCombatService dojoCombat;
    private final DojoService dojo;
    private final RoleService roles;
    private final RoleCosmeticService cosmetics;
    private final ElfService elves;
    private final ElfGearService gear;
    private final InventoryService inventory;
    private final GamePaymentService payments;
    private final ObjectMapper json;
    private final JsonNode cfg;

    /**
     * 装载固定底包的十二宫原池与技能消耗。
     * @param store 角色存档
     * @param combat 战斗快照引擎
     * @param arena 竞技互斥和已保存编队
     * @param normal 普通战斗及阵法
     * @param guild 公会战斗互斥
     * @param dojoCombat 道馆战斗互斥
     * @param dojo 助阵资格
     * @param roles 角色锁
     * @param cosmetics 角色属性加成
     * @param elves 角色精灵
     * @param gear 力石背包存档
     * @param inventory 钥匙及钻石扣费
     * @param luckyBag 福袋十二宫实际参战任务
     * @param payments 宝箱 VIP 资格
     * @param json 配置解析
     * @throws IOException 配置加载失败
     */
    ZodiacService(ArenaMapper store, ArenaCombat combat, ArenaService arena, BattleMapper normal,
                  GuildEncounterService guild, DojoCombatService dojoCombat, DojoService dojo, RoleService roles,
                  RoleCosmeticService cosmetics, ElfService elves, ElfGearService gear, InventoryService inventory,
                  GamePaymentService payments, ObjectMapper json, com.pokemon.local.activity.LuckyBagService luckyBag) throws IOException {
        this.luckyBag = luckyBag;
        this.gear = gear;
        this.inventory = inventory;
        this.payments = payments;
        this.store = store;
        this.combat = combat;
        this.arena = arena;
        this.normal = normal;
        this.guild = guild;
        this.dojoCombat = dojoCombat;
        this.dojo = dojo;
        this.roles = roles;
        this.cosmetics = cosmetics;
        this.elves = elves;
        this.json = json;
        try (InputStream input = new ClassPathResource("zodiac/expedition.json").getInputStream()) {
            cfg = json.readTree(input);
        }
    }

    /** 读取当前战斗。
     * @param id 角色编号
     * @return 是否已有待完成动作的十二宫战斗
     */
    public boolean active(String id) {
        return store.load(id, "zodiac:battle").has("seed");
    }

    /** 从原单怪模板生成当前编号的六槽编队。
     * @param progress 远征进度
     * @param palace 宫号
     * @return 敌方六个演员
     */
    private ArrayNode enemies(ObjectNode progress, int palace) {
        int group = (progress.path("sequence").asInt(1) - 1) % 5;
        JsonNode frozen = progress.path("missions").path(String.valueOf(palace));
        ArrayNode team = frozen.isArray() ? frozen.deepCopy() : json.createArrayNode();
        for (int slot = team.size(); slot < 6; slot++) {
            int sid = cfg.path("palaces").path(palace - 1).path(group * 6 + slot).asInt();
            ObjectNode actor = cfg.path("enemies").path(Integer.toString(sid)).deepCopy();
            int skill = actor.path("skill").asInt();
            String parent = "zodiac-" + palace;
            combat.fixedActor(actor, parent + "-" + slot, parent, sid, actor.path("sprite").asInt(), 50,
                    slot + 1, actor.path("type1").asInt(), actor.path("type2").asInt(), actor.path("quality").asInt(),
                    skill, 1, 0);
            team.add(actor);
        }
        for (JsonNode member : team) {
            ObjectNode actor = (ObjectNode) member;
            JsonNode previous = progress.path("foes").path(actor.path("sid").asText());
            if (previous.isObject()) {
                actor.put("hp", previous.path("hp").asInt());
                actor.put("sp", previous.path("sp").asInt());
            }
        }
        return team;
    }

    /** 从角色实例与远征历史恢复可用编队。
     * @param id 角色编号
     * @param state 远征进度
     * @param selection 原编队
     * @return 可参战的角色演员
     */
    private ArrayNode players(String id, ObjectNode state, JsonNode selection) {
        Map<String, LocalElf> owned = new HashMap<>();
        for (LocalElf elf : elves.list(id)) {
            owned.put(elf.id, elf);
        }
        ArrayNode team = json.createArrayNode();
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(id);
        for (JsonNode entry : selection) {
            LocalElf elf = owned.get(entry.path("id").asText());
            if (elf == null || elf.level < 50 || dojo.isAiding(id, elf.id)) {
                continue;
            }
            JsonNode previous = state.path("record").path(elf.id);
            if (previous.has("hp") && previous.path("hp").asInt() <= 0) {
                continue;
            }
            ObjectNode actor = combat.player(id, elf, entry.path("position").asInt(), bonuses);
            if (previous.has("hp")) {
                actor.put("hp", Math.min(previous.path("hp").asInt(), actor.path("hp_max").asInt()));
                actor.put("sp", previous.path("sp").asInt());
            }
            team.add(actor);
        }
        return team;
    }

    /** 新一天允许重新远征，已有战斗的存档保留至结算。
     * @param id 角色编号
     * @return 当前有效的进度
     */
    private ObjectNode progress(String id) {
        long day = LocalDate.now(ZoneId.of("Asia/Shanghai")).toEpochDay();
        ObjectNode state = store.load(id, "zodiac:progress");
        if (state.has("day") && state.path("day").asLong() < day
                && (state.path("palace").asInt(1) > 12 || state.path("defeated").asBoolean()) && !active(id)) {
            int sequence = state.path("sequence").asInt(1);
            state = json.createObjectNode().put("sequence", sequence + 1);
        }
        return state;
    }

    /** 原 S810 的完整宫位、队伍和生命快照。
     * @param id 角色编号
     * @return 十二宫面板协议体
     */
    @Transactional
    public ObjectNode snapshot(String id) {
        roles.lockRole(id);
        ObjectNode state = progress(id);
        ObjectNode view = arena.zodiacTeam(id);
        view.put("score", store.load(id, "zodiac:wallet").path("score").asLong());
        view.put("missionId", state.path("palace").asInt(1));
        ArrayNode missions = view.putArray("missiondata");
        for (int i = 1; i <= 12; i++) {
            ArrayNode enemies = enemies(state, i);
            ObjectNode mission = missions.addObject().put("id", i).put("level", 50).put("tactics", 101)
                    .put("power", combat.power(enemies));
            ArrayNode list = mission.putArray("monlist");
            for (JsonNode actor : enemies) {
                list.addObject().put("sid", actor.path("sid").asInt()).put("hp", actor.path("hp").asInt())
                        .put("hp_max", actor.path("hp_max").asInt()).put("pos", actor.path("position").asInt());
            }
        }
        ArrayNode record = view.putArray("record");
        state.path("record").elements().forEachRemaining(record::add);
        ArrayNode boxes = view.putArray("box");
        state.path("box").fields().forEachRemaining(entry -> boxes.addObject()
                .put("id", Integer.parseInt(entry.getKey())).put("times", entry.getValue().asInt()));
        return view;
    }

    /** 创建或恢复当前宫战斗。
     * @param id 角色编号
     * @return 原战斗开场或错误码
     */
    @Transactional
    public ObjectNode start(String id) {
        roles.lockRole(id);
        ObjectNode fight = store.load(id, "zodiac:battle");
        if (fight.has("seed")) {
            return fight;
        }
        BattleAttempt other = normal.findAttempt(id);
        if (arena.active(id) || guild.active(id) || dojoCombat.active(id)
                || other != null && "ACTIVE".equals(other.status) && Instant.now().getEpochSecond() - other.startedAt < 1200) {
            return json.createObjectNode().put("errno", 2002);
        }
        ObjectNode state = progress(id);
        int palace = state.path("palace").asInt(1);
        if (palace > 12) {
            return json.createObjectNode().put("errno", 2003);
        }
        JsonNode lineup = arena.zodiacTeam(id);
        ArrayNode team = players(id, state, lineup.path("teaminfo"));
        if (team.isEmpty()) {
            return json.createObjectNode().put("errno", 100021);
        }
        if (!state.has("day")) {
            state.put("day", LocalDate.now(ZoneId.of("Asia/Shanghai")).toEpochDay()).put("version", cfg.path("version").asText());
            ObjectNode stages = state.putObject("missions");
            for (int i = 1; i <= 12; i++) {
                stages.set(Integer.toString(i), enemies(state, i));
            }
        }
        ArrayNode enemy = enemies(state, palace);
        String opponent = "zodiac-" + UUID.randomUUID();
        for (JsonNode member : enemy) {
            ObjectNode actor = (ObjectNode) member;
            actor.put("parent", opponent).put("id", opponent + "-" + actor.path("position").asInt());
        }
        fight.put("errno", 0).put("seed", (UUID.randomUUID().hashCode() & Integer.MAX_VALUE) | 1)
                .put("weather", 0).put("limitround", 999).put("limitwinner", opponent);
        fight.set("player", combat.formation(id, normal.formationId(id, lineup.path("tactics").asInt(1)), false, team));
        fight.set("monster", combat.formation(opponent, 101, true, enemy));
        store.save(id, "zodiac:progress", state);
        store.save(id, "zodiac:battle", fight);
        luckyBag.participate(id,61003);
        return fight;
    }

    /** 执行原主动技能、自动战斗或撤退，按演员结转生命和技能点。
     * @param id 角色编号
     * @param skill 原技能编号
     * @param targets 目标实例编号
     * @param flag 原同步标志
     * @param flee 是否撤退
     * @return 零继续，一胜利，二失败，负数未受理
     */
    @Transactional
    public int advance(String id, int skill, List<?> targets, int flag, boolean flee) {
        roles.lockRole(id);
        ObjectNode fight = store.load(id, "zodiac:battle");
        if (!fight.has("seed")) {
            return -1;
        }
        int result;
        if (flee) {
            result = 2;
        } else if (skill == 0 && targets.isEmpty() && (flag == 0 || flag == 1)) {
            result = autoAction(fight);
        } else {
            result = combat.action(fight, skill, targets, flag, cfg.path("costs"), 999);
        }
        if (result == 0) {
            store.save(id, "zodiac:battle", fight);
        }
        if (result <= 0) {
            return result;
        }
        ObjectNode state = store.load(id, "zodiac:progress");
        for (JsonNode actor : fight.path("player").path("pokemon")) {
            int hp = actor.path("hp").asInt();
            int max = actor.path("hp_max").asInt();
            if (flee || fight.path("round").asInt() >= 999) {
                hp = Math.max(0, hp - (int) Math.ceil(max / 10.0));
            }
            String uid = actor.path("id").asText();
            state.with("record").putObject(uid).put("id", uid).put("hp", hp).put("hp_max", max).put("sp", actor.path("sp").asInt());
        }
        for (JsonNode enemy : fight.path("monster").path("pokemon")) {
            state.with("foes").putObject(enemy.path("sid").asText()).put("hp", enemy.path("hp").asInt()).put("sp", enemy.path("sp").asInt());
        }
        if (result == 1) {
            int palace = state.path("palace").asInt(1);
            ObjectNode wallet = store.load(id, "zodiac:wallet");
            wallet.put("score", Math.addExact(wallet.path("score").asLong(), 30L * palace));
            store.save(id, "zodiac:wallet", wallet);
            state.put("palace", palace + 1);
        }
        state.put("defeated", result == 2 && !flee);
        store.save(id, "zodiac:progress", state);
        store.remove(id, "zodiac:battle");
        return result;
    }

    /**
     * 自动同步一回合时选择可用技能及存活目标，随后按实际快照判定结果。
     * @param fight 当前宫战斗
     * @return 战斗结论
     */
    private int autoAction(ObjectNode fight) {
        JsonNode allies = fight.path("player").path("pokemon");
        JsonNode actor = null;
        for (int offset = 0; offset < allies.size(); offset++) {
            JsonNode member = allies.get((fight.path("turn").asInt() + offset) % allies.size());
            if (member.path("hp").asInt() > 0) {
                actor = member;
                break;
            }
        }
        if (actor == null) {
            return 2;
        }
        int skill = 40001;
        for (JsonNode candidate : actor.path("skill_bar")) {
            JsonNode cost = cfg.path("costs").path(candidate.asText());
            if (cost.isNumber() && cost.asInt() <= actor.path("sp").asInt()) {
                skill = candidate.asInt();
                break;
            }
        }
        String target = null;
        for (JsonNode enemy : fight.path("monster").path("pokemon")) {
            if (enemy.path("hp").asInt() > 0) {
                target = enemy.path("id").asText();
                break;
            }
        }
        if (target == null) {
            return 1;
        }
        return combat.action(fight, skill, Collections.singletonList(target), 1, cfg.path("costs"), 999);
    }

    /**
     * 按已通过的宫位和本宫开箱次数选择下一档，一次扣费并发放一个实际奖励。
     * @param id 角色编号
     * @param palace 原 C274 宫号
     * @param receipt 连接及请求序号
     * @return 原开箱回执
     */
    @Transactional
    public ObjectNode openBox(String id, int palace, String receipt) {
        roles.lockRole(id);
        ObjectNode prior = store.load(id, receipt);
        if (prior.has("errno")) {
            return prior;
        }
        ObjectNode state = progress(id);
        int times = state.path("box").path(String.valueOf(palace)).asInt();
        ObjectNode reply = json.createObjectNode().put("errno", 100021);
        if (palace < 3 || palace > 12 || palace % 3 != 0 || palace >= state.path("palace").asInt(1) || times >= 4) {
            return reply;
        }
        JsonNode box = cfg.path("boxes").path(times);
        if (payments.vip(id).level < box.path("vip").asInt()) {
            return reply;
        }
        int offset = ThreadLocalRandom.current().nextInt(5) * 3;
        JsonNode show = box.path("show");
        int kind = show.path(offset).asInt();
        int sid = show.path(offset + 1).asInt();
        int amount = show.path(offset + 2).asInt();
        if (kind == 13 ? !gear.canGrantGear(id, kind, sid, amount) : !inventory.canGrantItem(id, sid, amount)) {
            return reply;
        }
        int key = box.path("key").asInt();
        boolean paid = inventory.count(id, key) > 0 ? inventory.takeOne(id, key)
                : inventory.spendMaterials(id, Collections.emptyMap(), 0, box.path("price").asLong());
        if (!paid) {
            return reply;
        }
        if (kind == 13) {
            gear.grantGear(id, kind, sid, amount);
        } else {
            inventory.grant(id, sid, amount);
        }
        reply.put("errno", 0).put("idx", palace);
        reply.putArray("list").add(kind).add(sid).add(amount);
        state.with("box").put(String.valueOf(palace), times + 1);
        store.save(id, "zodiac:progress", state);
        store.save(id, receipt, reply);
        return reply;
    }
}
