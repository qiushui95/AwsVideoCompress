package org.example

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okio.Buffer
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.*
import ws.schild.jave.MultimediaObject
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.io.path.Path

class CallHandler : RequestStreamHandler {

    private companion object {
        const val FFMPEG_NAME = "ffmpeg"
        val md5: MessageDigest = MessageDigest.getInstance("MD5")
    }

    private val shellFile = File("/opt", FFMPEG_NAME)

    override fun handleRequest(input: InputStream?, output: OutputStream?, context: Context?) = runBlocking<Unit> {
        input ?: throw RuntimeException("input is null")
        context ?: throw RuntimeException("context is null")


        val baseUrl = System.getenv("base_url") ?: throw RuntimeException("base url is null")


        val okHttpClient = OkHttpClient.Builder()
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create())
            .client(okHttpClient)
            .build()

        val httpApis = retrofit.create(HttpApis::class.java)

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
            getS3Size(s3Client, srcBucket, srcKey)
        }

        val srcFile = dloadVideo(s3Client, srcBucket, srcKey).await()

        val srcSize = srcSizeDeferred.await() ?: throw RuntimeException("视频大小获取失败")

        if (srcFile.length() != srcSize) throw RuntimeException("视频下载失败")

        val srcMd5 = getFileMd5(srcFile)

        if (httpApis.compressStatus(srcMd5)) {
            context.logger.log("s3://${srcBucket}/${srcKey}已有被压缩的文件,跳过处理该视频")
            return@runBlocking
        }

        val fileObject = MultimediaObject(srcFile) { shellFile.absolutePath }

        val videoInfo = fileObject.info.video

        val bitrate = videoInfo.bitRate / 1000
        var width = videoInfo.size.width
        var height = videoInfo.size.height
        val frameRate = videoInfo.frameRate

        context.logger.log("视频信息:bitrate:$bitrate,${width}x${height},frameRate:$frameRate")

        val cmdList = mutableListOf<String>()

        cmdList.add(shellFile.absolutePath)
        cmdList.add("-i ${srcFile.absolutePath}")

        if (bitrate > 5000) {
            cmdList.add("-b 5000k ")
        }

        if (frameRate > 30) {
            cmdList.add("-r 30 ")
        }

        if (width > height && height > 1080) {
            width = width * 1080 / height
            cmdList.add("-s ${width}x1080")
        } else if (width < height && width > 1080) {
            height = height * 1080 / width
            cmdList.add("-s 1080x${height}")
        }

        if (cmdList.size == 2) {
            context.logger.log("不需要压缩")
            postResult(httpApis, srcMd5, srcKey, srcKey, width, height)
            s3Client.close()
            return@runBlocking
        }

        val dstKey = srcKey.removeSuffix(srcFile.name) + "${srcFile.nameWithoutExtension}_compress.${srcFile.extension}"

        if (getS3Size(s3Client, srcBucket, dstKey) != null) {
            context.logger.log("已有压缩文件")
            postResult(httpApis, srcMd5, srcKey, dstKey, width, height)
            s3Client.close()
            return@runBlocking
        }

        val dstFile = File("/tmp/$dstKey")

        cmdList.add(dstFile.absolutePath)

        val cmd = cmdList.joinToString(" ")

        context.logger.log("压缩命令:$cmd")

        launch(Dispatchers.IO) {
            Runtime.getRuntime().exec(cmd).waitFor()
        }.join()

        if (dstFile.exists().not()) {
            throw RuntimeException("压缩失败,压缩结果不存在")
        } else if (srcFile.length() <= dstFile.length()) {
            throw RuntimeException("压缩失败,压缩结果变大,${srcFile.length()},${dstFile.length()}")
        } else if (dstFile.length() <= 0) {
            throw RuntimeException("压缩失败,压缩结果为0")
        }

        context.logger.log("压缩成功,${srcFile.length()},${dstFile.length()}")

        uloadVideo(context, s3Client, dstFile, srcBucket, dstKey)

        postResult(httpApis, srcMd5, srcKey, dstKey, width, height)

        s3Client.close()
    }

    private fun getS3Size(s3Client: S3Client, bucket: String, key: String): Long? {
        val request = GetObjectAttributesRequest.builder()
            .bucket(bucket)
            .key(key)
            .objectAttributes(ObjectAttributes.OBJECT_SIZE)
            .build()

        return try {
            s3Client.getObjectAttributes(request)
        } catch (e: NoSuchKeyException) {
            return null
        }.objectSize()
    }

    private fun CoroutineScope.dloadVideo(
        s3Client: S3Client,
        bucket: String,
        key: String
    ) = async(Dispatchers.IO) {
        val sourceFile = File("/tmp/$key")

        sourceFile.parentFile.mkdirs()

        sourceFile.createNewFile()

        val request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build()

        s3Client.getObject(request).use { input ->
            sourceFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        sourceFile
    }

    private fun uloadVideo(
        context: Context,
        s3Client: S3Client,
        file: File,
        bucket: String,
        key: String
    ) {

        val contentType = Files.probeContentType(Path(file.absolutePath))

        val request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .contentType(contentType)
            .build()

        context.logger.log("开始上传")

        s3Client.putObject(request, RequestBody.fromFile(file))

        context.logger.log("结束上传")
    }


    private fun getFileMd5(file: File): String {
        val buffer = ByteArray(4096)
        md5.reset()
        file.inputStream().use { input ->
            do {
                val length = input.read(buffer)
                if (length == -1) break
                md5.update(buffer, 0, length)
            } while (true)
        }

        val md5Str = BigInteger(1, md5.digest()).toString(16)

        if (md5Str.isBlank()) throw RuntimeException("${file.absolutePath} md5 is null")

        return md5Str.uppercase()
    }

    private suspend fun postResult(
        httpApis: HttpApis,
        srcMd5: String,
        srcKey: String,
        dstKey: String,
        width: Int,
        height: Int
    ) {
        val paramInfo = ReqCompressResult(
            md5 = srcMd5, originKey = srcKey, compressKey = dstKey,
            width = width, height = height
        )

        httpApis.compressResult(paramInfo)
    }
}