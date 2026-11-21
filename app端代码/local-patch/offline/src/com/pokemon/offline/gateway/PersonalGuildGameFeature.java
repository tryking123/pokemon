package com.pokemon.offline.gateway;

import com.pokemon.offline.guild.PersonalGuildService;
import com.pokemon.offline.guild.api.entity.LocalGuild;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.RoleBattlePowerService;
import com.pokemon.offline.role.RoleCosmeticService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;
import static com.pokemon.offline.guild.PersonalGuildService.OK;
import static com.pokemon.offline.guild.PersonalGuildService.UNAVAILABLE;

/**
 * 原客户端个人公会面板、会长管理及贡献商城协议。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:12
 */
@Service
final class PersonalGuildGameFeature implements GameFeature {
    private static final int CHAIR = 1;
    private final PersonalGuildService guilds;
    private final RoleService roles;
    private final RoleBattlePowerService power;
    private final RoleCosmeticService cosmetics;
    private final GamePaymentService payments;
    private final InventoryGameFeature inventory;
    private final BattleGameFeature battle;
    /**
     * 先同步考古结算与权威背包，再应答触发原界面重绘；重放不重复触发奖励动画。
     * @param role 当前角色
     * @param request 藏宝图或探测器请求
     * @param output 原协议通道
     * @throws IOException 原协议写出失败
     */
    private void explore(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long item = number(request.body, "itemid", 0);
        com.fasterxml.jackson.databind.node.ObjectNode result = guilds.explore(role.id, request.type == 402,
                item, receipt(request, output));
        int errno = result.path("errno").asInt();
        if (result.has("reward") && !result.path("replayed").asBoolean()) {
            output.push(300, data("flags", "antiquity.shovel_cost", "del", data("antiquity",
                    Collections.singletonList(data("type", 20, "id", item, "n", 1)))));
        }
        if (result.has("reward") && !result.path("replayed").asBoolean()) {
            output.push(14, data("flags", "antiquity.shovel_award", "adds", data("antiquity",
                    Collections.singletonList(data("type", 20, "id", result.path("reward").asInt(),
                            "n", result.path("quantity").asInt()))), "loses", data()));
        }
        Map<String, Object> body = data("errno", errno, "itemid", item);
        if (result.has("length")) {
            body.put("length", result.path("length").asInt());
        }
        if (result.has("mapid")) {
            body.put("mapid", result.path("mapid").asInt());
        }
        output.push(465, guilds.antiquities(role.id));
        output.send(request, body);
    }

    /**
     * 推送结算后的原石板、徽章及化石状态。
     * @param role 已认证角色
     * @param request 原客户端请求
     * @param output 协议应答通道
     * @throws IOException 写入原协议失败
     */
    private void study(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long index = number(request.body, "idx", 0);
        int errno = guilds.study(role.id, request.type, index, receipt(request, output));
        output.send(request, request.type == 405 ? data("errno", errno) : data("errno", errno, "idx", index));
        if (errno == OK) {
            int protocol = request.type <= 405 ? 466 : request.type == 406 ? 468 : 469;
            output.push(protocol, guilds.researchSnapshot(role.id, protocol));
            output.push(465, guilds.antiquities(role.id));
            battle.pushElfs(role.id, output);
            output.push(3, data("battlepower", power.score(role.id)));
            if (request.type == 405) {
                pushGuild(role, guilds.active(role.id), output);
            }
            pushWallet(role, output);
        }
    }

    private final Map<GameReply, String> connections = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 注入公会与原客户端成员和奖励需要的本地数据。
     *
     * @param guilds 公会业务
     * @param roles 角色查询
     * @param power 战力数据
     * @param cosmetics 角色时装数据
     * @param payments 角色 VIP 数据
     * @param inventory 道具背包和邮件同步
     * @param battle 精灵研究后的状态同步
     */
    PersonalGuildGameFeature(PersonalGuildService guilds, RoleService roles, RoleBattlePowerService power,
                             RoleCosmeticService cosmetics, GamePaymentService payments,
                             InventoryGameFeature inventory, BattleGameFeature battle) {
        this.battle = battle;
        this.guilds = guilds;
        this.roles = roles;
        this.power = power;
        this.cosmetics = cosmetics;
        this.payments = payments;
        this.inventory = inventory;
    }

    /**
     * 从原协议入口读写当前角色自己的公会。
     *
     * @param role 已认证角色
     * @param request 客户端协议请求
     * @param output 应答及推送通道
     * @return 已处理自己负责的协议时为真
     * @throws IOException 写入客户端失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return false;
        }
        switch (request.type) {
            case 50:
            case 1401:
                create(role, request, output);
                return true;
            case 51:
            case 52:
            case 54:
            case 55:
            case 56:
            case 58:
                // 个人公会只有本角色一位会长，没有其他成员或入会申请。
                output.send(request, data("errno", UNAVAILABLE));
                return true;
            case 53:
            case 59:
            case 60:
            case 61:
            case 63:
            case 64:
            case 1402:
                query(role, request, output);
                return true;
            case 57:
            case 62:
            case 65:
            case 67:
            case 1403:
                update(role, request, output);
                return true;
            case 402:
            case 403:
                explore(role, request, output);
                return true;
            case 404:
            case 405:
            case 406:
            case 409:
            case 410:
                study(role, request, output);
                return true;
            case 2305:
                buy(role, request, output);
                return true;
            default:
                return false;
        }
    }

    /**
     * 推送本人公会成员状态、贡献记录和商店库存计数。
     *
     * @param role 已认证角色
     * @param output 角色状态推送通道
     * @throws IOException 写入客户端失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        pushGuild(role, guilds.active(role.id), output);
        output.push(704, guilds.shop(role.id));
        output.push(465, guilds.antiquities(role.id));
        if (role.level >= 50 && guilds.active(role.id) != null) {
            for (int protocol : new int[]{466, 468, 469}) {
                output.push(protocol, guilds.researchSnapshot(role.id, protocol));
            }
        }
    }

    /**
     * 在公会副本活跃结算后刷新完整公会资料。
     * @param role 已认证角色
     * @param output 原协议推送通道
     * @throws IOException 消息发送失败
     */
    void synchronizeGuild(LocalRole role, GameReply output) throws IOException {
        pushGuild(role, guilds.active(role.id), output);
    }

    /**
     * 同时接受原建会协议和新建会别名。
     *
     * @param role 当前角色
     * @param request 建会请求
     * @param output 应答及推送通道
     * @throws IOException 写入客户端失败
     */
    private void create(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String name = text(request, "name");
        String intro = text(request, request.type == 50 ? "content" : "intro");
        int errno = guilds.create(role.id, name, intro, receipt(request, output));
        if (errno == OK) {
            // 原创建回调会立即切换面板，先让面板拿到真实公会快照。
            pushGuild(role, guilds.active(role.id), output);
            pushWallet(role, output);
        }
        output.send(request, request.type == 50 ? data("errno", errno, "flag", false) : data("errno", errno));
    }

    /**
     * 读取个人公会资料、成员、日志和不同版本的列表。
     *
     * @param role 当前角色
     * @param request 查询请求
     * @param output 协议应答通道
     * @throws IOException 写入客户端失败
     */
    private void query(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        LocalGuild guild = guilds.active(role.id);
        switch (request.type) {
            case 61:
                output.send(request, data("errno", OK, "now_page", 1, "page", 1,
                        "list", guild == null ? Collections.emptyList() : Collections.singletonList(info(role, guild))));
                return;
            case 1402:
                long offset = number(request.body, "offset", 0);
                long limit = number(request.body, "limit", 1);
                if (offset < 0 || limit < 0) {
                    output.send(request, data("errno", UNAVAILABLE));
                } else {
                    boolean visible = guild != null && offset == 0 && limit > 0;
                    output.send(request, data("errno", OK, "unions", visible
                            ? Collections.singletonList(summary(role, guild)) : Collections.emptyList()));
                }
                return;
            default:
                if (guild == null) {
                    output.send(request, data("errno", UNAVAILABLE));
                    return;
                }
        }
        switch (request.type) {
            case 53:
                output.send(request, data("errno", OK, "list", Collections.emptyList(), "auto", guild.autoAudit));
                break;
            case 59:
                String id = text(request, "guild_id");
                if (id.isEmpty() || guild.id.equals(id)) {
                    output.send(request, data("errno", OK, "info", info(role, guild)));
                } else {
                    output.send(request, data("errno", UNAVAILABLE));
                }
                break;
            case 60:
                long cursor = number(request.body, "cursor", 0);
                output.send(request, data("errno", OK, "cursor", 0,
                        "list", cursor <= 0 ? Collections.singletonList(member(role, guild)) : Collections.emptyList()));
                break;
            case 63:
                List<Map<String, Object>> logs = guilds.history(guild).stream().map(log -> data("time", log.get("time"),
                        "context", logText(role, (String) log.get("action")))).collect(Collectors.toList());
                output.send(request, data("errno", OK, "logs", logs));
                break;
            case 64:
                if (guild.name.equalsIgnoreCase(text(request, "name").trim())) {
                    output.send(request, data("errno", OK, "info", info(role, guild)));
                } else {
                    output.send(request, data("errno", UNAVAILABLE));
                }
                break;
            default:
                throw new IllegalStateException("未知公会查询");
        }
    }

    /**
     * 将会长操作或金币捐献提交至对应本地事务。
     *
     * @param role 当前角色
     * @param request 修改请求
     * @param output 应答及推送通道
     * @throws IOException 写入客户端失败
     */
    private void update(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String receipt = receipt(request, output);
        int errno;
        switch (request.type) {
            case 57:
                errno = guilds.leave(role.id, receipt);
                break;
            case 62:
            case 1403:
                boolean intro = request.type == 1403;
                errno = guilds.changeText(role.id, text(request, intro ? "intro" : "content"), intro, receipt);
                break;
            case 65:
                errno = guilds.donate(role.id, text(request, "d_type"), receipt);
                break;
            case 67:
                errno = guilds.changeAudit(role.id, Boolean.TRUE.equals(request.body.get("auto")), receipt);
                break;
            default:
                throw new IllegalStateException("未知公会修改请求");
        }
        LocalGuild saved = guilds.active(role.id);
        output.send(request, request.type == 67 ? data("errno", errno, "auto", saved != null && saved.autoAudit)
                : data("errno", errno));
        if (errno == OK || request.type == 65) {
            // 避免原版成功回调按旧捐献数值本地累加后覆盖真实数据。
            pushGuild(role, saved, output);
            if (request.type == 65) {
                pushWallet(role, output);
            }
        }
    }

    /**
     * 结算贡献商店，并同时刷新背包、邮件和贡献显示。
     *
     * @param role 当前角色
     * @param request 购买请求
     * @param output 应答及推送通道
     * @throws IOException 写入客户端失败
     */
    private void buy(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Map<String, Object> settled = guilds.buy(role.id, number(request.body, "index", 0),
                number(request.body, "num", 0), receipt(request, output));
        output.push(704, guilds.shop(role.id));
        pushWallet(role, output);
        if (number(settled, "errno", UNAVAILABLE) == OK) {
            pushGuild(role, guilds.active(role.id), output);
            if (number(settled, "itemtype", 0) == 20) {
                output.push(465, guilds.antiquities(role.id));
            } else {
                inventory.pushBag(role, output);
                inventory.pushMailCount(role, output);
            }
        }
        output.send(request, settled);
    }

    /**
     * 按原客户端的两条消息同步公会及捐献次数。
     *
     * @param role 当前角色
     * @param guild 本人公会，无公会时为空
     * @param output 推送通道
     * @throws IOException 写入客户端失败
     */
    private void pushGuild(LocalRole role, LocalGuild guild, GameReply output) throws IOException {
        output.push(55, data("coins", guilds.donationsToday(role.id, "coins"),
                "diamonds", guilds.donationsToday(role.id, "diamonds"), "reset_tm", guilds.donationReset()));
        if (guild == null) {
            // Lua 中空字符串仍为真，省略 guild 字段才表示无公会。
            output.push(50, data("duty", 0, "apply", Collections.emptyList(), "contribute", guilds.contribution(role.id)));
        } else {
            output.push(50, data("guild", guild.id, "apply", Collections.emptyList(), "duty", CHAIR, "info", info(role, guild),
                    "tm", guild.createdAt, "contribute", guilds.contribution(role.id)));
        }
    }

    /**
     * 更新原客户端资产栏上的金币、钻石和贡献。
     *
     * @param role 当前角色
     * @param output 推送通道
     * @throws IOException 写入客户端失败
     */
    private void pushWallet(LocalRole role, GameReply output) throws IOException {
        LocalRole saved = roles.findById(role.id).orElseThrow(() -> new IllegalStateException("角色存档不存在"));
        output.push(3, data("coins", saved.coins, "diamonds", saved.diamonds, "union_degree", guilds.contribution(role.id)));
    }

    /**
     * 把本地个人公会转成原主面板资料。
     *
     * @param role 当前会长
     * @param guild 本人公会
     * @return 原游戏公会资料
     */
    private Map<String, Object> info(LocalRole role, LocalGuild guild) {
        return data("sid", guild.id, "now_num", 1, "sum_num", 1, "chairman", role.name, "g_lv", guilds.level(guild),
                "g_name", guild.name, "overbuild", guilds.levelProgress(guild), "notice", guild.notice,
                "vitality", guilds.guildVitality(guild), "integral", guild.xp, "unnamed", guild.intro, "apply", false);
    }

    /**
     * 将公会资料转为新协议列表中的条目。
     *
     * @param role 当前会长
     * @param guild 本人公会
     * @return 新列表协议使用的条目
     */
    private Map<String, Object> summary(LocalRole role, LocalGuild guild) {
        return data("id", guild.id, "name", guild.name, "intro", guild.intro, "score", guild.xp,
                "cur_size", 1, "max_size", 1, "owner_name", role.name);
    }

    /**
     * 构建真实会长的成员资料和贡献。
     *
     * @param role 当前会长
     * @param guild 本人公会
     * @return 原成员列表使用的角色条目
     */
    private Map<String, Object> member(LocalRole role, LocalGuild guild) {
        return data("uid", role.id, "name", role.name, "power", power.score(role.id), "duty", CHAIR, "level", role.level,
                "vip", payments.vip(role.id).level, "sex", role.sex, "dress", cosmetics.wornDress(role.id),
                "contribute", guilds.contribution(role.id), "off_line", 0, "state", 1, "advintegral", 0,
                "history_contribute", guilds.totalContribution(role.id), "icon", role.avatar, "score", guild.xp);
    }

    /**
     * 用原日志控件支持的自由文本渲染真实流水。
     *
     * @param role 当前会长
     * @param action 已提交的操作类型
     * @return 供客户端展示的日志文字
     */
    private String logText(LocalRole role, String action) {
        switch (action) {
            case "CREATE": return role.name + "创建了公会";
            case "INTRO": return role.name + "修改了公会简介";
            case "NOTICE": return role.name + "修改了公会公告";
            case "AUDIT": return role.name + "设置了审批方式";
            case "DONATE": return role.name + "完成了金币捐献";
            case "LEAVE": return role.name + "离开了公会";
            default: throw new IllegalStateException("未知公会日志类型");
        }
    }

    /**
     * 安全读取 Sproto 字符串字段。
     *
     * @param request 原协议请求
     * @param field 字段名称
     * @return 字段文本，未传时为空文本
     */
    private String text(SprotoProtocol.Request request, String field) {
        Object value = request.body.get(field);
        return value instanceof String ? (String) value : "";
    }

    /**
     * 为同一连接与回包位的重复请求生成一致回执。
     *
     * @param request 原协议请求
     * @param output 当前连接应答出口
     * @return 按连接、协议和请求位命名的回执
     */
    private String receipt(SprotoProtocol.Request request, GameReply output) {
        return connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString())
                + ":" + request.type + ":" + request.session;
    }
}
