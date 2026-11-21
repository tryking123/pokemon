package com.pokemon.local.payment;

import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 背包 VIP 经验卡、钻石券和永久代金券的消耗结算 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-20 21:38
 */
@Service
public class ItemVipService {
    private final JdbcTemplate jdbc;
    private final RoleService roles;
    private final InventoryService inventory;
    private final VoucherService vouchers;

    /**
     * 绑定角色锁、物品和独立经验流水。
     * @param jdbc 本地存档
     * @param roles 角色锁与钱包
     * @param inventory 真实库存
     * @param vouchers 永久代金券账本
     */
    public ItemVipService(JdbcTemplate jdbc, RoleService roles, InventoryService inventory, VoucherService vouchers) {
        this.jdbc = jdbc;
        this.roles = roles;
        this.inventory = inventory;
        this.vouchers = vouchers;
    }

    /**
     * 判断是否属于原背包直接使用的 VIP 道具。
     * @param item 道具编号
     * @return 是否使用专属结算
     */
    public static boolean supports(long item) {
        return item == 34100 || item == 31007 || item == 31008 || item >= 34506 && item <= 34508;
    }

    /**
     * 同事务消费指定道具，并为 VIP、钻石及活动面额分别保存唯一流水。
     * @param roleId 当前角色编号
     * @param item 消耗的道具编号
     * @param quantity 消耗数量
     * @param option 自选编号，直接使用必须为零
     * @param receipt 连接内唯一请求回执
     * @return 原协议错误码
     */
    @Transactional
    public int use(String roleId, long item, long quantity, long option, String receipt) {
        if (!supports(item) || quantity <= 0 || quantity > Integer.MAX_VALUE || option != 0) return 100021;
        roles.lockRole(roleId);
        if (jdbc.queryForObject("SELECT COUNT(*) FROM local_item_vip_grant WHERE role_id=? AND receipt=?",
                Integer.class, roleId, receipt) > 0) return 100011;
        if (!inventory.spendMaterials(roleId, (int) item, quantity, 0, 0)) return 100051;
        boolean voucher = item >= 34506 && item <= 34508;
        long experience = voucher ? 0 : Math.multiplyExact(quantity, item == 34100 ? 10 : 1000);
        long cents = item == 31008 ? Math.multiplyExact(quantity, 100) : 0;
        if (voucher) {
            int face = item == 34506 ? 10 : item == 34507 ? 30 : 68;
            vouchers.grantPermanent(roleId, "item:" + receipt, Math.multiplyExact(quantity, face));
        } else if (item != 34100) {
            roles.addCurrency(roleId, 0, Math.multiplyExact(quantity, 1000), 0);
        }
        jdbc.update("INSERT INTO local_item_vip_grant(id,role_id,receipt,item_id,quantity,vip_experience,"
                + "recharge_amount,created_at) VALUES(?,?,?,?,?,?,?,?)", UUID.randomUUID().toString(), roleId,
                receipt, item, quantity, experience, cents, System.currentTimeMillis());
        return 0;
    }

}
