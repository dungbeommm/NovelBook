package vn.tramtruyen.reader.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SafeJsonFilesTest {
    @Test fun restoresValidBackupInsteadOfReturningEmptyData() {
        val dir = Files.createTempDirectory("safe-json-test").toFile()
        try {
            val file = dir.resolve("library.json")
            file.writeText("{broken")
            dir.resolve("library.json.bak").writeText("[\"kept\"]")

            val recovered = SafeJsonFiles.readOrRecover(file, { emptyList() }) { raw ->
                require(raw.startsWith("["))
                listOf("kept")
            }

            assertEquals(listOf("kept"), recovered)
            assertEquals("[\"kept\"]", file.readText())
            assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("library.json.corrupt-") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun quarantinesUnrecoverableDataBeforeStartingFresh() {
        val dir = Files.createTempDirectory("safe-json-test").toFile()
        try {
            val file = dir.resolve("history.json").apply { writeText("broken") }
            val value = SafeJsonFiles.readOrRecover(file, { listOf("fresh") }) { error("bad json") }

            assertEquals(listOf("fresh"), value)
            assertTrue(!file.exists())
            assertTrue(dir.listFiles().orEmpty().any { it.name.startsWith("history.json.corrupt-") })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun atomicWritesKeepPreviousVersionAsBackup() {
        val dir = Files.createTempDirectory("safe-json-test").toFile()
        try {
            val file = dir.resolve("progress.json")
            SafeJsonFiles.writeAtomic(file, "{\"version\":1}")
            SafeJsonFiles.writeAtomic(file, "{\"version\":2}")

            assertEquals("{\"version\":2}", file.readText())
            assertEquals("{\"version\":1}", dir.resolve("progress.json.bak").readText())
        } finally {
            dir.deleteRecursively()
        }
    }
}