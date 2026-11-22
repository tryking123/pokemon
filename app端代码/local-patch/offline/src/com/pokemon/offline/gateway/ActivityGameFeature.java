package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.ActivityService;
import com.pokemon.offline.battle.NestService;
import com.pokemon.offline.eden.EdenEncounterService;
import com.pokemon.offline.tower.TowerService;
import com.pokemon.offline.space.SpaceService;
import com.fasterxml.jackson.databind.JsonNode;
import com.pokemon.offline.activity.ChristmasTreeService;
import com.pokemon.offline.activity.OpeningRankRewards;
import com.pokemon.offline.activity.OperatingActivityService;
import com.pokemon.offline.activity.LimitedBeastService;
import com.pokemon.offline.activity.api.entity.ActivityPeriod;
import com.pokemon.offline.activitylevel.LevelGiftService;
import com.pokemon.offline.arena.ArenaService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.core.annotation.Order;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;
import static com.pokemon.offline.activity.OpeningRankRewards.TWISTED_EGG_EVENT;

/**
 * Unity 运营活动开关、自然周期与单玩家排行榜协议
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:04
 */
@Service
@Order(0)
final class ActivityGameFeature implements GameFeature {
    private static final int ARENA_OVERVIEW = 1;
    private static final int LEVEL_EVENT = 10003;
    private static final int BATTLE_POWER_EVENT = 10015;
    private static final int RECHARGE_EVENT = 10018;
    private static final int OPEN_STATUS = 2;
    private static final int CLOSED_STATUS = 5;
    private final com.pokemon.offline.arena.WorldBossService worldBoss;
    private final com.pokemon.offline.arena.RocketService rocket;
    private final com.pokemon.offline.elf.StriveBoardService strive;
    private final com.pokemon.offline.activity.LuckyBagService luckyBag;
    private final Map<GameReply,Map<String,Object>> luckyPanels = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,Long> luckyRefresh = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<GameReply,String> luckyConnections = Collections.synchronizedMap(new WeakHashMap<>());
    private final NestService nest;
    private final ActivityService activities;
    private final LevelGiftService levelGifts;
    private final OpeningRankRewards rankRewards;
    private final ChristmasTreeService christmas;
    private final BagSnapshot bags;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final ArenaService arena;
    private final EdenEncounterService eden;
    private final TowerService tower;
    private final SpaceService space;
    private final Map<GameReply, Long> nextRefresh = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定本地活动时钟及榜单数据
     *
     * @param activities 本地活动服务
     * @param levelGifts 闯关礼包完成状态
     * @param rankRewards 开服榜单的原奖品和资格
     * @param christmas 春节狂欢活动
     * @param bags 角色背包推送
     * @param roles 角色货币快照
     * @param battle 精灵背包推送
     * @param arena 当日竞技训练次数
     * @param eden 神兽岛当日参与次数
     * @param tower 洞穴剩余挑战机会
     * @param strive 努力值训练中心剩余次数
     * @param worldBoss 普通与大师首领两套挑战次数
     * @param rocket 火箭队当日可用次数
     * @param luckyBag 钻石福袋真实任务与货币
     * @param nest 宝可梦巢穴剩余次数
     * @param space 究极空间剩余挑战机会
     */
    ActivityGameFeature(com.pokemon.offline.arena.WorldBossService worldBoss, ActivityService activities, LevelGiftService levelGifts, OpeningRankRewards rankRewards,
                        ChristmasTreeService christmas, BagSnapshot bags, RoleService roles, BattleGameFeature battle,
                        ArenaService arena, EdenEncounterService eden, TowerService tower, SpaceService space, com.pokemon.offline.activity.LuckyBagService luckyBag,
                        NestService nest, com.pokemon.offline.arena.RocketService rocket, com.pokemon.offline.elf.StriveBoardService strive) {
        this.strive=strive;
        this.rocket=rocket;
        this.worldBoss=worldBoss;
        this.nest = nest;
        this.luckyBag = luckyBag;
        this.activities = activities;
        this.levelGifts = levelGifts;
        this.rankRewards = rankRewards;
        this.christmas = christmas;
        this.bags = bags;
        this.roles = roles;
        this.battle = battle;
        this.arena = arena;
        this.eden = eden;
        this.tower = tower;
        this.space = space;
    }

    /**
     * 响应活动同步、红点和本地运营榜
     *
     * @param role 当前已认证的角色
     * @param request 活动协议请求
     * @param output 原客户端应答通道
     * @return 是否处理了该协议
     * @throws IOException 发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0) {
            return false;
        }
        switch (request.type) {
            case 389:
                String token = luckyConnections.computeIfAbsent(output,k -> java.util.UUID.randomUUID().toString()) + ":" + request.session;
                int diamonds = luckyBag.open(role.id, token);
                if (diamonds > 0) {
                    output.push(14, data("flags", "luckybag.open", "adds", data("diamonds",diamonds),
                            "loses", data("felicity",100)));
                    output.push(3,data("diamonds",roles.findById(role.id).orElse(role).diamonds));
                }
                output.send(request,data("errno",diamonds>0 ? 0 : 100012));
                if (diamonds>0) updateLucky(role,output,true);
                return true;
            case 1201:
                long task = number(request.body,"taskid",0);
                if (task < 61000 || task > 61003) return false;
                int points = luckyBag.submit(role.id,(int)task);
                output.send(request,data("errno",points>0 ? 0 : 100012,"taskid",task));
                if (points>0) {
                    output.push(14,data("flags","task.task_submit","adds",data("felicity",points)));
                    updateLucky(role,output,true);
                }
                return true;
            case 326:
                long event = number(request.body, "itype", 0);
                if (!rankRewards.isTrainingEvent(event)) {
                    output.send(request, data("errno", 100013, "itype", event));
                    return true;
                }
                sendActivityRank(role, request, output, (int) event);
                return true;
            case 384:
                output.send(request, christmas.status(role.id));
                return true;
            case 385:
            case 386:
            case 387:
                updateChristmas(role, request, output);
                return true;
            case 388:
                sendActivityRank(role, request, output, ChristmasTreeService.ACTIVITY);
                return true;
            case 5002:
                output.send(request, data("errno", 0));
                pushActivityStatus(role, output);
                return true;
            case 10005:
            case 10006:
            case 10007:
            case 10411:
                sendRank(role, request, output);
                return true;
            default:
                return false;
        }
    }

    /**
     * 转交节日操作，结算成功后刷新被扣除和发放的资产。
     *
     * @param role 已认证角色
     * @param request 原客户端活动请求
     * @param output 游戏协议通道
     * @throws IOException 发送失败
     */
    private void updateChristmas(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long selected = number(request.body, "idx", 0);
        int index = selected < 1 || selected > Integer.MAX_VALUE ? 0 : (int) selected;
        Map<String, Object> result;
        switch (request.type) {
            case 385:
                result = christmas.use(role.id, index);
                break;
            case 386:
                result = christmas.purchase(role.id, index, (String) request.body.get("quote"));
                break;
            default:
                result = christmas.claim(role.id, index);
        }
        if (number(result, "errno", 1) == 0 && !result.containsKey("preview")) {
            bags.push(role.id, output);
            if (request.type == 385 && index == 1) {
                LocalRole saved = roles.findById(role.id).orElse(role);
                output.push(3, data("diamonds", saved.diamonds));
            } else if (request.type == 386 && (index == 1 || index == 9 || index == 10)) {
                battle.pushElfs(role.id, output);
            }
        }
        output.send(request, result);
    }

    /**
     * 返回培养榜或节日积分榜的单人排名和原奖励。
     *
     * @param role 当前角色
     * @param request 原排行榜请求
     * @param output 游戏协议通道
     * @param event 原活动编号
     * @throws IOException 发送失败
     */
    private void sendActivityRank(LocalRole role, SprotoProtocol.Request request, GameReply output, int event)
            throws IOException {
        ActivityPeriod period = activities.cycle(event);
        Optional<LocalRole> ranked = activities.rankedRole(role.id);
        long minimum = rankRewards.threshold(event);
        long topThree = event == ChristmasTreeService.ACTIVITY ? OpeningRankRewards.CHRISTMAS_TOP_THREE : minimum;
        List<Integer> weights = event == 10066 ? OpeningRankRewards.CARRY_POINTS : Collections.emptyList();
        List<Map<String, Object>> list = Collections.emptyList();
        int rank = 0;
        if (ranked.isPresent() && Instant.now().getEpochSecond() >= period.start) {
            LocalRole candidate = ranked.get();
            long score = rankRewards.currentScore(candidate, period);
            rank = rankRewards.rank(event, score);
            if (rank > 0) {
                list = Collections.singletonList(data("uid", candidate.id, "name", candidate.name, "sex", candidate.sex,
                        "rank", rank, "score", score, "icon", candidate.sex + 1, "svrid", candidate.realmId,
                        "award", rankRewards.rewardsAtRank(event, rank)));
            }
        }
        Map<String, Object> response = data("errno", 0, "myrank", rank, "ranklist", list,
                "configure", data("min", minimum, "top3", topThree, "jifen", weights));
        if (request.type == 326) {
            response.put("itype", event);
        }
        output.send(request, response);
    }

    /**
     * 推送普通运营活动和限时活动全量快照
     *
     * @param role 当前角色
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    @Override
    public void synchronize(LocalRole role, GameReply output) throws IOException {
        updateLucky(role,output,true);
    }

    /** 在任务状态改变时通知原模型，并用真实周期覆盖原福袋监听插入的固定日期。
     * @param role 当前角色
     * @param output 原游戏连接
     * @param force 是否需要重新初始化福袋
     * @throws IOException 协议写入失败
     */
    private void updateLucky(LocalRole role,GameReply output,boolean force) throws IOException {
        LocalRole saved = roles.findById(role.id).orElse(role);
        if (saved.level >= 30) luckyBag.participate(role.id,61000);
        Map<String,Object> panel = luckyBag.panel(saved);
        Map<String,Object> previous = luckyPanels.put(output,panel);
        luckyRefresh.put(output,Instant.now().getEpochSecond()+5);
        if (!force && panel.equals(previous)) return;
        output.push(460,data("season",panel.get("season"),"felicity",panel.get("felicity")));
        pushActivityStatus(saved,output);
        for (Map<String,Object> entry : luckyBag.tasks(panel)) {
            if (previous == null || !luckyBag.tasks(previous).contains(entry)
                    || !panel.get("day").equals(previous.get("day")) || !panel.get("period").equals(previous.get("period"))) {
                output.push(101,entry);
            }
        }
    }

    /**
     * 推送运营活动时间窗并保存下次刷新时刻
     *
     * @param role 当前角色
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    void pushActivityStatus(LocalRole role, GameReply output) throws IOException {
        long now = Instant.now().getEpochSecond();
        List<ActivityPeriod> operating = activities.operatingPeriods();
        List<ActivityPeriod> overview = activities.overviewPeriods();
        if (com.pokemon.offline.arena.DnfService.open(role)) {
            long monday = com.pokemon.offline.arena.DnfService.week();
            overview.add(new ActivityPeriod(28, monday, monday + 6 * 86400L, monday + 7 * 86400L, 1));
        }
        output.push(10002, data("info", status(operating, now, levelGifts.state(role.id).all)));
        List<Map<String, Object>> overviewStatus = status(overview, now, false);
        overviewStatus.removeIf(row -> number(row,"activity_id",0)==27 && role.level<55);
        for (Map<String, Object> row : overviewStatus) {
            int id=(int)number(row,"activity_id",0);
            if(id==9 || id==23)row.put("num",worldBoss.remaining(role.id,id));
            if(number(row,"activity_id",0)==27) row.put("num",rocket.remaining(role.id));
            if (number(row, "activity_id", 0) == ARENA_OVERVIEW) {
                row.put("num", arena.remainingArena(role.id));
            }
            if (number(row, "activity_id", 0) == 10) {
                row.put("num", battle.dungeonRuns(role.id, now));
            }
            if (number(row, "activity_id", 0) == 3 && role.level >= 14) {
                row.put("num", 3 - tower.snapshot(role.id).path("times").asInt());
            }
            if (number(row, "activity_id", 0) == 19 && strive.open(role)) {
                row.put("num", strive.snapshot(role.id).path("primary").path("num").asInt());
            }
            if (number(row, "activity_id", 0) == 26) {
                row.put("num", nest.remaining(role.id));
            }
            if (number(row, "activity_id", 0) == 29 && role.level >= 50) {
                row.put("num", space.snapshot(role.id).path("times").asInt());
            }
            if (number(row, "activity_id", 0) == 24) {
                JsonNode counters = eden.counters(role.id);
                row.put("num", 3L + counters.path("buytime").asLong() - counters.path("times").asLong());
            }
        }
        output.push(1101, data("info", overviewStatus));
        long next = Math.min(nextBoundary(operating, now), nextBoundary(overview, now));
        nextRefresh.put(output, next);
    }

    /**
     * 角色在线跨越活动截止时推送下一轮状态
     *
     * @param role 当前角色
     * @param output 推送通道
     * @throws IOException 发送失败
     */
    @Override
    public void tick(LocalRole role, GameReply output) throws IOException {
        if (Instant.now().getEpochSecond() >= luckyRefresh.getOrDefault(output,0L)) {
            updateLucky(role,output,false);
        }
        if (Instant.now().getEpochSecond() >= nextRefresh.getOrDefault(output, 0L)) {
            pushActivityStatus(role, output);
        }
    }

    /**
     * 返回只由本地 admin 实际存档和订单计算的运营榜
     *
     * @param role 当前角色
     * @param request 排行榜协议请求
     * @param output 应答通道
     * @throws IOException 发送失败
     */
    private void sendRank(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        int event = rankEvent(request.type);
        Optional<LocalRole> ranked = activities.rankedRole(role.id);
        List<Map<String, Object>> list = Collections.emptyList();
        ActivityPeriod period = activities.cycle(event);
        if (ranked.isPresent() && Instant.now().getEpochSecond() >= period.start) {
            LocalRole candidate = ranked.get();
            long score = rankRewards.currentScore(candidate, period);
            long minimum = event == TWISTED_EGG_EVENT ? rankRewards.threshold(event) : 1;
            if (score >= minimum) {
                List<Integer> reward = score >= rankRewards.threshold(event)
                        ? rankRewards.rewards(event) : Collections.emptyList();
                list = Collections.singletonList(data("name", candidate.name, "sex", candidate.sex,
                        "level", event == RECHARGE_EVENT ? score / 100 : score,
                        "dress", 0, "uid", candidate.id, "icon", candidate.sex + 1, "reward", reward));
            }
        }
        Map<String, Object> body = data("errno", 0, "info", list);
        if (request.type == 10005) {
            body.put("day", period.day);
            body.put("special", 0);
        } else if (request.type == 10006) {
            body.put("special", 0);
        }
        output.send(request, body);
    }

    /**
     * 查找原榜单协议对应的运营活动。
     *
     * @param type 开服榜单协议编号
     * @return 原运营活动编号
     */
    private int rankEvent(int type) {
        if (type == 10005) {
            return LEVEL_EVENT;
        }
        if (type == 10411) {
            return TWISTED_EGG_EVENT;
        }
        return type == 10006 ? BATTLE_POWER_EVENT : RECHARGE_EVENT;
    }

    /**
     * 构造 Lua 需要的状态和 epoch 秒字符串
     *
     * @param periods 活动时间窗
     * @param now 当前 Unix 秒
     * @param levelGiftsDone 当前周期闯关礼包是否全部领取
     * @return 原协议活动状态列表
     */
    private List<Map<String, Object>> status(List<ActivityPeriod> periods, long now, boolean levelGiftsDone) {
        return periods.stream().map(period -> data("activity_id", period.id,
                "status", now < period.start || now >= (period.id == 28 || period.id == LimitedBeastService.ACTIVITY || period.id == com.pokemon.offline.activity.MazeService.ACTIVITY
                        ? period.showUntil : period.end) || levelGiftsDone && period.id == LevelGiftService.ACTIVITY
                        ? CLOSED_STATUS : OPEN_STATUS, "num", 0,
                "openTime", Long.toString(period.start),
                "endTime", Long.toString(period.id == OperatingActivityService.INVESTMENT ? period.end - 1 : period.end),
                "showTime", Long.toString(period.showUntil))).collect(Collectors.toList());
    }

    /**
     * 查询这组活动的下一次刷新时间
     *
     * @param periods 活动窗口集合
     * @param now 当前 Unix 秒
     * @return 即将开始或换轮的最早时间
     */
    private long nextBoundary(List<ActivityPeriod> periods, long now) {
        return periods.stream().mapToLong(period -> now < period.start ? period.start
                        : now < period.end ? period.end : period.showUntil)
                .min().orElse(Long.MAX_VALUE);
    }
}
