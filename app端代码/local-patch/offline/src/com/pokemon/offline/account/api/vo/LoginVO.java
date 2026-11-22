package com.pokemon.offline.account.api.vo;

/**
 * 登录请求视图对象，用于接收本地账号凭据
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:01
 */
public class LoginVO {

    /** 本地账号名 */
    public String username;

    /** 登录密码 */
    public String password;
}
