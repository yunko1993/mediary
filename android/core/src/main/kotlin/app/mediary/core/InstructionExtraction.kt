package app.mediary.core

import java.net.URI

data class InstructionDraft(val name: String, val dose: String, val frequency: String, val instructions: String,
    val duration: String, val source: String, val missing: List<String>) {
    val key: String get() = "$name|$source"
}

object InstructionExtraction {
    /** 格式正确仍可能包含臆测；MCP 草稿必须在本机再次核对原文证据。 */
    fun validate(drafts: List<InstructionDraft>, source: String): List<InstructionDraft> {
        require(drafts.size <= 30) { "药物草稿过多，请分段导入" }
        val input = normalize(source)
        return drafts.map { draft ->
            val fields = listOf(draft.name, draft.dose, draft.frequency, draft.instructions, draft.duration, draft.source)
            require(fields.all { it.length <= 12_000 } && draft.missing.size <= 20 && draft.missing.all { it.length <= 100 }) {
                "药物草稿格式异常，请重试或手动添加"
            }
            val quote = normalize(draft.source)
            require(quote.isNotEmpty() && quote in input) { "无法在原文核对医嘱引文，请重新导入或手动添加" }
            require(fields.dropLast(1).all { normalize(it) in quote }) { "解析中出现了原文未明确提供的内容，请重新导入或手动添加" }
            // 缺失提示只使用可信字段名，避免模型把新增用药建议藏进提示列表。
            val allowedMissing = setOf("药名", "每次剂量", "频次", "疗程", "服用条件", "用药途径", "开始日期", "结束日期", "具体提醒时间")
            val missing = draft.missing.filter { it in allowedMissing }.toMutableSet()
            listOf(draft.name to "药名", draft.dose to "每次剂量", draft.frequency to "频次", draft.duration to "疗程")
                .filter { it.first.isBlank() }.forEach { missing.add(it.second) }
            missing.add("具体提醒时间需人工核对")
            draft.copy(missing = missing.toList())
        }
    }

    private fun normalize(value: String) = value.filterNot { it.isWhitespace() }
}

object McpConnectionRules {
    fun validateEndpoint(value: String, allowLocalHttp: Boolean = false) {
        val uri = runCatching { URI(value) }.getOrNull()
        val localHttp = allowLocalHttp && uri?.scheme == "http" && uri.host in setOf("127.0.0.1", "localhost", "10.0.2.2")
        require(uri != null && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null &&
            (uri.port == -1 || uri.port in 1..65535) && (uri.scheme == "https" || localHttp)) {
            if (allowLocalHttp) "请输入 HTTPS 地址；调试包的自有服务可使用本机回环地址" else "请输入 HTTPS 服务基础地址，不要包含口令或查询参数"
        }
    }

    fun validateSecret(value: String) {
        require(value.isNotBlank() && value.length <= 4096 && value.all { it.code in 33..126 }) { "请输入有效访问口令，不要包含空格或换行" }
    }
}
