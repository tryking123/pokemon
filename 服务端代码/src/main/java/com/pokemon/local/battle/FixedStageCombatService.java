package com.pokemon.local.battle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.ArenaCombat;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;

/** 
 * 由角色第一队与底包固定 NPC 编队驱动普通本地关卡战斗。
 * @author tongruiyuan
 * @since 2026-11-19 19:14
 */
@Service
public class FixedStageCombatService {
    private final BattleMapper battles;
    private final ArenaCombat combat;
    private final ElfService elves;
    private final RoleCosmeticService cosmetics;
    private final ObjectMapper json;

    /** 绑定战斗所需的角色精灵、阵法及基础计算。
     * @param battles 普通阵法
     * @param combat 回合计算器
     * @param elves 角色持有精灵
     * @param cosmetics 角色战斗加成
     * @param json 协议快照
     */
    public FixedStageCombatService(BattleMapper battles, ArenaCombat combat, ElfService elves,
            RoleCosmeticService cosmetics, ObjectMapper json) {
        this.battles = battles;
        this.combat = combat;
        this.elves = elves;
        this.cosmetics = cosmetics;
        this.json = json;
    }

    /** 按固定关卡实际采用的基础战斗属性生成指定队当前成员的满生命。
     * @param roleId 角色编号
     * @param team 普通编队号
     * @return 各精灵原战斗生命
     */
    public ObjectNode health(String roleId,int team) {
        ObjectNode rows=json.createObjectNode();
        RoleCosmeticService.BattleBonuses bonuses=cosmetics.battleBonuses(roleId);
        for (LocalElf elf:elves.list(roleId)) {
            if(elf.team!=team || elf.position()==0)continue;
            int hp=combat.player(roleId,elf,elf.position(),bonuses).path("hp_max").asInt();
            rows.putObject(elf.id).put("uid",elf.id).put("team",team).put("crthp",hp).put("maxhp",hp);
        }
        return rows;
    }

    /** 生成角色第一队和底包敌队。
     * @param role 角色
     * @param stage 原关卡号
     * @param cfg 原配置
     * @return 战斗开场
     */
    public ObjectNode start(LocalRole role, int stage, JsonNode cfg) {
        return start(role,stage,cfg,battles.choice(role.id,"default"));
    }

    /** 生成角色明确选择的常规队伍和关卡怪物。
     * @param role 当前角色
     * @param stage 关卡编号
     * @param cfg 原怪物队伍
     * @param team 选择的队伍编号
     * @return 可用战斗开场或人员不足
     */
    public ObjectNode start(LocalRole role,int stage,JsonNode cfg,int team) {        ArrayNode allies = json.createArrayNode(), foes = json.createArrayNode();
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(role.id);
        for (LocalElf elf : elves.list(role.id))
            if (elf.team == team && elf.position() > 0)
                allies.add(combat.player(role.id, elf, elf.position(), bonuses));
        if (allies.isEmpty())
            return json.createObjectNode().put("errno", 1);
        String opp = UUID.randomUUID().toString();
        for (JsonNode row : cfg.path("actors")) {
            ObjectNode enemy = row.deepCopy();
            int eid = enemy.remove("enemy").asInt();
            combat.fixedActor(enemy, opp + "-" + enemy.path("position").asInt(), opp, eid, enemy.path("sprite").asInt(),
                    Math.max(1, cfg.path("level").asInt()), enemy.path("position").asInt(), enemy.path("type1").asInt(),
                    enemy.path("type2").asInt(), enemy.path("quality").asInt(), enemy.path("skill").asInt(), 1, 0);
            foes.add(enemy);
        }
        ObjectNode fight = json.createObjectNode().put("errno", 0).put("stage", stage)
                .put("expiry", Instant.now().getEpochSecond() + 1200)
                .put("seed", ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE)).put("weather", 0);
        fight.set("player", combat.formation(role.id, battles.formationId(role.id,battles.teamFormation(role.id,team)), false, allies));
        fight.set("monster", combat.formation(opp, cfg.path("formation").asInt(), true, foes));
        return fight;
    }

    /** 执行关卡动作，支持原空自动包和普通目标技能。
     * @param fight 当前战斗
     * @param skill 技能
     * @param targets 目标编号
     * @param flag 操作类型
     * @param retreat 是否撤退
     * @return 战斗结果
     */
    public int advance(ObjectNode fight, int skill, List<?> targets, int flag, boolean retreat) {
        if (skill == 0 && targets.isEmpty() && (flag == 0 || flag == 1)) {
            JsonNode members = fight.path("player").path("pokemon");
            for (int i = 0; i < members.size(); i++) {
                JsonNode member = members.get((fight.path("turn").asInt() + i) % members.size());
                if (member.path("hp").asInt() > 0) {
                    skill = member.path("skill_bar").path(0).asInt();
                    break;
                }
            }
            for (JsonNode foe : fight.path("monster").path("pokemon"))
                if (foe.path("hp").asInt() > 0) {
                    targets = Collections.singletonList(foe.path("id").asText());
                    break;
                }
        }
        return retreat ? 2 : combat.action(fight, skill, targets, flag);
    }
}
