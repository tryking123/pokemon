package com.pokemon.local.account;

import com.pokemon.local.account.api.entity.LocalAccount;
import com.pokemon.local.account.api.vo.LoginVO;

/**
 * 本地账号与会话 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:03
 */
public interface AccountService {

    /**
     * 验证账号凭据并签发会话令牌
     *
     * @param vo 本地账号登录凭据
     * @return 会话令牌和账号信息
     */
    Object login(LoginVO vo);

    /**
     * 注销当前会话
     *
     * @param authorization HTTP 授权请求头
     * @return 会话注销状态
     */
    Object logout(String authorization);

    /**
     * 查询当前账号信息
     *
     * @param authorization HTTP 授权请求头
     * @return 当前账号和钻石余额
     */
    Object me(String authorization);

    /**
     * 验证授权请求头并获取当前账号
     *
     * @param authorization HTTP 授权请求头
     * @return 有效会话对应的账号
     */
    LocalAccount requireAccount(String authorization);
}
