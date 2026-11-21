package com.pokemon.offline.gateway;

import com.pokemon.offline.elf.PowerStoneRefinementService;
import java.util.Collections;
import com.pokemon.offline.elf.PclResearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.pokemon.offline.elf.ElfGearService;
import com.pokemon.offline.elf.ElfCarryInheritanceService;
import com.pokemon.offline.elf.ElfRebirthService;
import com.pokemon.offline.elf.GoldCarryService;
import com.pokemon.offline.elf.ElfTrainingService;
import com.pokemon.offline.elf.api.entity.ElfGear;
import com.pokemon.offline.hangup.HangupService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原游戏的精灵培养协议入口。
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:51
 */
@Service
final class ElfTrainingGameFeature implements GameFeature {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final PclResearchService pcl;
    private final ElfTrainingService training;
    private final com.pokemon.offline.elf.ElfSkillUpgradeService skillUpgrade;
    private final HangupService hangup;
    private final BattleGameFeature battle;
    private final RoleService roles;
    private final BagSnapshot bags;
    private final ElfGearService gears;
    private final ElfCarryInheritanceService inheritance;
    private final ElfRebirthService rebirth;
    private final GoldCarryService gold;
    private final WorldGameFeature world;
    private final PowerStoneRefinementService stoneRefinement;
    private final Map<GameReply,String> trainingConnections = Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * 创建精灵训练协议入口。
     *
     * @param training 精灵训练服务
     * @param hangup 训练经验池
     * @param battle 精灵快照输出
     * @param roles 货币快照读取
     * @param bags 背包快照输出
     * @param gears 携带品和 P 力石服务
     * @param world 主线任务状态同步
     * @param pcl 精灵研究服务
     * @param inheritance 普通携带品整组继承
     * @param rebirth 原精灵重生及材料返还
     * @param gold 黄金携带品装卸与升星
     * @param stoneRefinement 力石洗炼及预览保存
     * @param skillUpgrade 精灵技能升级与消耗记录
     */
    ElfTrainingGameFeature(ElfTrainingService training, HangupService hangup, BattleGameFeature battle,
                           RoleService roles, BagSnapshot bags, ElfGearService gears, WorldGameFeature world,
                           PowerStoneRefinementService stoneRefinement, PclResearchService pcl, ElfCarryInheritanceService inheritance,
                           ElfRebirthService rebirth, GoldCarryService gold, com.pokemon.offline.elf.ElfSkillUpgradeService skillUpgrade) {
        this.skillUpgrade = skillUpgrade;
        this.gold = gold;
        this.rebirth = rebirth;
        this.inheritance = inheritance;
        this.pcl = pcl;
        this.training = training;
        this.hangup = hangup;
        this.battle = battle;
        this.roles = roles;
        this.bags = bags;
        this.gears = gears;
        this.world = world;
        this.stoneRefinement = stoneRefinement;
    }

    /**
     * 响应精灵培养操作，随后同步训练状态、扣费与背包。
     *
     * @param role 连接认证的角色
     * @param request 原游戏请求
     * @param output 原游戏消息出口
     * @return 是否处理该命令
     * @throws IOException 写入游戏协议失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        switch (request.type) {
            case 3043:
                if (request.session > 0) {
                    Map<String,Object> upgraded = skillUpgrade.upgrade(role.id, (String)request.body.get("uid"),
                            number(request.body,"skill",0), request.body.get("cost"), receipt(output, request));
                    if (number(upgraded,"errno",1) == 0) pushState(role, output);
                    output.send(request, upgraded);
                }
                return true;
            case 20020:
            case 20021:
                if (request.session > 0) {
                    String wearer = (String) request.body.get("id");
                    String carry = (String) request.body.get("carryid");
                    Map<String,Object> answer;
                    boolean success;
                    if (request.type == 20020) {
                        int star = gold.star(role.id, wearer, carry, (List<?>)request.body.get("self_list"),
                                (List<?>)request.body.get("other_list"), receipt(output, request));
                        success = star >= 0;
                        answer = data("errno", success ? 0 : 100021, "id", wearer, "carryid", carry, "star", Math.max(0,star));
                    } else {
                        int opt = (int)number(request.body,"opt",0);
                        int pos = (int)number(request.body,"pos",0);
                        String prior = (String)request.body.get("use");
                        success = gold.equip(role.id, wearer, carry, opt, pos, prior, receipt(output, request));
                        answer = data("errno", success ? 0 : 100021, "id", wearer, "carryid", carry,
                                "opt", opt, "pos", pos, "use", prior);
                    }
                    output.send(request, answer);
                    if (success) {
                        battle.pushElfs(role.id,output);
                        bags.push(role.id,output);
                        output.push(3,data("coins",roles.findById(role.id).orElse(role).coins));
                    }
                }
                return true;
            case 3036:
                if (request.session > 0) {
                    boolean ok = rebirth.rebirth(role.id, (String) request.body.get("onlyid"),
                            (int) number(request.body, "be_reborn", 0), receipt(output, request));
                    output.send(request, data("errno", ok ? 0 : 100021));
                    if (ok) {
                        battle.pushElfs(role.id, output);
                        bags.push(role.id, output);
                        bags.pushMailCount(role.id, output);
                        output.push(3, data("exp1", hangup.elfPool(role.id)));
                    }
                }
                return true;
            case 3020:
                if (request.session > 0) {
                    boolean ok = inheritance.inherit(role.id, (String) request.body.get("elfid_a"),
                            (String) request.body.get("elfid_b"), receipt(output, request));
                    output.send(request, data("errno", ok ? 0 : 100021));
                    if (ok) battle.pushElfs(role.id, output);
                }
                return true;
            case 417:
            case 418:
            case 419:
            case 420:
                if (request.session > 0) {
                    Map<String,Object> reply = JSON.convertValue(
                            pcl.perform(role.id, request.type, request.body, receipt(output, request)),
                            new TypeReference<Map<String,Object>>() {});
                    output.send(request, reply);
                    if (number(reply, "errno", 0) == 0) {
                        pushState(role, output);
                    }
                }
                return true;
            case 252:
            case 253:
                if (request.session > 0) {
                    refineStone(role, request, output);
                }
                return true;
            case 5079:
                if (request.session > 0) {
                    Object values = request.body.get("uid");
                    ElfGear result = values instanceof List ? gears.fuseCarries(role.id, (List<?>) values) : null;
                    if (result == null) {
                        output.send(request, data("errno", 100021));
                    } else {
                        // 原融合动画在收到回包后立即按新 uid 查询携带品背包。
                        battle.pushElfGear(gears.snapshot(role.id), output);
                        output.send(request, data("errno", 0, "uid", result.id));
                    }
                }
                return true;
            case 1503:
            case 1504:
            case 3004:
                upgrade(role, request, output);
                return true;
            case 255:
            case 256:
                processStones(role, request, output);
                return true;
            case 251:
            case 254:
            case 3019:
            case 3021:
            case 3024:
                equipment(role, request, output);
                return true;
            case 270:
            case 271:
            case 3005:
            case 3006:
            case 3007:
            case 3008:
            case 3009:
            case 3010:
            case 3011:
            case 3022:
            case 3035:
            case 407:
            case 408:
                String key = request.type == 270 ? "target" : request.type == 271
                        || request.type == 407 || request.type == 408 ? "uid"
                        : request.type == 3007 || request.type == 3035 ? "id" : "onlyid";
                if (request.type == 3009 && request.session <= 0) {
                    return true;
                }
                String receipt = request.type == 3009 || request.type == 3006 || request.type == 3007 || request.type == 270 || request.type == 271
                        ? receipt(output, request) : null;
                Map<String, Object> result = training.train(role.id, request.type,
                        (String) request.body.get(key), request.body, receipt);
                if (request.type == 3035 && number(result, "errno", 0) == 0) {
                    pushAwakening(role, request, result, output);
                    return true;
                }
                output.send(request, result);
                if (number(result, "errno", 0) == 0) {
                    pushState(role, output);
                    if (request.type == 3005 || request.type == 3006 || request.type == 3008) {
                        world.synchronize(role, output);
                    }
                }
                return true;
            default:
                return false;
        }
    }

    /**
     * 返回 P 力石的待确认词条，或保存候选并同步原实例。
     * @param role 当前角色
     * @param request 洗炼预览或保存请求
     * @param output 原协议输出
     * @throws IOException 无法发送响应
     */
    private void refineStone(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        String receipt = receipt(output, request);
        if (request.type == 253) {
            boolean saved = stoneRefinement.save(role.id, receipt);
            output.send(request, data("errno", saved ? 0 : 100021));
            if (saved) {
                battle.pushElfs(role.id, output);
            }
            return;
        }
        long pos = number(request.body, "pos", 0);
        long mode = number(request.body, "mode", -1);
        Object locks = request.body.getOrDefault("locklist", Collections.emptyList());
        List<Map<String,Integer>> next = pos >= 0 && pos <= 4 && mode >= 0 && mode <= 1 && locks instanceof List
                ? stoneRefinement.preview(role.id, (String) request.body.get("elfid"), (int)pos,
                        (String) request.body.get("sid"), (int)mode, (List<?>)locks, receipt) : null;
        output.send(request, next == null ? data("errno", 100021) : data("errno", 0, "mode", mode, "extra", next));
        if (next != null) {
            bags.push(role.id, output);
            output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
        }
    }

    /**
     * 将连接、协议和请求序号组合为本次训练操作的回执。
     * @param output 当前连接
     * @param request 原协议请求
     * @return 同一连接重发时保持相同的回执
     */
    private String receipt(GameReply output, SprotoProtocol.Request request) {
        return trainingConnections.computeIfAbsent(output, ignored -> java.util.UUID.randomUUID().toString())
                + ":" + request.type + ":" + request.session;
    }

    /**
     * 回复力石加工结果，并将产出实例发送给原版背包。
     *
     * @param role 当前角色
     * @param request 合成或分解请求
     * @param output 当前连接
     * @throws IOException 协议发送失败
     */
    private void processStones(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Object value = request.body.get("list");
        List<?> ids = value instanceof List ? (List<?>) value : java.util.Collections.emptyList();
        if (request.type == 256) {
            long balance = gears.splitStones(role.id, ids);
            output.send(request, balance < 0 ? data("errno", 100021) : data("errno", 0, "list", ids, "debris", balance));
            return;
        }
        List<ElfGear> result = training.compoundStones(role.id, ids);
        if (result.isEmpty()) {
            output.send(request, data("errno", 100021));
            return;
        }
        output.send(request, data("errno", 0, "list", ids));
        output.push(14, data("flags", "pstone.compound", "adds", data("pstone",
                result.stream().map(BattleGameFeature::powerStone).collect(Collectors.toList()))));
        BattleGameFeature.pushStoneBag(gears.snapshot(role.id), null, output);
        LocalRole current = roles.findById(role.id).orElse(role);
        output.push(3, data("coins", current.coins, "diamonds", current.diamonds));
    }

    /**
     * 处理培养界面内的装备获取、装配和保护。
     *
     * @param role 当前角色
     * @param request 装备操作请求
     * @param output 回复与推送出口
     * @throws IOException 写入游戏协议失败
     */
    private void equipment(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        Map<String, Object> body = request.body;
        String elfId = (String) body.get(request.type == 251 || request.type == 254 ? "elfid" : "id");
        String carryId = (String) body.get("carryid");
        long position = number(body, "pos", 0);
        Map<String, Object> result = data("errno", 100021);
        switch (request.type) {
            case 251:
                String stoneId = (String) body.get("stoneid");
                if (position >= 1 && position <= 4 && gears.equipStone(role.id, elfId, (int) position, stoneId)) {
                    result = data("errno", 0, "elfid", elfId, "pos", position);
                    if (stoneId != null && !stoneId.isEmpty()) {
                        result.put("stoneid", stoneId);
                    }
                }
                break;
            case 254:
                String sid = (String) body.get("sid");
                boolean lock = Boolean.TRUE.equals(body.get("lock"));
                if (position >= 0 && position <= 4 && gears.lockStone(role.id, elfId, (int) position, sid, lock)) {
                    result = data("errno", 0, "elfid", elfId == null ? "" : elfId, "pos", position,
                            "sid", sid, "lock", lock);
                }
                break;
            case 3019:
                if (gears.equipCarry(role.id, elfId, carryId)) {
                    List<ElfGear> equipped = gears.snapshot(role.id).stream().filter(gear -> gear.kind == ElfGearService.CARRY
                            && elfId.equals(gear.elfId)).collect(Collectors.toList());
                    result = data("errno", 0, "carrylist", BattleGameFeature.carryList(elfId, equipped));
                }
                break;
            case 3021:
                List<ElfGear> purchased = training.buyRandomCarry(role.id, number(body, "sid", 0));
                if (!purchased.isEmpty()) {
                    result = data("errno", 0, "carryid", purchased.stream().map(BattleGameFeature::carryInfo)
                            .collect(Collectors.toList()));
                }
                break;
            case 3024:
                long protection = number(body, "type", -1);
                if ((protection == 0 || protection == 1) && training.protectCarry(role.id, elfId, carryId, protection == 1)) {
                    result = data("errno", 0);
                }
                break;
            default:
                throw new IllegalArgumentException("未知精灵装备请求");
        }
        output.send(request, result);
        if (number(result, "errno", 0) == 0) {
            pushState(role, output);
        }
    }

    /**
     * 更新精灵、物品和角色资产的客户端缓存。
     *
     * @param role 当前角色
     * @param output 推送出口
     * @throws IOException 写入游戏协议失败
     */
    private void pushState(LocalRole role, GameReply output) throws IOException {
        battle.pushElfs(role.id, output);
        bags.push(role.id, output);
        LocalRole current = roles.findById(role.id).orElse(role);
        output.push(3, data("coins", current.coins, "diamonds", current.diamonds));
    }

    /**
     * 先更新精灵快照，再由原觉醒回调递增旧阶段，最后同步权威觉醒记录。
     * 原 S1001 会将客户端阶段初始化为零，不能放在成功回调之后覆盖界面。
     * 原回调会自行累加阶段，S1006 必须等回调完成后发送，避免重复累加。
     *
     * @param role 当前角色
     * @param request 原觉醒请求
     * @param result 已提交的觉醒结果
     * @param output 当前连接
     * @throws IOException 协议发送失败
     */
    private void pushAwakening(LocalRole role, SprotoProtocol.Request request, Map<String, Object> result,
                               GameReply output) throws IOException {
        List<Map<String, Object>> awakening = new java.util.ArrayList<>();
        GameReply ordered = new GameReply() {
            /** {@inheritDoc} */
            @Override
            public int actorSession() {
                return output.actorSession();
            }

            /** {@inheritDoc} */
            @Override
            public void send(SprotoProtocol.Request original, Map<String, Object> body) throws IOException {
                output.send(original, body);
            }

            /** {@inheritDoc} */
            @Override
            public void push(int type, Map<String, Object> body) throws IOException {
                if (type == 1006) {
                    awakening.add(body);
                } else {
                    output.push(type, body);
                }
            }
        };
        pushState(role, ordered);
        output.send(request, result);
        for (Map<String, Object> body : awakening) {
            output.push(1006, body);
        }
    }

    /**
     * 按现代和旧版的经验池协议升级。
     *
     * @param role 连接关联的角色
     * @param request 精灵升级请求
     * @param output 原游戏消息出口
     * @throws IOException 写入游戏协议失败
     */
    private void upgrade(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        boolean current = request.type == 3004;
        String elfId = (String) request.body.get(current ? "onlyid" : "id");
        long levels = current ? number(request.body, "numlv", 0) : request.type == 1503 ? 1 : 10;
        int result = training.upgrade(role.id, elfId, levels);
        if (result < 0) {
            output.send(request, data("errno", -result));
        } else {
            long experience = hangup.elfPool(role.id);
            output.send(request, current ? data("errno", 0, "onlyid", elfId, "level", result, "exp", experience)
                    : data("errno", 0));
            output.push(3, data("exp1", experience));
            battle.pushElfs(role.id, output);
            world.synchronize(role, output);
        }
    }
}
