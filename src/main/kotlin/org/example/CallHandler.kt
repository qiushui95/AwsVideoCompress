package org.example

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import kotlinx.coroutines.*
import okio.Buffer
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectAttributesRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.ObjectAttributes
import ws.schild.jave.MultimediaObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream

class CallHandler : RequestStreamHandler {

    private companion object {
        const val FFMPEG_NAME = "ffmpeg"
    }

    private val shellFile = File("/opt", FFMPEG_NAME)

    override fun handleRequest(input: InputStream?, output: OutputStream?, context: Context?) = runBlocking<Unit> {
        input ?: throw RuntimeException("input is null")
        context ?: throw RuntimeException("context is null")

        context.logger.log("开始处理任务")

        val callParamInfo = input.use {
            val buffer = Buffer()

            buffer.readFrom(it)

            MoShiFactory.getCallParamInfoAdapter().fromJson(buffer)
        } ?: throw RuntimeException("CallParamInfo is null")

        val srcBucket = callParamInfo.srcBucket
        val srcKey = callParamInfo.srcKey

        context.logger.log("当前处理文件:s3://${srcBucket}/${srcKey}")

        val s3Client = S3Client.builder()
            .build()

        val srcSizeDeferred = async(Dispatchers.IO) {
            val request = GetObjectAttributesRequest.builder()
                .bucket(srcBucket)
                .key(srcKey)
                .objectAttributes(ObjectAttributes.OBJECT_SIZE)
                .build()

            s3Client.getObjectAttributes(request).objectSize()
        }

        val srcFile = dloadVideo(s3Client, srcBucket, srcKey).await()

        if (srcFile.length() != srcSizeDeferred.await()) throw RuntimeException("视频下载失败")


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

    private fun CoroutineScope.dloadVideo(
        s3Client: S3Client,
        srcBucket: String,
        srcKey: String
    ) = async(Dispatchers.IO) {
        val sourceFile = File("/tmp/$srcKey")

        sourceFile.parentFile.mkdirs()

        sourceFile.createNewFile()

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