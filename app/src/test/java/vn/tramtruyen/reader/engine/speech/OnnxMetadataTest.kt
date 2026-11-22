package vn.tramtruyen.reader.engine.speech

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.file.Files

class OnnxMetadataTest {
    // Small metadata entries (<128 bytes), enough to test replacement without an ONNX dependency.
    private fun entry(key: String, value: String): ByteArray {
        val k = key.toByteArray(); val v = value.toByteArray()
        val data = byteArrayOf(10, k.size.toByte()) + k + byteArrayOf(18, v.size.toByte()) + v
        return byteArrayOf(114, data.size.toByte()) + data
    }
    @Test fun replacesDuplicatesPreservesGraphAndIsIdempotent() {
        val dir = Files.createTempDirectory("onnx-metadata").toFile()
        try {
            val file = dir.resolve("model.onnx")
            val graph = byteArrayOf(8, 8, 58, 3, 1, 2, 3)
            file.writeBytes(graph + entry("voice", "old") + entry("voice", "invalid") + entry("extra", "keep"))
            val update = linkedMapOf("voice" to "vi", "sample_rate" to "22050")
            OnnxMetadata.rewrite(file, update)
            val expected = graph + entry("voice", "vi") + entry("extra", "keep") + entry("sample_rate", "22050")
            assertArrayEquals(expected, file.readBytes())
            OnnxMetadata.rewrite(file, update)
            assertArrayEquals(expected, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun truncatedInputKeepsOriginalAndRemovesTemporaryFile() {
        val dir = Files.createTempDirectory("onnx-metadata-bad").toFile()
        try {
            val file = dir.resolve("model.onnx")
            val original = byteArrayOf(58, 100, 1) // claims a 100-byte graph but truncated
            file.writeBytes(original)
            assertTrue(runCatching { OnnxMetadata.rewrite(file, mapOf("voice" to "vi")) }.isFailure)
            assertArrayEquals(original, file.readBytes())
            assertFalse(dir.resolve("model.onnx.metadata.tmp").exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsInvalidWireWithoutTouchingOriginal() {
        val dir = Files.createTempDirectory("onnx-metadata-wire").toFile()
        try {
            val file = dir.resolve("model.onnx")
            val original = byteArrayOf(15)
            file.writeBytes(original)
            assertTrue(runCatching { OnnxMetadata.rewrite(file, emptyMap()) }.isFailure)
            assertArrayEquals(original, file.readBytes())
        } finally { dir.deleteRecursively() }
    }
    @Test fun streamsLargeLengthDelimitedFieldWithoutChangingPayload() {
        val dir = Files.createTempDirectory("onnx-metadata-large").toFile()
        try {
            val file = dir.resolve("model.onnx")
            val payload = ByteArray(200_000) { (it % 251).toByte() }
            // field 7, length=200000 as protobuf varint c0 9a 0c
            val graph = byteArrayOf(58, 0xc0.toByte(), 0x9a.toByte(), 0x0c) + payload
            file.writeBytes(graph)
            OnnxMetadata.rewrite(file, mapOf("voice" to "vi"))
            assertArrayEquals(graph + entry("voice", "vi"), file.readBytes())
        } finally { dir.deleteRecursively() }
    }
}
