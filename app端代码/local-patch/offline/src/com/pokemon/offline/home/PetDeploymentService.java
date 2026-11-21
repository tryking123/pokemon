package com.pokemon.offline.home;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 普通队伍宠物出战 Service，维护队伍唯一归属和原宠物占用标记。
 * @author tongruiyuan
 * @since 2026-11-22 03:55
 */
@Service
public final class PetDeploymentService {
    /** 原客户端普通队伍标记。 */
    public static final long NORMAL=10;
    /** 原客户端一键下阵标记。 */
    public static final long ALL=4294967295L;
    private final RoleService roles;
    private final HomeService home;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    /**
     * 绑定宠物归属、存档及操作回执。
     * @param roles 角色锁
     * @param home 原操作回执
     * @param jdbc 私有数据库
     * @param json 存档解析器
     */
    public PetDeploymentService(RoleService roles,HomeService home,JdbcTemplate jdbc,ObjectMapper json) {
        this.roles=roles;this.home=home;this.jdbc=jdbc;this.json=json;
    }

    /**
     * 查询普通队伍宠物映射，供登录及状态刷新使用。
     * @param roleId 角色编号
     * @return 原 S2048 队伍映射
     */
    public ArrayNode teams(String roleId) {
        ArrayNode result=json.createArrayNode();
        state(roleId).path("petTeams").path("10").fields().forEachRemaining(entry->{
            result.addObject().put("id",entry.getValue().asText()).put("teamid",Integer.parseInt(entry.getKey()));
        });
        return result;
    }

    /**
     * 将普通宠物移至指定队伍，或按原协议解除普通及全部出战占用。
     * @param roleId 当前角色
     * @param command 出战或下阵协议号
     * @param uid 宠物实例
     * @param mode 原出战场景标记
     * @param team 目标普通队伍
     * @param receipt 同一连接请求回执
     * @return 原协议应答
     */
    @Transactional
    public ObjectNode change(String roleId,int command,String uid,long mode,int team,String receipt) {
        LocalRole role=roles.lockRole(roleId);
        ObjectNode signature=json.createObjectNode().put("command",command).put("uid",uid).put("mode",mode).put("team",team);
        String key="pet-deploy:"+receipt,prior=home.reply(roleId,key);
        if(prior!=null) {
            JsonNode saved=parse(prior);
            return signature.equals(saved.path("request"))?(ObjectNode)saved.path("reply").deepCopy():failure();
        }
        ObjectNode state=state(roleId);
        JsonNode selected=uid==null?null:state.path("pets").get(uid);
        if(selected==null || !selected.isObject() || command!=10305 && command!=10306)return failure();
        ObjectNode pet=(ObjectNode)selected,reply=json.createObjectNode().put("errno",0);
        ObjectNode teams=state.with("petTeams").with("10");
        if(command==10305) {
            if(mode!=NORMAL || team<1 || team>Math.max(1,Math.min(3,role.stage+1)))return failure();
            String previous=teams.path(Integer.toString(team)).asText();
            remove(teams,uid);
            if(!previous.isEmpty() && !previous.equals(uid)) {
                JsonNode old=state.path("pets").get(previous);
                if(old!=null && old.isObject())removeStatus((ObjectNode)old,NORMAL,false);
                reply.put("pet_uid",previous);
            }
            teams.put(Integer.toString(team),uid);
            ArrayNode statuses=pet.withArray("status");
            boolean found=false;for(JsonNode status:statuses)if(status.asLong()==NORMAL)found=true;
            if(!found)statuses.add(NORMAL);
            reply.put("id",uid).put("teamid",team);
        } else {
            if(mode!=NORMAL && mode!=ALL)return failure();
            ArrayNode released=json.createArrayNode();
            for(JsonNode status:pet.path("status")) {
                long value=status.asLong();
                if(value==NORMAL || mode==ALL && value>=10 && value<=14)released.add(value);
            }
            remove(teams,uid);
            if(mode==ALL) {
                for(JsonNode mapping:state.path("petTeams"))if(mapping.isObject())remove((ObjectNode)mapping,uid);
                clearCasino(roleId,uid);
            }
            removeStatus(pet,mode,mode==ALL);
            reply.set("status",released);
        }
        jdbc.update("MERGE INTO local_home_breeding(role_id,state_json) KEY(role_id) VALUES(?,?)",roleId,state.toString());
        ObjectNode saved=json.createObjectNode();saved.set("request",signature);saved.set("reply",reply.deepCopy());
        home.record(roleId,key,saved.toString());
        return reply;
    }

    /**
     * 移除同一宠物在指定映射中的全部旧位置。
     * @param mapping 位置映射
     * @param uid 宠物编号
     */
    private void remove(ObjectNode mapping,String uid) {
        List<String> keys=new ArrayList<>();
        mapping.fields().forEachRemaining(entry->{if(uid.equals(entry.getValue().asText()))keys.add(entry.getKey());});
        mapping.remove(keys);
    }

    /**
     * 移除出战标记并保留生病、清洁等健康状态。
     * @param pet 当前宠物
     * @param mode 指定出战模式
     * @param all 是否清除全部出战占用
     */
    private void removeStatus(ObjectNode pet,long mode,boolean all) {
        ArrayNode statuses=pet.withArray("status");
        for(int i=statuses.size()-1;i>=0;i--) {
            long value=statuses.get(i).asLong();
            if(value==mode || all && value>=10 && value<=14)statuses.remove(i);
        }
    }

    /**
     * 一键下阵时同步清理同一宠物的游戏厅槽位。
     * @param roleId 当前角色
     * @param uid 宠物编号
     */
    private void clearCasino(String roleId,String uid) {
        List<String> rows=jdbc.queryForList("SELECT state_data FROM local_home_casino WHERE role_id=?",String.class,roleId);
        if(rows.isEmpty())return;
        ObjectNode casino=(ObjectNode)parse(rows.get(0));
        if(uid.equals(casino.path("pet").asText())) {
            casino.remove("pet");
            jdbc.update("UPDATE local_home_casino SET state_data=? WHERE role_id=?",casino.toString(),roleId);
        }
    }

    /**
     * 读取完整宠物存档。
     * @param roleId 当前角色
     * @return 宠物存档
     */
    private ObjectNode state(String roleId) {
        List<String> rows=jdbc.queryForList("SELECT state_json FROM local_home_breeding WHERE role_id=?",String.class,roleId);
        return rows.isEmpty()?json.createObjectNode():(ObjectNode)parse(rows.get(0));
    }

    /**
     * 解析宠物存档或回执。
     * @param value JSON 内容
     * @return 已解析数据
     */
    private JsonNode parse(String value) {
        try{return json.readTree(value);}catch(IOException error){throw new IllegalStateException("宠物出战存档损坏",error);}
    }

    /**
     * 返回明确的宠物操作参数错误。
     * @return 原协议失败结果
     */
    private ObjectNode failure() {return json.createObjectNode().put("errno",100021);}
}
