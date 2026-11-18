package me.rerere.rikkahub.utils.imageclipboard

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.zip.Adler32
import java.util.zip.CRC32

/**
 * 内存里现做的合成 PNG, 用于剪贴板暂存/回读测试.
 *
 * 刻意不使用平台编码器 (Android 上是 `Bitmap.compress`, JVM 上是 ImageIO), 这样:
 * - 字节完全确定, [SHA256_HEX] 和 [SIZE_BYTES] 在 JVM 单测和仪器测试里都成立,
 *   可以拿它们互相校验两份构建器没有漂移;
 * - 文件里带 tEXt / iTXt 文本块, 只要实现把图片解码再重新编码, 这些块就会消失,
 *   字节也会变, 测试立刻能发现"原样字节"被破坏.
 *
 * 图片内容: [WIDTH] x [HEIGHT] 的 8-bit RGBA, 每个像素由坐标算出, 无外部素材.
 * IDAT 使用 zlib 的 stored 块 (不压缩), 避免 zlib 压缩结果随 JDK/Android 版本变化.
 */
internal object SyntheticPng {
    const val WIDTH = 3
    const val HEIGHT = 2

    const val SOFTWARE_KEYWORD = "Software"
    const val SOFTWARE_TEXT = "RikkaHub synthetic fixture"
    const val COMMENT_KEYWORD = "Comment"

    /** 非 ASCII 文本, 用来确认 iTXt 的 UTF-8 内容在暂存后仍然逐字节保留. */
    const val COMMENT_TEXT = "合成剪贴板测试图像 · synthetic"

    /** [bytes] 的 SHA-256, 由 JVM 与 Android 两侧测试共同断言, 证明生成器跨平台一致. */
    const val SHA256_HEX = "5bbb15a7e00a48c2fb147cccb0416cb27949c3fa6fd7ac65e22f716aa2cf936e"

    /** [bytes] 的长度, 与 [SHA256_HEX] 配合定位生成器漂移. */
    const val SIZE_BYTES = 205

    private val SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )

    /** 每次调用都返回一份新的字节数组, 调用方可以随意修改. */
    fun bytes(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.write(chunk("IHDR", ihdr()))
        out.write(chunk("tEXt", text(SOFTWARE_KEYWORD, SOFTWARE_TEXT)))
        out.write(chunk("iTXt", internationalText(COMMENT_KEYWORD, COMMENT_TEXT)))
        out.write(chunk("IDAT", zlibStored(scanlines())))
        out.write(chunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    fun sha256Hex(bytes: ByteArray = bytes()): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** 按 PNG 结构切出所有块, 同时校验每块的 CRC; 供断言块顺序和内容用. */
    fun chunks(bytes: ByteArray): List<PngChunk> {
        require(bytes.size > SIGNATURE.size) { "Not a PNG: too short" }
        require(bytes.copyOfRange(0, SIGNATURE.size).contentEquals(SIGNATURE)) {
            "Not a PNG: bad signature"
        }
        val chunks = mutableListOf<PngChunk>()
        var offset = SIGNATURE.size
        while (offset + 12 <= bytes.size) {
            val length = readIntBE(bytes, offset)
            require(length >= 0 && offset + 12 + length <= bytes.size) {
                "Truncated PNG chunk at offset $offset"
            }
            val type = String(bytes, offset + 4, 4, Charsets.US_ASCII)
            val data = bytes.copyOfRange(offset + 8, offset + 8 + length)
            val expectedCrc = readIntBE(bytes, offset + 8 + length).toLong() and 0xFFFFFFFFL
            val actualCrc = CRC32().apply {
                update(bytes, offset + 4, 4 + length)
            }.value
            require(expectedCrc == actualCrc) {
                "Bad CRC for chunk $type: expected $expectedCrc, actual $actualCrc"
            }
            chunks += PngChunk(type = type, data = data)
            offset += 12 + length
            if (type == "IEND") break
        }
        return chunks
    }

    /** tEXt / iTXt 的文本内容, 关键字不匹配时返回 null. */
    fun textOf(chunk: PngChunk, keyword: String): String? = when (chunk.type) {
        "tEXt" -> {
            val separator = chunk.data.indexOf(0.toByte())
            if (separator < 0) {
                null
            } else if (String(chunk.data, 0, separator, Charsets.ISO_8859_1) != keyword) {
                null
            } else {
                String(chunk.data, separator + 1, chunk.data.size - separator - 1, Charsets.ISO_8859_1)
            }
        }

        "iTXt" -> {
            val keywordEnd = chunk.data.indexOf(0.toByte())
            if (keywordEnd < 0 || String(chunk.data, 0, keywordEnd, Charsets.ISO_8859_1) != keyword) {
                null
            } else {
                // keyword \0 compressionFlag compressionMethod languageTag \0 translatedKeyword \0 text
                val compressionFlag = chunk.data[keywordEnd + 1].toInt() and 0xFF
                var cursor = skipUntilNul(chunk.data, keywordEnd + 3)
                cursor = skipUntilNul(chunk.data, cursor)
                require(compressionFlag == 0) { "Compressed iTXt is not supported by this fixture" }
                String(chunk.data, cursor, chunk.data.size - cursor, Charsets.UTF_8)
            }
        }

        else -> null
    }

    /** 每个像素的 ARGB 值, 与 [scanlines] 写入的内容一致. */
    fun expectedArgb(x: Int, y: Int): Int {
        val red = x * 80
        val green = y * 120
        val blue = (x + y) * 40
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun ihdr(): ByteArray = ByteArrayOutputStream().apply {
        writeIntBE(WIDTH)
        writeIntBE(HEIGHT)
        write(8) // bit depth
        write(6) // color type: RGBA
        write(0) // compression
        write(0) // filter
        write(0) // interlace
    }.toByteArray()

    private fun text(keyword: String, value: String): ByteArray =
        keyword.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + value.toByteArray(Charsets.ISO_8859_1)

    private fun internationalText(keyword: String, value: String): ByteArray = ByteArrayOutputStream().apply {
        write(keyword.toByteArray(Charsets.ISO_8859_1))
        write(0) // keyword terminator
        write(0) // compression flag: uncompressed
        write(0) // compression method
        write(0) // empty language tag + terminator
        write(0) // empty translated keyword + terminator
        write(value.toByteArray(Charsets.UTF_8))
    }.toByteArray()

    /** 每行前面加一个 filter 字节 0 (None), 再按 RGBA 顺序写像素. */
    private fun scanlines(): ByteArray {
        val rowSize = 1 + WIDTH * 4
        val raw = ByteArray(rowSize * HEIGHT)
        for (y in 0 until HEIGHT) {
            var index = y * rowSize
            raw[index++] = 0
            for (x in 0 until WIDTH) {
                val argb = expectedArgb(x, y)
                raw[index++] = ((argb shr 16) and 0xFF).toByte()
                raw[index++] = ((argb shr 8) and 0xFF).toByte()
                raw[index++] = (argb and 0xFF).toByte()
                raw[index++] = ((argb ushr 24) and 0xFF).toByte()
            }
        }
        return raw
    }

    /**
     * zlib 容器 + 单个 stored deflate 块.
     * stored 块的字节只取决于输入长度, 不依赖压缩器实现, 因此跨平台完全一致.
     */
    private fun zlibStored(raw: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        write(0x78) // CMF: deflate, 32K window
        write(0x01) // FLG: no dictionary, check bits
        var offset = 0
        do {
            val length = minOf(0xFFFF, raw.size - offset)
            val isLast = offset + length == raw.size
            write(if (isLast) 1 else 0) // BFINAL + BTYPE=00
            write(length and 0xFF)
            write((length ushr 8) and 0xFF)
            write(length.inv() and 0xFF)
            write((length.inv() ushr 8) and 0xFF)
            write(raw, offset, length)
            offset += length
        } while (offset < raw.size)
        val adler = Adler32().apply { update(raw) }.value
        writeIntBE(adler.toInt())
    }.toByteArray()

    private fun chunk(type: String, data: ByteArray): ByteArray {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        return ByteArrayOutputStream().apply {
            writeIntBE(data.size)
            write(typeBytes)
            write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            writeIntBE(crc.toInt())
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeIntBE(value: Int) {
        write((value ushr 24) and 0xFF)
        write((value ushr 16) and 0xFF)
        write((value ushr 8) and 0xFF)
        write(value and 0xFF)
    }

    private fun readIntBE(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    /** 跳过以 NUL 结尾的字段, 返回下一个字段的起点. */
    private fun skipUntilNul(bytes: ByteArray, start: Int): Int {
        val end = bytes.indexOf(0.toByte(), start)
        require(end >= 0) { "Malformed PNG text chunk: missing NUL terminator" }
        return end + 1
    }

    private fun ByteArray.indexOf(value: Byte, start: Int = 0): Int {
        for (index in start until size) {
            if (this[index] == value) return index
        }
        return -1
    }
}

internal data class PngChunk(
    val type: String,
    val data: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        other is PngChunk && type == other.type && data.contentEquals(other.data)

    override fun hashCode(): Int = 31 * type.hashCode() + data.contentHashCode()
}
