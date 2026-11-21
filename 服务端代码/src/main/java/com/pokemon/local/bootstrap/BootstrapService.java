package com.pokemon.local.bootstrap;

import com.pokemon.local.bootstrap.api.vo.RoleHistoryVO;

/**
 * Unity 启动配置、区服与资源 Service
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
public interface BootstrapService {

    /**
     * 获取 Unity 启动配置文本。
     *
     * @return 按行给出的启动配置
     */
    String configuration();

    /**
     * 获取本地区服表。
     *
     * @return 客户端可以直接执行的 Lua 区服表
     */
    String realms();

    /**
     * 获取游戏启动窗口显示的单机公告。
     *
     * @return 原客户端直接显示的 UTF-8 正文
     */
    String notice();

    /**
     * 查询选服界面所需的历史角色。
     *
     * @param vo Unity 传入的账号和渠道
     * @return 每项为一条 JSON 文本的数组
     */
    Object roleHistory(RoleHistoryVO vo);

    /**
     * 获取清单或清单允许的原始逻辑文件。
     *
     * @param data 是否查找数据文件清单
     * @param logicalName 客户端使用的逻辑文件名
     * @return 去除底包加密及填充后的实际文件内容
     */
    byte[] resource(boolean data, String logicalName);
}
