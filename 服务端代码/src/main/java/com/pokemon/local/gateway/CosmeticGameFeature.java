package com.pokemon.local.gateway;

import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleBattlePowerService;
import com.pokemon.local.role.api.entity.LocalRole;
import com.pokemon.local.role.api.entity.RoleCosmeticState;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 原客户端称号与时装协议，基于本地角色存档应答。
 *
 * @author tongruiyuan
 * @since 2026-11-19 13:38
 */
@Service
final class CosmeticGameFeature implements GameFeature {
    private final com.pokemon.local.home.HomeRaceService races;
    private final RoleCosmeticService cosmetics;
    private final RoleBattlePowerService battlePower;
    private final Map<GameReply, String> connections = new WeakHashMap<>();

    /**
     * 绑定本地外观规则和角色战力。
     *
     * @param races 自行车赛时装碎片存档
     * @param cosmetics 角色外观业务
     * @param battlePower 战力应答数据
     */
    CosmeticGameFeature(com.pokemon.local.home.HomeRaceService races, RoleCosmeticService cosmetics, RoleBattlePowerService battlePower) {
        this.races = races;
        this.cosmetics = cosmetics;
        this.battlePower = battlePower;
    }

    /**
     * 响应原称号佩戴、展示及时装穿戴等请求。
     *
     * @param role 当前已认证角色
     * @param request 原客户端请求
     * @param output 原协议回复出口
     * @return 接口是否由本功能处理
     * @throws IOException 写入协议失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return false;
        }
        switch (request.type) {
            case 2001:
            case 2002:
                title(role, request, output);
                return true;
            case 3450:
            case 3451:
            case 3452:
                dress(role, request, output);
                return true;
            default:
                return false;
        }
    }

    /**
     * 按原客户端所区分的佩戴或展示栏切换称号。
     *
     * @param role 当前角色
     * @param request 原请求
     * @param output 原协议回复出口
     * @throws IOException 写入协议失败
     */
    private void title(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        boolean display = request.type == 2002;
        int id = field(request, "id");
        int errno = cosmetics.changeTitle(role.id, id, display, requestId(request, output));
        RoleCosmeticState saved = cosmetics.state(role.id);
        output.send(request, data("errno", errno, "titles", titleList(saved)));
        if (display && errno == 0) {
            output.push(3, data("titleid", saved.displayedTitle));
        }
    }

    /**
     * 保存穿脱时装或返回已拥有的旧购买、合成入口结果。
     *
     * @param role 当前角色
     * @param request 原请求
     * @param output 原协议回复出口
     * @throws IOException 写入协议失败
     */
    private void dress(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int errno;
        if (request.type == 3450) {
            errno = cosmetics.ownedForShop(field(request, "posid"));
        } else if (request.type == 3452) {
            errno = cosmetics.ownedForChip(field(request, "chipid"));
        } else {
            int mode = field(request, "ntype");
            errno = mode == 0 || mode == 1 ? cosmetics.changeDress(role.id, field(request, "dress_id"),
                    mode == 1, requestId(request, output)) : 3;
        }
        output.send(request, data("errno", errno));
        if (errno == 0) {
            pushDress(role.id, output);
            if (request.type == 3451) {
                pushSceneDress(role.id, output);
                output.push(3, data("battlepower", battlePower.score(role.id)));
            }
        }
    }

    /**
     * 读取角色当前称号和时装，为旧客户端提供本地快照。
     *
     * @param role 当前角色
     * @param output 原协议回复出口
     * @throws IOException 写入协议失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        RoleCosmeticState saved = cosmetics.state(role.id);
        output.push(350, data("titles", titleList(saved)));
        pushDress(role.id, output);
        if (saved.wornDress != 0) {
            pushSceneDress(role.id, output);
        }
    }

    /**
     * 构造原客户端称号拥有和选择状态。
     *
     * @param roleId 当前角色编号
     * @return 所有已拥有称号的原协议列表
     */
    private List<Map<String, Object>> titleList(RoleCosmeticState state) {
        List<Map<String, Object>> titles = new ArrayList<>();
        for (int id : cosmetics.ownedTitles()) {
            titles.add(data("id", id, "state", id == state.equippedTitle ? 3 : 2, "extend", 0, "life", 0,
                    "display", id == state.displayedTitle));
        }
        return titles;
    }

    /**
     * 发送原时装页面和角色外观共用的本地镜像字段。
     *
     * @param state 已持久化的当前选择
     * @param output 原协议回复出口
     * @throws IOException 写入协议失败
     */
    void pushDress(String roleId, GameReply output) throws IOException {
        java.util.List<Map<String,Object>> chips=new java.util.ArrayList<>();
        races.chips(roleId).forEach(row->chips.add(data("id",row.path("id").asInt(),"n",row.path("n").asLong())));
        output.push(850, data("owned", cosmetics.ownedDresses(), "carry", cosmetics.wornDress(roleId),
                "universal", 0, "chipbag", data("use", chips.size(), "capacity", chips.size(), "items", chips)));
    }

    /**
     * 更新原地图中玩家自己的场景模型。
     *
     * @param roleId 当前角色编号
     * @param output 当前场景连接的推送出口
     * @throws IOException 推送失败
     */
    private void pushSceneDress(String roleId, GameReply output) throws IOException {
        output.push(7, data("id", output.actorSession(), "props", data("dressid", cosmetics.wornDress(roleId))));
    }

    /**
     * 严格读取非负协议编号，越界或缺失返回无效编号。
     *
     * @param request 原请求
     * @param name 协议字段名称
     * @return 提交的编号或无效编号
     */
    private static int field(SprotoProtocol.Request request, String name) {
        long value = number(request.body, name, -1);
        return value >= 0 && value <= Integer.MAX_VALUE ? (int) value : -1;
    }

    /**
     * 生成同个连接内的持久操作回执键。
     *
     * @param request 原请求类型和流水
     * @param output 连接的回复出口
     * @return 操作回执唯一键
     */
    private String requestId(SprotoProtocol.Request request, GameReply output) {
        synchronized (connections) {
            String connectionId = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString());
            return connectionId + ":" + request.type + ":" + request.session;
        }
    }
}
