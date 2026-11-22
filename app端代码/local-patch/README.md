# 纯离线 App 代码与构建

默认实现规则见仓库 [AGENTS.md](../../AGENTS.md)。App 业务、SQLite 存档和运行配置均独立归属本目录，构建不读取 `服务端代码/`，不运行 Spring、H2 或 PC 服务，不使用 App 内 HTTP/TCP 监听或 ADB 反向转发。

## 代码与数据

- `src/com/qk/local/`：Android 页面、一次触发的模拟支付及 `OfflineBridge.exchange` 直接调用入口。
- `offline/src/com/pokemon/offline/`：App 自有业务、协议分派、SQLite 适配和轻量组件装配。`platform` 是 App 内部实现，不加载同名 Spring 框架。
- `offline/resources/`：权威业务配置、协议和 SQLite 迁移。构建时复制到 `assets/pokemon-local/`。
- `offline-network.lua` 和三个 `offline-*.lua`：Unity 原协议、区服、角色历史、下单的直接调用桥。
- `native/`：ARM64 与 ARMv7 JNI 微桥，没有 socket 或其他动态库依赖。
- `patch-unity-bootstrap.py`：校验固定底包并生成本地文件入口及已验证的原生统计补丁。
- `patch-unity-arena.py`、`patch-unity-offline.py`：保留已接入玩法补丁，再替换双架构 Lua 的网络边界，更新资源及依赖清单。

Unity 使用 `file://` 读取本地启动配置和游戏资源。首次进入前，App 从自身 APK 解密并校验资源，导出到私有 `files/offline-bootstrap/`；metadata 的文件入口在打包时绑定 APK 包名，`/sdcard` 指向当前 Android 用户；App 将小配置文件写入自己的外部私有目录，缓存原样同步 APK metadata 并核对包名，完整资源仍保存在内部私有目录。资源版本标记与资源清单绑定，失败不会写完成标记。账号、角色、订单与游戏进度仍使用私有 `pokemon-offline.db`，不重置玩家数据。

请求与推送沿用原 sproto 编解码和会话顺序，传输为 LuaJIT FFI → JNI → `OfflineBridge` → App 离线业务 → SQLite。业务提交后结果才进入 Unity 消息队列；失败丢弃本次消息。App 内计时器负责已接入玩法和到账推进，Unity 拉取已提交消息，不开网络端口。APK 移除了 INTERNET 与 ACCESS_NETWORK_STATE 权限。

## 构建

使用 JDK 17、API 30 android.jar、支持 16 KB 对齐的 Android Build Tools、Zig 0.13.0、Python 3.11+ 与 UnityPy/pycryptodome。Java 编译只需要第三方 Jackson 2.13.5 的三个 JAR；`MavenRepository` 仅定位这些缓存依赖，不构建 Maven 服务端项目。

以下路径参数按本机工具位置调整：

```powershell
& ./app端代码/local-patch/native/build-native.ps1 -Zig '<zig.exe>' -JdkHome '<JDK目录>'
py ./app端代码/local-patch/patch-unity-bootstrap.py
py ./app端代码/local-patch/prepare_cosmetic_import.py --python-libs '<Python依赖目录>'
py ./app端代码/local-patch/patch_miao_species.py --prepare-only --python-libs '<Python依赖目录>'
py ./app端代码/local-patch/prepare_character_catalog.py --python-libs '<Python依赖目录>'
& ./app端代码/local-patch/build.ps1 -AndroidJar '<android.jar>' -D8Jar '<d8.jar>' -JdkHome '<JDK目录>' -MavenRepository '<第三方JAR缓存目录>'
py ./app包/build_local_apk.py --entries ./app端代码/local-patch/apk-entries --build-tools '<Build Tools目录>' --java '<java.exe>' --python-libs '<Python依赖目录>' --output ./app包/new/pokemon-offline-direct-YYYYMMDD.apk
```

重包脚本从已核验的 `app包/origin/origin.apk` 建立临时目录，先生成已有玩法资源补丁，再叠加直接调用桥；不使用暂存目录中陈旧的 `.ab` 输出。双 ABI JNI、metadata 与原生补丁按预期摘要核验，签名、16 KB 对齐及包内替换项哈希均须通过。DEX 备份保存在忽略的 `build/dex-backups/`，不混入 APK。

`miao-import/` 保存精灵、称号和皮肤的已核对新增行及来源映射。外观准备脚本按名称合并各对比包，保留底包原条目；精灵准备脚本为真·百变怪分配 `64190`，保留密勒顿 `64188`，仅将真·百变怪和小软加入发送与返利兑换。三个百分比小软只供战斗变化。重包脚本通过 `patch_miao_species.py` 叠加双架构数据、最小战斗逻辑及外观资源，重算清单；`patch_imported_images.py` 将来源图像转换为底包的 Unity 序列化格式，复用已核对的原生动画预制体。来源 APK 只供开发时提取，运行无需这些文件。

称号通过 `TitleShow/{show}.prefab` 展示，不能只迁入图标。`patch_title_prefabs.py` 在图标导入后迁入缺失的完整UI对象图，重映射Sprite和字体外部引用；已存在路径须经文字、渐变、布局及引用的语义核对才复用。保留旧对象、原字体包及依赖关系，不将称号预制体降级为静态图替代。

`prepare_character_catalog.py` 从固定底包与已核验精灵迁移表生成 `offline/resources/elf/character.json` 中的物种特性与有效被动目录。`charactername` 是文字编号，不能作为战斗触发；旧培养表未保存特性数组时，从该目录读取真实编号。玩家存档中的合法特性覆盖继续生效，未知编号不进入战斗。

## 模拟支付

工作台点击「模拟充值」，或 Unity 原页面触发购买后，App 校验本地订单并直接执行结算，不再弹出 Android 二次确认框。工作台先持久化客户端订单号；游戏侧读取本地保存的商品、价格和奖励。同一订单重复提交仍只结算一次。工作台模拟余额与游戏角色钱包各自使用原有账本，不相互混淆。

## 验证范围

独立构建时可只复制本目录的 `src`、`embedded`、`offline`、`stubs` 及 `build.ps1` 到临时目录，确认不存在服务端目录后构建。设备上需在无 PC 服务、无 ADB 反向转发且断外网时验证启动、登录建角、支付去重、玩法与冷启动存档。包校验或 Lua mock 不能代替 Unity 实机回归；实际结果以 App 版本记录和本轮验收记录为准。
