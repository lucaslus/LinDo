package do_.linux.lindo.ui

import androidx.compose.ui.text.input.TextFieldValue

/**
 * 移动端 / 桌面 Compose 输入法（IME）组合态保护工具：
 * 1. 当拼音/五笔/日韩输入法处于活跃组合态（`composition != null`）时，阻止回车/发送/搜索误触发提交；
 * 2. 单行输入框归一化换行符时，若不含换行则原样保留 `TextFieldValue`（避免打断正在拼写的组合下划线）。
 */
object ImeInputGuard {

    fun canSubmitAction(value: TextFieldValue): Boolean = value.composition == null

    fun normalizeSingleLine(value: TextFieldValue): TextFieldValue {
        if (!value.text.contains('\n') && !value.text.contains('\r')) {
            return value
        }
        val cleaned = value.text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
        return value.copy(text = cleaned)
    }

    /**
     * 搜索框 IME 组合态隔离：拼音尚未选词/按空格上屏（`composition != null`）时冻结已提交搜索词，
     * 避免每打一个拼音字母就把本地列表闪空或把半截拼音发往后端检索；清空时立即生效。
     */
    fun resolveCommittedQuery(previousCommitted: String, currentField: TextFieldValue): String {
        if (currentField.text.isEmpty()) return ""
        return if (currentField.composition != null) previousCommitted else currentField.text
    }
}
