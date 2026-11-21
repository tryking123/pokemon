package com.pokemon.local.bootstrap;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pokemon.local.bootstrap.api.vo.RoleHistoryVO;
import com.pokemon.local.role.RoleService;
import com.pokemon.local.role.api.entity.LocalRole;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Unity 启动配置、区服与资源 ServiceImpl
 *
 * @author tongruiyuan
 * @since 2026-11-18 17:42
 */
@Service
public class BootstrapServiceImpl implements BootstrapService {

    private static final String CLIENT_HTTP = "http://127.0.0.1:8080";
    public static final int EMBEDDED_HTTP_PORT = 8081;
    public static final int EMBEDDED_GAME_PORT = 9003;
    private static final int REALM_ID = 1;

    private final BootstrapAssets assets;
    private final RoleService roles;
    private final ObjectMapper json;
    private final String gameHost;
    private final int gamePort;
    private final boolean unityLogger;
    private String httpBase = CLIENT_HTTP;

    /**
     * 初始化 Unity 启动业务。
     *
     * @param assets 底包资源目录
     * @param roles 本地角色服务
     * @param json JSON 序列化器
     * @param gameHost 向客户端公布的游戏 TCP 地址
     * @param gamePort 向客户端公布的游戏 TCP 端口
     * @param unityLogger 是否打开 Unity 日志
     */
    public BootstrapServiceImpl(BootstrapAssets assets, RoleService roles, ObjectMapper json,
                                @Value("${POKEMON_GAME_PUBLIC_HOST:127.0.0.1}") String gameHost,
                                @Value("${POKEMON_GAME_PUBLIC_PORT:9002}") int gamePort,
                                @Value("${POKEMON_UNITY_LOGGER:false}") boolean unityLogger) {
        if (!"127.0.0.1".equals(gameHost) || gamePort < 1 || gamePort > 65535) {
            throw new IllegalArgumentException("向客户端公布的游戏 TCP 地址须为 127.0.0.1，端口须在 1 到 65535 之间");
        }
        this.assets = assets;
        this.roles = roles;
        this.json = json;
        this.gameHost = gameHost;
        this.gamePort = gamePort;
        this.unityLogger = unityLogger;
    }

    /**
     * 构建面向当前已安装 APK 的 Unity 引导服务。
     *
     * @param apkPath 当前已安装应用的 APK 路径
     * @param roles 单机角色存档
     * @param json 序列化器
     * @return 从应用自身提供引导资源的实例
     */
    public static BootstrapService forInstalledApk(String apkPath, RoleService roles, ObjectMapper json) {
        BootstrapServiceImpl embedded = new BootstrapServiceImpl(BootstrapAssets.fromInstalledApk(apkPath), roles, json,
                "127.0.0.1", EMBEDDED_GAME_PORT, false);
        embedded.httpBase = "http://127.0.0.1:" + EMBEDDED_HTTP_PORT;
        return embedded;
    }

    /**
     * 获取 Unity 启动配置文本。
     *
     * @return 客户端按键值分隔符读取的启动配置
     */
    @Override
    public String configuration() {
        return "ASSET#" + httpBase + "/resources/\n"
                + "DATA#" + httpBase + "/data/\n"
                + "SERVER#" + httpBase + "/realmlist\n"
                + "NOTICE#" + httpBase + "/notice.txt\n"
                + "WEB#" + httpBase + "/\n"
                + "LOGGER#" + (unityLogger ? "1" : "0") + "\nDEBUG#0\n";
    }

    /**
     * 获取本地区服表。
     *
     * @return Lua 字面量形式的单区列表
     */
    @Override
    public String realms() {
        return "return {{range=\"本地\",list={{id=" + REALM_ID
                + ",name=\"本地区\",host=\"" + gameHost + "\",port=" + gamePort + ",state=1,new=true}}}}\n";
    }

    /**
     * 返回原公告弹窗使用的本地游戏说明。
     *
     * @return 原客户端直接显示的 UTF-8 正文
     */
    @Override
    public String notice() {
        return "角色、精灵和游戏进度保存在本设备。请使用工作台的本地账号进入「本地区」。\n\n"
                + "游戏内充值为模拟操作，不会产生实际扣款。";
    }

    /**
     * 查询选服界面所需的历史角色。
     *
     * @param vo Unity 传入的账号和渠道
     * @return 同时适配有角色和无角色的 JSON 数组
     */
    @Override
    public Object roleHistory(RoleHistoryVO vo) {
        String account = vo.getAccount();
        if (account == null || account.isEmpty() || account.length() > 64) {
            return Collections.emptyList();
        }
        Optional<LocalRole> role = roles.findRole(account, REALM_ID);
        if (!role.isPresent()) {
            return Collections.emptyList();
        }
        LocalRole character = role.get();
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("serverid", character.realmId);
        item.put("name", character.name);
        item.put("level", character.level);
        try {
            return Collections.singletonList(json.writeValueAsString(item));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法序列化本地角色历史", exception);
        }
    }

    /**
     * 获取清单或清单允许的原始逻辑文件。
     *
     * @param data 是否查找数据文件清单
     * @param logicalName 客户端使用的逻辑文件名
     * @return 校验通过的原始逻辑文件内容
     */
    @Override
    public byte[] resource(boolean data, String logicalName) {
        return assets.read(data, logicalName);
    }
}
