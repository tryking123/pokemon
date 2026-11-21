package com.pokemon.offline.inventory;

import com.pokemon.offline.platform.lifecycle.PostConstruct;
import com.pokemon.offline.platform.core.io.ClassPathResource;
import com.pokemon.offline.platform.jdbc.core.JdbcTemplate;
import com.pokemon.offline.platform.jdbc.datasource.init.ResourceDatabasePopulator;
import com.pokemon.offline.platform.stereotype.Service;
import com.pokemon.offline.platform.transaction.annotation.Transactional;

/**
 * 提供人物背包 600 格和精灵背包 500 格的统一总容量，保留历史扩容记录。
 *
 * @author tongruiyuan
 * @since 2026-11-19 14:37
 */
@Service
public class BagCapacityService {
    private static final int BASE_CAPACITY = 100;
    private static final int ORDINARY_PAGE = 20;
    private static final int ELF_PAGE = 10;
    private static final int ORDINARY_BASE_PRICE_INDEX = 2;
    private static final int[] ORDINARY_PRICES = {500, 1000, 1500, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000};
    private static final int[] ELF_PRICES = {500, 1000, 2000, 3000, 4000};
    private static final int STALE = 100031;
    private static final int REPEATED = 100011;
    private static final int LIMIT = 100015;
    private static final int NOT_ENOUGH = 100006;

    private final JdbcTemplate jdbc;
    private final InventoryMapper inventory;

    /**
     * 使用本地库存钱包扣除原价并写入扩容记录。
     *
     * @param jdbc 设备或桌面本地数据库
     * @param inventory 钱包事务及钻石消费账
     */
    BagCapacityService(JdbcTemplate jdbc, InventoryMapper inventory) {
        this.jdbc = jdbc;
        this.inventory = inventory;
    }

    /**
     * 初始化兼容旧存档的扩容表，旧记录默认使用原有容量。
     */
    @PostConstruct
    void initialize() {
        new ResourceDatabasePopulator(new ClassPathResource("inventory/bag-expansion.sql"))
                .execute(jdbc.getDataSource());
    }

    /**
     * 获取当前普通道具背包容量。
     *
     * @param roleId 角色编号
     * @return 当前普通道具格数
     */
    public int ordinary(String roleId) {
        return 600;
    }

    /**
     * 获取角色可持有的整只精灵数量。
     *
     * @param roleId 角色编号
     * @return 当前精灵格数
     */
    public int elves(String roleId) {
        return 500;
    }

    /**
     * 拒绝超过已开放总容量的扩容请求，不扣除钻石。
     *
     * @param roleId 角色编号
     * @param elfBag 是否扩容整只精灵背包
     * @param displayed 当前连接展示的旧容量
     * @param requestKey 连接及请求编号组成的重放键
     * @return 提交后的容量、余额和原协议错误码
     */
    @Transactional
    public Expansion buy(String roleId, boolean elfBag, int displayed, String requestKey) {
        long[] wallet = inventory.lockWallet(roleId);
        // 总容量已统一开放，保留历史购买记录，满容量时不再扣费。
        return new Expansion(LIMIT, elfBag ? elves(roleId) : ordinary(roleId), wallet[1]);
    }

    /**
     * 统计此类背包已经购买的页面。
     *
     * @param roleId 当前角色
     * @param elfBag 是否为精灵背包
     * @return 角色实际购买的页数
     */
    private int purchased(String roleId, boolean elfBag) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM local_bag_expansion WHERE role_id=? AND bag=?",
                Integer.class, roleId, elfBag ? "elf" : "ordinary");
        return count == null ? 0 : count;
    }

    /**
     * 扩容结算结果。
     *
     * @author tongruiyuan
     * @since 2026-11-19 14:37
     */
    public static final class Expansion {
        public final int errno;
        public final int capacity;
        public final long diamonds;

        /**
         * 保存真实钱包和背包容量。
         *
         * @param errno 原协议错误码
         * @param capacity 结算后的容量
         * @param diamonds 结算后的钻石
         */
        private Expansion(int errno, int capacity, long diamonds) {
            this.errno = errno;
            this.capacity = capacity;
            this.diamonds = diamonds;
        }
    }
}
