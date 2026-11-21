package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.FixedStageCombatService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 主城圣诞麋鹿的场次、原队伍战斗及逐只获奖记录。
 *
 * @author tongruiyuan
 * @since 2026-11-20 15:01
 */
@Service
public class FeastService {
    private static final int CITY = 71002, GIFT = 34700;
    private static final String MODE = "feast", BATTLE = "attempt";
    private final RoleService roles;
    private final ArenaMapper store;
    private final FixedStageCombatService combat;
    private final InventoryService inventory;
    private final WorldBossService encounters;
    private final ObjectMapper json;
    private final JsonNode rosters;

    /**
     * 绑定已有战斗、角色和奖励账本。
     * @param roles 角色锁
     * @param store 个人活动存档
     * @param combat 关卡战斗
     * @param inventory 背包与邮件
     * @param encounters 共用的战斗占用检查
     * @param json 协议对象
     * @throws IOException 原阵容读取失败
     */
    public FeastService(RoleService roles, ArenaMapper store, FixedStageCombatService combat,
                        InventoryService inventory, WorldBossService encounters, ObjectMapper json) throws IOException {
        this.roles = roles;
        this.store = store;
        this.combat = combat;
        this.inventory = inventory;
        this.encounters = encounters;
        this.json = json;
        try (InputStream in = new ClassPathResource("battle/feast-reindeer.json").getInputStream()) {
            rosters = json.readTree(in);
        }
    }

    /**
     * 计算北京时间的日序号。
     *
     * @param now 当前秒数
     * @return 北京日期序号
     */
    private long day(long now) {
        return Math.floorDiv(now + 28800, 86400);
    }

    /**
     * 计算主城怪物的半小时刷新轮次。
     *
     * @param now 当前秒数
     * @return 当日每半小时一轮的麋鹿场次，负一表示关闭
     */
    private int round(long now) {
        int offset = (int)Math.floorMod(now + 28800, 86400) - 43200;
        return offset >= 0 && offset < 7200 ? offset / 1800 : -1;
    }

    /**
     * 读取当天已制服的麋鹿记录。
     *
     * @param role 当前角色
     * @param now 当前秒数
     * @return 当日真实胜利与已击败目标
     */
    private ObjectNode ledger(String role, long now) {
        ObjectNode saved = store.load(role, MODE);
        return saved.path("day").asLong(-1) == day(now) ? saved : json.createObjectNode().put("day", day(now));
    }

    /**
     * 查询当前主城内可见的麋鹿。
     *
     * @param role 角色位置
     * @return 当前场景可见的活动目标
     */
    public ArrayNode scene(LocalRole role) {
        long now = Instant.now().getEpochSecond();
        return actors(role, now);
    }

    /**
     * 在当前刷新区域内生成未被该角色制服的目标。
     *
     * @param role 当前角色
     * @param now 当前秒数
     * @return 当前半小时内实际存活的原图怪物
     */
    private ArrayNode actors(LocalRole role, long now) {
        ArrayNode actors = json.createArrayNode();
        int round = round(now);
        if (role.sceneId != CITY || round < 0) return actors;
        ObjectNode ledger = ledger(role.id, now);
        if (ledger.path("wins").asInt() >= 10) return actors;
        for (int i = 0; i < 30; i++) {
            String uid = MODE + "-" + day(now) + "-" + round + "-" + i;
            if (ledger.path("killed").has(uid)) continue;
            ObjectNode actor = actors.addObject().put("sid", 430001 + i % 2).put("uid", uid).put("level", 1);
            actor.putObject("pos").put("x", (6 + (i % 6) * 5) * 32).put("y", (5 + (i / 6) * 5) * 32);
        }
        return actors;
    }

    /**
     * 判断当前角色是否处于有效麋鹿战斗。
     *
     * @param id 角色编号
     * @return 当前是否正在进行麋鹿战斗
     */
    public boolean active(String id) {
        JsonNode f = store.load(id, BATTLE);
        return MODE.equals(f.path("mode").asText()) && f.path("expiry").asLong() > Instant.now().getEpochSecond();
    }

    /** 为原协议裁去本地回合运算专用字段。
     * @param fight 当前战斗快照
     * @return 原格式开战应答
     */
    private ObjectNode opening(ObjectNode fight) {
        ObjectNode out = json.createObjectNode().put("errno", 0);
        WorldBossService.opening(fight, out);
        return out;
    }

    /**
     * 校验场景目标并冻结原版出战阵容。
     *
     * @param id 角色编号
     * @param target 主城怪物实例
     * @param key 本次请求标识
     * @return 开战响应
     */
    @Transactional
    public ObjectNode start(String id, String target, String key) {
        LocalRole role = roles.lockRole(id);
        long now = Instant.now().getEpochSecond();
        ObjectNode error = json.createObjectNode().put("errno", 2003);
        ObjectNode ledger = ledger(id, now);
        ObjectNode previous = store.load(id, BATTLE);
        if (ledger.path("lastKey").asText().equals(key)) {
            if (MODE.equals(previous.path("mode").asText()) && target.equals(previous.path("uid").asText())
                    && previous.path("expiry").asLong() > now && !previous.path("advanced").asBoolean()) return opening(previous);
            return error;
        }
        if (encounters.fightOccupied(id)) return error;
        for (JsonNode actor : actors(role, now)) {
            if (!target.equals(actor.path("uid").asText())) continue;
            int sid = actor.path("sid").asInt();
            ObjectNode fight = combat.start(role, sid, rosters.required(String.valueOf(sid)));
            if (fight.path("errno").asInt() != 0) return error;
            fight.put("mode", MODE).put("started", now).put("id", target).put("uid", target);
            ledger.put("lastKey", key);
            store.save(id, MODE, ledger);
            store.save(id, BATTLE, fight);
            return opening(fight);
        }
        return error;
    }

    /**
     * 用实际战斗回合结算胜利，首次击败该目标时发放麋鹿宝箱。
     * @param id 角色编号
     * @param skill 请求技能
     * @param targets 请求目标
     * @param flag 动作类型
     * @param retreat 是否退出
     * @return 服务端胜负及实际入包数
     */
    @Transactional
    public ObjectNode turn(String id, int skill, List<?> targets, int flag, boolean retreat) {
        roles.lockRole(id);
        ObjectNode fight = store.load(id, BATTLE);
        ObjectNode result = json.createObjectNode();
        long now = Instant.now().getEpochSecond();
        if (!MODE.equals(fight.path("mode").asText()) || fight.path("expiry").asLong() <= now) return result;
        int outcome = combat.advance(fight, skill, targets, flag, retreat);
        result.put("outcome", outcome);
        if (outcome == 0) store.save(id, BATTLE, fight.put("advanced", true));
        if (outcome <= 0) return result;
        store.remove(id, BATTLE);
        if (outcome != 1 || day(fight.path("started").asLong()) != day(now)) return result;
        ObjectNode ledger = ledger(id, now);
        String target = fight.path("uid").asText();
        if (ledger.path("wins").asInt() >= 10 || ledger.path("killed").has(target)) return result;
        Map<Integer,Long> given = inventory.grantItemsOrMail(id, target, Collections.singletonMap(GIFT, 1L));
        ledger.put("wins", ledger.path("wins").asInt() + 1).with("killed").put(target, true);
        store.save(id, MODE, ledger);
        return result.put("uid", target).put("gift", given.containsKey(GIFT));
    }
}
