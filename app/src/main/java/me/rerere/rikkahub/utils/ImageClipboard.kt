package me.rerere.rikkahub.utils

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PersistableBundle
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import coil3.SingletonImageLoader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.files.FileFolders
import okio.FileSystem
import okio.buffer

/**
 * 把聊天里显示的图片复制到系统剪贴板。
 *
 * 约定：
 * - 只暂存原始编码字节，不做任何重新压缩，也不发起任何网络请求；
 * - 暂存文件名随机生成，写入私有缓存子目录，
 *   经专用 [ImageClipboardFileProvider] 以只读 content URI 暴露；
 * - HTTP(S) 图片只命中界面上那次请求已落盘的 Coil 磁盘快照，快照不存在时直接失败；
 * - 只读取用户显式授权或应用自己拥有的附件目录，
 *   拒绝其它应用私有数据、缓存目录与工作区外路径；
 * - 应用自己的 `content://` 只认宽泛 FileProvider 里映射到附件目录的路径，
 *   `file_paths.xml` 暴露的缓存与外部目录不算授权；
 * - 文件一律以 `O_NOFOLLOW` 打开，校验之后才不会被换成符号链接；
 * - 暂存目录只在复制成功之后按预算清理，失败的复制不动上一次剪贴板用到的文件；
 * - 失败一律返回 false，不打印日志、不改动已有剪贴板内容；协程取消照常抛出。
 */
internal object ImageClipboard {
    private const val CLIP_LABEL = "image"

    /** FilesManager.getImagesDir() 使用的目录名（FilesManager 里是字面量）。 */
    private const val IMAGES_DIR_NAME = "images"

    /** 清单里宽泛 FileProvider 的 authority 后缀（`${applicationId}.fileprovider`）。 */
    private const val BROAD_PROVIDER_SUFFIX = ".fileprovider"

    /**
     * `res/xml/file_paths.xml` 里 `<files-path name="upload" path="."/>` 的标签。
     * 与 [FileFolders.UPLOAD] 同名但含义不同：那个是 filesDir 下的附件目录。
     */
    private const val OWN_FILES_PATH_TAG = "upload"

    /** 工作区里的符号链接不能把应用私有数据带进剪贴板。 */
    private val PRIVATE_DATA_DIRS = listOf("databases", "shared_prefs", "no_backup", "app_webview")

    private const val STAGED_KEEP_MILLIS = 7L * 24 * 60 * 60 * 1000

    /** 暂存目录整体上限，按字节而不是文件数，避免 32 × 16 MiB 撑到 512 MiB。 */
    private const val STAGED_MAX_BYTES = 64L * 1024 * 1024

    private const val STAGED_MAX_FILES = 32

    /** 串行化复制，避免并发清理掉正在使用的暂存文件或让旧请求覆盖新内容。 */
    private val copyMutex = Mutex()

    /** 读文件头用的字节数，够识别 HEIF 的 ftyp 品牌码。 */
    private const val HEADER_BYTES = 64

    private const val TEMP_SUFFIX = ".part"

    /** 已暂存、可直接交给剪贴板的图片。 */
    internal data class StagedImage(
        val file: File,
        val format: ImageClipboardFormat,
    )

    /**
     * 把 [model] 指向的图片写入系统剪贴板。
     *
     * [model] 支持 `http(s)://`（要求 [diskCacheKey] 命中 Coil 磁盘快照）、`data:image`、
     * 用户已授权的 `content://`、应用附件目录内的 `file://`/绝对路径。
     * [workspaceFile] 只应由工作区 scoped resolver 传入，优先于 [model]。
     *
     * 整个过程串行执行：并发复制不会互相清理暂存文件，
     * 也不会让较旧的请求覆盖较新的剪贴板内容。
     * 只有真正写进剪贴板之后才清理旧的暂存文件，失败路径不碰上一次的剪贴板内容。
     *
     * @return 平台剪贴板成功写入时为 true。
     */
    internal suspend fun copy(
        context: Context,
        model: String,
        diskCacheKey: String? = null,
        workspaceFile: File? = null,
    ): Boolean {
        val appContext = context.applicationContext ?: context
        return try {
            copyMutex.withLock {
                withContext(Dispatchers.IO) {
                    ensureActive()
                    val staged = stageModel(appContext, model, diskCacheKey, workspaceFile)
                        ?: return@withContext false
                    // 交给平台之前失败（含取消）就删掉暂存文件；
                    // 写入成功后保留，供粘贴使用
                    var published = false
                    try {
                        val clip = buildClip(appContext, staged.file, staged.format)
                            ?: return@withContext false
                        val clipboard =
                            appContext.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            ?: return@withContext false
                        ensureActive()
                        clipboard.setPrimaryClip(clip)
                        published = true
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // 平台仍持有旧内容，暂存文件没有存在意义
                        false
                    } finally {
                        if (published) {
                            // 发布成功之后才按预算清理；刚发布的那张永远保留
                            runCatching { pruneStagedFiles(appContext, keep = staged.file) }
                        } else {
                            staged.file.delete()
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 把 [model] 解析成暂存文件，供 [copy] 与测试共用。
     * 无法安全读取或格式不受支持时返回 null。
     */
    internal suspend fun stageModel(
        context: Context,
        model: String,
        diskCacheKey: String? = null,
        workspaceFile: File? = null,
    ): StagedImage? {
        val appContext = context.applicationContext ?: context
        return withContext(Dispatchers.IO) {
            // 大文件按块拷贝时也要能及时响应取消，取消照常抛出并删掉临时文件
            val job = coroutineContext[Job]
            val checkActive: () -> Unit = { job?.ensureActive() }
            if (workspaceFile != null) {
                val file = resolveWorkspaceFile(appContext, workspaceFile) ?: return@withContext null
                stageFromStream(appContext, open = { openNoFollow(file) }, checkActive = checkActive)
            } else {
                val trimmed = model.trim()
                when {
                    trimmed.isEmpty() -> null

                    trimmed.startsWith("data:", ignoreCase = true) -> {
                        val bytes = ImageClipboardFormats.parseDataUrlImage(trimmed)
                            ?: return@withContext null
                        stageFromBytes(appContext, bytes)
                    }

                    trimmed.startsWith("http://", ignoreCase = true) ||
                        trimmed.startsWith("https://", ignoreCase = true) ->
                        stageCoilSnapshot(appContext, diskCacheKey, checkActive)

                    trimmed.startsWith("content://", ignoreCase = true) -> {
                        val opener = contentUriOpener(appContext, trimmed)
                        stageFromStream(appContext, open = opener, checkActive = checkActive)
                    }

                    trimmed.startsWith("file://", ignoreCase = true) ->
                        stageOwnedFile(appContext, fileFromUrl(trimmed), checkActive)

                    trimmed.startsWith("/") -> stageOwnedFile(appContext, File(trimmed), checkActive)

                    else -> null
                }
            }
        }
    }

    /**
     * 把 [bytes] 原样写入新的暂存文件；[format] 为 null 时按文件头识别。
     * 文件名随机生成且不覆盖已有文件，写入后设为只读。
     */
    internal fun stageFromBytes(
        context: Context,
        bytes: ByteArray,
        format: ImageClipboardFormat? = null,
    ): StagedImage? {
        val detected = ImageClipboardFormats.detect(bytes) ?: return null
        // 声明的格式必须和文件头一致，避免用错扩展名共享出去
        if (format != null && format != detected) return null
        if (bytes.isEmpty() || bytes.size > ImageClipboardFormats.MAX_IMAGE_BYTES) return null
        return stageFromStream(context, open = { ByteArrayInputStream(bytes) })
    }

    /**
     * 暂存目录：私有缓存下的独立子目录，
     * 不复用现有 FileProvider 的宽泛映射，也不作为输入根。
     */
    internal fun stagingDir(context: Context): File =
        File(context.cacheDir, ImageClipboardFileProvider.STAGED_DIR_NAME)

    /** 应用自己拥有的图片附件目录，与 [FileFolders] / FilesManager.getImagesDir() 保持一致。 */
    internal fun ownedAttachmentRoots(context: Context): List<File> = listOf(
        File(context.filesDir, FileFolders.UPLOAD),
        File(context.filesDir, IMAGES_DIR_NAME),
        File(context.filesDir, FileFolders.MEDIA_CREATION),
        File(context.filesDir, FileFolders.TOOL_OUTPUTS),
    )

    /** 组装剪贴板条目：精确 MIME + 敏感标记，避免系统预览泄漏内容。 */
    internal fun buildClip(context: Context, file: File, format: ImageClipboardFormat): ClipData? {
        val uri = ImageClipboardFileProvider.stagedUri(context, file) ?: return null
        val description = ClipDescription(CLIP_LABEL, arrayOf(format.mimeType))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        return ClipData(description, ClipData.Item(uri))
    }

    /**
     * 清理过期或超出预算的暂存文件，给粘贴方留出足够时间。
     *
     * - 只对已提交（非 `.part`）的暂存文件计算数量和体积；
     * - 最近一次暂存的文件永远保留，保证刚复制的图片还能粘贴；
     * - [keep] 显式保护当前剪贴板正在使用的文件，不依赖修改时间的排序；
     * - 预算为 [maxBytes]（默认 64 MiB）与 [maxFiles] 中的先到者，
     *   避免按「文件数 × 单张上限」膨胀到几百 MiB；
     * - `.part` 只在明显过期（超过 [keepMillis]）时才删，避免误删正在写入的临时文件。
     */
    internal fun pruneStagedFiles(
        context: Context,
        now: Long = System.currentTimeMillis(),
        keepMillis: Long = STAGED_KEEP_MILLIS,
        maxBytes: Long = STAGED_MAX_BYTES,
        maxFiles: Int = STAGED_MAX_FILES,
        keep: File? = null,
    ) {
        val files = stagingDir(context).listFiles()?.filter { it.isFile } ?: return
        if (files.isEmpty()) return

        val committed = files.filterNot { it.name.endsWith(TEMP_SUFFIX) }.sortedBy { it.lastModified() }
        files.filter { it.name.endsWith(TEMP_SUFFIX) }.forEach { part ->
            if (now - part.lastModified() > keepMillis) runCatching { part.delete() }
        }
        if (committed.isEmpty()) return

        val keepPath = keep?.absolutePath
        var keptBytes = 0L
        var keptCount = 0
        for (file in committed.asReversed()) {
            val newest = file === committed.last()
            val protected = keepPath != null && file.absolutePath == keepPath
            val size = file.length()
            val expired = now - file.lastModified() > keepMillis
            val fits = keptCount + 1 <= maxFiles && keptBytes + size <= maxBytes
            if (newest || protected || (!expired && fits)) {
                keptCount += 1
                keptBytes += size
            } else {
                runCatching { file.delete() }
            }
        }
    }

    /**
     * 读满 [length] 字节或读到 EOF，返回实际读到的字节数。
     * 流返回 0 时退化成单字节读，避免在行为异常的 provider 上死循环。
     */
    private fun readFully(source: InputStream, buffer: ByteArray, offset: Int, length: Int): Int {
        var total = 0
        while (total < length) {
            val read = source.read(buffer, offset + total, length - total)
            if (read < 0) break
            if (read > 0) {
                total += read
                continue
            }
            val single = source.read()
            if (single < 0) break
            buffer[offset + total] = single.toByte()
            total += 1
        }
        return total
    }

    /**
     * 流式写入暂存目录：先读文件头确认格式，再按块拷贝剩余内容，只占一个读缓冲。
     * 任何失败（含取消）都会删掉临时文件。
     *
     * 这里不清理旧暂存文件：清理只在复制成功之后由 [copy] 触发，
     * 这样失败的复制不会影响上一次剪贴板正在使用的文件。
     */
    internal fun stageFromStream(
        context: Context,
        open: (() -> InputStream?)?,
        checkActive: (() -> Unit)? = null,
    ): StagedImage? {
        if (open == null) return null
        val dir = stagingDir(context)
        if (!dir.isDirectory && !dir.mkdirs()) return null

        val temp = File(dir, "${UUID.randomUUID()}$TEMP_SUFFIX")
        var staged: StagedImage? = null
        try {
            val input = open() ?: return null
            input.use { source ->
                val header = ByteArray(HEADER_BYTES)
                val headerSize = readFully(source, header, 0, HEADER_BYTES)
                val format = ImageClipboardFormats.detect(header, 0, headerSize) ?: return null
                // 只剩签名的截断输入不算图片
                if (!ImageClipboardFormats.hasCompleteHeader(format, header, 0, headerSize)) return null

                var total = headerSize.toLong()
                if (total > ImageClipboardFormats.MAX_IMAGE_BYTES) return null
                FileOutputStream(temp).use { output ->
                    output.write(header, 0, headerSize)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkActive?.invoke()
                        val read = readFully(source, buffer, 0, buffer.size)
                        if (read <= 0) break
                        total += read
                        if (total > ImageClipboardFormats.MAX_IMAGE_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }

                val target = File(dir, "${UUID.randomUUID()}.${format.extension}")
                if (!temp.renameTo(target)) return null
                if (!target.isFile || target.length() != total) {
                    target.delete()
                    return null
                }
                // 暂存后不再改写内容，只由清理逻辑删除
                target.setReadOnly()
                staged = StagedImage(target, format)
            }
            return staged
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        } finally {
            if (staged == null && temp.exists()) temp.delete()
        }
    }

    private fun stageOwnedFile(
        context: Context,
        file: File?,
        checkActive: (() -> Unit)? = null,
    ): StagedImage? {
        val owned = resolveOwnedFile(context, file) ?: return null
        return stageFromStream(context, open = { openNoFollow(owned) }, checkActive = checkActive)
    }

    /**
     * 应用附件目录里的文件：只算一次 canonical 路径，校验后原样打开这个路径，
     * 不再对目标重新 canonical，避免校验与打开之间被替换。
     */
    private fun resolveOwnedFile(context: Context, file: File?): File? {
        if (file == null) return null
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return null
        // canonical 已经解析过，前缀比较只解析根，不再对目标二次 canonical
        if (ownedAttachmentRoots(context).none { isUnderRoot(canonical, it) }) return null
        if (!canonical.isFile) return null
        return canonical
    }

    /**
     * 工作区文件由调用方经 scoped resolver 解析后显式传入。
     *
     * 这里只做兜底：拒绝解析后发生变化（含符号链接、相对段）的路径，
     * 拒绝应用私有数据目录与缓存目录，并以 no-follow 方式打开，
     * 避免校验之后把链接换成别的文件。
     */
    private fun resolveWorkspaceFile(context: Context, workspaceFile: File): File? {
        val canonical = runCatching { workspaceFile.canonicalFile }.getOrNull() ?: return null
        if (canonical.path != workspaceFile.path) return null
        if (isUnderRoot(canonical, context.cacheDir)) return null
        if (PRIVATE_DATA_DIRS.any { isUnderRoot(canonical, File(context.dataDir, it)) }) return null
        if (!canonical.isFile) return null
        return canonical
    }

    /**
     * 只打开平台已经授权给本进程的 `content://`
     * （持久授权与本次 Intent 的临时授权都算），
     * 外加本应用宽泛 FileProvider 里指向附件目录的那部分。
     *
     * 自己 provider 的 URI 没有 URI 授权记录（同 uid 直接可读），
     * 所以不能靠 `checkUriPermission` 判断，而是把路径还原成文件后按
     * [ownedAttachmentRoots] 做 canonical 包含性校验：`file_paths.xml` 暴露了
     * 整个 files/cache/external-files，剪贴板只认附件目录，其余（尤其缓存）一律拒绝。
     * 其它自己声明但没授权的 authority 也不放行。
     *
     * `getType` 只当提示：不少 provider 返回 null 或 `application/octet-stream`，
     * 但字节其实可读、界面也能正常渲染；真正的格式判定交给文件头签名。
     * 只有 provider 明确声明为非图片（如视频、PDF）时才提前拒绝。
     */
    private fun contentUriOpener(context: Context, raw: String): (() -> InputStream?)? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val authority = uri.authority ?: return null
        // 剪贴板暂存目录不能再作为输入源，否则暂存文件会被反复喂回剪贴板
        if (authority == ImageClipboardFileProvider.authority(context)) return null

        if (runCatching { hasReadGrant(context, uri) }.getOrDefault(false)) {
            val declaredType = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            if (declaredType != null && declaresNonImage(declaredType)) return null
            return { runCatching { context.contentResolver.openInputStream(uri) }.getOrNull() }
        }

        // 没有授权：只认自己 provider 里落在附件目录内的文件，直接用 no-follow 读
        val owned = ownAttachmentFile(context, uri) ?: return null
        return { openNoFollow(owned) }
    }

    private fun hasReadGrant(context: Context, uri: Uri): Boolean =
        context.checkUriPermission(
            uri,
            Process.myPid(),
            Process.myUid(),
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * 把本应用宽泛 FileProvider 的 URI 还原成附件目录里的 canonical 文件。
     *
     * [Uri.getPathSegments] 给的是解码后的段；FileProvider 用 `Uri.encode(path, "/")`
     * 生成路径，所以合法 URI 的段里不会出现 `/`，出现就说明不是它生成的。
     * 段里的 `..`、空段同样拒绝，最后仍靠 [resolveOwnedFile] 的 canonical 校验兜底。
     */
    private fun ownAttachmentFile(context: Context, uri: Uri): File? {
        if (uri.authority != "${context.packageName}$BROAD_PROVIDER_SUFFIX") return null
        val segments = uri.pathSegments ?: return null
        // file_paths.xml 里 `<files-path name="upload" path="."/>` 映射整个 filesDir；
        // cache-path / external-files-path 的标签不匹配，直接拒绝
        if (segments.size < 2 || segments[0] != OWN_FILES_PATH_TAG) return null
        val parts = segments.drop(1)
        if (parts.any { !isPlainPathSegment(it) }) return null
        val candidate = parts.fold(context.filesDir) { dir, part -> File(dir, part) }
        return resolveOwnedFile(context, candidate)
    }

    /** FileProvider 生成的单个路径段：非空、不是相对段、解码后不含分隔符或 NUL。 */
    private fun isPlainPathSegment(segment: String): Boolean =
        segment.isNotEmpty() &&
            segment != "." &&
            segment != ".." &&
            segment.none { it == '/' || it == '\\' || it == '\u0000' }

    /** provider 明确声明了非图片类型；通配符与 `application/octet-stream` 视为未知。 */
    private fun declaresNonImage(mimeType: String): Boolean {
        val normalized = mimeType.substringBefore(';').trim().lowercase()
        if (normalized.isEmpty() || normalized == "*/*") return false
        if (normalized == "application/octet-stream" || normalized == "binary/octet-stream") return false
        return !normalized.startsWith("image/")
    }

    /**
     * 读取界面上那次请求已经落盘的 Coil 快照，不做网络回源。
     * [diskCacheKey] 来自展示结果的 `SuccessResult.diskCacheKey`，未命中缓存时返回 null。
     */
    private suspend fun stageCoilSnapshot(
        context: Context,
        diskCacheKey: String?,
        checkActive: (() -> Unit)? = null,
    ): StagedImage? {
        val key = diskCacheKey?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val diskCache = runCatching { SingletonImageLoader.get(context).diskCache }.getOrNull() ?: return null
        val snapshot = try {
            diskCache.openSnapshot(key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: return null

        return snapshot.use { open ->
            stageFromStream(
                context,
                open = {
                    runCatching { FileSystem.SYSTEM.source(open.data).buffer().inputStream() }.getOrNull()
                },
                checkActive = checkActive,
            )
        }
    }

    /**
     * 以 `O_NOFOLLOW` 打开只读描述符，最后一段是符号链接时直接失败。
     *
     * 不用 `Files.newInputStream(NOFOLLOW_LINKS)`：Android 上这条 nio 路径对
     * `NOFOLLOW_LINKS` 的支持没有保证，而 `Os.open` 的 `O_NOFOLLOW` 是内核语义。
     *
     * 打开时带 `O_NONBLOCK` 再 `fstat` 确认是普通文件：校验与打开之间被换成 FIFO
     * 时，`O_RDONLY` 会一直等到对端写入，阻塞 IO 线程。不是普通文件就直接关掉。
     * 普通文件上 `O_NONBLOCK` 对读写没有影响，仍然清掉再交给读取方。
     *
     * [ParcelFileDescriptor.dup] 复制一份 fd 交给返回值，原始 fd 立刻关掉，
     * 这样调用方只持有唯一的关闭责任，不会出现同一个 fd 被关两次。
     */
    internal fun openReadOnlyNoFollow(file: File): ParcelFileDescriptor? {
        val fd = openNoFollowFd(file) ?: return null
        return try {
            ParcelFileDescriptor.dup(fd)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { Os.close(fd) }
        }
    }

    /**
     * 打开一个普通文件的只读 fd；符号链接、FIFO、设备节点、目录都返回 null。
     * 所有权随返回值转移，调用方负责关闭。
     */
    private fun openNoFollowFd(file: File): FileDescriptor? {
        val fd = runCatching {
            Os.open(
                file.path,
                OsConstants.O_RDONLY or
                    OsConstants.O_CLOEXEC or
                    OsConstants.O_NOFOLLOW or
                    OsConstants.O_NONBLOCK,
                0,
            )
        }.getOrNull() ?: return null
        var accepted = false
        try {
            // FIFO 在 O_RDONLY 下会等对端，先确认类型再决定是否读取
            if (!OsConstants.S_ISREG(Os.fstat(fd).st_mode)) return null
            runCatching {
                val flags = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, flags and OsConstants.O_NONBLOCK.inv())
            }
            accepted = true
            return fd
        } catch (e: ErrnoException) {
            return null
        } finally {
            if (!accepted) runCatching { Os.close(fd) }
        }
    }

    private fun openNoFollow(file: File): InputStream? =
        openNoFollowFd(file)?.let { NoFollowInputStream(it) }

    private fun fileFromUrl(raw: String): File? {
        val uri = runCatching { Uri.parse(raw) }.getOrNull() ?: return null
        val path = uri.path?.takeIf { it.isNotBlank() } ?: return null
        return File(path)
    }
}

/**
 * 只读文件描述符流。
 *
 * 直接持有 [FileDescriptor]，关闭时用 [Os.close] 只关一次：
 * `FileInputStream` + `ParcelFileDescriptor` 组合会在 `close()` 里重复关闭同一个 fd，
 * 号码被复用时可能误关别的线程刚打开的文件。
 */
private class NoFollowInputStream(
    private val fd: FileDescriptor,
) : InputStream() {
    private var closed = false
    private val single = ByteArray(1)

    override fun read(): Int {
        val read = read(single, 0, 1)
        return if (read <= 0) -1 else single[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (closed) throw IOException("Stream closed")
        if (off < 0 || len < 0 || off + len > b.size) throw IndexOutOfBoundsException()
        if (len == 0) return 0
        while (true) {
            try {
                val read = Os.read(fd, b, off, len)
                return if (read == 0) -1 else read
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue
                throw IOException("Failed to read image file", e)
            } catch (e: InterruptedIOException) {
                // 被信号打断，文件读没有副作用，重试即可
                continue
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { Os.close(fd) }
    }
}

/**
 * [file] 解析符号链接后是否仍在 [root] 之内。
 */
internal fun isInsideRoot(file: File, root: File): Boolean {
    val canonicalFile = runCatching { file.canonicalFile }.getOrNull() ?: return false
    return isUnderRoot(canonicalFile, root)
}

/**
 * [canonicalFile] 已经是 canonical 路径时用它，避免对目标再做一次符号链接解析。
 * 只解析 [root]。
 */
internal fun isUnderRoot(canonicalFile: File, root: File): Boolean {
    val canonicalRoot = runCatching { root.canonicalFile }.getOrNull() ?: return false
    val rootPath = canonicalRoot.path.trimEnd(File.separatorChar)
    val filePath = canonicalFile.path
    return filePath == rootPath || filePath.startsWith(rootPath + File.separator)
}
