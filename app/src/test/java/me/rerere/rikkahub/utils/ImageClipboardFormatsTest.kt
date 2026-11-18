package me.rerere.rikkahub.utils

import me.rerere.rikkahub.utils.imageclipboard.SyntheticPng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * 剪贴板图片的格式识别: 只看文件头签名, 不看后缀或 Content-Type.
 *
 * 全部是纯 JVM 逻辑, 不需要设备.
 */
class ImageClipboardFormatsTest {
    @Test
    fun `synthetic png fixture is detected as png`() {
        val bytes = SyntheticPng.bytes()

        assertEquals(ImageClipboardFormats.PNG, ImageClipboardFormats.detect(bytes))
        assertEquals("image/png", ImageClipboardFormats.detect(bytes)?.mimeType)
        assertEquals("png", ImageClipboardFormats.detect(bytes)?.extension)
    }

    @Test
    fun `jpeg webp gif heic and avif signatures are detected`() {
        assertEquals(
            ImageClipboardFormats.JPEG,
            ImageClipboardFormats.detect(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())),
        )
        assertEquals(
            ImageClipboardFormats.GIF,
            ImageClipboardFormats.detect("GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(4)),
        )
        assertEquals(
            ImageClipboardFormats.GIF,
            ImageClipboardFormats.detect("GIF87a".toByteArray(Charsets.US_ASCII) + ByteArray(4)),
        )
        assertEquals(
            ImageClipboardFormats.WEBP,
            ImageClipboardFormats.detect(
                "RIFF".toByteArray(Charsets.US_ASCII) +
                    ByteArray(4) +
                    "WEBPVP8 ".toByteArray(Charsets.US_ASCII)
            ),
        )
        assertEquals(ImageClipboardFormats.HEIC, ImageClipboardFormats.detect(isoBmff("heic")))
        assertEquals(ImageClipboardFormats.HEIC, ImageClipboardFormats.detect(isoBmff("mif1")))
        assertEquals(ImageClipboardFormats.AVIF, ImageClipboardFormats.detect(isoBmff("avif")))
        assertEquals(ImageClipboardFormats.AVIF, ImageClipboardFormats.detect(isoBmff("avis")))
    }

    @Test
    fun `non image bytes are rejected`() {
        assertNull(ImageClipboardFormats.detect(ByteArray(0)))
        assertNull(ImageClipboardFormats.detect("RikkaHub synthetic text fixture".toByteArray()))
        assertNull(ImageClipboardFormats.detect("%PDF-1.7".toByteArray()))
        assertNull(ImageClipboardFormats.detect("PK\u0003\u0004".toByteArray(Charsets.ISO_8859_1)))
        // 只有 PNG 的前 4 个字节, 不足以确认签名
        assertNull(ImageClipboardFormats.detect(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        // RIFF 但不是 WEBP
        assertNull(
            ImageClipboardFormats.detect(
                "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) + "WAVEfmt ".toByteArray(Charsets.US_ASCII)
            )
        )
        // ftyp 但品牌不是图片容器
        assertNull(ImageClipboardFormats.detect(isoBmff("isom")))
    }

    @Test
    fun `detect honours the offset and length window`() {
        val png = SyntheticPng.bytes()
        val padded = ByteArray(16) + png + ByteArray(16)

        assertEquals(ImageClipboardFormats.PNG, ImageClipboardFormats.detect(padded, offset = 16))
        assertEquals(
            ImageClipboardFormats.PNG,
            ImageClipboardFormats.detect(padded, offset = 16, length = 8),
        )
        // 窗口不含签名
        assertNull(ImageClipboardFormats.detect(padded, offset = 0, length = 16))
        assertNull(ImageClipboardFormats.detect(padded, offset = png.size + 16))
        assertNull(ImageClipboardFormats.detect(padded, offset = -1))
        assertNull(ImageClipboardFormats.detect(padded, offset = 0, length = 0))
    }

    @Test
    fun `data url round trips the synthetic fixture bytes`() {
        val bytes = SyntheticPng.bytes()
        val dataUrl = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)

        val decoded = ImageClipboardFormats.parseDataUrlImage(dataUrl)

        assertTrue(decoded != null && decoded.contentEquals(bytes))
        assertEquals(SyntheticPng.SHA256_HEX, SyntheticPng.sha256Hex(decoded!!))
        assertEquals(ImageClipboardFormats.PNG, ImageClipboardFormats.detect(decoded))
    }

    @Test
    fun `data url accepts whitespace and is case insensitive`() {
        val bytes = SyntheticPng.bytes()
        val encoded = Base64.getEncoder().encodeToString(bytes)
        val wrapped = encoded.chunked(40).joinToString("\n")

        val decoded = ImageClipboardFormats.parseDataUrlImage("DATA:Image/PNG;BASE64,$wrapped")

        assertTrue(decoded != null && decoded.contentEquals(bytes))
    }

    @Test
    fun `data url rejects non image and malformed payloads`() {
        assertNull(ImageClipboardFormats.parseDataUrlImage("data:text/plain;base64,aGVsbG8="))
        assertNull(ImageClipboardFormats.parseDataUrlImage("data:image/png,aGVsbG8=")) // not base64
        assertNull(ImageClipboardFormats.parseDataUrlImage("data:image/png;base64"))
        assertNull(ImageClipboardFormats.parseDataUrlImage("data:;base64,aGVsbG8="))
        assertNull(ImageClipboardFormats.parseDataUrlImage("data:image/png;base64,!!!not-base64!!!"))
        assertNull(ImageClipboardFormats.parseDataUrlImage("https://example.invalid/a.png"))
        assertNull(ImageClipboardFormats.parseDataUrlImage(""))
    }

    @Test
    fun `oversized data url payload is rejected instead of truncated`() {
        val oversized = "A".repeat(ImageClipboardFormats.MAX_BASE64_CHARS + 4)

        assertNull(ImageClipboardFormats.parseDataUrlImage("data:image/png;base64,$oversized"))
    }

    private fun isoBmff(brand: String): ByteArray =
        ByteArray(4) + "ftyp".toByteArray(Charsets.US_ASCII) + brand.toByteArray(Charsets.US_ASCII) + ByteArray(4)
}
