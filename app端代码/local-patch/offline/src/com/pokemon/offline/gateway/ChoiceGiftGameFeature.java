package com.pokemon.offline.gateway;

import com.pokemon.offline.inventory.ChoiceGiftService;
import com.pokemon.offline.role.RoleService;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pokemon.offline.guild.PersonalGuildService;
import java.util.stream.Collectors;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 背包道具使用请求与奖励同步。
 *
 * @author tongruiyuan
 * @since 2026-11-18 23:05
 */
@Service
final class ChoiceGiftGameFeature implements GameFeature {
    private final ChoiceGiftService gifts;
    private final PersonalGuildService guilds;
    private final Map<GameReply, String> connections = Collections.synchronizedMap(new WeakHashMap<>());
    private final BagSnapshot bags;
    private final RoleService roles;
    private final BattleGameFeature battle;
    private final CosmeticGameFeature cosmetics;
    private final com.pokemon.offline.elf.ElfGearService gears;
    private final com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.activity.PassportService> passport;
    private final Map<GameReply, java.util.Set<Long>> completed = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 初始化自选礼包协议入口。
     *
     * @param gifts 背包道具使用结算
     * @param bags 背包快照
     * @param roles 获取变动后钱包
     * @param battle 同步新获得的精灵
     * @param guilds 考古专属库存和礼包发放
     * @param cosmetics 时装库存同步
     * @param gears 携带品和力石库存同步
     * @param passport 原时装币钱包读取
     */
    ChoiceGiftGameFeature(ChoiceGiftService gifts, BagSnapshot bags, RoleService roles, BattleGameFeature battle,
                          PersonalGuildService guilds, CosmeticGameFeature cosmetics,
                          com.pokemon.offline.elf.ElfGearService gears,
                          com.pokemon.offline.platform.beans.factory.ObjectProvider<com.pokemon.offline.activity.PassportService> passport) {
        this.guilds = guilds;
        this.gifts = gifts;
        this.bags = bags;
        this.roles = roles;
        this.battle = battle;
        this.cosmetics = cosmetics;
        this.gears = gears;
        this.passport = passport;
    }

    /**
     * 解析道具使用请求并推送实际发放的奖励。
     *
     * @param role 当前登录角色
     * @param request 原道具使用协议
     * @param output 当前连接
     * @return 是否处理了请求
     * @throws IOException 消息发送失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        long requestedItem = number(request.body,"id",0);
        if (request.type != 2401 || requestedItem == 34100 || requestedItem == 31007 || requestedItem == 31008
                || requestedItem >= 34506 && requestedItem <= 34508
                || requestedItem == 23046 || requestedItem == 34700
                || com.pokemon.offline.activitylevel.TrainerRuneService.isGift(requestedItem)) {
            return false;
        }
        if (request.session <= 0) {
            return true;
        }
        long item = number(request.body, "id", 0);
        long option = number(request.body, "giftid", 0);
        long quantity = number(request.body, "num", 1);
        if (guilds.antiquityGift(item)) {
            String receipt = connections.computeIfAbsent(output, ignored -> UUID.randomUUID().toString())
                    + ":" + request.session;
            ObjectNode opened = guilds.openAntiquityGift(role.id, item, option, quantity, receipt);
            if (opened.path("errno").asInt() == 0) {
                if (!opened.path("replayed").asBoolean()) {
                    output.push(14, data("flags", "item.use", "adds", data("antiquity", Collections.singletonList(
                            data("id", opened.path("reward").asInt(), "n", opened.path("quantity").asLong(), "type", 20))),
                            "loses", data("items", Collections.singletonList(data("id", item, "n", quantity)))));
                }
                output.push(465, guilds.antiquities(role.id));
                bags.push(role.id, output);
            }
            output.send(request, data("errno", opened.path("errno").asInt()));
            return true;
        }
        java.util.Set<Long> processed = completed.computeIfAbsent(output, ignored -> new java.util.HashSet<>());
        if (processed.contains(request.session)) {
            bags.push(role.id, output);
            output.send(request, data("errno", 0));
            return true;
        }
        ChoiceGiftService.Grant reward = gifts.use(role.id, item, option, quantity);
        if (reward.errno != 0) {
            output.send(request, data("errno", reward.errno));
            return true;
        }
        processed.add(request.session);
        Map<String, Object> adds;
        if (!reward.randomRewards.isEmpty() || !reward.currencies.isEmpty() || !reward.petRewards.isEmpty()) {
            adds = new java.util.LinkedHashMap<>();
            for (int[] row : reward.randomRewards) {
                if (row[0] == 2 || row[0] == 35) continue;
                String field = row[0] == 4 ? "carrys" : row[0] == 13 ? "pstone" : row[0] == 6 ? "fashes" : row[0] == 3 ? "elschips" : "items";
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) adds.computeIfAbsent(field, ignored -> new java.util.ArrayList<>());
                if (row[0] == 6) {
                    list.add(row[1]);
                } else if (row[0] == 10) {
                    list.add(data("id", row[1], "n", row[2], "pos",
                            com.pokemon.offline.battle.AdventureStage.equipmentPosition(row[1]), "level", 0));
                } else {
                    // 原附件的 items.type 非空会被当作技能书，普通道具不携带该字段。
                    list.add(data("id", row[1], "n", row[2]));
                }
            }
            adds.putAll(reward.currencies);
            if (!reward.elfs.isEmpty()) adds.put("elf", reward.elfs.stream().map(elf -> data("id", elf.id,
                    "sid", elf.configId, "level", elf.level)).collect(Collectors.toList()));
            if (!reward.petRewards.isEmpty()) adds.put("pet", reward.petRewards);
        } else if (reward.type == 10) {
            adds = data("items", Collections.singletonList(data("id", reward.item, "n", reward.quantity,
                    "pos", com.pokemon.offline.battle.AdventureStage.equipmentPosition(reward.item), "level", 0)));
        } else if (reward.type == 6) {
            adds = data("fashes", Collections.singletonList(reward.item));
        } else if (reward.type == 13) {
            adds = data("pstone", Collections.singletonList(data("id", reward.item, "n", reward.quantity)));
        } else if (reward.elfExperience > 0) {
            adds = data("exp1", reward.elfExperience);
        } else if (reward.type == ChoiceGiftService.ELF) {
            adds = data("elf", reward.elfs.stream().map(elf -> data("id", elf.id, "sid", elf.configId,
                    "level", elf.level)).collect(Collectors.toList()));
        } else if (reward.type == ChoiceGiftService.CHIP) {
            adds = data("elschips", Collections.singletonList(data("id", reward.item, "n", reward.quantity)));
        } else if (reward.type == ChoiceGiftService.CARRY) {
            adds = data("carrys", Collections.singletonList(data("id", reward.item, "n", reward.quantity)));
        } else if (reward.type == ChoiceGiftService.DIAMONDS) {
            adds = data("diamonds", reward.quantity);
        } else {
            // 带 type 的奖励会进入图鉴等专用分支，普通道具由模板查找类型。
            adds = data("items", Collections.singletonList(data("id", reward.item, "n", reward.quantity)));
        }
        output.push(14, data("flags", "item.use", "adds", adds,
                "loses", data("items", Collections.singletonList(data("id", item, "n", quantity)))));
        if (reward.elfExperience > 0) {
            output.push(3, data("exp1", reward.elfPool));
        } else if (reward.type == ChoiceGiftService.DIAMONDS) {
            output.push(3, data("diamonds", roles.findById(role.id).orElse(role).diamonds));
        } else if (reward.type == ChoiceGiftService.ELF || reward.type == ChoiceGiftService.CARRY || reward.type == 13) {
            battle.pushElfs(role.id, output);
        }
        if (!reward.randomRewards.isEmpty() || !reward.currencies.isEmpty() || !reward.petRewards.isEmpty()) {
            battle.pushElfs(role.id, output);
            BattleGameFeature.pushStoneBag(gears.snapshot(role.id), null, output);
            cosmetics.pushDress(role.id, output);
            LocalRole changed = roles.findById(role.id).orElse(role);
            output.push(3, data("coins", changed.coins, "diamonds", changed.diamonds,
                    "fashescoins", passport.getIfAvailable().fashionCoins(role.id)));
        } else if (reward.type == 13) {
            BattleGameFeature.pushStoneBag(gears.snapshot(role.id), null, output);
        } else if (reward.type == 6) {
            cosmetics.pushDress(role.id, output);
        }
        bags.push(role.id, output);
        // 原客户端在使用成功回调中重绘背包，此时须已收到扣除后的库存。
        output.send(request, data("errno", 0));
        return true;
    }
}
