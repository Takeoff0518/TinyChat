# 09 · 合入 1.0.8 → 1.1.0（施工台账）

> **这份文件是断点。** 上下文被压缩、换人接手、隔几天回来，都先读这里，再看 `docs/03-handoff.md` 的决策表。
> 开工：2026-11-24。分支：`integration/1.1.0`（从 `master` 的 `0f7de79` 拉出）。

**当前进度：阶段 8 完成**（相册：部分授权单独识别并在列表尾部给"管理"入口；读取失败与"没有照片"分开并可点重试；7 套文案补齐。构建、单测、lint 全绿）。
**下一步：阶段 9 · 错误文案不再靠中文串拼。**

## 一、目标

把贡献者的两条 PR 线（`Takeoff0518/TinyChat#2`，head `pr-2` = `6f9dfb0`，163 文件 / +14859−5655）整包接进来，
删掉其中两个假功能，装回我们自己的内存实时快照，版本定为 1.1.0，`master` 全程不动直到最后一步。

PR 的定位：功能是我们需要的，实现是 Agent 赶工出来的，质量分三层——能用、能用但脏、不能用（假功能）。

## 二、已定的七个决定

| # | 决定 | 理由 |
|---|---|---|
| 1 | 出字节奏用我们的内存实时快照，删掉 PR 的逐字揭示层 | PR 那套是 `FrameMerger(minWaitMs = 30)` 把节奏压到 33 次/秒 + 逐字连锁，是给 200ms 落库批次的补丁；我们已经改成每帧一版源头 |
| 2 | `versionCode 2` / `versionName 1.1.0` | PR 填的 9/1.0.8 是贡献者自己 fork 的发版节奏，会把 2–8 白扔 |
| 3 | 走 `integration/1.1.0`，差不多再合 `master` | `master` 保持可发版，出错整条分支可扔 |
| 4 | Harness 提示词整块删，`DshProviderPresets` 保留 | 那份提示词是 DeepSeek Harness 标准模式的系统提示词原文（6744 字符），讲的全是 TinyChat 没有的工具；预设表省手填配置，是另一回事 |
| 5 | 后台生成（前台服务 + 通知）整块删 | 用户不需要；且没有 `POST_NOTIFICATIONS`，Android 13+ 上那条通知根本不可见，等于半截功能 |
| 6 | "最近照片"网格保留，补两处状态 | 实现比 PR 其余部分扎实（全表扫 + 小顶堆排序）；缺的是部分授权识别和失败态 |
| 7 | PR 全权在手，不跟贡献者来回 | 用户确认 |

## 三、阶段表

状态：`[ ]` 未开始 `[~]` 进行中 `[x]` 完成

| # | 阶段 | 状态 | 提交 |
|---|---|---|---|
| 0 | 安全网：patch 备份 + 拉分支 | [x] | — |
| 1 | 合并基线（冲突取 PR 版） | [~] | `chore: 合入 1.0.8（冲突取 PR 版，实时快照暂缺）` |
| 2 | lint 归正（撤 disable + 字符串边界 + CI 护栏） | [ ] | `fix: 撤回 lint 屏蔽` |
| 3 | 删假功能 A：Harness 提示词与专家模式 | [ ] | `revert: 删掉 Harness 提示词与专家模式` |
| 4 | 删假功能 B：后台生成前台服务 | [ ] | `revert: 删掉后台生成的前台服务` |
| 5 | 版本与元数据（1.1.0、NOTICE 修正） | [ ] | `chore: 版本定为 1.1.0` |
| 6 | 实时快照重装（重写，非打补丁） | [ ] | `feat: 正文回到内存实时快照` |
| 7 | 出字节奏收口（删揭示层、gfxinfo 复测） | [ ] | `perf: 出字跟帧走` |
| 8 | 相册两处补丁（部分授权 / 失败态） | [ ] | `fix: 相册部分授权与读取失败` |
| 9 | 错误文案去中文串 | [ ] | `fix: 错误文案不再靠中文串拼` |
| 10 | 文档 + draft PR + 合 master | [ ] | `docs: 记下 1.1.0 的取舍` |

## 四、每阶段的坑与做法

### 阶段 0（已完成）

- patch 备份：`.local/patches/0001-fix-500ms.patch`（gitignored）
- 分支：`integration/1.1.0` @ `0f7de79`
- PR 本地 ref：`pr-2` @ `6f9dfb0`（`git fetch origin pull/2/head:pr-2`）
- merge base = `2509a2a`（= `origin/master`），PR 里包含它，所以冲突只来自我们那一个 commit

### 阶段 1：合并基线

合并探测（已跑过，别再跑）：**6 个文件文本冲突 + 1 个自动合并但会断编译的文件**。

| 文件 | 冲突块 | 处置 |
|---|---|---|
| `core/data/.../chat/ChatStreamRunner.kt` | 3 | **取 PR 版**（它有 `NonCancellable` + `idAt` 恢复 + `markInterrupted`） |
| `core/data/.../chat/ChatRepository.kt` | 12 | 取 PR 版 |
| `feature/chat/.../ChatViewModel.kt` | 13 | 取 PR 版 |
| `feature/chat/.../ChatScreen.kt` | 1 | 取 PR 版 |
| `app/.../TinyChatApp.kt` | 1 | 取 PR 版 |
| `docs/03-handoff.md` | 1 | **取我们的** |
| `core/data/.../ChatStreamRunnerTest.kt` | 无（自动合并） | **也要取 PR 版**：我们那三条测的是被删掉的接口 |
| `feature/chat/src/test/.../LiveHandoffTest.kt` | 无 | **删除**：测的 `liveCaughtUp` 已不存在 |
| `docs/screenshots/p036-black-screen/*` | 无 | 取我们的（PR 把它们当二进制重写了，是历史证据） |
| `ListScroll.kt` / `ListScrollTest.kt` | 无 | 留着，阶段 6 恢复使用 |

验收：`:app:assembleDebug` + 全量单测过。

### 阶段 2：lint 归正

> ⚠️ **教训（2026-11-24）**：这台机器上的 PowerShell 读写 UTF-8 源码会把中文注释变成乱码（`Get-Content -Raw` 按 GBK 读、`WriteAllText` 按 UTF-8 写）。
> **改源码一律用 read / edit / write 工具，不要用 PowerShell 做文本替换。** 保险丝：`git checkout HEAD -- <文件>`。

- 删三处 `lint { disable += "LocalContextGetResourceValueCall" }`（`app` / `feature/chat` / `feature/settings` 的 `build.gradle.kts`）
- **规则不是机械换 `stringResource`**：非 Composable 函数只返回 `@StringRes Int` + 参数，解析发生在 Composable 边缘
- 已知命中点：`ProviderScreens.kt`（334–340、353、364、566、572、1177、1186）、`PromptScreens.kt`（463–464）、`SettingsScreens.kt`（437–533）、`ChatLabels.kt`、`ChatScreen.kt`（Toast 几处）
- CI 护栏：`ci.yml` 加一步，构建脚本里出现 `disable +=` 就失败
- 验收：五个模块 `lintDebug` 零错误

### 阶段 3：删 Harness 提示词与专家模式

删除：`core/data/.../prompt/HarnessStandardPrompt.kt`、`core/data/src/main/resources/META-INF/LICENSE-DeepSeek-Harness.txt`、`NOTICE` 里那 5 行。
拆链：`UserSettings.expertMode` + `EXPERT_MODE` key、`ChatRepository` 里约 10 处 `expertMode` 参数与 `promptFor` 分支（`ChatRepository.kt:1094`）、`ChatViewModel` 里约 10 处透传、`ChatScreen` 的开关、7 个 locale 的文案、`ChatRepositoryPromptTest` 与 `UserSettingsTest` 各一条。
验收：`git grep -i harness` 只剩 `DshProviderPresets.kt` 与文档叙述。

### 阶段 4：删后台生成

删除：`app/.../ChatGenerationService.kt`（含 `ChatGenerationLifetime`）、`drawable/ic_generation_notification.xml`、manifest 里 `<service>` + `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC`、7 个 locale 的 `app_chat_generation`。
拆链（全树只有这几处）：`TinyChatApplication.kt:20` 的 `generation`、`TinyChatApp.kt:147` 的 `keepGenerationAlive`、`ChatViewModel.kt:390 / 408 / 1785 / 1802`、`MainActivity.kt:67` 的 `languageTag`。
验收：冷启动无常驻通知；回桌面行为回到 1.0.0（生成可能被系统杀掉，已生成内容靠 `NonCancellable` 落库）。

### 阶段 5：版本与元数据

- `app/build.gradle.kts`：`versionCode = 2`、`versionName = "1.1.0"`（全树只有这一处写版本号）
- `NOTICE`：`tc_logo.webp` → `tc_logo.png`（两个分支的 `drawable-nodpi` 下都只有 png；PR 删重复文件时漏改）
- 核对 PR 动过的 `docs/06`、`docs/07`、`docs/08`

### 阶段 6：实时快照重装

- 三个 assembler 直接从 `0f7de79` 取回：`core/net/.../StreamAssembler.kt`、`MessageAssembler.kt`、`OpenAiStreamAssembler.kt`（PR 没碰过这三个文件）
- `ChatStreamRunner`：在 PR 版上叠加 `LiveStream` / `LiveSink` / `publishLive` / `liveIntervalMillis = 16` / `flushIntervalMillis = 500`
  **注意**：PR 已经加了 `NonCancellable`，我们那版也加过，不要叠两遍；取 PR 的更完整版（含 `recovering` 与 `markInterrupted`）
- `ChatRepository`：把 `onLive` 透传到 send / editMessage / regenerate / continueGeneration / retryTurn；`continueGeneration` 传 `baseText`
- `ChatViewModel`：`live` StateFlow、`liveMessageId`、`liveSink`、`clearLive` / `finishLive`、`liveCaughtUp`
- `ChatScreen`：`live` 参数、`shownText`、`withLiveThinking`、跟随修复、恢复 `shouldShowToBottom`
- 恢复 `LiveHandoffTest.kt` 和 `ChatStreamRunnerTest` 里那三条
- 验收：单测绿；真机正文逐帧、无字数回退

### 阶段 7：出字节奏收口

- `core/markdown/.../TcMarkdown.kt`：删 `BreakIterator` 揭示循环、`revealing`、`frames`/`step` 那套；**保留** `rebuildPrefix` 前缀续接（那是真修复）
- `core/markdown/.../FrameMerger.kt`：`minWaitMs` 回默认 0（`TcMarkdown` 里那个 `minWaitMs = 30` 必须去掉）
- 验收（gfxinfo）：文字回答 ≥40 帧/8s、卡顿 <2%

### 阶段 8：相册两处补丁

- 部分授权：`READ_MEDIA_VISUAL_USER_SELECTED` 有、`READ_MEDIA_IMAGES` 没有时，文案换成"只显示你选中的照片 / 管理"，点了重新申请
- 失败态：`queryRecentPhotos` 的 `runCatching` 不要把失败当空，给"读取相册失败，点此重试"
- 各加 7 个 locale 的文案

### 阶段 9：错误文案

`feature/chat/.../ChatLabels.kt:99`（`removeSuffix(" 服务繁忙，稍后再试")`）和 `:117`（`removePrefix("超过 ")`）：改成结构化错误 + 参数，不在 UI 层做中文字符串手术。

### 阶段 10：文档与合并

- `docs/03-handoff.md` 追加决策行（导入 1.0.8 / 删两功能 / 版本 1.1.0）
- `docs/02-dev-plan-and-ux-spec.md` §12 补规格行（多语言、附件文本提取、上下文压缩、相册网格；后台生成标注为未采纳）
- `docs/polish-log.md` 补台账行
- 推 `integration/1.1.0`，开 draft PR 指向 `master`（本仓库分支，CI 不需批准），全绿后 merge commit

## 五、验收基线（数字）

| 项目 | 数值 | 来源 |
|---|---|---|
| 文字回答帧率 | 8 秒 323 帧（约 40fps），卡顿 0.93%，50 分位 9ms | 2026-11-24 真机 release 包 |
| 代码块回答 | 约 43fps，卡顿 16.6% | 同上（PR 重写过 `TcCodeBlock`，要重测） |
| 冷启动 | 349 / 334ms；热启动 88ms | M9-5 记录 |
| 单测 | 全绿 | — |
| lint | 五模块零错误 | — |

设备：华为 FIN-AL60，API 31，1084×2412，序列号 `2TX9K24103023789`。
安装方式：`.local/keystore.properties` 在位，`assembleRelease` 出包后 `adb install -r`（签名与 1.0.0 一致，可覆盖）。

## 六、进度日志

- **2026-11-24 20:2x** 阶段 0 完成：patch 备份就位，`integration/1.1.0` 拉出；本台账落盘。
