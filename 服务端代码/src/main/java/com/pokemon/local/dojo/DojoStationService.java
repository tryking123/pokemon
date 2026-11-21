package com.pokemon.local.dojo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.ArenaCombat;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.dojo.api.entity.DojoPlacement;
import com.pokemon.local.elf.ElfMapper;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.InputStream;
import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 道馆驻扎与争夺 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-19 21:21
 */
@Service
public class DojoStationService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final int[] PRICES = {20,40,80,160,200};
    private final DojoMapper dojo;
    private final DojoStationMapper mapper;
    private final RoleService roles;
    private final ElfMapper elves;
    private final BattleMapper formations;
    private final RoleCosmeticService cosmetics;
    private final InventoryService inventory;
    private final ArenaCombat combat;
    private final ObjectMapper json;
    private final JsonNode rules;
    private final Map<Integer,Integer> npcPower = new HashMap<>();

    /** 加载固定底包的 48 座馆主及驻防增益配置。
     * @param dojo 本人通关与编队
     * @param mapper 驻防状态
     * @param roles 当前角色
     * @param elves 当前精灵
     * @param formations 培养后的阵法
     * @param cosmetics 战斗属性
     * @param inventory 钱包
     * @param combat 实际战斗回合
     * @param json 战斗快照
     * @throws IOException 配置读取失败
     */
    DojoStationService(DojoMapper dojo, DojoStationMapper mapper, RoleService roles, ElfMapper elves,
                       BattleMapper formations, RoleCosmeticService cosmetics, InventoryService inventory,
                       ArenaCombat combat, ObjectMapper json) throws IOException {
        this.dojo = dojo;
        this.mapper = mapper;
        this.roles = roles;
        this.elves = elves;
        this.formations = formations;
        this.cosmetics = cosmetics;
        this.inventory = inventory;
        this.combat = combat;
        this.json = json;
        try (InputStream input = new ClassPathResource("dojo-garrison.json").getInputStream()) {
            rules = json.readTree(input);
        }
        rules.fieldNames().forEachRemaining(key -> {
            int idx = Integer.parseInt(key);
            npcPower.put(idx, npc(idx,"preview").path("power").asInt());
        });
    }

    /** 计算北京凌晨四时开始的日期。
     * @param now 当前 UTC 秒
     * @return 业务日
     */
    private LocalDate day(long now) {
        return Instant.ofEpochSecond(now).atZone(ZONE).minusHours(4).toLocalDate();
    }

    /** 获取本日机会，切日不延续增购次数。
     * @param roleId 角色
     * @param now 当前 UTC 秒
     * @return 次数快照
     */
    private ObjectNode quota(String roleId, long now) {
        long day = day(now).toEpochDay();
        ObjectNode value = mapper.state(roleId, "quota");
        if (value.path("day").asLong(-1) != day) {
            value.removeAll().put("day", day).put("times", 2).put("buy", 0);
        }
        return value;
    }

    /** 在跨角色争夺锁内清算到期驻防。
     * @param realm 当前区服
     * @param now 当前 UTC 秒
     * @return 仍有效的驻防，键为道馆编号
     */
    private Map<Integer,ObjectNode> settled(int realm, long now) {
        mapper.lockStations();
        Map<Integer,ObjectNode> active = new HashMap<>();
        for (ObjectNode station : mapper.stations(realm)) {
            if (station.path("end").asLong() <= now) {
                release(station, station.path("end").asLong());
            } else {
                active.put(station.path("idx").asInt(), station);
            }
        }
        return active;
    }

    /** 按实际占用时长和原积分上限写入应收邮件。
     * @param station 当前归属
     * @param stop 交接或到期时刻
     */
    private void release(ObjectNode station, long stop) {
        long elapsed = Math.max(0, Math.min(86400, stop - station.path("time").asLong()));
        int max = rules.path(station.path("idx").asText()).path("instructions2").asInt();
        mapper.release(station, elapsed * max / 86400, Instant.now().getEpochSecond());
    }

    /** 读取同服驻防与当前角色的可用次数。
     * @param role 当前角色
     * @return info 和次数
     */
    @Transactional
    public ObjectNode snapshot(LocalRole role) {
        long now = Instant.now().getEpochSecond();
        Map<Integer,ObjectNode> owned = settled(role.realmId, now);
        ObjectNode state = quota(role.id, now);
        ArrayNode info = state.putArray("info");
        int cleared = dojo.progress(role.id).history;
        for (int i = 1001; i <= cleared && rules.has(String.valueOf(i)); i++) {
            ObjectNode holder = owned.get(i);
            ObjectNode val = info.addObject().put("idx", i).put("status", 1);
            if (holder == null) {
                JsonNode rule = rules.get(String.valueOf(i));
                val.put("uid", "dojo-keeper-" + i).put("name","道馆馆主").put("level", rule.path("level").asInt())
                        .put("sex",0).put("icon",1).put("time",day(now).atTime(4,0).atZone(ZONE).toEpochSecond()-3601)
                        .put("battlepower",npcPower.get(i).intValue());
            } else {
                for (String field : new String[]{"uid","name","level","sex","icon","time"}) {
                    val.set(field, holder.get(field));
                }
                val.put("battlepower", holder.path("team").path("power").asInt());
                if (role.id.equals(holder.path("uid").asText())) {
                    state.put("place", true);
                }
            }
        }
        return state;
    }

    /** 购买一次原驻扎机会。
     * @param roleId 当前角色
     * @param receipt 本连接购买回执
     * @return 原客户端次数
     */
    @Transactional
    public ObjectNode buy(String roleId, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        ObjectNode previous = mapper.state(roleId, "buy:"+receipt);
        if (previous.has("errno")) {
            return previous.put("diamonds", role.diamonds);
        }
        long now = Instant.now().getEpochSecond();
        ObjectNode daily = quota(roleId, now);
        int count = daily.path("buy").asInt();
        int price = PRICES[Math.min(count, PRICES.length - 1)];
        if (dojo.progress(roleId).history < 1001 || count == Integer.MAX_VALUE
                || daily.path("times").asInt() == Integer.MAX_VALUE
                || !inventory.spendMaterials(roleId, Collections.emptyMap(), 0, price)) {
            return json.createObjectNode().put("errno", 3);
        }
        daily.put("buy", count+1).put("times", daily.path("times").asInt()+1);
        mapper.save(roleId, "quota", daily);
        ObjectNode reply = daily.deepCopy().put("errno", 0).put("diamonds",role.diamonds-price);
        mapper.save(roleId, "buy:"+receipt, reply);
        return reply;
    }

    /** 读取当前角色是否有待结算的驻防战。
     * @param roleId 当前角色
     * @return 是否在有效战斗中
     */
    public boolean active(String roleId) {
        ObjectNode battle = mapper.state(roleId, "battle");
        return battle.path("end").asLong() > Instant.now().getEpochSecond();
    }

    /** 按通关、保护期、次数与原道馆队伍发起争夺战。
     * @param roleId 当前角色
     * @param index 原道馆索引
     * @param receipt 本连接战斗请求
     * @return 原 C268 战斗响应
     */
    @Transactional
    public ObjectNode challenge(String roleId, long index, String receipt) {
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        Map<Integer,ObjectNode> owned = settled(role.realmId, now);
        ObjectNode invalid = json.createObjectNode().put("errno", 3);
        if (index < 1001 || index > dojo.progress(roleId).history || !rules.has(String.valueOf(index))
                || role.sceneId != 220000 && (role.sceneId < 72101 || role.sceneId > 72148)) {
            return invalid;
        }
        ObjectNode battle = mapper.state(roleId, "battle");
        if (battle.path("end").asLong() > now) {
            return index == battle.path("dojo").asInt() && receipt.equals(battle.path("receipt").asText())
                    ? battle : invalid;
        }
        if (mapper.state(roleId, "start:"+receipt).has("used")) {
            return invalid;
        }
        int idx = (int) index;
        ObjectNode holder = owned.get(idx);
        if (holder != null && (role.id.equals(holder.path("uid").asText()) || holder.path("time").asLong()+3600 > now)) {
            return invalid;
        }
        ObjectNode daily = quota(roleId, now);
        if (daily.path("times").asInt() <= 0) {
            return invalid;
        }
        ObjectNode player = player(role, cosmetics.battleBonuses(role.id));
        if (player.path("pokemon").size() == 0) {
            return invalid;
        }
        String token = UUID.randomUUID().toString();
        int times = daily.path("times").asInt()-1;
        long reset = day(now).plusDays(1).atTime(4,0).atZone(ZONE).toEpochSecond();
        battle = json.createObjectNode().put("errno",0).put("dojo",idx).put("scene",role.sceneId)
                .put("receipt",receipt).put("token",token).put("end",Math.min(reset, now + 1800))
                .put("holder",holder == null ? "" : holder.path("token").asText()).put("times",times)
                .put("weather",0).put("seed",ThreadLocalRandom.current().nextInt(1,Integer.MAX_VALUE))
                .put("isnpc",holder == null ? 1 : 0);
        battle.set("player",player);
        battle.set("monster",holder == null ? npc(idx, token) : holder.path("team").deepCopy());
        battle.set("defender",player(role,cosmetics.bonusesAtStation(role.id,idx)));
        mapper.save(roleId, "battle", battle);
        mapper.save(roleId, "quota", daily.put("times",times));
        mapper.save(roleId, "start:"+receipt, json.createObjectNode().put("used",true));
        return battle;
    }

    /** 以本场血量和合法技能判定抢夺结果，再次核对受挑战的驻防归属。
     * @param roleId 当前角色
     * @param skill 本回合技能
     * @param targets 本回合目标
     * @param flag 原行动模式
     * @param flee 是否主动逃跑
     * @return 0继续，1胜利，2失利，负数为未处理
     */
    @Transactional
    public int action(String roleId, int skill, List<?> targets, int flag, boolean flee) {
        LocalRole role = roles.lockRole(roleId);
        long now = Instant.now().getEpochSecond();
        ObjectNode battle = mapper.state(roleId,"battle");
        if (battle.path("end").asLong() <= now) {
            return -1;
        }
        int outcome = flee || role.sceneId != battle.path("scene").asInt() ? 2 : combat.action(battle,skill,targets,flag);
        if (outcome < 0) {
            return -1;
        }
        if (outcome == 1) {
            Map<Integer,ObjectNode> active = settled(role.realmId,now);
            int idx = battle.path("dojo").asInt();
            ObjectNode holder = active.get(idx);
            String current = holder == null ? "" : holder.path("token").asText();
            if (!current.equals(battle.path("holder").asText())) {
                outcome = 2;
            } else {
                for (ObjectNode station : active.values()) {
                    if (station.path("idx").asInt()==idx || station.path("uid").asText().equals(roleId)) {
                        release(station,now);
                    }
                }
                long reset = day(now).plusDays(1).atTime(4,0).atZone(ZONE).toEpochSecond();
                mapper.occupy(role.realmId,roleId,idx,battle.path("token").asText(),now,reset,
                        (ObjectNode)battle.path("defender"));
            }
        }
        mapper.save(roleId,"battle",outcome == 0 ? battle : json.createObjectNode());
        return outcome;
    }

    /** 从已保存道馆队伍构建带培养属性的真实玩家阵容。
     * @param role 当前角色
     * @param bonuses 生效属性
     * @return 原战斗编队
     */
    private ObjectNode player(LocalRole role, RoleCosmeticService.BattleBonuses bonuses) {
        ArrayNode members = json.createArrayNode();
        Map<String,LocalElf> roster = new HashMap<>();
        for (LocalElf elf : elves.list(role.id)) {
            roster.put(elf.id,elf);
        }
        for (DojoPlacement slot : dojo.team(role.id)) {
            LocalElf elf = roster.get(slot.elfId);
            if (elf != null && FirstBattleData.starter(elf.configId) != null) {
                members.add(combat.player(role.id,elf,slot.position,bonuses)
                        .put("stationDamagePercent",bonuses.stationDamagePercent));
            }
        }
        return combat.formation(role.id, formations.formationId(role.id,dojo.progress(role.id).tactics),false,members);
    }

    /** 构建当前道馆的原六只馆主队伍。
     * @param idx 道馆编号
     * @param token 本场战斗编号
     * @return 原配置对手的战斗编队
     */
    private ObjectNode npc(int idx, String token) {
        JsonNode rule = rules.path(String.valueOf(idx));
        ArrayNode actors = json.createArrayNode();
        String parent = "keeper-"+idx;
        for (JsonNode row : rule.path("actors")) {
            ObjectNode actor = row.deepCopy();
            int sid = row.path("enemy").asInt();
            actors.add(combat.fixedActor(actor,token+"-"+sid,parent,sid,row.path("sprite").asInt(),
                    rule.path("groupLevel").asInt(),row.path("position").asInt(),row.path("type1").asInt(),
                    row.path("type2").asInt(),row.path("quality").asInt(),row.path("skill").asInt(),
                    row.path("targetLimit").asInt(),0));
        }
        return combat.formation(parent,rule.path("formation").asInt(),true,actors);
    }
}
