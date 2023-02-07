package org.example
import com.squareup.moshi.JsonClass

import com.squareup.moshi.Json


@JsonClass(generateAdapter = true)
data class CallParamInfo(
    @Json(name = "srcBucket")
    val srcBucket: String,
    @Json(name = "srcKey")
    val srcKey: String
)