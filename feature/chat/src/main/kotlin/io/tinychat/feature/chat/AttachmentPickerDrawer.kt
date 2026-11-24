package io.tinychat.feature.chat

import androidx.compose.ui.res.stringResource
import android.Manifest
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.compose.ui.platform.LocalView
import io.tinychat.designsystem.TcIcons
import io.tinychat.designsystem.TcTheme
import io.tinychat.designsystem.TcTypography
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
internal fun AttachmentPickerDrawer(
    progress: () -> Float,
    photosEnabled: Boolean,
    cameraEnabled: Boolean,
    filesEnabled: Boolean,
    drafts: List<DraftAttachment>,
    onTogglePhoto: (Uri) -> Unit,
    onOpenGallery: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFiles: () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth().clipToBounds(),
        content = {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (photosEnabled) RecentPhotos(drafts, onTogglePhoto)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (cameraEnabled) AttachmentAction(TcIcons.Camera, stringResource(R.string.chat_take_a_photo), Modifier.weight(1f), onTakePhoto)
                    if (photosEnabled) AttachmentAction(TcIcons.Photo, stringResource(R.string.chat_photos), Modifier.weight(1f), onOpenGallery)
                    if (filesEnabled) AttachmentAction(TcIcons.Folder, stringResource(R.string.chat_files), Modifier.weight(1f), onPickFiles)
                }
            }
        },
    ) { measurables, constraints ->
        val panel = measurables.single().measure(constraints.copy(minHeight = 0))
        val height = constraints.constrainHeight((panel.height * progress().coerceAtLeast(0f)).roundToInt())
        layout(panel.width, height) { panel.placeRelative(0, height - panel.height) }
    }
}

@Composable
private fun AttachmentAction(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val palette = TcTheme.palette
    Column(
        modifier = modifier
            .height(68.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(palette.selector)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, contentDescription = null, tint = palette.labelPrimary, modifier = Modifier.size(22.dp))
        Text(label, style = TcTypography.bodySmall, color = palette.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentPhotos(drafts: List<DraftAttachment>, onTogglePhoto: (Uri) -> Unit) {
    val context = LocalContext.current
    val palette = TcTheme.palette
    val lifecycle = LocalView.current.findViewTreeLifecycleOwner()?.lifecycle
    var refresh by remember { mutableIntStateOf(0) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refresh++
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) refresh++ }
        lifecycle?.addObserver(observer)
        onDispose { lifecycle?.removeObserver(observer) }
    }

    val access = remember(context, refresh) { recentPhotoAccess(context) }
    val recent by produceState<RecentPhotoState>(RecentPhotoState.Loading, context, access, refresh) {
        value = if (access == PhotoAccess.DENIED) {
            RecentPhotoState.Loaded(emptyList())
        } else {
            withContext(Dispatchers.IO) {
                // 失败与「确实没有照片」必须分开：以前一律当空，用户看到「没有最近照片」
                // 却不知道是读不到，也没有重试入口。
                queryRecentPhotos(context.contentResolver)
                    ?.let { RecentPhotoState.Loaded(it) }
                    ?: RecentPhotoState.Failed
            }
        }
    }
    var preview by remember { mutableStateOf<Uri?>(null) }
    val items = (recent as? RecentPhotoState.Loaded)?.photos.orEmpty()
    if (items.isNotEmpty()) {
        val selected = drafts.filter { it.isImage }.map { it.uri }.toSet()
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(items, key = { _, uri -> uri.toString() }) { index, uri ->
                val picked = uri in selected
                val shape = RoundedCornerShape(14.dp)
                Box(
                    modifier = Modifier.size(78.dp).clip(shape).background(palette.selector)
                        .border(1.dp, if (picked) palette.brand else palette.borderL2, shape)
                        .semantics { this.selected = picked }
                        .combinedClickable(
                            role = Role.Checkbox,
                            onClick = { onTogglePhoto(uri) },
                            onLongClickLabel = stringResource(R.string.chat_preview),
                            onLongClick = { preview = uri },
                        ),
                ) {
                    val thumb by rememberPhotoBitmap(uri, targetSize = 240, thumbnail = true)
                    thumb?.let {
                        Image(it, stringResource(R.string.chat_recent_photo_number, index + 1), Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                    }
                    Box(
                        modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(19.dp)
                            .background(if (picked) palette.brand else Color.Black.copy(alpha = 0.32f), CircleShape)
                            .border(1.5.dp, Color.White, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (picked) Icon(TcIcons.Check, null, tint = Color.White, modifier = Modifier.size(12.dp))
                    }
                }
            }
            if (access == PhotoAccess.PARTIAL) {
                // 用户选了「仅选中的照片」：能看到的只有那几张，给一个再次选择的入口，
                // 否则界面上只会莫名其妙地少一大截。
                item(key = "manage-photo-access") {
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        modifier = Modifier.size(78.dp).clip(shape).background(palette.selector)
                            .border(1.dp, palette.borderL2, shape)
                            .clickable(role = Role.Button) { requestPermission.launch(recentPhotoPermissions()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            stringResource(R.string.chat_manage_photo_access),
                            style = TcTypography.bodySmall,
                            color = palette.brand,
                        )
                    }
                }
            }
        }
    } else {
        Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.Center) {
            when {
                access == PhotoAccess.DENIED -> Text(
                    stringResource(R.string.chat_allow_access_to_recent_photos),
                    style = TcTypography.bodyMedium,
                    color = palette.brand,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                        requestPermission.launch(recentPhotoPermissions())
                    }.padding(10.dp),
                )
                recent == RecentPhotoState.Failed -> Text(
                    stringResource(R.string.chat_photo_read_failed),
                    style = TcTypography.bodyMedium,
                    color = palette.brand,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) {
                        refresh++
                    }.padding(10.dp),
                )
                recent == RecentPhotoState.Loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Text(stringResource(R.string.chat_no_recent_photos), style = TcTypography.bodySmall, color = palette.labelTertiary)
            }
        }
    }
    preview?.let { uri ->
        Dialog(onDismissRequest = { preview = null }) {
            val image by rememberPhotoBitmap(uri, targetSize = 1080, thumbnail = false)
            Box(
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(20.dp))
                    .background(Color.Black).clickable { preview = null },
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it, stringResource(R.string.chat_photo_preview), Modifier.matchParentSize(), contentScale = ContentScale.Fit) }
            }
        }
    }
}

/** 相册访问的三种状态；`PARTIAL` 是 Android 14 的「仅选中的照片」 */
private enum class PhotoAccess { FULL, PARTIAL, DENIED }

private sealed interface RecentPhotoState {
    data object Loading : RecentPhotoState
    data object Failed : RecentPhotoState
    data class Loaded(val photos: List<Uri>) : RecentPhotoState
}

private fun recentPhotoPermissions() = when {
    Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
}

/**
 * 区分「全库」「只选了几张」「没授权」。
 *
 * 以前用的是 `any { granted }`：Android 14 上用户选了「仅选中的照片」也算有权限，
 * 于是网格只会显示那几张，界面却没有任何说明，看起来像相册被清空了。
 */
private fun recentPhotoAccess(context: Context): PhotoAccess {
    fun granted(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    return when {
        Build.VERSION.SDK_INT < 33 -> if (granted(Manifest.permission.READ_EXTERNAL_STORAGE)) PhotoAccess.FULL else PhotoAccess.DENIED
        granted(Manifest.permission.READ_MEDIA_IMAGES) -> PhotoAccess.FULL
        Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> PhotoAccess.PARTIAL
        else -> PhotoAccess.DENIED
    }
}

/** 读不到时返回 `null`，与「确实是空相册」区分开 */
private fun queryRecentPhotos(resolver: ContentResolver): List<Uri>? = runCatching {
    val photos = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    val columns = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DATE_TAKEN,
        MediaStore.Images.Media.DATE_MODIFIED,
        MediaStore.Images.Media.DATE_ADDED,
    )
    resolver.query(photos, columns, null, null, null)?.use { cursor ->
        val metadata = sequence {
            while (cursor.moveToNext()) {
                yield(RecentPhoto(cursor.getLong(0), cursor.getLong(1), cursor.getLong(2), cursor.getLong(3)))
            }
        }
        newestPhotoIds(metadata).map { ContentUris.withAppendedId(photos, it) }
    }.orEmpty()
}.getOrNull()

@Composable
private fun rememberPhotoBitmap(uri: Uri, targetSize: Int, thumbnail: Boolean): androidx.compose.runtime.State<ImageBitmap?> {
    val resolver = LocalContext.current.contentResolver
    return produceState<ImageBitmap?>(null, resolver, uri, targetSize, thumbnail) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bitmap = when {
                    thumbnail && Build.VERSION.SDK_INT >= 29 -> resolver.loadThumbnail(uri, Size(targetSize, targetSize), null)
                    Build.VERSION.SDK_INT >= 28 -> ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        val size = info.size
                        val scale = minOf(1f, targetSize.toFloat() / maxOf(size.width, size.height))
                        decoder.setTargetSize((size.width * scale).roundToInt().coerceAtLeast(1), (size.height * scale).roundToInt().coerceAtLeast(1))
                    }
                    else -> {
                        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                        options.inSampleSize = Integer.highestOneBit((maxOf(options.outWidth, options.outHeight) / targetSize).coerceAtLeast(1))
                        options.inJustDecodeBounds = false
                        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    }
                }
                bitmap?.asImageBitmap()
            }.getOrNull()
        }
    }
}
