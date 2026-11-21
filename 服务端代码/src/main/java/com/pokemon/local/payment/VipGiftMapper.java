package com.pokemon.local.payment;

import com.pokemon.local.inventory.DiamondSpendMapper;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * VIP 福利购买记录 Mapper
 *
 * @author tongruiyuan
 * @since 2026-11-18 22:17
 */
@Repository
class VipGiftMapper {
    private final JdbcTemplate jdbc;
    private final DiamondSpendMapper diamondSpend;

    /**
     * 绑定本地角色数据库
     *
     * @param jdbc 数据库模板
     * @param diamondSpend 钻石消费流水
     */
    VipGiftMapper(JdbcTemplate jdbc, DiamondSpendMapper diamondSpend) {
        this.jdbc = jdbc;
        this.diamondSpend = diamondSpend;
    }

    /**
     * 读取角色已购买的 VIP 福利等级
     *
     * @param roleId 当前角色
     * @return 按等级排序的持久记录
     */
    List<Integer> purchased(String roleId) {
        return jdbc.queryForList("SELECT vip_level FROM local_vip_gift_purchase "
                + "WHERE role_id = ? ORDER BY vip_level", Integer.class, roleId);
    }

    /**
     * 从已加锁的角色余额尝试扣除钻石
     *
     * @param roleId 当前角色
     * @param amount 商品售价
     * @return 是否成功扣除
     */
    boolean debit(String roleId, int amount) {
        return diamondSpend.debit(roleId, amount) == 1;
    }

    /**
     * 在同一事务内记录成功购买
     *
     * @param roleId 当前角色
     * @param level 礼包对应的 VIP 等级
     */
    void insertPurchase(String roleId, int level) {
        jdbc.update("INSERT INTO local_vip_gift_purchase(role_id, vip_level) VALUES (?, ?)", roleId, level);
    }

}
