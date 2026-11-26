# TinyChat 调研报告与初步实施方案

> 状态：调研完成（2026-11-17）
> 输入：`temp-doc/outline.md`、`temp-doc/app-preview/*.jpg`（10 张）、`temp-doc/deepseek_data-2026-11-16.zip`（3340 个会话）、`temp-doc/base.apk`
> 输出：结论先行 → 调研发现 → 逐功能可行性 → 技术选型与架构 → 风险与对策 → 分阶段计划 → 需要你拍板的问题

**目录**

- [0. 结论先行](#0-结论先行)
- [1. 需求复述与范围界定](#1-需求复述与范围界定)
- [2. 调研发现](#2-调研发现)
- [3. 逐功能可行性核对](#3-逐功能可行性核对)
- [4. 技术选型与架构](#4-技术选型与架构)
- [5. 主要风险与对策](#5-主要风险与对策)
- [6. 分阶段计划](#6-分阶段计划)
- [7. 建议的第一个可验证切片（Spike，3 天）](#7-建议的第一个可验证切片spike3-天)
- [8. 需要你拍板的问题](#8-需要你拍板的问题)
- [附录 A：API 速查](#附录-aapi-速查) / [B：环境现状](#附录-b环境现状本机已核对) / [C：参考资料](#附录-c参考资料) / [D：设计规格](#附录-d设计规格从-chat-demo-themecss-提取) / [E：UI 元素清单](#附录-eui-元素清单从-10-张截图提取)

---

## 0. 结论先行

**结论：方案可行，而且比预期好得多。项目成立的核心前提已经具备。**

三条决定性发现：

1. **官方开放平台已经把"网页端/APP 的全部对话能力"开放成 API 了，而且明文支持"1:1 复刻"。**
   联网搜索、深度思考搜索、阅读链接、文件解析、内容安全、会话标题、用户元信息、ASR、TTS——一整套"全家桶 API"在 2026-11-13 正式发布，官方还专门写了一篇 [构建 Chatbot](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot) 指南。官方公告原文：

   > "开发者可以在自己的应用中轻松实现复杂的对话功能，甚至 **1:1 复刻出完整的 DeepSeek App**。"
   > "开启对应参数后，相关工具调用与上下文拼接均由**服务端自动完成**。"
   > —— [DeepSeek 发布全家桶应用 API](https://api-docs.deepseek.com/zh-cn/news/news261113)

   **这类"搜索是服务端做的、客户端只负责渲染内容块"的能力，原本是我最担心的不可复制部分，现在是被官方公开背书的一等公民。** 这条同时解决了"复刻是否合规"的心理负担——至少产品层面，官方是鼓励的。

2. **`chat-demo` 不是"网页端源码"，而是官方演示客户端；MIT 协议；且它跑的就是这套 API。**
   模型名、请求形态（`/anthropic/v1/messages`）、扩展字段（`deepseek_web_search`、`safety_check`、`title`、`latest_reminder`）与我们能用的公开 API **完全一致**。也就是说：参考项目不是"看着像"，而是**同一套接口的另一个客户端实现**，可以直接当"可执行规格说明书"用。

3. **`chat-demo` 的 UI 资产（i18n 文案、60KB SVG 图标、设计 token、Mermaid/KaTeX 集成方式）都是 MIT 的，可以合法移植。**
   这把"复刻 UI"从"逆向/抄视觉"变成了"移植一份 MIT 代码 + 用截图校准"。

同时有 **三个必须提前决策的点**（详见 §5）：

- **商标与"冒充"风险**：可以直接照抄的是**布局/间距/字号/色值**，不能直接照抄的是 **DeepSeek 名称与鲸鱼 Logo**。建议保留"视觉语言"、换掉"品牌标识"，并在关于页声明非官方。
- **多服务商的"能力落差"**：搜索/文件/语音/余额全是 DeepSeek 独有能力，第三方兼容服务商拿不到。必须做**能力声明 + UI 降级**，否则用户会以为"坏了"。
- **成本不再为 0**：联网工具按次计费（5 元/1000 次）。重度使用一个月约 ¥20–60，需在 App 内做用量可见化（正好是你要的上下文/费用功能）。

---

## 1. 需求复述与范围界定

### 1.1 目标
做一个 Android 原生 App（Jetpack Compose），复刻 DeepSeek 官方 App 的交互体验，通过**用户自己的付费 API Key** 替代官方订阅额度，解决：
- 每日 token 总额限制
- 7 天不活跃会话冻结
- 修改/重新生成次数与冷却限制

### 1.2 明确不做（来自 outline）
- 不做上下文压缩（保 Chat 一致性）
- 不做"账号体系"（换成服务商 + API Key）
- 不逆向 APK（除非确实无法确定；本次调研已找到替代路径，见 §3 备注 2）
- 不把 `temp-doc/` 纳入 git

### 1.3 验收锚点
- 视觉/交互与 `temp-doc/app-preview` 的 10 张截图「难以分辨」
- 能无损导入 `deepseek_data-*.zip`（v3 格式），并能导出同格式
- 打字机流式 + 折叠思维链 + 搜索步骤卡片 + 引用角标，四者要对

---

## 2. 调研发现

### 2.1 官方 API 能力矩阵（2026-11）

模型（[模型 & 价格](https://api-docs.deepseek.com/zh-cn/quick_start/pricing)）：

| | `deepseek-flash` | `deepseek-v4-pro` |
|---|---|---|
| 版本 | DeepSeek-V4.1-Flash-1017 | DeepSeek-V4-Pro-0813 |
| 上下文 | 1M | 1M |
| 最大输出 | 384K | 384K |
| 思考模式 | 支持（默认开） | 支持 |
| 图像理解 | ✅ | ❌（会替换为 `[Unsupported Image]`） |
| **服务端扩展能力**（联网搜索/阅读链接/文件输入/标题） | ✅ **仅此模型支持** | ❌ |
| 客户端工具调用 / JSON / Anthropic API | ✅ | ✅ |
| 并发上限 | 2500 | 500 |

> ⚠️ **重要约束**：全家桶扩展能力（`deepseek_web_search` / `deepseek_read_url` / 文件 `file_id` / 图片）**目前只有 `deepseek-flash` 支持**。官方文档在联网工具、文件解析两处都明确写"请使用 `deepseek-flash` 模型"。
> 这直接影响 outline 新增功能 #3（"把深度思考换成模型切换"）：**切到 `deepseek-v4-pro` 会同时失去联网、读链接、传文件、发图**。UI 必须如实提示，否则用户会认为"是 bug"。

三套协议（同一套对话能力）：

| 协议 | 端点 | 备注 |
|---|---|---|
| Messages（Anthropic 兼容） | `POST https://api.deepseek.com/anthropic/v1/messages` | **chat-demo 用的就是这条**，扩展字段最全 |
| Responses（OpenAI 风格） | `POST https://api.deepseek.com/responses` | 不支持 `latest_reminder`、不支持续写 |
| Chat Completions | `POST https://api.deepseek.com/chat/completions` | 兼容面最广，续写需 `/beta` + `prefix:true` |

**技术决策：主链路走 Messages（Anthropic 兼容）**，理由：
1. 与 chat-demo 完全同构，可直接照搬其请求/事件处理逻辑与已验证的坑；
2. `server_tool_use` / `web_search_tool_result` / `web_fetch_tool_result` / `citations` / `thinking` 块语义清晰，天然映射为"碎片列表"；
3. 服务端工具的多轮回传规则（`kind` + `signature` 原样回传）在 Messages 下表达最自然。

### 2.2 关键扩展字段（复刻体验的关键）

| 字段 | 作用 | 对应 outline 的功能 |
|---|---|---|
| `tools:[{type:"deepseek_web_search"},{type:"deepseek_read_url"}]` | 服务端联网搜索 / 阅读链接 | 智能搜索、思维链里的搜索步骤 |
| `thinking:{type:enabled/disabled}` | 思考开关 | 深度思考 |
| `output_config:{effort:low/high/max}` | **思考强度** | 新增功能 #3（替代深度思考） |
| `safety_check:{level:"high"}` | 基础风控 + 拒答语义 | 合规必需（对外分发必须开） |
| `title:{enabled:true}` | 回答顺带生成会话标题（不计费，最多等 5s） | 自动标题 |
| `{"role":"latest_reminder", content:"日期,星期,地点,平台,语言"}` | 用户元信息 | 新增功能 #6 |
| `POST /anthropic/v1/files` + `document`/`image` 块 | 文件/图片上传解析 | 图片与文件上传 |

`latest_reminder` 格式已确证：`2026-11-09,星期一,新加坡,Web,中文`，语言取值表官方给了 70+ 项，`platform` 只能是 `App` 或 `Web`（我们用 `App`）。

### 2.3 思考强度（新增功能 #3 的落点）

- Messages 协议：`output_config: {effort: "low"|"high"|"max"}`
- 映射表：`minimal→low`、`medium→high`、`xhigh→high`、`ultra→max`
- **注意**：声明了联网工具 / `safety_check:high` / `title` 后进入"扩展模式"，`temperature`、`top_p`、`thinking.budget_tokens` 等参数会被**忽略**。所以"思考强度"在扩展模式下只能靠 `output_config.effort` 表达——这正好是我们想要的。

### 2.4 用量、计费与余额（新增功能 #4、#5 的落点）

流式最终 `message_delta.usage` 是计费依据：

```jsonc
{ "input_tokens": 9078,                 // 未命中缓存
  "cache_read_input_tokens": 4096,      // 命中缓存
  "cache_creation_input_tokens": 0,     // 恒为 0
  "output_tokens": 1686,
  "output_tokens_details": { "thinking_tokens": 812, "safety_tokens": 0 },
  "server_tool_use": { "tool_search": 2, "tool_fetch": 0, "tool_image_search": 0 } }
```

费用公式（元）：

```
cost = input_tokens/1e6 * price_in_miss
     + cache_read_input_tokens/1e6 * price_in_hit
     + output_tokens/1e6 * price_out
     + tool_search * 0.005 + tool_fetch * 0.005 + tool_image_search * 0.005
```

价格（元/百万 token，**空闲/高峰**两档，高峰 = 北京时间周一至周五 9:00–12:00、14:00–18:00，法定节假日除外）：

| 模型 | 输入(命中) | 输入(未命中) | 输出 |
|---|---|---|---|
| `deepseek-flash` | 0.02 / 0.04 | 1 / 2 | 4 / 8 |
| `deepseek-v4-pro` | 0.15 / 0.30 | 4.5 / 9 | 13.5 / 27 |

其他计费项：搜索 5 元/1000 次、抓取 5 元/1000 次、文件解析 1.5 元/1000 页、TTS 3 元/万字符（限时 1.5）、ASR 0.78 元/小时（限时 0.39）。

**余额**：`GET https://api.deepseek.com/user/balance`（需 `Authorization: Bearer`），返回 `is_available` + `balance_infos[{currency,total_balance,granted_balance,topped_up_balance}]`。→ 新增功能 #5 直接可用；但**仅 DeepSeek 系服务商支持**，第三方需隐藏。

**成本感**（用于说服自己"这方案真的省钱"）：一轮典型问答（上下文 8K 命中 + 2K 未命中 + 1.5K 输出 + 1 次搜索），flash 空闲时段约 **¥0.012**；100 轮/天 ≈ **¥1.2/天**。v4-pro 约 6–7 倍。这与官方订阅的价格形成有效对照。

### 2.5 语音

- **ASR**：`POST https://api.deepseek.com/v1/tools/asr`，multipart，16-bit PCM WAV 或 Ogg Opus，≤8MiB、≤120s，单声道（双声道只取左）。返回 `{text, duration_ms}`。不支持流式。
- **TTS**：`POST https://api.deepseek.com/v1/tools/tts`（整段，流式返回 `audio/wav` 或 24kHz/16bit/mono PCM），或 WebSocket `wss://api.deepseek.com/v1/tools/tts`（边生成边合成）。
- **音色只有两个**：`stella`（海星，女，默认）、`tide`（暗潮，男）。→ outline 里"可以选择不同的朗读音色"，**API 侧只有 2 个**。**已定：v1.0 只用这 2 个官方音色，不接系统 TTS、不接第三方 TTS。**

### 2.6 数据格式（新增功能 #2 的落点）

`deepseek_data-*.zip`，`export_format_version: 3`，结构：

```
manifest.json                              {"export_format_version": 3}   ← 必须是第一个条目
user.json                                  账号信息（导入时跳过）
chat_sessions/{session_id}.json            会话（含消息树）
chat_session_index.json                    索引 [{id,title,pinned,message_count,inserted_at,updated_at}]
chat_sessions/{session_id}_files/{file_id}_{file_name}   图片原件
```

**实测样本统计**（`temp-doc/deepseek_data-2026-11-16.zip`）：3340 个会话 / 23580 条消息 / 79 个图片附件（png 53、webp 13、jpg 8、jpeg 5）/ 解压后 ~87MB。消息状态分布：`FINISHED` 21510、`INCOMPLETE` 2004、`TIMEOUT` 45、`CONTENT_FILTER` 21。

碎片（fragment）类型与出现次数：

| type | 次数 | 含义 |
|---|---|---|
| `THINK` | 13063 | 思维链（含 `elapsed_secs`） |
| `RESPONSE` | 11643 | 正文 |
| `REQUEST` | 10890 | 用户提问 |
| `CONTENT_RISK` | 4411 | 风险提示条，**实测全部是同一句"AI 生成仅供参考"** → 直接丢弃 |
| `TOOL_OPEN` | 2366 | 边想边搜打开的页面（url/title/snippet/site_*/published_at/stage_id） |
| `TOOL_SEARCH` | 1503 | 搜索（queries + results[]） |
| `FILE` | 125 | 附件（id/file_name/file_size/token_usage/export_path/is_image/width/height） |
| `TEMPLATE_RESPONSE` | 66 | 服务端模板（"服务器繁忙"等），不可入上下文 |
| `TOOL_FIND` | 3 | 页内查找（pattern） |
| `READ_LINK` | 3 | 阅读链接（results[].signature） |
| `TOOL_IMAGE_SEARCH` | 3 | 图搜 |

**结论：内部数据模型直接对齐这份导出格式**（详见 §4.3），导入导出天然可逆，不用写两套转换。

### 2.7 参考项目与竞品

| 项目 | 性质 | 对我们的价值 |
|---|---|---|
| [chat-demo](https://github.com/deepseek-ai/chat-demo) | 官方静态 Web 客户端，MIT，4.3k★ | **主参考**：请求形态、SSE 事件处理、碎片模型、i18n、图标、设计 token、导入导出实现 |
| [DeepChat](https://github.com/YanJiuWeiYuanHui/DeepChat) | chat-demo 的 Capacitor 套壳安卓魔改版，MIT | 证明"套壳"路线可行（也说明体验会打折）；**多服务商/多模态通道抽象**做得很完整，可参考数据结构 |
| [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) | 重量级 Agent 框架 | 参考上下文/用量计量、模型选择与路由 |
| OpenCode Go | 订阅制编程模型网关 | 见 §2.8 |

**为什么不直接套壳 DeepChat？** 它已经解决了"能用"的问题，但 outline 的立项理由恰恰是"体验复刻"和"轻量"。WebView 套壳在长列表滚动、输入法、流式打字、TTS 播报、分享出图这些高频交互上与原生差距明显，且无法做你要的上下文饼图/余额常驻等原生控件。→ **坚持原生 Compose**，但**大量复用 DeepChat 的 provider 抽象思路**。

### 2.8 OpenCode Go（备注 1）核查结论

outline 的判断**方向正确，但表述需要修正**：

| outline 说法 | 实际情况 |
|---|---|
| 需要携带"自身标识" | ✅ 但**不是伪装**：官方要求用**自己的 UA**（`TinyChat/1.0`），**禁止**伪装成 OpenCode CLI |
| 需要"稳定的对话会话请求头" | ✅ **硬要求**：`x-opencode-session`，缺失直接 `400 MissingSessionID` |
| 通用 OpenAI 配置"可能无法使用" | ✅ 实为**确定性失败**，不是"可能" |
| base URL | 应为 `https://opencode.ai/inference/go` + 协议子路径（不是根、不是 `/zen/go/v1`） |

最小实现要求：
1. base URL `https://opencode.ai/inference/go`，再拼 `/openai/v1/chat/completions`、`/openai/v1/responses`、`/anthropic/v1/messages`；
2. 每个请求带 `x-opencode-session`：**按会话稳定的 ASCII 值、≤64 字符**（最简 `sha256(sessionId).hex()`）；
3. **同一会话的所有派生请求必须同值**（主请求、标题、图片描述、重试）——这是最容易漏的地方；
4. UA 用自己产品名 + 版本；
5. 逐模型记录原生协议（`anthropic-messages` / `openai-completions` / `openai-responses`），不能全局一个协议；
6. 无 OAuth，Bearer key 即可。

ToS 层面：第三方客户端被**明确支持**（官方列出 Claude Code/Codex/Hermes/Pi 等已验证客户端），但禁止伪装身份、禁止多账号绕额度、禁止逆向。→ 我们的做法（用户自填 base URL、自己的 UA、本地存 key）**风险可控**。

> 定价与模型清单会变，`/go/v1/models` 可动态拉取。建议**把 OpenCode Go 做成一个独立 provider 类型**，而不是"通用 OpenAI 兼容"的一个实例。

---

## 3. 逐功能可行性核对

### 3.1 官方已有功能（12 项）

| # | 功能 | 可行性 | 实现要点 / 风险 |
|---|---|---|---|
| 1 | 基础聊天 + 思维链 + 搜索工具调用展示 | ✅ 高 | 服务端下发 `thinking` / `server_tool_use` / `web_search_tool_result` 块，客户端只渲染。**不需要自己实现搜索** |
| 2 | Markdown / LaTeX / Mermaid 渲染 | ✅ 中高 | Markdown 用现成库；**LaTeX 与 Mermaid 建议 WebView + 本地打包 KaTeX/mermaid.js**（见 §4.5）。流式重渲染是性能瓶颈，需节流 |
| 3 | 深度思考开关 | ✅ 高 | 改成"模型 + 强度"（`output_config.effort`）。**注意：切到 `deepseek-v4-pro` 会失去联网/读链接/文件/图片（见 §2.1 约束），且 v4-pro 不支持图片** |
| 4 | 智能搜索开关 | ✅ 高 | 仅控制是否声明 `deepseek_web_search`（`deepseek_read_url` 常开，与 chat-demo 一致） |
| 5 | 会话改名 / 置顶 | ✅ 高 | 本地 DB；置顶排序 + 自动标题（`title.enabled` 仅首次请求开） |
| 6 | 图片 / 文件上传 | ✅ 中 | Files API：解析型（PDF/Word/PPT）`text_recognition`/`rich_layout_recognition`，纯文本类 `user_data`。限制：100MiB、1000 页、120s 上传+解析、每请求最多 20 个文档且合计 ≤8MiB 文本。**仅 `deepseek-flash` 可用**；移动网络下大文件要进度与重试 |
| 7 | 修改输入（编辑后重发） | ✅ 高 | 消息树 + 分支：编辑 → 在原 user 消息下新建分支 |
| 8 | 重新生成 / 恢复 | ✅ 高 | 复用分支机制；续写（`prefix`）注意：**用过联网工具的回复不能续写**（服务端 400） |
| 9 | 语音输入 | ✅ 中 | 本地录音 → 16kHz 单声道 WAV → `/v1/tools/asr`。**要自己画实时频谱**（截图里有） |
| 10 | TTS 朗读 + 音色 | ⚠️ 中（受限） | API 只有 `stella`/`tide` 两个音色。建议：服务商 TTS + 系统 TTS 兜底 |
| 11 | 个性化（多语言/深浅色/字号） | ✅ 高 | DataStore + 设计 token 双套色板（已从 chat-demo 取到完整色板） |
| 12 | 分享为图片 | ✅ 中 | Compose 把消息渲染进 `Bitmap` → 分享。长文需分页/长图上限 |

### 3.2 计划新增功能（6 项）

| # | 功能 | 可行性 | 说明 |
|---|---|---|---|
| 1 | 服务商与 API Key 设置 | ✅ 高 | Provider 抽象 + 能力位；Key 用 Keystore/加密存储 |
| 2 | 数据管理：导入导出（兼容官方 v3） | ✅ 高 | 格式已完全掌握（§2.6），3340 会话样本可做验收基准。**导出必须保留 `READ_LINK.signature` / `kind` / `stage_id` / `group_id`**，否则回传后网页上下文丢失 |
| 3 | 深度思考 → 模型 + 思考强度；搜索按钮只留图标 | ✅ 高 | UI 改动 + `output_config.effort`；底栏空间问题顺带解决 |
| 4 | 上下文饼图 / token 计数 / 费用预估 / 每轮统计 | ✅ 中高 | 本轮精确值来自 `usage`；**发送前的预估值**需 tokenizer（官方提供 6.4MB `tokenizer.json`）或启发式（中文字符×0.6、英文字符×0.3）。饼图分母 = 模型上下文（1M） |
| 5 | 侧栏底部显示余额 | ✅ 高（DeepSeek only） | `GET /user/balance`；第三方服务商隐藏该区域 |
| 6 | 用户元信息开关 | ✅ 高 | `latest_reminder`，需按语言生成星期名（官方给了 70+ 语言取值表） |
| 7 | **系统提示词自定义 + 预设 + 恢复默认** | ✅ 高（但有 2 个约束） | 见 §4.9。约束一：自定义提示词**不能**随 v3 导出（见 §4.9.4）；约束二：会话内改动会**击穿 KV 缓存**（命中价 0.02 vs 未命中 1，差 50 倍） |

### 3.3 outline 备注（3 项）

| 备注 | 核查结论 |
|---|---|
| 1. 兼容 OpenCode Go | 需要专门设计，不能当通用 OpenAI 服务商用。方案见 §2.8 / §4.4 |
| 2. DeepSeek APP 也是 Compose，必要时可逆向参考 | **本次调研已找到完全够用的合法替代路径**（官方 API 文档 + chat-demo MIT 源码 + 截图），因此**建议不逆向**。仅当某处动效/细节确实无法确定时，再考虑对 `base.apk` 做只读的**资源字典提取**（颜色/字符串/布局尺寸），且不复制代码、不留存反编译产物。⚠️ 另注：APK 的逆向可能违反其用户协议，且 App 内含他人账号信息风险，建议只用 `aapt2 dump` 级别的**公开元数据**（包名、版本、图标尺寸） |
| 3. MIT 开源 | 可。需：保留我们的 LICENSE；在 NOTICE/README 中致谢 chat-demo（MIT）；因为我们参考/移植了其文案与图标（MIT），需保留其版权声明。**注意 MIT 不覆盖 DeepSeek 商标与品牌** |

---

## 4. 技术选型与架构

> 本章的库版本在 2026-11 核对过；实施时以当时的稳定版为准。

### 4.1 Compose vs Compose Multiplatform

**结论：用纯 Jetpack Compose（Android-only）。**

| 判据 | 纯 Compose | CMP |
|---|---|---|
| iOS 诉求 | 无（outline 只提手机端 APP，实际是 Android） | 才有意义 |
| 生态缺口 | 无 | WebView 交互、TTS/ASR、SAF 文件选择、分享出图**都需 expect/actual 重写** |
| 构建复杂度 | 低 | 多 target、KMP 依赖解析更慢 |
| 迁移成本 | 若将来要 iOS，UI 层可平移（ViewModel/数据层已用 Kotlin 纯逻辑） | — |

> 如果未来真要 iOS：把 `core:model` / `core:net` / `core:data` 写成纯 Kotlin（不依赖 Android API）即可，UI 层再按需迁移。**先不为不存在的需求付成本。**

### 4.2 模块划分

```
:app                    导航、DI 装配、主题、启动
:core:model             纯 Kotlin：Session/Message/Fragment/Usage/Provider 定义
:core:net               OkHttp + SSE 解析 + 协议适配器（DeepSeek-Anthropic / OpenAI / Anthropic / OpenCodeGo）
:core:data              Room、DataStore、附件文件仓库、ZIP 导入导出
:core:designsystem      色板/字体/形状/图标（移植 chat-demo theme.css + icons.tsx）
:core:markdown          Markdown/KaTeX/Mermaid/代码高亮渲染
:feature:chat           会话页（消息列表、输入区、流式状态）
:feature:sessions       会话列表（分组、置顶、重命名、删除）
:feature:settings       服务商/模型/外观/语音/元信息
:feature:data           导入导出、清空
```

### 4.3 数据模型：碎片化（与导出格式同构）

**这是整个设计的关键决策。** 一条消息不是一个字符串，而是**有序碎片列表**：

```kotlin
sealed interface Fragment {
  data class Request(val content: String) : Fragment
  data class Response(val content: String) : Fragment            // 正文
  data class Think(val content: String, val elapsedSecs: Int?) : Fragment
  data class ToolSearch(val queries: List<String>, val results: List<SearchResult>,
                        val status: ToolStatus?, val stageId: Int?) : Fragment
  data class ToolOpen(val status: ToolStatus, val url: String?, val title: String?,
                      val snippet: String?, val siteName: String?, val publishedAt: String?,
                      val stageId: Int?) : Fragment
  data class ToolFind(val status: ToolFindStatus, val pattern: String?, val stageId: Int?) : Fragment
  data class ReadLink(val results: List<ReadLinkResult>) : Fragment   // 保留 signature！
  data class File(val files: List<FileEntry>) : Fragment
  data class Template(val content: String) : Fragment
  data object ContentRisk : Fragment
}

data class Message(
  val id: Long, val parentId: Long?, val role: Role, val status: MessageStatus,
  val thinkingEnabled: Boolean, val searchEnabled: Boolean,
  val accumulatedTokenUsage: Int, val insertedAt: Instant,
  val fragments: List<Fragment>,
  // 仅本地追加：本轮精确用量与费用
  val usage: Usage?, val costYuan: Double?, val model: String?, val effort: String?
)
```

好处：
1. 导入导出**零转换**（字段名直接对齐 v3）；
2. 流式渲染只需"给最后一个碎片追加文本"，不用重排整条消息；
3. 搜索步骤卡片、思维链折叠、引用角标都是**碎片的自然投影**；
4. 回传请求时按碎片重建 `content` 数组（`kind`/`signature` 原样带回），与服务端约定一致。

**注意**：`usage`/`cost`/`model` 不入导出（v3 不含这些字段），仅在本地库保留——与 chat-demo 的取舍一致（导出不保留用量明细）。

### 4.4 网络层与协议适配

```kotlin
interface ChatProtocol {
  suspend fun stream(req: ChatRequest): Flow<WireEvent>   // SSE 原始事件
  fun encode(req: ChatRequest): JsonObject                 // 请求体
  val capabilities: Capabilities
}

data class Capabilities(
  val serverSearch: Boolean, val readLink: Boolean, val files: Boolean,
  val thinkingEffort: Set<Effort>, val title: Boolean, val latestReminder: Boolean,
  val balance: Boolean, val vision: Boolean, val tts: Boolean, val asr: Boolean,
  val maxContext: Int, val maxOutput: Int
)
```

| Provider | 协议 | 特殊点 |
|---|---|---|
| DeepSeek 官方 | Messages | 全家桶能力全开；`safety_check:high` + `title` + `latest_reminder` |
| OpenAI 兼容 | Chat Completions | 能力降级：无服务端搜索/文件/余额；搜索改用**客户端工具循环**（可选，第二阶段） |
| Anthropic 官方 | Messages | 思考 = `thinking.budget_tokens`；无 `latest_reminder` |
| OpenCode Go | 按模型选 3 种协议 | `x-opencode-session` + `User-Agent`（见 §2.8） |

**SSE 解析**：手写解析器（按 `event:` / `data:` 行），原因：DeepSeek 的流里既有具名事件也有无名 `data` 错误帧、还有 `: keep-alive` 注释——第三方 SSE 库经常在这三种情况上翻车。chat-demo 的 `parseEvent.ts` / `sse.ts` 已验证过这些边界，照抄逻辑。

**请求重建（回传规则，最容易出错的地方）**：
- 历史 assistant 消息：只回传正文 + `kind == read_link` 的调用与结果（含 `signature`）；**历史思考不回传**（除非本轮续写且开启思考）
- 搜索结果、打开的网页、历史思考都**不进**后续上下文（服务端只保留正文）
- 失败/模板块的轮次**整轮跳过**
- 续写：末尾放未完成的 assistant，且**用过联网工具的回复不能续写**

### 4.5 渲染栈

| 能力 | 方案 | 理由 |
|---|---|---|
| Markdown | `com.mikepenz:multiplatform-markdown-renderer`（**v0.46.0**，2026-10） | Compose 原生；**自带流式增量 API**（`rememberStreamingMarkdownState()` / `collectAsStreamingMarkdownState()`），append 时只重解析"不稳定尾部"；内置 GFM 表格、GitHub alerts、`LazyMarkdownSuccess`（基于 LazyColumn 的超长文档） |
| 代码高亮 | **自研词法扫描器**（`core/markdown/SyntaxHighlighter.kt`，+21 条单测） | 十二种语言。官方 `-code` 模块的引擎只有十七种且**没有 JSON 与 SQL**（SQL 连 `--` 注释都不认），引擎作者自己留了「已排除区间未应用到全部定位器」的 TODO，导致字符串里的数字与标点被错色；它的可视组件我们又用不上（代码块是自绘的）。详见 03 决策 42 |
| 行内 LaTeX | ✅ **已定稿：WebView + 离线 KaTeX** | 纯 Compose 候选 `io.github.huarangmeng:latex:0.0.1` **已排除**：`.module` 依赖 `latex:latex-base:unspecified`（group 写错）→ Maven Central **404**，构建必挂；且只有 0.0.1（2026-01）、强制 CMP 1.9.3 + kotlin-stdlib 2.3.0。详见 04 §4 |
| 块级 LaTeX | 同上（与行内共用宿主） | 需处理"未闭合 `$$`"的流式中间态（见 §4.5.1） |
| Mermaid | **WebView + 本地 `mermaid.min.js`**（assets，离线，与 KaTeX 共用同一 WebView 宿主） | 生态上没有成熟的 Compose 原生实现；`huarangmeng/diagram` 无 LICENSE |

> ⚠️ **三个必须核实的库风险 — Spike 已全部核实完毕（2026-11-17）**：
> 1. ~~`huarangmeng/latex`~~ → **排除**（见上表：依赖 group 写错、Maven 404、0.0.1 且倒逼 CMP 1.9.3）。
> 2. `huarangmeng/diagram`（纯 Kotlin Mermaid）：**仓库无 LICENSE 文件** → **不可直接依赖或复制**。Mermaid 走 WebView。
> 3. 坐标已实测：`com.mikepenz:multiplatform-markdown-renderer:0.46.0` + `-m3` **可用**（编译通过）；
>    `io.github.huarangmeng:codehighlight:1.0.7` 存在但**不采用**（引擎语言覆盖与错色问题见上表，M2-4 改为自研）。
>    **流式必须用 `rememberStreamingMarkdownState` + `append()`**：实测 0 次高度回缩；用 `Markdown(String)` 或 `rememberMarkdownState` 驱动追加会每帧塌陷（42–61 次回缩 / 2042dp）。
>
> ⚠️ **不要选 `jeziellago:compose-markdown`**：它封装 Markwon 4.6.2（自 2021 年停更），本质是 `TextView` 包装，**没有增量解析**，在流式场景会整段重排。

**WebView 复用策略**（用于 Mermaid，以及 LaTeX 的兜底路径）：一个 1–2 个 WebView 的池，串行渲染并高度回传；滚出屏幕的图换成静态 `Bitmap`，避免长列表里 N 个 WebView 同时活着。深色主题通过给 mermaid.js 传 `theme: 'dark'` + 背景透明处理。

#### 4.5.1 流式长列表"结尾抖动"的三个已定位根因

这是 Chat UI 最容易返工的地方，社区已有逐帧实测（小米13 120Hz / Redmi 8A 60Hz，2026-10）：

| 根因 | 现象 | 修法 |
|---|---|---|
| 流式切块恰好停在单独的 `=` 或 `-` 上 | 上一行被 CommonMark 解析成 setext 标题 → **下移 56dp** | 流式期间，最后一行匹配 `^[ \t>]*(=+|-+)[ \t]*$` 时**暂缓**交给解析器 |
| 占位消息落库后换 id | `items(key={it.id})` 的 key 变化 → 整条 item 销毁重建 → 块级公式首帧为空 → 下移 **61dp** + 75ms 长帧 | **UI 身份与存储 id 分离**（`val key get() = uiKey ?: id`），替换时沿用占位 key |
| 未闭合块级 `$$` 先显示源码 | 闭合后从 4 行源码变成 2 行公式 → 下移 **52dp** | 流式期间未闭合的块级公式显示"一字高骨架"；**必须由扫描器在原位留占位**（不能在文末追加），且只认**行首**定界符（否则链接里的 `\[` 会误触发） |

**review checklist 判据**：`reverseLayout=true` 时底部固定，消息**变高 = 内容上推（正常）**；消息只要在**任何一帧变矮 = 内容下跳（bug）**。→ 设计原则：**每一帧中间态在视觉上单调不减高度**。

> 另有一个致命小坑：`key` 不能写成"默认值等于 `id` 的构造参数"，因为 data class 的 `copy(id=…)` **不会重算默认参数**，会导致所有消息共用 key 0 → LazyColumn 重复 key 直接崩溃。

### 4.6 存储与安全

| 数据 | 方案 |
|---|---|
| 会话 / 消息 / 碎片 | Room（`messages` 表 + `fragments` 表 或 单列 JSON；分支靠 `parent_id`） |
| 设置 | DataStore（Proto 或 Preferences） |
| API Key | Keystore 加密后存 DataStore。⚠️ `androidx.security-crypto`（EncryptedSharedPreferences/EncryptedFile）**已 deprecated**，官方不再推荐新项目使用；建议用 Keystore + AES-GCM 自封装（约 100 行），或采用社区替代库 |
| 附件 | `filesDir/attachments/{fileId}_{name}`；图片另存缩略图 |
| 导出 | SAF `ACTION_CREATE_DOCUMENT` + 流式写 ZIP（`java.util.zip` 逐条 `putNextEntry`，避免全量进内存） |

**导入 87MB / 3340 会话的性能要求**：必须流式解压 + 分事务写入 + 进度回调；不能 `readAllBytes`。建议导入前先读 `manifest.json` 校验版本，再按 `chat_sessions/*.json` 逐个解析入库（暂存区 + 一次性切换，失败回滚）。

### 4.7 上下文与费用（新增功能 #4 的实现细节）

- **分母**：模型上下文长度（DeepSeek 两模型均 1M），按 provider/model 可配置
- **分子（本轮）**：`input_tokens + cache_read_input_tokens + output_tokens`（服务端权威值）
- **分子（发送前预估）**：**已定：不内嵌 tokenizer**（6.4MB 太重）。用「上一轮真实 `prompt_tokens` 作基线 + 新增内容的启发式增量」：`中文字符×0.6 + 其他字符×0.3`，UI 标注"预估"。因为每轮服务端都会回真实 `usage`，基线会不断自我校准，误差可控。
- **饼图**：两个环或一个环 + 数字（已用/上限、命中率）；右上角常驻
- **每轮统计条**：输入(命中/未命中)/输出/思考/搜索次数/费用，可在设置里开关
- **价格表**：内置 DeepSeek 当前价 + 峰值/空闲时段判断（北京时间、排除法定节假日→节假日需要一张表，建议只做"周末 + 时段"近似并标注），允许用户自定义（第三方服务商）

### 4.8 语音

> ⚠️ 2026-11-19 按用户对官方 App 的核对**改正**：原先写的「ASR 返回后填入输入框，不自动发送」是错的。
> 官方是：点麦克风 → 按钮变键盘图标、输入框位置变成居中的「按住说话」→ **按住录音** →
> **松手立即转写并直接发送**；没识别出内容就弹提示，不回填输入框。
> chat-demo（Web 版）是「点一下开始、再点结束、结果回填输入框」，**与官方 App 不同，别照抄交互**。

- 录音：`AudioRecord`，16kHz / 16bit / mono，写 WAV 头（44 字节），上限 120s 自动停止
- 频谱：对录音 PCM 做 512 点 FFT 取对数幅度，100–4000Hz **按对数分段**，竖条根数按可用宽度自适应
  （2dp 宽 + 3dp 间距，同 chat-demo），每倍频程 +4dB 倾斜补偿（人声高频天然衰减，否则右侧永远贴底）
- ASR：`POST /v1/tools/asr` 一次返回全文，**松手后直接发送**，不经过输入框
- TTS：`format=pcm` 边收边写 `AudioTrack`（24kHz / 16bit / mono），**不做整段缓冲**——
  2026-11-19 实测 570 字请求首字节 0.95s、17.8s 出完 108s 音频（约 6 倍速），够快了
- TTS 的 `wav` 响应里 `data` 长度是**流式占位**（0x7FFFFFFF），落文件后喂 `MediaPlayer` 会算成几万秒；
  所以走 `pcm` + `AudioTrack`。长文按句切分（单次上限 10000 计费字符，**汉字算 2 个**）
- 朗读要处理 audio focus：来电/切歌（`LOSS_TRANSIENT`）暂停并能续播，拔出耳机（`become noisy`）直接停

### 4.9 系统提示词：现状、自定义与预设

#### 4.9.1 官方到底发了什么提示词？（事实分级）

| 结论 | 可靠性 | 依据 |
|---|---|---|
| **官方演示客户端只发一句**：`system = "该助手为DeepSeek，由深度求索公司创造。"` | ✅ **最可靠（一手源码，MIT）** | [chat-demo `store.ts:38`](https://github.com/deepseek-ai/chat-demo/blob/main/src/services/chat/store.ts)，且全仓库仅此一处 system 字符串 |
| 联网搜索、引用、"深度思考"行为**不靠提示词**，靠服务端工具参数 | ✅ 可靠（官方文档） | `deepseek_web_search` / `deepseek_read_url` / `thinking` / `title` 都是请求参数，服务端自动完成 |
| App 端是否另有更长的提示词 | ❓ **无法确证** | 官方未公开；导出数据（v3）不含 system；只能逆向，outline 建议不做 |
| 网传"DeepSeek 系统提示词全文"（如 20 条规则的繁体版、V2/R1 英文版） | ❌ **不可信** | 内容与当前事实矛盾（称上下文 128K、知识截止 2024-07），文风与官方演示客户端差距极大，多为旧版或杜撰。**不要基于它们做设计** |

**设计含义（很关键）**：既然官方客户端用的是"一句话身份 + 服务端能力"，那么**"像不像官方"不取决于提示词长度，而取决于请求参数与渲染**。所以默认值必须**一字不改**地保持那句原文——这是复刻的基线。

#### 4.9.2 分层设计（v1：两层 + 只读快照）

```
① 内置默认（只读，不可删改）          = 官方那句原文
② 全局自定义（设置里选"新会话用哪一套"） = 内置预设 / 用户预设 / 自定义文本
③ 会话快照（只读）                    = 会话创建时固化 ②，此后不再变
```

生效规则：**新会话 = 当时的 ②**；**已有会话 = 它自己的 ③（只读）**。

**为什么不做"会话级可编辑覆盖"（已定）**：
1. 改 `system` 会让整段 KV Cache 失效，输入单价从 0.02 元/M 跳到 1 元/M（**50 倍**），会话中途改属于"不炸也炸穿钱包"；
2. 提示词入口只保留 `设置 → 应用 → 系统提示词` 一处，**会话页不加快捷入口**，减少误触；
3. 角色扮演用户的实际习惯是"一个角色一个新对话"，**新建对话**即可换人设，不需要改写旧对话。

> ⚠️ 已知取舍：用户无法把**已有**会话换成另一套提示词。若后续确有需求，v1.1 可加"在会话内重置人设（明确提示将重建缓存）"，成本很低——只需放开 `system_prompt_snapshot` 的写权限。

三个**组合模式**（解决"我想保留官方身份，只追加风格"）：

| 模式 | 发给 API 的 `system` | 适用 |
|---|---|---|
| 替换（默认） | `用户提示词` | 角色扮演 |
| 追加 | `官方句 + "\n\n" + 用户提示词` | 只想调风格/输出格式，仍要 DeepSeek 人设 |
| 清空 | 不传 `system` | 想让模型完全按上下文自由发挥（等价"无提示词"预设） |

#### 4.9.3 变量与预设

**变量白名单**（严格，未识别的 `{{xxx}}` 保留原文并高亮报错，不做静默替换）：

| 变量 | 值 | 说明 |
|---|---|---|
| `{{date}}` | `2026-11-17` | 本地时区 |
| `{{weekday}}` | `星期一` | 按界面语言 |
| `{{time}}` | `16:37` | 可选 |
| `{{location}}` | `新加坡` / 空 | 取设置里的所在地 |
| `{{language}}` | `中文` | 官方 70+ 语言取值表 |
| `{{model}}` | `deepseek-flash` | 当前模型 |

> ⚠️ 与 `latest_reminder` 的分工：日期/地点**优先**由 `latest_reminder` 提供（这是官方机制，且能提高缓存命中）。变量是给"关掉了元信息开关"或"非 DeepSeek 服务商"的用户兜底，两者不冲突但要提示"重复注入会多花 token"。

**内置预设（v1 定稿 5 个，全部"正当用途"）**：

| 预设 | 用途 | 备注 |
|---|---|---|
| 默认（官方） | 复刻基线 | **只读、不可改、不可删**，一字不改：`该助手为DeepSeek，由深度求索公司创造。` |
| 无提示词 | 自由度最高 | 空 `system`（等价"清空"组合模式） |
| 角色扮演骨架 | **中性骨架**：人设槽 + 世界观槽 + 说话风格槽 + 禁止事项槽 | 用 `{{...}}` 占位由用户填空；**不内置写好的成品角色**（内容责任留给用户） |
| 翻译/润色 | 只输出译文或改写结果 | 高频刚需；建议默认走"替换"模式 |
| 学习辅导 | 苏格拉底式提问、不直接给答案 | 家长/学生场景 |

> 📌 决策记录：原计划的「结构化输出」预设**不进 v1 预设列表**——该需求更适合用原生 JSON Output（见 §4.9.5 第 3 条）解决，而不是靠提示词堆格式约束。用户仍可自建同名预设。

**预设管理**：可新建/复制/改名/删除/排序；可导出导入为独立 JSON（**不放进 v3 归档**，见 4.9.4）；内置预设不可删，只能"复制后改"。

#### 4.9.4 与导出格式的冲突（**已实测的硬约束**）

我读了 chat-demo 的导入实现，结论很明确：

- **不能**在 ZIP 里加新条目（如 `tiny_chat_meta.json`）：导入循环对**任何未识别的条目路径**直接 `fail('backupIncomplete')`，**整包导入失败**；且要求 `manifest.json` 必须是**第一个**条目。
- **可以**在会话 JSON 里加新字段：`readSessionFile` 只挑已定义字段返回，**未知字段被静默丢弃**，不会报错。

因此导出策略：

| 模式 | 内容 | 风险 |
|---|---|---|
| **兼容模式（默认）** | 纯 v3，自定义提示词/模型/费用**不导出** | 无；但换机/重装会丢提示词设置 |
| **增强模式（可选，需勾选确认）** | v3 + 在 `session` JSON 内追加 `x_tinychat.system_prompt` 等字段 | 官方 App 若比 chat-demo 更严格会拒收；**必须在 UI 明示** |
| **完整备份（另一条路径）** | TinyChat 自有格式（含提示词、预设、用量、费用） | 与官方不互通，但数据完整 |

另外注意两个已确认的丢字段行为（会影响"往返一致"）：`pinned` 在 chat-demo 导入时**被强制置为 false**（`readSessionIndex` / `readSessionFile` 都硬编码 `pinned: false`）。→ 我们的导入要保留 `pinned`，但**导出给官方的包不要指望置顶能带过去**。

#### 4.9.5 三个容易踩的坑

1. **缓存击穿**：`system` 在 prompt 前缀里。会话进行到一半改提示词 → 之前缓存全失效，下一次请求按**未命中价**计（0.02 → 1 元/M，**50 倍**）。→ 已用"会话快照"策略规避（§4.9.2）；改会话级时仍要弹提示"本对话下次回复将重建缓存"。
2. **扩展模式吞参数 —— 「纯对话模式」v1 不做**：
   只要声明了 `deepseek_read_url` / `deepseek_web_search` / `safety_check:high` / `title` 中的任意一个，`temperature`、`top_p`、`stop_sequences` 就会被**服务端忽略**。而我们的默认配置为了对齐官方体验，**这四项全开** → 所以 `temperature` 实际上永远是 1.0。
   结论：**v1 不提供 `temperature` 开关，也不提供「纯对话模式」**。理由：① 官方 App 本身就没有这个设置，暴露它反而偏离"复刻"；② 为了调 temperature 而关掉联网+风控，代价远大于收益；③ 角色扮演的风格主要由**系统提示词**决定，实测足够。
   → 若要保留可能性：在 `Capabilities` 里加一个 `clientSamplingParams` 位，等真有用户抱怨"风格太平"再评估；第三方服务商（OpenAI 兼容）本来就可以按能力位显示。
3. **结构化输出别只靠提示词**：DeepSeek 两个模型都支持 JSON Output（Chat Completions 的 `response_format`）。Messages 协议下 JSON 模式与联网扩展能力的组合**未在文档中明确**，需实测。→ v1 用"提示词约束 + 代码块渲染"兜底（不内置预设），**V1.1 评估原生 JSON 开关**。

#### 4.9.6 合规红线

- 内置预设**只放正当用途**（人设、风格、格式、翻译、教学），**不放**越狱/绕过安全策略的模板。而且放了也没用——服务端 `safety_check:high` 会拦。
- README 与关于页声明：提示词由用户自负内容责任；分发预设不得用于生成违法内容。
- 若上 Google Play，"AI 生成内容"标识与内容审核责任仍需自行承担（官方文档亦如此要求）。

---

## 5. 主要风险与对策

| 风险 | 等级 | 对策 |
|---|---|---|
| **商标/品牌**：直接使用 DeepSeek 名称与鲸鱼 Logo 分发，可能构成商标侵权或"混淆来源"，Google Play 也会因"冒充"下架 | 🔴 高 | 名称用 TinyChat；图标用自绘（非鲸鱼）；保留配色/布局/字号（不受商标保护）；关于页声明"非官方，与 DeepSeek 无关"；APK 内不内置任何 Logo |
| **"服务商能力落差"导致用户困惑** | 🟠 中 | 每个 provider 声明 `Capabilities`；不可用功能置灰并给出原因（"当前服务商不支持服务端联网搜索"） |
| **联网工具按次计费**，用户对"为什么扣了钱"无感 | 🟠 中 | 用量可见化（每轮统计 + 每日汇总）；首次开启搜索时提示计费 |
| **流式 Markdown 掉帧** | 🟠 中 | 帧合并 + 增量解析 + 长消息分块折叠；必要时对超长消息降级为"纯文本流式，结束后再渲染" |
| **Mermaid/KaTeX 的 WebView 内存与高度** | 🟠 中 | WebView 池化、离屏转位图、`addJavascriptInterface` 回传高度 |
| **大文件上传/解析失败（120s 限制）** | 🟡 中低 | 上传前压缩图片、进度条、失败可重试；PDF 超过页数直接提示 |
| **导入 87MB 包 OOM / 卡顿** | 🟡 中低 | 流式解压 + 分批事务 + 后台线程 + 进度 UI |
| **Android 14+ 后台生成限制** | 🟡 中低 | 生成中启动 FGS（`dataSync` 类型）或在前台停止并明确提示；不承诺后台长跑 |
| **chat-demo 是移动靶** | 🟢 低 | 记录参考版本（commit）；逻辑自研为主 |
| **API 变更/价格调整** | 🟢 低 | 价格表远程可配 + 内置默认值 |
| **拒答语义**（`stop_reason: refusal`） | 🟢 低 | 状态单独记 `CONTENT_FILTER`：正文保留展示、该轮不进上下文（决策 85，原判断「必须丢弃」已作废） |

---

## 6. 分阶段计划

> 估算按 **1 人全职（5 人日/周）** 计，总计约 **67.4 人日 ≈ 13.5 周**（Spike 3 人日另计），含缓冲 **16 周**。若为业余投入（15h/周 ≈ 2 人日/周），则需约 34 周，此时应砍范围（详见 [02 §6](./02-dev-plan-and-ux-spec.md#6-任务拆解wbs)）。

| 阶段 | 内容 | 交付 | 估时 |
|---|---|---|---|
| **M0 骨架** | Gradle 工程（AGP + version catalog）、模块划分、设计 token 移植（色板/字号/形状）、图标移植、导航骨架、CI（build + lint） | 能跑的"空壳"App，双主题切换正确 | 1 周 |
| **M1 聊天内核** | Provider 配置、SSE 解析、碎片模型、Room、流式打字机、停止/重试、编辑重发、重新生成、分支 | 能用 DeepSeek Key 连续对话，行为与官方一致 | 2 周 |
| **M2 渲染** | Markdown/代码高亮/表格、KaTeX、Mermaid、引用角标、思维链折叠、流式节流 | 视觉效果对齐截图 | 2 周 |
| **M3 联网** | 搜索/打开网页/页内查找卡片、搜索结果底部面板、来源聚合（"22 个网页"） | 开启智能搜索后步骤展示完整 | 1 周 |
| **M4 附件** | 图片选择/拍照、文件选择、Files API 上传、解析态、失败重试、消息内文件卡 | 传图问答可用 | 1 周 |
| **M5 语音** | 录音 + 频谱 + ASR、TTS 朗读（2 音色 + 系统兜底） | 语音输入/朗读可用 | 1 周 |
| **M6 数据管理** | v3 导入导出（流式 ZIP + SAF）、清空、进度与回滚 | 3340 会话样本 100% 往返 | 1.5 周 |
| **M7 用量与费用** | 上下文圆环（点击出右侧用量抽屉）、每轮统计、计价与峰谷、余额、元信息开关，**外加凭据（Key 存储/校验/无 Key 引导，决策 23）** | 新增功能 4/5/6 | 1 周 |
| **M7.5 提示词** | 三层提示词（默认/全局/会话级 + 会话快照固化）、三种组合模式、**5 个内置预设**、预设 CRUD 与导入导出、变量校验、恢复默认 | 新增功能 7 | 0.5 周 |
| **M8 打磨** | 分享出图、i18n（中/英）、字号、设置页、引导页、OpenCode Go provider | 可内测 | 1 周 |
| **M9 发布** | 性能专项（长会话/大图/导入）、崩溃与错误文案、签名 APK、README/LICENSE/NOTICE | v1.0 | 1.5 周 |

关键路径：**M1 → M2**（内核与渲染决定 80% 的体感），建议先做纵向切片（M1+M2 的一个子集：单会话、无搜索、Markdown 基础）打通再铺开。

---

## 7. 建议的第一个可验证切片（Spike，3 天）— ✅ 已完成

在正式动工前，用 3 天做一个**丢弃型**验证工程，把风险最高的四件事先摸一遍（结果见 **04-spike-report.md**）：

1. ✅ OkHttp + 手写 SSE 解析 `/anthropic/v1/messages`，打印各事件（含 `thinking` / `server_tool_use` / `citations`）——离线固件 40/40 通过，在线待 Key；
2. ✅ Compose 里把 Markdown 流式渲染压测——**结论：只有 `rememberStreamingMarkdownState` 可用**（另两种写法每帧塌陷 2042dp）；
3. ✅ **行内公式与图表**——纯 Compose LaTeX 库依赖链断裂（Maven 404），**定稿 WebView + 离线 KaTeX/mermaid**；
4. ✅ 解压并解析 `deepseek_data-2026-11-16.zip`——1.07s / 43MB / 0 错误（限额 25s / 150MB）。

这四项通过 → 全案风险基本出清。

---

## 8. 决策清单

> **全部 13 项已于 2026-11-17 定稿。本文档后续内容以此为准。**

### 8.1 已拍板

| # | 议题 | 结论 |
|---|---|---|
| 1 | **品牌** | **TinyChat + 自绘图标**，不贴近官方；保留设计语言（色板/圆角/字号/布局） |
| 2 | **首版服务商** | **DeepSeek 官方 + 通用 OpenAI 兼容**（后者为降级文本能力）。OpenCode Go 推迟到 v1.1 |
| 3 | **TTS 音色** | **只用官方 2 个**（`stella`/`tide`）。不接系统 TTS、不接第三方 TTS |
| 4 | **Token 预估** | **启发式估算**（中文 ×0.6 / 其他 ×0.3）+"上一轮真实值校准"。**不内嵌 tokenizer** |
| 5 | **客户端侧搜索** | **v1.0 不做**。非 DeepSeek 服务商把搜索入口置灰并说明原因 |
| 6 | **发布渠道** | **GitHub Releases 直发 APK**。不上 Google Play |
| 7 | **导出默认档** | **兼容模式**（纯 v3，提示词/用量不随包走） |
| 8 | **提示词入口** | **只放 设置 → 应用 → 系统提示词**。会话页不加快捷入口 |
| 9 | 内置预设范围 | **5 个**：默认（官方）/ 无提示词 / 角色扮演骨架 / 翻译·润色 / 学习辅导。去掉「结构化输出」，交给 v1.1 原生 JSON Output |
| 10 | 全局提示词改动影响范围 | **不影响已有会话**。会话创建时快照生效提示词；避免击穿 KV Cache |
| 11 | 「纯对话模式」/ `temperature` | **v1 不做、不暴露**。官方 App 本无此设置；为调温度牺牲联网+风控不划算 |
| 12 | 角色扮演预设形态 | **中性骨架**（人设/世界观/风格/禁止事项四槽位 + `{{}}` 占位），不内置成品角色 |
| 13 | 恢复默认 | 一键恢复**官方原文**；该默认只读、不可编辑、不可删除 |

> 决策 14 及以后（回形针、无启动页、图标不再暂缓、M7 只做 DeepSeek 一家、上下文圆环落输入区、语音费用不计入对话费用等）逐条记在 [03 交接文档 §3](./03-handoff.md)。

### 8.2 由此推导出的实施约束（写代码前必读）

1. 品牌名 `TinyChat` 出现在：应用名、`applicationId`（建议 `io.github.<user>.tinychat`）、关于页；**任何界面不得出现 DeepSeek 鲸鱼 Logo**。
2. `Capabilities` 必须能表达"OpenAI 兼容服务商没有联网/文件/余额/TTS/ASR"，UI 据此置灰。
3. 会话表必须有 `system_prompt_snapshot` 列（提示词快照）与 `provider_id` / `model` 列。
4. 导出**只写官方 v3 定义内的条目与字段**，不追加任何自定义 ZIP 条目、不在 session JSON 里塞扩展字段。
5. 输入区默认不显示 `temperature` 等采样参数控件。
6. 用量显示统一走"真实值优先、预估兜底"两条路径，UI 上必须可区分（`预估` 角标）。

---

## 附录 A：API 速查

| 用途 | 方法与路径 |
|---|---|
| 对话（主链路） | `POST /anthropic/v1/messages`（`x-api-key` 或 `Bearer`） |
| 上传文件 | `POST /anthropic/v1/files`（需 `anthropic-beta: files-api-2025-04-14`；通用等价 `POST /files`） |
| 模型列表 | `GET /models` |
| 余额 | `GET /user/balance` |
| ASR | `POST /v1/tools/asr` |
| TTS | `POST /v1/tools/tts` / `wss://api.deepseek.com/v1/tools/tts` |
| 独立搜索 | `POST /search`（另有 `/fetch`、`/ocr`，按次计费，与工具同价） |
| Tokenizer | `https://cdn.deepseek.com/api-docs/deepseek_v4_tokenizer.zip`（LlamaTokenizerFast，`tokenizer.json` 6.4MB） |

## 附录 B：环境现状（本机已核对）

| 项 | 现状 |
|---|---|
| JDK | 21.0.4（Alibaba Dragonwell）✅ |
| Gradle | 系统 8.4（较旧，**建议用 wrapper 固定到 8.7+ / 9.x**，不依赖系统版） |
| Android SDK | `%LOCALAPPDATA%\Android\Sdk`，platforms **34 / 36**，build-tools 34.0.0 / 35.0.0 / 36.0.0 / 36.1.0 ✅ |
| 缺 | `ANDROID_HOME` 未设置（用 `local.properties` 指向 SDK 即可）；未确认 emulator 镜像 |

## 附录 C：参考资料

- [chat-demo（官方演示客户端，MIT）](https://github.com/deepseek-ai/chat-demo)
- [构建 Chatbot](https://api-docs.deepseek.com/zh-cn/guides/build_a_chatbot)
- [联网工具](https://api-docs.deepseek.com/zh-cn/guides/web_tools) / [思考模式](https://api-docs.deepseek.com/zh-cn/guides/thinking_mode) / [文件解析](https://api-docs.deepseek.com/zh-cn/guides/document_parsing)
- [用户元信息标记](https://api-docs.deepseek.com/zh-cn/guides/latest_reminder) / [会话标题](https://api-docs.deepseek.com/zh-cn/guides/conversation_title) / [模型内容安全](https://api-docs.deepseek.com/zh-cn/guides/safety)
- [TTS](https://api-docs.deepseek.com/zh-cn/guides/tts) / [ASR](https://api-docs.deepseek.com/zh-cn/guides/asr) / [Anthropic 兼容](https://api-docs.deepseek.com/zh-cn/guides/anthropic_api)
- [OpenCode Go](https://opencode.ai/docs/console/go) / [OpenCode ToS](https://opencode.ai/legal/terms-of-service)
- [DeepChat（Capacitor 套壳参考）](https://github.com/YanJiuWeiYuanHui/DeepChat)

---

## 附录 D：设计规格（从 chat-demo `theme.css` 提取）

> 这是 MIT 代码里的设计 token，可直接移植为 `Color.kt` / `Type.kt` / `Shape.kt`。**色值与字号是"视觉语言"，不是商标**，可放心使用。

### D.1 色板（浅色 / 深色）

| 语义 | 浅色 | 深色 |
|---|---|---|
| 背景 base | `#FFFFFF` | `rgb(21,21,23)` `#151517` |
| 背景 layer-2 / layer-3 | `#FFFFFF` | `#2C2C2E` / `#353638` |
| **用户气泡** | `rgb(237,243,254)` `#EDF3FE` | `rgb(44,44,46)` `#2C2C2E` |
| 文本 primary | `rgb(15,17,21)` `#0F1115` | `rgb(249,250,251)` `#F9FAFB` |
| 文本 secondary | `rgb(97,102,107)` `#61666B` | `rgb(207,211,214)` `#CFD3D6` |
| 文本 tertiary | `rgb(129,133,140)` `#81858C` | `rgb(173,178,184)` `#ADB2B8` |
| 文本 caption | `rgb(173,178,184)` `#ADB2B8` | `rgb(97,102,107)` `#61666B` |
| **品牌主色** | `rgb(57,100,254)` `#3964FE` | `rgb(86,134,254)` `#5686FE` |
| 边框 l1 / l2 / l3 | 黑 4% / 10% / 12% | 白 6% / 12% / 16% |
| 侧栏底 | `rgb(249,250,251)` `#F9FAFB` | `rgb(27,27,28)` `#1B1B1C` |
| 代码块底 | `rgb(249,250,251)` | `rgb(27,27,28)` |
| 行内代码 / 引用角标底 | `rgb(235,238,242)` `#EBEEF2` | `rgb(44,44,46)` |
| 危险/错误 | `rgb(236,19,19)` / `rgb(242,90,90)` | 同左两值 |
| Toast 底 | `rgb(53,54,56)` | `rgb(67,69,74)` |
| 交互 hover / active | 黑 6% / 10%（带蓝调 `rgba(38,49,72,…)`） | 白 8% / 14% |

### D.2 字体（Markdown 语义）

| 用途 | 规格 |
|---|---|
| h1 / h2 / h3 / h4 | 700 24/34 · 700 22/32 · 700 20/30 · 600 16/28 |
| 正文 | 400 16/28 |
| 正文加粗 | 600 16/28 |
| 表格 / 表头 | 15/25 · 500 15/25 |
| 小字 / 小字加粗 | 14/24 · 600 14/24 |
| 行内代码 | 14/22（等宽） |
| 代码块 | 13/22（等宽） |
| 控件辅助字 | 500 14/22、13/20 |

### D.3 圆角与尺寸

| 元素 | 值 |
|---|---|
| 用户气泡 | **22px** |
| 输入框 | **24px** |
| 输入框内按钮（pill） | 18px |
| 附件卡片 / 消息内文件卡 | 16px |
| 搜索/来源卡片、设置项、会话项、Toast | 12px |
| Modal | 24px |
| Tooltip | 10px |
| 代码块 | 8px |
| 侧栏"新对话"pill | 100px |
| 控件高度 | xl 44 / l 40 / m 36 / s 32 / xs 28 |
| 动效 | 0.2s（fast）/ 0.3s（slow），`cubic-bezier(0.4,0,0.2,1)` |

### D.4 系统提示词（影响"像不像"）

chat-demo 固定发送：

```
system = "该助手为DeepSeek，由深度求索公司创造。"
safety_check = { level: "high" }
tools = [deepseek_read_url] + (search ? [deepseek_web_search] : [])
title = { enabled: true }   // 仅会话首次请求
```

→ 建议做成**可配置**：DeepSeek 服务商默认用这句（体验最接近官方），其他服务商默认中性提示词。

---

## 附录 E：UI 元素清单（从 10 张截图提取）

用于 M0/M2 的界面拆解与验收。截图位于 `temp-doc/app-preview/`。

### E.1 首页 / 新会话
- 顶栏：左侧「两横」= 会话抽屉按钮；右侧：**声音外放开关**（喇叭划掉 = TTS 自动朗读关）、「⊕ 新对话」
- 中部：鲸鱼 logo + **按时段变化的问候语**（"下午好，我能帮什么忙吗？"；应为 上午/下午/晚上）
- 底部输入区（悬浮大圆角卡片）：
  - 首行占位文案 "发消息或按住说话"
  - 第二行：`深度思考` pill · `智能搜索` pill · `＋`（附件）· `(•))`（语音）
  - **改动点**：`深度思考` → 显示当前"模型 + 强度"；`智能搜索` 只留图标

### E.2 会话页
- 顶栏：抽屉按钮 · 会话标题（居中）· 声音开关 · 新对话
- 用户消息：右对齐、`#EDF3FE` 气泡、22px 圆角
- 助手消息：
  - 「已思考（用时 3 秒）›" —— 可展开，展开后是带左侧竖线的灰色思维链
  - 搜索步骤：「🔍 搜索到 26 个网页」+ 站点图标堆叠 + 可展开结果列表
  - 打开页面：「📄 已浏览 4 个页面」
  - 正文 Markdown：标题带 emoji、有序/无序列表、加粗
  - **引用角标**：正文中的小圆角数字（①②…），文末聚合为「🔍 22 个网页」胶囊
  - 底部："本回答由 AI 生成，内容仅供参考，请仔细甄别"
  - 操作栏：复制 · 👍 · 👎 · 🔊 朗读 · ↗ 分享 · 🔄 重新生成（最右）
- 流式时右下角有"回到底部"圆钮

### E.3 搜索结果面板（底部抽屉）
- 标题「搜索结果」+ 右上 `×`
- 每条：站点图标 + 站点名 + 日期（如 2023/10/24）+ 右上角序号
- 标题（粗）+ 两行摘要
- 底部「全部」按钮

### E.4 分享
- 全选态：左侧勾选圆点 + 「全选」
- 底部操作条：「已选 1 组」+ `×`，四个圆钮：生成图片 / 复制链接 / 分享到微信 / 更多分享

### E.5 设置（分组结构与顺序）
```
账户     账号管理（→ 改为「服务商与 API Key」）· 数据管理
应用     语言 · 外观 · 字体大小 · 个性化
语音     朗读音色
关于     检查更新 · 服务协议
        帮助与反馈
```
- 项样式：左图标 + 标题 + 右侧值 + `›`；分组标题为灰色小字；卡片 12px 圆角、白底、分组间距较大

### E.6 数据管理
```
数据用于优化体验（开关，本项目应删除）
我共享的链接（本项目的"分享记录"，可删）
导出所有历史对话
删除所有历史对话（红色）
```

