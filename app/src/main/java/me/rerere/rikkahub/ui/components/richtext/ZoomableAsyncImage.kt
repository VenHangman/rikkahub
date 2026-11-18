package me.rerere.rikkahub.ui.components.richtext

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.request.crossfade
import coil3.request.placeholder
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.rikkahub.R
import me.rerere.rikkahub.ui.components.ui.ImagePreviewDialog
import me.rerere.rikkahub.ui.modifier.shimmer
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import me.rerere.rikkahub.utils.ImageClipboard
import me.rerere.ui.components.LocalExportContext
import java.io.File

/**
 * 可点击放大、可长按复制的一张图片。
 *
 * @param model Coil 的数据源：http、data:、content:、file: 或工作区解析后的绝对路径
 * @param workspaceFile 工作区对应的宿主文件；有它时复制直接读它，不等 Coil 落盘。
 *        只有调用方按作用域解析过（并确认仍在当前工作区内）才传，默认 null 走 [model]。
 */
@Composable
fun ZoomableAsyncImage(
    model: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
    contentScale: ContentScale = ContentScale.Fit,
    alpha: Float = DefaultAlpha,
    workspaceFile: File? = null,
) {
    val context = LocalContext.current
    val export = LocalExportContext.current
    val scope = rememberCoroutineScope()

    // 状态按 model 记：LazyRow/LazyColumn 复用同一个槽位换成另一张图时，
    // 上一张图的菜单、缓存 key 不会串到新图上
    var showImageViewer by remember(model) { mutableStateOf(false) }
    var menuExpanded by remember(model) { mutableStateOf(false) }
    var copying by remember(model) { mutableStateOf(false) }
    // 只有真正显示出来的图才有可复制的内容；占位图、加载失败时不给长按菜单
    var displayed by remember(model) { mutableStateOf(false) }
    // 远端图片编码后的字节在 Coil 磁盘缓存里，key 跟着当前 model 记录
    var diskCacheKey by remember(model) { mutableStateOf<String?>(null) }

    val placeholder = if (LocalDarkMode.current) R.drawable.placeholder_dark else R.drawable.placeholder
    val coilModel = ImageRequest.Builder(context)
        .data(model)
        .placeholder(placeholder)
        .crossfade(false)
        .allowHardware(!export)
        .build()
    var loading by remember(model) { mutableStateOf(false) }

    val canCopy = !export && displayed && !model.isNullOrBlank()
    val longPressLabel = stringResource(R.string.chat_image_menu_long_press)
    val onLongClick: (() -> Unit)? = if (canCopy) {
        { menuExpanded = true }
    } else {
        null
    }

    // 图被换掉、重新加载或加载失败时收起菜单，避免它挂在占位图上
    LaunchedEffect(canCopy) {
        if (!canCopy) menuExpanded = false
    }

    // 锚点容器：DropdownMenu 贴在图片本身的位置弹出。
    // propagateMinConstraints 让图片拿到的约束和直接放在父布局里时一致，测量结果不变
    Box(propagateMinConstraints = true) {
        AsyncImage(
            model = coilModel,
            contentDescription = contentDescription,
            modifier = modifier
                .shimmer(isLoading = loading)
                .combinedClickable(
                    onLongClickLabel = longPressLabel,
                    onLongClick = onLongClick,
                    onClick = {
                        showImageViewer = true
                    },
                ),
            contentScale = contentScale,
            alpha = alpha,
            alignment = alignment,
            onLoading = {
                loading = true
                displayed = false
                diskCacheKey = null
            },
            onSuccess = {
                loading = false
                displayed = true
                diskCacheKey = it.result.diskCacheKey
            },
            onError = {
                loading = false
                displayed = false
                diskCacheKey = null
            },
        )

        if (canCopy) {
            DropdownMenu(
                expanded = menuExpanded,
                onDismissRequest = { menuExpanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.chat_image_copy)) },
                    leadingIcon = {
                        Icon(
                            imageVector = HugeIcons.Copy01,
                            contentDescription = null,
                        )
                    },
                    enabled = !copying,
                    onClick = {
                        if (copying) return@DropdownMenuItem
                        menuExpanded = false
                        // 复制期间图可能被换掉，先把这次要用的源同步取出来
                        val source = model.orEmpty()
                        val sourceDiskCacheKey = diskCacheKey
                        val sourceWorkspaceFile = workspaceFile
                        copying = true
                        scope.launch {
                            // ImageClipboard 自己把可预期的失败收敛成 false，取消原样抛出，
                            // 这里只需要保证无论成功、失败还是取消都能把 copying 放开
                            val copied = try {
                                ImageClipboard.copy(
                                    context = context,
                                    model = source,
                                    diskCacheKey = sourceDiskCacheKey,
                                    workspaceFile = sourceWorkspaceFile,
                                )
                            } finally {
                                copying = false
                            }
                            // Android 13 起系统自带复制提示，更早版本才补一条
                            if (copied) {
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                                    Toast.makeText(
                                        context,
                                        R.string.chat_image_copy_success,
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                            } else {
                                Toast.makeText(
                                    context,
                                    R.string.chat_image_copy_failed,
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                    },
                )
            }
        }
    }

    if (showImageViewer) {
        ImagePreviewDialog(images = listOf(model ?: "")) {
            showImageViewer = false
        }
    }
}
