package io.tinychat.data.chat

import io.tinychat.data.attachment.AttachmentStore
import io.tinychat.data.attachment.AttachmentUploader
import io.tinychat.data.attachment.PickedFile
import io.tinychat.data.db.AttachmentEntity
import io.tinychat.data.db.MessageEntity
import io.tinychat.data.db.MessageWithFragments
import io.tinychat.data.db.SessionEntity
import io.tinychat.data.db.TinyChatDatabase
import io.tinychat.data.db.toDomain
import io.tinychat.data.db.toEntity
import io.tinychat.data.provider.ProviderStore
import io.tinychat.model.AttachmentState
import io.tinychat.model.Fragment
import io.tinychat.model.BranchNode
import io.tinychat.model.ErrorKind
import io.tinychat.model.FragmentPayloadCodec
import io.tinychat.model.KnownProviders
import io.tinychat.model.MessageStatus
import io.tinychat.model.MessageStatus.FINISHED
import io.tinychat.model.ModelInfo
import io.tinychat.model.Provider
import io.tinychat.model.ProviderProtocol
import io.tinychat.model.pickVersion
import io.tinychat.model.PromptMode
import io.tinychat.model.RequestPayload
import io.tinychat.model.ResponsePayload
import io.tinychat.model.Role
import io.tinychat.model.SessionSearchHit
import io.tinychat.model.ProviderMeta
import io.tinychat.model.ThinkingEffort
import io.tinychat.model.ThinkingMode
import io.tinychat.model.Usage
import io.tinychat.model.billing.CostCalculator
import io.tinychat.model.billing.PeakClock
import io.tinychat.model.billing.UsageSummary
import io.tinychat.data.prompt.PromptSnapshot
import io.tinychat.model.prompt.PromptMeta
import io.tinychat.model.prompt.PromptVariables
import java.time.Instant
import io.tinychat.model.buildSearchHit
import io.tinychat.net.context.ContextRebuilder
import io.tinychat.net.context.ContextTurn
import io.tinychat.net.context.HistoryAttachment
import io.tinychat.net.context.ImageFlavor
import io.tinychat.net.context.ReminderBuilder
import io.tinychat.net.protocol.ChatCommand
import io.tinychat.net.protocol.ChatProtocols
import io.tinychat.net.protocol.WireMessage
import io.tinychat.net.transport.ChatException
import io.tinychat.net.transport.ChatTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.UUID

/**
 * 聊天仓库：把「协议 + 传输 + 归并 + 落库」串起来（M1-5/M1-7/M1-10）。
 *
 * 上下文按 02 §5.2 的规则重建（见 [ContextRebuilder]）；
 * 本轮用户元信息按 §5.4 生成（见 [ReminderBuilder]）。
 */
class ChatRepository(
    private val db: TinyChatDatabase,
    private val transport: ChatTransport,
    private val clock: () -> Long = { System.currentTimeMillis() },
    /** 用户元信息开关（M7-5）：关掉后请求里不再带 `latest_reminder` */
    private val reminderEnabled: () -> Boolean = { true },
    /** 发请求前的连通性预检：离线就不必等 15s 连接超时，直接落失败让界面挂重试钮 */
    private val isOnline: () -> Boolean = { true },
    /** 附件落盘（M4）；只有带附件时才会用到 */
    private val attachmentStore: AttachmentStore,
    /** 附件上传（M4-3） */
    private val uploader: AttachmentUploader,
    /** 新会话的系统提示词快照（M7.5）；返回 null 表示「不发 system」 */
    private val promptSnapshot: suspend () -> PromptSnapshot = { PromptSnapshot(null, PromptMode.REPLACE) },
    /** 用户元信息，供 `{{location}}` / `{{language}}` 求值 */
    private val promptMeta: () -> PromptMeta = { PromptMeta() },
    /** 服务商配置（M8-6）：按会话快照的 providerId 取地址/协议/能力/模型 */
    private val providerStore: ProviderStore,
    /** 取某家服务商当前的 Key；未配置返回 null（上层在发送前就拦住了） */
    private val apiKeyFor: suspend (String) -> String?,
) {

    private val runner = ChatStreamRunner(db.messageDao(), db.fragmentDao(), clock)

    fun observeMessages(sessionId: String): Flow<List<MessageWithFragments>> =
        db.messageDao().observeWithFragments(sessionId)

    fun observeSessions(): Flow<List<SessionEntity>> = db.sessionDao().observeAll()

    /**
     * 会话用量合计（M7-2）：token 求和，费用优先用**落库时定格**的值。
     *
     * 决策 47：会话允许中途换服务商，所以「这一轮算不算钱」按**这一轮自己的服务商**判定，
     * 不再看会话当前是哪家。老数据（`provider_id` 与 `cost_micros` 都为空）仍按旧口径
     * 回落到会话服务商补算，保证升级前后数字不跳。
     */
    suspend fun usageSummary(sessionId: String): UsageSummary = withContext(Dispatchers.IO) {
        var prompt = 0
        var cacheHit = 0
        var completion = 0
        var thinking = 0
        var search = 0
        var fetch = 0
        var imageSearch = 0
        var cost = 0L
        val providers = providerStore.all().associateBy { it.id }
        val sessionProviderId = db.sessionDao().byId(sessionId)?.providerId
        db.messageDao().usageRows(sessionId).forEach { row ->
            val usage = Usage(
                promptTokens = row.promptTokens ?: 0,
                cacheHitTokens = row.cacheHitTokens ?: 0,
                completionTokens = row.completionTokens ?: 0,
                thinkingTokens = row.thinkingTokens ?: 0,
                searchCalls = row.searchCalls ?: 0,
                fetchCalls = row.fetchCalls ?: 0,
                imageSearchCalls = row.imageSearchCalls ?: 0,
            )
            if (usage.isEmpty) return@forEach
            prompt += usage.promptTokens
            cacheHit += usage.cacheHitTokens
            completion += usage.completionTokens
            thinking += usage.thinkingTokens
            search += usage.searchCalls
            fetch += usage.fetchCalls
            imageSearch += usage.imageSearchCalls
            cost += row.costMicros
                ?: legacyCost(row, usage, providers, sessionProviderId)
        }
        UsageSummary(
            usage = Usage(
                promptTokens = prompt,
                cacheHitTokens = cacheHit,
                completionTokens = completion,
                thinkingTokens = thinking,
                searchCalls = search,
                fetchCalls = fetch,
                imageSearchCalls = imageSearch,
            ),
            costMicros = cost,
        )
    }

    /** 升级前落库的消息没有定格费用：仍按「会话当前服务商是官方才算钱」的老口径补算。 */
    private fun legacyCost(
        row: io.tinychat.data.db.UsageRow,
        usage: Usage,
        providers: Map<String, Provider>,
        sessionProviderId: String?,
    ): Long {
        val provider = (row.providerId ?: sessionProviderId)?.let { providers[it] } ?: return 0
        if (provider.kind != io.tinychat.model.ProviderKind.DEEPSEEK_OFFICIAL) return 0
        val peak = PeakClock.isPeak(Instant.ofEpochMilli(row.insertedAt).atZone(PeakClock.ZONE))
        return CostCalculator.micros(usage, row.model ?: KnownProviders.flash.id, peak)
    }

    /**
     * 本轮费用（微元）：写入 `messages.cost_micros`，此后不再随价目表变化（决策 47）。
     *
     * 只给 DeepSeek 官方算——第三方没有单价表，写 0 等于宣称「免费」，比留空更糟；
     * 模型不在价目表里也留空，等以后补价再算。
     */
    private fun costFor(
        provider: Provider,
        modelId: String,
        usage: Usage?,
        atMillis: Long,
    ): Long? {
        if (usage == null || usage.isEmpty) return null
        if (provider.kind != io.tinychat.model.ProviderKind.DEEPSEEK_OFFICIAL) return null
        if (io.tinychat.model.billing.DefaultPricing.value.forModel(modelId) == null) return null
        val peak = PeakClock.isPeak(Instant.ofEpochMilli(atMillis).atZone(PeakClock.ZONE))
        return CostCalculator.micros(usage, modelId, peak)
    }


    /** 关掉开关时整条 reminder 消息都不发（M7-5） */
    /**
     * 本轮的用户元信息（02 §5.4）。所在地与语言取设置里的值（M7.5 起由 `PromptMeta` 提供）；
     * 关掉开关就整条不发。
     */
    private fun currentReminder(): String? {
        if (!reminderEnabled()) return null
        val meta = promptMeta()
        return ReminderBuilder.build(
            date = LocalDate.now(),
            location = meta.location.takeIf { it.isNotBlank() },
            language = meta.language,
        )
    }

    suspend fun session(sessionId: String): SessionEntity? = db.sessionDao().byId(sessionId)

    /** 冷启动恢复用：最近活跃的一条未删除会话（只看 `updated_at` 倒序，不受置顶影响）。 */
    suspend fun latestSession(): SessionEntity? = db.sessionDao().latest()

    suspend fun renameSession(sessionId: String, title: String?) {
        db.sessionDao().rename(sessionId, title?.trim()?.takeIf { it.isNotEmpty() }, clock())
    }

    suspend fun setSessionPinned(sessionId: String, pinned: Boolean) {
        db.sessionDao().setPinned(sessionId, pinned, clock())
    }

    /** 软删；消息与碎片留给 M6 的清理任务。附件的原件与缩略图当场删掉（02 §3.4）。 */
    suspend fun deleteSession(sessionId: String) {
        db.attachmentDao().bySession(sessionId).forEach { row ->
            runCatching { attachmentStore.delete(row.id, row.localPath) }
        }
        db.sessionDao().softDelete(sessionId, clock())
    }

    /**
     * M6-6：清空所有对话（含附件原件与缩略图）。
     *
     * 先删文件再删行：行没了就找不到 `local_path`，文件会变成永远清不掉的垃圾。
     * 一次查出全部附件（3340 会话时不逐会话查库）。
     */
    suspend fun clearAllSessions() {
        db.attachmentDao().all().forEach { row ->
            runCatching { attachmentStore.delete(row.id, row.localPath) }
        }
        db.sessionDao().deleteAll()
    }

    /**
     * 会话搜索：正文命中或标题命中都算，一条会话最多一条结果（正文命中优先）。
     *
     * 正文命中给「上下文 + 高亮」；只有标题命中时没有上下文，只回标题行。
     */
    suspend fun searchSessions(query: String, limit: Int = 30): List<SessionSearchHit> {
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()

        val hits = LinkedHashMap<String, SessionSearchHit>()

        db.fragmentDao().searchPayloads(needle).forEach { row ->
            if (hits.containsKey(row.sessionId)) return@forEach
            val content = when (val payload = FragmentPayloadCodec.decode(row.payload)) {
                is RequestPayload -> payload.content
                is ResponsePayload -> payload.content
                else -> null
            } ?: return@forEach
            buildSearchHit(row.sessionId, row.title, content, needle)?.let { hits[row.sessionId] = it }
        }

        // 只有标题命中的会话补一条（正文里找不到，就没有灰色上下文行）
        db.sessionDao().all()
            .filter { it.title?.contains(needle, ignoreCase = true) == true }
            .forEach { session -> hits.getOrPut(session.id) { titleHit(session.id, session.title, needle) } }

        return hits.values.take(limit)
    }

    private fun titleHit(sessionId: String, title: String?, query: String): SessionSearchHit {
        val titleText = title?.trim()?.takeIf { it.isNotEmpty() } ?: "新对话"
        val index = titleText.indexOf(query, ignoreCase = true)
        return if (index < 0) {
            SessionSearchHit(sessionId, titleText, "", "", "", matchedInTitle = true)
        } else {
            SessionSearchHit(
                sessionId = sessionId,
                title = titleText,
                before = titleText.substring(0, index),
                match = titleText.substring(index, index + query.length),
                after = titleText.substring(index + query.length),
                matchedInTitle = true,
            )
        }
    }

    /**
     * 建会话。系统提示词走**全局当前预设**（M7.5）：创建时取一次快照写进会话，
     * 之后用户在设置里怎么改都不影响这条会话（决策 10）。
     * 显式传入 `systemPrompt` 时以传入为准（导入/测试用）。
     */
    suspend fun createSession(
        providerId: String = KnownProviders.deepSeek.id,
        model: String = KnownProviders.flash.id,
        thinkingEnabled: Boolean = true,
        searchEnabled: Boolean = false,
        effort: ThinkingEffort? = ThinkingEffort.HIGH,
        systemPrompt: String? = null,
        systemMode: PromptMode = PromptMode.REPLACE,
        useCurrentPrompt: Boolean = true,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = clock()
        val snapshot = if (systemPrompt == null && useCurrentPrompt) {
            promptSnapshot()
        } else {
            PromptSnapshot(content = systemPrompt, mode = systemMode)
        }
        db.sessionDao().upsert(
            SessionEntity(
                id = id,
                createdAt = now,
                updatedAt = now,
                providerId = providerId,
                model = model,
                thinkingEnabled = thinkingEnabled,
                searchEnabled = searchEnabled,
                effort = effort,
                systemPromptSnapshot = snapshot.content,
                systemPromptMode = snapshot.mode,
            ),
        )
        return id
    }

    /** 启动时把上次被杀进程留下的 WIP 归为 INCOMPLETE（02 §4.6） */
    suspend fun recoverInterrupted() {
        db.messageDao().recoverWipAll()
    }

    /**
     * 发送一条用户消息并流式接收回复。**取消即停止**：
     * 上层 cancel 协程 → 传输层断流 → 状态落 INCOMPLETE、已生成内容保留。
     */
    /** 底栏切模型/强度/搜索：只改会话快照，不影响已有消息 */
    suspend fun updateSettings(
        sessionId: String,
        providerId: String,
        thinkingEnabled: Boolean,
        searchEnabled: Boolean,
        effort: ThinkingEffort?,
        model: String,
    ) {
        db.sessionDao().updateSettings(
            id = sessionId,
            providerId = providerId,
            thinking = thinkingEnabled,
            search = searchEnabled,
            effort = effort,
            model = model,
            now = clock(),
        )
    }


    fun observeSession(sessionId: String): Flow<SessionEntity?> = db.sessionDao().observeById(sessionId)

    suspend fun send(
        sessionId: String,
        text: String,
        attachments: List<PickedFile> = emptyList(),
        /** 实时出字（决策 74）；`null` = 不要 */
        onLive: LiveSink? = null,
    ): RunOutcome {
        val session = db.sessionDao().byId(sessionId) ?: error("会话不存在：$sessionId")
        val parentId = session.currentMessageId
            ?: db.messageDao().withFragments(sessionId).lastOrNull()?.message?.id
        return sendInternal(session, parentId, text, attachments, onLive)
    }

    /**
     * 编辑重发（02 §5.1）：在原消息的**父节点**下新建一条 USER 兄弟。
     * 旧分支留在库里（M1-8 的版本切换要用），当前分支改走新的。
     */
    suspend fun editMessage(
        sessionId: String,
        messageId: Long,
        text: String,
        onLive: LiveSink? = null,
    ): RunOutcome {
        val session = db.sessionDao().byId(sessionId) ?: error("会话不存在：$sessionId")
        val original = db.messageDao().byId(messageId) ?: error("消息不存在：$messageId")
        return sendInternal(session, original.parentId, text, onLive = onLive)
    }

    /**
     * 重新生成（M1-8）：在同一条提问下再挂一条 ASSISTANT 兄弟，旧回答留着当版本。
     *
     * 不新建用户消息，上下文回溯到那条提问为止，再按原样把提问追加回请求。
     */
    suspend fun regenerate(
        sessionId: String,
        assistantMessageId: Long,
        onLive: LiveSink? = null,
    ): RunOutcome {
        val session = db.sessionDao().byId(sessionId) ?: error("会话不存在：$sessionId")
        val original = db.messageDao().byId(assistantMessageId) ?: error("消息不存在：$assistantMessageId")
        val userMessageId = original.parentId ?: error("未找到对应的提问，无法重新生成")
        val question = db.messageDao().byId(userMessageId) ?: error("找不到对应的提问：$userMessageId")

        val userText = db.fragmentDao().byMessage(userMessageId)
            .mapNotNull { (FragmentPayloadCodec.decode(it.payload) as? RequestPayload)?.content }
            .joinToString("")
        // 只发了图片/文件、没写文字的那一轮也可以重新生成
        if (userText.isBlank() && db.attachmentDao().byMessage(userMessageId).isEmpty()) {
            error("对应的提问为空，无法重新生成")
        }

        return streamAssistant(
            session = session,
            userMessageId = userMessageId,
            userText = userText,
            // 沿用当时那条提问的元信息快照，避免上下文里凭空多出一条新的 reminder
            reminder = question.reminderSnapshot ?: currentReminder(),
            onLive = onLive,
        )
    }

    /**
     * 切换版本（M1-8）：把当前分支指针移到 [messageId] 的相邻兄弟分支，落到那条分支的叶子上。
     *
     * @return 新的叶子消息 id；只有一版时返回 null
     */
    suspend fun switchVersion(sessionId: String, messageId: Long, delta: Int): Long? {
        val nodes = db.messageDao().withFragments(sessionId).map {
            BranchNode(it.message.id, it.message.parentId, it.message.insertedAt)
        }
        val leaf = pickVersion(nodes, messageId, delta) ?: return null
        db.sessionDao().setCurrentMessage(sessionId, leaf.id, clock())
        return leaf.id
    }

    /**
     * 继续生成（M1-9）：把已停止/截断的回答接着写下去。
     *
     * 做法是把「提问 + 已生成的部分正文」作为 assistant 预填发过去，模型从断点续写；
     * 新内容仍落在同一条消息上（`ord` 递增追加），所以界面上是原地接续。
     *
     * 三个前置条件（02 §5.1）任一不满足就抛错，按钮本身在 UI 侧已经置灰：
     * 1. 该轮没调用过服务端工具（服务端会 400）；
     * 2. 状态是 INCOMPLETE（用户停止 / max_tokens / 中断）；
     * 3. 不含模板块、正文非空。
     *
     * 预填属于 assistant 末尾消息，按 Anthropic 规矩**不能**同时开思考，因此这一轮关掉思考。
     */
    suspend fun continueGeneration(
        sessionId: String,
        assistantMessageId: Long,
        onLive: LiveSink? = null,
    ): RunOutcome {
        val session = db.sessionDao().byId(sessionId) ?: error("会话不存在：$sessionId")
        val target = db.messageDao().byId(assistantMessageId) ?: error("消息不存在：$assistantMessageId")
        require(target.status == MessageStatus.INCOMPLETE) { "这条回答已完整，无需继续生成" }

        val userMessageId = target.parentId ?: error("未找到对应的提问，无法继续生成")
        val question = db.messageDao().byId(userMessageId) ?: error("找不到对应的提问：$userMessageId")
        val fragments = db.fragmentDao().byMessage(assistantMessageId)

        require(fragments.none { it.type.startsWith("TOOL_") }) { "联网搜索结果不支持继续生成" }
        require(fragments.none { it.type == "TEMPLATE_RESPONSE" }) { "预设模板回复不支持继续生成" }

        val partial = fragments
            .filter { it.type == "RESPONSE" }
            .mapNotNull { (FragmentPayloadCodec.decode(it.payload) as? ResponsePayload)?.content }
            .joinToString("")
        require(partial.isNotBlank()) { "当前没有可继续生成的内容" }

        val userText = db.fragmentDao().byMessage(userMessageId)
            .mapNotNull { (FragmentPayloadCodec.decode(it.payload) as? RequestPayload)?.content }
            .joinToString("")

        val provider = requireProvider(session)
        require(ChatProtocols.supportsContinuation(provider.protocol)) {
            "当前服务商不支持继续生成"
        }
        val modelInfo = requireModel(provider, session)
        val key = requireKey(provider)
        // 继续生成只有 Messages 协议支持（预填充），所以图片仍走官方 Files API 那条形状
        val history = chainTurns(sessionId, question.parentId, ImageFlavor.DEEPSEEK)
        val reminder = question.reminderSnapshot ?: currentReminder()

        val command = ChatCommand(
            capabilities = modelInfo.capabilities,
            model = session.model,
            systemPrompt = systemFor(session, modelInfo),
            systemMode = session.systemPromptMode,
            thinkingEnabled = false,
            effort = session.effort,
            thinking = modelInfo.thinking,
            maxOutputTokens = modelInfo.maxOutputTokens,
            searchEnabled = false,
            includeTitle = false,
            messages = ContextRebuilder.build(history, reminder, ImageFlavor.DEEPSEEK) +
                ContextRebuilder.userMessage(
                    userText,
                    attachmentsOf(question.id, ImageFlavor.DEEPSEEK),
                    ImageFlavor.DEEPSEEK,
                ) +
                WireMessage.text(WireMessage.ROLE_ASSISTANT, partial),
        )

        val request = ChatProtocols.build(
            provider = provider,
            apiKey = key,
            command = command,
            extraHeaders = ChatProtocols.extraHeaders(provider, sessionId),
        )

        db.messageDao().setStatus(assistantMessageId, MessageStatus.WIP)
        val outcome = try {
            runner.run(
                assistantMessageId,
                request,
                transport,
                ChatProtocols.assembler(request.protocol),
                // 续写是接在这段后面的：实时快照要从已有正文起算，否则界面会先短一截
                baseText = partial,
                onLive = onLive,
            )
        } catch (e: Throwable) {
            // 失败要把状态退回「未完成」，否则界面一直显示生成中，还挡住下一次继续
            runCatching { db.messageDao().setStatus(assistantMessageId, MessageStatus.INCOMPLETE) }
            throw e
        }
        // 续写同样定格费用（决策 47）：一次续写是一笔独立的账
        val insertedAt = db.messageDao().byId(assistantMessageId)?.insertedAt ?: clock()
        costFor(provider, session.model, outcome.usage, insertedAt)?.let { micros ->
            runCatching { db.messageDao().setCost(assistantMessageId, micros) }
        }
        db.sessionDao().touch(sessionId, clock())
        return outcome
    }

    private suspend fun sendInternal(
        session: SessionEntity,
        parentId: Long?,
        text: String,
        attachments: List<PickedFile> = emptyList(),
        onLive: LiveSink? = null,
    ): RunOutcome {
        val sessionId = session.id
        val now = clock()

        // 本轮的用户元信息（02 §5.4）；地点暂未接设置项，留空但保留逗号
        val reminder = currentReminder()

        val userId = db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                parentId = parentId,
                role = Role.USER,
                status = MessageStatus.FINISHED,
                thinkingEnabled = session.thinkingEnabled,
                searchEnabled = session.searchEnabled,
                insertedAt = now,
                providerId = session.providerId,
                reminderSnapshot = reminder,
            ),
        )
        // 只有图片/文件、没有文字的那一轮**不写 REQUEST 碎片**（真实样本就是这样）
        if (text.isNotBlank()) {
            db.fragmentDao().insert(Fragment(messageId = userId, ord = 0, payload = RequestPayload(text)).toEntity())
        }

        if (attachments.isNotEmpty()) {
            // 先把指针挪到这条提问上：上传失败时它才是叶子，重试才能接在同一条上
            db.sessionDao().setCurrentMessage(sessionId, userId, clock())
            persistAttachments(userId, attachments)
            uploadPending(userId)
        }

        return streamAssistant(
            session = session,
            userMessageId = userId,
            userText = text,
            reminder = reminder,
            assistantInsertedAt = now + 1,
            onLive = onLive,
        )
    }

    /** 原件拷进 `attachments/{messageId}/`，同时落一条 LOCAL 记录（图片带缩略图与宽高） */
    private suspend fun persistAttachments(userMessageId: Long, picked: List<PickedFile>) {
        picked.forEach { file ->
            val saved = attachmentStore.save(userMessageId, file)
            db.attachmentDao().upsert(
                AttachmentEntity(
                    id = file.id,
                    messageId = userMessageId,
                    fileName = file.fileName,
                    fileSize = saved.size,
                    mime = file.mime,
                    localPath = saved.path,
                    isImage = file.isImage,
                    width = file.width,
                    height = file.height,
                    state = AttachmentState.LOCAL,
                ),
            )
        }
    }

    /**
     * M4-3：把一轮里还没有 `file_id` 的附件传掉。
     *
     * 状态先落库再上传，界面直接读库画「上传中 / 失败」；失败立刻抛，调用方负责提示与重试。
     */
    suspend fun uploadPending(userMessageId: Long) {
        val message = db.messageDao().byId(userMessageId) ?: return
        val session = db.sessionDao().byId(message.sessionId) ?: return
        // 附件只有官方 Files API 支持；其他服务商在 UI 侧就没有添加入口，这里防御性跳过
        val provider = providerStore.byId(session.providerId) ?: return
        if (provider.kind != io.tinychat.model.ProviderKind.DEEPSEEK_OFFICIAL) return
        val key = apiKeyFor(provider.id) ?: return
        db.attachmentDao().byMessage(userMessageId).forEach { row ->
            if (!row.remoteId.isNullOrBlank()) return@forEach
            val path = row.localPath
            if (path == null || !attachmentStore.exists(path)) {
                db.attachmentDao().setState(row.id, AttachmentState.FAILED)
                throw ChatException(ErrorKind.UPLOAD, message = "附件文件已丢失或被移动，请重新上传")
            }
            db.attachmentDao().setState(row.id, AttachmentState.UPLOADING)
            try {
                val remoteId = uploader.upload(key, path, row.fileName, row.mime, row.isImage)
                db.attachmentDao().setRemote(row.id, remoteId, AttachmentState.UPLOADED)
            } catch (e: Throwable) {
                db.attachmentDao().setState(row.id, AttachmentState.FAILED)
                throw e
            }
        }
    }

    /**
     * M4-3 失败重试：附件重传 + 重新请求，**复用同一条提问**，不新建用户消息。
     */
    suspend fun retryTurn(sessionId: String, userMessageId: Long, onLive: LiveSink? = null): RunOutcome {
        val session = db.sessionDao().byId(sessionId) ?: error("会话不存在：$sessionId")
        val question = db.messageDao().byId(userMessageId) ?: error("消息不存在：$userMessageId")

        val userText = db.fragmentDao().byMessage(userMessageId)
            .mapNotNull { (FragmentPayloadCodec.decode(it.payload) as? RequestPayload)?.content }
            .joinToString("")

        uploadPending(userMessageId)
        return streamAssistant(
            session = session,
            userMessageId = userMessageId,
            userText = userText,
            reminder = question.reminderSnapshot
                ?: currentReminder(),
            onLive = onLive,
        )
    }

    /**
     * 这一轮图片走哪种形状（决策 53）：只有 OpenAI 兼容 + 模型声明了图片输入才内联 base64；
     * 官方仍走 Files API；Anthropic 兼容与不支持视觉的模型一律不带图（历史里的图会被丢弃）。
     */
    private fun imageFlavor(provider: Provider, model: ModelInfo): ImageFlavor =
        if (provider.protocol == ProviderProtocol.OPENAI_CHAT && model.capabilities.imageInput) {
            ImageFlavor.OPENAI
        } else {
            ImageFlavor.DEEPSEEK
        }

    /**
     * 本轮提问的附件。
     *
     * 官方链路只带 `remoteId`（Files API 的 file_id）；第三方视觉把本地原件压成 data URI。
     * 压缩失败时给 null —— 当前这一轮会退化成纯文字，而不是悄悄发一张空图。
     */
    private suspend fun attachmentsOf(
        messageId: Long,
        flavor: ImageFlavor,
    ): List<HistoryAttachment> =
        db.attachmentDao().byMessage(messageId).map { row ->
            val dataUri = if (flavor == ImageFlavor.OPENAI && row.isImage) visionDataUri(row) else null
            // 本轮刚落盘的图片压不出来：明确报错，别让这一轮悄悄发成纯文字
            if (flavor == ImageFlavor.OPENAI && row.isImage && dataUri == null) {
                throw ChatException(ErrorKind.UPLOAD, message = "图片处理失败，请重新选择这张图")
            }
            HistoryAttachment(
                remoteId = row.remoteId,
                isImage = row.isImage,
                dataUri = dataUri,
            )
        }

    /** 本地图片 → `data:image/jpeg;base64,…`；文件不存在或解码失败返回 null */
    private fun visionDataUri(row: AttachmentEntity): String? =
        row.localPath
            ?.takeIf { attachmentStore.exists(it) }
            ?.let { attachmentStore.visionDataUri(it) }

    /**
     * 在一条**已存在的**提问下流式生成回答：发送与重新生成共用这一段。
     */
    private suspend fun streamAssistant(
        session: SessionEntity,
        userMessageId: Long,
        userText: String,
        reminder: String?,
        assistantInsertedAt: Long = clock(),
        onLive: LiveSink? = null,
    ): RunOutcome {
        val sessionId = session.id
        val provider = requireProvider(session)
        val modelInfo = requireModel(provider, session)
        val key = requireKey(provider)

        val assistantId = db.messageDao().insert(
            MessageEntity(
                sessionId = sessionId,
                parentId = userMessageId,
                role = Role.ASSISTANT,
                status = MessageStatus.WIP,
                thinkingEnabled = session.thinkingEnabled,
                searchEnabled = session.searchEnabled,
                insertedAt = assistantInsertedAt,
                model = session.model,
                providerId = provider.id,
                effort = session.effort,
            ),
        )
        db.sessionDao().setCurrentMessage(sessionId, assistantId, clock())

        // 离线预检：没网就别等连接超时了，立刻落失败（界面显示橘色重试圆钮）
        if (!isOnline()) {
            val offline = ChatException(ErrorKind.NETWORK, message = "当前无网络连接，请检查网络后重试")
            db.messageDao().setStatus(assistantId, MessageStatus.FAILED)
            db.messageDao().setError(assistantId, offline.kind, offline.message)
            db.sessionDao().touch(sessionId, clock())
            return RunOutcome(
                messageId = assistantId,
                status = MessageStatus.FAILED,
                usage = null,
                stopReason = null,
                title = null,
                error = offline,
            )
        }

        // 上下文只取**当前分支**：到这条提问的父节点为止，再追加本次提问
        val question = db.messageDao().byId(userMessageId)
        val flavor = imageFlavor(provider, modelInfo)
        val history = chainTurns(sessionId, question?.parentId, flavor)

        // 这家服务商之前拒绝过思考参数：记住后不再发（模型声明还留着，用户改协议再说）
        val effortDisabled = provider.meta[ProviderMeta.NO_EFFORT] == "1"
        val command = ChatCommand(
            capabilities = if (effortDisabled) modelInfo.capabilities.copy(effort = false) else modelInfo.capabilities,
            model = session.model,
            systemPrompt = systemFor(session, modelInfo),
            systemMode = session.systemPromptMode,
            thinkingEnabled = session.thinkingEnabled,
            effort = session.effort,
            thinking = if (effortDisabled) ThinkingMode.NONE else modelInfo.thinking,
            maxOutputTokens = modelInfo.maxOutputTokens,
            searchEnabled = session.searchEnabled,
            // 服务端标题只有官方支持；第三方不可能有，开着也只是白填字段
            includeTitle = session.title == null && modelInfo.capabilities.serverSideTitle,
            messages = ContextRebuilder.build(history, reminder, flavor) +
                ContextRebuilder.userMessage(userText, attachmentsOf(userMessageId, flavor), flavor),
        )

        val request = ChatProtocols.build(
            provider = provider,
            apiKey = key,
            command = command,
            extraHeaders = ChatProtocols.extraHeaders(provider, sessionId),
        )

        val outcome = runner.run(
            assistantId,
            request,
            transport,
            ChatProtocols.assembler(request.protocol),
            onLive = onLive,
        )
        val finalOutcome = retryWithoutThinking(provider, session, command, key, assistantId, outcome, onLive)

        // 费用定格（决策 47）：用这一轮自己的服务商与落库时间算一次写死，之后换家、调价都不改历史账
        costFor(provider, session.model, finalOutcome.usage, assistantInsertedAt)?.let { micros ->
            runCatching { db.messageDao().setCost(assistantId, micros) }
        }

        // 标题：官方用服务端下发的；第三方本地取首句前 24 字（02 §5.3 的等价实现）
        if (session.title == null) {
            val title = finalOutcome.title ?: userText
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.let(::localTitle)
            if (title != null && finalOutcome.status == FINISHED) {
                db.sessionDao().rename(sessionId, title, clock())
            }
        }
        db.sessionDao().touch(sessionId, clock())
        return finalOutcome
    }

    /**
     * 思考参数不被支持时的自愈（M8-1 收尾）。
     *
     * 第三方网关对 `reasoning_effort` / `thinking` 的态度分三种：支持、静默忽略、直接 400。
     * 我们在「首帧之前 400 且报文点了参数名」时去掉该参数重发一次，并把这家记住
     * （`meta.no_effort=1`），避免每次都撞一遍。
     */
    private suspend fun retryWithoutThinking(
        provider: Provider,
        session: SessionEntity,
        command: ChatCommand,
        key: String,
        assistantId: Long,
        outcome: RunOutcome,
        onLive: LiveSink? = null,
    ): RunOutcome {
        val error = outcome.error ?: return outcome
        if (outcome.status != MessageStatus.FAILED) return outcome
        if (error.httpStatus != 400) return outcome
        if (command.thinking == ThinkingMode.NONE) return outcome
        if (provider.meta[ProviderMeta.NO_EFFORT] == "1") return outcome
        val body = error.message.orEmpty()
        val mentions = listOf("reasoning_effort", "thinking", "budget_tokens")
            .any { body.contains(it, ignoreCase = true) }
        if (!mentions) return outcome

        providerStore.setMeta(provider.id, ProviderMeta.NO_EFFORT, "1")
        val retryCommand = command.copy(
            capabilities = command.capabilities.copy(effort = false),
            thinking = ThinkingMode.NONE,
        )
        val retry = ChatProtocols.build(
            provider = provider,
            apiKey = key,
            command = retryCommand,
            extraHeaders = ChatProtocols.extraHeaders(provider, session.id),
        )
        return runner.run(
            assistantId,
            retry,
            transport,
            ChatProtocols.assembler(retry.protocol),
            onLive = onLive,
        )
    }

    /** 本地标题：压平换行后截 24 字，超出加省略号（与官方服务端标题长度相当）。 */
    private fun localTitle(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length <= TITLE_MAX) flat else flat.take(TITLE_MAX).trimEnd() + "…"
    }

    private suspend fun requireProvider(session: SessionEntity): Provider =
        providerStore.byId(session.providerId)
            ?: throw ProviderMissingException(session.providerId)

    private fun requireModel(provider: Provider, session: SessionEntity): ModelInfo =
        provider.model(session.model)
            ?: provider.defaultModel
            ?: error("服务商「${provider.displayName}」暂无可用模型")

    private suspend fun requireKey(provider: Provider): String =
        apiKeyFor(provider.id)?.takeIf { it.isNotBlank() }
            ?: throw MissingKeyException(provider.displayName)

    /**
     * 会话快照 → 本轮真正要发的 system。
     *
     * 变量在**发送时**求值：日期、星期、时间必须实时，所以库里存的是原文；
     * 不在白名单里的变量原样保留（设置页负责报错，这里不做静默吞掉）。
     */
    private fun systemFor(session: SessionEntity, modelInfo: ModelInfo): String? {
        val raw = session.systemPromptSnapshot ?: return null
        val meta = promptMeta()
        return PromptVariables.evaluate(
            text = raw,
            now = ZonedDateTime.now(PeakClock.ZONE),
            location = meta.location,
            language = meta.language,
            model = modelInfo.displayName,
        )
    }

    /** 从 leaf 往上回溯成 root → leaf 的顺序 */
    private suspend fun chainTurns(
        sessionId: String,
        leafId: Long?,
        flavor: ImageFlavor,
    ): List<ContextTurn> {
        if (leafId == null) return emptyList()
        val all = db.messageDao().withFragments(sessionId).associateBy { it.message.id }
        val path = ArrayDeque<MessageWithFragments>()
        var cursor: Long? = leafId
        val guard = HashSet<Long>()
        while (cursor != null && guard.add(cursor)) {
            val node = all[cursor] ?: break
            path.addFirst(node)
            cursor = node.message.parentId
        }
        return path.map { entry ->
            ContextTurn(
                message = entry.message.toDomain(),
                fragments = entry.orderedFragments.map { it.toDomain() },
                attachments = entry.attachments.map { row ->
                    HistoryAttachment(
                        remoteId = row.remoteId,
                        isImage = row.isImage,
                        // 第三方视觉：历史里的图每一轮都要重发，这里顺带把 data URI 压好
                        dataUri = if (flavor == ImageFlavor.OPENAI && row.isImage) {
                            visionDataUri(row)
                        } else {
                            null
                        },
                    )
                },
            )
        }
    }

    private companion object {
        /** 本地标题长度：官方服务端标题一般 10–20 字，24 字够用（M8-1） */
        const val TITLE_MAX = 24
    }
}

/** 会话指向的服务商已被删除：历史可读，但不能继续对话（M8-6）。 */
class ProviderMissingException(val providerId: String) :
    IllegalStateException("当前会话使用的服务商已被移除")

/** 该服务商还没填 Key（UI 在发送前就该拦住，这里是最后一道防线）。 */
class MissingKeyException(val providerName: String) :
    IllegalStateException("尚未配置 $providerName 的 API Key")
