package com.pokemon.local.gateway;

import com.pokemon.local.account.AccountService;
import com.pokemon.local.account.api.entity.LocalAccount;
import com.pokemon.local.common.ApiException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 本地游戏网关票据签发与验证服务
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
@Service
public class GameTicketService {

    private static final long TTL = TimeUnit.MINUTES.toMillis(5);
    private static final int MAX_TICKETS = 1024;

    private final AccountService accounts;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Ticket> tickets = new HashMap<>();

    /**
     * 初始化游戏票据服务
     *
     * @param accounts 本地账号服务
     */
    public GameTicketService(AccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * 使用 REST 会话签发短期游戏票据
     *
     * @param authorization HTTP 授权请求头
     * @return 游戏票据及获授权账号标识
     */
    public Object issue(String authorization) {
        LocalAccount account = accounts.requireAccount(authorization);
        byte[] secret = new byte[32];
        random.nextBytes(secret);
        String key = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        synchronized (tickets) {
            long now = System.currentTimeMillis();
            tickets.values().removeIf(ticket -> ticket.expiresAt <= now);
            if (tickets.size() >= MAX_TICKETS) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TICKET_LIMIT", "游戏票据过多，请稍后重试");
            }
            tickets.put(key, new Ticket(account.id, authorization, now + TTL));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ticket", key);
        result.put("accountId", account.id);
        return result;
    }

    /**
     * 读取游戏票据绑定的账号并确认关联 REST 会话仍有效
     *
     * @param accountId 登录消息中的账号
     * @param key 短期游戏票据
     * @return 验证结果
     */
    public boolean validate(String accountId, String key) {
        Ticket ticket;
        synchronized (tickets) {
            ticket = tickets.get(key);
        }
        if (ticket == null || ticket.expiresAt <= System.currentTimeMillis()
                || !ticket.accountId.equals(accountId)) {
            return false;
        }
        try {
            return accounts.requireAccount(ticket.authorization).id.equals(accountId);
        } catch (ApiException exception) {
            return false;
        }
    }

    /**
     * 游戏票据的账号来源及失效时间
     *
     * @author tongruiyuan
     * @since 2026-11-18 17:42
     */
    private static final class Ticket {
        private final String accountId;
        private final String authorization;
        private final long expiresAt;

        /**
         * 建立内存票据记录
         *
         * @param accountId 票据所属账号
         * @param authorization 签发票据所用的 REST 会话
         * @param expiresAt 票据失效时间，单位毫秒
         */
        private Ticket(String accountId, String authorization, long expiresAt) {
            this.accountId = accountId;
            this.authorization = authorization;
            this.expiresAt = expiresAt;
        }
    }
}
