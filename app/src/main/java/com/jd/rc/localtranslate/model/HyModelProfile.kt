package com.jd.rc.localtranslate.model

import org.json.JSONObject

internal object HyModelProfile {
    const val VERSION = "tencent-hymt2-1.8b-stq1-9df5c824"
    const val SIZE = 461860800L
    const val SHA256 = "cc497fe8f033b52b3b8b00a7669e9661435432f9d4cd43f7ed24400c01507a93"
    fun validate(manifest: JSONObject) {
        require(manifest.getString("version") == VERSION && manifest.getString("quantization") == "STQ1_0" &&
            manifest.getString("license") == "Apache-2.0") { "仅支持已登记的 Hy-MT2 1.8B 1.25-bit 包。" }
        val files = manifest.getJSONArray("files")
        val model = (0 until files.length()).map { files.getJSONObject(it) }.singleOrNull { it.getString("path") == "model.gguf" }
        require(model != null && model.getLong("size") == SIZE && model.getString("sha256") == SHA256) { "Hy-MT2 权重身份不匹配。" }
    }
}
