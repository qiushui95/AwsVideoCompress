package org.example

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.S3Event
import kotlinx.coroutines.*
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import ws.schild.jave.MultimediaObject
import java.io.File

class Handler : RequestHandler<S3Event, Unit> {
    private companion object {
        const val FFMPEG_NAME = "ffmpeg"
    }

    private val shellFile = File("/tmp", FFMPEG_NAME)

    override fun handleRequest(input: S3Event?, context: Context?) = runBlocking<Unit> {
        input ?: return@runBlocking
        context ?: return@runBlocking

        val record = input.records.getOrNull(0) ?: return@runBlocking

        val srcBucket = record.s3.bucket.name
        val srcKey = record.s3.`object`.urlDecodedKey
        val srcSize = record.s3.`object`.sizeAsLong

        val s3Client = S3Client.builder()
            .build()


        val exportShellJob = exportShell()

        val srcFile = dloadVideo(s3Client, srcBucket, srcKey).await()

        if (srcFile.length() != srcSize) throw RuntimeException("视频下载失败")

        exportShellJob.join()

        if (shellFile.exists().not()) throw RuntimeException("ffmpeg导出失败")

        val fileObject = MultimediaObject(srcFile) { shellFile.absolutePath }

        val videoInfo = fileObject.info.video

        val bitrate = videoInfo.bitRate
        val width = videoInfo.size.width
        val height = videoInfo.size.height
        val frameRate = videoInfo.frameRate

        val cmdBuilder = StringBuilder()

        cmdBuilder.append("sudo chmod 777 ${shellFile.absolutePath} && ")

        cmdBuilder.append(shellFile.absolutePath)
            .append(" -i ${srcFile.absolutePath}")

        if (bitrate > 5000) {
            cmdBuilder.append(" -b 5000k ")
        }

        if (frameRate > 30) {
            cmdBuilder.append(" -r 30 ")
        }

        if (width > height && height > 1080) {
            val realWidth = width * 1080 / height
            cmdBuilder.append(" -s ${realWidth}x1080 ")
        } else if (width < height && width > 1080) {
            val realHeight = height * 1080 / width
            cmdBuilder.append(" -s 1080x${realHeight} ")
        }

        val dstDir = srcFile.parentFile

        val dstFile = File(dstDir.absolutePath, "${srcFile.nameWithoutExtension}_compress.${srcFile.extension}")

        cmdBuilder.append(dstFile.absolutePath)

        val cmd = cmdBuilder.toString()

        context.logger.log("压缩命令:$cmd")

        launch(Dispatchers.IO) {
            val process = Runtime.getRuntime().exec(cmd)

            for (line in process.errorStream.bufferedReader().lines()) {
                context.logger.log("压缩过程:$line")
            }

            process.waitFor()
        }.join()

        context.logger.log("压缩结束,压缩成功:${srcFile.length() == dstFile.length()},dstFile.length()")
    }

    private fun CoroutineScope.exportShell() = launch(Dispatchers.IO) {
        Handler::class.java.getResourceAsStream(FFMPEG_NAME)?.use { input ->

            shellFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun CoroutineScope.dloadVideo(
        s3Client: S3Client,
        srcBucket: String,
        srcKey: String
    ) = async(Dispatchers.IO) {
        val sourceFile = File("/tmp/$srcKey")

        val request = GetObjectRequest.builder()
            .bucket(srcBucket)
            .key(srcKey)
            .build()

        s3Client.getObject(request).use { input ->
            sourceFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        sourceFile
    }
}