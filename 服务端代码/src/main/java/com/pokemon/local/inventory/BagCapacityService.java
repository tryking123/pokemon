package com.pokemon.local.inventory;

import javax.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保存普通道具和精灵背包的独立扩容页数，原子记录扣费和购买凭据。
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
        return BASE_CAPACITY + ORDINARY_PAGE * purchased(roleId, false);
    }

    /**
     * 获取角色可持有的整只精灵数量。
     *
     * @param roleId 角色编号
     * @return 当前精灵格数
     */
    public int elves(String roleId) {
        return BASE_CAPACITY + ELF_PAGE * purchased(roleId, true);
    }

    /**
     * 以原客户端展示容量计算下一页，从钱包扣除准确价格。
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
        int used = purchased(roleId, elfBag);
        int page = elfBag ? ELF_PAGE : ORDINARY_PAGE;
        int current = BASE_CAPACITY + used * page;
        String bag = elfBag ? "elf" : "ordinary";
        Integer replay = jdbc.queryForObject("SELECT COUNT(*) FROM local_bag_expansion "
                + "WHERE role_id=? AND bag=? AND request_key=?", Integer.class, roleId, bag, requestKey);
        if (replay != null && replay > 0) {
            return new Expansion(REPEATED, current, wallet[1]);
        }
        if (current != displayed) {
            return new Expansion(STALE, current, wallet[1]);
        }
        int[] prices = elfBag ? ELF_PRICES : ORDINARY_PRICES;
        int index = used + (elfBag ? 0 : ORDINARY_BASE_PRICE_INDEX);
        if (index >= prices.length) {
            return new Expansion(LIMIT, current, wallet[1]);
        }
        int price = prices[index];
        if (wallet[1] < price) {
            return new Expansion(NOT_ENOUGH, current, wallet[1]);
        }
        long remaining = wallet[1] - price;
        inventory.settleWallet(roleId, wallet[0], remaining, price);
        jdbc.update("INSERT INTO local_bag_expansion(role_id,bag,request_key,amount) VALUES(?,?,?,?)",
                roleId, bag, requestKey, price);
        return new Expansion(0, current + page, remaining);
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

