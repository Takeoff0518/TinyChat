package io.tinychat.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * 应用图标入口。
 *
 * **能用的全部来自 chat-demo（MIT）**，见 [ChatDemoIcons]：路径数据一字未改，机械移植，
 * 不再有手绘近似。只有 chat-demo 里确实没有的（分享、向上箭头）才保留手绘。
 *
 * 决策 15 已更新：图标不再"暂缓"，素材这一侧已经齐了。
 */
object TcIcons {

    // —— 直接来自 chat-demo ——

    val Menu: ImageVector get() = ChatDemoIcons.IconMenuOutline20
    val NewChat: ImageVector get() = ChatDemoIcons.IconNewChatOutline20
    val NewChatCircle: ImageVector get() = ChatDemoIcons.NewChatIcon
    val Send: ImageVector get() = ChatDemoIcons.IconSendOutline16
    val Stop: ImageVector get() = ChatDemoIcons.IconStopFill16
    val Mic: ImageVector get() = ChatDemoIcons.IconMicOutline16
    val MicFilled: ImageVector get() = ChatDemoIcons.IconMicFill16
    val Attach: ImageVector get() = ChatDemoIcons.IconPaperclipOutline16
    val Copy: ImageVector get() = ChatDemoIcons.IconCopyOutline16
    val Speak: ImageVector get() = ChatDemoIcons.IconPlayMediaOutline16
    val Regenerate: ImageVector get() = ChatDemoIcons.IconRefreshOutline16
    val Settings: ImageVector get() = ChatDemoIcons.IconSettingsOutline16
    val Think: ImageVector get() = ChatDemoIcons.IconThinkOutline14
    val Globe: ImageVector get() = ChatDemoIcons.IconGlobeLanguageOutline14
    val PlusCircle: ImageVector get() = ChatDemoIcons.NewChatIcon
    val Search: ImageVector get() = ChatDemoIcons.IconSearchOutline16
    val ChevronDown: ImageVector get() = ChatDemoIcons.IconChevronDownOutline14
    val ChevronRight: ImageVector get() = ChatDemoIcons.IconChevronRightOutline14

    // 设置与数据管理会用到
    val Edit: ImageVector get() = ChatDemoIcons.IconEditOutline16
    val Trash: ImageVector get() = ChatDemoIcons.IconTrashOutline16
    val Close: ImageVector get() = ChatDemoIcons.IconCloseOutline16
    val CloseFilled: ImageVector get() = ChatDemoIcons.IconCloseFill14
    val Check: ImageVector get() = ChatDemoIcons.IconCheckOutline16
    val Checked: ImageVector get() = ChatDemoIcons.IconCheckedOutline14
    val Link: ImageVector get() = ChatDemoIcons.IconLinkOutline16
    val LinkSmall: ImageVector get() = ChatDemoIcons.IconLinkOutline14
    val ExternalLink: ImageVector get() = ChatDemoIcons.IconRightUpOutline14
    val Download: ImageVector get() = ChatDemoIcons.IconDownloadOutline16
    val Upload: ImageVector get() = ChatDemoIcons.IconUploadOutline14
    val Ellipsis: ImageVector get() = ChatDemoIcons.IconEllipsisOutline16
    val Browse: ImageVector get() = ChatDemoIcons.IconBrowseOutline16
    val BrowseWarning: ImageVector get() = ChatDemoIcons.IconBrowseWarningOutline16
    val SearchWarning: ImageVector get() = ChatDemoIcons.IconSearchWarningOutline16
    val Warning: ImageVector get() = ChatDemoIcons.IconWarningOutline16
    val ToastError: ImageVector get() = ChatDemoIcons.IconToastError16
    val PhotoError: ImageVector get() = ChatDemoIcons.IconPhotoErrorFill20
    val Data: ImageVector get() = ChatDemoIcons.IconDataOutline16
    val PanelLeft: ImageVector get() = ChatDemoIcons.IconPanelLeftOutline16
    val ThemeDark: ImageVector get() = ChatDemoIcons.IconDarkOutline16
    val ThemeLight: ImageVector get() = ChatDemoIcons.IconLightOutline16
    val ThemeSystem: ImageVector get() = ChatDemoIcons.IconFollowsystemOutline16

    // —— chat-demo 里没有，保留手绘 ——

    /**
     * 分享（chat-demo 无对应素材，手绘）。
     *
     * **必须按 16 网格、近似满幅画**：chat-demo 那套图标都是 16 网格的填充轮廓，
     * 图形几乎顶满画布（约 97% 宽高）。原实现是 24 网格 + 1.8 描边，"框+上箭头"
     * 只占中间 58%，渲染到 18dp 时视觉上比旁边的复制/朗读/重新生成小一圈、笔画也细。
     * 这里改成 16 网格、1.35 描边，满幅后与三者同视觉重量。
     */
    val Share: ImageVector by lazy {
        strokeIcon(
            name = "tc_share",
            pathData = "M8,0.9 V9.8 M3.9,4.3 L8,0.8 L12.1,4.3 M0.9,8.4 V15.0 H15.1 V8.4",
            viewport = 16f,
            strokeWidth = 1.35f,
        )
    }

    /** 回到底部用的下箭头（chat-demo 用 ChevronDown 套圆圈，这里保留更直白的箭头） */
    val ArrowDown: ImageVector by lazy { strokeIcon("tc_arrow_down", "M12,5 V18 M7,13 L12,18 L17,13") }

    /** 返回（chat-demo 把 ChevronRight 旋转 180°，这里直接画左箭头） */
    val Back: ImageVector by lazy { strokeIcon("tc_back", "M15,4 L7,12 L15,20") }

    val ChevronUp: ImageVector by lazy { strokeIcon("tc_chevron_up", "M7,14 L12,9 L17,14") }

    val Account: ImageVector by lazy {
        strokeIcon("tc_account", "M12,4 A4,4 0 1,0 12,12 A4,4 0 1,0 12,4 M4,21 C4,13 20,13 20,21")
    }
    val TypeSize: ImageVector by lazy {
        strokeIcon("tc_type_size", "M2,18 L6,9 L10,18 M3.8,14.5 H8.2 M12,18 L17,5 L22,18 M14,13 H20")
    }
    val Location: ImageVector by lazy {
        strokeIcon("tc_location", "M12,22 C10,20 5,15 5,10 A7,7 0 1,1 19,10 C19,15 14,20 12,22 Z M12,8 A2,2 0 1,0 12,12 A2,2 0 1,0 12,8")
    }
    val Usage: ImageVector by lazy {
        strokeIcon("tc_usage", "M4,19 H21 M8,15 V11 M13,15 V8 M18,15 V5")
    }
    val Info: ImageVector by lazy {
        strokeIcon("tc_info", "M12,2.5 A9.5,9.5 0 1,0 12,21.5 A9.5,9.5 0 1,0 12,2.5 M12,11 V17 M12,7 V7.2")
    }

    /** 多选（chat-demo 无素材）：方框 + 勾 */
    val MultiSelect: ImageVector by lazy {
        strokeIcon("tc_multi_select", "M4,6 H20 V20 H4 Z M8,13 L11,16 L16,10")
    }

    /** 置顶（chat-demo 无素材）：顶部横线 + 向上箭头 */
    val Pin: ImageVector by lazy {
        strokeIcon("tc_pin", "M5,4 H19 M12,20 V8 M7,13 L12,8 L17,13")
    }

    /**
     * 键盘（M5 语音模式里替换麦克风，chat-demo 无素材）：外框 + 两排按键 + 空格键。
     *
     * 同样只用折线不画圆弧（见下面附件图标的说明）。
     */
    val Keyboard: ImageVector by lazy {
        strokeIcon(
            name = "tc_keyboard",
            pathData = "M3,6 H21 V18 H3 Z " +
                "M6.5,9 V10 M9.5,9 V10 M12.5,9 V10 M15.5,9 V10 M18,9 V10 " +
                "M6.5,12 V13 M9.5,12 V13 M12.5,12 V13 M15.5,12 V13 M18,12 V13 " +
                "M8,15 H16",
        )
    }

    // —— 附件（M4）：chat-demo 没有相册/拍照/文件图标，按 24 网格手绘 ——
    // 只用折线不画圆弧：PathParser 的 A 指令在这些小尺寸下容易糊，菱形/三角在 18dp 更清楚。

    /** 相册：外框 + 山形 */
    val Photo: ImageVector by lazy {
        strokeIcon("tc_photo", "M3,5 H21 V19 H3 Z M3,16 L9,11 L14,15 L17,12.5 L21,16")
    }

    /** 拍照：机身 + 顶部凸起 + 菱形镜头 */
    val Camera: ImageVector by lazy {
        strokeIcon("tc_camera", "M3,8 H6.5 L8.5,5.5 H15.5 L17.5,8 H21 V19 H3 Z M12,10.2 L14.5,12.7 L12,15.2 L9.5,12.7 Z")
    }

    /** 文件：带折角的纸张 */
    val File: ImageVector by lazy {
        strokeIcon("tc_file", "M6,3 H14 L19,8 V21 H6 Z M14,3 V8 H19")
    }

    /** 文件夹（选文件的入口与文件夹型附件） */
    val Folder: ImageVector by lazy {
        strokeIcon("tc_folder", "M3,6 H10 L12,8.5 H21 V19 H3 Z")
    }

    private const val SW = 1.8f

    /**
     * 手绘描边图标。
     *
     * [viewport] 要跟同排的邻居对齐：chat-demo 那套是 16 网格的填充轮廓，
     * 手绘图标若用 24 网格画，同样的 dp 尺寸下会明显偏小偏细（见 [Share] 的注释）。
     */
    private fun strokeIcon(
        name: String,
        pathData: String,
        viewport: Float = 24f,
        strokeWidth: Float = SW,
    ): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = viewport.dp,
            defaultHeight = viewport.dp,
            viewportWidth = viewport,
            viewportHeight = viewport,
        ).addPath(
            pathData = PathParser().parsePathString(pathData).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = strokeWidth,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()
}
