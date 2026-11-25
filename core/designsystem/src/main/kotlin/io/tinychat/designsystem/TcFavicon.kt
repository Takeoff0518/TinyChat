package io.tinychat.designsystem

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.compose.SubcomposeAsyncImage
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.ImageRequest
import coil3.request.crossfade
import java.util.concurrent.atomic.AtomicBoolean

private val loaderReady = AtomicBoolean(false)

/**
 * 站点 favicon：圆形，没有图标或加载失败时显示地球图标。
 *
 * DeepSeek 的图标 CDN（`https://cdn.deepseek.com/site-icons/<domain>`）**没有扩展名**、
 * `Content-Type: binary/octet-stream`，靠 Coil 的内容嗅探解码，所以必须走网络 fetcher。
 */
@Composable
fun TcFavicon(
    url: String?,
    modifier: Modifier = Modifier,
    size: Int = 16,
    showFallback: Boolean = true,
    onAvailableChange: ((Boolean) -> Unit)? = null,
) {
    val palette = TcTheme.palette
    val context = LocalContext.current

    if (!url.isNullOrBlank()) ensureImageLoader()

    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(palette.bgLayer2),
        contentAlignment = Alignment.Center,
    ) {
        if (url.isNullOrBlank()) {
            if (showFallback) TcWebIcon(size)
        } else {
            SubcomposeAsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { if (showFallback) TcWebIcon(size) },
                error = { if (showFallback) TcWebIcon(size) },
                onLoading = { onAvailableChange?.invoke(false) },
                onSuccess = { onAvailableChange?.invoke(true) },
                onError = { onAvailableChange?.invoke(false) },
            )
        }
    }
}

@Composable
private fun TcWebIcon(size: Int) {
    Icon(
        imageVector = TcIcons.Globe,
        contentDescription = null,
        tint = TcTheme.palette.labelSecondary,
        modifier = Modifier.size((size * 0.75f).dp),
    )
}

private fun ensureImageLoader() {
    if (!loaderReady.compareAndSet(false, true)) return
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory()) }
            .build()
    }
}
