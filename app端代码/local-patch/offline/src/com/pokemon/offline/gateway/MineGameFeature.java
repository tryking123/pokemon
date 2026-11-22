package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.arena.MineService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原矿区开采、矿位和真实 NPC 抢夺的协议适配。
 *
 * @author tongruiyuan
 * @since 2026-11-20 10:41
 */
@Service
@Order(-120)
final class MineGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY=new TypeReference<Map<String,Object>>() {};
    private final MineService mines;
    private final ObjectMapper json;
    private final Map<GameReply,Long> days=Collections.synchronizedMap(new WeakHashMap<>());

    /** 绑定矿洞服务与响应字段编码器。
     * @param mines 开采和抢夺服务
     * @param json 原响应字段映射
     */
    MineGameFeature(MineService mines,ObjectMapper json) {this.mines=mines; this.json=json;}

    /** 同步原矿洞面板与冻结的开采游标。
     * @param role 已登录角色
     * @param output 推送出口
     * @throws IOException 写入失败
     */
    @Override
    public void synchronize(LocalRole role,GameReply output) throws IOException {
        days.put(output,Math.floorDiv(Instant.now().getEpochSecond()+14400,86400));
        output.push(431,body(mines.act(role.id,0,0,0,0,"").get("panel")));
    }

    /** 日界到达时刷新矿场、剩余次数及开采游标。
     * @param role 当前角色
     * @param output 推送出口
     * @throws IOException 写入失败
     */
    @Override
    public void tick(LocalRole role,GameReply output) throws IOException {
        long today=Math.floorDiv(Instant.now().getEpochSecond()+14400,86400);
        Long previous=days.get(output);
        if (previous==null || previous==today) return;
        synchronize(role,output);
        if (role.sceneId>=61011 && role.sceneId<=63020) {
            ObjectNode result=(ObjectNode)mines.act(role.id,126,role.sceneId,0,0,"").get("response");
            if (result.has("minelist")) output.push(436,body(result.retain("idx","minelist")));
        }
    }

    /** 将原矿位操作及矿洞战斗回合交给角色事务。
     * @param role 当前角色
     * @param request 客户端请求
     * @param output 应答与推送
     * @return 本功能是否已处理请求
     * @throws IOException 写入失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output) throws IOException {
        int type=request.type;
        if (type==3001 || type==3002) {
            if (!mines.active(role.id)) return false;
            Object targets=request.body.get("objs");
            ObjectNode result=mines.turn(role.id,(int)number(request.body,"sid",0),
                    targets instanceof List ? (List<?>)targets : Collections.emptyList(),(int)number(request.body,"flag",-1),type==3002);
            if (result!=null) {
                publish(result,output);
                if (result.path("outcome").asInt()==2 && type!=3002) output.push(1051,data());
            }
            return true;
        }
        if (type>=122 && type<=126 || type==129) {
            if (request.session<=0) return true;
            ObjectNode result=mines.act(role.id,type,(int)number(request.body,"idx",0),(int)number(request.body,"pos",0),
                    (int)number(request.body,"teamid",0),String.valueOf(request.body.get("miner")));
            output.send(request,body(result.path("response")));
            publish(result,output);
            return true;
        }
        switch(type) {
            case 8: case 66: case 68: case 69: case 102: case 105: case 109: case 121: case 127:
            case 168: case 169: case 181: case 182: case 268: case 272: case 273: case 285: case 300:
            case 2203: case 3037: case 5069: case 5071: case 5072: case 5101: case 10040:
                if (!mines.active(role.id)) return false;
                if (request.session>0) output.send(request,data("errno",2002));
                return true;
            default: return false;
        }
    }

    /** 将结构化状态转换为原 Sproto 字段。
     * @param node 服务端状态
     * @return 对应协议字段映射
     */
    private Map<String,Object> body(JsonNode node) {return json.convertValue(node,BODY);}

    /** 推送已提交的金币、占位和同步游标。
     * @param result 矿洞事务返回值
     * @param output 推送出口
     * @throws IOException 写入失败
     */
    private void publish(ObjectNode result,GameReply output) throws IOException {
        if (result.has("award")) {
            output.push(14,data("flags","mine.reward","adds",data("coins",result.path("award").asInt())));
            output.push(3,data("coins",result.path("coins").asLong()));
        }
        if (result.has("quit")) output.push(432,body(result.get("quit")));
        if (result.has("panel")) output.push(431,body(result.get("panel")));
        if (result.has("previous")) output.push(435,body(result.get("previous")));
        if (result.has("change")) output.push(435,body(result.get("change")));
    }
}
