package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.WorldBossService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.*;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 普通与大师世界首领原协议、地图活动和战斗回合适配。
 *
 * @author tongruiyuan
 * @since 2026-11-20 13:56
 */
@Service
@Order(-142)
final class WorldBossGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY=new TypeReference<Map<String,Object>>() {};
    private final WorldBossService boss;
    private final com.pokemon.offline.role.RoleService roles;
    private final InventoryGameFeature bag;
    private final ObjectMapper json;
    private final Map<GameReply,View> views=Collections.synchronizedMap(new WeakHashMap<>());
    /** 当前连接展示的两个独立场次和本机请求标识。 */
    private static class View {
        final String connection=UUID.randomUUID().toString();
        long normal=-1,master=-1,next;
        String scene="",target="";
    }

    /**
     * 绑定原地图、角色奖励和战斗。
     * @param roles 当前角色位置
     * @param boss 首领服务
     * @param bag 邮箱刷新
     * @param json 原协议投影
     */
    WorldBossGameFeature(com.pokemon.offline.role.RoleService roles,WorldBossService boss,InventoryGameFeature bag,ObjectMapper json){this.roles=roles;this.boss=boss;this.bag=bag;this.json=json;}
    /**
     * 编码 Sproto 对象。
     * @param obj 原对象
     * @return 协议字段
     */
    private Map<String,Object> body(JsonNode obj){return json.convertValue(obj,BODY);}
    /**
     * 更新原场景列表，空列表清理上一次显示的首领。
     * @param role 角色
     * @param output 当前连接
     * @throws IOException 写失败
     */
    @Override
    public void tick(LocalRole role,GameReply output)throws IOException{
        View view=views.computeIfAbsent(output,k->new View());long now=System.currentTimeMillis()/1000;
        if(now<view.next)return;
        view.next=now+10;
        role=roles.findById(role.id).orElse(role);
        if(role.sceneId!=71002){view.scene="";view.target="";return;}
        ArrayNode actors=boss.scene(role);
        if(actors.toString().equals(view.scene))return;
        String next=actors.isEmpty()?"":actors.get(0).path("uid").asText();
        if(!view.target.isEmpty()&&!view.target.equals(next))output.push(12,data("uids",Collections.singletonList(view.target),"sid",role.sceneId));
        view.target=next;view.scene=actors.toString();
        output.push(801,data("scence",role.sceneId,"worldboss",json.convertValue(actors,List.class)));
    }
    /**
     * 补寄旧周期、同步大师领奖档。
     * @param role 角色
     * @param output 连接
     * @throws IOException 写失败
     */
    @Override
    public void synchronize(LocalRole role,GameReply output)throws IOException{
        if(boss.rollover(role))bag.pushMailCount(role,output);
        View v=views.computeIfAbsent(output,k->new View());
        ArrayNode a=boss.scene(role);v.target=a.isEmpty()?"":a.get(0).path("uid").asText();v.scene=a.toString();
        output.push(10006,body(boss.summary(role)));
    }
    /**
     * 接管原世界首领请求与匹配的战斗回合。
     * @param role 角色
     * @param request 请求
     * @param output 出口
     * @return 是否已处理
     * @throws IOException 写失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output)throws IOException{
        int type=request.type;
        if(type==3001 || type==3002){
            if(!boss.active(role.id))return false;
            Object objs=request.body.get("objs");
            ObjectNode result=boss.turn(role.id,(int)number(request.body,"sid",0),objs instanceof List?(List<?>)objs:Collections.emptyList(),(int)number(request.body,"flag",-1),type==3002);
            if(result.has("summary"))output.push(10006,body(result.get("summary")));
            if(result.path("mail").asBoolean())bag.pushMailCount(role,output);
            if(result.path("outcome").asInt()==2 && type!=3002)output.push(1051,data());
            return true;
        }
        if(type>=3651&&type<=3653 || type>=10033&&type<=10036){
            if(request.session<=0)return true;
            View view=views.computeIfAbsent(output,k->new View());
            String uid=String.valueOf(request.body.getOrDefault("uid",""));
            boolean pay=type==3651 && uid.startsWith("buy:");
            ObjectNode result=boss.action(role.id,type,pay?uid.substring(4):uid,
                    (int)number(request.body,"index",0),pay,type<10000?view.normal:view.master,view.connection+":"+request.session);
            ObjectNode response=(ObjectNode)result.get("response");
            if(response.path("errno").asInt()==0){
                if(type==3653)view.normal=result.path("slot").asLong();
                if(type==10033)view.master=result.path("slot").asLong();
            }
            output.send(request,body(response));
            if(type==10035&&response.path("errno").asInt()==0)output.push(10006,body(boss.summary(role)));
            if(result.has("diamonds"))output.push(3,data("diamonds",result.path("diamonds").asLong()));
            if(result.path("mail").asBoolean())bag.pushMailCount(role,output);
            return true;
        }
        if (!competingAction(type) || !boss.active(role.id)) return false;
        if (request.session > 0) output.send(request, data("errno", 2002));
        return true;
    }

    /**
     * 判定会离开当前场景或创建另一场战斗的请求。
     * @param type 原请求编号
     * @return 主城限时战斗期间是否需要拦截
     */
    static boolean competingAction(int type) {
        switch(type){
            case 8: case 66: case 68: case 69: case 102: case 105: case 109: case 121: case 123: case 127:
            case 168: case 169: case 181: case 182: case 268: case 272: case 273: case 285: case 300:
            case 333: case 2203: case 3037: case 5069: case 5071: case 5072: case 5101:
            case 10016: case 10040: case 290: case 294: case 3651: case 10034:
                return true;
            default:return false;
        }
    }
}
