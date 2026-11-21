package com.pokemon.local.gateway;

import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地游戏网关票据
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
@RestController
public class GameTicketController {

    private final GameTicketService tickets;

    /**
     * 初始化游戏票据接口
     *
     * @param tickets 本地票据签发服务
     */
    public GameTicketController(GameTicketService tickets) {
        this.tickets = tickets;
    }

    /**
     * 兑换供 Unity 网关登录使用的短期票据
     *
     * @param authorization HTTP 授权请求头
     * @return 短期游戏票据和本地账号标识
     */
    @PostMapping("/api/game/ticket")
    public Object issue(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return tickets.issue(authorization);
    }
}
