package com.pokemon.local.gateway;

import com.pokemon.local.activitylevel.TrainerEquipmentService;
import com.pokemon.local.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.local.battle.AdventureStage;
import com.pokemon.local.battle.FirstBattleData;
import com.pokemon.local.elf.ElfCombatGrowth;
import com.pokemon.local.elf.ElfGearService;
import com.pokemon.local.elf.ElfService;
import com.pokemon.local.elf.api.entity.ElfGear;
import com.pokemon.local.elf.api.entity.LocalElf;
import com.pokemon.local.payment.GamePaymentService;
import com.pokemon.local.ranking.LocalRankingService;
import com.pokemon.local.ranking.RankingEntry;
import com.pokemon.local.role.RoleBattlePowerService;
import com.pokemon.local.role.RoleCosmeticService;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

import static com.pokemon.local.gateway.SprotoProtocol.data;
import static com.pokemon.local.gateway.SprotoProtocol.number;

/**
 * 普通排行榜与本地角色名片的协议适配
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:28
 */
@Service
final class RankingGameFeature implements GameFeature {

    private final LocalRankingService ranking;
    private final RoleService roles;
    private final RoleBattlePowerService power;
    private final ElfService elfs;
    private final TrainerEquipmentService equipment;
    private final GamePaymentService payments;
    private final ElfGearService gear;
    private final RoleCosmeticService cosmetics;
    private final BagSnapshot bags;

    /**
     * 初始化单玩家普通榜处理器
     *
     * @param ranking 普通榜的真实数据服务
     * @param roles 角色快照查询
     * @param power 队伍及精灵战力查询
     * @param elfs 精灵存档查询
     * @param equipment 训练家装备快照查询
     * @param payments 角色 VIP 快照查询
     * @param gear 精灵携带品快照查询
     * @param cosmetics 外观展示和属性查询
     * @param bags 装备刻印快照
     */
    RankingGameFeature(LocalRankingService ranking, RoleService roles, RoleBattlePowerService power,
                       ElfService elfs, TrainerEquipmentService equipment, GamePaymentService payments,
                       ElfGearService gear, RoleCosmeticService cosmetics, BagSnapshot bags) {
        this.ranking = ranking;
        this.roles = roles;
        this.power = power;
        this.elfs = elfs;
        this.equipment = equipment;
        this.payments = payments;
        this.gear = gear;
        this.cosmetics = cosmetics;
        this.bags = bags;
    }

    /**
     * 分页返回单玩家排行榜以及独立的当前角色名次
     *
     * @param role 已认证本地角色
     * @param request 原客户端请求
     * @param output 原协议消息输出
     * @return 是否处理了该请求
     * @throws IOException 写入协议响应失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.type == 2516) {
            if (request.session > 0) {
                output.send(request, profile(role, request));
            }
            return true;
        }
        if (request.type != 2515) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        long index = number(request.body, "index", 0);
        long page = number(request.body, "page", 0);
        long size = number(request.body, "size", 0);
        if (index < 1 || index > 7 || page < 1 || size < 1) {
            output.send(request, data("errno", 57, "index", index, "page", page, "size", size));
            return true;
        }

        Optional<RankingEntry> entry = ranking.current(role.id, (int) index);
        List<Map<String, Object>> list = entry.isPresent() && page == 1
                ? Collections.singletonList(row(entry.get())) : Collections.emptyList();
        Map<String, Object> myInfo = entry
                .map(value -> data("ranking", 1, "data", value.value))
                .orElseGet(() -> data("ranking", 0, "data", 0));
        // 原 Lua 使用 uptm 加上 60 秒安排刷新，传入本次实时快照时间。
        output.send(request, data("errno", 0, "index", index, "page", page, "size", size,
                "list", list, "myinfo", myInfo, "uptm", Instant.now().getEpochSecond()));
        return true;
    }

    /**
     * 返回单机角色自己的名片、编队和实际佩戴的训练家装备。
     *
     * @param role 当前已认证角色
     * @param request 角色资料请求
     * @return 原协议名片应答
     */
    private Map<String, Object> profile(LocalRole role, SprotoProtocol.Request request) {
        Object requestedId = request.body.get("uid");
        if (!role.id.equals(requestedId) || number(request.body, "svrid", role.realmId) != role.realmId) {
            return data("errno", 49);
        }
        LocalRole saved = roles.findById(role.id).orElse(role);
        RoleCosmeticService.BattleBonuses bonuses = cosmetics.battleBonuses(saved.id);
        List<Map<String, Object>> team = new ArrayList<>();
        List<Map<String, Object>> attributes = new ArrayList<>();
        int activeTeam=power.defaultTeam(saved.id);
        for (LocalElf elf : elfs.list(role.id)) {
            if (elf.team != activeTeam || elf.slot <= 0) {
                continue;
            }
            team.add(BattleGameFeature.elfInfo(elf));
            FirstBattleData.Starter base = FirstBattleData.starter(elf.configId);
            if (base != null) {
                attributes.add(data("id", elf.id, "sid", elf.configId, "level", elf.level,
                        "position", elf.position(), "quality", base.quality, "power", power.elfScore(elf, bonuses),
                        "type1", base.type, "type2", base.secondType,
                        "hp_max", bonuses.attribute("hp_max", ElfCombatGrowth.attribute(elf, "hp_max", base.life)),
                        "physical_attack", bonuses.attribute("physical_attack", ElfCombatGrowth.attribute(elf, "physical_attack", base.physicalAttack)),
                        "physical_defense", bonuses.attribute("physical_defense", ElfCombatGrowth.attribute(elf, "physical_defense", base.physicalDefense)),
                        "special_attack", bonuses.attribute("special_attack", ElfCombatGrowth.attribute(elf, "special_attack", base.specialAttack)),
                        "special_defense", bonuses.attribute("special_defense", ElfCombatGrowth.attribute(elf, "special_defense", base.specialDefense)),
                        "speed", bonuses.attribute("speed", ElfCombatGrowth.attribute(elf, "speed", base.speed))));
            }
        }
        List<Map<String, Object>> parts = new ArrayList<>();
        for (AdventureEquipment item : equipment.equipped(role.id)) {
            parts.add(data("id", item.configId, "sid", item.id, "n", 1, "pos", item.slot, "level", item.level,
                    "type", AdventureStage.EQUIPMENT));
        }
        List<ElfGear> carried = gear.snapshot(role.id);
        return data("errno", 0, "uid", saved.id, "name", saved.name, "level", saved.level,
                "title", cosmetics.displayedTitle(saved.id), "power", power.score(saved.id), "vip", payments.vip(saved.id).level,
                "sex", saved.sex, "dress", cosmetics.wornDress(saved.id), "elfs", Collections.singletonList(data("team", activeTeam, "list", team)),
                "equiplist", parts, "elfcarry", BattleGameFeature.equippedCarries(carried, false),
                "suitdata", bags.equipmentMarks(role.id),
                "allelf", attributes, "goldcarry", BattleGameFeature.equippedCarries(carried, true), "battle_pet", Collections.emptyList());
    }

    /**
     * 填充排行界面支持的角色及精灵字段
     *
     * @param entry 当前榜的真实角色记录
     * @return 原协议 rankinginfo 结构
     */
    private Map<String, Object> row(RankingEntry entry) {
        LocalRole role = entry.role;
        return data("uid", role.id, "name", role.name, "ranking", 1, "data", entry.value,
                "sid", entry.elfConfigId, "sex", role.sex, "dress", cosmetics.wornDress(role.id),
                "title", cosmetics.displayedTitle(role.id),
                "ctime", role.createdAt, "icon", role.avatar);
    }
}
