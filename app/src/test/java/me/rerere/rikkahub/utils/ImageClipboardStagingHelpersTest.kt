package me.rerere.rikkahub.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 目录归属判断：暂存目录与附件根目录的边界就是靠这两个函数守住的。
 *
 * [isInsideRoot] 会先把文件 canonical 化再比较，是外部路径的入口；
 * [isUnderRoot] 只 canonical 化根目录，供已经拿到 canonical 路径的调用方复用。
 */
class ImageClipboardStagingHelpersTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `inside root accepts the root itself and nested paths`() {
        val root = tempFolder.newFolder("upload")
        val nested = File(root, "nested/deeper/a.png").apply {
            parentFile?.mkdirs()
            writeText("synthetic")
        }

        assertTrue(isInsideRoot(root, root))
        assertTrue(isInsideRoot(nested, root))
        assertTrue(isInsideRoot(File(root, "nested"), root))
    }

    @Test
    fun `inside root rejects siblings sharing a name prefix`() {
        val root = tempFolder.newFolder("upload")
        val sibling = tempFolder.newFolder("upload-other")

        assertFalse(isInsideRoot(File(sibling, "a.png"), root))
        assertFalse(isInsideRoot(sibling, root))
        // 没有分隔符的公共前缀不能被当成子目录
        assertFalse(isInsideRoot(File("${root.absolutePath}extra"), root))
    }

    @Test
    fun `inside root rejects parents and unrelated directories`() {
        val root = tempFolder.newFolder("upload")
        val outside = tempFolder.newFolder("elsewhere")

        assertFalse(isInsideRoot(root.parentFile!!, root))
        assertFalse(isInsideRoot(outside, root))
    }

    @Test
    fun `inside root follows the canonical path`() {
        val root = tempFolder.newFolder("upload")
        val plain = File(root, "plain.png").apply { writeText("synthetic") }

        // 判定必须与直接把 canonical 结果传进去一致
        assertEqualsDecision(isInsideRoot(plain, root), isInsideRoot(requireNotNull(plain.canonicalFile), root))
        assertTrue(isInsideRoot(plain, root))
    }

    @Test
    fun `under root is a pure prefix check on an already canonical file`() {
        val root = tempFolder.newFolder("staging")
        val nested = File(root, "a/b.png").apply {
            parentFile?.mkdirs()
            writeText("synthetic")
        }

        assertTrue(isUnderRoot(root, root))
        assertTrue(isUnderRoot(nested, root))
        assertFalse(isUnderRoot(tempFolder.newFolder("outside"), root))
        assertFalse(isUnderRoot(File("${root.absolutePath}extra"), root))

        // 它不做 canonical 化, 所以带 .. 的原始路径必须先经过 isInsideRoot;
        // 直接把非 canonical 路径喂进来会得到错误答案,
        // 这正是调用方必须传 canonical 路径的原因
        val escaping = File(root, "../elsewhere/a.png")
        assertFalse(isInsideRoot(escaping, root))
        assertFalse(isUnderRoot(requireNotNull(escaping.canonicalFile), root))
    }

    @Test
    fun `trailing separator on the root does not widen the boundary`() {
        val root = tempFolder.newFolder("staging")
        val sibling = tempFolder.newFolder("staging-other")

        // 根目录路径带结尾分隔符时, 兄弟目录仍然不能被算进去
        assertFalse(isUnderRoot(File(sibling, "x.png"), File("${root.absolutePath}${File.separator}")))
        assertTrue(isUnderRoot(File(root, "x.png"), File("${root.absolutePath}${File.separator}")))
    }

    private fun assertEqualsDecision(actual: Boolean, expected: Boolean) {
        org.junit.Assert.assertEquals("decision must follow the canonical path", expected, actual)
    }
}
