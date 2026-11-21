package com.pokemon.local.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.payment.BlindBoxService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

/**
 * 原盲盒商城与精灵银行的库存、资产回执及已付订单同步。
 * @author tongruiyuan
 * @since 2026-11-20 13:16
 */
@Service
final class BlindBoxGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> FIELDS=new TypeReference<Map<String,Object>>(){};
    private final BlindBoxService boxes;
    private final BagSnapshot bags;
    private final BattleGameFeature battle;
    private final CosmeticGameFeature costumes;
    private final ObjectMapper json;
    private final Map<GameReply,View> views=Collections.synchronizedMap(new WeakHashMap<>());

    /** 同一已认证连接可重放的请求标识及已推送库存。 */
    private static final class View {
        final String id=UUID.randomUUID().toString();
        ObjectNode last;
    }

    /**
     * 连接盲盒账本及各原玩法资产快照。
     * @param boxes 盲盒业务
     * @param bags 普通道具和溢出邮件
     * @param battle 精灵背包
     * @param costumes 时装碎片背包
     * @param json 原协议字段转换
     */
    BlindBoxGameFeature(BlindBoxService boxes,BagSnapshot bags,BattleGameFeature battle,CosmeticGameFeature costumes,ObjectMapper json) {
        this.boxes=boxes; this.bags=bags; this.battle=battle; this.costumes=costumes; this.json=json;
    }

    /**
     * 原角色进服时提供精灵银行及已付未开的盲盒。
     * @param role 已认证角色
     * @param output 原游戏连接
     * @throws IOException 无法推送
     */
    @Override
    public void synchronize(LocalRole role,GameReply output) throws IOException {
        View view=views.computeIfAbsent(output,k->new View());
        view.last=boxes.snapshot(role.id);
        output.push(464,json.convertValue(view.last,FIELDS));
    }

    /**
     * 独立模拟支付窗口确认订单后通知原商城页面。
     * @param role 已认证角色
     * @param output 原游戏连接
     * @throws IOException 无法推送
     */
    @Override
    public void tick(LocalRole role,GameReply output) throws IOException {
        View view=views.computeIfAbsent(output,k->new View());
        ObjectNode state=boxes.snapshot(role.id);
        if(!state.equals(view.last)) {
            output.push(464,json.convertValue(state,FIELDS));
            view.last=state;
        }
    }

    /**
     * 校验开箱或银行动作后同步其真实资产。
     * @param role 已认证角色
     * @param request 开箱、拆解或领取请求
     * @param output 原游戏连接
     * @return 是否处理该协议
     * @throws IOException 连接写入失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output) throws IOException {
        int cmd=request.type;
        if(cmd<399 || cmd>401)return false;
        if(request.session<=0)return true;
        View view=views.computeIfAbsent(output,k->new View());
        ObjectNode result=boxes.transact(role.id,cmd,json.valueToTree(request.body),view.id+":"+request.session);
        JsonNode adds=result.remove("adds");
        if(adds!=null) {
            if(adds.size()>0)output.push(14,SprotoProtocol.data("flags",cmd==400?"hollar.tear":"bbox.open","adds",json.convertValue(adds,FIELDS)));
            if(adds.has("items")){bags.push(role.id,output);bags.pushMailCount(role.id,output);}
            if(adds.has("fasheschips"))costumes.pushDress(role.id,output);
            if(cmd==401)battle.pushElfs(role.id,output);
        }
        if(result.path("errno").asInt()==0)synchronize(role,output);
        output.send(request,json.convertValue(result,FIELDS));
        return true;
    }
}
