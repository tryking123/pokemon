package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.PassportService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.pokemon.offline.platform.stereotype.Service;
import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 通行证原协议和原任务面板的数据同步。
 * @author tongruiyuan
 * @since 2026-11-20 12:30
 */
@Service
final class PassportGameFeature implements GameFeature {
    private final PassportService passport;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final Map<GameReply, View> previews = Collections.synchronizedMap(new WeakHashMap<>());

    /** 每个已认证连接最后展示的轮次、任务快照和购买凭据前缀。 */
    private static final class View {
        final String connection = UUID.randomUUID().toString();
        long start;
        long next;
        Map<String,Object> panel = Collections.emptyMap();
        List<Map<String,Object>> tasks = Collections.emptyList();
    }

    /**
     * 连接等级奖励、赛季任务与角色资产。
     * @param passport 通行证业务
     * @param roles 角色钱包与等级
     * @param bags 背包与邮件快照
     */
    PassportGameFeature(PassportService passport, RoleService roles, BagSnapshot bags) {
        this.passport = passport;
        this.roles = roles;
        this.bags = bags;
    }

    /**
     * 读取可随 S100 同步的通行证任务。
     * @param role 角色
     * @return 当前任务列表
     */
    List<Map<String,Object>> currentTasks(LocalRole role) {
        return role.level < 30 ? Collections.emptyList() : passport.tasks(role.id, passport.start());
    }

    /**
     * 提交已经展示且达标的通行证任务。
     * @param role 角色
     * @param request C1201 请求
     * @param output 游戏连接
     * @param task 原客户端任务编号
     * @throws IOException 协议写入失败
     */
    void claimTask(LocalRole role,SprotoProtocol.Request request,GameReply output,int task) throws IOException {
        if (request.session <= 0) return;
        View view = previews.get(output);
        int exp = view == null ? -1 : passport.claimTask(role.id,task,view.start);
        output.send(request, data("errno", exp >= 0 ? 0 : 1, "taskid", task));
        if (exp > 0) output.push(14, data("adds", data("passexp", exp)));
        synchronize(role, output);
    }

    /**
     * 响应激活、领档、兑换和购买等级。
     * @param role 角色
     * @param request 原客户端请求
     * @param output 游戏连接
     * @return 是否处理该请求
     * @throws IOException 协议写入失败
     */
    @Override
    public boolean handle(LocalRole role,SprotoProtocol.Request request,GameReply output) throws IOException {
        int cmd = request.type;
        if (cmd < 227 || cmd > 230) return false;
        if (request.session <= 0) return true;
        View view = previews.get(output);
        String field = cmd == 228 ? "level" : cmd == 229 ? "idx" : "num";
        long value = number(request.body, field, 0);
        boolean all = Boolean.TRUE.equals(request.body.get("auto"));
        int errno = view == null ? 1 : passport.act(role.id, view.start, cmd, value, all, view.connection + ":" + request.session);
        Map<String,Object> response = data("errno", errno);
        if (cmd != 227) response.put(field, value);
        if (cmd == 228) response.put("auto", all);
        if (cmd == 230 && errno == 0) {
            Map<String,Object> state = passport.panel(role.id,view.start);
            response.put("level",state.get("level"));
            response.put("exp",state.get("exp"));
        }
        output.send(request, response);
        if (errno == 0) {
            LocalRole current = roles.findById(role.id).orElse(role);
            output.push(3, data("coins",current.coins,"diamonds",current.diamonds,"fashescoins",passport.fashionCoins(role.id)));
            bags.push(role.id, output);
            bags.pushMailCount(role.id, output);
        }
        synchronize(role, output);
        return true;
    }

    /**
     * 更新赛季和角色已变化的任务，首次连接时推送当前时装币。
     * @param role 当前角色
     * @param output 游戏连接
     * @throws IOException 协议写入失败
     */
    @Override
    public void synchronize(LocalRole role,GameReply output) throws IOException {
        if (role.level < 30) return;
        View view = previews.computeIfAbsent(output, ignored -> new View());
        long start = passport.start();
        if (view.start == 0) output.push(3,data("fashescoins",passport.fashionCoins(role.id)));
        Map<String,Object> panel = passport.panel(role.id,start);
        if (!panel.equals(view.panel)) output.push(804,panel);
        List<Map<String,Object>> tasks = passport.tasks(role.id,start);
        for (Map<String,Object> item : tasks) {
            if (!view.tasks.contains(item)) output.push(101,item);
        }
        view.start = start;
        view.panel = panel;
        view.tasks = tasks;
        view.next = Instant.now().getEpochSecond() + 20;
    }

    /**
     * 从已提交的充值和其他玩法动作更新通行证。
     * @param role 当前角色
     * @param output 游戏连接
     * @throws IOException 协议写入失败
     */
    @Override
    public void tick(LocalRole role,GameReply output) throws IOException {
        if (role.level < 30) return;
        View view = previews.get(output);
        if (view == null || view.next <= Instant.now().getEpochSecond() || view.start != passport.start()) {
            synchronize(role, output);
        }
    }
}
