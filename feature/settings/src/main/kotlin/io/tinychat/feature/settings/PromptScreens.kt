package io.tinychat.feature.settings

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import io.tinychat.data.db.PromptPresetEntity
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import io.tinychat.model.prompt.PromptTemplate
import io.tinychat.model.prompt.PromptVariables
import io.tinychat.ui.TcAlertDialog
import io.tinychat.ui.TcButton
import io.tinychat.ui.TcButtonVariant
import io.tinychat.ui.TcChip
import io.tinychat.ui.TcIconButton
import io.tinychat.ui.TcListRow
import io.tinychat.ui.TcSectionLabel
import io.tinychat.ui.TcTopBar

/**
 * 系统提示词（决策 45）。
 *
 * 页面只有两件事：要不要官方那句（开关），以及用不用自己的一套（下面的单选列表）。
 * 「替换 / 追加 / 清空」那个三选一已经取消——它们描述的正是这两个控件组合出来的四种状态。
 */
@Composable
fun PromptSettingsScreen(
    includeOfficial: Boolean,
    presets: List<PromptPresetEntity>,
    selectedId: String?,
    onBack: () -> Unit,
    onToggleOfficial: (Boolean) -> Unit,
    onSelect: (String?) -> Unit,
    onEdit: (String) -> Unit,
    onNew: () -> Unit,
) {
    val palette = TcTheme.palette

    Column(Modifier.fillMaxSize().background(palette.bg)) {
        TcTopBar(title = stringResource(R.string.prefs_detail_prompt_title), leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), onBack) })

        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.prefs_detail_prompt_official),
                    style = TcTypography.bodyLarge,
                    color = palette.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = includeOfficial,
                    onCheckedChange = onToggleOfficial,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = palette.brand,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = palette.labelTertiary.copy(alpha = 0.4f),
                    ),
                )
            }
            Spacer(Modifier.height(16.dp))
            TcSectionLabel(stringResource(R.string.prefs_detail_prompt_presets))

            PromptRow(
                title = stringResource(R.string.prefs_detail_prompt_no_preset),
                selected = selectedId == null,
                onClick = { onSelect(null) },
            )

            presets.forEach { preset ->
                PromptRow(
                    title = preset.name,
                    selected = preset.id == selectedId,
                    onClick = { onSelect(preset.id) },
                    onEdit = { onEdit(preset.id) },
                )
            }

            TcListRow(
                title = stringResource(R.string.prefs_detail_prompt_new),
                leading = TcIcons.PlusCircle,
                onClick = onNew,
            )
        }
    }
}

/** 一行预设：点行 = 选中，右侧「编辑」进编辑页。 */
@Composable
private fun PromptRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val palette = TcTheme.palette
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = TcTypography.bodyLarge,
            color = if (selected) palette.brand else palette.labelPrimary,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                imageVector = TcIcons.Checked,
                contentDescription = stringResource(R.string.prefs_detail_selected),
                tint = palette.brand,
                modifier = Modifier.size(18.dp),
            )
        }
        if (onEdit != null) {
            Spacer(Modifier.size(8.dp))
            Text(
                text = stringResource(R.string.prefs_detail_edit),
                style = TcTypography.bodyMedium,
                color = palette.brand,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onEdit)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

/** 编辑页里「填进去」的两种动作，两者都会覆盖内容，所以都要确认。 */
private sealed interface FillAction {
    data object Clear : FillAction
    data class Template(val template: PromptTemplate) : FillAction
}

/**
 * 编辑预设 / 新建预设（决策 45）。
 *
 * 「复制为新预设」把当前屏幕上的内容变成一份**草稿**：名称自动加「副本」，
 * 只有点保存才会落库，原预设自始至终不动。这样同一套调教结果可以分叉出好几条不同用途的预设。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptEditorScreen(
    initialName: String,
    initialContent: String,
    /** 进入时就是「新建」：还没有对应行 */
    isNew: Boolean,
    canDelete: Boolean,
    /** 已有预设的名字，用来给副本起一个不重复的名字 */
    existingNames: List<String>,
    onBack: () -> Unit,
    onSave: (name: String, content: String, asNew: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val palette = TcTheme.palette
    val context = LocalContext.current

    // draft = 这份内容保存时会新建一条，而不是覆盖原来那条
    var draft by remember { mutableStateOf(isNew) }
    var name by remember { mutableStateOf(initialName) }
    var body by remember { mutableStateOf(TextFieldValue(initialContent)) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingFill by remember { mutableStateOf<FillAction?>(null) }

    val unknown = PromptVariables.unknown(body.text)
    val chars = body.text.length
    val tokens = (chars * 2 + 2) / 3
    val canSave = name.isNotBlank() && body.text.isNotBlank() && unknown.isEmpty()
    val dirty = name != initialName || body.text != initialContent

    fun leave() {
        if (dirty) confirmDiscard = true else onBack()
    }

    BackHandler(enabled = dirty) { confirmDiscard = true }

    Column(Modifier.fillMaxSize().background(palette.bg)) {
        TcTopBar(
            title = if (draft) stringResource(R.string.prefs_detail_prompt_new) else stringResource(R.string.prefs_detail_prompt_edit),
            leading = { TcIconButton(TcIcons.Back, stringResource(R.string.prefs_detail_back), ::leave) },
        )

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp),
        ) {
            if (draft) {
                Spacer(Modifier.height(8.dp))
            }
            EditorLabel(stringResource(R.string.prefs_detail_name))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(palette.selector, RoundedCornerShape(10.dp))
                    .border(1.dp, palette.borderL2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    textStyle = TcTypography.bodyLarge.copy(color = palette.labelPrimary),
                    cursorBrush = SolidColor(palette.brand),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EditorLabel(stringResource(R.string.prefs_detail_prompt_content), modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.prefs_detail_prompt_count, chars, tokens),
                    style = TcTypography.labelMedium,
                    color = palette.labelTertiary,
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 220.dp)
                    .background(palette.selector, RoundedCornerShape(10.dp))
                    .border(1.dp, palette.borderL2, RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                BasicTextField(
                    value = body,
                    onValueChange = { body = it },
                    textStyle = TcTypography.bodyLarge.copy(color = palette.labelPrimary),
                    cursorBrush = SolidColor(palette.brand),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
                )
            }

            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(R.string.prefs_detail_prompt_templates),
                style = TcTypography.labelMedium,
                color = palette.labelTertiary,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FillChip(stringResource(R.string.prefs_detail_prompt_empty)) {
                    if (body.text.isNotBlank()) pendingFill = FillAction.Clear
                }
                Templates.forEach { (nameResource, contentResource) ->
                    val template = PromptTemplate(stringResource(nameResource), stringResource(contentResource))
                    FillChip(template.name) {
                        if (body.text.isBlank()) {
                            body = body.copy(text = template.content)
                        } else {
                            pendingFill = FillAction.Template(template)
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PromptVariables.names.forEach { variable ->
                    TcChip(
                        text = "{{$variable}}",
                        onClick = { body = insertVariable(body, variable) },
                    )
                }
            }

            if (unknown.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.prefs_detail_prompt_unknown_variables, unknown.joinToString(" ") { "{{$it}}" }),
                    style = TcTypography.labelMedium,
                    color = palette.danger,
                )
            }

            // 动作区（02 §10.x）：与正文拉开距离，最多两个有形状的按钮，危险动作退到分隔线下的页脚
            Spacer(Modifier.height(32.dp))

            if (!draft) {
                TcButton(
                    text = stringResource(R.string.prefs_detail_prompt_duplicate),
                    onClick = {
                        draft = true
                        name = context.copyName(name, existingNames)
                    },
                    variant = TcButtonVariant.Outline,
                    fillWidth = true,
                )
                Spacer(Modifier.height(8.dp))
            }

            TcButton(
                text = stringResource(R.string.prefs_detail_save),
                onClick = {
                    onSave(name.trim(), body.text, draft)
                    Toast.makeText(context, R.string.prefs_detail_prompt_saved, Toast.LENGTH_SHORT).show()
                },
                enabled = canSave,
                fillWidth = true,
            )

            if (canDelete && !draft) {
                Spacer(Modifier.height(24.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(palette.borderL2),
                )
                Spacer(Modifier.height(24.dp))
                TcButton(
                    text = stringResource(R.string.prefs_detail_prompt_delete),
                    onClick = { confirmDelete = true },
                    variant = TcButtonVariant.Danger,
                    fillWidth = true,
                )
            }
        }
    }

    pendingFill?.let { action ->
        val title = when (action) {
            FillAction.Clear -> stringResource(R.string.prefs_detail_prompt_clear_confirm)
            is FillAction.Template -> stringResource(R.string.prefs_detail_prompt_replace_confirm, action.template.name)
        }
        TcAlertDialog(
            onDismissRequest = { pendingFill = null },
            title = { Text(title) },
            text = { Text(stringResource(R.string.prefs_detail_prompt_undo_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    body = when (action) {
                        FillAction.Clear -> body.copy(text = "")
                        is FillAction.Template -> body.copy(text = action.template.content)
                    }
                    pendingFill = null
                }) { Text(if (action == FillAction.Clear) stringResource(R.string.prefs_detail_prompt_empty) else stringResource(R.string.prefs_detail_prompt_replace)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingFill = null }) { Text(stringResource(R.string.prefs_detail_cancel), color = TcTheme.palette.labelSecondary) }
            },
        )
    }

    if (confirmDiscard) {
        TcAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.prefs_detail_discard_changes)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    onBack()
                }) { Text(stringResource(R.string.prefs_detail_discard)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.prefs_detail_keep_editing)) }
            },
        )
    }

    if (confirmDelete) {
        TcAlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.prefs_detail_prompt_delete_confirm)) },
            text = { Text(stringResource(R.string.prefs_detail_prompt_delete_warning)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.prefs_detail_delete), color = TcTheme.palette.danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.prefs_detail_cancel), color = TcTheme.palette.labelSecondary) }
            },
        )
    }
}

@Composable
private fun FillChip(text: String, onClick: () -> Unit) {
    TcChip(text = text, onClick = onClick)
}

@Composable
private fun EditorLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = TcTypography.labelMedium,
        color = TcTheme.palette.labelTertiary,
        modifier = modifier.padding(bottom = 6.dp),
    )
}

private val Templates = listOf(
    R.string.prefs_detail_prompt_roleplay to R.string.prefs_detail_prompt_roleplay_body,
    R.string.prefs_detail_prompt_translate to R.string.prefs_detail_prompt_translate_body,
    R.string.prefs_detail_prompt_tutor to R.string.prefs_detail_prompt_tutor_body,
)

// A copy keeps its name when the app language changes.
private val CopySuffix = Regex("""(?:\s*副本|\s+(?:copy|copie|копия|copia))(\s+\d+)?$""", RegexOption.IGNORE_CASE)

private fun Context.copyName(base: String, existing: List<String>): String {
    val stem = base.trim().replace(CopySuffix, "").trim().ifEmpty { getString(R.string.prefs_detail_preset) }
    val suffix = getString(R.string.prefs_detail_prompt_copy_suffix)
    val copy = "$stem $suffix"
    var candidate = copy
    var index = 2
    while (candidate in existing) {
        candidate = "$copy $index"
        index++
    }
    return candidate
}

/** 在光标处插入变量；有选区就替换掉选区。 */
private fun insertVariable(value: TextFieldValue, variable: String): TextFieldValue {
    val token = "{{$variable}}"
    val start = value.selection.min
    val end = value.selection.max
    val text = value.text.replaceRange(start, end, token)
    return value.copy(text = text, selection = TextRange(start + token.length))
}
