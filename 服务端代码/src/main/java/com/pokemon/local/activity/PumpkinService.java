package com.pokemon.local.activity;

import com.pokemon.local.activity.api.entity.ActivityPeriod;
import com.pokemon.local.inventory.InventoryService;
import com.pokemon.local.role.RoleService;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 捣蛋南瓜选奖、翻牌与活动周期 Service。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:37
 */
@Service
public class PumpkinService {
    public static final int SIZE = 20;
    public static final int TICKET = 34641;
    private static final int ACTIVITY = 10072;
    private static final int[] JACKPOTS = {20003, 20004, 20005, 20006, 20007, 20008, 20009};
    private static final int OTHER_PRIZE = 21026;
    private final ActivityService calendar;
    private final PumpkinMapper boards;
    private final RoleService roles;
    private final InventoryService inventory;

    /**
     * 绑定活动周期、棋盘及背包存档。
     *
     * @param calendar 运营活动周期服务
     * @param boards 棋盘落库对象
     * @param roles 本地角色事务锁
     * @param inventory 奖品及门票库存
     */
    PumpkinService(ActivityService calendar, PumpkinMapper boards, RoleService roles, InventoryService inventory) {
        this.calendar = calendar;
        this.boards = boards;
        this.roles = roles;
        this.inventory = inventory;
    }

    /**
     * 初始化本周的体验门票，读取当前棋盘或进入下一层。
     *
     * @param roleId 角色标识
     * @param next 是否由客户端明确请求换层
     * @param seen 连接最后读取的棋盘，可为空
     * @return 当前棋盘，活动尚未开始时为空
     */
    @Transactional
    public Board enter(String roleId, boolean next, Board seen) {
        roles.lockRole(roleId);
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        if (Instant.now().getEpochSecond() < period.start) {
            return null;
        }
        Board board = boards.find(roleId, period.start).orElse(null);
        if (board == null) {
            if (!inventory.canGrantItem(roleId, TICKET, SIZE)) {
                return null;
            }
            // 底包界面使用南瓜券。本地每周首次参与补齐一板用量，作为单机券来源。
            inventory.grant(roleId, TICKET, SIZE);
            board = new Board(period.start, 1, 0, ThreadLocalRandom.current().nextLong(), 0);
            boards.save(roleId, board);
        } else if (next && board.matches(seen) && board.passed()) {
            board = new Board(board.period, board.floor + 1, 0, ThreadLocalRandom.current().nextLong(), 0);
            boards.save(roleId, board);
        }
        return board;
    }

    /**
     * 锁定本层大奖选项，重复选择相同奖品时返回已有棋盘。
     *
     * @param roleId 角色标识
     * @param seen 连接最后读取的棋盘
     * @param index 大奖序号
     * @return 选奖成功的棋盘
     */
    @Transactional
    public Board select(String roleId, Board seen, long index) {
        if (index < 1 || index > JACKPOTS.length) {
            return null;
        }
        roles.lockRole(roleId);
        Board board = current(roleId, seen);
        if (board == null || board.jackpot != 0 && board.jackpot != index) {
            return null;
        }
        if (board.jackpot == 0) {
            board = new Board(board.period, board.floor, (int) index, board.seed, 0);
            boards.save(roleId, board);
        }
        return board;
    }

    /**
     * 按未公开的已保存牌面一次性消耗南瓜券并发放奖励。
     *
     * @param roleId 角色标识
     * @param seen 连接最后读取的棋盘
     * @param index 翻开的牌面编号
     * @return 实际翻牌后的棋盘
     */
    @Transactional
    public Board reveal(String roleId, Board seen, long index) {
        if (index < 1 || index > SIZE) {
            return null;
        }
        roles.lockRole(roleId);
        Board board = current(roleId, seen);
        int tile = (int) index;
        if (board == null || board.jackpot == 0 || board.isOpen(tile)) {
            return null;
        }
        List<Integer> prize = board.prize(tile);
        if (!inventory.canGrantItem(roleId, prize.get(1), prize.get(2)) || !inventory.takeOne(roleId, TICKET)) {
            return null;
        }
        inventory.grant(roleId, prize.get(1), prize.get(2));
        Board updated = new Board(board.period, board.floor, board.jackpot, board.seed, board.opened | bit(tile));
        boards.save(roleId, updated);
        return updated;
    }

    /**
     * 返回底包所列大奖，前端选项从一计数。
     *
     * @param index 底包大奖档位
     * @return 原版奖励三元组，超出时为空列表
     */
    public static List<Integer> jackpot(int index) {
        return index < 1 || index > JACKPOTS.length ? Collections.emptyList()
                : Arrays.asList(1, JACKPOTS[index - 1], index);
    }

    /**
     * 返回可选择的真实大奖档位数。
     *
     * @return 大奖档位数
     */
    public static int jackpotCount() {
        return JACKPOTS.length;
    }

    /**
     * 校验该连接操作的牌面属于现在的活动周期和层。
     *
     * @param roleId 角色标识
     * @param seen 最近显示的棋盘
     * @return 仍可操作的棋盘
     */
    private Board current(String roleId, Board seen) {
        ActivityPeriod period = calendar.cycle(ACTIVITY);
        return boards.find(roleId, period.start).filter(board -> board.matches(seen)).orElse(null);
    }

    /**
     * 将一号至二十号牌转换为已翻位图。
     *
     * @param tile 牌面编号
     * @return 该牌对应的位值
     */
    private static int bit(int tile) {
        return 1 << (tile - 1);
    }

    /**
     * 单角色南瓜棋盘的固定存档状态。
     *
     * @author tongruiyuan
     * @since 2026-11-18 21:37
     */
    public static final class Board {
        public final long period;
        public final int floor;
        public final int jackpot;
        final long seed;
        final int opened;

        /**
         * 还原一层持久化棋盘。
         *
         * @param period 本期起点
         * @param floor 当前层
         * @param jackpot 已选择的大奖档位
         * @param seed 发牌时的随机种子
         * @param opened 已开启牌面位图
         */
        Board(long period, int floor, int jackpot, long seed, int opened) {
            this.period = period;
            this.floor = floor;
            this.jackpot = jackpot;
            this.seed = seed;
            this.opened = opened;
        }

        /**
         * 检查访问连接展示的是否为同一层。
         *
         * @param seen 连接已见棋盘
         * @return 周期及层数均相同
         */
        boolean matches(Board seen) {
            return seen != null && seen.period == period && seen.floor == floor;
        }

        /**
         * 检查指定牌面是否已成功翻开。
         *
         * @param tile 牌面编号
         * @return 已翻开为真
         */
        public boolean isOpen(int tile) {
            return (opened & bit(tile)) != 0;
        }

        /**
         * 判断本层能否进入下一层。
         *
         * @return 已翻开大奖为真
         */
        public boolean passed() {
            return jackpot != 0 && isOpen(jackpotTile());
        }

        /**
         * 读取翻牌奖励，普通奖使用底包奖池中仍有实际道具模板的档位。
         *
         * @param tile 牌面编号
         * @return 原版奖励三元组
         */
        public List<Integer> prize(int tile) {
            if (tile == jackpotTile()) {
                return jackpot(jackpot);
            }
            return Arrays.asList(1, OTHER_PRIZE, 1);
        }

        /**
         * 计算由本层种子固定的大奖位置。
         *
         * @return 一号至二十号牌中的大奖位置
         */
        private int jackpotTile() {
            return new Random(seed).nextInt(SIZE) + 1;
        }
    }
}
