package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.battle.NestService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.*;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 巢穴地图、战斗和拾取事件的原协议入口。
 * @author tongruiyuan
 * @since 2026-11-19 22:08
 */
@Service
@Order(-63)
final class NestGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY=new TypeReference<Map<String,Object>>(){};
    private final NestService nest;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final ObjectMapper json;
    private final Map<GameReply,String> connections=Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定权威战斗和库存。
     * @param nest 巢穴进度
     * @param roles 钱包
     * @param bags 背包推送
     * @param json 协议转换
     */
    NestGameFeature(NestService nest,RoleService roles,BagSnapshot bags,ObjectMapper json) {
        this.nest=nest;this.roles=roles;this.bags=bags;this.json=json;
    }

    /** 在原入口恢复进行中的地图。
     * @param role 角色
     * @param out 原协议输出
     * @throws IOException 推送失败
     */
    @Override
    public void synchronize(LocalRole role,GameReply out) throws IOException {
        if(role.level<85) return;
        ObjectNode current=nest.current(role.id);
        if(current!=null) {pushHp(role,out);out.push(2045,json.convertValue(current,BODY));out.push(111,json.convertValue(nest.buffs(role.id),BODY));}
    }

    /** 转发操作与可见事件。
     * @param role 当前角色
     * @param request 原请求
     * @param out 连接
     * @return 已处理该请求
     * @throws IOException 编码失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply out) throws IOException {
        if(request.type>=10038 && request.type<=10042 && request.session>0) {
            String conn=connections.computeIfAbsent(out,k->UUID.randomUUID().toString());
            long value=number(request.body,request.type==10040?"direction":"sid",0);
            ObjectNode result=nest.handle(role.id,request.type,value,conn+":"+request.session);
            JsonNode buffs=result.remove("_buff");
            JsonNode strength=result.remove("_strength"),event=result.remove("_event"),adds=result.remove("_adds");
            out.send(request,json.convertValue(result,BODY));
            if(result.path("errno").asInt()==0 && (request.type==10039 || request.type==10041)) pushHp(role,out);
            if(buffs!=null) out.push(111,json.convertValue(buffs,BODY));
            if(strength!=null) out.push(2043,data("strength",strength.asInt()));
            if(event!=null) out.push(2046,json.convertValue(event,BODY));
            if(adds!=null) publish(role,out,adds,"do_nest_specialevent");
            if(request.type==10042 && result.path("errno").asInt()==0) {
                bags.push(role.id,out);out.push(3,data("diamonds",roles.findById(role.id).orElse(role).diamonds));
            }
            return true;
        }
        if((request.type==3001 || request.type==3002) && role.level>=85 && nest.fighting(role.id)) {
            Object values=request.body.get("objs");
            long skill=number(request.body,"sid",0),flag=number(request.body,"flag",-1);
            if(skill<0 || skill>Integer.MAX_VALUE || flag < -1 || flag>Integer.MAX_VALUE) return true;
            ObjectNode result=nest.advance(role.id,(int)skill,values instanceof List?(List<?>)values:Collections.emptyList(),
                    (int)flag,request.type==3002);
            if(result.path("outcome").asInt()>0) pushHp(role,out);
            if(result.has("_event")) out.push(2046,json.convertValue(result.get("_event"),BODY));
            if(result.has("_adds")) publish(role,out,result.get("_adds"),"nest_win");
            if(result.path("outcome").asInt()>0 && request.type!=3002) out.push(1051,data());
            return true;
        }
        return false;
    }

    /** 同步原 gdun_model 中的巢穴队伍生命。
     * @param role 当前角色
     * @param out 输出
     * @throws IOException 编码失败
     */
    private void pushHp(LocalRole role,GameReply out) throws IOException {
        out.push(379,data("teamhp",json.convertValue(nest.teamHp(role.id),BODY),"flag",0));
    }

    /** 原界面显示实际结算的巢穴奖励。
     * @param role 角色
     * @param out 连接
     * @param adds 资产
     * @param flags 原事件名
     * @throws IOException 推送失败
     */
    private void publish(LocalRole role,GameReply out,JsonNode adds,String flags) throws IOException {
        out.push(14,data("flags",flags,"adds",json.convertValue(adds,BODY)));
        bags.push(role.id,out);
    }
}
