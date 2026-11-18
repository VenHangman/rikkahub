package me.rerere.rikkahub.utils

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileNotFoundException

/**
 * 只服务剪贴板暂存目录的最小 [FileProvider]。
 *
 * 清单里声明的 `image_clipboard_paths` 只映射 `cache/image_clipboard/`，
 * 这个子类再加一层校验：URI 必须是暂存目录里按约定生成的图片文件名、
 * 只能是已存在的普通文件、只能以只读方式打开。
 * provider 不导出，也不给任何包授予权限，读权限由平台剪贴板在粘贴时按需授予。
 *
 * 继承来的 [FileProvider] 允许带写权限的调用方直接删文件，
 * 这里显式拒绝删除，并把 [query] 收窄成只回显暂存文件自己的名字和大小。
 */
class ImageClipboardFileProvider : FileProvider() {
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val appContext = context ?: throw FileNotFoundException("No provider context")
        // 暂存图片只读，写模式一律拒绝
        if (mode != "r") throw FileNotFoundException("Unsupported clipboard image mode")
        val file = resolveStagedFile(appContext, uri)
            ?: throw FileNotFoundException("Unsupported clipboard image")
        // O_NOFOLLOW: 校验之后即使文件名被换成符号链接也打不开
        return ImageClipboard.openReadOnlyNoFollow(file)
            ?: throw FileNotFoundException("Unsupported clipboard image")
    }

    /** 类型完全由暂存文件的扩展名决定，扩展名只可能来自 [ImageClipboardFormats]。 */
    override fun getType(uri: Uri): String {
        val appContext = context ?: return OCTET_STREAM
        val file = resolveStagedFile(appContext, uri) ?: return OCTET_STREAM
        return mimeTypeForExtension(file.extension) ?: OCTET_STREAM
    }

    /**
     * 继承的 [FileProvider.delete] 只要拿到写权限就会真的删掉文件；
     * 剪贴板只需要只读访问，这里一律拒绝，返回 0 行受影响。
     */
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int = 0

    /**
     * 只回显暂存文件自己的 [OpenableColumns.DISPLAY_NAME] 与 [OpenableColumns.SIZE]。
     *
     * 请求的列里一个都不支持时返回空结果集（列名按请求回显），
     * 而不是伪造别的列，免得调用方按下标取值取错。
     * URI 不在暂存目录时与 [openFile] 一样抛 [FileNotFoundException]。
     */
    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?,
    ): Cursor {
        val appContext = context ?: throw FileNotFoundException("No provider context")
        val file = resolveStagedFile(appContext, uri)
            ?: throw FileNotFoundException("Unsupported clipboard image")
        val supported = DEFAULT_QUERY_COLUMNS.toSet()
        val requested = projection?.toList() ?: DEFAULT_QUERY_COLUMNS.toList()
        val columns = requested.filter { it in supported }
        val cursor = MatrixCursor(columns.toTypedArray(), if (columns.isEmpty()) 0 else 1)
        if (columns.isEmpty()) return cursor
        cursor.addRow(
            columns.map { column ->
                when (column) {
                    OpenableColumns.DISPLAY_NAME -> file.name
                    else -> file.length()
                }
            }
        )
        return cursor
    }

    companion object {
        /** 暂存子目录名，同时也是 provider 路径配置里的第一段。 */
        const val STAGED_DIR_NAME = "image_clipboard"

        private const val AUTHORITY_SUFFIX = ".image_clipboard"

        private const val OCTET_STREAM = "application/octet-stream"

        private val DEFAULT_QUERY_COLUMNS = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)

        private const val STAGED_NAME_PATTERN =
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(png|jpg|gif|webp|heic|avif)"

        private val stagedNameRegex = Regex(STAGED_NAME_PATTERN)

        /** 与清单中的 `${applicationId}.image_clipboard` 一致。 */
        fun authority(context: Context): String = "${context.packageName}$AUTHORITY_SUFFIX"

        /**
         * 为暂存文件生成 content URI；文件不在暂存目录内时返回 null。
         */
        fun stagedUri(context: Context, file: File): Uri? {
            val stagedDir = ImageClipboard.stagingDir(context)
            if (!file.isFile || !isInsideRoot(file, stagedDir)) return null
            if (!isStagedFileName(file.name)) return null
            return runCatching {
                FileProvider.getUriForFile(context, authority(context), file)
            }.getOrNull()
        }

        /**
         * 把 provider URI 还原成暂存文件，只接受 `content://<authority>/image_clipboard/<uuid>.<ext>`。
         */
        fun resolveStagedFile(context: Context, uri: Uri): File? {
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
            if (uri.authority != authority(context)) return null
            val segments = uri.pathSegments ?: return null
            if (segments.size != 2 || segments[0] != STAGED_DIR_NAME) return null
            val name = segments[1].takeIf { isStagedFileName(it) } ?: return null
            val stagedDir = ImageClipboard.stagingDir(context)
            val candidate = File(stagedDir, name)
            if (!isInsideRoot(candidate, stagedDir)) return null
            return candidate.takeIf { it.isFile }
        }

        /** 只接受 [ImageClipboard] 生成的随机文件名，杜绝任意路径片段。 */
        fun isStagedFileName(name: String): Boolean = stagedNameRegex.matches(name)

        private fun mimeTypeForExtension(extension: String): String? = when (extension.lowercase()) {
            ImageClipboardFormats.PNG.extension -> ImageClipboardFormats.PNG.mimeType
            ImageClipboardFormats.JPEG.extension -> ImageClipboardFormats.JPEG.mimeType
            ImageClipboardFormats.GIF.extension -> ImageClipboardFormats.GIF.mimeType
            ImageClipboardFormats.WEBP.extension -> ImageClipboardFormats.WEBP.mimeType
            ImageClipboardFormats.HEIC.extension -> ImageClipboardFormats.HEIC.mimeType
            ImageClipboardFormats.AVIF.extension -> ImageClipboardFormats.AVIF.mimeType
            else -> null
        }
    }
}
