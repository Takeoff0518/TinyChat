package io.tinychat

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.NavigationEventHandler
import androidx.navigationevent.NavigationEventHistory
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventInput
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import io.tinychat.designsystem.TcMotion
import kotlin.math.abs

private val PageSlide = tween<IntOffset>(TcMotion.Page, easing = TcMotion.Standard)
private val PredictivePageSlide = tween<IntOffset>(TcMotion.Page, easing = LinearEasing)

@Composable
internal fun PageNavHost(
    navController: NavHostController,
    startDestination: String,
    builder: NavGraphBuilder.() -> Unit,
) {
    val parentDispatcher = checkNotNull(LocalNavigationEventDispatcherOwner.current)
        .navigationEventDispatcher
    val input = remember(parentDispatcher) { PageBackInput() }
    val currentEntry by navController.currentBackStackEntryAsState()
    val canSwipeBack = currentEntry?.destination?.route?.let { it != startDestination } == true
    DisposableEffect(parentDispatcher, input) {
        input.attachTo(parentDispatcher)
        onDispose { input.dispose() }
    }
    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides input) {
        NavHost(
            navController = navController,
            startDestination = startDestination,
            modifier = Modifier.fillMaxSize().onSizeChanged { input.pageWidthPx = it.width }
                .swipePageBack(enabled = canSwipeBack, input = input),
            enterTransition = { slideInHorizontally(PageSlide) { it } },
            exitTransition = { ExitTransition.None },
            popEnterTransition = { EnterTransition.None },
            popExitTransition = { slideOutHorizontally(PageSlide) { it } },
            predictivePopEnterTransition = { _ -> EnterTransition.None },
            predictivePopExitTransition = { _ -> slideOutHorizontally(PredictivePageSlide) { it } },
            builder = builder,
        )
    }
}

@Composable
private fun Modifier.swipePageBack(enabled: Boolean, input: PageBackInput): Modifier {
    if (!enabled) return this
    val systemGestures = WindowInsets.systemGestures
    val layoutDirection = LocalLayoutDirection.current
    return pointerInput(input, systemGestures, layoutDirection) {
        val slop = viewConfiguration.touchSlop
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val width = size.width
            val leftEdge = systemGestures.getLeft(this, layoutDirection)
            val rightEdge = systemGestures.getRight(this, layoutDirection)
            if (width == 0 || down.position.x < leftEdge || down.position.x >= width - rightEdge) {
                return@awaitEachGesture
            }
            val velocity = VelocityTracker()
            velocity.addPosition(down.uptimeMillis, down.position)
            var started = false
            var completed = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed || event.changes.any { it.id != down.id && it.pressed }) break

                    val dx = change.position.x - down.position.x
                    val progress = (dx / width).coerceIn(0f, 1f)
                    velocity.addPosition(change.uptimeMillis, change.position)
                    if (!change.pressed) {
                        completed = started && (progress >= 0.25f ||
                            (progress >= 0.12f && velocity.calculateVelocity().x / density >= 700f))
                        if (started) change.consume()
                        break
                    }
                    if (!started) {
                        val dy = abs(change.position.y - down.position.y)
                        if (abs(dx) < slop && dy < slop) continue
                        if (dx < slop || dx < 1.5f * dy) break
                        started = input.startSwipe(down)
                        if (!started) break
                    }
                    change.consume()
                    input.progressSwipe(change, progress)
                }
            } finally {
                if (started) input.finishSwipe(completed)
            }
        }
    }
}

// Keep NavHost's handler and descendant BackHandlers together. The system still decides
// when to complete or cancel edge gestures. Page swipes use the same child dispatcher.
private class PageBackInput : NavigationEventInput(), NavigationEventDispatcherOwner {
    override val navigationEventDispatcher = NavigationEventDispatcher()
    var pageWidthPx = 0
    private var attached = false

    fun attachTo(parent: NavigationEventDispatcher) {
        navigationEventDispatcher.addInput(this)
        parent.addHandler(systemHandler)
        attached = true
    }

    fun dispose() {
        attached = false
        systemHandler.remove()
        navigationEventDispatcher.dispose()
    }

    fun startSwipe(change: PointerInputChange): Boolean {
        if (!attached) return false
        dispatchOnBackStarted(change.backEvent(0f))
        return true
    }

    fun progressSwipe(change: PointerInputChange, progress: Float) {
        if (attached) dispatchOnBackProgressed(change.backEvent(progress))
    }

    fun finishSwipe(completed: Boolean) {
        if (!attached) return
        if (completed) dispatchOnBackCompleted() else dispatchOnBackCancelled()
    }

    private val systemHandler = object : NavigationEventHandler<NavigationEventInfo>(
        initialInfo = NavigationEventInfo.None,
        isBackEnabled = false,
        isForwardEnabled = false,
    ) {
        private var startTouchX = 0f

        override fun onBackStarted(event: NavigationEvent) {
            startTouchX = event.touchX
            dispatchOnBackStarted(event.withPageProgress(startTouchX, pageWidthPx))
        }

        override fun onBackProgressed(event: NavigationEvent) {
            dispatchOnBackProgressed(event.withPageProgress(startTouchX, pageWidthPx))
        }

        override fun onBackCancelled() = dispatchOnBackCancelled()

        override fun onBackCompleted() = dispatchOnBackCompleted()
    }

    override fun onHasEnabledHandlersChanged(hasEnabledHandlers: Boolean) {
        systemHandler.isBackEnabled = hasEnabledHandlers
    }

    override fun onHistoryChanged(history: NavigationEventHistory) {
        val entries = history.mergedHistory
        val currentIndex = history.currentIndex
        systemHandler.setInfo(
            currentInfo = entries.getOrNull(currentIndex) ?: NavigationEventInfo.None,
            backInfo = entries.take(currentIndex.coerceAtLeast(0)),
            forwardInfo = entries.drop(currentIndex + 1),
        )
    }
}

private fun PointerInputChange.backEvent(progress: Float) = NavigationEvent(
    swipeEdge = NavigationEvent.EDGE_LEFT,
    progress = progress,
    touchX = position.x,
    touchY = position.y,
    frameTimeMillis = uptimeMillis,
)

private fun NavigationEvent.withPageProgress(startTouchX: Float, pageWidthPx: Int): NavigationEvent =
    NavigationEvent(
        swipeEdge = swipeEdge,
        progress = pageBackProgress(this, startTouchX, pageWidthPx),
        touchX = touchX,
        touchY = touchY,
        frameTimeMillis = frameTimeMillis,
    )

internal fun pageBackProgress(event: NavigationEvent, startTouchX: Float, pageWidthPx: Int): Float {
    val distance = when (event.swipeEdge) {
        NavigationEvent.EDGE_LEFT -> event.touchX - startTouchX
        NavigationEvent.EDGE_RIGHT -> startTouchX - event.touchX
        else -> Float.NaN
    }
    if (pageWidthPx <= 0 || !distance.isFinite()) {
        return if (event.progress.isFinite()) event.progress.coerceIn(0f, 1f) else 0f
    }
    return (distance / pageWidthPx).coerceIn(0f, 1f)
}
