# 《守护小精灵》App 端与本地服务端缺口分析

核对日期 2026-11-18。基线为爱趣版 1.1 原 APK（包名 `com.ymfy.shxjl2mdb.aiqu`）及当前本地 APK、Java 服务。服务可直接在 IDEA 启动，App 跑在本地 Android 模拟器上。目标为单区、单玩家的 Unity 玩法，活动按本地周期开放并重新累计，榜单可以只统计 `admin`。PVP、PK、跨服、玩家间交易及协作延后。

## 1. 已有实现与本次联调

2026-11-19 后续迁移按 APK 内置服务执行。当前逐条状态见 [未接入本地行为的接口](未接入本地行为的接口.md)，活动下架和缺失配置见 [暂未展示的运营活动](暂未展示的运营活动.md)。以下内容保留 2026-11-18 的基线和协议取证。

2026-11-19 数值统筹批次接入神秘兑换、消耗活动、每日消费、节日签到和三天终身累充。钻石支出流水与资产在同一事务保存。48 座道馆使用本地扫荡奖档，首区域普通挂机和快速战斗增加道具掉落。运行配置与后续接入条件见 [全局数值规划](../全局数值规划统筹/全局数值规划.md)。

2026-11-19 App 本地存档批次补充免费补签、一次性友情点、外观拥有和选择，以及系统邮件分批领取。道馆扫荡和签到类奖励的超容量道具可进本地系统邮件。原补签实际使用 C130 `ispatch=true`，C131 的 VIP 追加与 C2601 的玩法入口继续取证。

当前发行目标为独立 Android APK。工作台直接调用应用内业务，原 Unity 仍使用原消息格式，通过本设备回环进入应用内协议适配，SQLite 保存角色、订单、精灵和活动。以下电脑服务启动与 H2 资料保留用于旧版兼容及构建参考。电脑旧档尚无自动导入入口。

独立版已接入钻石充值、道具礼包、VIP、经典首充、18 档 VIP 福利、周卡、月卡、终身卡和成长基金。精灵、助阵与装备均从本地存档同步。冒险和排行覆盖首区域 1,849 关、21 张挂机地图，支持首通领奖、普通挂机收益和快速战斗。前 24 座道馆可单人挑战，助阵槽按实际通关解锁。捣蛋南瓜、限时概览、每日充值积分兑换和周期扭蛋榜已接入。底包后续区域与道馆、挂机随机掉落和其他活动专用动作仍待补齐。

本地引导、两份资源清单、单区选服、游戏票据、Sproto 登录和建角已经接通。MuMu 离线验证冷启动、建角进场和重进，期间没有下载差分资源。完整的底包协议定义现已加载，下方仍保留每批接口的取证资料与业务清单。网关可解析原始协议，未实现的动作需继续补业务。

| 模块 | 当前实现 | 仍待实现或联调 |
| --- | --- | --- |
| 地图、精灵及战斗 | 主城、研究所、桐树林与 21 张挂机图传送，教学战、首个单人副本、首区域 1,849 关、冒险快速战斗、精灵升级、主队与助阵、前 24 座道馆 | 后续区域与道馆、其他副本、野外捕捉、随机挂机掉落、道馆驻扎和扫荡奖励、精灵升星及进化、装备进阶和洗练 |
| 资产 | 普通和神秘商城、系统邮件、训练家装备穿戴及强化、45 种有明确普通道具选项的自选礼包、模拟下单与确认 | 其他礼包奖励类型、物品出售、限时商城、代金券和福利卡被动加成 |
| 活动 | 发布 80 项有效运营配置和 25 项限时概览，支持签到、在线奖励、七日登录、新手充值返利、闯关礼包、每日充值积分兑换及捣蛋南瓜。等级、战力、模拟充值、扭蛋榜单按周期结算邮件奖励 | 其他活动的计分、兑换、参与次数、红点、领奖及榜奖。单人友情扭蛋暂无友情点产出 |

周期起点保存在数据库并以北京时间计算。显式期限沿用原活动表，其余默认 7 天。每日在线、30 日签到和七日登录分别使用独立的周期开关和领取记录。等级榜读取角色等级，战力榜按当前阵容计算，充值榜统计活动期内已确认的游戏内本地订单。轮换不清空历史角色、背包或订单账本。

- Java 服务已实现 `GET /health`、`POST /api/auth/login`、`POST /api/auth/logout`、`GET /api/account/me`、`GET /api/payments/products`、`POST /api/payments/orders`、`POST /api/payments/orders/{orderId}/confirm`。默认账号/密码为 `admin/admin`。H2 存储账户、会话、账户余额及本地模拟订单，重复确认只发放一次。
- Android 本地工作台通过模拟器内的 `127.0.0.1:8080` 访问服务端。2026-11-18 复查时，IDEA 启动的宿主机服务已正常，当前 ADB 设备没有反向映射。加上 `adb -s <serial> reverse tcp:8080 tcp:8080` 后，Android 侧 `/health` 返回 200，现有 APK 用已填写的 `admin` 重新登录成功并显示本地钱包和商品。重连后若映射丢失，可重跑上述命令；一键脚本为可选入口。
- 工作台登录成功后可启动 Unity，桥接回调通过 `userToken` 下发仅限本地网关的短期票据。`channelToken` 保持为空。工作台钻石仍属于账户钱包，游戏角色的金币和钻石由独立角色存档下发。

## 2. 按 Fate 的方式拆分服务

参考 `E:/myProjects/game/fate/源码/project_decoded/assets/src/info.lua`、`服务端代码/localserver/src/main/java/com/local/fate/controller/ConfigController.java`、`net/GameTcpServer.java` 和 `application.yml`。Fate 实际采用「客户端一个可配置的 HTTP 引导地址 → HTTP 返回可达的区服与 TCP 地址 → 独立游戏 TCP 服务」。可以复用这一架构、Maven/JAR 启动、健康检查和本机 MuMu ADB 工具。接口和编码保持 Pokémon 的固定格式。Fate 文档里的 `10.0.2.2` 是标准模拟器方案，当前联调继续用已实测的 ADB reverse。

建议 HTTP 保持 `127.0.0.1:8080`，新游戏 TCP 先用另一个未占用的宿主回环端口（例如 `9002`，Fate 使用 `9001`）。为两个端口分别做 reverse，HTTP 选服返回的是**模拟器内可达**的 `127.0.0.1:9002`。监听地址、公布地址和端口分开配置；若多个项目同跑可改宿主端口并保持 Android 端端口固定。

服务端在同一 Java 工程内按 `bootstrap`（配置与资源）、`gateway`（TCP/Sproto）、`account`、`role`、`inventory`、`scene`、`battle`、`quest`、`shop`、`payment` 等职责落位。Controller 负责 HTTP 和参数绑定，TCP 适配层负责消息编解码与分发，业务校验、事务、存档与推送由 Service 统一处理。

## 3. P0：冷启动、资源与选服

| 接口或落点 | 客户端约定与实现要求 |
| --- | --- |
| `GET /1.0.1/config.txt?r=<时间>` | 当前 Unity 使用 `ConfigURI/<AppVersion>/config.txt`。原生 `LoadSetting` 直接设置 Unity AppID `cn.yytx.fkbbds1`、渠道 `ds0051`、应用版本 `1.0.1`、Build 1；Android versionName 是 1.1。虽然存在 `get_SettingPath`，当前流程没有先读取 `Setting.xml`。需要把原生引导地址改成本地（按 ARM64/ARMv7 各自核对），并用文本 `键#值` 每行一项。`LoadConfig` 的网络或 HTTP 错误会等待约 3 秒后重试。 |
| `ASSET`、`DATA`、`SERVER`、`WEB` | `ASSET` 是资源根且尾随 `/`，客户端拼 `android/`。`DATA` 是数据根，未设时用 `ASSET` 加 `data/`。`SERVER` 指向本地选服脚本；`WEB` 在客户端拼接子路径，保留尾随 `/`。正常路径明确设 `DEBUG#0`，`LOGGER#1` 可用于联调。真实完整键集合由启动和功能请求增量确认。 |
| `GET /resources/android/version.txt?r=…`、`GET /data/xversion.txt?r=…` | 路径前缀为本地建议，后缀和清单语法已静态确认。响应文本每行 `逻辑相对文件名\|MD5\|十进制大小`。使用本底包的对应清单正文，保持版本与缓存匹配。 |
| `GET /resources/android/{逻辑文件}`、`GET /data/{逻辑文件}` | 只按清单允许的相对路径提供静态内容，响应为解密后且去填充的原始逻辑文件字节。发出的大小与 MD5 须和对应清单一致。首次提取成功且缓存完整时预期无差分资源 GET；资源损坏或修复时可以从本地重新取。 |
| `GET <SERVER 所配置 URL>?r=…` | `login_model.lua` 用 `loadstring` 执行响应，要求 Lua 源文本：`return { { range = "本地", list = { { id = 1, name = "本地区", host = "127.0.0.1", port = 9002, state = 1, new = true } } } }`。客户端读 `range/list/id/name/host/port/state/new`，选服使用数字 `id/port`，`state` 用于 1–4 的状态图标。响应由服务端固定生成，避免把任意输入拼进 Lua。localhost 和端口为建议值。 |
| `GET <WEB>get_my_plyer?account=…&channel=…` | 选服历史分支读取 JSON，外层为数组、各项也是 JSON 字符串，使用 `serverid/name/level` 填已建角色。新号或无历史可返回兼容的空数组，按 Lua 分支验证。原路径拼写保留 `plyer`。 |
| 凭据交接 | 原生配置、Lua 选服、SDK 及网关的目标全部指向本地后，为 Unity 登录发放本地网关票据（推荐独立、短期、限本地区）。工作台 `/api/auth/login` 的 Bearer 用于本地 REST，会话不得发送给原外部渠道。当前空 token 桥需要改接真实票据，再由网关绑定账户。 |

### 底包已有首启资源

底包 `assets/assets/manifest.ab` 解出 1,655 条 `逻辑名\|物理包名\|偏移\|区段长度`，对应 248 个物理包。已校验逻辑名无重复、物理区段连续且完全覆盖，不能把这些物理包当作各自只含一个 Unity Bundle。逻辑 `version.txt` 位于 `data_004.ab` 偏移 33376、长度 65968；`xversion.txt` 位于 `model_008.ab` 偏移 48560、长度 46256。两表含 1,063 与 590 条，合计 1,653 项已逐项用解密后准确长度和 MD5 与实物核验，余两项就是清单自身。`assetconfig.ab`、`index.ab`、`luaconfig.ab`、Lua 网络定义及数据库表均从底包定位到，包含两种 Lua 架构版本。

`ThreadManager.OnExtractStream` 会按清单从 APK 解密抽取对应架构的文件写入 `<persistentDataPath>/cache/1.0.1/`。`GameManager.CheckVersion` 随后下载两个版本清单，比对缓存文件。MuMu 离线联调从没有游戏资源缓存开始完成了提取和版本校验，HTTP 日志只有两份清单请求，未出现任何差分资源 GET。底包自带版本可以直接用本地引导与清单启动。

## 4. P0：TCP、游戏登录和进入主场景

- 原始网络层为 TCP。每帧是 `2 字节无符号大端 payload 长度 + Sproto packed payload`。Sproto `.package` 有 `type:0 integer` 和 `session:1 integer`；客户端递增并记录 session，响应带相同 session，服务端主动消息按 `s2c` 的 type 分发。两方向各有独立编号，不能按 REST/JSON 或 Fate 的编码接入。端口可连通只是第一步，还要验证拆包、粘包、非法帧长度、不同消息响应、心跳和重连。
- 协议证据来自 `shader_001.ab` 内的逻辑 `lua64_network.ab`（偏移 0、长度 99264），包括 `network.lua`、`proto.lua`、`typedef.lua` 及 `libil2cpp.so` 的 `SocketClient.WriteMessage/OnReceive`。选服与登录取证来自 `files_003.ab` 的 `lua64_module_login.ab`、Lua32 新版登录代码和注册处理。首包完整顺序在下面标为待验证。

| 方向及消息 | 需要补齐的行为 |
| --- | --- |
| C→S `login 1` | 输入包含 `sid/account/appid/channel/platform/version/device/sdk/token`，定义还含 `uid/phoneid/gaid/phonetype/timestamp/facility`。绑定本地票据、区与账户。成功响应**省略** `error`，空字符串在 Lua 里也为真；没角色按原客户端分支返回包含 `404` 的错误，旧登录会打开注册窗口，新版登录可能直接启动默认建角流程。`503` 表示维护。首期单区避免使用 `4009:svrid:ip:port` 重定向。 |
| C→S `register 3` | `{name:string,sex:integer}` → `{errno:integer}`，0 成功。以账户+区保证唯一，初始角色、货币、背包、队伍、引导和任务存档在同一事务内建立，重复消息返回可预期结果。成功时客户端会进入游戏流程。 |
| C→S `ping 2`、`relogin 11`、`ready 4` | `ping` 回显 `ti`；`ready` 无响应，场景脚本实际发送。`relogin` 输入 `sid/uid`，无常规响应结构；单区方案先验证原 UI 是否会走这条路，重连以主 `login` 恢复角色，且重推必要快照。 |
| S→C `character 3` | 角色属性、钻石/金币、等级经验、VIP、体力/行动值、队伍、区服及服务器时间等。具体以 `character` 推送为准；时间与角色 `session` 保持一致。缺省数值只在确认 Lua 有默认值后采用。 |
| S→C `scene 4`、`actor_now_position 20` | 当前场景 ID、`position` 坐标、怪物/NPC/其他交互对象及必要替换 NPC。仅单人时可以不生成其他玩家实体，自己坐标、怪物、门、传送和互动实体要真实存在。 |
| S→C `elfs 13`、`bags 17`、`stamina 18`、`sc_new_teaching 19`、`sc_tasklist 100` | 精灵列表/队列/经验与容量；普通、精灵、携带品背包分别返回形状一致的数据；推送体力日计数、教学和任务/已领章节，起始数据从同版本配置和种子存档生成。 |
| S→C `sc_config_list 700`、`sc_send_activity_status 1101`、`sc_send_operate_activity 10002` 等 | 候选公共配置、活动开关和红点同步。先用空白新档记录实际调用，确定哪些推送是首包必需以及先后关系；功能停用时按原 Lua 的关闭分支与明确状态处理。 |

首包实施顺序建议：网关鉴权并分支建角 → 角色状态与首场景数据 → 客户端加载场景并发送 `ready` → 推送或响应剩余资料。首张 `scene` 必须先发；`scene_model` 收到后查 `scene_proto` 及出生点，`scene_ctrl` 在加载完成后才发 `ready`。角色、库存、精灵等其他推送的相对顺序仍须用干净存档验证。Java 可内部聚合角色快照，再由网关拆成原推送。

## 5. P1–P2：完整单人玩法的接口清单

表内消息名和编号来自底包定义。列出的服务行为是本地应实现的业务。首场景实际必需项、启用等级及推送先后以目标 Lua 回调和模拟器联调为准。活动入口与活动业务按第 1 节的当前实现追踪。

| 优先级 / 模块 | 对应 C→S 请求或动作 | 对应 S→C 推送及服务端职责 |
| --- | --- | --- |
| P1 主城与地图 | `move 5`、`stop 6`、`lookup 7`、`mapJump 8`、`ask_scene_trigger 2210` | `scene 4`、`actor_now_position 20`、`del_scence_obj 12`、`sc_new_trigger 377`。保存地图/坐标与传送资格，处理 NPC、触发器、固定怪物与宝箱，保持单人场景会话。 |
| P1 新手及主线 | `three_choose_one 3201`、`task_submit 1201`、`chapter_submit 1202`、`adventure_mission_open 3037` | `sc_new_teaching 19`、`sc_tasklist 100`、`sc_newtask 101`、`reward 14`。初始三选一只领取一次，保存引导进度、任务状态和章节宝箱。三选一在 `guidepkm_model.lua` 实有引用，`guide_data.lua` 有 `ask_war/hunt`。 |
| P1 剧情战、野战及副本 | `ask_dungeon_info 2207`、`ask_war 2203`、`dungeon_sync 3001`、`dungeon_flee 3002`、`ask_box 2204`；`battle 3013` 按玩法分支 | `ask_war` 返回 `errno/seed/weather/player/monster`，请求带 `muid/elsteamid/ntype`；同步由 Lua `battle_model` 触发。关联服务端战斗尝试、关卡、阵容、随机种子和配置版本，处理次数/体力、退出和重连。按原逻辑同步 `sc_duplicate 371`、`sc_dup_end 911`、`sc_event_battle 378`、`reward 14`。单人结算先验证客户端上报契约，不直接按客户端上报任意发奖。 |
| P1 图鉴、精灵、阵容 | `get_hunting_elf 3101`、`enter_hunting 3102`、`leave_hunting 3103`、`hunt 3104`、`refresh_cur_elf 3105`；`elfs_battle 1501`、`elfs_dismiss 1502`、`elfs_level_up 1503`、`set_battle_team 3025`、`ask_change_team 2211` | `elfs 13`、`elf/elf_battle/elf_position/elf_state 1001–1004`、`sc_send_huntingelf 751`。保存精灵实例、捕获结果与消耗、编队位置、当前队伍和可用队列；后续按表扩展性格、升星、进化、觉醒、携带品。 |
| P1 背包、资源、商店 | `buy_bag_page 1701`、`player_use_item 2401`、`sell_item 233`、`normal_shop_buy 2301`、`fast_buy 3501`；高级商店见索引 | `bags 17`、`reward 14`、`consume 16`、`sc_del_item 300`、`sc_send_normaalshop 702`、`sc_fastbuy_data 1401`。扣库存、加奖励与任务进度同事务，根据目录/限购和资产校验，背包满给实际错误和提示。 |
| P1 邮件、基础福利 | `get_mails 1000`、`get_attach 1001`、`ask_open_mail 1004`、`sign_up/sign_extra/sign_total 130–132`、`login_reward_info 136`、`ask_login_reward 137` | `new_mail 10`、`attendance 437`、`loginAward 438`、`reward 14`。自己/系统邮件和附件领取属于单人，保留已读、领取和过期状态；玩家间发信 `ask_send_mail 1003` 暂缓。签到/福利以业务日及本地开服时间为准。 |
| P1/P2 游戏内模拟充值 | `ask_charge 2801` 接收整数商品 `sid`，返回 `errno/order`，其中 `order` 为 `orderid/fee/cbkurl`。`player_order 5090` 为另一查询入口，触发时机待联调。 | Unity `SDKAdapter.Order/Verify` 会对调用方 URL 发请求，需定向到本地或桥接至同一订单服务。映射原游戏整数充值档位到本地商品，工作台和原 UI 共享同一结算服务。订单绑定角色、服务端价格和奖励快照；发放、账本、状态同事务。提交后按实际客户端分支推 `sc_pay 11`（`orderid/paysid`）、`sc_sdk_pay_rsp 1300`（`attach`）、`reward 14` 或角色资产更新；区分账号钱包与角色钻石，选择明确的统一入账方式。代金券 `10410/10426`、充值积分商城 `164/165`、首充礼包 `148/149` 各有规则。 |
| P2 挂机、扫荡及体力恢复 | `ask_clean 2202`、`ask_fastbattle 2206`、`ask_onhook 2208`、`ask_onhook_award 2209`、`buy_tiredpower 1208`、`ask_dailydup/clean_dailydup 168/169` | `sc_onhook 372`、`sc_fastbattle 373`、`sc_onhook_award 380`、`dailydup 415`、`stamina 18`、`sc_update_tirepower 1210`。保存挂机开始/累计/领取游标、每日次数及世界时间，处理服务重启重算收益，重放领取不能增加资源。 |
| P2 精灵与角色养成 | `release/upgrade/upintimacy/evolution/advance 3003–3007`、`unit/personality/star/skill 3009–3012`、`elfskill_upgrade 3043`、`elf_lock 3022`、`set_retinue/swop_retinue 3044/3045`、`equip_on/equip_enhance/equip_melt/equip_refine_ask/equip_refine_save 2519–2523`、`wear_elf_equiment 5013`、`pet_levelup/pet_starup 10301/10302` | `elf 1001`、`elf_carry 1005`、`elf_awaken 1006`、`equipment 433`、`retinue 447`、`pet 2047`。按角色、精灵、装备、宠物、携带品分别存实例与属性，模板从同版本客户端数据核对；公共扣费、上限、重置和背包逻辑复用。 |
| P2 单人探索与成长活动 | `tower_preview 120`、`challenge_tower 121`、`tower_buff 134`、`refresh_new_secretarea 5051`、`enter_secret 5052`、`finish_secret_reward 5054`、`challenge_space 5101`、`enter_mission 10039`、`choose_elf_travel 5063`、`elf_travel_reward 5066`、`ask_levelgift/do_levelgift 170/171`、`get_online_reward 10000` 等 | `tower_info 430`、`sc_send_secretarea 2008`、`send_travel_info 2019`、`sc_space 2051`、`levelgift 444`。单人挑战和奖励保留，在公共场景、战斗和物品稳定后逐项打开。含玩家掠夺、组队、世界排名的变体独立延后。 |
| P2 常驻活动、兑换与小游戏 | `get_activity_info 5002`、`ask_mission 320`、`get_award 321`、`ask_fesinfo 324`、`gat_fes_award 325`、`do_twisted_egg 1901`、`askpuzzle 344`、`puzzle_start 345`、`new_linkgame 349`；其他按最后的协议索引 | 对应 `sc_send_activity_status 1101`、`sc_send_operate_activity 10002`、`sc_send_twistedegg 200`、`puzzle 820`、`linkgame 821` 等。常驻单人福利按实际规则补，运营档期、广告、旧发行渠道、对战活动先关；菜单仍请求时返回该功能的合法关闭状态。 |

### P3：多人及排行榜

- 实时 PK、竞技匹配、跨服 PK、公会战、组队副本、好友赠送、其他玩家查看/搜索、世界玩家 AOI、聊天、玩家邮件、掠夺与跨服活动均可暂缓。典型入口为 `accept_PK/request_battle/update_battle 3026–3028`、`arena_matching/arena_challenge 104/105`、`friend_find 2501`、`ask_team_list 3670`、`guild_cross_*`、`*matching_p`。先禁用 UI 入口和任务依赖；若首包必须表示公会/好友状态，只提供协议形状相符的无公会/空列表/关闭状态。已有响应结构的动作不得假成功或静默等待，无响应协议不要硬塞同 session 的响应。
- 普通榜 `ask_ranklist 2515` 已支持本地角色的队伍战力、金币财富、精灵战力、等级和实际冒险通关数。输入 `index/page/size`，输出 `errno/index/page/size/list/myinfo/uptm`。徽章点数和爬塔排行等待对应玩法存档。
- 开服榜 `get_level_rank/get_battlepower_rank/get_recharge_rank 10005/10006/10007` 和 `get_twistedegg_rank 10411` 已接本地单角色数据。扭蛋榜每期统计真实的高级单抽与十连抽数，达到 200 抽才上榜，截止按底包榜首奖励发送系统邮件。爬塔、迷宫和拼图仍需补各自的分数来源和结算。

## 6. 数据与实施验收

- 角色由 `account_id + realm_id` 定位，另有稳定的 `role_id`。至少落角色属性/进度、货币与流水、背包栈、精灵与编队、任务/引导、场景、尝试与结算、邮件福利、配置版本、模拟充值订单。账户余额和角色货币不可在同一张账户表中模糊混用。
- 从原底包的 590 个 Lua32/64 数据配置文件核对业务字典和引用，例如 `lua64_database_scene_proto.lua.bytes`、`dungeons_proto`、`fight_proto`、`skill_proto`、`normal_shop_proto`、`sign_proto`。现有基础数据工作簿可交叉核对精灵、物品、碎片、时装和宠物条目。场景、任务、奖励、等级规则继续以原包数据与客户端实际查询为准。
- 双层幂等：Sproto `session` 只匹配连接上的响应。支付用订单 ID，战斗用尝试 ID，任务/签到/邮件用角色+业务来源键。扣费、发奖、进度和记录在同一事务，重试返回已提交结果。按受影响的角色与存档版本做并发控制，数据库提交后发推送，重连可重建快照。
- 分派顺序：①客户端/打包改引导地址及两套 ABI，服务端提供配置、清单、逻辑资源和选服；②网关实现 TCP/Sproto、会话、登录/注册和重连；③角色与地图完成初始快照并保存；④战斗、关卡、任务、背包打通第一次单人循环；⑤原游戏 UI 模拟充值和资产入账对齐；⑥按模块开放剩余常驻单人内容，逐项处理排行榜与多人兼容状态。①和②可并行，③依赖②，④和⑤可在角色落库后并行。
- 交付验证用断开外网的 MuMu 新数据目录：登录 `admin/admin` → 本地配置与清单成功 → 记录实际差分资源 GET 数量 → 选服、建角、见到 NPC 和怪物 → 进一次剧情战或副本、领取奖励并看背包变化 → 从原游戏 UI 结算一笔模拟订单 → App 和服务都重启，角色、位置、进度、余额还原 → 重放同一次支付/领取/结算仍只生效一次；验证无外部渠道或 CDN 请求。

静态证据锚点：`assets/assets/manifest.ab` 与上表的实际物理区段；ARM64 `GameManager.<LoadConfig>d__8.MoveNext`、`DecodeVersionFile`、`ThreadManager.OnExtractStream`、`SocketClient.WriteMessage/OnReceive`；Lua `network/network.lua`、`module/login/login_model.lua`、`module/scene/scene_model.lua`、`module/battle/battle_model.lua`、`module/ranking/ranking_model.lua`。现有 REST 和冷启动结果见项目的 [服务端说明](../服务端代码/README.md) 与 [App 版本记录](../app包/版本迭代_app.md)。

## 7. 底包原始 Sproto 消息索引

供分派子任务和按 ID 查协议。下列索引取自同一底包的 Lua64 Sproto 字符串常量，合计 617 条 C→S、243 条 S→C；同向 ID 唯一，跨方向可重复。索引列入了原包定义的多人、旧活动、测试及地区专用消息。启用范围以第 4–6 节为准，未命中单人路径的消息进入待验证清单。应答列反映定义是否声明 `response`，运行时是否带 `session` 仍应按实际客户端封包处理。

<details>
<summary>C→S 客户端消息（617 条）</summary>

```text
编号    原始消息名                                  声明应答
    1   login                                        有
    2   ping                                         有
    3   register                                     有
    4   ready                                        无
    5   move                                         无
    6   stop                                         无
    7   lookup                                       无
    8   mapJump                                      有
    9   ask_rename                                   有
   10   ask_gift                                     有
   11   relogin                                      无
   12   player_binding                               有
   13   player_untying                               有
   50   guild_basic_create                           有
   51   guild_basic_apply_join                       有
   52   guild_basic_cancel_apply                     有
   53   guild_basic_get_apply_list                   有
   54   guild_basic_apply_result                     有
   55   guild_basic_clear_apply                      有
   56   guild_basic_change_duty                      有
   57   guild_basic_guild_exit                       有
   58   guild_basic_del_menber                       有
   59   guild_basic_open_guild_hmi                   有
   60   guild_basic_get_menber_list                  有
   61   guild_basic_get_guild_list                   有
   62   guild_basic_set_notice                       有
   63   guild_ask_log                                有
   64   guild_search                                 有
   65   guild_donate_donate                          有
   66   banditnum_ask                                有
   67   autoGuildAudit                               有
   68   banditboss_info                              有
   69   banditboss_preview                           有
  100   alone_info                                   有
  101   set_alone_battle                             有
  102   alone_challenge                              有
  103   alone_newmatching                            有
  104   arena_matching                               有
  105   arena_challenge                              有
  106   arena_refresh                                有
  107   elf_king_info                                有
  108   elf_king_set                                 有
  109   elf_king_challenge                           有
  110   arena_buy                                    有
  111   elf_king_buy                                 有
  112   alone_buy                                    有
  113   arena_week                                   有
  120   tower_preview                                有
  121   challenge_tower                              有
  122   mine_start                                   有
  123   mine_loot                                    有
  124   mine_stop                                    有
  125   mine_collect                                 有
  126   mine_info                                    有
  127   bandit_challenge                             有
  128   banditboss_challenge                         有
  129   mine_allinfo                                 有
  130   sign_up                                      有
  131   sign_extra                                   有
  132   sign_total                                   有
  133   vitality_reward                              有
  134   tower_buff                                   有
  136   login_reward_info                            有
  137   ask_login_reward                             有
  138   limitshop_ask                                有
  139   limitshop_buy                                有
  140   convert_ask                                  有
  141   convert_buy                                  有
  142   comsumption_ask                              有
  143   comsumption_buy                              有
  148   ask_firstcharge                              有
  149   do_firstcharge                               有
  150   ask_amasscharge                              有
  151   do_amasscharge                               有
  152   ask_seriescharge                             有
  153   do_seriescharge                              有
  154   ask_singlecharge                             有
  155   do_singlecharge                              有
  157   ask_fund                                     有
  158   do_fund                                      有
  159   ask_monthcard                                有
  160   do_monthcard                                 有
  161   buy_vip_gift                                 有
  162   ask_dailycharge                              有
  163   do_dailycharge                               有
  164   chargeshop_ask                               有
  165   chargeshop_buy                               有
  168   ask_dailydup                                 有
  169   clean_dailydup                               有
  170   ask_levelgift                                有
  171   do_levelgift                                 有
  172   ask_kylin                                    有
  173   do_kylin                                     有
  174   ask_rebate                                   有
  175   sign_rebate                                  有
  176   do_rebate                                    有
  177   ask_carnival                                 有
  178   do_carnival                                  有
  179   ask_flop                                     有
  180   dojo_set                                     有
  181   dojo_attack                                  有
  182   dojo_clean                                   有
  183   set_aid                                      有
  184   swop_aid                                     有
  185   plutus_ask                                   有
  186   plutus_do                                    有
  187   ask_limitelf                                 有
  188   do_limitelf                                  有
  189   limitelf_score                               有
  190   rank_limitelf                                有
  191   hitrun_apply                                 有
  192   ask_dailyamass                               有
  193   do_dailyamass                                有
  194   dailyconsume_ask                             有
  195   dailyconsume_buy                             有
  196   hitrun_speed                                 有
  197   hitrun_attack                                有
  198   hitrun_quit                                  有
  199   hitrun_rank                                  有
  211   rushjob_ask                                  有
  212   rushjob_do                                   有
  215   pika_ask                                     有
  216   pika_do                                      有
  217   pika_total                                   有
  218   pika_buy                                     有
  219   clockin_ask                                  有
  220   clockin_do                                   有
  221   clockin_total                                有
  222   ask_chargegift                               有
  223   do_chargegift                                有
  224   sprint_catalog                               有
  225   sprint_detail                                有
  226   sprint_award                                 有
  227   passport_unlock                              有
  228   passport_reward                              有
  229   passport_convert                             有
  230   passport_buy                                 有
  231   icon_unlock                                  有
  232   icon_change                                  有
  233   sell_item                                    有
  234   league_apply                                 有
  235   league_reward                                有
  236   league_map                                   有
  237   league_group                                 有
  238   league_winner                                有
  239   elite_winner                                 有
  240   elite_like                                   有
  241   elite_map                                    有
  242   elite_reward                                 有
  243   elite_room                                   有
  244   elite_bet                                    有
  245   elite_update                                 有
  246   mythshop_info                                有
  247   mythshop_refresh                             有
  248   mythshop_buy                                 有
  249   league_replay                                有
  250   elite_replay                                 有
  251   pstone_equip                                 有
  252   pstone_refine                                有
  253   pstone_save                                  有
  254   pstone_lock                                  有
  255   pstone_compound                              有
  256   pstone_split                                 有
  257   pstone_refresh                               有
  258   pstone_buy                                   有
  259   ask_vipgift                                  有
  260   do_vipgitf                                   有
  261   invest_ask                                   有
  262   invest_do                                    有
  263   invest_get                                   有
  265   ask_wishwell                                 有
  266   lock_want                                    有
  267   get_want                                     有
  268   dojo_loot                                    有
  269   dojo_buy                                     有
  270   vein_insert                                  有
  271   vein_phase                                   有
  272   zodiac_set                                   有
  273   zodiac_attack                                有
  274   zodiac_getbox                                有
  275   zodiac_refresh                               有
  276   zodiac_buy                                   有
  277   newfirst_info                                有
  278   newfirst_get                                 有
  279   handcraft_ask                                有
  280   handcraft_do                                 有
  281   ask_welfare                                  有
  282   eden_funland                                 有
  283   eden_detail                                  有
  284   eden_buy                                     有
  285   eden_attack                                  有
  286   eden_catch                                   有
  287   lifeamass_ask                                有
  288   lifeamass_do                                 有
  289   daygift_get                                  有
  290   bombshell_attack                             有
  291   ask_privlege                                 有
  292   get_privlege                                 有
  293   dnf_info                                     有
  294   dnf_atk                                      有
  295   dnf_accept                                   有
  296   dnf_rank                                     有
  297   dnf_lotto                                    有
  298   dnf_raffle                                   有
  299   dnf_refresh                                  有
  300   dnf_depart                                   有
  301   dnf_accomplish                               有
  302   dnf_watchbuff                                有
  303   intorun                                      有
  304   reflash_map                                  有
  305   look_at_box                                  有
  306   open_box                                     有
  307   check_and_look                               有
  309   get_start_info                               有
  310   buy_elf_bags                                 有
  311   dynamax_combine                              有
  312   dynamax_upgrade                              有
  313   dynamax_insert                               有
  314   dynamax_unload                               有
  315   dynamax_escalate                             有
  316   dynamax_reset                                有
  320   ask_mission                                  有
  321   get_award                                    有
  322   get_eo_data                                  有
  324   ask_fesinfo                                  有
  325   gat_fes_award                                有
  326   operank_ask                                  有
  327   payme_ask                                    有
  328   payme_do                                     有
  330   rocket_rank                                  有
  331   rocket_set                                   有
  332   rocket_match                                 有
  333   rocket_duel                                  有
  334   rocket_log                                   有
  335   rocket_buy                                   有
  336   rocket_reward                                有
  337   matrix_build                                 有
  338   matrix_dismantle                             有
  339   matrix_expend                                有
  340   matrix_mine                                  有
  341   orange_enter                                 有
  342   orange_battle                                有
  343   homeshop_buy                                 有
  344   askpuzzle                                    有
  345   puzzle_start                                 有
  346   puzzle_click                                 有
  347   puzzle_quit                                  有
  348   puzzle_rank                                  有
  349   new_linkgame                                 有
  350   linkgame_click                               有
  351   linkgame_quit                                有
  352   linkgame_buy                                 有
  353   linkgame_rank                                有
  354   race_newtrack                                有
  355   race_buy                                     有
  356   race_bet                                     有
  357   race_start                                   有
  358   race_cheer                                   有
  359   race_quit                                    有
  360   race_rank                                    有
  361   feedlot_levelup                              有
  362   feedlot_foster                               有
  363   feedlot_cancel                               有
  364   feedlot_collect                              有
  365   feedlot_interact                             有
  368   puzzle_buy                                   有
  369   feedlot_accelerate                           有
  370   ask_alwaysamass                              有
  371   do_alwaysamass                               有
  372   orange_clean                                 有
  373   riddle_get                                   有
  374   riddle_buy                                   有
  375   riddle_elect                                 有
  376   riddle_fraud                                 有
  377   voucher_use                                  有
  378   voucher_sell                                 有
  379   pumpkin_ask                                  有
  380   pumpkin_select                               有
  381   pumpkin_click                                有
  382   elf_equiment_lock                            有
  384   xmastree_ask                                 有
  385   xmastree_button                              有
  386   xmastree_shop                                有
  387   xmastree_box                                 有
  388   xmastree_rank                                有
  389   luckybag_open                                有
  390   fesfund_unlock                               有
  391   fesfund_award                                有
  393   casino_enter                                 有
  394   casino_getcard                               有
  395   casino_stopcard                              有
  396   casino_settle                                有
  397   casino_getbox                                有
  398   casino_buy                                   有
  399   bbox_open                                    有
  400   hollar_tear                                  有
  401   hollar_accept                                有
  402   antiquity_map                                有
  403   antiquity_shovel                             有
  404   tablet_click                                 有
  405   tablet_lvlup                                 有
  406   emblem_lvlup                                 有
  407   ancient_unlock                               有
  408   ancient_lvlup                                有
  409   fossilstone_lvlup                            有
  410   fossilsuit_lvlup                             有
  411   puzzle_clean                                 有
  412   linkgame_clean                               有
  413   groupon_ask                                  有
  414   groupon_get                                  有
  417   pcl_active                                   有
  418   pcl_inject                                   有
  419   pcl_upgrade                                  有
  420   pcl_reset                                    有
  421   vnamass_ask                                  有
  422   vngift_ask                                   有
  423   paymev2_ask                                  有
  424   paymev2_do                                   有
 1000   get_mails                                    有
 1001   get_attach                                   有
 1003   ask_send_mail                                有
 1004   ask_open_mail                                有
 1201   task_submit                                  有
 1202   chapter_submit                               有
 1208   buy_tiredpower                               有
 1301   ask_chats_send                               有
 1401   union_create                                 有
 1402   union_list                                   有
 1403   union_change_intro                           有
 1501   elfs_battle                                  有
 1502   elfs_dismiss                                 有
 1503   elfs_level_up                                有
 1504   elfs_level_up10                              有
 1701   buy_bag_page                                 有
 1901   do_twisted_egg                               有
 2001   ask_eq_title                                 有
 2002   ask_display                                  有
 2037   carry_shop_buy                               有
 2101   marquee_test                                 无
 2202   ask_clean                                    有
 2203   ask_war                                      有
 2204   ask_box                                      有
 2206   ask_fastbattle                               有
 2207   ask_dungeon_info                             有
 2208   ask_onhook                                   有
 2209   ask_onhook_award                             有
 2210   ask_scene_trigger                            有
 2211   ask_change_team                              有
 2216   ask_reject_invide                            有
 2301   normal_shop_buy                              有
 2302   mysterious_shop_buy                          有
 2303   mysterious_shop_refresh                      有
 2304   pkpoint_shop_buy                             有
 2305   contribute_shop_buy                          有
 2306   djpoint_shop_buy                             有
 2401   player_use_item                              有
 2501   friend_find                                  有
 2502   friend_apply                                 有
 2503   friend_dispose_apply_res                     有
 2504   friend_del                                   有
 2505   get_friend_info                              有
 2506   friendship_give                              有
 2507   friendship_get                               有
 2508   release_wish                                 有
 2509   recall_wish                                  有
 2510   check_wish                                   有
 2511   check_debris_bags                            有
 2512   give_debris                                  有
 2515   ask_ranklist                                 有
 2516   watch_player                                 有
 2517   ask_robot                                    有
 2519   equip_on                                     有
 2520   equip_enhance                                有
 2521   equip_melt                                   有
 2522   equip_refine_ask                             有
 2523   equip_refine_save                            有
 2524   equip_refresh                                有
 2525   equip_forge                                  有
 2526   invite_rinia                                 有
 2527   response_rinia                               有
 2528   engraving                                    有
 2529   sellsuit_eq                                  有
 2530   runecargo_refresh                            有
 2531   runecargo_buy                                有
 2532   runecargo_lock                               有
 2533   runecargo_split                              有
 2534   equip_up_grade                               有
 2535   get_develop_level                            有
 2601   ask_getsign7day                              有
 2801   ask_charge                                   有
 3000   test_battle                                  有
 3001   dungeon_sync                                 无
 3002   dungeon_flee                                 无
 3003   release                                      有
 3004   upgrade                                      有
 3005   upintimacy                                   有
 3006   evolution                                    有
 3007   advance                                      有
 3008   strive                                       有
 3009   unit                                         有
 3010   personality                                  有
 3011   star                                         有
 3012   skill                                        有
 3013   battle                                       有
 3014   quitbattle                                   有
 3015   setposition                                  有
 3016   upgrade_position                             有
 3017   put_down_skill                               有
 3018   elf_state_get_info                           有
 3019   apparel                                      有
 3020   inherit                                      有
 3021   random_buy                                   有
 3022   elf_lock                                     有
 3023   arrangement                                  有
 3024   carry_protect                                有
 3025   set_battle_team                              有
 3026   accept_PK                                    有
 3027   request_battle                               有
 3028   update_battle                                有
 3032   play_end                                     有
 3034   player_batttle_flee                          有
 3035   req_elf_awaken                               有
 3036   elf_be_reborn                                有
 3037   adventure_mission_open                       有
 3038   adventure_receive_normal                     有
 3039   adventure_quicken                            有
 3040   adventure_change_scene                       有
 3041   map_complete                                 有
 3042   fast_hangup                                  有
 3043   elfskill_upgrade                             有
 3044   set_retinue                                  有
 3045   swop_retinue                                 有
 3101   get_hunting_elf                              有
 3102   enter_hunting                                有
 3103   leave_hunting                                有
 3104   hunt                                         有
 3105   refresh_cur_elf                              有
 3201   three_choose_one                             有
 3450   ask_buy_dress                                有
 3451   ask_carry_dress                              有
 3452   ask_compound_dress                           有
 3501   fast_buy                                     有
 3551   hit_new_elfmap                               有
 3651   challenge_worldboss                          有
 3652   get_worldboss_reward                         有
 3653   get_worldboss_info                           有
 3670   ask_team_list                                有
 3671   ask_create_team                              有
 3672   query_team                                   有
 3673   ask_join_team                                有
 3674   ask_fast_join_team                           有
 3675   ask_quit_team                                有
 3676   ask_dissmis_team                             有
 3677   ask_invite_personal                          有
 3678   ask_invite_union                             有
 3679   ask_invite_server                            有
 3680   ask_del_member                               有
 3681   ask_turn_job                                 有
 3683   ask_team_war                                 有
 3684   ask_team_ready                               有
 3686   ask_team_times                               有
 5002   get_activity_info                            有
 5011   elf_equiment_devour                          有
 5012   elf_equiment_synthetic                       有
 5013   wear_elf_equiment                            有
 5014   washs_elf_equiment                           有
 5015   debris_synthetic                             有
 5016   save_washs_attr                              有
 5017   elf_equiment_decomposition                   有
 5051   refresh_new_secretarea                       有
 5052   enter_secret                                 有
 5053   hit_cover                                    有
 5054   finish_secret_reward                         有
 5055   buy_secret_item                              有
 5056   get_secert_item                              有
 5057   get_statistical_secret                       有
 5058   buy_secret_dupitem                           有
 5059   use_resurrection                             有
 5060   use_fly_card                                 有
 5061   use_baozi_card                               有
 5062   buy_num                                      有
 5063   choose_elf_travel                            有
 5064   make_new_travel_group                        有
 5065   add_travel_speed                             有
 5066   elf_travel_reward                            有
 5067   pray                                         有
 5069   elf_travel_battle                            有
 5070   guildboss_challenge                          有
 5071   open_guild_boss                              有
 5072   get_guildboos_group                          有
 5077   buy_action_num                               有
 5078   refresh_bento                                有
 5079   carry_synthetic                              有
 5080   open_mirror_box                              有
 5081   open_mirror_box_onekey                       有
 5082   get_mirror_info                              有
 5083   kill_monster                                 有
 5090   player_order                                 有
 5091   get_exp_back                                 有
 5092   get_team_log                                 有
 5093   get_reward_back                              有
 5094   open_all_cover                               有
 5095   buy_elf_travel_num                           有
 5096   monopoly                                     有
 5098   get_monopoly                                 有
 5099   treatment                                    有
 5100   elf_fit                                      有
 5101   challenge_space                              有
 5102   get_space_rank                               有
 5103   open_space_stage_reward                      有
 5104   get_space_stage_reward                       有
10000   get_online_reward                            有
10001   get_online_reward_info                       有
10005   get_level_rank                               有
10006   get_battlepower_rank                         有
10007   get_recharge_rank                            有
10008   hit_redpoint                                 有
10009   get_elf_come_rank                            有
10010   get_master_elf_come_rank                     有
10012   get_challenge_bountyboss_info                有
10013   get_exchange_bountyboss_info                 有
10014   get_bountyboss_rank                          有
10015   get_leiji_bountyboss_info                    有
10016   challenge_bountyboss                         有
10017   refresh_bountyboss                           有
10018   exchange_bountyboss                          有
10019   get_leiji_bountyboss                         有
10020   buy_challenge_bountyboss                     有
10021   get_master_turn_table_rank                   有
10022   get_master_turn_rable_turn                   有
10023   get_master_turn_rable_shop                   有
10024   master_turn_table_turn                       有
10025   master_turn_rable_shop                       有
10026   get_maze_rank                                有
10027   get_maze_shop                                有
10028   get_maze_map                                 有
10029   maze_throw                                   有
10030   open_maze_box                                有
10031   buy_maze_throw                               有
10032   buy_maze_shop                                有
10033   get_master_worldboss_info                    有
10034   challenge_master_worldboss                   有
10035   get_master_worldboss_reward                  有
10036   buy_challenge_master_worldboss               有
10037   refresh_twistedegg                           有
10038   get_nest_mission                             有
10039   enter_mission                                有
10040   nest_move                                    有
10041   quit_nest                                    有
10042   nest_refresh                                 有
10043   vn5starGet                                   有
10050   get_diamondsrob_hotgoods_show                有
10051   get_diamondsrob_hotgoods_now                 有
10052   get_diamondsrob_hotgoods_shop                有
10053   buy_diamondsrob_hotgoods_shop                有
10054   diamondsrob_hotgoods                         有
10100   update_battle_m                              有
10101   play_end_m                                   有
10102   player_batttle_flee_m                        有
10103   add_matching_p                               有
10104   del_matching_p                               有
10105   get_now_datainfo                             有
10106   get_old_datainfo                             有
10108   receive_duan_award                           有
10109   receive_everyday_award                       有
10201   get_smashegg_info                            有
10202   get_smashegg_rank                            有
10203   get_smashegg_reward                          有
10204   buy_smashegg_item                            有
10205   smashegg                                     有
10250   watch_advertising                            有
10251   complete_watch_advertising                   有
10271   get_master_recharge_rank                     有
10301   pet_levelup                                  有
10302   pet_starup                                   有
10304   pet_washs                                    有
10305   pet_battle                                   有
10306   pet_out_battle                               有
10307   add_pet_washtime                             有
10308   pet_free                                     有
10309   save_pet_wash_data                           有
10406   binding                                      有
10407   binding_info                                 有
10410   voucher_recharge                             有
10411   get_twistedegg_rank                          有
10412   binding_gift                                 有
10413   c2s_ask_vnspf_exchange_conf                  有
10414   c2s_vnspf_exchange                           有
10415   vnspf_giftbox_ask                            有
10416   vnspf_giftbox_do                             有
10417   vnspf_login_reward_info                      有
10418   ask_vnspf_login_reward                       有
10419   vnspf_recharge_reward_info                   有
10420   ask_vnspf_recharge_reward                    有
10421   vnspf_turntab_reward_info                    有
10422   vnspf_turntab_start                          有
10423   voucher_dayfree_reward_info                  有
10424   ask_voucher_dayfree_reward                   有
10425   voucher_daygift_ask                          有
10426   recharge_with_voucher                        有
20001   guild_cross_battle_info_all                  有
20002   guild_cross_require_defend_info              有
20003   guild_cross_require_attack_info              有
20004   guild_cross_require_defend_site_info         有
20005   guild_cross_require_defend_video             有
20006   guild_cross_now_rank                         有
20007   guild_cross_require_old_rank_list            有
20008   guild_cross_require_old_rank_info            有
20009   guild_cross_attack                           有
20010   guild_cross_require_all_against_lsit         有
20011   guild_cross_require_all_war_report           有
20012   guild_cross_require_attak_site_info          有
20013   guild_cross_win_card_show                    有
20014   guild_cross_win_card                         有
20015   guild_cross_get_times_award                  有
20020   gold_carry_upstar                            有
20021   gold_carry_up_down                           有
```

</details>

<details>
<summary>S→C 服务端消息（243 条）</summary>

```text
编号    原始消息名                                  声明应答
    1   progress                                     无
    2   exception                                    无
    3   character                                    无
    4   scene                                        无
    5   aoi_addnew                                   无
    6   aoi_remove                                   无
    7   aoi_update                                   无
    8   sc_updateday                                 无
    9   sc_weather                                   无
   10   new_mail                                     无
   11   sc_pay                                       无
   12   del_scence_obj                               无
   13   elfs                                         无
   14   reward                                       无
   15   reward_flag                                  无
   16   consume                                      无
   17   bags                                         无
   18   stamina                                      无
   19   sc_new_teaching                              无
   20   actor_now_position                           无
   50   guild_basic                                  无
   51   guild_apply_add                              无
   52   guild_apply_result                           无
   53   guild_by_del                                 无
   54   guild_change_duty                            无
   55   guild_donate                                 无
  100   sc_tasklist                                  无
  101   sc_newtask                                   无
  111   sc_bufflist                                  无
  112   sc_hitrun_people                             无
  113   sc_hitrun_start                              无
  114   sc_hitrun_battle                             无
  115   sc_hitrun_hp                                 无
  116   sc_hitrun_end                                无
  119   sc_hitrun_pk                                 无
  120   sc_gbattle_pk                                无
  200   sc_send_twistedegg                           无
  201   sc_reset_twistedegg                          无
  251   sc_chats_send                                无
  252   old_guildchats_send                          无
  300   sc_del_item                                  无
  350   sc_titles                                    无
  351   sc_new_title                                 无
  352   sc_update_title                              无
  371   sc_duplicate                                 无
  372   sc_onhook                                    无
  373   sc_fastbattle                                无
  375   sc_onhook_special                            无
  376   sc_onhook_monster                            无
  377   sc_new_trigger                               无
  378   sc_event_battle                              无
  379   sc_dup_hpinfo                                无
  380   sc_onhook_award                              无
  401   friend_reset                                 无
  402   friend_apply_req                             无
  403   friend_apply_fail                            无
  404   friend_apply_succeed                         无
  405   friend_by_del                                无
  406   receive_friendship_give                      无
  407   chat_of_friend                               无
  408   broad_wish                                   无
  409   del_wish_to_other                            无
  411   send_debris_bags                             无
  413   sc_vitality                                  无
  415   dailydup                                     无
  420   alone_battle                                 无
  421   arena_win                                    无
  422   arenamessage                                 无
  423   elf_king                                     无
  424   sc_dojo                                      无
  425   elf_aid                                      无
  426   dojo_info                                    无
  427   dojo_buyinfo                                 无
  428   grun_box                                     无
  430   tower_info                                   无
  431   my_mine                                      无
  432   mine_quit                                    无
  433   equipment                                    无
  434   guild_bandit                                 无
  435   mine_update                                  无
  436   mine_refresh                                 无
  437   attendance                                   无
  438   loginAward                                   无
  439   firstcharge                                  无
  440   vipinfo                                      无
  441   vip_add                                      无
  442   rinia_result                                 无
  443   fund                                         无
  444   levelgift                                    无
  445   suit_equip                                   无
  447   retinue                                      无
  448   retinue_intimacy                             无
  449   sprint                                       无
  450   lifeamass                                    无
  451   daygift                                      无
  452   bombshell                                    无
  453   dnf                                          无
  454   dnf_result                                   无
  457   sc_rocket                                    无
  458   sc_rocketnext                                无
  459   sc_rocket_result                             无
  460   luckybag                                     无
  461   fesfund                                      无
  462   casino                                       无
  463   casino_box                                   无
  464   hollar                                       无
  465   antiquity                                    无
  466   tablets                                      无
  467   tablet_drop                                  无
  468   emblem                                       无
  469   fossil                                       无
  700   sc_config_list                               无
  701   sc_send_mysterious                           无
  702   sc_send_normaalshop                          无
  703   sc_send_pkpointshop                          无
  704   sc_send_contributeshop                       无
  705   sc_send_djpointshop                          无
  706   sc_send_carryshop                            无
  751   sc_send_huntingelf                           无
  801   sc_refresh_evnet                             无
  802   sc_send_elfmap                               无
  803   runecargo                                    无
  804   passport                                     无
  805   icon_new                                     无
  806   icon_list                                    无
  807   league                                       无
  808   powerstone                                   无
  810   zodiac                                       无
  811   newfirst                                     无
  812   welfare                                      无
  813   eden                                         无
  815   limitgift                                    无
  817   matrix                                       无
  818   feedlot                                      无
  819   homeshop                                     无
  820   puzzle                                       无
  821   linkgame                                     无
  822   sc_race                                      无
  823   raceprocess                                  无
  824   orange_result                                无
  825   orange_push                                  无
  826   orange_progress                              无
  827   orange_online                                无
  828   alwaysamass                                  无
  829   riddle                                       无
  830   itemcell                                     无
  850   sc_dress_data                                无
  911   sc_dup_end                                   无
 1001   elf                                          无
 1002   elf_battle                                   无
 1003   elf_position                                 无
 1004   elf_state                                    无
 1005   elf_carry                                    无
 1006   elf_awaken                                   无
 1007   hangup                                       无
 1008   adventure_catch_event                        有
 1051   sc_battle_stop                               无
 1101   sc_send_activity_status                      无
 1160   sc_update_teaminfo                           无
 1161   sc_invite_team                               无
 1162   sc_turn_info                                 无
 1163   sc_battle_data                               无
 1164   sc_object_pos                                无
 1165   sc_member_award                              无
 1166   sc_notify_kf                                 无
 1167   sc_team_tips                                 无
 1171   sc_group_monster_award_view                  无
 1210   sc_update_tirepower                          无
 1300   sc_sdk_pay_rsp                               无
 1401   sc_fastbuy_data                              无
 1402   sc_vip_item_buy                              无
 1451   sc_secret_area_duplicate                     无
 2000   end_immediate                                无
 2001   accept_battle                                无
 2002   opposite_operation                           无
 2005   other_play_end                               无
 2008   sc_send_secretarea                           无
 2009   sc_secret_challenge                          无
 2010   sc_finish_secret                             无
 2011   update_secret_buff                           无
 2012   sc_statistical_secret                        无
 2013   sc_friend                                    无
 2014   sc_send_secret_buff                          无
 2015   sc_send_secret_boss                          无
 2016   sc_send_bags_full                            无
 2017   sc_send_secret_useitem_info                  无
 2018   sc_bigtallup                                 无
 2019   send_travel_info                             无
 2020   sc_send_three_chose_one                      无
 2022   sc_travelevent_id                            无
 2023   sc_travel_battle                             无
 2024   sc_guildboss_status                          无
 2025   sc_tollgate_guildboss                        无
 2026   sc_unlock_guildboss                          无
 2027   sc_guildboss_challengenum                    无
 2030   sc_guildbiss_damage                          无
 2031   sc_randevent_data                            无
 2032   send_mirror                                  无
 2033   exp_back                                     无
 2034   sc_rewardback_info                           无
 2035   sc_wear_elf_equiment                         无
 2036   sc_secret_boss_dead                          无
 2037   secret_area_boss_dead                        无
 2038   sc_secret_bare_pos                           无
 2039   sc_specail_twistedegg                        无
 2040   sc_send_monopoly                             无
 2041   sc_send_dice                                 无
 2042   sc_send_teamboss_challenge_num               无
 2043   sc_nest_strength                             无
 2044   refresh_nest                                 无
 2045   in_nest                                      无
 2046   clean_nest_event                             无
 2047   pet                                          无
 2048   sc_pet_battle                                无
 2049   sc_pet_rocket_battle                         无
 2051   sc_space                                     无
 2053   sc_vn5star                                   无
 2054   sc_vngift                                    无
 2056   sc_binding_gift                              无
10000   sc_send_remaining_time                       无
10002   sc_send_operate_activity                     无
10003   sc_send_operate_activity_redpoint            无
10004   sc_send_master_operate_activity              无
10005   sc_maze_box                                  无
10006   send_master_worldboss                        无
10007   send_master_diamondsrob_openreward           无
10008   advertising                                  无
10009   send_master_diamondsrob_shelvesgoods         无
10010   send_master_diamondsrob_allintgeral          无
10011   sc_vnspf_exchange                            无
10012   s2c_vnspf_login_data                         无
10013   s2c_vnspf_recharge_data                      无
10014   s2c_voucher_dayfree_data                     无
10015   sc_voucher_daygift                           无
11000   integral_match_m                             无
11001   match_pvp_my                                 无
11003   integral_match_end                           无
11004   opposite_operation_m                         无
11005   other_play_end_m                             无
11006   end_immediate_m                              无
20001   guild_cross                                  无
20002   guild_cross_war_report_all                   无
20003   guild_cross_war_report_add                   无
```

</details>
