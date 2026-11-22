# pokemon

守护小精灵 / 天天驯兽师本地 AI 版。交付目标为可独立运行的 Android APK，默认全部在 App 内离线实现，不使用服务端代码或 HTTP/TCP 监听。具体范围与实现约定见 [项目全局规范](AGENTS.md)。

## 开发与协作

- [全局 Git 协作规范](CONTRIBUTING.md)：首次上传、分支权限、开 PR、提 Issue、关联问题与发布流程。
- [Android 内置运行环境与构建](app端代码/local-patch/README.md)
- [历史 PC 服务端参考](服务端代码/README.md)（仅明确要求时使用，不参与 App 构建）
- [反馈 BUG 或提交需求](https://github.com/tryking123/pokemon/issues/new/choose)

`main` 为稳定分支，仅所有者可更新；`devlop` 为集成分支，需通过 PR 审核。仓库内提供权限规则配置，须在 GitHub 导入启用后生效，见协作规范。

根目录 `.gitignore` 排除原始 APK、解包产物、构建输出、密钥和本地存档。克隆仓库后，原 APK 与签名证书需自行合法准备；底包路径和哈希见 AGENTS.md。

## 许可

本项目原创且有权授权的内容采用 [PolyForm Noncommercial License 1.0.0](LICENSE)，允许非商业使用、修改与分发，**不授权商业使用**。这是带非商业限制的源码可用许可。第三方 APK、SDK、反编译代码及游戏资源不在本项目统一授权范围内，详见 [许可及第三方权利说明](LICENSE.NOTICE.md)。
