package me.rerere.rikkahub.utils

import java.util.Base64

/**
 * 剪贴板图片允许暂存的一种编码格式。
 *
 * 只保留常见、能被粘贴方稳定识别的图片格式；格式完全由文件头签名决定，
 * 不信任 URL 后缀或 `Content-Type`，避免把任意文件当图片共享出去。
 */
internal data class ImageClipboardFormat(
    val mimeType: String,
    val extension: String,
)

/**
 * 剪贴板图片的格式识别与体积限制，纯 JVM 逻辑，便于单元测试。
 */
internal object ImageClipboardFormats {
    /** 单张图片暂存上限，超过则放弃复制而不是截断。 */
    const val MAX_IMAGE_BYTES: Int = 16 * 1024 * 1024

    /** 能解码出 [MAX_IMAGE_BYTES] 的 base64 载荷长度上限。 */
    const val MAX_BASE64_CHARS: Int = (MAX_IMAGE_BYTES / 3 + 1) * 4

    /** PNG 签名 8 字节 + 完整的 IHDR 块（长度 4 + 类型 4 + 数据 13 + CRC 4）。 */
    private const val PNG_MIN_BYTES = 8 + 25

    val PNG = ImageClipboardFormat("image/png", "png")
    val JPEG = ImageClipboardFormat("image/jpeg", "jpg")
    val GIF = ImageClipboardFormat("image/gif", "gif")
    val WEBP = ImageClipboardFormat("image/webp", "webp")
    val HEIC = ImageClipboardFormat("image/heic", "heic")
    val AVIF = ImageClipboardFormat("image/avif", "avif")

    private val HEIC_BRANDS = setOf(
        "heic", "heix", "heim", "heis",
        "hevc", "hevx", "hevm", "hevs",
        "mif1", "msf1", "heif",
    )
    private val AVIF_BRANDS = setOf("avif", "avis")

    /**
     * 按文件头签名识别图片格式，无法识别时返回 null。
     *
     * [bytes] 可以是完整内容，也可以只是文件头；[offset]/[length] 限定有效区间。
     */
    fun detect(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset): ImageClipboardFormat? {
        if (length <= 0 || offset < 0 || offset >= bytes.size) return null
        val end = minOf(bytes.size, offset + length)
        val size = end - offset

        fun byteAt(index: Int): Int =
            if (index in 0 until size) bytes[offset + index].toInt() and 0xFF else -1

        fun matchesAscii(text: String, start: Int): Boolean {
            if (start < 0 || start + text.length > size) return false
            for (i in text.indices) {
                if (byteAt(start + i) != text[i].code) return false
            }
            return true
        }

        if (size >= 8 &&
            byteAt(0) == 0x89 && byteAt(1) == 0x50 && byteAt(2) == 0x4E && byteAt(3) == 0x47 &&
            byteAt(4) == 0x0D && byteAt(5) == 0x0A && byteAt(6) == 0x1A && byteAt(7) == 0x0A
        ) {
            return PNG
        }

        if (size >= 3 && byteAt(0) == 0xFF && byteAt(1) == 0xD8 && byteAt(2) == 0xFF) {
            return JPEG
        }

        if (matchesAscii("GIF87a", 0) || matchesAscii("GIF89a", 0)) {
            return GIF
        }

        if (size >= 12 && matchesAscii("RIFF", 0) && matchesAscii("WEBP", 8)) {
            return WEBP
        }

        // HEIF/AVIF 都是 ISO-BMFF 容器：字节 4..8 是 "ftyp"，8..12 是主品牌码
        if (size >= 12 && matchesAscii("ftyp", 4)) {
            val brand = buildString {
                for (i in 8 until 12) append(byteAt(i).toChar())
            }
            when (brand) {
                in HEIC_BRANDS -> return HEIC
                in AVIF_BRANDS -> return AVIF
            }
        }

        return null
    }

    /**
     * 暂存前的结构校验：签名正确之外，还要有最基本的容器头。
     *
     * [detect] 只按签名判定（展示逻辑和单元测试都依赖这一点），
     * 这里额外挡掉只剩几个字节的截断输入，避免把打不开的图片放进剪贴板。
     * 传入的可以是完整内容，也可以只是文件头。
     */
    fun hasCompleteHeader(
        format: ImageClipboardFormat,
        bytes: ByteArray,
        offset: Int = 0,
        length: Int = bytes.size - offset,
    ): Boolean {
        if (length <= 0 || offset < 0 || offset >= bytes.size) return false
        val size = minOf(bytes.size - offset, length)

        fun byteAt(index: Int): Int =
            if (index in 0 until size) bytes[offset + index].toInt() and 0xFF else -1

        fun matchesAscii(text: String, start: Int): Boolean {
            if (start < 0 || start + text.length > size) return false
            return text.indices.all { byteAt(start + it) == text[it].code }
        }

        return when (format) {
            // 签名之后必须紧跟完整的 IHDR 块：长度 13 + "IHDR"
            PNG -> size >= PNG_MIN_BYTES && matchesAscii("IHDR", 12) &&
                byteAt(8) == 0 && byteAt(9) == 0 && byteAt(10) == 0 && byteAt(11) == 13
            // SOI + 下一个标记段
            JPEG -> size >= 4
            // 6 字节文件头 + 7 字节逻辑屏幕描述符
            GIF -> size >= 13
            // RIFF 头 + "WEBP" + 首个 chunk 的 fourcc
            WEBP -> size >= 16
            // ftyp 盒的固定头：size(4) + "ftyp"(4) + 主品牌(4) + 次版本(4)
            HEIC, AVIF -> size >= 16
            else -> true
        }
    }

    /**
     * 解析 `data:image/...;base64,...`，载荷体积有上限，非法输入返回 null。
     */
    fun parseDataUrlImage(model: String): ByteArray? {
        if (!model.startsWith("data:", ignoreCase = true)) return null
        val headerEnd = model.indexOf(',')
        if (headerEnd <= 5) return null

        val header = model.substring(5, headerEnd)
        if (!header.endsWith(";base64", ignoreCase = true)) return null
        val mimeType = header.substringBefore(';').trim()
        if (!mimeType.startsWith("image/", ignoreCase = true)) return null

        val payload = model.substring(headerEnd + 1)
        if (payload.length > MAX_BASE64_CHARS) return null
        // 常见情况没有换行，直接解码，避免再复制一份载荷
        val compact = if (payload.any { it.isWhitespace() }) payload.filterNot { it.isWhitespace() } else payload
        if (compact.length > MAX_BASE64_CHARS) return null

        val decoded = runCatching { Base64.getDecoder().decode(compact) }.getOrNull() ?: return null
        if (decoded.size > MAX_IMAGE_BYTES) return null
        return decoded
    }
}
