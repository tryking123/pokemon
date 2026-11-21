package com.pokemon.offline.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 独立本机联赛报名、分组、对局与奖励 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-20 09:27
 */
@Service
public class LeagueSeasonService {
    private static final String KEY = "offline-league-v1";
    private static final int[][] ROUNDS = {{0,3,1,2},{0,2,3,1},{0,1,2,3}};
    private final ObjectMapper json;
    private final RoleService roles;
    private final ArenaMapper mapper;
    private final ArenaService arena;
    private final ArenaCombat combat;
    private final BattleMapper formations;
    private final InventoryService inventory;
    private final EliteSeason elite;
    private final LeagueReports reports;

    /**
     * 绑定既有竞技快照、阵法和资产存档。
     * @param json 协议数据编码器
     * @param roles 角色事务锁
     * @param mapper 角色分组存档
     * @param arena 真实上阵精灵读取
     * @param combat 确定性训练战斗
     * @param formations 当前阵法
     * @param inventory 奖品入包及溢出邮件
     */
    public LeagueSeasonService(ObjectMapper json, RoleService roles, ArenaMapper mapper, ArenaService arena,
                               ArenaCombat combat, BattleMapper formations, InventoryService inventory) {
        this.json = json;
        this.roles = roles;
        this.mapper = mapper;
        this.arena = arena;
        this.combat = combat;
        this.formations = formations;
        this.inventory = inventory;
        this.elite = new EliteSeason(this, arena, combat, formations, inventory, roles);
        this.reports = new LeagueReports(mapper,combat,json);
    }

    /**
     * 读取或操作本机联赛；访问时补齐已到期的赛程。
     * @param roleId 已登录角色
     * @param type 原请求编号，零表示登录同步
     * @param stage 请求查看的训练师等阶
     * @param group 原界面的小组序号
     * @param rank 精英奖励名次档
     * @param room 精英竞猜房间编号
     * @param bet 精英竞猜方
     * @return 应答、联赛推送及实际入包奖品
     */
    @Transactional
    public ObjectNode dispatch(String roleId, int type, int stage, int group, int rank, String room, String bet) {
        LocalRole role = roles.lockRole(roleId);
        long today = (Instant.now().getEpochSecond() + 28800) / 86400;
        long anchor = (role.createdAt + 28800) / 86400;
        long week = today - Math.floorMod(today - anchor, 7);
        ObjectNode book = mapper.load(roleId, KEY);
        ObjectNode result = json.createObjectNode();
        ObjectNode response = result.putObject("response");
        response.put("errno", 0);
        ObjectNode season = (ObjectNode) book.get("current");
        boolean changed = elite.advance(book, role, today, result);
        if (season != null) {
            int due = (int) Math.min(7, today - season.path("week").asLong() + 1);
            if (due > season.path("day").asInt()) {
                advance(book, season, due);
                changed = true;
            }
            if (week > season.path("week").asLong()) {
                elite.qualify(book, season, role, today, result);
                if (today - season.path("week").asLong() < 20) {
                    book.withArray("archives").add(season);
                } else {
                    reports.purge(role.id,season);
                }
                book.remove("current");
                season = null;
                changed = true;
            }
        }
        ArrayNode archives = book.withArray("archives");
        for (int i = archives.size() - 1; i >= 0; i--) {
            if (today - archives.get(i).path("week").asLong() >= 20) {
                reports.purge(role.id,archives.get(i));
                archives.remove(i);
                changed = true;
            }
        }
        switch (type) {
            case 234:
                if (role.level < 55) {
                    response.put("errno", 2003);
                    break;
                }
                if (season == null && today == week) {
                    season = register(role, week);
                    if (season != null) {
                        book.set("current", season);
                        changed = true;
                    }
                }
                if (season == null) response.put("errno", today == week ? 253540 : 2003);
                else response.put("stage", season.path("stage").asInt());
                break;
            case 235:
                ArrayNode pending = book.withArray("pending");
                if (pending.isEmpty()) {
                    response.put("errno", 2003);
                    break;
                }
                JsonNode prize = pending.remove(0);
                int item = prize.path("item").asInt();
                Map<Integer,Long> delivered = inventory.grantItemsOrMail(roleId, "league:" + prize.path("week").asLong(),
                        Collections.singletonMap(item, prize.path("count").asLong()));
                result.put("item", item);
                result.put("delivered", delivered.getOrDefault(item, 0L));
                changed = true;
                break;
            case 236: case 237:
                response.put("stage", stage);
                if (stage < 0 || stage > 2 || type == 237 && (group < 1 || group > 2)) {
                    response.put("errno", 100021);
                    break;
                }
                ArrayNode list = response.putArray(type == 236 ? "map" : "list");
                if (type == 237) response.put("group", group);
                if (season == null || stage != season.path("stage").asInt()) break;
                for (int index = 1; index <= 2; index++) {
                    List<Integer> ordered = groupOrder(season, index);
                    if (type == 236) {
                        int seat = season.path("day").asInt() >= 5 ? season.path("matches").get(index + 11).path("winner").asInt() : ordered.get(0);
                        ObjectNode representative = player(season, seat);
                        representative.put("index", index);
                        list.add(representative);
                    } else if (index == group) {
                        for (int seat : ordered) list.add(player(season, seat));
                    }
                }
                if (type == 236) {
                    response.put("mygroup", season.path("self").asInt() / 4 + 1);
                    if (season.has("places")) response.set("winner", player(season, season.path("places").get(0).asInt()));
                }
                break;
            case 239: case 240: case 241: case 242: case 243: case 244: case 245:
                changed |= elite.request(book, role, type, stage, rank, room, bet, today, result);
                break;
            case 238:
                ArrayNode winners = response.putArray("winnerlist");
                for (JsonNode champion : book.path("champions")) winners.add(champion);
                break;
            default:
                break;
        }
        if (changed) mapper.save(roleId, KEY, book);
        if (result.has("wallet")) result.put("coins",roles.findById(roleId).orElse(role).coins);
        ObjectNode panel = result.putObject("panel");
        if (season != null) {
            panel.put("flag", season.path("stage").asInt());
            panel.put("lastupload", season.path("uploaded").asLong());
        }
        for (String key : new String[]{"toprank","topkill","lastrank","lastkill"}) panel.put(key, book.path(key).asInt());
        if (book.path("pending").size() > 0) panel.putArray("reward").add(book.path("pending").get(0).path("rank").asInt());
        return result;
    }

    /**
     * 返回已经结算且仍在保存期内的逐行动战报。
     * @param roleId 当前角色
     * @param type 普通联赛或精英赛回放协议
     * @param stage 所查训练师等阶
     * @param uid1 第一名参赛者
     * @param uid2 第二名参赛者
     * @param idx 两席对阵的场次序号
     * @param page 日志页号
     * @return 战报、阵容和赛季同步数据
     */
    @Transactional
    public ObjectNode replay(String roleId,int type,int stage,String uid1,String uid2,int idx,int page) {
        ObjectNode result = dispatch(roleId,0,-1,-1,-1,"","");
        ObjectNode book = mapper.load(roleId,KEY);
        result.set("response",reports.view(roleId,book,type,stage,uid1,uid2,idx,page));
        return result;
    }

    /** 清理已经过期赛季的大型战报。
     * @param roleId 当前角色
     * @param event 到期的参赛记录
     */
    void purgeReports(String roleId,JsonNode event) {
        reports.purge(roleId,event);
    }

    /**
     * 报名时固定角色和七位训练对手的席位、阵容与随机种子。
     * @param role 报名角色
     * @param week 赛季首日
     * @return 独立赛季快照，无可用阵容时为空
     */
    private ObjectNode register(LocalRole role, long week) {
        ArrayNode members = arena.players(role, ArenaService.ARENA, 0, 1);
        if (members.isEmpty()) return null;
        ObjectNode season = json.createObjectNode();
        int seed = (int)(week * 31 + role.id.hashCode());
        Random random = new Random(seed);
        int self = random.nextInt(8);
        season.put("seed", seed);
        season.put("owner",role.id);
        season.put("week", week);
        season.put("stage", role.stage);
        season.put("uploaded", Instant.now().getEpochSecond());
        season.put("day", 1);
        season.put("self", self);
        season.put("rules", 1);
        season.put("rostersVersion", combat.config.path("sourceVersions").path("rosters").asText());
        season.putArray("matches");
        JsonNode pool = combat.config.path("pools").path("offline_season");
        List<String> templates = new ArrayList<>();
        for (JsonNode ref : pool) templates.add(ref.asText());
        Collections.shuffle(templates, random);
        ArrayNode seats = season.putArray("seats");
        for (int i = 0; i < 8; i++) {
            ObjectNode entry = seats.addObject();
            boolean own = i == self;
            String uid = own ? role.id : "npc:season:" + week + ":" + i;
            ObjectNode team;
            if (own) {
                team = combat.formation(role.id, formations.formationId(role.id), false, members);
            } else {
                String roster = templates.get(i);
                entry.put("template", roster);
                team = combat.opposition(roster, uid, members, i % 3, members.get(0).path("level").asInt(1));
            }
            combat.addTrainingFallback(team);
            entry.set("team", team);
            ObjectNode profile = entry.putObject("profile");
            profile.put("name", own ? role.name : "[本机NPC] 联赛训练师" + (i + 1));
            profile.put("uid", uid);
            profile.put("svrid", role.realmId);
            profile.put("sex", own ? role.sex : 0);
            profile.put("icon", own ? role.avatar : 1);
            profile.put("level", role.level);
            profile.put("stage", role.stage);
            profile.put("dress", 0);
            profile.put("title", 0);
            profile.put("vip", 0);
            profile.put("power", team.path("power").asLong());
            profile.put("index", i + 1);
        }
        // 将当前规则计算的种子赛程封存；只有到期的比赛才进入成绩、资格和资产结算。
        ObjectNode forecast = season.deepCopy();
        advance(json.createObjectNode(), forecast, 6);
        season.set("schedule", forecast.path("matches"));
        return season;
    }

    /**
     * 顺序完成已到期的组内赛、交叉半决赛、决赛及结算。
     * @param book 本角色联赛记录
     * @param season 已冻结的本期参赛表
     * @param due 已到达的赛季日
     */
    private void advance(ObjectNode book, ObjectNode season, int due) {
        for (int day = season.path("day").asInt() + 1; day <= due; day++) {
            if (day <= 4) {
                int[] pairs = ROUNDS[day - 2];
                for (int offset : new int[]{0,4}) {
                    play(season, pairs[0]+offset, pairs[1]+offset);
                    play(season, pairs[2]+offset, pairs[3]+offset);
                }
            } else if (day == 5) {
                List<Integer> first = groupOrder(season, 1);
                List<Integer> second = groupOrder(season, 2);
                play(season, first.get(0), second.get(1));
                play(season, second.get(0), first.get(1));
            } else if (day == 6) {
                JsonNode matches = season.path("matches");
                int first = matches.get(12).path("winner").asInt();
                int second = matches.get(13).path("winner").asInt();
                play(season, first, second);
                JsonNode finalMatch = matches.get(14);
                List<Integer> others = new ArrayList<>(Arrays.asList(0,1,2,3,4,5,6,7));
                ArrayNode places = season.putArray("places");
                int champion = finalMatch.path("winner").asInt();
                places.add(champion).add(champion == first ? second : first);
                others.remove(Integer.valueOf(first));
                others.remove(Integer.valueOf(second));
                others.sort((a,b) -> Integer.compare(score(season,b,"played"),score(season,a,"played")) != 0
                        ? Integer.compare(score(season,b,"played"),score(season,a,"played")) : compareRecord(season,a,b));
                for (int seat : others) places.add(seat);
            } else {
                int self = season.path("self").asInt();
                ObjectNode profile = player(season, self);
                int rank = place(season, self);
                int tier = profile.path("rank").asInt();
                int kills = profile.path("kill").asInt();
                book.put("lastrank",tier);
                book.put("lastkill",kills);
                book.put("toprank",Math.min(book.path("toprank").asInt(9),tier));
                book.put("topkill",Math.max(book.path("topkill").asInt(),kills));
                book.with("champions").set(Integer.toString(season.path("stage").asInt()),player(season,season.path("places").get(0).asInt()));
                if (score(season,self,"played") >= 3) {
                    ObjectNode prize = book.withArray("pending").addObject();
                    prize.put("week",season.path("week").asLong());
                    prize.put("rank",rank);
                    prize.put("item",rank == 1 ? 21059 : rank <= 4 ? 21068 : 21063);
                    prize.put("count",rank <= 4 ? 1 : 2);
                }
            }
            season.put("day",day);
        }
    }

    /**
     * 以报名时冻结的双方快照和固定种子运行整场对局。
     * @param season 本期记录
     * @param left 左席位
     * @param right 右席位
     */
    void play(ObjectNode season, int left, int right) {
        ArrayNode matches = season.withArray("matches");
        if (season.has("schedule")) {
            JsonNode fixed = season.path("schedule").get(matches.size());
            if (fixed == null || fixed.path("left").asInt() != left || fixed.path("right").asInt() != right) {
                throw new IllegalStateException("本期联赛对阵与封存的席位不一致");
            }
            matches.add(fixed);
            return;
        }
        JsonNode seats = season.path("seats");
        ObjectNode battle = json.createObjectNode();
        int seed = (season.path("seed").asInt() * 31 + matches.size() * 127) & Integer.MAX_VALUE;
        battle.put("seed",seed);
        battle.put("league",true);
        battle.put("arenaDamageVersion",2);
        battle.set("player",seats.get(left).path("team").deepCopy());
        battle.set("monster",seats.get(right).path("team").deepCopy());
        if (season.has("buff")) {
            EliteSeason.applyBuff(battle, season.path("buff").asInt());
        }
        int outcome;
        int actions = 0;
        do {
            outcome = combat.seasonTurn(battle);
            if (++actions > 864 || outcome < 0) throw new IllegalStateException("联赛阵容行动无效");
        } while (outcome == 0);
        ObjectNode match = matches.addObject();
        match.put("left",left);
        match.put("right",right);
        match.put("seed",seed);
        match.put("actions",actions);
        reports.save(season,match,battle.path("report"),matches.size()-1);
        match.put("winner",outcome == 1 ? left : right);
        if (season.has("buff")) match.put("buff",season.path("buff").asInt());
        for (String side : new String[]{"player","monster"}) {
            int lost = 0;
            for (JsonNode member : battle.path(side).path("pokemon")) if (member.path("hp").asInt() <= 0) lost++;
            match.put(side.equals("player") ? "leftLoss" : "rightLoss",lost);
        }
    }

    /**
     * 统计席位实际参赛、胜场或击倒数据。
     * @param season 本期记录
     * @param seat 席位号
     * @param stat 统计字段
     * @return 本期已完成的对应数量
     */
    private int score(ObjectNode season, int seat, String stat) {
        int sum = 0;
        for (JsonNode match : season.path("matches")) {
            boolean left = match.path("left").asInt() == seat;
            if (!left && match.path("right").asInt() != seat) continue;
            int kills = match.path(left ? "rightLoss" : "leftLoss").asInt();
            switch (stat) {
                case "wins": if (match.path("winner").asInt() == seat) sum++; break;
                case "kill": sum += kills; break;
                case "delta": sum += kills - match.path(left ? "leftLoss" : "rightLoss").asInt(); break;
                default: sum++;
            }
        }
        return sum;
    }

    /**
     * 按各席位既有战绩排列淘汰后的名次。
     * @param season 本期记录
     * @param a 首席
     * @param b 次席
     * @return 排序比较值
     */
    private int compareRecord(ObjectNode season, int a, int b) {
        int byWins = Integer.compare(score(season,b,"wins"),score(season,a,"wins"));
        if (byWins != 0) return byWins;
        int delta = Integer.compare(score(season,b,"delta"),score(season,a,"delta"));
        return delta != 0 ? delta : Integer.compare(a,b);
    }

    /**
     * 比较小组胜场、同分小循环、击倒差及席位号。
     * @param season 本期记录
     * @param group 小组序号
     * @return 本组已排序的四个席位
     */
    private List<Integer> groupOrder(ObjectNode season, int group) {
        int offset = (group - 1) * 4;
        List<Integer> seats = new ArrayList<>(Arrays.asList(offset,offset+1,offset+2,offset+3));
        int[] wins = new int[8], mutual = new int[8], delta = new int[8];
        JsonNode matches = season.path("matches");
        for (int i = 0; i < Math.min(matches.size(),12); i++) {
            JsonNode match = matches.get(i);
            wins[match.path("winner").asInt()]++;
            int diff = match.path("rightLoss").asInt() - match.path("leftLoss").asInt();
            delta[match.path("left").asInt()] += diff;
            delta[match.path("right").asInt()] -= diff;
        }
        for (int i = 0; i < Math.min(matches.size(),12); i++) {
            JsonNode match = matches.get(i);
            if (wins[match.path("left").asInt()] == wins[match.path("right").asInt()]) mutual[match.path("winner").asInt()]++;
        }
        seats.sort((a,b) -> {
            if (wins[a] != wins[b]) return Integer.compare(wins[b],wins[a]);
            if (mutual[a] != mutual[b]) return Integer.compare(mutual[b],mutual[a]);
            if (delta[a] != delta[b]) return Integer.compare(delta[b],delta[a]);
            return Integer.compare(a,b);
        });
        return seats;
    }

    /**
     * 返回原协议可显示的席位、排名和有效击倒数。
     * @param season 本期记录
     * @param seat 席位号
     * @return 参赛选手资料
     */
    private ObjectNode player(ObjectNode season, int seat) {
        ObjectNode view = season.path("seats").get(seat).path("profile").deepCopy();
        int rank = place(season, seat);
        view.put("rank", rank <= 2 ? rank : rank <= 4 ? 3 : 4);
        view.put("kill",score(season,seat,"kill"));
        return view;
    }

    /**
     * 获取用于发奖的精确名次。
     * @param season 本期记录
     * @param seat 席位号
     * @return 最终名次，决赛前为零
     */
    private int place(ObjectNode season, int seat) {
        int rank = 0;
        if (season.has("places")) {
            for (JsonNode entry : season.path("places")) {
                rank++;
                if (entry.asInt() == seat) break;
            }
        }
        return rank;
    }
}
