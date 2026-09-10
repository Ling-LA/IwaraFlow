package com.ling.iwaraflow

/**
 * 把输入框里的文字变成 Iwara 的标签过滤条件。
 *
 * Iwara 的标签是视频列表接口的 `tags` 过滤：多个标签用逗号连接表示同时命中，
 * 标签 ID 只有小写形式。用户输入的空格既可能是“两个标签”，也可能是一个标签里的
 * 下划线或者干脆连写，所以按这个顺序给出候选，前一种搜不到就换下一种。
 */
object TagQuery {
    private val separators = Regex("[\\s,，、;；]+")

    fun tags(raw: String): List<String> =
        raw.trim().lowercase().split(separators).filter { it.isNotBlank() }

    fun candidates(raw: String): List<String> {
        val words = tags(raw)
        if (words.isEmpty()) return emptyList()
        return listOf(
            words.joinToString(","),
            words.joinToString("_"),
            words.joinToString("")
        ).distinct()
    }

    /** 状态栏里展示当前真正用于搜索的标签。 */
    fun display(candidate: String): String =
        candidate.split(",").filter { it.isNotBlank() }.joinToString("  ") { "#$it" }
}
