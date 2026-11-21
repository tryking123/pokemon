package com.pokemon.local.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.secret.SecretService;
import org.springframework.stereotype.Service;
import org.springframework.core.annotation.Order;
import java.util.*;
import java.io.IOException;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/** 秘境原协议的任务面板、场景和战斗同步。
 * @author tongruiyuan
 * @since 2026-11-19 22:04
 */
@Service
@Order(-51)
final class SecretGameFeature implements GameFeature {
    private final SecretService secret;
    private final RoleService roles;
    private final org.springframework.beans.factory.ObjectProvider<WorldGameFeature> world;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final com.pokemon.local.elf.StoneFusionService fusion;
    private final Map<GameReply, String> connections = Collections.synchronizedMap(new WeakHashMap<>());
    private final ObjectMapper json;
    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<Map<String, Object>>() {
    };

    /** 连接秘境存档和角色位置。 */
    SecretGameFeature(SecretService secret, RoleService roles, ObjectMapper json, BagSnapshot bags,
            org.springframework.beans.factory.ObjectProvider<WorldGameFeature> world, BattleGameFeature battle,
            com.pokemon.local.elf.StoneFusionService fusion) {
        this.battle = battle;
        this.fusion = fusion;
        this.world = world;
        this.bags = bags;
        this.secret = secret;
        this.roles = roles;
        this.json = json;
    }

    /**
     * 同步普通怪掉落实收物品与邮件数量。
     * @param id 角色编号
     * @param killed 本次真实击杀结算
     * @param out 当前连接
     * @throws IOException 推送失败
     */
    private void pushMonsterDrop(String id, JsonNode killed, GameReply out) throws IOException {
        if (killed.has("drop")) {
            if (killed.has("added")) {
                out.push(14, data("flags", "duplicate.passDupAward", "adds", json.convertValue(killed.get("added"), BODY)));
            }
            bags.push(id, out);
            bags.pushMailCount(id, out);
        }
    }

    /** 发送角色本次已见地图格和仍可挑战的怪物。 */
    private void room(LocalRole role, GameReply out) throws IOException {
        JsonNode r = secret.run(role.id);
        Map<String, Object> actors = actorsIn(r, r.path("open"));
        actors.put("id", role.sceneId);
        actors.put("pos", data("x",role.x,"y",role.y));
        out.push(4, actors);
        progress(role.id, r, out);
        out.push(3, data("action", r.path("action").asInt()));
    }

    /** 收集指定地块内尚可交互的原版场景对象。
     * @param r 本局地图存档
     * @param tiles 本次可见的像素坐标
     * @return 怪物、机遇和普通地图物件
     */
    private Map<String, Object> actorsIn(JsonNode r, Iterable<JsonNode> tiles) {
        Set<JsonNode> visible = new HashSet<>();
        tiles.forEach(visible::add);
        List<Object> monsters = new ArrayList<>();
        for (JsonNode unit : r.path("units"))
            if (!unit.path("dead").asBoolean() && visible.contains(unit.path("pos"))) {
                ObjectNode wire = (ObjectNode) unit.deepCopy();
                wire.remove(Arrays.asList("dead", "boss"));
                monsters.add(json.convertValue(wire, BODY));
            }
        List<Object> others=new ArrayList<>();
        for(JsonNode wall:r.path("walls"))if(visible.contains(wall.path("pos")))others.add(json.convertValue(wall,BODY));
        for(JsonNode event:r.path("events"))if(!event.path("done").asBoolean() && visible.contains(event.path("pos"))){
            ObjectNode wire=event.deepCopy();wire.remove(Arrays.asList("done","receipt"));others.add(json.convertValue(wire,BODY));
        }
        List<Object> npcs = new ArrayList<>();
        for (JsonNode contact : r.path("contacts")) {
            if (visible.contains(contact.path("actor").path("pos"))) {
                npcs.add(json.convertValue(contact.path("actor"), BODY));
            }
        }
        return data("monsters", monsters, "npcs", npcs, "others", others);
    }

    /** 用客户端的动态场景增量消息显示新探索的物件。
     * @param roleId 当前角色
     * @param tiles 新揭开的地块
     * @param out 当前会话
     * @throws IOException 无法写出场景消息
     */
    private void revealActors(String roleId, Iterable<JsonNode> tiles, GameReply out) throws IOException {
        JsonNode run = secret.run(roleId);
        Map<String, Object> actors = actorsIn(run, tiles);
        actors.put("scence", run.path("map").asInt());
        out.push(801, actors);
    }

    /** 同步原副本导航的任务编号、行动、击败数量及队伍生命。
     * @param roleId 角色编号
     * @param r 本次秘境进度
     * @param out 当前连接
     * @throws IOException 发送失败
     */
    private void progress(String roleId, JsonNode r, GameReply out) throws IOException {
        int dead = 0;
        for (JsonNode u : r.path("units"))
            if (u.path("dead").asBoolean())
                dead++;
        ObjectNode health=secret.teamHealth(roleId,0);
        Object teamHealth=health==null ? data("teamid",1,"detail",Collections.emptyList()) : json.convertValue(health,BODY);
        List<Object> tools=new ArrayList<>(),info=new ArrayList<>();
        for(int type=2236;type<=2239;type++){
            String key=Integer.toString(type);
            int bought=type==2239?r.path("purchased").asInt():r.path("bought").path(key).asInt();
            int left=type==2239?r.path("buns").asInt():r.path("tools").path(key).asInt();
            int item=type-1219;if(left>0)tools.add(data("id",item,"n",left));
            info.add(data("id",item,"buy_limit",type<2238?5:2,"buy_num",bought,"use_num",bought-left));
        }
        if (secret.allowsInstantDefeat(r)) {
            int cards = r.path("tools").path("1021").asInt();
            if (cards > 0) {
                tools.add(data("id", 1021, "n", cards));
            }
            int purchased = r.path("instantPurchases").asInt();
            info.add(data("id", 1021, "buy_num", purchased, "use_num", purchased));
        }
        if(r.path("keys").asInt()>0)tools.add(data("id",1008,"n",r.path("keys").asInt()));
        out.push(2017,data("info",info));
        out.push(1451,
                data("dupinfo",
                        data("sid", r.path("task").asInt(), "dnum", 1, "extern",
                                data("idkillmonstern", dead, "mtotal", r.path("units").size())),
                        "dbags", data("use", tools.size(), "capacity", 4, "items", tools), "teamhp",
                        teamHealth));
        if(health!=null)out.push(379,data("teamhp",teamHealth,"flag",0));
        out.push(2012,
                data("total", r.path("units").size(), "finish", dead, "condition", 2, "task_finish",
                        r.path("won").asBoolean() ? 1 : 0, "task_total", 1, "index", r.path("index").asInt(1),
                        "action_num", r.path("actionBuys").asInt()));
    }

    /** 登录恢复原秘境列表和已进入场景。 */
    @Override
    public void synchronize(LocalRole role, GameReply out) throws IOException {
        if (role.level < 21)
            return;
        JsonNode s = secret.panel(role.id);
        out.push(2008, data("id", json.convertValue(s.path("offers"), List.class)));
        out.push(2009, data("one_refresh_num", s.path("free").asInt(), "free_refresh_num", s.path("free").asInt(),
                "cost_refresh_num", 0));
        if (s.path("run").path("map").asInt() == role.sceneId)
            room(roles.findById(role.id).orElse(role), out);
    }

    /** 处理真实入场、战斗和奖励领取。 */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request req, GameReply out) throws IOException {
        int t = req.type;
        if (t == 5 && number(req.body, "id", -1) == 40001 && secret.run(role.id).path("map").asInt() == 40001) {
            // 秘境位置与经过地块一起在原停步请求中提交。
            return true;
        }
        if (t == 6 && number(req.body, "id", -1) == 40001) {
            Object raw = req.body.get("pos");
            if (!(raw instanceof Map)) {
                return true;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> pos = (Map<String, Object>) raw;
            ObjectNode stop = secret.stopExploration(role.id, 40001, number(pos, "x", -1), number(pos, "y", -1));
            if (stop == null) {
                return false;
            }
            if (!stop.path("accepted").asBoolean()) {
                out.push(20, data("id", 40001, "pos", data("x", stop.path("x").asInt(), "y", stop.path("y").asInt())));
            } else if (stop.path("revealed").size() > 0) {
                revealActors(role.id, stop.path("revealed"), out);
            }
            out.push(3, data("action", stop.path("action").asInt()));
            return true;
        }
        if ((t == 382 || t == 5011 || t == 5017) && req.session <= 0) {
            return true;
        }
        if (t == 382) {
            String sid = (String) req.body.get("onlyid");
            Object lock = req.body.get("lock");
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            Boolean current = sid != null && lock instanceof Boolean
                    ? secret.lockStone(role.id, sid, (Boolean) lock, receipt) : null;
            out.send(req, current == null ? data("errno", 1) : data("errno", 0, "onlyid", sid, "lock", current));
            if (current != null && fusion.equipped(role.id, sid)) {
                battle.pushElfs(role.id, out);
            }
            return true;
        }
        if (t == 5011) {
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            ObjectNode result = secret.upgradeStone(role.id, (String) req.body.get("devour_A"), receipt);
            out.send(req, json.convertValue(result, BODY));
            if (result.has("info")) {
                bags.push(role.id, out);
                if (fusion.equipped(role.id, (String) req.body.get("devour_A"))) {
                    battle.pushElfs(role.id, out);
                }
                out.push(3, data("coins", roles.findById(role.id).orElse(role).coins));
            }
            return true;
        }
        if (t == 5017) {
            Object selection = req.body.get("uid");
            boolean ok = selection instanceof List && secret.decomposeStones(role.id, (List<?>) selection);
            out.send(req, data("errno", ok ? 0 : 1));
            if (ok) {
                bags.push(role.id, out);
                bags.pushMailCount(role.id, out);
            }
            return true;
        }
        if (t == 5067 && req.session > 0) {
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            JsonNode offers = secret.pray(role.id, number(req.body, "itype", 0), receipt);
            out.send(req, offers == null ? data("errno", 1)
                    : data("errno", 0, "id", json.convertValue(offers, List.class)));
            if (offers != null) {
                LocalRole saved = roles.findById(role.id).orElse(role);
                out.push(3, data("coins", saved.coins, "diamonds", saved.diamonds));
            }
            return true;
        }
        if (t == 5051) {
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            ObjectNode s = secret.refresh(role.id, (int) number(req.body, "itype", 0), receipt);
            out.send(req, s == null ? data("errno", 1)
                    : data("errno", 0, "id", json.convertValue(s.path("offers"), List.class)));
            if (s != null) {
                synchronize(role, out);
                bags.push(role.id, out);
                out.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        if (t == 5052) {
            boolean ok = secret.enter(role.id, (int) number(req.body, "id", 0));
            out.send(req, data("errno", ok ? 0 : 1));
            if (ok)
                out.push(3, data("challenge", secret.balance(role.id)));
            return true;
        }
        if (t == 5077 && req.session > 0) {
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            ObjectNode run = secret.buyAction(role.id, receipt);
            out.send(req, run == null ? data("errno", 1) : data("errno", 0, "num", run.path("actionBuys").asInt()));
            if (run != null) {
                out.push(3, data("action", run.path("action").asInt(), "diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        if (t == 5094 && req.session > 0) {
            Set<JsonNode> before = new HashSet<>();
            secret.run(role.id).path("open").forEach(before::add);
            JsonNode open = secret.revealAfterBoss(role.id);
            out.send(req, data("errno", open == null ? 1 : 0));
            if (open != null) {
                List<JsonNode> newlySeen = new ArrayList<>();
                for (JsonNode point : open) {
                    if (!before.contains(point)) {
                        newlySeen.add(point);
                    }
                }
                revealActors(role.id, newlySeen, out);
                progress(role.id,secret.run(role.id), out);
            }
            return true;
        }
        if (t == 5083 && req.session > 0) {
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            String uid = (String) req.body.get("uid");
            ObjectNode killed = secret.instantDefeat(role.id, uid, number(req.body, "monsterid", -1),
                    number(req.body, "x", -1), number(req.body, "y", -1), number(req.body, "itype", 0), receipt);
            List<?> rewards = killed != null && killed.has("drop")
                    ? Collections.singletonList(json.convertValue(killed.get("drop"), BODY)) : Collections.emptyList();
            out.send(req, data("errno", killed == null ? 1 : 0, "reward", rewards));
            if (killed != null && !killed.path("replay").asBoolean()) {
                JsonNode run = secret.run(role.id);
                out.push(12, data("sid", run.path("map").asInt(), "uids", Collections.singletonList(uid)));
                progress(role.id,run, out);
                out.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
                pushMonsterDrop(role.id, killed, out);
            }
            return true;
        }
        if (t == 5055 || t == 5056 || t >= 5080 && t <= 5082) {
            if (req.session <= 0) {
                return true;
            }
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            ObjectNode result = secret.encounter(role.id, t, number(req.body, "npcid", -1),
                    number(req.body, "x", -1), number(req.body, "y", -1), number(req.body, "index", -1), receipt);
            Map<String, Object> reply = data("errno", result == null ? 1 : 0);
            if (result != null && t == 5056) {
                reply.put("item", json.convertValue(result, BODY));
            }
            out.send(req, reply);
            if (result != null && t >= 5080) {
                out.push(2032, json.convertValue(result, BODY));
            }
            if (result != null && t != 5056 && t != 5082) {
                bags.push(role.id, out);
                bags.pushMailCount(role.id, out);
                out.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        if (t != 2211 && t != 2210 && t != 5059 && t != 5060 && t != 5061 && t != 8 && t != 2203 && t != 3001 && t != 3002 && (t < 5053 || t > 5058))
            return false;
        long portal = number(req.body, "id", 0);
        if (t == 8 && portal != 2100 && portal != 2101)
            return false;
        JsonNode r = secret.run(role.id);
        if (t == 8) {
            LocalRole saved = secret.travel(role.id, portal == 2100);
            out.send(req, data("errno", saved == null ? 1 : 0));
            if (saved != null) {
                if (portal == 2100)
                    room(saved, out);
                else
                    world.getIfAvailable().synchronizeScene(saved, out);
            }
            return true;
        }
        if (!r.has("map") || roles.findById(role.id).orElse(role).sceneId != r.path("map").asInt())
            return false;
        if (t == 5053) {
            Object raw = req.body.get("pos");
            if (!(raw instanceof Map)) {
                out.send(req, data("errno", 1));
                return true;
            }
            Map<?, ?> pos = (Map<?, ?>) raw;
            if (!(pos.get("x") instanceof Number) || !(pos.get("y") instanceof Number)) {
                out.send(req, data("errno", 1));
                return true;
            }
            int x = ((Number) pos.get("x")).intValue(), y = ((Number) pos.get("y")).intValue();
            int left = secret.reveal(role.id, x, y);
            out.send(req, data("errno", left < 0 ? 1 : 0, "action", Math.max(0, left)));
            if (left >= 0) {
                JsonNode point = json.createObjectNode().put("x", x).put("y", y);
                boolean seen = false;
                for (JsonNode tile : r.path("open")) {
                    seen |= tile.equals(point);
                }
                if (!seen) {
                    revealActors(role.id, Collections.singletonList(point), out);
                }
                out.push(3, data("action", left));
            }
            return true;
        }
        if(t==2211) {
            int selected=(int)number(req.body,"tid",-1);
            ObjectNode health=selected>0?secret.teamHealth(role.id,selected):null;
            out.send(req,data("errno",health==null?1:0));
            if(health!=null)out.push(379,data("teamhp",json.convertValue(health,BODY),"flag",0));
            return true;
        }
        if (t == 2203) {
            ObjectNode wire = secret
                    .start(role.id, String.valueOf(req.body.get("muid")), (int) number(req.body, "elsteamid", 1))
                    .deepCopy();
            wire.retain("errno", "seed", "weather", "player", "monster");
            for (String side : Arrays.asList("player", "monster"))
                for (JsonNode a : wire.path(side).path("pokemon"))
                    ((ObjectNode) a)
                            .remove(Arrays.asList("skill", "physical", "goldCarry", "homeStatues", "baseDamage", "targetLimit"));
            out.send(req, json.convertValue(wire, BODY));
            return true;
        }
        if (t == 3001 || t == 3002) {
            if (!r.has("fight"))
                return false;
            Object list = req.body.get("objs");
            JsonNode killed = secret.advance(role.id, (int) number(req.body, "sid", 0),
                    list instanceof List ? (List<?>) list : Collections.emptyList(), (int) number(req.body, "flag", -1),
                    t == 3002);
            if (killed.has("uid"))
                progress(role.id,secret.run(role.id), out);
            if (killed.has("uid"))
                out.push(12, data("sid", r.path("map").asInt(), "uids",
                        Collections.singletonList(killed.path("uid").asText())));
            pushMonsterDrop(role.id, killed, out);
            if (killed.path("boss").asBoolean()) {
                out.push(2036, data());
                out.push(2037, data("bossid", killed.path("sid").asInt()));
                int gold = killed.path("coins").asInt();
                Map<String, Object> reward = data("coins", gold);
                JsonNode stone = secret.run(role.id).path("stone");
                if (gold > 0 && stone.isObject())
                    reward.put("zstone", Collections.singletonList(json.convertValue(stone, BODY)));
                out.push(2010, data("reward", gold > 0 ? Collections.singletonList(reward) : Collections.emptyList()));
                if (gold > 0) {
                    bags.push(role.id, out);
                    out.push(3, data("coins", roles.findById(role.id).orElse(role).coins));
                }
            }
            return true;
        }
        if (t == 5054) {
            int gold = secret.claim(role.id);
            Map<String, Object> reward = data("coins", gold);
            JsonNode z = secret.run(role.id).path("stone");
            if (z.isObject())
                reward.put("zstone", Collections.singletonList(json.convertValue(z, BODY)));
            out.send(req, data("drop", gold > 0 ? Collections.singletonList(reward) : Collections.emptyList(), "action",
                    r.path("action").asInt(), "total", 1, "finish", gold > 0 ? 1 : 0));
            if (gold > 0)
                bags.push(role.id, out);
            if (gold > 0)
                out.push(3, data("coins", roles.findById(role.id).orElse(role).coins));
            return true;
        }
        if (t == 2210 || (t>=5058 && t<=5061)) {
            String receipt = connections.computeIfAbsent(out, k -> UUID.randomUUID().toString()) + ":" + req.session;
            boolean ok = t == 5058
                    ? secret.buy(role.id, (int) number(req.body, "itype", 0), (int) number(req.body, "n", 0), receipt)
                    : t==5061?secret.heal(role.id,(int)number(req.body,"team",0),receipt)
                    :secret.useTool(role.id,t==2210?2237:t==5059?2238:2236,String.valueOf(req.body.get("uid")),(int)number(req.body,"team",0),receipt);
            if(t==2210&&!ok)ok=secret.treasure(role.id,String.valueOf(req.body.get("uid")),receipt);
            out.send(req, data("errno", ok ? 0 : 1));
            if (ok) {
                if(t==5060){LocalRole saved=roles.findById(role.id).orElse(role);out.push(20,data("id",saved.sceneId,"pos",data("x",saved.x,"y",saved.y)));}
                if(t==2210)out.push(12,data("sid",r.path("map").asInt(),"uids",Collections.singletonList(req.body.get("uid"))));
                progress(role.id,secret.run(role.id), out);
                out.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        if (t == 5057) {
            List<Object> bosses = new ArrayList<>(), monsters = new ArrayList<>();
            for (JsonNode u : r.path("units"))
                (u.path("boss").asBoolean() ? bosses : monsters)
                        .add(data("id", u.path("sid").asInt(), "num", 1, "finish", u.path("dead").asBoolean() ? 1 : 0));
            List<Object> boxes=new ArrayList<>();for(JsonNode event:r.path("events")){
                int sid=event.path("sid").asInt();if(sid>=40001&&sid<=40003)
                    boxes.add(data("id",sid,"num",1,"finish",event.path("done").asBoolean()?1:0));}
            List<Object> shops = new ArrayList<>(), mirrors = new ArrayList<>();
            JsonNode shop = r.path("contacts").path("merchant"), mirror = r.path("contacts").path("mirror");
            if (shop.has("actor")) {
                shops.add(data("id", shop.path("actor").path("sid").asInt(), "num", 1, "finish",
                        shop.path("item").path("buy_flag").asInt()));
            }
            if (mirror.has("actor")) {
                mirrors.add(data("id", mirror.path("actor").path("sid").asInt(), "num", 1, "finish",
                        mirror.path("opened").asInt() == mirror.path("deck").size() ? 1 : 0));
            }
            out.send(req,data("errno",0,"boss",bosses,"monsters",monsters,"npcs",shops,"mirror",mirrors,"boxs",boxes));
            return true;
        }
        return false;
    }
}
