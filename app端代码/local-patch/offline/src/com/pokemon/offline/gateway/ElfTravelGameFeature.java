package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.elf.ElfTravelService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原旅行社和旅行事件的客户端协议适配。
 * @author tongruiyuan
 * @since 2026-11-20 09:27
 */
@Service
@Order(-61)
final class ElfTravelGameFeature implements GameFeature {
    private final ElfTravelService travel;
    private final BagSnapshot bags;
    private final ObjectMapper json;
    private final RoleService roles;
    private final Map<GameReply,String> connections=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,Long> boardPeriods=Collections.synchronizedMap(new WeakHashMap<>());
    private static final TypeReference<Map<String,Object>> BODY=new TypeReference<Map<String,Object>>(){};

    /**
     * 连接角色的旅行存档及背包推送。
     * @param travel 旅行服务
     * @param bags 背包快照
     * @param json 协议字段映射器
     * @param roles 角色钱包
     */
    ElfTravelGameFeature(ElfTravelService travel,BagSnapshot bags,ObjectMapper json,RoleService roles){
        this.travel=travel;this.bags=bags;this.json=json;this.roles=roles;
    }

    /** 登录恢复旅行团、在途倒计时和未处理的遇敌。
     * @param role 当前角色
     * @param out 原协议连接
     * @throws IOException 连接写入失败
     */
    @Override public void synchronize(LocalRole role,GameReply out)throws IOException{
        if(role.level<36)return;
        out.push(2019,body(travel.snapshot(role.id)));
        boardPeriods.put(out,Math.floorDiv(java.time.Instant.now().getEpochSecond()+28800,14400));
        ObjectNode state=travel.resume(role.id);
        if(state.has("event")){
            out.push(2022,body(state.get("event")));
            out.push(2023,data());
        }
        if(state.has("start"))openBattle(out,state.get("start"));
    }

    /**
     * 升至开放等级、四小时次数恢复及每日重置后刷新原界面。
     * @param role 当前角色
     * @param out 原协议连接
     * @throws IOException 连接写入失败
     */
    @Override public void tick(LocalRole role,GameReply out)throws IOException{
        if(role.level<36)return;
        long period=Math.floorDiv(java.time.Instant.now().getEpochSecond()+28800,14400);
        if(!Long.valueOf(period).equals(boardPeriods.get(out))){
            out.push(2019,body(travel.snapshot(role.id)));
            boardPeriods.put(out,period);
        }
    }

    /** 处理旅行社动作并按原回调顺序推送已存档的变化。
     * @param role 当前角色
     * @param req 请求动作
     * @param out 原协议连接
     * @return 是否由旅行处理
     * @throws IOException 连接写入失败
     */
    @Override public boolean handle(LocalRole role,SprotoProtocol.Request req,GameReply out)throws IOException{
        if(req.type==3001||req.type==3002){
            Object targets=req.body.get("objs");
            ObjectNode result=travel.advance(role.id,(int)number(req.body,"sid",0),targets instanceof List?(List<?>)targets:Collections.emptyList(),
                    (int)number(req.body,"flag",-1),req.type==3002);
            if(result==null)return false;
            if(result.path("outcome").asInt()==1)publish(role,result,out,"travel_event_battle");
            if(result.path("outcome").asInt()==2&&req.type!=3002)out.push(1051,data());
            return true;
        }
        switch(req.type){
        case 8: case 66: case 68: case 102: case 105: case 109: case 181: case 273: case 285:
        case 2203: case 3037: case 5071: case 5072: case 5101: case 10040:
            if(!travel.active(role.id))return false;
            if(req.session>0)out.send(req,data("errno",2002));
            return true;
        default:break;
        }
        switch(req.type){
        case 5063: case 5064: case 5065: case 5066: case 5069: case 5078: case 5095:break;
        default:return false;
        }
        if(req.session<=0)return true;
        String key=connections.computeIfAbsent(out,k->UUID.randomUUID().toString())+":"+req.type+":"+req.session;
        ObjectNode r=travel.act(role.id,req.type,json.valueToTree(req.body),key);
        boolean changed=r.path("errno").asInt()==0;
        if(changed&&r.has("event"))out.push(2022,body(r.get("event")));
        out.send(req,body(r.deepCopy().retain("errno","uid","region","itype","fruit","debris","efforts")));
        if(changed){
            out.push(2019,body(travel.snapshot(role.id)));
            if(!r.path("replayed").asBoolean()){
                publish(role,r,out,"travel.reward");
                if(r.has("start"))openBattle(out,r.get("start"));
                if(r.path("prompt").asBoolean())out.push(2023,data());
            }
        }
        return true;
    }

    /** 生成原 Sproto 可编码的数据结构。 */
    private Map<String,Object> body(JsonNode node){return json.convertValue(node,BODY);}

    /** 同步已经入账的资产。 */
    private void publish(LocalRole role,ObjectNode r,GameReply out,String flag)throws IOException{
        if(r.has("adds"))out.push(14,data("flags",flag,"adds",body(r.get("adds")),"loses",data()));
        LocalRole current=roles.findById(role.id).orElse(role);
        out.push(3,data("coins",current.coins,"diamonds",current.diamonds));
        bags.push(role.id,out);
        bags.pushMailCount(role.id,out);
    }

    /** 用原客户端事件战斗入口发送双方阵型和种子。 */
    private void openBattle(GameReply out,JsonNode fight)throws IOException{
        ObjectNode wire=((ObjectNode)fight).deepCopy().retain("seed","weather","player","monster");
        for(String side:new String[]{"player","monster"}){
            for(JsonNode actor:wire.path(side).path("pokemon")){
                ((ObjectNode)actor).remove(java.util.Arrays.asList("skill","targetLimit","physical","goldCarry", "homeStatues","baseDamage"));
            }
        }
        out.push(378,body(wire));
    }
}
