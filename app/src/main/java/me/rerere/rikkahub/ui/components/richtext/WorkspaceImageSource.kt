package me.rerere.rikkahub.ui.components.richtext

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import kotlinx.coroutines.CancellationException
import me.rerere.rikkahub.data.repository.WorkspaceRepository
import me.rerere.rikkahub.ui.context.LocalWorkspaceId
import org.koin.compose.koinInject
import java.io.File

/**
 * 一张 Markdown 图片的渲染结果。
 *
 * [model] 交给 Coil: 工作区图片为解析出的宿主机绝对路径, 其余情况为原始 url, 解析中为 null (占位图)。
 * [workspaceFile] 只在当前活动工作区解析成功时给出, 与 [model] 同源, 不会是上一次解析的残留。
 */
internal data class WorkspaceImageRenderModel(
    val model: String?,
    val workspaceFile: File? = null,
)

/**
 * 把 Markdown 里的 Rootfs 绝对路径 (如 `/workspace/out.png`) 解析成当前活动工作区里的真实文件。
 *
 * 只在 [LocalWorkspaceId] 非空且 url 是单个 `/` 开头的绝对路径时解析; 相对路径和 `//host/x.png`
 * 原样交给 Coil。定位完全交给 [WorkspaceRepository.resolveAbsoluteFile] (canonical 包含性校验、
 * `..` 归一化、内核伪文件系统拒绝都在后端), 这里不复制规则, 也不扩挂载或跑 shell。
 * 文件已删除、不可读、工作区已删除或远程工作区没有本地落点时回退到原始 url, 不抛异常。
 */
@Composable
internal fun rememberWorkspaceImageRenderModel(url: String): WorkspaceImageRenderModel {
    val workspaceId = LocalWorkspaceId.current
    val path = remember(url) { url.workspaceAbsolutePathOrNull() }
    if (workspaceId == null || path == null) return WorkspaceImageRenderModel(model = url)
    // 必须用 key 重建状态: produceState 的 initialValue 存在不带 key 的 remember 里,
    // 仅靠它的 key 参数只重启 LaunchedEffect, 切换工作区或地址时会先沿用上一个 File。
    return key(workspaceId, url) {
        ResolvedWorkspaceImage(workspaceId = workspaceId, path = path, url = url)
    }
}

@Composable
private fun ResolvedWorkspaceImage(
    workspaceId: String,
    path: String,
    url: String,
): WorkspaceImageRenderModel {
    val workspaceRepository: WorkspaceRepository = koinInject()
    val resolved by produceState<WorkspaceImageRenderModel?>(initialValue = null, workspaceId, path) {
        // 取消要原样抛出, 否则旧解析会覆盖新状态
        val file = try {
            workspaceRepository.resolveAbsoluteFile(workspaceId, path)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
        value = if (file != null) {
            WorkspaceImageRenderModel(model = file.absolutePath, workspaceFile = file)
        } else {
            WorkspaceImageRenderModel(model = url)
        }
    }
    return resolved ?: WorkspaceImageRenderModel(model = null)
}

/** Markdown 解析器会百分号编码 src, 这里取解码后的 path; 非工作区绝对路径返回 null。 */
private fun String.workspaceAbsolutePathOrNull(): String? {
    if (!startsWith("/") || startsWith("//")) return null
    val decoded = runCatching { toUri().path }.getOrNull() ?: return null
    if (!decoded.startsWith("/") || decoded.length <= 1) return null
    if (decoded.any { it == '\u0000' || it.isISOControl() }) return null
    return decoded
}
