package site.xmpp.greenthumb.core.platform

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ListDirectoryJvmTest {

    @Test
    fun listsDirsFirstWithoutHiddenEntries() {
        val dir = File(System.getProperty("java.io.tmpdir"), "gt-listdir-${System.nanoTime()}")
        dir.mkdirs()
        try {
            File(dir, "b.txt").createNewFile()
            File(dir, "a.txt").createNewFile()
            File(dir, "adir").mkdirs()
            File(dir, ".hidden").createNewFile()

            val entries = listDirectory(dir.absolutePath)
            assertEquals(listOf("adir", "a.txt", "b.txt"), entries.map { it.name }, "каталоги первыми, без точечных")
            assertTrue(entries[0].isDirectory)
            assertTrue(!entries[1].isDirectory && !entries[2].isDirectory)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingPathIsEmptyList() {
        assertEquals(emptyList(), listDirectory("/definitely/not/a/dir-${System.nanoTime()}"))
    }

    @Test
    fun filePathIsEmptyList() {
        val file = File.createTempFile("gt-notdir", ".tmp")
        try {
            assertEquals(emptyList(), listDirectory(file.absolutePath))
        } finally {
            file.delete()
        }
    }
}
