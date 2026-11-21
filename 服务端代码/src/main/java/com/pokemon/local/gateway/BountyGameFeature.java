package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.arena.BountyService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;
import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原悬赏首领挑战、兑换、排名和领奖协议适配。
 *
 * @author tongruiyuan
 * @since 2026-11-20 11:05
 */
@Service
@Order(-121)
final class BountyGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY=new TypeReference<Map<String,Object>>() {};
    private final BountyService bounty;
    private final InventoryGameFeature bag;
    private final ObjectMapper json;
    private final Map<GameReply,String> connections=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,Long> periods=Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定悬赏活动及已有的资产刷新。
     * @param bounty 悬赏服务
     * @param bag 背包及邮件推送
     * @param json 原协议数据转换
     */
    BountyGameFeature(BountyService bounty,InventoryGameFeature bag,ObjectMapper json) {
        this.bounty=bounty; this.bag=bag; this.json=json;
    }

    /** 登录时只处理已结束赛季的寄奖，不生成尚未参与的首领。
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 发送失败
     */
    @Override
    public void synchronize(LocalRole role,GameReply output) throws IOException {
        ObjectNode result=bounty.act(role.id,0,0,0,0,"",0);
        if(result.path("mail").asBoolean()) bag.pushMailCount(role,output);
    }

    /** 原窗口读取、结算请求和悬赏回合。
     * @param role 当前角色
     * @param request 客户端请求
     * @param output 协议出口
     * @return 悬赏活动是否已处理消息
     * @throws IOException 发送失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output) throws IOException {
        int type=request.type;
        if(type==3001 || type==3002) {
            if(!bounty.active(role.id)) return false;
            Object targets=request.body.get("objs");
            ObjectNode result=bounty.turn(role.id,(int)number(request.body,"sid",0),
                    targets instanceof List ? (List<?>)targets : Collections.emptyList(),(int)number(request.body,"flag",-1),type==3002);
            if(result!=null) {
                publish(role,result,output);
                if(result.path("outcome").asInt()==2 && type!=3002)output.push(1051,data());
            }
            return true;
        }
        if(type>=10012 && type<=10020) {
            if(request.session<=0)return true;
            String connection=connections.computeIfAbsent(output,k->UUID.randomUUID().toString());
            ObjectNode result=bounty.act(role.id,type,(int)number(request.body,"index",0),(int)number(request.body,"itype",0),
                    periods.getOrDefault(output,-1L),connection,request.session);
            if(type<=10015 && result.path("response").path("errno").asInt()==0) periods.put(output,result.path("week").asLong());
            output.send(request,body(result.get("response")));
            publish(role,result,output);
            return true;
        }
        switch(type) {
            case 8: case 66: case 68: case 69: case 102: case 105: case 109: case 121: case 123: case 127:
            case 168: case 169: case 181: case 182: case 268: case 272: case 273: case 285: case 300:
            case 2203: case 3037: case 5069: case 5071: case 5072: case 5101: case 10040:
                if(!bounty.active(role.id))return false;
                if(request.session>0)output.send(request,data("errno",2002));
                return true;
            default: return false;
        }
    }

    /** 编码 JSON 原协议字段。
     * @param node 应答或推送数据
     * @return 原 Sproto 对象
     */
    private Map<String,Object> body(JsonNode node) {return json.convertValue(node,BODY);}

    /** 推送已经提交的悬赏奖励和余额。
     * @param role 当前角色
     * @param result 事务结果
     * @param output 协议出口
     * @throws IOException 发送失败
     */
    private void publish(LocalRole role,ObjectNode result,GameReply output) throws IOException {
        if(result.has("adds")) {
            output.push(14,data("flags","fesoffer.reward","adds",body(result.get("adds"))));
            if(result.path("adds").has("items"))bag.pushBag(role,output);
        }
        if(result.has("diamonds"))output.push(3,data("diamonds",result.path("diamonds").asLong()));
        if(result.path("mail").asBoolean())bag.pushMailCount(role,output);
    }
}
