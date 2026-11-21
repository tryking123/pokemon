package com.pokemon.local.account;

import com.pokemon.local.account.api.vo.LoginVO;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本地账号登录、注销和资产查询
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:03
 */
@RestController
@RequestMapping("/api")
public class AccountController {

    private final AccountService accounts;

    /**
     * 初始化本地账号接口
     *
     * @param accounts 本地账号服务
     */
    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * 登录本地账号
     *
     * @param vo 本地账号登录凭据
     * @return 会话令牌和账号信息
     */
    @PostMapping("/auth/login")
    public Object login(@RequestBody LoginVO vo) {
        return accounts.login(vo);
    }

    /**
     * 注销当前会话
     *
     * @param authorization HTTP 授权请求头
     * @return 会话注销状态
     */
    @PostMapping("/auth/logout")
    public Object logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return accounts.logout(authorization);
    }

    /**
     * 获取当前账号信息和钻石余额
     *
     * @param authorization HTTP 授权请求头
     * @return 当前账号信息
     */
    @GetMapping("/account/me")
    public Object me(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return accounts.me(authorization);
    }
}
