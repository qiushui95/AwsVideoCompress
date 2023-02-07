package org.example

import com.squareup.moshi.JsonClass

import com.squareup.moshi.Json


@JsonClass(generateAdapter = true)
data class ReqCompressResult(
    @Json(name = "md5")
    val md5: String,
    @Json(name = "source_path")
    val originKey: String,
    @Json(name = "new_source_path")
    val compressKey: String,
    @Json(name = "width")
    val width: Int,
    @Json(name = "height")
    val height: Int,
)