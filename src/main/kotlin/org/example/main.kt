package org.example

import java.io.File
import java.math.BigInteger
import java.security.MessageDigest
import java.util.regex.Pattern
import kotlin.math.max

fun main() {
    val file = File("C:\\Users\\97457\\Downloads\\4b225a64-bc71-4405-b568-3a232748b985.mp4")

    println(getFileMd5(file))
}

private fun getFileMd5(file: File): String {
    val buffer = ByteArray(4096)

    val md5: MessageDigest = MessageDigest.getInstance("MD5")

    file.inputStream().use { input ->
        do {
            val length = input.read(buffer)
            if (length == -1) break
            md5.update(buffer, 0, length)
        } while (true)
    }

    val md5Str = BigInteger(1, md5.digest()).toString(16)

    val md5Builder = StringBuilder()

    md5Builder.append(md5Str)

    repeat(max(32 - md5Str.length, 0)) {
        md5Builder.insert(0, 0)
    }

    if (md5Builder.isBlank()) throw RuntimeException("${file.absolutePath} md5 is null")

    return md5Builder.toString().uppercase()
}