# 全局 Git 协作与发布规范

适用仓库：[tryking123/pokemon](https://github.com/tryking123/pokemon)。分支名称按项目要求使用 **`devlop`**（不是 `develop`）。业务开发仍遵循 [AGENTS.md](AGENTS.md)。

## 分支与权限

| 分支 | 用途 | 提交与审核 | 合并权限 |
| --- | --- | --- | --- |
| `main` | 稳定发布，保留为默认分支 | 通常由 `devlop` 提发布 PR；所有者可直接更新 | 仅仓库所有者 `tryking123` |
| `devlop` | 日常集成 | 必须 PR，至少 1 个 Code Owner 批准；新推送使旧批准失效，最后推送需由其他人批准，评论须解决 | 有写权限的成员可在审核通过后合并 |
| `feature/<issue>-<name>` | 功能开发 | 从 `devlop` 创建，PR → `devlop` | 分支开发者 |
| `fix/<issue>-<name>` | BUG 修复 | 从 `devlop` 创建，PR → `devlop` | 分支开发者 |
| `docs/<name>`、`chore/<name>` | 文档与工程维护 | 从 `devlop` 创建，PR → `devlop` | 分支开发者 |
| `hotfix/<issue>-<name>` | 已发布版本紧急修复 | 从 `main` 创建；PR → `main` 由所有者合并，再回流 `devlop` 审核 | 所有者负责发布 |

`main`、`devlop` 均禁止强推和删除。其他成员在仓库中最多授予 Write；外部贡献者通过 Fork 提交 PR，不授予 Admin。Git 提交邮箱只是作者信息，不能决定账号权限。

当前使用个人仓库，`main-owner-only` 规则通过“仓库 Admin 角色可绕过更新限制”实现所有者更新。**前提是只有你拥有 Admin 权限**；如果迁入组织或增加管理员，应改为专属所有者团队，而不能继续把全部管理员当成你本人。独立的安全规则没有绕过名单，即便所有者也不能强推或删除受保护分支。

`.github/CODEOWNERS` 指定 `@tryking123` 审核全部文件。Code Owners 文件本身不会授予权限，也不会自动启用强制审核，必须配合远端规则。

### 所有者自己的 PR

GitHub 不允许作者批准自己的 PR。`devlop-review` **没有所有者免审例外，也没有自动批准工作流**。若你也要向 `devlop` 提 PR，应在开启规则前邀请至少一位可信成员，赋予 Write，并把同一行改为 `* @tryking123 @可信审核人`。必须由非作者、非最后推送者完成有效审核。只有你一个人时，这种严格审核模式会阻止你的 PR 合并，这是规则的实际限制；不要把未审核描述为已审核。

## 首次上传与本地准备

根目录 `.gitignore` 对整个仓库生效，不修改本机 `core.excludesFile`。提交源码、构建/补丁脚本、JSON/CSV/SQL 运行配置、统筹文档、基础数据与 BUG 台账。原始 APK、解包分析目录、生成的 DEX/原生库/元数据、IDE 配置、本地存档、日志及签名密钥不提交。

原 APK 必须自行合法取得，放入 `app包/origin/origin.apk`，并按 AGENTS.md 核验 SHA-256；不能仅靠克隆仓库得到完整底包。分析目录从该底包重新解码。需要在多设备上覆盖安装时，签名证书通过私有安全渠道备份和共享，不能提交到 Git。新 APK 也不默认上传公开 Release，发布前须单独确认第三方资源再分发授权。

`.gitignore` 不影响已入索引的文件。本次准备会仅移除命中规则的索引项，保留磁盘文件；以后新增忽略项时，可以用以下 PowerShell 流程重新检查并清理：

```powershell
git -c core.quotepath=false ls-files -ci --exclude-standard
# 确认列表仅为应排除的内容后，按具体路径执行；不要删除本地文件：
git rm -r --cached --ignore-unmatch -- '具体路径'
```

已提交到历史中的文件不会被忽略规则抹去。若泄露过密钥，应先撤销或轮换；清理历史需另外安排，不能靠 `.gitignore` 补救。

提交身份已可在当前仓库检查；需要设置时仅设置本仓库：

```powershell
git config --local user.name "tryking123"
git config --local user.email "1437870930@qq.com"
git remote -v
```

GitHub 的 HTTPS Git 操作使用 Git Credential Manager 浏览器登录或 PAT，也可使用 SSH；不能使用账号密码作为 Git 推送密码。凭据交给系统凭据管理器，不写在远程 URL、脚本、Markdown 或配置文件中。

当前已有本地 `main`、`develop`，远端首次核对仅有 `main`。本次准备将本地 `develop` 改名为 `devlop`，保留工作区和暂存内容；不自动提交、推送代码或发布 APK。首次发布按顺序完成：

1. 检查 `git status --short`、暂存差异和大文件，核对第三方来源及许可。
2. 在 `devlop` 提交准备好的源代码、规范、模板及许可文件。
3. `git push -u origin devlop`，完成首次集成分支初始化。此时还未开启强制审核；以后不再直接推送该分支。
4. 创建 `devlop → main` 的首次发布 PR，由你审核并合并；确保两个分支均包含 CODEOWNERS 和模板。
5. 在远端启用下节三份规则。保留默认分支 `main`，不要把历史 `develop` 作为第二个长期集成入口。

如将来在其他克隆中仍使用 `develop`，先确认是否有未推送提交，再执行 `git branch -m develop devlop` 并设置 `origin/devlop` 为上游；不要覆盖分叉提交。

## 在 GitHub 启用权限规则

**仓库内 JSON 只是可导入的配置，不会因提交代码而自动生效。本次仅准备配置，未修改远端规则。**

由你打开 [Settings → Rules → Rulesets](https://github.com/tryking123/pokemon/settings/rules)，使用 New ruleset → Import a ruleset，依次导入：

1. [main-owner-only.json](.github/rulesets/main-owner-only.json)：仅 Admin 可创建/更新 `main`，仅你保留 Admin。
2. [devlop-review.json](.github/rulesets/devlop-review.json)：至少一次 Code Owner 批准，无绕过；禁止直推，开启新提交重审、最后推送审批及评论解决要求。
3. [protected-branch-safety.json](.github/rulesets/protected-branch-safety.json)：两个受保护分支禁止删除和强推，无绕过。

确认状态为 Active，并核对实际匹配 `main` 和 `devlop`。配置属于可执行规则的建议版本，尚未在此远端导入验证。

使用已登录且具有仓库 Administration 写权限的 GitHub CLI 也可以导入（首次执行一次；重复执行会创建重复规则）：

```powershell
gh auth login
gh api --method POST repos/tryking123/pokemon/rulesets --input .github/rulesets/main-owner-only.json
gh api --method POST repos/tryking123/pokemon/rulesets --input .github/rulesets/devlop-review.json
gh api --method POST repos/tryking123/pokemon/rulesets --input .github/rulesets/protected-branch-safety.json
gh api repos/tryking123/pokemon/rules/branches/main
gh api repos/tryking123/pokemon/rules/branches/devlop
```

仓库设置允许 Squash merge 和 Merge commit。日常 PR 优先 Squash，发布 PR `devlop → main` 使用 Merge commit 保留两条长期分支的共同历史；发布后通过 `main → devlop` 同步 PR 回流，避免在两条分支上分别重复修复。当前未配置 CI，不把不存在的检查设为必需；启用 CI 后，先实际运行成功，再将真实检查名称加入保护规则。

公有仓库可使用 GitHub Free 的分支 Rulesets。若改为私有仓库，需要核对套餐支持。规则限制的是成员操作；仓库所有者仍可以修改设置，不能据此宣称任何人都无法修改权限。

启用后由协作者验证：无法直接更新 `main`，无法直接推送 `devlop`；`devlop` PR 无批准不可合并，新增提交后旧批准失效；你能更新 `main`；两个分支均不可强推或删除。验证使用正常协作提交，不为测试权限推送无关业务改动。

## 如何提 Issue

1. 在 [Issues](https://github.com/tryking123/pokemon/issues) 搜索，避免重复。
2. 进入 [New issue](https://github.com/tryking123/pokemon/issues/new/choose)，选择 BUG 或功能模板；模板上传到默认分支后生效。
3. BUG 写清 APK/提交版本、环境、入口、前置条件、复现步骤、预期与实际、脱敏证据；功能写清范围和验收条件。
4. 涉及腾讯文档时，写 `BUG登记 / 问题登记 / 第 N 行`，附原链接。GitHub `#123` 是 Issue 编号，不能当成腾讯原行号。同步本地 `问题/bug清单.xlsx` 的规则以 AGENTS.md 为准。
5. 维护者确认范围并分配负责人。涉及数值、玩法设计、NPC 阵容时，按 AGENTS.md 对应分工更新统筹产物。

## 如何开 PR

有 Write 权限的成员从最新 `devlop` 切工作分支：

```powershell
git switch devlop
git pull --ff-only origin devlop
git switch -c fix/123-login
# 修改后先核对文件，再按实际范围添加：
git add -- '需要提交的文件或目录'
git diff --cached --stat
git diff --cached --check
git commit -m "fix: 修复登录异常 (#123)"
git push -u origin fix/123-login
```

外部贡献者先 Fork，克隆自己的 Fork，把原仓库添加为 `upstream`，从 `upstream/devlop` 建分支并推到自己 Fork；PR 的 base repository 选择 `tryking123/pokemon`。

在 GitHub Pull requests → New pull request，确认 **base: devlop / compare: 你的工作分支**；按模板填写问题、实际改动、验证、风险和回退。尚未完成时创建 Draft，准备好后点击 Ready for review 并请求 Code Owner 审核。修改审核意见后继续推到同一工作分支，不需要重开 PR。所有检查通过、审核有效、评论解决后再合并；合并后可删除个人工作分支。

仅对文档/工程配置的改动检查配置语法、链接和忽略规则即可，不声称完成设备验证。Java 业务修改按实际范围编译并运行已有检查；APK 修改按 AGENTS.md 完成包校验，有设备时验证离线运行和重启存档。不要为遵循模板而新增测试类，也不要勾选未执行的验证。

## Issue、提交与 PR 如何关联

- 工作分支：`fix/123-login`；提交标题：`fix: 修复登录异常 (#123)`；PR 描述：`Refs #123`，便于追踪。
- 日常 PR 目标是非默认分支 `devlop`，应在右侧 **Development** 手动关联 Issue；无 Write 权限时请维护者关联。描述中的 `Refs #123` 仅是引用，不会自动关闭。
- **不要依赖 `Closes #123` 在合入 `devlop` 时自动关闭 Issue**。GitHub 关闭关键字针对默认分支生效；当前默认分支保留 `main`。
- 发布 PR `devlop → main` 列出已验收问题，逐项写 `Closes #123`、`Closes #124`；合并到默认分支后自动关闭。未验收或仅局部完成的问题继续使用 `Refs #123`。
- 跨仓库引用用 `owner/repo#123`；腾讯文档记录用完整 URL、页签与原行号。Issue 中回填实现 PR、验证版本和本地台账记录，形成闭环。

## 许可与贡献边界

原创且有权授权的内容采用 [PolyForm Noncommercial 1.0.0](LICENSE)：允许协议范围内的非商业使用、修改和分发，商业用途不获授权。分发时保留许可和 Required Notice。适用范围和第三方内容见 [LICENSE.NOTICE.md](LICENSE.NOTICE.md)。不将第三方 APK、游戏资源或反编译内容改标为本项目许可，也不沿用参考项目的 Steam 分发例外或任意重新授权条款。

## 官方依据

- [GitHub Rulesets 可用规则](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/available-rules-for-rulesets)
- [CODEOWNERS 与强制审核](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/about-code-owners)
- [PR 与 Issue 关联及自动关闭](https://docs.github.com/en/issues/tracking-your-work-with-issues/using-issues/linking-a-pull-request-to-an-issue)
- [GitHub 身份验证](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/about-authentication-to-github)
