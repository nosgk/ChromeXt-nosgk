# ChromeXt（nosgk 分支）

[![Build](https://github.com/nosgk/ChromeXt-nosgk/actions/workflows/build.yml/badge.svg?branch=master)](https://github.com/nosgk/ChromeXt-nosgk/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/nosgk/ChromeXt-nosgk?color=orange&label=%E4%B8%8B%E8%BD%BD)](https://github.com/nosgk/ChromeXt-nosgk/releases/latest)
[![Downloads](https://img.shields.io/github/downloads/nosgk/ChromeXt-nosgk/total?label=%E4%B8%8B%E8%BD%BD%E9%87%8F&color=yellow)](https://github.com/nosgk/ChromeXt-nosgk/releases)

借助 Xposed 框架，为基于 Chromium 内核以及基于 WebView 的 Android 浏览器提供 UserScript（用户脚本）与 DevTools（开发者工具）支持。

---

> [!WARNING]
> **请先读完这段话，再决定是否使用。**
>
> 1. 本仓库是 [JingMatrix/ChromeXt](https://github.com/JingMatrix/ChromeXt) 的**第三方分支**，由 AI 编程助手在维护者 [nosgk](https://github.com/nosgk) 的指导与审核下大量修改、重写和补充。它不是上游官方版本，也**不代表上游的立场、质量或路线**。
> 2. **遇到任何问题，请在本仓库提 issue，不要去找上游仓库。** 上游维护者没有参与本分支的开发，也没有义务为本分支的代码、构建产物或行为负责。把本分支的 bug 报到上游，只会给无关的人添麻烦。
> 3. **本项目的代码与文档主要由 AI 编写。** AI 生成的内容可能存在逻辑疏漏、边界条件遗漏、与上游行为不一致、甚至完全错误的地方。维护者会尽量审核与测试，但**不提供任何正确性保证**。
> 4. **使用风险自负。** 本模块需要注入浏览器进程、修改运行时行为、读写脚本数据，并且依赖 Xposed / LSPatch 这类本身就有系统级风险的框架。刷机变砖、应用崩溃、数据丢失、账号异常、隐私泄露等一切后果，由使用者自行承担。请务必先做好备份，并清楚自己在做什么。
> 5. 如果你需要稳定、经过长期验证的实现，请使用[上游项目](https://github.com/JingMatrix/ChromeXt)。

---

## 这个仓库是什么

ChromeXt 的核心能力很直接：让 Android 上的 Chromium 系浏览器和 WebView 系浏览器能够像桌面端的 Tampermonkey 一样加载用户脚本，同时提供一套可以在手机上使用的开发者工具入口。

本分支在保留上游主体逻辑的前提下，主要做了三件事：

- **工程加固**：补齐文档、测试、发布校验与签名流程，把构建收敛到一条可复现的路径上；
- **原生脚本管理**：用 Jetpack Compose 写了一个原生管理面板，替代原先依赖网页前端的脚本管理方式；
- **数据集中化**：把脚本数据从「每个浏览器各自一份」改成「模块内一份中央数据库 + 浏览器侧回退」，避免同一份脚本在多个浏览器里反复维护。

需要再次强调的是：这些改动由 AI 主导完成，属于「能用、但需要你自己判断」的状态，而不是一个成熟商业软件的交付标准。

## 工作原理

ChromeXt 会 hook `onUpdateUrl`（见 [UserScript.kt](app/src/main/kotlin/org/matrix/chromext/hook/UserScript.kt)），在页面地址发生变化时，把当前 URL 与已安装的用户脚本逐一比对，命中匹配规则后，再通过 `javascript:` scheme 或（可用时）DevTools 协议把脚本注入页面。

支持的浏览器包括 Chrome、Brave、Vivaldi、Whale、Bromite、Cromite、Thorium 等 Chromium 系浏览器，以及大多数 WebView 系浏览器。如果你的浏览器不在支持列表里，可以在本仓库提 issue 说明情况——但请记得，这里是分支仓库，不是上游。

> **WebView 用户请注意**：只对你实际要用的那个浏览器应用启用本模块的作用域，不要对所有 WebView 宿主、更不要对 Android 系统本身启用。WebView 的宿主范围很广，错误的作用域会让模块进入大量你并不希望它介入的进程。

## 环境要求

- Android 5.0 及以上（API 21+），**arm64-v8a** 设备。
- 支持 **libxposed Modern API 100 及以上**的框架（本模块已迁移到 API 102）：
  - 有 root：LSPosed 1.10+ 或 [Vector](https://github.com/JingMatrix/Vector)；
  - 无 root：带 libxposed 支持的新版 [LSPatch](https://github.com/JingMatrix/LSPatch) 构建。

> 注意：3.10.0 起模块只声明 libxposed Modern API 入口（`META-INF/xposed/`），不再提供旧的 `assets/xposed_init`。只支持旧 Xposed API 82 的框架或旧版 LSPatch **不会加载**本模块，升级前请先确认框架版本。

模块代码本身与 CPU 架构无关，但发布用的 APK 只打包了 Compose 图形路径原生库的 arm64-v8a 切片，因此产物统一命名为 `ChromeXt-arm64-v8a.apk`。这也是为什么在 32 位或 x86 设备上不能直接使用官方发布包。

## 安装

常规流程：

1. 从 [Releases](https://github.com/nosgk/ChromeXt-nosgk/releases/latest) 下载最新的 `ChromeXt-arm64-v8a.apk`；
2. 安装后，在你的 Xposed 管理器里启用本模块；
3. 把模块作用域设置为你实际使用的浏览器；
4. 重启浏览器进程（强制停止或重启设备均可）。

无 root 的 LSPatch 流程：

1. 下载打好补丁的 `lspatch.jar` 与 `ChromeXt-signed.apk`；
2. 对目标浏览器执行：
   ```bash
   java -jar lspatch.jar <browser.apk> -d -v -m ChromeXt-signed.apk --force --injectdex
   ```
3. 安装打过补丁的浏览器（签名不同，可能需要先卸载原版，注意提前备份数据）。

## 使用

### 安装用户脚本

打开以 `.user.js` 结尾的链接即可触发安装流程；也可以把 JavaScript 文件分享给 ChromeXt，由它接管安装。

### 支持的用户脚本 API

ChromeXt 实现了 [Tampermonkey 文档](https://www.tampermonkey.net/documentation.php)中最常用的一批接口：

| 类别 | 支持项 |
| --- | --- |
| 元数据 | `@name`（不允许出现冒号）、`@namespace`、`@description`、`@version`、`@author`、`@icon` |
| 匹配 | `@match`（Chrome 匹配模式语法，支持正则）、`@include`、`@exclude` |
| 时机 | `@run-at`：`document-start`、`document-end`、`document-idle`（默认） |
| 页面操作 | `GM_addStyle`、`GM_addElement`、`GM_openInTab`、`GM_registerMenuCommand`、`GM_unregisterMenuCommand`、`GM_download`、`unsafeWindow` |
| 网络 | `GM_xmlhttpRequest` |
| 存储与系统 | `GM_setValue`、`GM_getValue`、`GM_listValues`、`GM_addValueChangeListener`、`GM_removeValueChangeListener`、`GM_setClipboard`、`GM_cookie`、`GM_notification`、`window.close` |
| 依赖 | `@require`、`@resource`（不支持 Subresource Integrity） |
| 注入控制 | `@inject-into`、`@sandbox`：被引入的脚本默认可以定义全局变量，设置 `@inject-into content` 或 `@sandbox DOM` 可关闭该行为 |
| 框架 | `@noframes`：向 iframe 注入需要主页面脚本声明 `@grant frames` |

具体实现位于 [Local.kt](app/src/main/kotlin/org/matrix/chromext/script/Local.kt) 与 [GM.js](app/src/main/assets/GM.js)。**注意：这里的若干行为是有意与 Tampermonkey 不同的**，遇到疑问时请直接读这两个文件，而不是照搬 Tampermonkey 的文档。

### `GM.ChromeXt`

`GM.ChromeXt` 是一个强大且危险的接口，必须在脚本中显式声明 `@grant GM.ChromeXt` 才会解锁。它向页面脚本暴露 `ChromeXt.dispatch(action, payload)`，具体动作由 [Listener.kt](app/src/main/kotlin/org/matrix/chromext/Listener.kt) 处理。

之所以默认锁死，是因为一旦放开，恶意用户脚本就可以直接接管浏览器集成层，做出远超「改改页面样式」的事情。请只在你完全信任某个脚本时授予这个权限。

### 原生脚本管理面板

ChromeXt 自带一个用 Jetpack Compose 编写的原生管理面板。可以从以下入口打开：

- 桌面图标直接启动；
- LSPosed 模块卡片上的设置按钮；
- 浏览器页面菜单里的 `Script manager`；
- Chrome 的开发者选项。

面板目前支持：

- 已安装脚本列表，带搜索、筛选、排序与一键启用/停用，支持下拉刷新；
- 以应用为入口管理脚本：应用页展示图标、包名、脚本总数与启用数，点进去是该应用的脚本列表；「全局脚本」入口展示全部已安装脚本；
- 「应用」页展示真正被注入过的应用（含最近激活时间与脚本数量），以及已安装但尚未激活的支持浏览器；
- 手动检查脚本更新：拉取 `@updateURL`/`@downloadURL`，比较版本后一键更新，并保留本地设置与 GM 存储；
- 代码编辑器：编辑页只保留元数据与代码摘要，完整代码在独立的全屏编辑器中修改（等宽字体、查找替换、行列定位）；
- 每个脚本的独立设置（`@run-at`、自定义 `@match` / `@include` / `@exclude`、更新地址、适用应用）；
- `GM_storage` 查看与编辑；
- 安装确认页，展示目标站点、申请的权限与代码预览；
- ZIP 备份与恢复（支持 ChromeXt 自有格式，也支持纯 `.user.js` 压缩包）；
- 主题切换（跟随系统 / 浅色 / 深色）与「关于」页（本分支与上游链接、当前版本号）。

界面文案已全部资源化，并提供简体中文翻译；中文系统的设备会自动使用中文。

脚本保存在模块内的中央数据库里，并从各浏览器原有的旧数据库做一次性迁移。如果模块进程不可用（例如被某些 OEM 的后台策略杀掉），浏览器会自动回退到本地数据库，保证脚本仍然可用。

### 先改后装

如果你在安装提示里选择了取消，可以直接在页面上编辑脚本文本；改完之后，用页面菜单里的 `Install UserScript` 再安装修改后的版本。

### DevTools 与 Eruda

页面右上角的三点菜单里提供三项入口：

1. `Developer tools`：ChromeXt 的开发者工具前端；
2. `Script manager`：打开原生管理面板；
3. `Eruda console`：面向普通页面的 Eruda 控制台。

对于 WebView 系浏览器，以及自带页面菜单的 Chromium 分支（例如 Herond、Whale），这些入口会出现在上下文菜单里。

## 构建

构建**只在 GitHub Actions 上运行**，工作流是**手动触发**（`workflow_dispatch`），任何 push 都不会自动触发。详见 [.github/workflows/build.yml](.github/workflows/build.yml)。

该工作流会执行 `assembleRelease`，校验 libxposed 入口（`META-INF/xposed/java_init.list`、`module.prop` 的 `targetApiVersion=102`、`scope.list`）与 arm64-v8a 切片，用存放在 `release` 环境中的密钥库签名 APK，最后上传 `ChromeXt-arm64-v8a.apk` 并附带 SHA256 校验值。

工作流**刻意没有单独的 CI/测试阶段**。这不是偷懒，而是为了把构建时间压到最低、并避免「CI 绿了但产物不可用」的假安全感；所有检查请按下面的命令在本地跑。

本地开发需要 JDK 21 与 Android SDK 35：

```bash
./gradlew format          # 应用 ktfmt 格式化
./gradlew ktfmtCheck      # 校验格式
./gradlew lintDebug       # Android lint
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

### 发布签名

`release` 环境里保存四个 secret：`SIGNING_KEY`（base64 编码的 PKCS#12 密钥库）、`ALIAS`、`KEY_STORE_PASSWORD`、`KEY_PASSWORD`。密钥库本身不在仓库里。

轮换签名密钥需要同步更新这些 secret；而一旦签名变化，用户必须卸载重装才能升级，历史数据也可能因此丢失，操作前请想清楚。

## 测试

```bash
./gradlew testDebugUnitTest
node --test tools/js-tests/*.test.js
```

测试布局、设备矩阵以及如何新增 fixture，见 [TESTING.md](TESTING.md)。

## 关键入口

想改代码的话，从这几个地方开始最省时间：

| 关注点 | 文件 |
| --- | --- |
| Tampermonkey API 实现 | [Local.kt](app/src/main/kotlin/org/matrix/chromext/script/Local.kt)、[GM.js](app/src/main/assets/GM.js) |
| Eruda 配置 | [eruda.js](app/src/main/assets/eruda.js) |
| WebView 浏览器支持 | [WebView.kt](app/src/main/kotlin/org/matrix/chromext/hook/WebView.kt) |
| Hook 兼容层 | [proxy/](app/src/main/kotlin/org/matrix/chromext/proxy)、[hook/](app/src/main/kotlin/org/matrix/chromext/hook) |
| 原生管理面板 | [ui/](app/src/main/kotlin/org/matrix/chromext/ui) |
| 脚本更新检查 | [UpdateChecker.kt](app/src/main/kotlin/org/matrix/chromext/ui/UpdateChecker.kt) |
| 中央数据库与 Provider | [ScriptStore.kt](app/src/main/kotlin/org/matrix/chromext/script/ScriptStore.kt)、[ScriptContentProvider.kt](app/src/main/kotlin/org/matrix/chromext/provider/ScriptContentProvider.kt) |
| libxposed 入口与元数据 | [MainHook.kt](app/src/main/kotlin/org/matrix/chromext/MainHook.kt)、`app/src/main/resources/META-INF/xposed/` |

## 已知差异与限制

- **与 Tampermonkey 的行为差异**：API 覆盖面接近，但并非完全等价，部分语义是有意简化的，遇到脚本不工作时先对照 `Local.kt` / `GM.js`。
- **只支持 libxposed Modern API**：3.10.0 起不再提供旧 Xposed API 82 入口，只支持 API 100+ 的框架；旧框架与旧版 LSPatch 不会加载模块。
- **只发 arm64-v8a**：发布产物仅包含 arm64-v8a 切片，其他架构需要自行构建。
- **无 CI 测试阶段**：如前述，测试靠本地执行，仓库不保证每次提交都经过完整验证。
- **依赖 Xposed 生态**：模块行为会随 LSPosed / LSPatch / Vector 的版本变化而波动，某些 ROM 的后台限制也会影响模块进程的存活。
- **已 Hook 应用列表依赖运行时上报**：只有真正被注入过的应用才会显示为「已激活」；有 root 时应用页会尝试读取 LSPosed 的作用域配置，未激活但已勾选的应用会标为「已勾选，未激活」。
- **应用页需要读取已安装应用列表**：为支持把脚本限定到任意宿主，模块声明了 `QUERY_ALL_PACKAGES`；应用页只会展示应用名称、包名、版本与图标。
- **AI 生成的代码**：这是本分支最需要你警惕的一条，见开头的警告。

## 3.12.0 更新内容

- 导航改为「应用为主、脚本为二级」：底部导航只保留应用/设置/关于，点选应用（或「全局脚本」）进入该应用的脚本列表，列表顶部有返回按钮；宽屏下仍是左侧导航栏。
- 在某个应用里安装脚本时，默认只在该应用生效，不再默认全局启用；重新安装会保留已有的应用范围。
- 脚本编辑器：元数据栏保持原样，代码栏只显示行数/字符数和「打开代码编辑器」按钮；点进去是全屏编辑器，支持等宽字体、查找替换、行列定位和放弃修改确认，避免长脚本把编辑页拖卡。
- 应用选择器支持搜索，并且只列出 LSPosed 作用域内的应用；作用域读不到时才回退到已上报的应用。
- 「全局脚本」现在展示全部已安装脚本。
- `@run-at` 的中文改为更准确的「文档开始前 / DOM 解析完成 / 页面加载完成」。
- LSPosed 作用域读取重写：用 `find` 在 `/data/adb` 下发现 `modules_config.db`，连 `-wal`/`-shm` 一起复制，兼容两种查询写法，并把失败原因（无 root / 未找到配置 / 解析失败 / 作用域为空）显示在应用页。

## 3.11.0 更新内容

- 设置页重做为响应式工作台：宽屏使用左侧 `NavigationRail`，卡片改为双列瀑布流，所有卡片等宽；内容包含实时遥测（IPC 延迟、Schema 版本、数据库路径与体积、脚本/存储统计）、已安装宿主（图标、版本、root 一键重启）、注入引擎开关和数据维护区。
- 应用页重做为按应用管理：显示应用图标、包名、脚本总数/启用数，顶部有「全局脚本」入口，支持搜索与「全部应用」筛选；在应用内新建脚本会自动限定到该应用。
- 脚本页与应用页支持下拉刷新。
- 「已激活」判定修复：每个被注入的进程都会通过系统上下文上报，Provider 也会记录任何合法调用方，不会再出现已注入却显示「未激活」。
- 新增中央 `config` 表（Schema v12），引擎开关由管理面板写入、浏览器进程同步读取。

## 3.10.0 更新内容

上一版 README 里列为「路线图」的能力，现在都已经落地：

- 界面全面支持中文，文案全部资源化，中文系统自动生效；
- 原生脚本管理面板支持按「已 Hook 的应用」分别管理脚本，脚本可以指定只在某些应用内运行；
- 支持手动检查脚本更新，比较 `@version` 后可一键更新，并保留本地设置与 GM 存储；
- 新增「应用」页，展示已激活与已安装未激活的应用；
- 暗色模式支持跟随系统 / 浅色 / 深色三种模式；
- 新增「关于」页，展示本分支与上游仓库链接以及当前版本号；
- Xposed 接入迁移到 libxposed Modern API 102。

## 反馈与贡献

请阅读 [CONTRIBUTING.md](CONTRIBUTING.md)。简单说：行为变更要写清楚，纯逻辑改动要配测试，提交前跑一遍 `./gradlew format` 和本地检查。手动构建工作流必须能产出已签名的 `ChromeXt-arm64-v8a.apk`。

再次提醒：**所有问题请提到本仓库**。上游项目与本分支无关，请不要向上游提交本分支的 issue、PR 或安全报告。

## 安全

如果发现安全问题，请按照 [SECURITY.md](SECURITY.md) 的说明私下报告，不要开公开 issue。

## 许可证

ChromeXt 依据 [LICENSE](LICENSE)（GNU GPL v3）分发。本分支同样遵循该许可证；你在分发修改版本时，也需要遵守 GPL 的相应义务。
