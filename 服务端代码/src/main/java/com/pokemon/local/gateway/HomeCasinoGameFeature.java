package com.pokemon.local.gateway;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.local.home.BreedingService;
import com.pokemon.local.home.HomeCasinoService;
import com.pokemon.local.home.HomeService;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.api.entity.LocalRole;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * 家园游戏厅原协议与牌局、游戏币、任务、成宠同步。
 *
 * @author tongruiyuan
 * @since 2026-11-20 12:19
 */
@Service
final class HomeCasinoGameFeature implements GameFeature {
    private static final TypeReference<Map<String, Object>> BODY =
            new TypeReference<Map<String, Object>>() {};
    private final HomeCasinoService casino;
    private final BreedingService breeding;
    private final HomeService home;
    private final InventoryService inventory;
    private final ObjectMapper json;
    private final Map<GameReply, String> sessions =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply, String> rounds = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply, Long> days = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定内置服务端的真实家园状态和原协议编码。
     *
     * @param casino 牌局结算
     * @param breeding 宠物仓库
     * @param home 饲育屋等级
     * @param inventory 家园币余额
     * @param json 序列化器
     */
    HomeCasinoGameFeature(
            HomeCasinoService casino,
            BreedingService breeding,
            HomeService home,
            InventoryService inventory,
            ObjectMapper json) {
        this.casino = casino;
        this.breeding = breeding;
        this.home = home;
        this.inventory = inventory;
        this.json = json;
    }

    /**
     * 恢复钱包和已开放牌桌的原每日状态。
     *
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 连接失效
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        long day = (Instant.now().getEpochSecond() + 28800) / 86400;
        days.put(output, home.level(role.id) >= 5 ? day : -1L);
        if (home.level(role.id) < 5) return;
        wallet(role.id, output);
        output.push(462, json.convertValue(casino.panel(role.id), BODY));
        for (JsonNode task : casino.tasks(role.id)) output.push(101, json.convertValue(task, BODY));
    }

    /**
     * 在饲育屋升至五级或北京时间跨日时发布新的每日状态。
     *
     * @param role 当前角色
     * @param output 当前连接
     * @throws IOException 连接失效
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        long day = (Instant.now().getEpochSecond() + 28800) / 86400;
        if (days.getOrDefault(output, -1L) != day && home.level(role.id) >= 5)
            synchronize(role, output);
    }

    /**
     * 以实际家园币和独立游戏币更新玩家显示。
     *
     * @param role 角色编号
     * @param output 当前连接
     * @throws IOException 连接失效
     */
    private void wallet(String role, GameReply output) throws IOException {
        output.push(3, data("homecoin", inventory.homeCoins(role), "jetton", casino.jettons(role)));
    }

    /**
     * 按原回调顺序发送牌局、资产和成宠的变动。
     *
     * @param role 当前角色
     * @param request 原请求
     * @param output 当前连接
     * @return 当前模块是否处理
     * @throws IOException 连接失效
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output)
            throws IOException {
        int type = request.type;
        boolean pet = (type == 10305 || type == 10306) && number(request.body, "itype", 0) == 14;
        if (!pet && (type < 393 || type > 398)) return false;
        if (request.session <= 0) return true;
        String session = sessions.computeIfAbsent(output, k -> UUID.randomUUID().toString());
        long idx = number(request.body, "idx", -1),
                count = number(request.body, "count", 0),
                team = number(request.body, "teamid", 0);
        if (idx < -1
                || idx > Integer.MAX_VALUE
                || count < 0
                || count > Integer.MAX_VALUE
                || team < 0
                || team > Integer.MAX_VALUE) {
            output.send(request, data("errno", 100021));
            return true;
        }
        ObjectNode out =
                casino.handle(
                        role.id,
                        type,
                        (int) idx,
                        (int) count,
                        Boolean.TRUE.equals(request.body.get("quit")),
                        String.valueOf(request.body.getOrDefault("id", "")),
                        (int) team,
                        rounds.get(output),
                        "casino:" + session + ":" + type + ":" + request.session);
        JsonNode bound = out.remove("boundRound");
        if (bound != null) rounds.put(output, bound.asText());
        JsonNode adds = out.remove("adds"),
                loses = out.remove("loses"),
                box = out.remove("box"),
                tasks = out.remove("tasks"),
                taskCoins = out.remove("taskCoins");
        String flags =
                type == 396
                        ? "casino.settle"
                        : type == 397
                                ? "casino_box_reward"
                                : type == 398 ? "casino.buy" : "casino.enter";
        if (loses != null)
            output.push(14, data("flags", flags, "loses", json.convertValue(loses, BODY)));
        if (adds != null)
            output.push(14, data("flags", flags, "adds", json.convertValue(adds, BODY)));
        if (tasks != null)
            for (JsonNode task : tasks) output.push(101, json.convertValue(task, BODY));
        if (taskCoins != null)
            output.push(
                    14,
                    data(
                            "flags",
                            "task.task_submit",
                            "adds",
                            data("homecoin", taskCoins.asLong())));
        if (loses != null || adds != null || taskCoins != null) wallet(role.id, output);
        if ((pet && out.path("errno").asInt() == 0) || adds != null && adds.has("pet"))
            HomeGameFeature.pushPets(breeding, json, role.id, output);
        output.send(request, json.convertValue(out, BODY));
        if (box != null) output.push(463, json.convertValue(box, BODY));
        if ((pet || type == 393 || type == 396) && out.path("errno").asInt() == 0)
            output.push(462, json.convertValue(casino.panel(role.id), BODY));
        return true;
    }
}
