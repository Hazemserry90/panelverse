package com.example.testing

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class OfflineArchiveTest {
    @Test
    fun rejectsUnsafePathsAndKeepsNormalFolders() {
        assertEquals("chapter 1/page 02.jpg", OfflineArchive.validatedPath("Chapter 1/Page 02.JPG", false))
        assertEquals("chapter 1", OfflineArchive.validatedPath("Chapter 1/", true))
        for (path in listOf("../page.jpg", "chapter/../page.jpg", "/page.jpg",
            "C:/page.jpg", "chapter\\page.jpg", "chapter//page.jpg", "chapter/./page.jpg",
            "chapter./page.jpg", "chapter /page.jpg", "page\u202E.jpg", "page\u0000.jpg")) {
            assertThrows(path, OfflineArchive.Rejected::class.java) {
                OfflineArchive.validatedPath(path, false)
            }
        }
    }

    @Test
    fun rejectsDuplicateAndActiveEntries() {
        withArchive("pages/01.jpg", "PAGES/01.JPG") { archive ->
            assertThrows(OfflineArchive.Rejected::class.java) { OfflineArchive.pages(archive) }
        }
        withArchive("01.jpg", "metadata/readme.html") { archive ->
            assertThrows(OfflineArchive.Rejected::class.java) { OfflineArchive.pages(archive) }
        }
    }

    @Test
    fun ordersPagesAndChecksIntegrity() {
        withArchive("10.JPG", "2.webp", "1.png", "ComicInfo.xml") { archive ->
            val pages = OfflineArchive.pages(archive)
            assertEquals(listOf("1.png", "2.webp", "10.JPG"), pages.map(ZipEntry::getName))
            assertEquals("image/png", OfflineArchive.expectedMime(pages[0]))
            assertArrayEquals(byteArrayOf(7, 8, 9), OfflineArchive.readPage(archive, pages[0]))
            val mismatchedChecksum = ZipEntry(pages[0]).apply { crc = 0 }
            assertThrows(OfflineArchive.Rejected::class.java) {
                OfflineArchive.readPage(archive, mismatchedChecksum)
            }
        }
    }

    private fun withArchive(vararg paths: String, test: (ZipFile) -> Unit) {
        val file = File.createTempFile("panelverse-test-", ".cbz")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                for (path in paths) {
                    zip.putNextEntry(ZipEntry(path))
                    zip.write(byteArrayOf(7, 8, 9))
                    zip.closeEntry()
                }
            }
            ZipFile(file).use(test)
        } finally {
            file.delete()
        }
    }
}
