package com.pokemon.offline.dailydup;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.ArenaService;
import com.pokemon.offline.arena.ZodiacService;
import com.pokemon.offline.battle.BattleMapper;
import com.pokemon.offline.battle.FixedStageCombatService;
import com.pokemon.offline.battle.api.entity.BattleAttempt;
import com.pokemon.offline.dojo.DojoCombatService;
import com.pokemon.offline.eden.EdenEncounterService;
import com.pokemon.offline.guild.GuildEncounterService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.RoleBattlePowerService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 每日副本原关卡战斗及独立扫荡次数服务。
 * @author tongruiyuan
 * @since 2026-11-19 19:02
 */
@Service
public class DailyDungeonService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final JsonNode config;
    private final RoleService roles;
    private final RoleBattlePowerService power;
    private final FixedStageCombatService fixed;
    private final InventoryService inventory;
    private final GamePaymentService payments;
    private final BattleMapper battles;
    private final ArenaService arena;
    private final ZodiacService zodiac;
    private final EdenEncounterService eden;
    private final GuildEncounterService guild;
    private final DojoCombatService dojo;

    /** 加载底包五类副本、阵容及奖励倍率。
     * @param jdbc 本地存档数据库
     * @param json 资源解码器
     * @param roles 角色与钱包
     * @param power 第一队当前战力
     * @param fixed 固定副本战斗
     * @param inventory 道具和溢出邮件
     * @param payments 真实VIP
     * @param battles 普通副本战斗和阵法
     * @param arena 竞技占用
     * @param zodiac 十二宫占用
     * @param eden 神兽岛占用
     * @param guild 公会战斗占用
     * @param dojo 道馆战斗占用
     * @throws IOException 无法读取原关卡
     */
    public DailyDungeonService(JdbcTemplate jdbc, ObjectMapper json, RoleService roles, RoleBattlePowerService power,
            FixedStageCombatService fixed, InventoryService inventory, GamePaymentService payments,
            BattleMapper battles, ArenaService arena, ZodiacService zodiac, EdenEncounterService eden,
            GuildEncounterService guild, DojoCombatService dojo) throws IOException {
        this.jdbc = jdbc;
        this.json = json;
        this.roles = roles;
        this.power = power;
        this.fixed = fixed;
        this.inventory = inventory;
        this.payments = payments;
        this.battles = battles;
        this.arena = arena;
        this.zodiac = zodiac;
        this.eden = eden;
        this.guild = guild;
        this.dojo = dojo;
        try (InputStream in = new ClassPathResource("dailydup/stages.json").getInputStream()) {
            config = json.readTree(in);
        }
    }

    /** 建立副本存档与结算回执。 */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("dailydup/schema.sql")).execute(jdbc.getDataSource());
    }

    /** 查询与固定关卡互斥的普通副本、竞技及其他玩法战斗。
     * @param id 角色编号
     * @return 是否存在其他进行中的战斗
     */
    public boolean otherFightActive(String id) {
        BattleAttempt current = battles.findAttempt(id);
        return arena.active(id) || zodiac.active(id) || eden.active(id) || guild.active(id) || dojo.active(id)
                || current != null && "ACTIVE".equals(current.status)
                        && Instant.now().getEpochSecond() - current.startedAt < 1200;
    }

    /** 查询存档。
     * @param sql 查询语句
     * @param args 参数
     * @return 已存对象或空对象
     */
    private ObjectNode read(String sql, Object... args) {
        List<String> rows = jdbc.queryForList(sql, String.class, args);
        if (rows.isEmpty())
            return json.createObjectNode();
        try {
            return (ObjectNode) json.readTree(rows.get(0));
        } catch (IOException e) {
            throw new IllegalStateException("每日副本存档无法解析", e);
        }
    }

    /**
     * 保存实际观察到的金币挑战资格，并列出前三个副本日可找回的场次。
     * @param id 角色编号
     * @return 原奖励找回面板以及当前最早一笔资格对应的副本日
     */
    @Transactional
    public ObjectNode recovery(String id) {
        LocalRole role = roles.lockRole(id);
        long day = (Instant.now().getEpochSecond() + 14400) / 86400;
        List<Integer> cleared = jdbc.queryForList("SELECT DISTINCT stage_id FROM local_dailydup_completion "
                + "WHERE role_id=? AND stage_id BETWEEN 10001 AND 10009 AND swept=0 ORDER BY stage_id DESC",
                Integer.class, id);
        if (role.level >= 23 && !cleared.isEmpty()) {
            long score = power.score(id);
            for (int stage : cleared) {
                JsonNode cfg = config.path("stages").path(String.valueOf(stage));
                if (score < cfg.path("power").asLong()) continue;
                long amount = amount(cfg, payments.vip(id).level);
                List<Long> previous = jdbc.queryForList("SELECT coins FROM local_dailydup_recovery "
                        + "WHERE role_id=? AND play_day=?", Long.class, id, day);
                if (previous.isEmpty()) {
                    jdbc.update("INSERT INTO local_dailydup_recovery(role_id,play_day,coins) VALUES(?,?,?)",
                            id, day, amount);
                } else if (amount > previous.get(0)) {
                    jdbc.update("UPDATE local_dailydup_recovery SET coins=? WHERE role_id=? AND play_day=? AND claimed=0",
                            amount, id, day);
                }
                break;
            }
        }
        return recoveryPanel(id, day);
    }

    /**
     * 找回仅覆盖已观察且未完成的正式金币挑战；扫荡使用另一项额度。
     * @param id 角色编号
     * @param day 当前副本日
     * @return 可领取的最早记录、满额奖励及剩余次数
     */
    private ObjectNode recoveryPanel(String id, long day) {
        List<long[]> rows = jdbc.query("SELECT r.play_day,r.coins FROM local_dailydup_recovery r "
                + "WHERE r.role_id=? AND r.play_day>=? AND r.play_day<? AND r.claimed=0 AND NOT EXISTS "
                + "(SELECT 1 FROM local_dailydup_completion c WHERE c.role_id=r.role_id AND c.swept=0 "
                + "AND c.stage_id BETWEEN 10001 AND 10009 AND c.completed_at>=r.play_day*86400-14400 "
                + "AND c.completed_at<(r.play_day+1)*86400-14400) ORDER BY r.play_day",
                (rs, index) -> new long[]{rs.getLong(1),rs.getLong(2)}, id, day-3, day);
        ObjectNode out = json.createObjectNode();
        ArrayNode info = out.putArray("info");
        if (!rows.isEmpty()) {
            out.put("day", rows.get(0)[0]);
            info.addObject().put("id",1100).put("num", rows.size()).putObject("reward").put("coins",rows.get(0)[1]);
        }
        return out;
    }

    /**
     * 领取面板上对应的一笔历史金币挑战，消费、奖励和回执同时提交。
     * @param id 角色编号
     * @param choice 原面板按钮，1 为金币、2 为钻石
     * @param shown 客户端当前展示的副本日
     * @param receipt 本次连接与请求编号
     * @return 本次实际发放的金币，成功重放为零，负数表示失败
     */
    @Transactional
    public long recover(String id, long choice, long shown, String receipt) {
        roles.lockRole(id);
        if (choice != 1 && choice != 2) return -1;
        if (!jdbc.queryForList("SELECT play_day FROM local_dailydup_recovery WHERE role_id=? AND receipt=?",
                Long.class, id, receipt).isEmpty()) return 0;
        long day = (Instant.now().getEpochSecond() + 14400) / 86400;
        ObjectNode panel = recoveryPanel(id, day);
        if (!panel.has("day") || panel.get("day").asLong() != shown) return -1;
        long full = panel.path("info").get(0).path("reward").path("coins").asLong();
        long fee = choice == 1 ? full / 10 : 0;
        if (!inventory.spendMaterials(id, java.util.Collections.emptyMap(), fee, choice == 2 ? 4000 : 0)) return -1;
        long granted = choice == 1 ? full / 2 : full;
        roles.addCurrency(id, granted, 0, 0);
        jdbc.update("UPDATE local_dailydup_recovery SET claimed=?,receipt=? WHERE role_id=? AND play_day=?",
                choice, receipt, id, shown);
        return granted;
    }

    /**
     * 按原关卡和当前 VIP 计算一场副本的真实收益。
     * @param cfg 关卡配置
     * @param vip VIP 等级
     * @return 一场全额奖励
     */
    private long amount(JsonNode cfg, int vip) {
        return cfg.path("reward").get(2).asLong() * config.path("vip").get(Math.min(17, vip)).asLong() / 100;
    }

    /** 读取角色状态，按北京时间四点清理两套当日计数。
     * @param id 角色编号
     * @return 进度和当前对局
     */
    private ObjectNode state(String id) {
        ObjectNode s = read("SELECT state_json FROM local_dailydup_state WHERE role_id=?", id);
        long today = Instant.now().atOffset(ZoneOffset.ofHours(8)).minusHours(4).toLocalDate().toEpochDay();
        if (s.path("day").asLong(-1) != today) {
            s.put("day", today);
            s.putObject("challenge");
            s.putObject("sweep");
        }
        if (s.has("fight") && s.path("fight").path("expiry").asLong() <= Instant.now().getEpochSecond())
            s.remove("fight");
        return s;
    }

    /** 提交副本状态。
     * @param id 角色编号
     * @param s 进度
     */
    private void save(String id, ObjectNode s) {
        jdbc.update("MERGE INTO local_dailydup_state(role_id,state_json) KEY(role_id) VALUES(?,?)", id, s.toString());
    }

    /** 读取角色当前独占的每日副本战斗。
     * @param id 角色编号
     * @return 当前可继续战斗
     */
    public boolean active(String id) {
        return state(id).has("fight");
    }

    /** 读取原界面挑战已用次数和永久通关列表。
     * @param id 角色编号
     * @return S415 协议体
     */
    public ObjectNode snapshot(String id) {
        ObjectNode s = state(id), out = json.createObjectNode();
        ArrayNode times = out.putArray("timelist");
        for (int i = 1; i <= 5; i++)
            times.addObject().put("itype", i).put("times", s.path("challenge").path(String.valueOf(i)).asInt());
        for (int i = 1; i <= 5; i++)
            times.addObject().put("itype", 5 + i).put("times", s.path("sweep").path(String.valueOf(i)).asInt());
        ArrayNode passed = out.putArray("passlist");
        s.path("passed").fieldNames().forEachRemaining(key -> passed.add(Integer.parseInt(key)));
        return out;
    }

    /** 按原等级、当前战力、实际VIP及永久通关状态开始挑战或扫荡。
     * @param id 角色编号
     * @param stage 原副本编号
     * @param sweep 是否扫荡
     * @param receipt 当前请求编号
     * @return 原开场或扫荡结果及奖励
     */
    @Transactional
    public ObjectNode enter(String id, int stage, boolean sweep, String receipt) {
        LocalRole role = roles.lockRole(id);
        ObjectNode s = state(id);
        if (sweep) {
            ObjectNode old = read("SELECT reply_json FROM local_dailydup_receipt WHERE role_id=? AND receipt=?", id,
                    receipt);
            if (old.has("errno")) {
                old.put("replayed", true);
                return old;
            }
        }
        JsonNode cfg = config.path("stages").path(String.valueOf(stage));
        ObjectNode out = json.createObjectNode().put("errno", 1);
        if (!cfg.isObject() || role.level < 23)
            return out;
        String type = cfg.path("type").asText();
        if (s.has("fight")) {
            ObjectNode pending = (ObjectNode) s.get("fight");
            return !sweep && pending.path("stage").asInt() == stage ? pending.deepCopy() : out;
        }
        if (otherFightActive(id))
            return out;
        if (sweep) {
            int vip = payments.vip(id).level;
            if (vip < 3 || !s.path("passed").has(String.valueOf(stage)) || s.path("sweep").path(type).asInt() >= 1)
                return out;
            out.put("errno", 0).set("adds", reward(id, cfg, vip, receipt));
            recordCompletion(id, receipt, stage, true);
            s.with("sweep").put(type, 1);
            jdbc.update("INSERT INTO local_dailydup_receipt(role_id,receipt,reply_json) VALUES(?,?,?)", id, receipt,
                    out.toString());
        } else {
            if (s.path("challenge").path(type).asInt() >= 1 || power.score(id) < cfg.path("power").asLong())
                return out;
            out = fixed.start(role, stage, cfg);
            if (out.path("errno").asInt() == 0)
                s.set("fight", out);
        }
        save(id, s);
        return out;
    }

    /** 执行原客户端战斗动作，胜利时扣该类型挑战次数并发放奖励。
     * @param id 角色编号
     * @param skill 实际技能
     * @param targets 实际攻击目标
     * @param flag 原动作标记
     * @param retreat 撤退标记
     * @return 回合结果与胜利奖励
     */
    @Transactional
    public ObjectNode advance(String id, int skill, List<?> targets, int flag, boolean retreat) {
        roles.lockRole(id);
        ObjectNode s = state(id), result = json.createObjectNode().put("outcome", -1);
        if (!s.has("fight"))
            return result;
        ObjectNode fight = (ObjectNode) s.get("fight");
        int outcome = fixed.advance(fight, skill, targets, flag, retreat);
        result.put("outcome", outcome);
        if (outcome == 1) {
            int stage = fight.path("stage").asInt();
            JsonNode cfg = config.path("stages").path(String.valueOf(stage));
            s.with("passed").put(String.valueOf(stage), true);
            s.with("challenge").put(cfg.path("type").asText(), 1);
            String receipt = "dailydup:" + fight.path("monster").path("identifer").asText();
            result.set("adds", reward(id, cfg, payments.vip(id).level, receipt));
            recordCompletion(id, receipt, stage, false);
            result.put("stage", stage);
        }
        if (outcome > 0)
            s.remove("fight");
        save(id, s);
        return result;
    }

    /** 按实际结算时刻写入唯一记录，供限时任务按自己的自然日窗口读取。
     * @param roleId 角色编号
     * @param receipt 成功场次回执
     * @param stage 原副本编号
     * @param swept 是否扫荡
     */
    private void recordCompletion(String roleId, String receipt, int stage, boolean swept) {
        jdbc.update(
                "INSERT INTO local_dailydup_completion(role_id,receipt,stage_id,swept,completed_at) VALUES(?,?,?,?,?)",
                roleId, receipt, stage, swept ? 1 : 0, Instant.now().getEpochSecond());
    }

    /** 按界面所示的 VIP 收益比例发金币或真实材料，超出背包时转邮件。
     * @param id 当前角色
     * @param cfg 原副本配置
     * @param vip 当前 VIP 等级
     * @param receipt 资产入账回执
     * @return 原 attachment 本次到账
     */
    private ObjectNode reward(String id, JsonNode cfg, int vip, String receipt) {
        JsonNode award = cfg.path("reward");
        int item = award.get(1).asInt();
        long amount = amount(cfg, vip);
        ObjectNode adds = json.createObjectNode();
        if (item == 1001) {
            roles.addCurrency(id, amount, 0, 0);
            adds.put("coins", amount);
        } else {
            Map<Integer, Long> sent = inventory.grantItemsOrMail(id, receipt, Collections.singletonMap(item, amount));
            if (sent.containsKey(item))
                adds.putArray("items").addObject().put("id", item).put("n", sent.get(item));
        }
        return adds;
    }
}
