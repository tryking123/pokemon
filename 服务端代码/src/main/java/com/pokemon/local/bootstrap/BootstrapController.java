package com.pokemon.local.bootstrap;

import com.pokemon.local.bootstrap.api.vo.RoleHistoryVO;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unity 启动配置、区服与资源接口
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
@RestController
public class BootstrapController {

    private static final String TEXT = "text/plain;charset=UTF-8";
    private final BootstrapService bootstrap;

    /**
     * 初始化启动接口。
     *
     * @param bootstrap Unity 启动服务
     */
    public BootstrapController(BootstrapService bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * 返回 Unity 版本 1.0.1 的启动配置。
     *
     * @return UTF-8 配置文件
     */
    @GetMapping(value = "/1.0.1/config.txt", produces = TEXT)
    public String configuration() {
        return bootstrap.configuration();
    }

    /**
     * 返回客户端用来展示区服并建立连接的列表。
     *
     * @return UTF-8 Lua 区服表
     */
    @GetMapping(value = "/realmlist", produces = TEXT)
    public String realms() {
        return bootstrap.realms();
    }

    /**
     * 返回游戏公告弹窗使用的本地文本。
     *
     * @return UTF-8 单机公告
     */
    @GetMapping(value = "/notice.txt", produces = TEXT)
    public String notice() {
        return bootstrap.notice();
    }

    /**
     * 返回区服选择界面中的历史角色。
     *
     * @param vo 历史角色查询参数
     * @return 兼容旧客户端的历史角色数组
     */
    @GetMapping(value = "/get_my_plyer", produces = MediaType.APPLICATION_JSON_VALUE)
    public Object roleHistory(RoleHistoryVO vo) {
        return bootstrap.roleHistory(vo);
    }

    /**
     * 返回 Android 资源清单。
     *
     * @return 原始资源清单正文
     */
    @GetMapping(value = "/resources/android/version.txt", produces = TEXT)
    public ResponseEntity<byte[]> assetManifest() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(TEXT))
                .body(bootstrap.resource(false, "version.txt"));
    }

    /**
     * 返回游戏数据清单。
     *
     * @return 原始游戏数据清单正文
     */
    @GetMapping(value = "/data/xversion.txt", produces = TEXT)
    public ResponseEntity<byte[]> dataManifest() {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(TEXT))
                .body(bootstrap.resource(true, "xversion.txt"));
    }

    /**
     * 返回 Android 资源清单中的单个逻辑文件。
     *
     * @param logicalName 逻辑文件名
     * @return 逻辑文件的二进制数据
     */
    @GetMapping(value = "/resources/android/{logicalName:.+}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] asset(@PathVariable("logicalName") String logicalName) {
        return bootstrap.resource(false, logicalName);
    }

    /**
     * 返回游戏数据清单中的单个逻辑文件。
     *
     * @param logicalName 逻辑文件名
     * @return 逻辑文件的二进制数据
     */
    @GetMapping(value = "/data/{logicalName:.+}", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public byte[] data(@PathVariable("logicalName") String logicalName) {
        return bootstrap.resource(true, logicalName);
    }
}
