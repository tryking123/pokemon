package com.pokemon.offline.gateway;

import com.pokemon.offline.activitydaily.DailyActivityService;
import com.pokemon.offline.activitylevel.AdventureProgressService;
import com.pokemon.offline.gacha.GachaService;
import com.pokemon.offline.guild.PersonalGuildService;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.battle.BattleService;
import com.pokemon.offline.payment.GamePaymentService;
import com.pokemon.offline.role.RoleBattlePowerService;
import com.pokemon.offline.role.RoleCosmeticService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import com.pokemon.offline.scene.SceneService;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import com.pokemon.offline.platform.logging.Logger;
import com.pokemon.offline.platform.logging.LoggerFactory;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 本地区服登录、建角与首场景服务
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:49
 */
@Service
final class GameGatewayService {

    private static final Logger LOG = LoggerFactory.getLogger(GameGatewayService.class);
    private static final int LOCAL_REALM = 1;
    private static final int MAX_NAME_LENGTH = 24;
    private static final Pattern ROLE_NAME = Pattern.compile("[\\p{L}\\p{N}_-]+");

    private final GameTicketService tickets;
    private final RoleService roles;
    private final WorldGameFeature world;
    private final BattleService battles;
    private final RoleBattlePowerService power;
    private final AdventureProgressService adventure;
    private final HangupService hangup;
    private final com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.secret.SecretService> secret;
    private final GamePaymentService payments;
    private final GachaService gacha;
    private final RoleCosmeticService cosmetics;
    private final PersonalGuildService guilds;
    private final com.pokemon.offline.home.HomeService home;
    private final DailyActivityService daily;
    private final com.pokemon.offline.scene.DisconnectedBattleService disconnectedBattles;
    private final List<GameFeature> features;

    /**
     * 初始化本地区服业务
     *
     * @param tickets 本地票据服务
     * @param roles 本地角色服务
     * @param world 本地地图和任务适配器
     * @param battles 初始教学战存档
     * @param power 本地第一队战力计算
     * @param adventure 本地冒险关卡进度
     * @param hangup 冒险挂机收益服务
     * @param payments 本地已确认游戏订单及 VIP 数据
     * @param gacha 本地扭蛋和友情点余额
     * @param cosmetics 本地称号展示记录
     * @param guilds 个人公会贡献记录
     * @param home 家园心情余额
     * @param daily 本地体力余额
     * @param disconnectedBattles 重登时的中断战斗结算
     * @param secret 秘境余额
     * @param features 已启用的游戏功能
     */
    GameGatewayService(GameTicketService tickets, RoleService roles, WorldGameFeature world, BattleService battles,
                       RoleBattlePowerService power, AdventureProgressService adventure, HangupService hangup, com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.secret.SecretService> secret,
                       GamePaymentService payments, GachaService gacha, RoleCosmeticService cosmetics, PersonalGuildService guilds,
                       com.pokemon.offline.home.HomeService home, DailyActivityService daily,
                       com.pokemon.offline.scene.DisconnectedBattleService disconnectedBattles, List<GameFeature> features) {
        this.daily = daily;
        this.disconnectedBattles = disconnectedBattles;
        this.tickets = tickets;
        this.roles = roles;
        this.world = world;
        this.battles = battles;
        this.power = power;
        this.adventure = adventure;
        this.hangup = hangup;
        this.secret=secret;
        this.payments = payments;
        this.gacha = gacha;
        this.cosmetics = cosmetics;
        this.guilds = guilds;
        this.home=home;
        this.features = features;
    }

    /**
     * 分发已按原协议解析的本地入服请求
     *
     * @param connection 当前客户端连接状态
     * @param request 已解码请求
     * @param output 向连接发送原协议消息
     * @return 是否已处理该请求
     * @throws IOException 向连接写入响应失败
     */
    boolean handle(Connection connection, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
            case 1:
                login(connection, request, output);
                return true;
            case 2:
                Long ti = (Long) request.body.get("ti");
                output.send(request, ti == null ? data() : data("ti", ti));
                if (connection.ready) {
                    refreshGrowth(connection, output);
                    for (GameFeature feature : features) {
                        safeTick(feature, connection.role, output);
                    }
                }
                return true;
            case 3:
                register(connection, request, output);
                return true;
            case 4:
                ready(connection, output);
                return true;
            case 9:
                rename(connection, request, output);
                return true;
            case 11:
                restoreRole(connection, request, output);
                return true;
            case 8:
                if (number(request.body, "id", 0)
                        == com.pokemon.offline.scene.DisconnectedBattleService.ESCAPE_PORTAL) {
                    if (connection.role != null && connection.accountId != null) {
                        connection.role = disconnectedBattles.escape(connection.role.id);
                        output.send(request, data("errno", 0));
                        world.synchronizeScene(connection.role, output);
                    }
                    return true;
                }
                return connection.role != null && world.handle(connection.role, request, output);
            case 2505:
                if (connection.role != null && request.session > 0) {
                    output.send(request, noFriends());
                }
                return true;
            default:
                if (connection.role != null) {
                    for (GameFeature feature : features) {
                        if (feature.handle(connection.role, request, output)) {
                            return true;
                        }
                    }
                }
                return false;
        }
    }

    /**
     * 验证网关登录凭据并恢复已有角色
     *
     * @param connection 连接状态
     * @param request 原协议登录请求
     * @param output 原协议响应和推送输出
     * @throws IOException 输出失败
     */
    private void login(Connection connection, SprotoProtocol.Request request, GameReply output) throws IOException {
        connection.accountId = null;
        connection.role = null;
        connection.ready = false;
        long realm = number(request.body, "sid", -1);
        String account = (String) request.body.get("account");
        String token = (String) request.body.get("token");
        if (realm != LOCAL_REALM || account == null || token == null) {
            output.send(request, data("error", "400 Bad Request"));
            return;
        }
        if (!tickets.validate(account, token)) {
            output.send(request, data("error", "401 Unauthorized"));
            return;
        }

        connection.accountId = account;
        Optional<LocalRole> existing = roles.findRole(account, LOCAL_REALM);
        if (!existing.isPresent()) {
            output.send(request, data("error", "404 Not Found"));
            return;
        }
        pushStarterGuide(existing.get(), output);
        output.send(request, data());
        enterScene(connection, existing.get(), output);
    }

    /**
     * 在已验证的连接中重新加载本人的角色、货币与场景。
     *
     * @param connection 当前已登录连接
     * @param request 包含区号和角色编号的重进请求
     * @param output 本连接的场景与角色推送
     * @throws IOException 写入连接失败
     */
    private void restoreRole(Connection connection, SprotoProtocol.Request request, GameReply output)
            throws IOException {
        if (connection.role == null || connection.accountId == null
                || number(request.body, "sid", -1) != LOCAL_REALM
                || !connection.role.id.equals(request.body.get("uid"))) return;
        Optional<LocalRole> saved = roles.findRole(connection.accountId, LOCAL_REALM);
        if (saved.isPresent() && saved.get().id.equals(connection.role.id)) {
            pushStarterGuide(saved.get(), output);
            enterScene(connection, saved.get(), output);
        }
    }

    /**
     * 为通过网关鉴权的账号建立或读取本地区服角色
     *
     * @param connection 连接状态
     * @param request 原协议建角请求
     * @param output 原协议响应和推送输出
     * @throws IOException 输出失败
     */
    private void register(Connection connection, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (connection.accountId == null || request.session <= 0) {
            output.send(request, data("errno", 3));
            return;
        }

        String name = (String) request.body.get("name");
        long sex = number(request.body, "sex", -1);
        if (name == null || name.isEmpty() || name.length() > MAX_NAME_LENGTH || !ROLE_NAME.matcher(name).matches()) {
            output.send(request, data("errno", 1));
            return;
        }
        if (sex != 0 && sex != 1) {
            output.send(request, data("errno", 2));
            return;
        }
        Optional<LocalRole> role = roles.register(connection.accountId, LOCAL_REALM, name, (int) sex);
        if (!role.isPresent()) {
            output.send(request, data("errno", 4));
            return;
        }
        boolean entering = connection.role == null;
        if (entering) {
            pushStarterGuide(role.get(), output);
            prepareRole(connection, role.get(), output);
        }
        // 原创角成功回调会立即打开地图、导航与引导，必须先有角色和挂机快照。
        output.send(request, data("errno", 0));
        if (entering) {
            world.synchronizeScene(connection.role, output);
        }
    }

    /**
     * 在进入游戏通知之前让原 Lua 判断是否开始初始精灵引导
     *
     * @param role 本地角色存档
     * @param output 当前连接的推送通道
     * @throws IOException 推送失败
     */
    private void pushStarterGuide(LocalRole role, GameReply output) throws IOException {
        boolean begin = role.sceneId == SceneService.CITY && !battles.hasWonTutorial(role.id);
        output.push(2020, data("status", begin ? 0 : 1));
    }

    /**
     * 推送角色资料和出生场景，客户端加载后才能发送 ready
     *
     * @param connection 连接状态
     * @param role 已读取的角色
     * @param output 原协议响应和推送输出
     * @throws IOException 输出失败
     */
    private void enterScene(Connection connection, LocalRole role, GameReply output) throws IOException {
        prepareRole(connection, role, output);
        world.synchronizeScene(connection.role, output);
    }

    /**
     * 在创角成功回调之前准备角色和挂机快照，不提前触发场景切换。
     * @param connection 当前本地会话
     * @param role 已持久化角色
     * @param output 提交后派发的协议出口
     * @throws IOException 快照编码失败
     */
    private void prepareRole(Connection connection, LocalRole role, GameReply output) throws IOException {
        role = disconnectedBattles.restoreScene(role.id);
        connection.role = role;
        connection.ready = false;
        connection.battlePower = power.score(role.id);
        long now = Instant.now().getEpochSecond();
        output.push(3, data(
                "id", role.id, "name", role.name, "sex", role.sex, "level", role.level, "exp", role.exp,
                "coins", role.coins, "diamonds", role.diamonds, "vip", payments.vip(role.id).level, "createtime", role.createdAt,
                "titleid", cosmetics.displayedTitle(role.id), "tiredpower", daily.staminaBalance(role.id), "exp1", hangup.elfPool(role.id),
                "friendpoint", gacha.snapshot(role.id).friendPoints, "rename", role.renameCount,
                "pkpoint", 0, "union_degree", guilds.contribution(role.id), "universal", 0, "team", battles.defaultTeam(role.id), "svrid", role.realmId,
                "stage", role.stage, "challenge", secret.getIfAvailable().balance(role.id), "action", 0, "battlepower", connection.battlePower,
                "userid", Long.parseLong(role.accountId), "dojo_point", role.dojoPoints, "speed", 130,
                "opentime", role.createdAt, "lastlogin", now, "lastlogout", 0, "icon", role.avatar,
                "fashescoins", 0, "mood", home.mood(role.id), "homecoin", 0, "jetton", 0, "bindingID", "",
                "country", "CN", "limit_voucher", 0, "real_voucher", 0,
                "timezone", 8, "session", connection.id, "servertime", now));

        // 地图界面启动时会读挂机快照，须在首张 scene 触发地图加载前同步。
        int mission = adventure.nextMission(role.id);
        HangupGameFeature.pushSnapshot(output, mission, hangup, role.id, Collections.emptyList());
    }

    /**
     * 将已提交的新名称和扣费后的金额同步至当前连接。
     *
     * @param connection 已登录的游戏连接
     * @param request 原改名请求
     * @param output 当前连接的协议应答
     * @throws IOException 输出失败
     */
    private void rename(Connection connection, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return;
        }
        if (connection.role == null || number(request.body, "ntype", 1) != 1) {
            output.send(request, data("errno", 3));
            return;
        }
        int errno = roles.rename(connection.role.id, (String) request.body.get("new_name"));
        if (errno == 0) {
            connection.role = roles.findById(connection.role.id).orElse(connection.role);
            LocalRole saved = connection.role;
            output.push(3, data("name", saved.name, "rename", saved.renameCount, "diamonds", saved.diamonds));
        }
        output.send(request, data("errno", errno));
    }

    /**
     * 把已提交的角色等级、经验和队伍战力同步到原界面
     *
     * @param connection 当前游戏连接
     * @param output 协议推送通道
     * @throws IOException 推送失败
     */
    private void refreshGrowth(Connection connection, GameReply output) throws IOException {
        LocalRole previous = connection.role;
        LocalRole latest = roles.findById(previous.id).orElse(previous);
        long latestPower = power.score(latest.id);
        if (latest.level != previous.level || latest.exp != previous.exp || latestPower != connection.battlePower) {
            output.push(3, data("level", latest.level, "exp", latest.exp, "battlepower", latestPower));
        }
        connection.role = latest;
        connection.battlePower = latestPower;
    }

    /**
     * 接收场景加载通知并同步初始容器状态
     *
     * @param connection 连接状态
     * @param output 原协议响应和推送输出
     * @throws IOException 输出失败
     */
    private void ready(Connection connection, GameReply output) throws IOException {
        if (connection.role == null || connection.ready) {
            return;
        }
        connection.ready = true;
        LocalRole role = connection.role;
        output.push(20, data("id", role.sceneId, "pos", data("x", role.x, "y", role.y)));
        output.push(18, data("times", 0, "nexttime", 0));
        output.push(19, data("teaching_id", Collections.emptyList()));
        // 原任务模型依赖 S100 建立索引，所有玩法的 S101 增量必须在全量快照之后。
        world.synchronize(role, output);
        for (GameFeature feature : features) {
            if (feature != world) {
                safeSynchronize(feature, role, output);
            }
        }
    }

    /**
     * 返回本地单区初始的空好友资料
     *
     * @return 空好友列表、申请列表及友情点记录
     */
    private Map<String, Object> noFriends() {
        return data("errno", 0, "friend", Collections.emptyList(), "apply", Collections.emptyList(),
                "give", Collections.emptyList(), "get", 0, "un_get", Collections.emptyList(),
                "recommend", Collections.emptyList());
    }

    /**
     * 判断当前连接是否需要进入到账推送事务，空闲时只检查内存标记。
     *
     * @param connection 当前连接
     * @return 角色已就绪且仍在结算推送窗口内
     */
    boolean needsPump(Connection connection) {
        LocalRole role = connection.role;
        return role != null && connection.ready
                && com.pokemon.offline.common.SettledSignals.active(role.id);
    }

    /**
     * 在网关共享事务内检查刚结算的角色，提交后推送盲盒库存与充值到账。
     *
     * @param connection 当前连接
     * @param output 当前连接出口
     * @throws IOException 推送写入失败
     */
    void pump(Connection connection, GameReply output) throws IOException {
        LocalRole role = connection.role;
        if (!needsPump(connection)) {
            return;
        }
        for (GameFeature feature : features) {
            if (feature instanceof BlindBoxGameFeature || feature instanceof PaymentGameFeature) {
                safeTick(feature, role, output);
            }
        }
    }

    /**
     * 按功能隔离执行心跳推送：单个 Feature 抛异常只记录日志，不中断整条心跳与后续推送。
     *
     * @param feature 待执行的功能
     * @param role 当前角色
     * @param output 协议出口
     */
    private void safeTick(GameFeature feature, LocalRole role, GameReply output) {
        try {
            feature.tick(role, output);
        } catch (Exception e) {
            LOG.warn("心跳推送功能异常 role={} feature={}", role == null ? null : role.id,
                    feature.getClass().getSimpleName(), e);
        }
    }

    /**
     * 按功能隔离执行入服全量同步：单个 Feature 同步失败不影响其余功能下发。
     *
     * @param feature 待同步的功能
     * @param role 当前角色
     * @param output 协议出口
     */
    private void safeSynchronize(GameFeature feature, LocalRole role, GameReply output) {
        try {
            feature.synchronize(role, output);
        } catch (Exception e) {
            LOG.warn("入服同步功能异常 role={} feature={}", role == null ? null : role.id,
                    feature.getClass().getSimpleName(), e);
        }
    }

    /**
     * 单条 TCP 连接关联的本地游戏状态
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:49
     */
    static final class Connection {
        final int id = ThreadLocalRandom.current().nextInt(1, Integer.MAX_VALUE);
        private String accountId;
        private LocalRole role;
        private long battlePower;
        private boolean ready;
    }
}
