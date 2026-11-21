package com.pokemon.offline.gateway;

import com.pokemon.offline.activity.PumpkinService;
import com.pokemon.offline.activity.PumpkinService.Board;
import com.pokemon.offline.role.api.entity.LocalRole;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import com.pokemon.offline.platform.stereotype.Service;

import static com.pokemon.offline.gateway.SprotoProtocol.data;
import static com.pokemon.offline.gateway.SprotoProtocol.number;

/**
 * 原 Unity 捣蛋南瓜活动协议。
 *
 * @author tongruiyuan
 * @since 2026-11-18 21:40
 */
@Service
final class PumpkinGameFeature implements GameFeature {
    private final PumpkinService pumpkins;
    private final BagSnapshot bags;
    private final Map<GameReply, Board> displayed = Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * 绑定南瓜活动与背包推送。
     *
     * @param pumpkins 活动 Service
     * @param bags 角色背包快照
     */
    PumpkinGameFeature(PumpkinService pumpkins, BagSnapshot bags) {
        this.pumpkins = pumpkins;
        this.bags = bags;
    }

    /**
     * 转换原客户端查询、选奖和翻牌命令。
     *
     * @param role 已验证角色
     * @param request 原版协议请求
     * @param output 应答通道
     * @return 是否由本活动处理
     * @throws IOException 写入连接失败
     */
    @Override
    public boolean handle(LocalRole role, SprotoProtocol.Request request, GameReply output) throws IOException {
        if (request.session <= 0 || request.type < 379 || request.type > 381) {
            return false;
        }
        Board seen = displayed.get(output);
        long index = number(request.body, "idx", 0);
        boolean next = Boolean.TRUE.equals(request.body.get("enter"));
        Board board;
        switch (request.type) {
            case 379:
                board = pumpkins.enter(role.id, next, seen);
                break;
            case 380:
                board = pumpkins.select(role.id, seen, index);
                break;
            default:
                board = pumpkins.reveal(role.id, seen, index);
                break;
        }
        if (board == null) {
            output.send(request, data("errno", 1));
            return true;
        }
        displayed.put(output, board);
        if (request.type != 380) {
            bags.push(role.id, output);
        }
        output.send(request, response(request.type, board, (int) index, next));
        return true;
    }

    /**
     * 转换已完成的活动操作，保留原版空棋盘和未选大奖的空字段语义。
     *
     * @param type 原版请求编号
     * @param board 当前棋盘
     * @param index 请求档位或牌面
     * @param next 是否请求进入下一层
     * @return 原客户端应答对象
     */
    private Map<String, Object> response(int type, Board board, int index, boolean next) {
        if (type == 381) {
            return data("errno", 0, "idx", index, "award", board.prize(index), "pass", board.passed());
        }
        if (type == 380) {
            return data("errno", 0, "idx", index, "board", tiles(board));
        }
        List<Map<String, Object>> pool = IntStream.rangeClosed(1, PumpkinService.jackpotCount())
                .mapToObj(prize -> data("index", prize, "status", 0, "award", PumpkinService.jackpot(prize)))
                .collect(Collectors.toList());
        Map<String, Object> result = data("errno", 0, "enter", next, "layer", board.floor,
                "pool", pool, "pass", board.passed(), "cost", Arrays.asList(1, PumpkinService.TICKET, 1));
        if (board.jackpot != 0) {
            result.put("jackpot", board.jackpot);
            result.put("board", tiles(board));
        }
        return result;
    }

    /**
     * 发布完整牌面编号及已翻牌的奖励，保留未翻奖励的随机结果。
     *
     * @param board 当前棋盘
     * @return 原版 Lua 按 index 读取的棋盘
     */
    private List<Map<String, Object>> tiles(Board board) {
        return IntStream.rangeClosed(1, PumpkinService.SIZE)
                .mapToObj(tile -> board.isOpen(tile)
                        ? data("index", tile, "status", 1, "award", board.prize(tile))
                        : data("index", tile, "status", 0))
                .collect(Collectors.toList());
    }
}
