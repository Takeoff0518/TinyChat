package io.tinychat.feature.chat.share

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.tinychat.designsystem.LightPalette
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcThemeMode
import io.tinychat.markdown.MathPrewarm
import io.tinychat.markdown.MathRenderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * 分享长图（M8-2）。
 *
 * 做法：把一张 `ComposeView` 以 1×1 的尺寸**临时塞进当前 Activity 的 decorView**，
 * 手动 measure 成 1080px 宽、自然高度，再分段 draw 到 Bitmap。
 *
 * 为什么不另开 Dialog/悬浮窗：Compose 在新建窗口里要自己造 window recomposer，
 * 需要 `ViewTreeLifecycleOwner`；Dialog 的 decor 上没有，会直接抛
 * "ViewTreeLifecycleOwner not found"。挂在 Activity 已有的窗口里就白捡一个 recomposer。
 *
 * 为什么要等：正文走流式 markdown 通道（`TcMarkdown` 在 LaunchedEffect 里 append），
 * 公式/Mermaid 也是异步出位图。所以不能"布局完就画"——要等**高度连续几帧不变**，
 * 否则会拍到半张卡片。
 *
 * 为什么切段时立刻落盘：一张 1080×12000 的 ARGB_8888 是 51MB，攒三张就 OOM。
 */
object ShareCapture {

    /** 输出宽度固定 1080px（02 §5.7） */
    const val WIDTH_PX = 1080

    /** 单张图的高度上限（02 §5.7） */
    const val MAX_SEGMENT_PX = 12_000

    /** 卡片总高上限，防止病态内容把 measure 拖死 */
    private const val MAX_CARD_PX = 60_000

    data class Output(
        val files: List<File>,
        /** 首张图的 1/2 缩略图，只用于预览 */
        val preview: Bitmap,
        val pages: Int,
    )

    /** 卡片按 0.85× 密度渲染：1080px 宽里能多放约 20% 字符，代码块横向被裁得更少 */
    private const val DENSITY_SCALE = 0.85f

    /** 与 ShareCard 的水平内边距保持一致（预热公式要用正文区宽度） */
    private const val CARD_PADDING_DP = 26f

    /**
     * 渲染并落盘。失败抛异常，由调用方决定提示文案。
     *
     * @param markdown 正文，用于**预热公式**（见 [MathPrewarm]）
     * @param card 卡片内容；参数是**卡片宽度**（dp），保证输出正好 1080px 宽。
     */
    // 这一屏必须整段在主线程：View 的 draw 只能在 UI 线程跑，截图也是一段一段接着画的。
    // 落盘（PNG 压缩）夹在中间，页数只有 1–3 张、每张一条 1080px 的条带，实测不卡；
    // 想让 lint 闭嘴只能把压缩挪到别的线程，那样就得把 bitmap 跨线程传，收益不值这个复杂度。
    @SuppressLint("ThreadConstraint")
    suspend fun render(
        context: Context,
        dir: File,
        markdown: String,
        card: @Composable (width: Dp) -> Unit,
    ): Output = withContext(Dispatchers.Main.immediate) {
        val activity = context.findActivity() ?: error("分享需要 Activity 上下文")
        val density = activity.resources.displayMetrics.density * DENSITY_SCALE
        val widthDp = (WIDTH_PX / density).dp

        // 先把块级公式渲染好写进缓存：卡片是新的组合树，公式要重新过 WebView，
        // 不预热就会拍到「公式渲染中」——占位块高度固定，靠等高度等不出来。
        MathPrewarm.renderBlocks(
            context = activity,
            markdown = markdown,
            fontPx = (17 * density).toInt(),
            widthPx = (WIDTH_PX - 2 * CARD_PADDING_DP * density).toInt(),
            textColor = LightPalette.labelPrimary,
            background = LightPalette.bg,
        )

        val decor = activity.window.decorView as ViewGroup
        // 外层容器负责把宽度约束焊死；ComposeView 自己不能被继承（final）
        val container = FixedWidthHost(activity, WIDTH_PX)
        val view = ComposeView(context)
        view.setContent {
            // 分享图**固定浅色**：深色主题的长图发给别人会糊成一片
            CompositionLocalProvider(LocalDensity provides Density(density)) {
                TcTheme(mode = TcThemeMode.LIGHT) { card(widthDp) }
            }
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        decor.addView(container, FrameLayout.LayoutParams(WIDTH_PX, MAX_CARD_PX))

        try {
            val height = awaitStableHeight(container)
            require(height > 0) { "卡片高度为 0" }

            val outputDir = File(dir, "share").apply { mkdirs() }
            outputDir.listFiles()?.forEach { it.delete() }

            val files = ArrayList<File>(2)
            var preview: Bitmap? = null
            var offset = 0
            while (offset < height) {
                val piece = minOf(MAX_SEGMENT_PX, height - offset)
                val bitmap = Bitmap.createBitmap(WIDTH_PX, piece, Bitmap.Config.ARGB_8888)
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    translate(0f, -offset.toFloat())
                    container.draw(this)
                }
                if (preview == null) preview = scaleHalf(bitmap)
                val file = File(outputDir, "tinychat-${files.size + 1}.png")
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                files.add(file)
                bitmap.recycle()
                offset += piece
            }

            Output(
                files = files,
                preview = requireNotNull(preview),
                pages = files.size,
            )
        } finally {
            decor.removeView(container)
        }
    }

    /**
     * 反复 measure 直到"真的画完"。
     *
     * 三个条件同时满足才返回：
     * 1. 总时长 ≥ 2.5s：`TcMarkdown` 的 append 与 Compose 首帧都是异步的；
     * 2. 高度静默 ≥ 1.2s：内容块/图片落地会改变高度；
     * 3. **公式渲染队列清零**（`MathRenderState.inFlight() == 0`）。
     *    只看高度会把「公式渲染中」的占位块拍进去——占位块高度固定，
     *    高度可能一直稳定到最后一刻（实测踩过）。
     */
    private suspend fun awaitStableHeight(view: View): Int {
        val start = SystemClock.uptimeMillis()
        var last = -1
        var lastChange = start
        while (true) {
            awaitFrame()
            measure(view)
            val height = view.measuredHeight
            val now = SystemClock.uptimeMillis()
            if (height != last) {
                last = height
                lastChange = now
            }
            val elapsed = now - start
            val quiet = now - lastChange
            if (height > 0 && elapsed >= 2500 && quiet >= 1200 && MathRenderState.inFlight() == 0) {
                return height
            }
            if (elapsed > 25_000) return height.coerceAtLeast(0)
            delay(40)
        }
    }

    private fun measure(view: View) {
        val width = View.MeasureSpec.makeMeasureSpec(WIDTH_PX, View.MeasureSpec.EXACTLY)
        val height = View.MeasureSpec.makeMeasureSpec(MAX_CARD_PX, View.MeasureSpec.AT_MOST)
        view.measure(width, height)
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private suspend fun awaitFrame() = suspendCancellableCoroutine { cont ->
        Choreographer.getInstance().postFrameCallback {
            if (cont.isActive) cont.resume(Unit)
        }
    }

    private fun scaleHalf(source: Bitmap): Bitmap {
        val width = source.width / 2
        val height = (source.height / 2).coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, width, height, true)
    }

    private fun Context.findActivity(): Activity? {
        var cursor: Context? = this
        while (cursor is ContextWrapper) {
            if (cursor is Activity) return cursor
            cursor = cursor.baseContext
        }
        return null
    }

    /**
     * 把子 View 的宽度约束焊成 1080px 的容器。
     *
     * **为什么需要它**：decorView 每帧都会按 LayoutParams 重新 measure 子 View。
     * 若直接把手动 measure 到 1080 宽的 ComposeView 挂上去，下一帧又会被父容器
     * 量回去，Compose 里 `BoxWithConstraints` 的宽度就在两个值之间来回跳，
     * `TcMath` 的 `LaunchedEffect(key = request)` 跟着每帧重启 —— 公式永远渲染不完。
     * 这里是外层容器自己定尺寸，ComposeView 只见到一个固定宽度。
     */
    private class FixedWidthHost(context: Context, private val widthPx: Int) :
        FrameLayout(context) {

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.makeMeasureSpec(widthPx, MeasureSpec.EXACTLY)
            val height = MeasureSpec.makeMeasureSpec(MAX_CARD_PX, MeasureSpec.AT_MOST)
            var childHeight = 0
            for (index in 0 until childCount) {
                val child = getChildAt(index)
                child.measure(width, height)
                childHeight = maxOf(childHeight, child.measuredHeight)
            }
            setMeasuredDimension(widthPx, childHeight)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val child = getChildAt(0) ?: return
            child.layout(0, 0, widthPx, child.measuredHeight)
        }
    }
}
