package org.example

import com.amazonaws.services.lambda.runtime.Context
import com.amazonaws.services.lambda.runtime.RequestHandler
import com.amazonaws.services.lambda.runtime.events.S3Event
import kotlinx.coroutines.*
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.InvocationType
import software.amazon.awssdk.services.lambda.model.InvokeRequest
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import ws.schild.jave.MultimediaObject
import java.io.File

class S3Handler : RequestHandler<S3Event, Unit> {
    private companion object {
        const val REGEX_OBJECT_KEY = "upload/v\\d/video/.+"
        const val REGEX_COMPRESS_KEY = "upload/v\\d/video/.+_compress\\..+"
    }

    override fun handleRequest(input: S3Event?, context: Context?) {
        input ?: throw RuntimeException("input is null")
        context ?: throw RuntimeException("context is null")

        context.logger.log("+++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++")

        context.logger.log("开始处理任务")

        val record = input.records.getOrNull(0) ?: throw RuntimeException("record is null")

        val srcBucket = record.s3.bucket.name
        val srcKey = record.s3.`object`.urlDecodedKey

        if (srcKey.matches(Regex(REGEX_OBJECT_KEY)).not()) {
            context.logger.log("不在处理文件夹内")
            return
        }

        if (srcKey.matches(Regex(REGEX_COMPRESS_KEY))) {
            context.logger.log("已被压缩,不需要压缩")
            return
        }

        val paramInfo = CallParamInfo(srcBucket = srcBucket, srcKey = srcKey)

        val paramJson = MoShiFactory.getCallParamInfoAdapter().toJson(paramInfo)

        context.logger.log("lambda invoke json:$paramJson")

        val payload = SdkBytes.fromUtf8String(paramJson)

        val request = InvokeRequest.builder()
            .functionName("ssr_video_compress_call")
            .payload(payload)
            .invocationType(InvocationType.EVENT)
            .build()

        val lambdaClient = LambdaClient.builder()
            .build()

        val response = lambdaClient.invoke(request)

        if (response.statusCode() == 202) {
            context.logger.log("lambda异步执行")
        } else {
            context.logger.log("lambda执行失败,${response.functionError()}")
        }
    }
}