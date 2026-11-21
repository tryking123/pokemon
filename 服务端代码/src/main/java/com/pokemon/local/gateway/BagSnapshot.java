package com.pokemon.local.gateway;

import com.pokemon.local.activitylevel.TrainerRefinementService;
import com.pokemon.local.activitydaily.DailyRechargeService;
import com.pokemon.local.activitylevel.AdventureProgressService;
import com.pokemon.local.activitylevel.api.entity.AdventureEquipment;
import com.pokemon.local.battle.AdventureStage;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.inventory.api.entity.InventoryItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

import static com.pokemon.local.gateway.SprotoProtocol.data;

/**
 * 以原客户端的背包协议同步普通堆叠物品和独立装备实例。
 *
 * @author tongruiyuan
 * @since 2026-11-18 19:50
 */
@Component
final class BagSnapshot {
    private final Map<GameReply, Long> announcedMailCounts = Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final InventoryService inventory;
    private final com.pokemon.local.activitylevel.TrainerRuneService runes;
    private final AdventureProgressService adventure;
    private final DailyRechargeService recharge;
    private final TrainerRefinementService refinement;
    private final org.springframework.beans.factory.ObjectProvider<com.pokemon.local.secret.SecretService> secret;

    /**
     * 连接背包物品和冒险装备存档。
     *
     * @param inventory 普通背包库存
     * @param adventure 冒险通关装备
     * @param recharge 已存入精灵背包的兑换碎片
     * @param runes 贴纸背包和当前刻印
     * @param secret 秘境奥义石
     * @param refinement 训练家装备附属性
     */
    BagSnapshot(com.pokemon.local.activitylevel.TrainerRuneService runes, InventoryService inventory, AdventureProgressService adventure, DailyRechargeService recharge,
                TrainerRefinementService refinement,org.springframework.beans.factory.ObjectProvider<com.pokemon.local.secret.SecretService> secret) {
        this.runes=runes;
        this.secret=secret;
        this.inventory = inventory;
        this.adventure = adventure;
        this.recharge = recharge;
        this.refinement = refinement;
    }

    /**
     * 输出待处理邮件数量相对此连接上次展示值的变化。
     * @param roleId 角色编号
     * @param output 原协议通道
     * @throws IOException 写入连接失败
     */
    void pushMailCount(String roleId, GameReply output) throws IOException {
        publishMailCount(roleId, output, true);
    }

    /**
     * 在本地物品弹窗寄信后，为原游戏连接补发邮箱角标的实际变化量。
     * @param roleId 当前角色
     * @param output 游戏连接
     * @throws IOException 写入连接失败
     */
    void refreshMailCount(String roleId, GameReply output) throws IOException {
        publishMailCount(roleId, output, false);
    }

    /**
     * 按同一连接的上一次角标发送差值。
     * @param roleId 收件角色
     * @param output 当前连接
     * @param force 是否在角标不变时也发送初始同步
     * @throws IOException 写入游戏连接失败
     */
    private void publishMailCount(String roleId, GameReply output, boolean force) throws IOException {
        long pending = inventory.pendingMailCount(roleId);
        long previous = announcedMailCounts.getOrDefault(output, 0L);
        if (force || pending != previous) {
            output.push(10, data("num", pending - previous));
            announcedMailCounts.put(output, pending);
        }
    }

    /**
     * 扣除原界面领取邮件时自行减少的角标。
     * @param output 原协议通道
     * @param claimed 刚领取的邮件数
     */
    void acknowledgeMail(GameReply output, int claimed) {
        announcedMailCounts.computeIfPresent(output, (key, count) -> Math.max(0, count - claimed));
    }

    /**
     * 读取和发送角色背包的 S17 快照。
     *
     * @param roleId 当前角色编号
     * @param output 协议推送通道
     * @throws IOException 写入连接失败
     */
    void push(String roleId, GameReply output) throws IOException {
        List<Map<String, Object>> items = new ArrayList<>();
        for (InventoryItem item : inventory.snapshot(roleId)) {
            // 原版 Lua 把带 pos 的记录归为训练家装备，把部分有 type 的记录另作分类。
            Map<String, Object> stack = data("id", item.id, "n", item.quantity, "state", false,
                    "sid", Integer.toString(item.id), "lock", false);
            if (inventory.isDynamaxBook(item.id)) {
                stack.put("type", 14);
            }
            items.add(stack);
        }
        for (AdventureEquipment equipment : adventure.equipment(roleId)) {
            items.add(data("id", equipment.configId, "n", 1, "state", false, "sid", equipment.id,
                    "pos", equipment.slot, "level", 0, "extra", refinement.properties(refinement.attributes(equipment)),
                    "type", AdventureStage.EQUIPMENT, "upgrade",equipment.advanced ? 1 : 0, "lock", false));
        }
        for(com.fasterxml.jackson.databind.JsonNode stone:secret.getIfAvailable().stones(roleId)){
            List<Map<String,Object>> attributes = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode attr : stone.path("elfequ_addattr")) {
                attributes.add(data("attrid", attr.path("attrid").asInt(), "attrnum", attr.path("attrnum").asInt()));
            }
            items.add(data("id",stone.path("id").asInt(),"sid",stone.path("sid").asText(),"n",1,"level",0,
                    "elfequ_star",stone.path("elfequ_star").asInt(),"lucky",stone.path("lucky").asInt(),"elfequ_starexp",0,"elfequ_addattr",attributes,"lock",stone.path("lock").asBoolean()));
        }
        List<Map<String,Object>> stickers=runes.bag(roleId);
        items.addAll(stickers);
        Map<String, Object> empty = data("use", 0, "capacity", InventoryService.CAPACITY,
                "items", Collections.emptyList());
        List<Map<String, Object>> chips = new ArrayList<>();
        for (DailyRechargeService.Award chip : recharge.chips(roleId)) {
            chips.add(data("id", chip.id, "n", chip.quantity, "sid", Integer.toString(chip.id)));
        }
        output.push(17, data("norbag", data("use", items.size(), "capacity", inventory.ordinaryCapacity(roleId), "items", items),
                "elsbag", data("use", chips.size(), "capacity", InventoryService.CAPACITY, "items", chips), "porbag", empty));
        output.push(445, data("suit_data", equipmentMarks(roleId), "suit_bag", stickers));
    }

    /**
     * 读取训练家各装备位置已保存的刻印。
     *
     * @param roleId 本地角色编号
     * @return 按装备位置和刻印位排序的原协议记录
     */
    List<Map<String, Object>> equipmentMarks(String roleId) {
        List<Map<String, Object>> marks = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode mark : runes.marks(roleId)) {
            marks.add(data("id", mark.path("id").asInt(), "pos", mark.path("pos").asInt(),
                    "uid", mark.path("uid").asInt()));
        }
        return marks;
    }
}
