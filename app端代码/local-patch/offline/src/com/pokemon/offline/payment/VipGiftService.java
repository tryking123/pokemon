package com.pokemon.offline.payment;

import com.pokemon.offline.activitylogin.LoginActivityService;
import com.pokemon.offline.elf.ElfGearService;
import com.pokemon.offline.elf.ElfService;
import com.pokemon.offline.inventory.InventoryService;
import com.pokemon.offline.role.RoleService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 两个入口共用的原版 VIP 福利展示与原子购买
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:17
 */
@Service
public class VipGiftService {
    public static final int ELF = 2;
    public static final int CARRY = 4;
    public static final int DRESS = 6;
    private static final int ITEM = 1;
    private static final int COINS = 103;
    private static final int COIN_ITEM = 1001;
    private static final int[] ORIGINAL_COST = {
        1500, 8750, 26250, 43750, 131250, 218750, 350000, 656250, 1312500,
        2625000, 4375000, 8750000, 13125000, 17500000, 21875000, 26250000, 30625000, 35000000
    };
    private static final int[] PRICE = {
        499, 999, 1299, 3999, 5999, 7999, 9999, 10099, 13999,
        15999, 19999, 29999, 49999, 69999, 99999, 299999, 399999, 599999
    };
    // 按 vipgift_proto 的 sid 1–18 排列，同时与 vip_proto 的 VIP0–17 购买档相同。
    private static final List<List<Integer>> GOODS = Collections.unmodifiableList(Arrays.asList(
            goods(103,0,500000, 1,21033,2, 1,20001,30),
            goods(1,21025,1, 1,21034,5, 1,32004,1),
            goods(2,21009,1, 1,20002,20, 1,21023,5),
            goods(6,40017,1, 1,31022,5, 1,22019,50),
            goods(6,40018,1, 1,31022,10, 1,22019,100),
            goods(6,40019,1, 1,34031,10, 1,21023,20),
            goods(6,40020,1, 1,32001,1, 1,21023,50),
            goods(6,40021,1, 1,32001,1, 1,34031,20),
            goods(6,40022,1, 1,20003,30, 1,31023,20),
            goods(6,40023,1, 1,32002,1, 1,21000,100),
            goods(6,40024,1, 1,21023,100, 1,20003,50),
            goods(6,40025,1, 1,32002,1, 1,20003,100),
            goods(6,40027,1, 4,40018,1, 1,20003,120),
            goods(6,40028,1, 4,40019,1, 1,20003,150),
            goods(6,40026,1, 4,40016,1, 1,20003,200),
            goods(1,21058,2, 4,40017,1, 1,20003,200),
            goods(2,63007,1, 4,40014,1, 1,21026,1000),
            goods(6,40029,1, 2,63006,1, 1,21026,2000)
    ));
    private final VipGiftMapper mapper;
    private final GamePaymentService payments;
    private final RoleService roles;
    private final InventoryService inventory;
    private final ElfService elfs;
    private final LoginActivityService dresses;
    private final ElfGearService gear;

    /**
     * 绑定当前角色与真实礼包发放方式
     *
     * @param mapper 礼包购买记录
     * @param payments 已确认订单计算的 VIP
     * @param roles 角色事务锁
     * @param inventory 普通道具与金币服务
     * @param elfs 精灵服务
     * @param dresses 永久时装服务
     * @param gear 统一携带品存档服务
     */
    VipGiftService(VipGiftMapper mapper, GamePaymentService payments, RoleService roles, InventoryService inventory,
                   ElfService elfs, LoginActivityService dresses, ElfGearService gear) {
        this.mapper = mapper;
        this.payments = payments;
        this.roles = roles;
        this.inventory = inventory;
        this.elfs = elfs;
        this.dresses = dresses;
        this.gear = gear;
    }

    /**
     * 返回全部底包商品及个人剩余购买资格
     *
     * @param roleId 当前角色
     * @return 按礼包编号排好的原 Sproto 行
     */
    public List<Map<String, Object>> offers(String roleId) {
        List<Integer> purchased = mapper.purchased(roleId);
        int vip = payments.vip(roleId).level;
        List<Map<String, Object>> result = new ArrayList<>();
        for (int level = 0; level < GOODS.size(); level++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("sid", level + 1);
            entry.put("level", level);
            entry.put("goods", GOODS.get(level));
            entry.put("orig", ORIGINAL_COST[level]);
            entry.put("cost", PRICE[level]);
            entry.put("personal", vip >= level && !purchased.contains(level) ? 1 : 0);
            result.add(entry);
        }
        return result;
    }

    /**
     * 查询持久化购买记录供 S440 和充值页使用
     *
     * @param roleId 当前角色
     * @return 已购买的 VIP 等级列表
     */
    public List<Integer> purchased(String roleId) {
        return mapper.purchased(roleId);
    }

    /**
     * 扣除真实钻石并一次性发放完整套餐
     *
     * @param roleId 当前角色
     * @param level 原礼包所需 VIP 等级
     * @return 已发放的奖励三元组，失败为空
     */
    @Transactional
    public List<Integer> buy(String roleId, long level) {
        if (level < 0 || level >= GOODS.size()) {
            return Collections.emptyList();
        }
        int index = (int) level;
        roles.lockRole(roleId);
        List<Integer> reward = GOODS.get(index);
        Map<Integer, Long> items = new LinkedHashMap<>();
        long elfCount = 0;
        for (int offset = 0; offset < reward.size(); offset += 3) {
            int type = reward.get(offset);
            int item = reward.get(offset + 1);
            long quantity = reward.get(offset + 2);
            if (type == ITEM) {
                items.merge(item, quantity, Math::addExact);
            } else if (type == ELF) {
                elfCount += quantity;
            } else if (type == CARRY && !gear.canGrantGear(roleId, ElfGearService.CARRY, item, quantity)) {
                return Collections.emptyList();
            }
        }
        if (payments.vip(roleId).level < index || mapper.purchased(roleId).contains(index)
                || elfCount > 0 && elfs.list(roleId).size() + elfCount > inventory.elfCapacity(roleId)
                || !inventory.canReceive(roleId, items) || !mapper.debit(roleId, PRICE[index])) {
            return Collections.emptyList();
        }
        for (int offset = 0; offset < reward.size(); offset += 3) {
            grant(roleId, reward.get(offset), reward.get(offset + 1), reward.get(offset + 2));
        }
        mapper.insertPurchase(roleId, index);
        return reward;
    }

    /**
     * 按原奖励真实类型写入游戏存档
     *
     * @param roleId 当前角色
     * @param type 奖励类型
     * @param id 底包配置编号
     * @param quantity 发放数量
     */
    private void grant(String roleId, int type, int id, int quantity) {
        switch (type) {
            case ITEM:
                inventory.grant(roleId, id, quantity);
                break;
            case COINS:
                inventory.grant(roleId, COIN_ITEM, quantity);
                break;
            case ELF:
                elfs.grant(roleId, id);
                break;
            case DRESS:
                dresses.grantDress(roleId, id);
                break;
            case CARRY:
                gear.grantGear(roleId, ElfGearService.CARRY, id, quantity);
                break;
            default:
                throw new IllegalArgumentException("未知 VIP 奖励类型 " + type);
        }
    }

    /**
     * 保存一档不可变的底包奖励顺序
     *
     * @param fields 类型、编号和数量字段
     * @return 原奖励配方
     */
    private static List<Integer> goods(Integer... fields) {
        return Collections.unmodifiableList(Arrays.asList(fields));
    }
}
