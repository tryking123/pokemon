package com.pokemon.offline.arena;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.battle.FixedStageCombatService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.RoleBattlePowerService;
import com.pokemon.offline.role.RoleCosmeticService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;

import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 地下城周赛的关卡战斗、寻宝值、委托、榜单和结算服务。
 *
 * @author tongruiyuan
 * @since 2026-11-20 14:49
 */
@Service
public class DnfService {
    private static final long MONDAY = 1794758400L, DAY = 86400L, WEEK = 7 * DAY;
    private static final String STATE = "dnf:season";
    private final ArenaMapper store;
    private final RoleService roles;
    private final FixedStageCombatService combat;
    private final DnfTasks tasks;
    private final DnfRewards rewards;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode cfg;
    private final WorldBossService boss;
    private final BountyService bounty;
    private final MineService mine;
    private final FeastService feast;
    private final RoleCosmeticService cosmetics;
    private final RoleBattlePowerService power;
    private final GamePaymentService payments;

    /**
     * 加载赛季配置并连接共享的战斗与角色事务。
     *
     * @param store 角色玩法存档
     * @param roles 角色服务
     * @param combat 本地回合引擎
     * @param tasks 委托规则
     * @param rewards 奖励结算
     * @param jdbc 同服成绩读取
     * @param json 资源解析
     * @param boss 世界首领占用
     * @param bounty 悬赏占用
     * @param mine 矿洞占用
     * @param feast 限时主城怪物占用
     * @param cosmetics 榜单时装和称号
     * @param power 历史战力
     * @param payments 角色 VIP 等级
     * @throws IOException 底包配置缺失
     */
    DnfService(
            ArenaMapper store,
            RoleService roles,
            FixedStageCombatService combat,
            DnfTasks tasks,
            DnfRewards rewards,
            JdbcTemplate jdbc,
            ObjectMapper json,
            WorldBossService boss,
            BountyService bounty,
            MineService mine,
            FeastService feast,
            RoleCosmeticService cosmetics,
            RoleBattlePowerService power,
            GamePaymentService payments)
            throws IOException {
        this.store = store;
        this.roles = roles;
        this.combat = combat;
        this.tasks = tasks;
        this.rewards = rewards;
        this.jdbc = jdbc;
        this.json = json;
        this.boss = boss;
        this.bounty = bounty;
        this.mine = mine;
        this.feast = feast;
        this.cosmetics = cosmetics;
        this.power = power;
        this.payments = payments;
        try (InputStream in = new ClassPathResource("activity/dnf.json").getInputStream()) {
            cfg = json.readTree(in);
        }
    }

    /**
     * 核验角色等级与开服时长。
     *
     * @param role 本地角色
     * @return 是否解锁
     */
    public static boolean open(LocalRole role) {
        long now = Instant.now().getEpochSecond();
        return role.level >= 80 && now - role.createdAt >= 3 * DAY;
    }

    /**
     * 返回当前周一起点。
     *
     * @return 北京时间周一零点秒数
     */
    public static long week() {
        return MONDAY + Math.floorDiv(Instant.now().getEpochSecond() - MONDAY, WEEK) * WEEK;
    }

    /**
     * 读取、按周初始化、结算已过去时段的寻宝值。
     *
     * @param id 角色编号
     * @param week 当前周起点
     * @param now 当前秒数
     * @return 待持久化状态
     */
    private ObjectNode state(String id, long week, long now) {
        ObjectNode s = store.load(id, STATE);
        if (s.path("week").asLong() != week) {
            s =
                    json.createObjectNode()
                            .put("week", week)
                            .put("season", 1 + Math.floorMod((int) ((week - MONDAY) / WEEK), 9))
                            .put("mission", 1)
                            .put("last", now)
                            .put("score", 0)
                            .put("lotto", 0)
                            .put("flag", 0)
                            .put("newbie", 0);
            tasks.generate(cfg, floor(s), s);
        }
        long day = (now + 28800) / DAY;
        if (s.path("day").asLong() != day) s.put("day", day).put("times", 0).put("refreshtimes", 0);
        JsonNode floor = floor(s);
        long end = Math.min(now, week + 6 * DAY),
                last = s.path("last").asLong(),
                period = floor.path("speed").asInt();
        long ticks = Math.max(0, end - last) / period;
        if (ticks > 0) {
            s.put("last", last + ticks * period);
            int score = s.path("score").asInt();
            s.put(
                    "score",
                    Math.max(
                            score,
                            Math.min(
                                    floor.path("expmax").asInt(),
                                    score + ticks * floor.path("exp").asInt())));
        }
        if (s.path("fight").path("expiry").asLong() <= now || now >= week + 6 * DAY)
            s.remove("fight");
        return s;
    }

    /**
     * 读取当前层底表。
     *
     * @param s 赛季状态
     * @return 关卡行
     */
    private JsonNode floor(ObjectNode s) {
        return cfg.path("point").path(s.path("mission").asText());
    }

    /**
     * 原传送入口使用的当前关卡。
     *
     * @param id 角色编号
     * @return 当前层编号
     */
    public int mission(String id) {
        ObjectNode s = store.load(id, STATE);
        return s.path("week").asLong() == week() ? s.path("mission").asInt(1) : 1;
    }

    /**
     * 读取供原客户端初始化的全量状态。
     *
     * @param id 角色编号
     * @return S453 原字段
     */
    @Transactional
    public ObjectNode panel(String id) {
        roles.lockRole(id);
        ObjectNode s = state(id, week(), Instant.now().getEpochSecond());
        store.save(id, STATE, s);
        return s.deepCopy()
                .retain(
                        "season",
                        "mission",
                        "times",
                        "lotto",
                        "flag",
                        "score",
                        "tasklist",
                        "refreshtimes",
                        "newbie");
    }

    /**
     * 检查回合所属玩法。
     *
     * @param id 角色编号
     * @return 是否仍有地下城会话
     */
    public boolean active(String id) {
        ObjectNode s = store.load(id, STATE);
        long now = Instant.now().getEpochSecond();
        return s.path("fight").path("expiry").asLong() > now
                && s.path("week").asLong() == week()
                && now < week() + 6 * DAY;
    }

    /**
     * 按需读取固定阵容，避免启动时在手机内存中驻留全部一万八千只怪。
     *
     * @param gid 原怪物组编号
     * @return 符合回合引擎格式的单组阵容
     */
    private JsonNode roster(int gid) {
        try (InputStream in = new ClassPathResource("battle/dnf-rosters.json").getInputStream();
                JsonParser p = json.getFactory().createParser(in)) {
            p.nextToken();
            p.nextToken();
            p.nextToken();
            while (p.nextToken() == JsonToken.FIELD_NAME) {
                boolean match = String.valueOf(gid).equals(p.currentName());
                p.nextToken();
                if (match) return json.readTree(p);
                p.skipChildren();
            }
        } catch (IOException e) {
            throw new IllegalStateException("地下城阵容读取失败", e);
        }
        throw new IllegalStateException("地下城怪物组缺失 " + gid);
    }

    /**
     * 读取本区本赛季实际成绩，按层数、到达时间和角色编号排序。
     *
     * @param role 当前角色
     * @param week 赛季
     * @return 已达门槛的成绩
     */
    private ArrayNode ranking(LocalRole role, long week) {
        String key = "dnf:rank:" + week;
        List<ObjectNode> rows =
                jdbc.query(
                        "SELECT r.id,r.name,s.state_data FROM local_game_role r JOIN"
                            + " local_arena_state s ON r.id=s.role_id WHERE r.realm_id=? AND"
                            + " s.state_key=?",
                        (rs, n) -> {
                            try {
                                ObjectNode row = (ObjectNode) json.readTree(rs.getString(3));
                                return row.put("uid", rs.getString(1))
                                        .put("name", rs.getString(2))
                                        .put("svrid", role.realmId);
                            } catch (IOException e) {
                                throw new IllegalStateException("地下城成绩读取失败", e);
                            }
                        },
                        role.realmId,
                        key);
        rows.sort(
                Comparator.comparingInt((ObjectNode row) -> row.path("layer").asInt())
                        .reversed()
                        .thenComparingLong(row -> row.path("time").asLong())
                        .thenComparing(row -> row.path("uid").asText()));
        ArrayNode result = json.createArrayNode();
        rows.forEach(result::add);
        return result;
    }

    /**
     * 根据原属性编号将当季加成加入本场参战精灵快照。
     *
     * @param s 赛季存档
     * @param fight 本次战斗
     */
    private void buffs(ObjectNode s, ObjectNode fight) {
        String[] names = {
            "physical_attack", "special_attack", "physical_defense", "special_defense"
        };
        for (JsonNode node : fight.path("player").path("pokemon")) {
            ObjectNode actor = (ObjectNode) node;
            for (int i = 0; i < names.length; i++)
                actor.put(
                        names[i],
                        actor.path(names[i]).asLong()
                                + s.path("buffs").path(String.valueOf(i + 6)).asLong());
            actor.put("speed", actor.path("speed").asLong() + s.path("buffs").path("14").asLong());
            long hp = s.path("buffs").path("5").asLong();
            actor.put("hp_max", actor.path("hp_max").asLong() + hp)
                    .put("hp", actor.path("hp").asLong() + hp);
        }
    }

    /**
     * 处理赛季请求，在同一事务内提交资格、消耗、资产和回执。
     *
     * @param id 角色编号
     * @param cmd 客户端动作
     * @param input 原字段
     * @param preview 客户端最后见到的赛季
     * @param receipt 连接和请求号
     * @return 协议与另行推送的奖励
     */
    @Transactional
    public ObjectNode act(String id, int cmd, JsonNode input, long preview, String receipt) {
        LocalRole role = roles.lockRole(id);
        long week = week(), now = Instant.now().getEpochSecond();
        ObjectNode out = json.createObjectNode().put("errno", 1);
        if (!open(role) || preview != week) return out;
        ObjectNode s = state(id, week, now);
        JsonNode base = cfg.path("base").path(s.path("season").asText());
        boolean settle = now >= week + 6 * DAY;
        String key = "dnf:" + week + ":" + cmd + ":" + receipt;
        if (s.path("seen").has(key)) return out;
        if (cmd == 293 || cmd == 295 || cmd == 296) {
            ArrayNode ranks = ranking(role, week);
            int rank = 0;
            for (int i = 0; i < ranks.size(); i++)
                if (id.equals(ranks.get(i).path("uid").asText())) rank = i + 1;
            int beyond = rank == 0 ? 0 : (ranks.size() - rank + 1) * 10000 / ranks.size();
            if (cmd == 293) {
                out.put("errno", settle && s.path("flag").asInt() == 0 ? 138 : 0)
                        .put("status", settle ? 2 : 1)
                        .put("beyond", beyond);
                if (rank > 0) out.put("rank", rank);
            } else if (cmd == 296) out.put("errno", 0).set("ranklist", ranks);
            else if (settle && rank > 0 && s.path("flag").asInt() == 0) {
                ArrayNode grants = json.createArrayNode();
                if (rank <= base.path("reward").size())
                    grants.addAll((ArrayNode) base.path("reward").get(rank - 1));
                else
                    for (JsonNode tier : base.path("reward1"))
                        if (beyond >= tier.get(0).asInt()) {
                            for (int i = 1; i < tier.size(); i++) grants.add(tier.get(i));
                            break;
                        }
                out.put("errno", 0).set("adds", rewards.grant(id, s, grants, "dnf:season:" + week));
                s.put("flag", 1);
            }
        } else if (cmd == 302) {
            ArrayNode list = out.putArray("buff");
            s.path("buffs")
                    .fields()
                    .forEachRemaining(
                            e -> {
                                list.add(Integer.parseInt(e.getKey()));
                                list.add(e.getValue().asLong());
                            });
            out.putArray("feature");
            out.put("errno", 0);
        } else if ((!settle || cmd == 297 || cmd == 298 || cmd == 301) && cmd != 294) {
            if (cmd == 297 || cmd == 298) out = rewards.lottery(id, s, base, cmd == 298, key);
            else out = tasks.act(id, cmd, input, cfg, floor(s), s, key, now);
        } else if (!settle
                && cmd == 294
                && !s.path("completed").asBoolean()
                && role.sceneId == floor(s).path("scene").get(0).asInt()) {
            if (s.has("fight")) return (ObjectNode) s.get("fight").deepCopy();
            if (s.path("times").asInt() >= 10
                    || boss.fightOccupied(id)
                    || boss.active(id)
                    || feast.active(id)
                    || bounty.active(id)
                    || mine.active(id)) return out;
            JsonNode groups = floor(s).path("monster");
            int group = groups.get(ThreadLocalRandom.current().nextInt(groups.size())).asInt();
            out = combat.start(role, s.path("mission").asInt(), roster(group));
            if (out.path("errno").asInt() == 0) {
                buffs(s, out);
                s.set("fight", out.deepCopy());
                s.put("times", s.path("times").asInt() + 1);
            }
        }
        if (out.path("errno").asInt() == 0 && cmd != 293 && cmd != 296 && cmd != 297 && cmd != 302)
            s.with("seen").put(key, true);
        store.save(id, STATE, s);
        return out;
    }

    /**
     * 以存档快照推进回合；胜利时一次提交实际成绩和关卡奖励。
     *
     * @param id 角色编号
     * @param skill 当前技能
     * @param targets 本次目标
     * @param flag 操作类型
     * @param retreat 是否撤退
     * @return 终局时的 S454 和奖励增量
     */
    @Transactional
    public ObjectNode advance(String id, int skill, List<?> targets, int flag, boolean retreat) {
        roles.lockRole(id);
        long week = week(), now = Instant.now().getEpochSecond();
        ObjectNode s = state(id, week, now), out = json.createObjectNode();
        if (!s.has("fight")) return out;
        int outcome = combat.advance((ObjectNode) s.get("fight"), skill, targets, flag, retreat);
        if (outcome < 0) return out;
        if (outcome > 0) {
            int mission = s.path("mission").asInt();
            if (outcome == 1) {
                JsonNode stage = floor(s);
                out.set(
                        "adds",
                        rewards.grant(
                                id, s, stage.path("award"), "dnf:clear:" + week + ":" + mission));
                if (mission >= cfg.path("base").path(s.path("season").asText()).path("low").asInt())
                    store.save(
                            id,
                            "dnf:rank:" + week,
                            json.createObjectNode()
                                    .put("layer", mission)
                                    .put("time", now)
                                    .put("power", power.score(id))
                                    .put("vip", payments.vip(id).level)
                                    .put("title", cosmetics.displayedTitle(id))
                                    .put("dress", cosmetics.wornDress(id)));
                s.put("mission", Math.min(1000, mission + 1))
                        .put("completed", mission == 1000)
                        .put("last", now)
                        .put("lotto", s.path("lotto").asInt() + stage.path("luckynum").asInt());
            }
            s.remove("fight");
            out.put("errno", 0)
                    .put("result", outcome == 1)
                    .put("times", s.path("times").asInt())
                    .put("lotto", s.path("lotto").asInt());
        }
        store.save(id, STATE, s);
        return out;
    }
}
