# 守护小精灵本地服务端

## 用 IDEA 启动并连接模拟器

把本目录作为 Maven 模块导入 IDEA，运行 `com.pokemon.local.PokemonLocalServer`。将运行配置的工作目录设为本目录，确保使用同一个 `data/pokemon.mv.db`。在电脑打开 `http://127.0.0.1:8080/health`，返回 `{"status":"UP"}` 即说明服务已启动。

打开并连接 MuMu。转发模拟器的 8080 给本地 HTTP 服务，转发 9002 给 Unity 游戏网关。模拟器重启或 ADB 重连后，若映射丢失，重新执行转发即可。

```powershell
$adb = 'E:/mySorf/MuMuPlayer/nx_main/adb.exe'
& $adb devices -l
& $adb -s '<把这里换成上方列出的序列号>' reverse tcp:8080 tcp:8080
& $adb -s '<把这里换成上方列出的序列号>' reverse tcp:9002 tcp:9002
```

随后在 App 里用 `admin/admin` 登录。默认新建角色从 1 级、0 经验开始，已有角色继续使用存档等级。IDEA 已在运行时可以直接用以上命令。也可以用下方脚本配置转发，脚本会复用已启动的服务。

## 可选的一键联调

打开并连接 MuMu 模拟器，在本目录的 PowerShell 7 执行：

```powershell
.\start-local.ps1
```

脚本复用已就绪的守护小精灵服务，或在需要时构建 JAR、启动服务并等待就绪。随后为选定模拟器配置 8080 和 9002 的转发。出现“已配置”后可尝试登录，首次启动的默认账号和密码均为 `admin`。修改后端后，请用 Maven 重新打包或重新从 IDEA 启动，以加载新的代码。后台启动日志在 `target/local-run/`。

```powershell
.\start-local.ps1 -AdbPath 'E:\mySorf\MuMuPlayer\nx_main\adb.exe' -Serial '<adb devices -l 显示的序列号>'
.\start-local.ps1 -Check
.\start-local.ps1 -HostPort 18080 -GameHostPort 19002
```

- 仅有一台已授权设备时自动选择。多台设备在线时用 `-Serial` 指定；ADB 搜索 PATH、Android SDK 和 MuMu 的常用安装目录，也可用 `-AdbPath` 指定。
- `-Check` 只检查服务和所选设备的转发状态。
- 如果 Fate 等其他服务占用宿主机的端口，指定空闲的 `-HostPort` 或 `-GameHostPort`。App 仍使用 8080 和 9002。脚本报告已有转发指向其他端口时，先检查该映射是否仍在使用。
- 模拟器重启或 ADB 连接重建后，重跑脚本即可恢复转发。

## 手动打包和启动

在本目录使用 Java 8 和 Maven 打包并启动。

```powershell
mvn clean package
java -jar target/pokemon-local-server-1.0.0.jar
```

初次启动后，账号和密码均为 `admin`。HTTP 默认监听 `127.0.0.1:8080`，游戏网关默认监听 `127.0.0.1:9002`。可在电脑访问 `GET /health` 确认 HTTP 正常，然后按上方说明转发两个端口。

H2 将账户、会话、模拟充值订单和钻石余额保存在工作目录的 `data/pokemon.mv.db`。重启时沿用同一数据库文件。会话有效期为 24 小时，注销会撤销调用方使用的会话令牌。

可用环境变量

| 变量 | 默认值 | 用途 |
| --- | --- | --- |
| `POKEMON_BIND` | `127.0.0.1` | HTTP 监听地址 |
| `POKEMON_PORT` | `8080` | HTTP 监听端口 |
| `POKEMON_GAME_PORT` | `9002` | 本机游戏 TCP 监听端口 |
| `POKEMON_GAME_PUBLIC_PORT` | `9002` | 选服表公布的 Android 侧端口。使用 ADB reverse 时保持默认值 |
| `POKEMON_BOOTSTRAP_APK` | 从工作目录向上查找 `app包/origin/origin.apk` | 提供游戏内资源和清单的固定底包路径 |
| `POKEMON_DATA` | `./data/pokemon` | H2 文件路径前缀，无需扩展名 |
| `POKEMON_ADMIN_PASSWORD` | `admin` | 仅新库创建 admin 时采用的密码 |
| `POKEMON_ADMIN_START_LEVEL` | `1` | admin 首次建角等级，支持 1 至 150 |
| `POKEMON_ADMIN_START_STAGE` | `2` | admin 首次建角晋升等阶，0 为初阶，1 为中阶，2 为高阶 |
| `POKEMON_ACTIVITY_START` | 新库首次启动的北京时间日期 | 新库活动起始日，格式 `yyyy-MM-dd`，已有数据库继续使用已保存的起始日 |
| `POKEMON_PRODUCTS` | 内置六个模拟充值档位 | 改为逗号分隔的 `商品ID:价格分:钻石数` |

接口响应为 JSON，接口要求授权时使用 `Authorization: Bearer <token>`。

| 方法和路径 | 授权 | 请求体或参数 | 成功返回 |
| --- | --- | --- | --- |
| `GET /health` | 无 | 无 | `status` |
| `POST /api/auth/login` | 无 | `username`、`password` | `accountId`、`username`、`balance`、`token` |
| `POST /api/auth/logout` | 需要 | 无 | `status` 为 `LOGGED_OUT` |
| `GET /api/account/me` | 需要 | 无 | `accountId`、`username`、`balance` |
| `GET /api/payments/products` | 无 | 无 | `{productId, amount, reward}` 对象数组 |
| `POST /api/payments/orders` | 需要 | `productId`、`clientOrderId` | `orderId`、`status`、`productId`、`amount`、`reward`、`balance` |
| `POST /api/payments/orders/{orderId}/confirm` | 需要 | 路径中的订单编号 | 订单数据和当前余额，`status` 为 `CONFIRMED` |
| `POST /api/game/ticket` | 需要 | 无 | 短期游戏网关 `ticket` 与 `accountId` |

Unity 连接本地 HTTP 引导，依次读取 `/1.0.1/config.txt`、`/resources/android/version.txt`、`/data/xversion.txt` 和 `/realmlist`。资源接口从固定底包提取随包内容，需要保留原 APK。游戏服务支持单区建角和恢复、主城与研究所传送、部分首章任务、初始精灵、教学战、首个单人副本、冒险前 20 关、单次扭蛋、基础商店和邮件。部分关卡难度、抽取顺序与奖励使用本地规则。

原游戏充值在 Unity 商城选择档位，调用本地 `/getorderid`，在 Android 界面确认后将钻石发到角色存档。工作台订单入账户钱包。两处各自用本地订单幂等结算。

活动以北京时间零点为周期边界。运营表的有效活动使用原配置中明确的期限，未指定有限天数的活动默认 7 天。每日在线奖励每天重开，签到每 30 天重开，七日登录、新手充值返利和闯关礼包每 7 天重开。下一轮使用独立的签到、在线和领取记录，已入账的奖励继续保留。开服等级、战力和当期模拟充值榜最多显示 `admin` 对应角色，战力按在阵精灵的基础属性和等级计算。达到原版门槛时由邮件发放榜首奖励。其他活动的具体玩法按缺口清单逐项接入。

`amount` 是以分为单位的模拟价格，`reward` 和 `balance` 是模拟钻石数量。初始余额为 0。默认档位的商品 ID 为 `diamond_60`、`diamond_300`、`diamond_680`、`diamond_1280`、`diamond_3280`、`diamond_6480`，编号中的数字表示发放的钻石数。客户端重试下单须复用 `clientOrderId`，服务端会返回同一订单。结算从订单保存的商品快照取奖励，重复或并发确认时只发放一次。本服务的结算与记账均在本地完成。

在服务运行时，可从 PowerShell 发起一笔新的模拟订单并确认两次。

```powershell
$base = 'http://127.0.0.1:8080'
$loginBody = @{ username = 'admin'; password = 'admin' } | ConvertTo-Json
$login = Invoke-RestMethod "$base/api/auth/login" -Method Post -ContentType 'application/json' -Body $loginBody
$auth = @{ Authorization = "Bearer $($login.token)" }
$orderBody = @{ productId = 'diamond_60'; clientOrderId = [Guid]::NewGuid().ToString() } | ConvertTo-Json
$order = Invoke-RestMethod "$base/api/payments/orders" -Method Post -Headers $auth -ContentType 'application/json' -Body $orderBody
$confirm = "$base/api/payments/orders/$($order.orderId)/confirm"
Invoke-RestMethod $confirm -Method Post -Headers $auth
Invoke-RestMethod $confirm -Method Post -Headers $auth
Invoke-RestMethod "$base/api/account/me" -Headers $auth
```
