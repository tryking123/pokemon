package com.pokemon.offline.gateway;

import com.pokemon.offline.activitydaily.DailyMissionService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.*;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原每日任务领奖及真实进度变化推送。
 * @author tongruiyuan
 * @since 2026-11-20 21:25
 */
@Service
@Order(-99)
final class DailyMissionGameFeature implements GameFeature {
    private final DailyMissionService missions;
    private final RoleService roles;
    private final InventoryGameFeature inventory;
    private final Map<GameReply,List<Map<String,Object>>> displayed=Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定任务及资产刷新服务。
     * @param missions 每日任务业务
     * @param roles 角色钱包
     * @param inventory 背包和邮件
     */
    DailyMissionGameFeature(DailyMissionService missions,RoleService roles,InventoryGameFeature inventory) {
        this.missions=missions;this.roles=roles;this.inventory=inventory;
    }

    /**
     * 处理原任务提交请求，保证重复领取只返回既有结果。
     * @param role 当前角色
     * @param request 原任务请求
     * @param output 原连接
     * @return 是否处理
     * @throws IOException 推送失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output) throws IOException {
        long id=number(request.body,"taskid",0);
        if(request.type!=1201 || id<40001 || id>40012)return false;
        if(request.session<=0)return true;
        int errno=missions.claim(role.id,(int)id);
        if(errno==0) {
            LocalRole saved=roles.findById(role.id).orElse(role);
            inventory.pushBag(saved,output);inventory.pushMailCount(saved,output);
            output.push(3,data("coins",saved.coins,"diamonds",saved.diamonds));
        }
        tick(role,output);
        output.send(request,data("errno",errno,"taskid",id));
        return true;
    }

    /**
     * 同一连接按真实行为或换日变化刷新原任务状态。
     * @param role 当前角色
     * @param output 原连接
     * @throws IOException 推送失败
     */
    @Override
    public void tick(LocalRole role,GameReply output) throws IOException {
        List<Map<String,Object>> state=missions.snapshot(role.id);
        if(!state.equals(displayed.get(output))) {
            for(Map<String,Object> task:state)output.push(101,task);
            displayed.put(output,state);
        }
    }
}
