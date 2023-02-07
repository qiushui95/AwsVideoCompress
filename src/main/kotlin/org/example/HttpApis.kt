package org.example

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

interface HttpApis {

    /**
     * 获取压缩状态
     * @return true->已压缩
     */
    @GET("v2/public/sourceIsCompress")
    suspend fun compressStatus(@Query("md5") md5: String): Boolean

    /**
     * 上报压缩结果
     */
    @POST("/v2/public/replaceSource")
    suspend fun compressResult(@Body body: ReqCompressResult)
}