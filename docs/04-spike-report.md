# Spike 结论报告（2026-11-17）

> 一次性验证工程，**不在主仓库内**：`E:\code\TinyChat-spike`（结论落地后整体丢弃）。
> 本文是把 03-handoff §7 Step 0 的四项风险打掉的实测记录，结论已回写到 01/02/03。

---

## 0. 结论速览

| # | 验证项 | 结果 | 影响 |
|---|---|---|---|
| 1 | 手写 SSE 解析器 | **离线 40/40 通过**；在线真打**待 API Key** | M1-2 可照此实现，边界已穷举 |
| 4 | 87MB 导出包全量解析 | **PASS，且余量极大**（1.07s / 43MB / 0 错误） | M6 导入器可全量内存解析，不需要分批 |
| 2 | 流式 Markdown 渲染 | **决定性**：只有流式状态 API 不掉高，另两种写法每帧塌陷 | M2-1/M2-2 **API 用法被钉死** |
| 3 | 公式 / 图表选型 | **定稿：WebView + 离线 KaTeX / mermaid.js** | 纯 Compose LaTeX 库有硬伤，排除 |

另外发现并解决了 **3 个工具链级问题**（见 §6），M0 建骨架时必须照做，否则第一天就卡住。

---

## 1. Spike-1：SSE 解析器

### 1.1 离线固件（不需要网络与 Key）

固件帧形状全部取自 03-handoff §6.3 的一手抓包，不自造字段。共 **40 条断言全通过**：

```
[OK] message_start 抓到 id / model
[OK] 块数量 6，类型顺序 THINKING,SERVER_TOOL_USE,WEB_SEARCH_RESULT,THINKING,TEXT,TEXT
[OK] 思考全文、正文全文跨块拼接正确（含 \n）
[OK] 引用角标数 1
[OK] 工具块 kind=deep_search、input.action=search
[OK] 搜索结果条数 2
[OK] stop_reason=end_turn、标题从 message_delta 带回
[OK] ping 帧计数、注释行不产生事件
[OK] usage 以 message_delta 为准（input 9078 / cache_read 4096 / output 1686）
[OK] cache_creation_input_tokens 恒为 0
[OK] CRLF 换行、缺结尾空行、多行 data、无 event 名 → 都能解析
[OK] READ_LINK signature 读出 16 字符；抓取失败块无 signature、有 error_code
[OK] 拒答 stop_reason=refusal + stop_details.type=refusal
[OK] error 帧被记录、其后没有 message_stop、error 帧也带 usage
[OK] 未知事件只登记进 unknownBlocks，不崩
```

**给 M1-2 的实现要点（已验证有效）**：

- 按 `\n\n` 切帧不够——必须逐行读，`data:` 用 `\n` 拼接，空行触发 emit，EOF 也要 flush；
- **不能依赖 `[DONE]`**（扩展模式没有），终止靠 `message_stop` 或 error 帧；
- 未知 `type` 只登记不抛异常（官方随时可能加事件）；
- `content_block_start` 里 `server_tool_use` 的 `input` 是**完整原子下发**（不是增量），所以工具调用不需要 `input_json_delta` 拼接；
- `web_fetch_tool_result` 的 `signature` 必须读出并回传（硬约束 3）。

### 1.2 在线部分（未执行）

需要用户提供 DeepSeek API Key。命令已就绪：

```powershell
$env:DEEPSEEK_API_KEY="sk-..."
cd E:\code\TinyChat-spike
.\.tools\gradle-9.9.1\bin\gradle.bat run --args="sse-live"
```

会打印 HTTP 状态、首帧延迟、总耗时、块构成、事件分布、用量，并自动判定
`手写 SSE 打通` / `收到 thinking_delta` / `收到 server_tool_use` / `收到 citations_delta` 四条。

> 无 Key 时**不阻塞**：解析器本身已被固件钉死，联网只是确认"真实服务端行为与文档一致"。

---

## 2. Spike-4：导出包全量解析 —— PASS

样本：`deepseek_data-2026-11-16.zip`（87MB）。JVM 堆强制压到 **-Xmx150m** 运行。

| 指标 | 实测 | 验收标准 | 余量 |
|---|---|---|---|
| 总耗时 | **1.07 s**（会话 1.01 / 索引 0.01） | ≤25 s | 23× |
| 峰值堆占用 | **43.0 MB** | ≤150 MB | 3.5× |
| 校验错误 | **0 条** | 0 | — |
| 未识别 ZIP 条目 | **0 个** | 0 | — |
| 会话数（文件/索引） | 3340 / 3340 | 一致 | — |
| 最慢会话 | 24 ms | — | — |

数据画像（与早期分析完全吻合，可放心按此建模）：

```
ZIP 条目 3422 / 未压缩 153.3MB / export_format_version=3 / 第一条=manifest.json
消息 23580：FINISHED 21510 · INCOMPLETE 2004 · TIMEOUT 45 · CONTENT_FILTER 21
角色：USER 10904 · ASSISTANT 12676
碎片 44076：THINK 13063 · RESPONSE 11643 · REQUEST 10890 · CONTENT_RISK 4411
             TOOL_OPEN 2366 · TOOL_SEARCH 1503 · FILE 125 · TEMPLATE_RESPONSE 66
             TOOL_IMAGE_SEARCH 3 · TOOL_FIND 3 · READ_LINK 3
附件 79 个 / 43.0MB；最大会话 3.6MB
被置 null 丢弃的碎片：0（schema 的容错分支在真实数据里没触发）
```

**字段结论（对导入器/导出器最重要）**：

- 会话顶层字段恰好 7 个：`id, title, pinned, current_message_id, inserted_at, updated_at, messages`
- 消息字段恰好 9 个：`id, parent_id, role, status, thinking_enabled, search_enabled, accumulated_token_usage, inserted_at, fragments`
- **真实数据里没有任何扩展字段**，所以 v3 往返不会丢字段；同时也说明**我们自己绝不能新增字段**（会污染兼容性，硬约束 2）
- `pinned` 在文件里存在，但 chat-demo 导入时硬编码 `false` → 我们的导入器要**保留** `pinned`，导出时写回（比 chat-demo 更正确）
- `message_count`（索引）与 `messages.size` 全部一致，可作导入后的完整性校验

**给 M6 的实现结论**：不需要流式/分批解析，`ZipFile` + 完整 `String` 内存解析足够，一套事务写入即可；但仍建议按会话分事务提交（崩溃可续）。

---

## 3. Spike-2：流式渲染 —— 决定性结论

### 3.1 实验设计

同一份 1897 字 Markdown（含表格、代码块、`===` setext 陷阱、引用、列表、行内代码、链接），
切成 136 个 14 字符的硬分片（模拟最坏分词边界），每 40ms 追加一片（理想总时长 5440ms）。
用 `onGloballyPositioned` 记录内容高度，统计**高度回缩次数**与**最大回缩量**。

### 3.2 结果

| 模式 | 写法 | 实际耗时 | 高度采样 | **回缩次数** | **最大回缩** | 最终高度 |
|---|---|---|---|---|---|---|
| A | `Markdown(String)` | 12441 ms（2.3×） | 86 | **42** | **2042 dp** | 2798 dp |
| B | `rememberMarkdownState(str)` + `Markdown(state)` | 10742 ms（2.0×） | 127 | **61** | **2042 dp** | 2798 dp |
| C | `rememberStreamingMarkdownState()` + `append()` + `Markdown(state)` | **6558 ms（1.2×）** | 159 | **0** | **0 dp** | 2798 dp |

### 3.3 解读

- A/B 的 2042dp 回缩 ≈ 内容从满屏瞬间塌成 1/4 再重建 —— 即**每帧都整篇重新解析、内容短暂消失**。
  这不是抖动，是**闪屏**；在真机上表现为"正在输出的回答反复闪一下、列表往下跳"。
- C 只有 1.2× 于理论时长，且**高度严格单调不减**，159 个采样点无一次回缩。
- A/B 耗时都超过 2×，说明整篇重解析把主线程拖垮（swiftshader 软件渲染下更明显）。

### 3.4 结论（写进 M2-1/M2-2 的硬性规定）

1. **禁止** `Markdown(String)` 重载用于流式输出；
2. **禁止** 用 `rememberMarkdownState` 驱动逐帧追加（它适合"整篇一次性渲染"）；
3. **只用** `rememberStreamingMarkdownState()` + 逐片 `append()` + `Markdown(streamingState)`；
4. 长消息在 `LazyColumn` 里可用库自带的 `LazyMarkdownSuccess(streamingState, snapshot, components, modifier, paddingValues)`（已确认该 API 存在），但**必须先把分片率/帧合并做完再上懒加载**，否则仍会抖；
5. 每帧内容的**高度单调不减**应作为 M2-3 的自动化断言（本次统计方法可直接复用）；
6. **正在生成的那条正文不读数据库**（决策 74）：出字走内存里的实时快照，落库只负责防丢与历史。理由是同为「每帧一次」，两条路的代价差一个量级——读库那条每刷新一次都要把整段会话重新查一遍、把所有碎片重新解码，5Hz 就已经在烧主线程；内存快照只往下追加几个字符。判断依据见 `feature/chat/.../ChatViewModel.kt` 的 `observe()`：它每收到一次落库就把整条对话重新组装。

### 3.5 环境说明（诚实标注）

模拟器为 `Pixel_3a_API_34` + `-gpu swiftshader_indirect`（无头、软件渲染）。
**绝对帧率不可作为 fps 依据**；但"1.2× vs 2.3× 于理论时长"与"0 次 vs 42 次回缩"是相对量，可靠。
真机 ≥55fps 的验收仍然要在 M2-2 之后用真机测（已写入 02 §7.3）。

---

## 4. Spike-3：公式与图表选型 —— 定稿 WebView

### 4.1 纯 Compose LaTeX 库（`io.github.huarangmeng:latex`）排除，三条硬证据

1. **依赖链断裂，根本解析不下来**：它的 `.module` 声明依赖 `latex:latex-base:unspecified`，
   注意 group 是 `latex` 而不是 `io.github.huarangmeng` —— `https://repo1.maven.org/maven2/latex/latex-base/` 返回 **404**。
   这是发布配置错误，不是我们的用法问题；
2. **发布时间与成熟度**：Maven 上只有 `unspecified` 与 `0.0.1` 两个版本，最后一次更新 **2026-01-06**，距今 10 个月；元数据 jar 仅 4KB；
3. **版本倒逼**：它要求 Compose Multiplatform **1.9.3** + `kotlin-stdlib 2.3.0` + CMP material3 1.9.0，
   引入即强迫整个 App 对齐一套与我们无关的版本，收益远小于成本。

> 附带结论：同一作者仍有可用的库——`io.github.huarangmeng:codehighlight` 已到 **1.0.7**（2026-04）。
> M2-4 开工时核实过：**不采用**（2026-11-20）。理由不是「没验证」，而是验完发现不够用——它底下的
> `dev.snipme:highlights` 只有十七种语言、没有 JSON 与 SQL，且字符串里的数字与标点会被错色；
> 代码高亮最终自研，见 03 决策 42。`diagram` 仍因仓库无 LICENSE 不可依赖。

### 4.2 Mermaid 无可用 Kotlin 方案

纯 Kotlin 的 `huarangmeng/diagram` 仓库无 LICENSE（法律上不可依赖），且与上面的版本耦合问题相同。

### 4.3 定稿方案

| 内容 | 方案 | 理由 |
|---|---|---|
| 行内/块级公式 | **KaTeX**（离线打包 `assets/`） | 单 HTML + 本地 JS/CSS；无网络依赖；成熟稳定 |
| Mermaid 图 | **mermaid.js**（离线打包 `assets/`） | 已是事实标准；必须 WebView 才有 |
| 承载方式 | **一个共享的 WebView 渲染宿主** | 公式与图表共用同一宿主、同一套高度回传与深色主题注入，避免维护两套引擎 |

必须一并解决的三个实现细节（M2-5/M2-6 的任务，不是本次 Spike 范围）：

1. **高度回传**：`addJavascriptInterface` 暴露 `onHeight(px)`，渲染完成后把文档高度回传给 Compose，`AndroidView` 的 `Modifier.height()` 跟随；
2. **增量刷新**：流式过程中**不要**每帧重建 WebView——用 `evaluateJavascript` 只更新内容区，并做 200–300ms 节流；
3. **深色主题**：KaTeX 的 CSS 变量 + mermaid 的 `theme` 参数，随 Compose 主题切换时重新注入，而不是重建 WebView。

> 兜底：若 KaTeX/mermaid 在 WebView 里首次渲染超过 300ms（冷启动时），公式先以源码态显示、渲染完成后替换——
> 这属于"单调不减"的例外，需要显式处理（预留高度或用 `Placeholder`），否则又会引入高度回缩。

---

## 5. 工具链实测（M0 必须照此，否则第一天就卡）

产品骨架必须用**当前世代**工具链，实测可用的组合：

| 组件 | 版本 | 说明 |
|---|---|---|
| Gradle | **9.9.1** | 系统 Gradle 8.4 太旧；wrapper 走镜像下载 |
| AGP | **9.5.1** | **AGP 9 起内置 Kotlin 支持，不能再应用 `org.jetbrains.kotlin.android`**（会报错要求移除） |
| Kotlin | **2.4.21** | 由 AGP/Gradle 内置，用 `org.jetbrains.kotlin.plugin.compose` 启用 Compose 编译器 |
| compileSdk | **37** | Compose 1.12.1 强制要求 ≥37（用 36 会直接构建失败） |
| Compose BOM | **2026.09.00** | 对应 Compose 1.12.1 |
| AndroidX Activity | 1.13.0 | activity-compose |
| Markdown 渲染器 | `com.mikepenz:multiplatform-markdown-renderer:0.46.0` + `-m3` | 已验证编译通过、API 可用 |

### 5.1 踩到的坑（按顺序，M0 直接避开）

1. **插件 classloader 冲突**：根工程应用 `kotlin("jvm")`、子工程又带版本声明 `kotlin("android")` → 报
   `Could not generate a decorated class ... BaseVariant`。
   解法：所有插件版本集中放 `settings.gradle.kts` 的 `pluginManagement.plugins {}`，并在**根工程**用 `apply false` 统一解析。
2. **AGP 9 不允许再应用 kotlin-android 插件**（见上表）。
3. **compileSdk 37 平台包缺失**：本机 SDK 只有 34/36，且**没有 sdkmanager**（无 cmdline-tools、无 Android Studio）。
   手工装了 `platform-37.0_r02.zip`，坑在于：
   - 包内顶层目录是 `android-37.0`，**必须**放成 `platforms\android-37.0`（放 `android-37` 会报 `Failed to find Platform SDK with path: platforms;android-37.0`）；
   - 原始 `source.properties` 里 `AndroidVersion.ApiLevel=37.0`、`Platform.Version=17`（Google 打包笔误），
     sdklib 按整数解析失败 → 平台被视为不存在。**改成 `ApiLevel=37` + 增加 `AndroidVersion.MinorApiLevel=0` 后立刻可用**。
   - → **M0-1 必须补装 cmdline-tools 并用 `sdkmanager` 正规安装**，不要依赖这次的手工修补。
4. **Gradle wrapper 镜像**：`services.gradle.org` 10s 超时；腾讯镜像能下但 wrapper 解压报
   `zip END header not found`（下载被截断）。本次改为**手工下载 + 解压到 `.tools\gradle-9.9.1`** 直接用。
   → M0 生成 wrapper 时要设 `networkTimeout=120000`，并核对 `sha256`。

---

## 6. 未完成项（明确的、不是遗漏）

| 项 | 状态 | 谁来做 |
|---|---|---|
| 在线 SSE 真打 | 待 Key | 用户给 Key 后 5 分钟 |
| 真机 fps / jank 验收 | 模拟器软件渲染不可作准 | M2-2 之后用真机 |
| KaTeX / mermaid 离线资源与高度回传 | 机制已定，实现属 M2-5/M2-6 | M2 |
| `codehighlight` 1.0.7 是否可用 | **已核实：可用但不采用**（引擎十七种语言、无 JSON 与 SQL、字符串内错色） | M2-4 已定，见 03 决策 42 |

---

## 7. 复现方式

```powershell
cd E:\code\TinyChat-spike

# 离线两项（不需要 Key、不需要模拟器）
.\.tools\gradle-9.9.1\bin\gradle.bat run --args="sse"
.\.tools\gradle-9.9.1\bin\gradle.bat run --args="zip"

# 在线（需要 Key）
$env:DEEPSEEK_API_KEY="sk-..."; .\.tools\gradle-9.9.1\bin\gradle.bat run --args="sse-live"

# 渲染对比（需要模拟器）
.\.tools\gradle-9.9.1\bin\gradle.bat :app:assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n spike.render/.MainActivity
adb logcat -s SPIKE
```

原始输出留档：`out\sse.txt`、`out\zip.txt`。

---

## 8. 对 01/02/03 的修订清单（已执行）

1. **02 §2.2 依赖清单**：改为上面的实测版本；补 `compileSdk 37`、`pluginManagement` 写法、AGP 9 无 kotlin-android。
2. **02 §M2-1/M2-2/M2-3**：写入"只用 `rememberStreamingMarkdownState`"的硬规定 + 高度单调断言。
3. **02 §M2-5/M2-6**：定稿 WebView + 离线 KaTeX/mermaid.js 共享宿主，记录三条排除证据。
4. **03 §4.2**："LaTeX 选型未定" → 已定稿；"Mermaid 无方案" → 已定 WebView。
5. **03 §7**：Spike 由"待做"改为"已完成 3/4，剩在线 SSE 待 Key"。
6. **01 §8**：LaTeX 决策项落定。
