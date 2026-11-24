package io.tinychat.designsystem

import androidx.compose.ui.graphics.Color

/**
 * 代码着色令牌（M2-4）。
 *
 * 只服务等宽代码区，所以单独成组，不混进界面配色。取值要在 `codeBg`
 * （浅色 #F9FAFB / 深色 #1B1B1C）上都能读清：关键字与属性偏冷，字符串偏深，
 * 注释压到最淡——注释本来就不该抢视线。
 */
data class TcSyntaxColors(
    val keyword: Color,
    val string: Color,
    val number: Color,
    val comment: Color,
    val punctuation: Color,
    val annotation: Color,
    /** JSON 的键、Markdown 的链接地址、Shell 的变量名：都是「名字」类 */
    val property: Color,
)

/**
 * 色板（M0-3）。取值来自 chat-demo `src/app/theme.css`（MIT），见 docs/02 §10.2。
 * 浅色/深色成对维护，**不要在业务代码里写死颜色**。
 *
 * Derived from deepseek-ai/chat-demo (MIT). Copyright (c) DeepSeek.
 */
data class TcPalette(
    val brand: Color,
    val brandOn: Color,
    val bubble: Color,
    val bg: Color,
    val bgLayer2: Color,
    val settingsBackground: Color,
    /** 列表「当前项」底色（抽屉里高亮正在聊的那条） */
    val selector: Color,
    val labelPrimary: Color,
    val labelSecondary: Color,
    val labelTertiary: Color,
    val borderL2: Color,
    /**
     * 圆角 logo 方块的描边（决策 62）。
     *
     * 取图自带的黑底（#020101）与深色页底（#151517）只差一点，纯靠底色分不出边界，
     * 所以暗色下用一条半透明白把轮廓勾出来；浅色下黑方块本来就有边界，取透明即可。
     */
    val logoRing: Color,
    val codeBg: Color,
    val inlineCodeBg: Color,
    val danger: Color,
    /** 警示橙：断网/限流这类「可以手动重试」的失败用它（官方 App 的重试圆钮就是这个色） */
    val warning: Color,
    /** 代码着色令牌，见 [TcSyntaxColors] */
    val syntax: TcSyntaxColors,
)

val LightPalette = TcPalette(
    brand = Color(0xFF3964FE),
    brandOn = Color(0xFFFFFFFF),
    bubble = Color(0xFFEDF3FE),
    bg = Color(0xFFFFFFFF),
    bgLayer2 = Color(0xFFFFFFFF),
    settingsBackground = Color(0xFFF6F6F6),
    selector = Color(0xFFF2F3F5),
    labelPrimary = Color(0xFF0F1115),
    labelSecondary = Color(0xFF61666B),
    labelTertiary = Color(0xFF81858C),
    borderL2 = Color(0x1A000000),
    logoRing = Color(0x00000000),
    codeBg = Color(0xFFF9FAFB),
    inlineCodeBg = Color(0xFFEBEEF2),
    danger = Color(0xFFEC1313),
    warning = Color(0xFFEF8A0C),
    syntax = TcSyntaxColors(
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        number = Color(0xFF953800),
        comment = Color(0xFF6E7781),
        punctuation = Color(0xFF57606A),
        annotation = Color(0xFF8250DF),
        property = Color(0xFF0550AE),
    ),
)

val DarkPalette = TcPalette(
    brand = Color(0xFF5686FE),
    brandOn = Color(0xFFFFFFFF),
    bubble = Color(0xFF2C2C2E),
    bg = Color(0xFF151517),
    bgLayer2 = Color(0xFF2C2C2E),
    settingsBackground = Color(0xFF151517),
    selector = Color(0xFF232326),
    labelPrimary = Color(0xFFF9FAFB),
    labelSecondary = Color(0xFFCFD3D6),
    labelTertiary = Color(0xFFADB2B8),
    borderL2 = Color(0x1FFFFFFF),
    logoRing = Color(0x29FFFFFF),
    codeBg = Color(0xFF1B1B1C),
    inlineCodeBg = Color(0xFF2C2C2E),
    danger = Color(0xFFF25A5A),
    warning = Color(0xFFF0A13C),
    syntax = TcSyntaxColors(
        keyword = Color(0xFFFF7B72),
        string = Color(0xFFA5D6FF),
        number = Color(0xFFFFA657),
        comment = Color(0xFF8B949E),
        punctuation = Color(0xFFC9D1D9),
        annotation = Color(0xFFD2A8FF),
        property = Color(0xFF79C0FF),
    ),
)
