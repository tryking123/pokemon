package com.pokemon.offline.bootstrap.api.vo;

/**
 * Unity 选服界面的历史角色查询参数
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
public class RoleHistoryVO {

    private String account;
    private String channel;

    /** @return 本地账号标识 */
    public String getAccount() {
        return account;
    }

    /** @param account 本地账号标识 */
    public void setAccount(String account) {
        this.account = account;
    }

    /** @return 客户端传入的渠道标识 */
    public String getChannel() {
        return channel;
    }

    /** @param channel 客户端传入的渠道标识 */
    public void setChannel(String channel) {
        this.channel = channel;
    }
}
