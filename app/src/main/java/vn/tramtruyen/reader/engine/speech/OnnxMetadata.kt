package vn.tramtruyen.reader.engine.speech

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** Stream ModelProto without loading tensors into heap. Replace field 14, never append duplicate keys. */
internal object OnnxMetadata {
    fun rewrite(model: File, updates: Map<String, String>) {
        val temp = File(model.parentFile, model.name + ".metadata.tmp")
        val values = linkedMapOf<String, String>()
        try {
            FileInputStream(model).buffered().use { input ->
                FileOutputStream(temp).use { raw ->
                    val output = raw.buffered()
                    while (true) {
                        val tag = readVarint(input, allowEof = true) ?: break
                        check(tag > 0 && tag ushr 3 > 0) { "ONNX protobuf tag không hợp lệ" }
                        val wire = (tag and 7).toInt()
                        if (tag ushr 3 == 14L) {
                            check(wire == 2) { "ONNX metadata wire type không hợp lệ" }
                            val size = requireNotNull(readVarint(input))
                            check(size in 0..(2L * 1024 * 1024)) { "ONNX metadata quá lớn" }
                            val bytes = ByteArray(size.toInt())
                            readFully(input, bytes)
                            val pair = decodeEntry(bytes)
                            values[pair.first] = pair.second
                        } else {
                            writeVarint(output, tag)
                            copyValue(input, output, wire)
                        }
                    }
                    values.putAll(updates)
                    for ((key, value) in values) {
                        val entry = ByteArrayOutputStream()
                        writeString(entry, 1, key)
                        writeString(entry, 2, value)
                        writeVarint(output, 14L shl 3 or 2L)
                        writeVarint(output, entry.size().toLong())
                        entry.writeTo(output)
                    }
                    output.flush()
                    raw.fd.sync()
                }
            }
            // Android/Linux rename atomically replaces the original on the same filesystem.
            check(temp.renameTo(model)) { "Không thể cập nhật metadata ONNX" }
        } finally { temp.delete() }
    }

    private fun decodeEntry(bytes: ByteArray): Pair<String, String> {
        val input = ByteArrayInputStream(bytes)
        var key = ""
        var value = ""
        while (true) {
            val tag = readVarint(input, allowEof = true) ?: break
            val field = tag ushr 3
            val wire = (tag and 7).toInt()
            if ((field == 1L || field == 2L) && wire == 2) {
                val size = requireNotNull(readVarint(input))
                check(size in 0..bytes.size.toLong()) { "ONNX metadata string không hợp lệ" }
                val data = ByteArray(size.toInt())
                readFully(input, data)
                if (field == 1L) key = data.toString(Charsets.UTF_8) else value = data.toString(Charsets.UTF_8)
            } else copyValue(input, null, wire)
        }
        check(key.isNotEmpty()) { "ONNX metadata thiếu key" }
        return key to value
    }

    private fun copyValue(input: InputStream, output: OutputStream?, wire: Int) {
        when (wire) {
            0 -> requireNotNull(readVarint(input)).let { if (output != null) writeVarint(output, it) }
            1 -> copyExact(input, output, 8L)
            2 -> {
                val length = requireNotNull(readVarint(input))
                check(length >= 0) { "ONNX protobuf length không hợp lệ" }
                if (output != null) writeVarint(output, length)
                copyExact(input, output, length)
            }
            5 -> copyExact(input, output, 4L)
            else -> error("ONNX protobuf wire type không hỗ trợ: $wire")
        }
    }

    private fun copyExact(input: InputStream, output: OutputStream?, length: Long) {
        val buffer = ByteArray(minOf(length, 128L * 1024).toInt())
        var remaining = length
        while (remaining > 0) {
            val n = input.read(buffer, 0, minOf(remaining, buffer.size.toLong()).toInt())
            if (n < 0) throw EOFException("ONNX bị cắt cụt")
            if (n == 0) continue
            output?.write(buffer, 0, n)
            remaining -= n
        }
    }

    private fun readFully(input: InputStream, bytes: ByteArray) {
        var offset = 0
        while (offset < bytes.size) {
            val n = input.read(bytes, offset, bytes.size - offset)
            if (n < 0) throw EOFException("ONNX metadata bị cắt cụt")
            if (n > 0) offset += n
        }
    }

    private fun readVarint(input: InputStream, allowEof: Boolean = false): Long? {
        var value = 0L
        for (i in 0..9) {
            val b = input.read()
            if (b < 0) {
                if (i == 0 && allowEof) return null
                throw EOFException("ONNX varint bị cắt cụt")
            }
            check(i != 9 || b <= 1) { "ONNX varint overflow" }
            value = value or ((b and 127).toLong() shl (7 * i))
            if (b and 128 == 0) return value
        }
        error("ONNX varint không hợp lệ")
    }

    private fun writeString(out: OutputStream, field: Int, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        writeVarint(out, (field.toLong() shl 3) or 2L)
        writeVarint(out, bytes.size.toLong())
        out.write(bytes)
    }

    private fun writeVarint(out: OutputStream, raw: Long) {
        var value = raw
        while (value and -128L != 0L) {
            out.write((value.toInt() and 127) or 128)
            value = value ushr 7
        }
        out.write(value.toInt())
    }
}
