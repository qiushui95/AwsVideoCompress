package org.example

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestStreamHandler
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
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
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.math.max

class CallHandler : RequestStreamHandler {

    private companion object {
        const val FFMPEG_NAME = "ffmpeg"
        const val LIFECYCLE_BUCKET = "res-southeast-lifecycle"
        val md5: MessageDigest = MessageDigest.getInstance("MD5")
    }

    private val shellFile = File("/opt", FFMPEG_NAME)

    override fun handleRequest(input: InputStream?, output: OutputStream?, context: Context?) = runBlocking<Unit> {
        input ?: throw RuntimeException("input is null")
        context ?: throw RuntimeException("context is null")

        context.logger.log("+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")

        val baseUrl = System.getenv("base_url") ?: throw RuntimeException("base url is null")

        val interceptor = HttpLoggingInterceptor { context.logger.log(it) }
        interceptor.level = HttpLoggingInterceptor.Level.BODY

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(interceptor)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .addConverterFactory(MoshiConverterFactory.create())
            .client(okHttpClient)
            .build()

        val httpApis = retrofit.create(HttpApis::class.java)

        context.logger.log("??????????????????")

        val callParamInfo = input.use {
            val buffer = Buffer()

            buffer.readFrom(it)

            MoShiFactory.getCallParamInfoAdapter().fromJson(buffer)
        } ?: throw RuntimeException("CallParamInfo is null")

        val srcBucket = callParamInfo.srcBucket
        val srcKey = callParamInfo.srcKey

        context.logger.log("??????????????????:s3://${srcBucket}/${srcKey}")

        val s3Client = S3Client.builder()
            .build()

        val srcSizeDeferred = async(Dispatchers.IO) {
            getS3Size(s3Client, srcBucket, srcKey)
        }

        val srcFile = dloadVideo(s3Client, srcBucket, srcKey).await()

        val srcSize = srcSizeDeferred.await() ?: throw RuntimeException("????????????????????????")

        if (srcFile.length() != srcSize) throw RuntimeException("??????????????????")

        val srcMd5 = getFileMd5(srcFile)

        if (httpApis.compressStatus(srcMd5)) {
            context.logger.log("s3://${srcBucket}/${srcKey}????????????????????????,?????????????????????")
            postResult(
                s3Client = s3Client,
                httpApis = httpApis,
                srcMd5 = srcMd5,
                srcBucket = srcBucket,
                srcKey = srcKey,
                dstKey = srcKey,
                width = 0,
                height = 0
            )
            return@runBlocking
        }

        val fileObject = MultimediaObject(srcFile) { shellFile.absolutePath }

        val videoInfo = fileObject.info.video

        val bitrate = videoInfo.bitRate / 1000
        var width = videoInfo.size.width
        var height = videoInfo.size.height
        val frameRate = videoInfo.frameRate

        context.logger.log("????????????:bitrate:$bitrate,${width}x${height},frameRate:$frameRate")

        val dstKey = srcKey.removeSuffix(srcFile.name) + "${srcFile.nameWithoutExtension}_compress.mp4"

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

        if (cmdList.size == 2 && srcFile.extension == "mp4") {
            context.logger.log("???????????????")
            copyS3Object(
                s3Client = s3Client,
                srcBucket = srcBucket,
                srcKey = srcKey,
                dstBucket = srcBucket,
                dstKey = dstKey
            )
            postResult(
                s3Client = s3Client,
                httpApis = httpApis,
                srcMd5 = srcMd5,
                srcBucket = srcBucket,
                srcKey = srcKey,
                dstKey = dstKey,
                width = width,
                height = height
            )
            return@runBlocking
        }

        if (getS3Size(s3Client, srcBucket, dstKey) != null) {
            context.logger.log("??????????????????")
            postResult(
                s3Client = s3Client,
                httpApis = httpApis,
                srcMd5 = srcMd5,
                srcBucket = srcBucket,
                srcKey = srcKey,
                dstKey = dstKey,
                width = width,
                height = height
            )
            return@runBlocking
        }

        val dstFile = File("/tmp/$dstKey")

        cmdList.add(dstFile.absolutePath)

        val cmd = cmdList.joinToString(" ")

        context.logger.log("????????????:$cmd")

        launch(Dispatchers.IO) {
            Runtime.getRuntime().exec(cmd).waitFor()
        }.join()

        if (dstFile.exists().not()) {
            throw RuntimeException("????????????,?????????????????????")
        } else if (srcFile.length() <= dstFile.length()) {
            context.logger.log("????????????,??????????????????,${srcFile.length()},${dstFile.length()}")

            copyS3Object(
                s3Client = s3Client,
                srcBucket = srcBucket,
                srcKey = srcKey,
                dstBucket = srcBucket,
                dstKey = dstKey
            )

        } else if (dstFile.length() <= 0) {
            throw RuntimeException("????????????,???????????????0")
        }

        context.logger.log("????????????,${srcFile.length()},${dstFile.length()}")

        uloadVideo(context, s3Client, dstFile, srcBucket, dstKey)

        postResult(
            s3Client = s3Client,
            httpApis = httpApis,
            srcMd5 = srcMd5,
            srcBucket = srcBucket,
            srcKey = srcKey,
            dstKey = dstKey,
            width = width,
            height = height
        )
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

        context.logger.log("????????????")

        s3Client.putObject(request, RequestBody.fromFile(file))

        context.logger.log("????????????")
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

        val md5Builder = StringBuilder()

        md5Builder.append(md5Str)

        repeat(max(32 - md5Str.length, 0)) {
            md5Builder.insert(0, 0)
        }

        if (md5Builder.isBlank()) throw RuntimeException("${file.absolutePath} md5 is null")

        return md5Builder.toString().uppercase()
    }

    private suspend fun postResult(
        s3Client: S3Client,
        httpApis: HttpApis,
        srcMd5: String,
        srcBucket: String,
        srcKey: String,
        dstKey: String,
        width: Int,
        height: Int
    ) {
        copyS3Object(s3Client, srcBucket, srcKey, LIFECYCLE_BUCKET, srcKey)

        val request = DeleteObjectRequest.builder()
            .bucket(srcBucket)
            .key(srcKey)
            .build()

        s3Client.deleteObject(request)

        val paramInfo = ReqCompressResult(
            md5 = srcMd5, originKey = getFormatKey(srcKey), compressKey = getFormatKey(dstKey),
            width = width, height = height
        )

        httpApis.compressResult(paramInfo)

        s3Client.close()
    }

    private fun copyS3Object(
        s3Client: S3Client, srcBucket: String,
        srcKey: String,
        dstBucket: String,
        dstKey: String,
    ) {
        val request = CopyObjectRequest.builder()
            .destinationBucket(dstBucket)
            .destinationKey(dstKey)
            .sourceBucket(srcBucket)
            .sourceKey(srcKey)
            .build()

        s3Client.copyObject(request).copyObjectResult()
    }

    private fun getFormatKey(key: String): String {
        if (key.startsWith("/")) return key

        return "/$key"
    }
}