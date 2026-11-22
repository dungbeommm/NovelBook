package vn.tramtruyen.reader.engine.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class OfflineTtsArchiveTest {
    @Test fun rejectsZipTraversal() {
        val root = Files.createTempDirectory("tts-zip-test").toFile()
        try {
            val zip = zipOf("../escaped.txt" to "bad".toByteArray())
            val result = runCatching {
                ZipInputStream(ByteArrayInputStream(zip)).use {
                    OfflineTtsModelManager.unzipSafe(it, root, 1024)
                }
            }
            assertTrue(result.isFailure)
            assertFalse(root.parentFile!!.resolve("escaped.txt").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun propagatesExpandedSizeFailure() {
        val root = Files.createTempDirectory("tts-zip-test").toFile()
        try {
            val zip = zipOf("model.onnx" to ByteArray(2048))
            val result = runCatching {
                ZipInputStream(ByteArrayInputStream(zip)).use {
                    OfflineTtsModelManager.unzipSafe(it, root, 128)
                }
            }
            assertTrue(result.isFailure)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun zipOf(vararg files: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            files.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}