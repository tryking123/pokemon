package com.pokemon.local.arena;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.battle.BattleMapper;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 火箭队车轮战专用的三支编队及已冻结的敌我战斗快照。
 * @author tongruiyuan
 * @since 2026-11-20 12:42
 */
@Service
public class RocketTeams {
    private final ArenaCombat combat;
    private final ArenaMapper store;
    private final BattleMapper formations;
    private final RoleCosmeticService cosmetics;
    private final JdbcTemplate jdbc;
    private final ElfService elves;
    private final ObjectMapper json;

    /**
     * 连接原精灵、阵法与训练战斗引擎。
     * @param combat 逐回合战斗引擎
     * @param store 本地竞技存档
     * @param formations 阵法等级
     * @param cosmetics 参战精灵的实际加成
     * @param jdbc 角色数据库
     * @param elves 持有的精灵实例
     * @param json 快照编码器
     */
    RocketTeams(ArenaCombat combat, ArenaMapper store, BattleMapper formations, RoleCosmeticService cosmetics,
                JdbcTemplate jdbc, ElfService elves, ObjectMapper json) {
        this.combat=combat; this.store=store; this.formations=formations; this.cosmetics=cosmetics;
        this.jdbc=jdbc; this.elves=elves; this.json=json;
        jdbc.execute("CREATE TABLE IF NOT EXISTS local_rocket_slot(role_id VARCHAR(36) NOT NULL,elf_id VARCHAR(64) NOT NULL,team_index INTEGER NOT NULL, PRIMARY KEY(role_id,elf_id))");
    }

    /**
     * 读取三支编队，已解除的实例不继续占位。
     * @param role 角色标识
     * @return 按队号排序的原协议阵容
     */
    ArrayNode load(String role) {
        ArrayNode teams=json.createArrayNode();
        for (int i=1;i<=3;i++) {
            ObjectNode team=store.load(role,"lineup:rocket:"+i);
            ObjectNode row=teams.addObject().put("index",i).put("tactics",team.path("tactics").asInt(1));
            row.set("list",team.has("battle")?team.get("battle"):json.createArrayNode());
        }
        return teams;
    }

    /**
     * 替换一支队伍，校验归属、站位、助阵状态及三队唯一性。
     * @param role 角色标识
     * @param index 原队号
     * @param tactics 阵法类型
     * @param slots 原请求中的精灵和阵位
     * @return 是否完成保存
     */
    boolean set(String role, int index, int tactics, JsonNode slots) {
        if(index<1 || index>3 || tactics<1 || tactics>7 || !slots.isArray() || slots.size()>6) return false;
        Set<String> reserved=new HashSet<>(jdbc.queryForList("SELECT elf_id FROM local_elf_aid WHERE role_id=? UNION SELECT elf_id FROM local_travel_dispatch WHERE role_id=?",String.class,role,role));
        for(JsonNode team:load(role)) if(team.path("index").asInt()!=index)
            for(JsonNode slot:team.path("list")) reserved.add(slot.path("id").asText());
        Map<String,LocalElf> owned=owned(role);
        Set<Integer> positions=new HashSet<>(), ids=new HashSet<>();
        ObjectNode team=json.createObjectNode().put("tactics",tactics);
        ArrayNode checked=team.putArray("battle");
        for(JsonNode slot:slots) {
            String uid=slot.path("id").asText(); LocalElf elf=owned.get(uid);
            int position=slot.path("position").asInt(), id=slot.path("battleid").asInt();
            if(elf==null || FirstBattleData.starter(elf.configId)==null || !reserved.add(uid)
                    || position<1 || position>6 || !positions.add(position) || id<1 || id>6 || !ids.add(id)) return false;
            checked.addObject().put("id",uid).put("sid",elf.configId).put("position",position).put("battleid",id);
        }
        jdbc.update("DELETE FROM local_rocket_slot WHERE role_id=? AND team_index=?",role,index);
        for(JsonNode elf:checked)jdbc.update("INSERT INTO local_rocket_slot(role_id,elf_id,team_index) VALUES(?,?,?)",role,elf.path("id").asText(),index);
        store.save(role,"lineup:rocket:"+index,team);
        return true;
    }

    /**
     * 建立角色精灵索引。
     * @param role 角色标识
     * @return 实例到当前精灵的映射
     */
    private Map<String,LocalElf> owned(String role) {
        Map<String,LocalElf> owned=new LinkedHashMap<>();
        for(LocalElf elf:elves.list(role)) owned.put(elf.id,elf);
        return owned;
    }

    /**
     * 按当前三队和原表训练模板建立匹配快照，避免查看匹配后替换资产。
     * @param role 当前角色
     * @param rate 真实段位
     * @param pool 已核实的六套原训练模板
     * @return 敌我阵容及原匹配预览，缺少出战精灵时为空
     */
    ObjectNode match(LocalRole role,int rate,JsonNode pool) {
        Map<String,LocalElf> owned=owned(role.id);
        Set<String> aid=new HashSet<>(jdbc.queryForList("SELECT elf_id FROM local_elf_aid WHERE role_id=? UNION SELECT elf_id FROM local_travel_dispatch WHERE role_id=?",String.class,role.id,role.id));
        ObjectNode snap=json.createObjectNode().put("id",UUID.randomUUID().toString());
        ArrayNode allies=snap.putArray("allies"), opponents=snap.putArray("opponents");
        ArrayNode reference=null;
        RoleCosmeticService.BattleBonuses bonus=cosmetics.battleBonuses(role.id);
        for(JsonNode source:load(role.id)) {
            int index=source.path("index").asInt();
            ArrayNode members=json.createArrayNode();
            for(JsonNode slot:source.path("list")) {
                LocalElf elf=owned.get(slot.path("id").asText());
                if(elf==null || aid.contains(elf.id))return null;
                members.add(combat.player(role.id,elf,slot.path("position").asInt(),bonus));
            }
            if(!members.isEmpty() && reference==null) reference=members;
            allies.add(combat.formation(role.id,formations.formationId(role.id,source.path("tactics").asInt()),false,members));
        }
        if(reference==null)return null;
        int offset=Math.floorMod(snap.path("id").asText().hashCode(),pool.size());
        String uid="npc:rocket:"+snap.path("id").asText();
        long power=0;
        for(int i=0;i<3;i++) {
            ArrayNode current=(ArrayNode)allies.get(i).get("pokemon");
            if(current.isEmpty())current=reference;
            ObjectNode opponent=combat.opposition(pool.get((offset+i)%pool.size()).asText(),uid+":"+i,current,
                    Math.min(2,(rate-1)/3),current.get(0).path("level").asInt());
            opponents.add(opponent);power+=opponent.path("power").asLong();
        }
        ObjectNode preview=snap.putObject("preview").put("errno",0).put("rate",rate).put("tuid",uid).put("svrid",1)
                .put("icon",1).put("name","[NPC] 火箭队训练员").put("power",power);
        preview.set("teams",opponents.deepCopy());
        return snap;
    }

    /**
     * 建立两支仍存活队伍的下一场战斗，保留生命并恢复入场技能点。
     * @param match 匹配快照
     * @return 是否仍有双方可出战
     */
    boolean next(ObjectNode match) {
        int a=nextAlive(match.path("allies"),match.path("a").asInt()), b=nextAlive(match.path("opponents"),match.path("b").asInt());
        match.put("a",a).put("b",b);
        if(a>=3||b>=3)return false;
        ObjectNode fight=match.putObject("fight").put("errno",0).put("weather",0).put("arenaDamageVersion",2)
                .put("seed",Math.floorMod((match.path("id").asText()+":"+a+":"+b).hashCode(),Integer.MAX_VALUE));
        for(String side:new String[]{"player","monster"}) {
            boolean player="player".equals(side);
            ObjectNode team=match.path(player?"allies":"opponents").get(player?a:b).deepCopy();
            ArrayNode members=team.withArray("pokemon");
            for(int i=members.size()-1;i>=0;i--) {
                ObjectNode elf=(ObjectNode)members.get(i);
                if(elf.path("hp").asInt()<=0) {members.remove(i);continue;}
                elf.put("sp",elf.path("rocketSp").asInt(elf.path("sp").asInt()));
                elf.remove("actions");
            }
            combat.addTrainingFallback(team);
            fight.set(side,team);
        }
        return true;
    }

    /**
     * 找出当前索引以后第一支仍有成员的队伍。
     * @param teams 三支已冻结的队伍
     * @param index 当前队号的零基索引
     * @return 可用队伍索引，三表示全灭
     */
    private int nextAlive(JsonNode teams,int index) {
        while(index<teams.size()) {
            for(JsonNode elf:teams.get(index).path("pokemon")) if(elf.path("hp").asInt()>0)return index;
            index++;
        }
        return index;
    }

    /**
     * 将终局中每个实例的实际余血写回车轮战，下一场另行重置技能点。
     * @param match 当前匹配快照
     * @param outcome 已验证的胜负结果
     */
    void carry(ObjectNode match,int outcome) {
        for(String label:new String[]{"player","monster"}) {
            boolean player="player".equals(label);
            JsonNode team=match.path(player?"allies":"opponents").get(match.path(player?"a":"b").asInt());
            for(JsonNode slot:team.path("pokemon")) {
                for(JsonNode result:match.path("fight").path(label).path("pokemon")) {
                    if(slot.path("id").equals(result.path("id"))) {
                        ((ObjectNode)slot).put("hp",(player && outcome==2 || !player && outcome==1)?0:result.path("hp").asInt());
                    }
                }
            }
        }
    }

    /**
     * 按客户端实际发送的行动推进当前场次。
     * @param match 当前匹配
     * @param skill 技能编号
     * @param targets 实例目标
     * @param flag 原自动或手动标记
     * @return 继续为零，胜负为一或二，非法为负一
     */
    int turn(ObjectNode match,int skill,List<?> targets,int flag) {
        return combat.action((ObjectNode)match.get("fight"),skill,targets,flag);
    }
}
