package org.example

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi

object MoShiFactory {
    private val moshi by lazy {
        Moshi.Builder()
            .build()
    }

    fun getCallParamInfoAdapter(): JsonAdapter<CallParamInfo> {
        return moshi.adapter(CallParamInfo::class.java)
    }
}