package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

/**
 * 从上期普通赛的八个实际名次选拔精英选手，管理淘汰赛和场地竞猜。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:50
 */
final class EliteSeason {
    private static final int[] SEEDS = {0,7,3,4,1,6,2,5};
    private static final String[][] ELEMENTS = {{"water","fire"},{"grass","dark"},{"fairy","dragon"},
            {"ice","ghost"},{"normal","bug"},{"poison","electric"},{"flying","ground"}};
    private final LeagueSeasonService league;
    private final ArenaService arena;
    private final ArenaCombat combat;
    private final BattleMapper formations;
    private final InventoryService inventory;
    private final RoleService roles;

    /**
     * 共享普通联赛的回合、阵容和资产事务。
     * @param league 联赛对局结算
     * @param arena 上阵精灵
     * @param combat 阵容快照
     * @param formations 角色阵法
     * @param inventory 道具和邮件
     * @param roles 金币账本
     */
    EliteSeason(LeagueSeasonService league, ArenaService arena, ArenaCombat combat, BattleMapper formations,
                InventoryService inventory, RoleService roles) {
        this.league = league;
        this.arena = arena;
        this.combat = combat;
        this.formations = formations;
        this.inventory = inventory;
        this.roles = roles;
    }

    /**
     * 用上轮普通联赛的最终名次构建一支独立的八强签表。
     * @param book 本角色全部联赛存档
     * @param source 已结算的普通联赛
     * @param role 当前角色
     * @param today 北京自然日编号
     * @param result 本次资产通知
     */
    void qualify(ObjectNode book, ObjectNode source, LocalRole role, long today, ObjectNode result) {
        if (source.path("places").size() != 8 || source.path("matches").size() != 15) return;
        ObjectNode old = (ObjectNode) book.get("elite");
        long week = source.path("week").asLong() + 7;
        if (old != null && old.path("week").asLong() >= week) return;
        if (old != null && eligible(old)) {
            long now = Instant.now().getEpochSecond();
            int rank = rank(old, old.path("self").asInt());
            inventory.createRewardMail(role.id, "elite-" + old.path("week").asLong() + ":" + role.id, "本机精英赛名次奖励",
                    "上一期精英赛的未领奖励已寄到邮箱。", now, now + 30L * 86400,
                    Arrays.asList(1, prize(rank), 1));
            result.put("mail",true);
        }
        if (old != null && today - old.path("week").asLong() < 21) book.withArray("eliteArchives").add(old);
        ObjectNode event = book.putObject("elite");
        event.put("week",week);
        event.put("owner",role.id);
        event.put("stage",source.path("stage").asInt());
        event.put("seed",source.path("seed").asInt() ^ 0x5e155);
        event.put("rules",1);
        event.put("rostersVersion",source.path("rostersVersion").asText());
        event.putArray("matches");
        ArrayNode seats = event.putArray("seats");
        for (int i = 0; i < SEEDS.length; i++) {
            int oldSeat = source.path("places").get(SEEDS[i]).asInt();
            ObjectNode seat = source.path("seats").get(oldSeat).deepCopy();
            seat.with("profile").put("index",i+1);
            seats.add(seat);
            if (oldSeat == source.path("self").asInt()) event.put("self",i);
        }
        advance(book,role,today,result);
    }

    /**
     * 补做已到比赛日的单败淘汰轮次，并结算真实投注。
     * @param book 本角色联赛存档
     * @param role 已锁定角色
     * @param today 北京自然日编号
     * @param result 本次资产通知
     * @return 存档是否发生变化
     */
    boolean advance(ObjectNode book, LocalRole role, long today, ObjectNode result) {
        boolean changed = false;
        ArrayNode archive = book.withArray("eliteArchives");
        for (int i = archive.size()-1; i >= 0; i--) {
            if (today - archive.get(i).path("week").asLong() >= 21) {
                league.purgeReports(role.id,archive.get(i));
                archive.remove(i);
                changed = true;
            }
        }
        ObjectNode event = (ObjectNode)book.get("elite");
        if (event == null) return changed;
        long day = today - event.path("week").asLong() + 1;
        int allowed = day >= 7 ? 7 : day >= 5 ? 6 : day >= 3 ? 4 : 0;
        ArrayNode matches = event.withArray("matches");
        while (matches.size() < allowed) {
            int number = matches.size();
            int left = slot(event,number,0);
            int right = slot(event,number,1);
            event.put("buff",buff(event,number));
            league.play(event,left,right);
            ObjectNode match = (ObjectNode)matches.get(number);
            ObjectNode order = (ObjectNode) event.path("bets").get(Integer.toString(number));
            if (order != null && !order.has("payout")) {
                boolean red = match.path("winner").asInt() == left;
                long payout = order.path("side").asText().equals(red ? "red" : "blue") ? 7500L : 0L;
                order.put("payout",payout);
                if (payout != 0) {
                    roles.addCurrency(role.id,payout,0,0);
                    result.put("wallet",true);
                }
            }
            changed = true;
        }
        if (allowed == 7 && !event.has("finished")) {
            event.put("finished",true);
            ObjectNode champion = profile(event, matches.get(6).path("winner").asInt());
            champion.put("likes",0);
            book.with("eliteChampions").set(Integer.toString(event.path("stage").asInt()),champion);
            changed = true;
        }
        if (day >= 21 && !event.has("reportsExpired")) {
            league.purgeReports(role.id,event);
            event.put("reportsExpired",true);
            changed = true;
        }
        return changed;
    }

    /**
     * 应答原精英榜、膜拜、奖励、房间、竞猜和赛前同步。
     * @param book 本角色联赛存档
     * @param role 已锁定角色
     * @param type 客户端请求号
     * @param stage 训练师等阶
     * @param requestedRank 奖励档位
     * @param roomId 竞猜房间编号
     * @param side 请求竞猜的阵营
     * @param today 北京自然日编号
     * @param result 应答和资产变更
     * @return 是否已修改存档
     */
    boolean request(ObjectNode book, LocalRole role, int type, int stage, int requestedRank, String roomId,
                    String side, long today, ObjectNode result) {
        ObjectNode out = result.with("response");
        out.put("stage",stage);
        if (stage < 0 || stage > 2) return reject(out);
        ObjectNode event = (ObjectNode)book.get("elite");
        ObjectNode champion = (ObjectNode)book.path("eliteChampions").get(Integer.toString(stage));
        if (type == 239 || type == 240) {
            if (champion == null) return type == 239 ? false : reject(out);
            boolean liked = champion.path("likes").asInt() > 0;
            if (type == 239) {
                out.set("winner",champion);
                out.put("isliked",liked);
                return false;
            }
            out.put("likes",1);
            champion.put("likes",1);
            return !liked;
        }
        if (event == null || event.path("stage").asInt() != stage) return type == 241 || type == 243 ? false : reject(out);
        int self = event.path("self").asInt();
        int played = event.path("matches").size();
        long day = today - event.path("week").asLong() + 1;
        switch (type) {
            case 241:
                if (day > 7 && !eligible(event)) return false;
                ArrayNode map = out.putArray("map");
                for (int i=0; i<8; i++) map.add(profile(event,i));
                out.put("reward",eligible(event) ? rank(event,self)+1 : 1);
                return false;
            case 242:
                if (!eligible(event) || requestedRank != rank(event,self)) return reject(out);
                int item = prize(requestedRank);
                Map<Integer,Long> delivered = inventory.grantItemsOrMail(role.id,"elite:" + event.path("week").asLong(),
                        Collections.singletonMap(item,1L));
                event.put("claimed",true);
                out.put("rank",requestedRank);
                result.put("item",item);
                result.put("delivered",delivered.getOrDefault(item,0L));
                return true;
            case 243: case 244:
                return rooms(event,role,type,day,roomId,side,result);
            case 245:
                if (day != 1 || played != 0) return reject(out);
                ArrayNode members = arena.players(role,ArenaService.ARENA,0,1);
                if (members.isEmpty()) return reject(out);
                ObjectNode team = combat.formation(role.id, formations.formationId(role.id), false, members);
                combat.addTrainingFallback(team);
                ObjectNode seat = (ObjectNode)event.path("seats").get(self);
                seat.set("team",team);
                seat.with("profile").put("power",team.path("power").asLong());
                event.put("uploaded",Instant.now().getEpochSecond());
                return true;
            default:
                return reject(out);
        }
    }

    /**
     * 生成下一轮的实际房间，给已经淘汰的本机角色开一次竞猜。
     * @param event 本期精英赛
     * @param role 已锁定角色
     * @param type 请求号
     * @param day 当前赛季日
     * @param roomId 请求房间
     * @param side 请求阵营
     * @param result 应答及钱包通知
     * @return 是否已新增投注订单
     */
    private boolean rooms(ObjectNode event, LocalRole role, int type, long day, String roomId, String side, ObjectNode result) {
        ObjectNode out = result.with("response");
        int done = event.path("matches").size();
        if (day > 7 || done == 7 || !eliminated(event,event.path("self").asInt())) {
            return type == 243 ? false : reject(out);
        }
        int end = done == 4 ? 6 : 7;
        ArrayNode rooms = type == 243 ? out.putArray("roomlist") : null;
        for (int i=done; i<end; i++) {
            String id = "elite:" + event.path("week").asLong() + ":" + i;
            ObjectNode order = (ObjectNode)event.path("bets").get(Integer.toString(i));
            int left = slot(event,i,0), right = slot(event,i,1);
            if (rooms != null) {
                ObjectNode room = rooms.addObject();
                room.put("index",id);
                room.put("buff",buff(event,i));
                room.put("rank",done == 4 ? 3 : 2);
                ObjectNode red = profile(event,left);
                ObjectNode blue = profile(event,right);
                // 原客户端按红方固定签位归并半决赛房间；决赛只显示第一组。
                red.put("index",i == 5 ? 5 : 1);
                room.set("red",red);
                room.set("blue",blue);
                if (order != null) room.put("bet",order.path("side").asText());
            } else if (id.equals(roomId)) {
                if (!"red".equals(side) && !"blue".equals(side)) return reject(out);
                out.put("idx",id);
                out.put("bet",side);
                if (order != null) return order.path("side").asText().equals(side) ? false : reject(out);
                int daily = 0;
                for (JsonNode other : event.path("bets")) if (other.path("day").asLong() == day) daily += 5000;
                if (daily >= 5000 || event.path("bets").size() >= 5 ||
                        !inventory.spendMaterials(role.id,Collections.emptyMap(),5000,0)) return reject(out);
                ObjectNode saved = event.with("bets").putObject(Integer.toString(i));
                saved.put("day",day);
                saved.put("side",side);
                result.put("wallet",true);
                return true;
            }
        }
        return type == 243 ? false : reject(out);
    }

    /**
     * 应用原场地两种属性技能的伤害加成。
     * @param battle 本场独立的两队快照
     * @param buff 场地编号
     */
    static void applyBuff(ObjectNode battle,int buff) {
        for (String side : new String[]{"player","monster"}) {
            for (JsonNode entry : battle.path(side).path("pokemon")) {
                ObjectNode actor = (ObjectNode)entry;
                for (String element : ELEMENTS[buff-50021]) {
                    String field = element + "_damage";
                    actor.put(field,actor.path(field).asInt()+200);
                }
            }
        }
    }

    /**
     * 按种子和房间编号选择场地。
     * @param event 本期精英赛
     * @param number 对局序号
     * @return 本场固定增益编号
     */
    private int buff(ObjectNode event, int number) {
        return 50021 + Math.floorMod(event.path("seed").asInt() + number,7);
    }

    /**
     * 解析淘汰赛对阵的前置胜方。
     * @param event 本期精英赛
     * @param number 对局序号
     * @param side 对阵方
     * @return 上轮真实胜出的席位
     */
    private int slot(ObjectNode event, int number, int side) {
        if (number < 4) return number * 2 + side;
        return event.path("matches").get((number-4)*2+side).path("winner").asInt();
    }

    /**
     * 计算精英赛的排名档位。
     * @param event 本期精英赛
     * @param seat 席位
     * @return 目前已达到的四档排名
     */
    private int rank(ObjectNode event,int seat) {
        int tier = 4;
        for (JsonNode match : event.path("matches")) if (match.path("winner").asInt() == seat) tier--;
        return tier;
    }

    /**
     * 判断当前席位已被淘汰。
     * @param event 本期精英赛
     * @param seat 席位
     * @return 是否已有一场真实败绩
     */
    private boolean eliminated(ObjectNode event,int seat) {
        for (JsonNode match : event.path("matches")) {
            if ((match.path("left").asInt() == seat || match.path("right").asInt() == seat)
                    && match.path("winner").asInt() != seat) return true;
        }
        return false;
    }

    /**
     * 判断当前角色是否达到名次领奖条件。
     * @param event 本期精英赛
     * @return 是否完整参赛三场并有待领奖励
     */
    private boolean eligible(ObjectNode event) {
        return event.path("finished").asBoolean() && !event.path("claimed").asBoolean() &&
                rank(event,event.path("self").asInt()) <= 2;
    }

    /**
     * 取得本期已配置的奖品。
     * @param tier 达到的奖励档
     * @return 本期名次道具
     */
    private int prize(int tier) {
        return tier == 1 ? 21059 : 21068;
    }

    /**
     * 构造原精英赛协议的选手状态。
     * @param event 本期精英赛
     * @param seat 签表席位
     * @return 供界面显示的实际选手资料
     */
    private ObjectNode profile(ObjectNode event,int seat) {
        ObjectNode profile = event.path("seats").get(seat).path("profile").deepCopy();
        profile.put("rank",rank(event,seat));
        int kills = 0;
        for (JsonNode match : event.path("matches")) {
            if (match.path("left").asInt() == seat) kills += match.path("rightLoss").asInt();
            if (match.path("right").asInt() == seat) kills += match.path("leftLoss").asInt();
        }
        profile.put("kill",kills);
        return profile;
    }

    /**
     * 写入无效操作的协议错误码。
     * @param out 协议应答
     * @return 未修改存档
     */
    private boolean reject(ObjectNode out) {
        out.put("errno",2003);
        return false;
    }
}
