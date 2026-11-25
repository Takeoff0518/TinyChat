package io.tinychat.feature.chat

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import io.tinychat.model.ThinkingEffort

@Composable
internal fun localizedThinkingEffort(effort: ThinkingEffort): String =
    localizedThinkingEffort(LocalResources.current, effort)

/**
 * 非 Compose 场景（独立窗口里的浮层）用这个重载：显式传 Resources。
 *
 * 浮层是自己的窗口，在里面调 `stringResource` 会按窗口的资源解析，实测切成日语后
 * 仍然出英文；所以调用方在**主窗口**取一次 Resources 传进来。
 */
internal fun localizedThinkingEffort(res: Resources, effort: ThinkingEffort): String = res.getString(
    when (effort) {
        ThinkingEffort.LOW -> R.string.chat_low
        ThinkingEffort.HIGH -> R.string.chat_high
        ThinkingEffort.MAX -> R.string.chat_max
    },
)

@Composable
internal fun localizedPanelTitle(step: ToolStep): String = when (step.kind) {
    ToolKind.OPEN -> stringResource(R.string.chat_opened_pages)
    ToolKind.SEARCH, ToolKind.FIND -> stringResource(R.string.chat_search_results)
}

@Composable
internal fun localizedToolTitle(step: ToolStep): String = when (step.kind) {
    ToolKind.OPEN -> if (step.failed) {
        stringResource(R.string.chat_couldn_t_open_the_page)
    } else {
        stringResource(R.string.chat_pages_opened, step.entries.size)
    }
    ToolKind.SEARCH -> when {
        step.failed && !step.searchFailure.isNullOrBlank() -> stringResource(R.string.chat_web_search_unavailable, step.searchFailure)
        step.failed -> stringResource(R.string.chat_web_search_is_unavailable)
        step.entries.isEmpty() -> stringResource(R.string.chat_no_relevant_results)
        else -> stringResource(R.string.chat_search_result_count, step.entries.size)
    }
    ToolKind.FIND -> if (step.failed) {
        stringResource(R.string.chat_no_matches)
    } else {
        stringResource(R.string.chat_looked_for, step.findPattern.orEmpty())
    }
}

private fun chatStatusResource(message: String): Int? = when (message) {
    "上下文压缩失败，已保留原模型" -> R.string.chat_context_compression_failed
    "已达到单次录音时长上限" -> R.string.chat_recording_time_limit_reached
    "打不开选中的文件" -> R.string.chat_couldn_t_open_the_selected_file
    "当前模型不支持图片输入" -> R.string.chat_this_model_doesn_t_accept_images
    "当前模型不支持文件输入" -> R.string.chat_this_model_doesn_t_accept_files
    "请在模型设置中启用 PDF 文件输入" -> R.string.chat_enable_pdf_uploads_in_the_model_settings
    "支持 PDF、DOCX、XLSX、PPTX 和纯文本" -> R.string.chat_supported_document_types
    "此 Android 版本无法在本机读取 PDF，请转为文本或使用支持 PDF 的模型" -> R.string.chat_pdf_local_read_unsupported
    "无法从 PDF 提取文字。请先做文字识别，或使用支持 PDF 的模型" -> R.string.chat_pdf_no_readable_text
    "无法读取 Office 文件中的文字" -> R.string.chat_office_text_read_failed
    "附件可提取文字过长" -> R.string.chat_document_text_too_long
    "无法读取 PDF 文件" -> R.string.chat_pdf_read_failed
    "本机原件已丢失" -> R.string.chat_attachment_original_missing
    "附件文件已丢失或被移动，请重新上传" -> R.string.chat_attachment_file_missing
    "无法打开该文件，请重新选择" -> R.string.chat_attachment_reselect
    "无法读取附件，请检查文件是否完整" -> R.string.chat_attachment_corrupt
    "无法处理这张图片" -> R.string.chat_attachment_image_unreadable
    "需要非空的 UTF-8 或 UTF-16 文本" -> R.string.chat_attachment_text_encoding
    "本轮内联附件总大小超过 16 MiB" -> R.string.chat_attachment_combined_size
    "文件上传失败，请重试" -> R.string.chat_couldn_t_upload_the_file_try_again
    "当前会话使用的服务商已被移除，请新建会话" -> R.string.chat_this_chat_s_provider_was_removed_start
    "尚未配置当前服务商的 API Key" -> R.string.chat_set_an_api_key_for_this_provider
    "生成失败，请重试" -> R.string.chat_couldn_t_generate_a_response_try_again
    "请在设置的「联网搜索」中配置可用的搜索服务" -> R.string.chat_choose_a_search_provider_in_web_search
    "无法使用麦克风，请检查权限后重试" -> R.string.chat_check_microphone_permissions_and_try_again
    "无法开始录音，请稍后再试" -> R.string.chat_couldn_t_start_recording_try_again_later
    "未识别到语音内容" -> R.string.chat_no_speech_detected
    "语音识别失败，请稍后再试" -> R.string.chat_couldn_t_transcribe_the_recording_try_again
    "朗读失败，请稍后再试" -> R.string.chat_couldn_t_read_the_response_aloud_try
    "网络连接失败" -> R.string.chat_connection_failed
    "API Key 无效，请在设置中更新" -> R.string.chat_invalid_key
    "账户余额不足，充值后即可继续" -> R.string.chat_insufficient_balance
    "请求异常（内部错误）" -> R.string.chat_request_error
    "当前对话已达上限，请开启新对话继续。" -> R.string.chat_context_limit
    "操作太频繁，稍后再试" -> R.string.chat_rate_limited
    "服务繁忙，稍后再试" -> R.string.chat_provider_busy
    "生成意外中断" -> R.string.chat_interrupted
    "已停止生成" -> R.string.chat_stopped
    "暂时无法回答，换个说法试试" -> R.string.chat_refusal
    "上传失败，点击重试" -> R.string.chat_upload_retry
    "附件已失效，请重新上传" -> R.string.chat_file_expired
    "请检查网络后重试" -> R.string.chat_network_retry
    "请求超时：连接或等待服务端响应超时" -> R.string.chat_response_timeout
    "域名解析失败，请检查网络或接口地址" -> R.string.chat_dns_failure
    else -> null
}

/** Translate app-owned notices and preserve raw details returned by providers. */
internal fun localizedChatStatus(res: Resources, message: String): String {
    chatStatusResource(message)?.let { return res.getString(it) }
    streamTimeout.matchEntire(message)?.let { match ->
        return res.getString(R.string.chat_stream_timeout, match.groupValues[1])
    }
    namedProviderBusy.matchEntire(message)?.let { match ->
        return res.getString(R.string.chat_named_provider_busy, match.groupValues[1])
    }
    switchedProvider.matchEntire(message)?.let { match ->
        val provider = match.groupValues[1]
        val name = if (provider == "新的服务商") res.getString(R.string.chat_the_new_provider) else provider
        return res.getString(R.string.chat_switched_to_supported_attachments_will_carry_over, name)
    }
    oversizedAttachment.matchEntire(message)?.let { match ->
        val (fileName, limit) = match.destructured
        return res.getString(R.string.chat_exceeds, fileName, limit)
    }
    namedAttachmentIssue.matchEntire(message)?.let { match ->
        val (fileName, reason) = match.destructured
        if (reason == "仅支持 PDF 和纯文本文件") {
            return res.getString(R.string.chat_isn_t_supported_use_a_pdf_or, fileName)
        }
        attachmentOverLimit.matchEntire(reason)?.let { over ->
            return res.getString(R.string.chat_exceeds, fileName, over.groupValues[1])
        }
        chatStatusResource(reason)?.let {
            return res.getString(R.string.chat_attachment_error, fileName, res.getString(it))
        }
    }
    return message
}

// 参数一律用正则分组取：以前的 removePrefix / removeSuffix 是在“拼好的展示串”上
// 切字符，生产方多一个空格或换个措辞，用户看到的就会是半截话。
private val streamTimeout = Regex("""^请求超时：超过 (.*) 秒没有收到数据$""", RegexOption.DOT_MATCHES_ALL)
private val namedProviderBusy = Regex("""^(.*) 服务繁忙，稍后再试$""", RegexOption.DOT_MATCHES_ALL)
private val switchedProvider = Regex("""^已换到 (.*)，支持的历史附件会随对话发送$""", RegexOption.DOT_MATCHES_ALL)
private val attachmentOverLimit = Regex("""^超过 (.*)$""", RegexOption.DOT_MATCHES_ALL)

private val oversizedAttachment = Regex("附件「(.*)」超过 (.*)", RegexOption.DOT_MATCHES_ALL)
private val namedAttachmentIssue = Regex("附件「(.*)」：(.*)", RegexOption.DOT_MATCHES_ALL)

