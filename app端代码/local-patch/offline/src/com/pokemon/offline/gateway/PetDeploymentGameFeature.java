package com.pokemon.offline.gateway;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.home.BreedingService;
import com.pokemon.offline.home.PetDeploymentService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.platform.stereotype.Service;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 普通宠物队伍原协议，登录恢复出战并刷新原生定制后的宠物数据。
 * @author tongruiyuan
 * @since 2026-11-22 03:55
 */
@Service
final class PetDeploymentGameFeature implements GameFeature {
    private static final TypeReference<Map<String,Object>> BODY=new TypeReference<Map<String,Object>>(){};
    private final PetDeploymentService deployment;
    private final BreedingService breeding;
    private final ObjectMapper json;
    private final Map<GameReply,String> connections=Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,String> last=Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定出战和定制后状态同步。
     * @param deployment 真实队伍映射
     * @param breeding 当前宠物数据
     * @param json 协议转换器
     */
    PetDeploymentGameFeature(PetDeploymentService deployment,BreedingService breeding,ObjectMapper json) {
        this.deployment=deployment;this.breeding=breeding;this.json=json;
    }

    /**
     * 登录时恢复普通出战队伍。
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 消息生成失败
     */
    @Override public void synchronize(LocalRole role,GameReply output)throws IOException {
        output.push(2048,data("battle_info",json.convertValue(deployment.teams(role.id),java.util.List.class)));
        last.put(output,breeding.pets(role.id).toString());
    }

    /**
     * 定制面板返回后推送当前宠物的新配置。
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 消息生成失败
     */
    @Override public void tick(LocalRole role,GameReply output)throws IOException {
        String current=breeding.pets(role.id).toString();
        if(!current.equals(last.get(output))) {
            HomeGameFeature.pushPets(breeding,json,role.id,output);
            last.put(output,current);
        }
    }

    /**
     * 处理原普通队伍出战与一键下阵，保持专属游戏厅路由。
     * @param role 当前角色
     * @param request 原出战请求
     * @param output 当前连接
     * @return 是否处理请求
     * @throws IOException 消息生成失败
     */
    @Override public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output)throws IOException {
        long mode=number(request.body,"itype",-1);
        if(request.type!=10305 && request.type!=10306 || mode!=PetDeploymentService.NORMAL
                && !(request.type==10306 && mode==PetDeploymentService.ALL))return false;
        if(request.session<=0)return true;
        String connection=connections.computeIfAbsent(output,k->UUID.randomUUID().toString());
        long team=number(request.body,"teamid",0);
        Object uid=request.body.get("id");
        ObjectNode result=deployment.change(role.id,request.type,uid instanceof String?(String)uid:null,
                mode,team>=0 && team<=3?(int)team:-1,connection+":"+request.session);
        output.send(request,json.convertValue(result,BODY));
        if(result.path("errno").asInt()==0) {
            HomeGameFeature.pushPets(breeding,json,role.id,output);
            synchronize(role,output);
        }
        return true;
    }
}
