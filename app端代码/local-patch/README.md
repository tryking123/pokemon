# Android 单机版

安装 APK 后直接打开，用 `admin/admin` 登录并启动 Unity。首次建角为 1 级、0 经验，已有角色沿用存档等级，存档位于应用私有 SQLite 数据库 `pokemon-offline.db`。运行要求 Android 8.0 及以上，无需启动电脑 Java 服务或配置 ADB 端口。PC 版 H2 存档不会自动导入，卸载应用或清除应用数据会清掉设备上的单机存档。

打包脚本 `app包/build_local_apk.py` 将 DEX、SQLite 表结构及原协议配置加入固定底包。Android 登录、票据和模拟支付直接调用进程内业务。原 Unity 通过应用自身的回环地址获取启动配置和游戏响应，背包、精灵、交易、活动与榜单均复用已经接入的业务实现。模拟订单不扣款。

打包时使用 Python 3.11 或更高版本，并通过本地安装或 `--python-libs` 提供 pycryptodome 和 UnityPy。打包脚本从校验通过的原 APK 重新生成双 ABI 竞技购买和周奖界面、公会数值的资源补丁，将修改追加到临时载体并更新相应的清单区段。

## 构建客户端补丁

使用 JDK 17、Android SDK 平台 30 的 `android.jar` 和 Android Build Tools 的 `lib/d8.jar`。以下命令在此目录执行。

```powershell
.\build.ps1 -AndroidJar '路径\android.jar' -D8Jar '路径\lib\d8.jar' -JdkHome '路径\jdk-17'
python .\patch-unity-bootstrap.py
```

PowerShell 编译工作台、应用适配层和共享 Java 业务，Unity 声明只用于编译。编译从 Maven 本地仓库读取项目已使用的 Jackson 2.13.5 三个 JAR，可通过 `-MavenRepository` 调整路径。Python 脚本从固定底包生成共享 `global-metadata.dat` 和 ARM64、ARMv7 的 `libil2cpp.so` 补丁，把 Unity `ConfigURI` 改为应用监听的 `http://127.0.0.1:8081`，两个 ABI 的渠道统计回调直接返回。TCP `127.0.0.1:9003` 也由应用进程内监听。这两个端口与旧电脑联调版的 ADB 映射相互独立。单机 APK 最低版本为 API 26。

覆盖安装后，Unity 可能保留应用外部文件目录中的 `il2cpp/Metadata/global-metadata.dat`。启动游戏时，客户端将已知原版或旧电脑联调版 SHA-256 的缓存替换成已校验的单机版本。同目录的临时文件写完后才原子替换原文件。初次安装仍由 Unity 自行提取，已更新文件直接使用，未知文件会提示核对版本。

## 联调

1. 安装并打开单机 APK，用 `admin/admin` 登录。
2. 如需验证充值，在 Unity 的原商城发起模拟订单，检查角色余额、VIP 或礼包物品。工作台的模拟钻石单独记在本地账户。
3. 若需要查看运行日志，使用 `adb logcat -s PokemonOffline PokemonLocalBridge LocalGameGateway`。

Unity 通过安装包内部的回环接口读取 `/1.0.1/config.txt`、版本清单和选服脚本，游戏资源来自该安装包。工作台登录走进程内路由。Unity 请求登录时，桥签发短期游戏票据，将 `accountId` 和 `ticket` 分别放入原回调的 `userId`、`userToken`。票据过期后重新登录会再次签发。

工作台保留自己的账户模拟余额。Unity 充值页用原 WWWForm 请求应用内 `POST /getorderid`，也支持游戏协议 `ask_charge 2801`。两路径共用角色的待付订单。Android 确认时根据已保存的商品、价格和奖励结算，把钻石、道具和 VIP 进度写入设备存档。原游戏心跳同步到账及商品状态。

当前支持周卡、月卡、终身卡和成长基金档 1–4，钻石档 5–14，礼包 101/103/104/106，经典首充、限时新手返利和 18 档钻石 VIP 福利。每日充值活动按已确认订单统计，活动积分可以在兑换商店购买原版精灵碎片。支付页面只模拟本地结算。

单人冒险可挑战底包首区域的第 1 至 1,849 关及 21 张挂机地图。冒险榜读取实际进度，地图领奖和快速战斗均写入存档。训练家装备可以穿戴、替换和强化。前 24 座道馆可单人挑战，助阵槽按通关进度解锁。扭蛋活动榜按每轮高级抽取次数统计，达到 200 次可参与排行，周期截止后以系统邮件结算。
