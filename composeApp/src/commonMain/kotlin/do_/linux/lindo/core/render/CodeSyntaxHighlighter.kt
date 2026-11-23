package do_.linux.lindo.core.render

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * Lightweight, zero-dependency, 120Hz-safe syntax highlighter for Discourse code blocks.
 * Produces refined One Dark / GitHub Light semantic token spans without blocking UI frames.
 */
object CodeSyntaxHighlighter {
    private const val MAX_HIGHLIGHT_CHARS = 20_000

    private data class SyntaxColors(
        val keyword: Color,
        val string: Color,
        val number: Color,
        val comment: Color,
        val property: Color,
        val literal: Color,
        val diffAdd: Color,
        val diffDel: Color,
        val diffMeta: Color
    )

    private val darkColors = SyntaxColors(
        keyword = Color(0xFFC678DD),
        string = Color(0xFF98C379),
        number = Color(0xFFD19A66),
        comment = Color(0xFF7F848E),
        property = Color(0xFFE06C75),
        literal = Color(0xFF56B6C2),
        diffAdd = Color(0xFF98C379),
        diffDel = Color(0xFFE06C75),
        diffMeta = Color(0xFF61AFEF)
    )

    private val lightColors = SyntaxColors(
        keyword = Color(0xFFCF222E),
        string = Color(0xFF0A3069),
        number = Color(0xFF0550AE),
        comment = Color(0xFF6E7781),
        property = Color(0xFF116329),
        literal = Color(0xFF0550AE),
        diffAdd = Color(0xFF116329),
        diffDel = Color(0xFFCF222E),
        diffMeta = Color(0xFF0550AE)
    )

    private val commonKeywords = setOf(
        "fun", "val", "var", "class", "object", "interface", "enum", "sealed", "data", "override",
        "private", "public", "protected", "internal", "suspend", "inline", "return", "if", "else",
        "when", "for", "while", "do", "try", "catch", "finally", "throw", "import", "package",
        "function", "const", "let", "async", "await", "export", "default", "from", "type", "extends",
        "implements", "new", "switch", "case", "break", "continue", "def", "elif", "lambda", "with",
        "as", "pass", "yield", "raise", "fn", "pub", "struct", "impl", "trait", "match", "mut", "use",
        "mod", "crate", "ref", "move", "select", "insert", "update", "delete", "where", "join", "left",
        "right", "inner", "outer", "group", "order", "by", "limit", "into", "values", "create", "table",
        "alter", "drop", "index", "and", "or", "not", "in", "is", "echo", "export", "local", "then",
        "fi", "esac", "done", "func", "go", "defer", "chan", "range", "static", "void", "final", "struct"
    )

    private val commonLiterals = setOf("true", "false", "null", "nil", "none", "undefined", "this", "self", "super")

    private val jsonTokenRegex = Regex(
        """("(?:\\.|[^"\\])*")\s*(?=:)|("(?:\\.|[^"\\])*")|\b(true|false|null)\b|(-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?)"""
    )

    private val codeTokenRegex = Regex(
        """(//[^\n]*|#[^\n]*|--[^\n]*|/\*[\s\S]*?\*/)|("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|`(?:\\.|[^`\\])*`)|\b(0x[0-9a-fA-F]+|\d+(?:\.\d+)?)\b|\b([A-Za-z_][A-Za-z0-9_]*)\b"""
    )

    fun isMermaidLanguage(language: String): Boolean {
        val norm = language.trim().lowercase()
        return norm == "mermaid" || norm == "mmd"
    }

    data class MermaidPreviewModel(
        val diagramType: String,
        val lines: List<String>
    )

    fun parseMermaidPreview(code: String): MermaidPreviewModel {
        val rawLines = code.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("%%") }
        val header = rawLines.firstOrNull().orEmpty().lowercase()
        val diagramType = when {
            header.startsWith("sequencediagram") -> "时序图 (Sequence)"
            header.startsWith("flowchart") || header.startsWith("graph") -> "流程图 (Flowchart)"
            header.startsWith("statediagram") -> "状态图 (State)"
            header.startsWith("classdiagram") -> "类图 (Class)"
            header.startsWith("erdiagram") -> "ER 关系图"
            header.startsWith("gantt") -> "甘特图 (Gantt)"
            header.startsWith("pie") -> "饼图 (Pie)"
            header.startsWith("mindmap") -> "思维导图 (Mindmap)"
            else -> "Mermaid 图表"
        }
        val bodyLines = rawLines.drop(1).map { line ->
            line.replace("-->", " → ")
                .replace("-.->", " ⇢ ")
                .replace("==>", " ⇒ ")
                .replace("---", " — ")
                .replace("->>", " → ")
                .replace("-->>", " ⇢ ")
        }.take(16)
        return MermaidPreviewModel(diagramType = diagramType, lines = bodyLines)
    }

    fun highlight(code: String, language: String, isDark: Boolean): AnnotatedString {
        if (code.isEmpty() || code.length > MAX_HIGHLIGHT_CHARS) {
            return AnnotatedString(code)
        }
        val colors = if (isDark) darkColors else lightColors
        val lang = language.trim().lowercase()
        return buildAnnotatedString {
            append(code)
            when {
                lang in setOf("diff", "patch") -> {
                    var offset = 0
                    code.lineSequence().forEach { line ->
                        val end = offset + line.length
                        when {
                            line.startsWith("@@") || line.startsWith("diff ") ->
                                addStyle(SpanStyle(color = colors.diffMeta, fontWeight = FontWeight.SemiBold), offset, end)
                            line.startsWith("+") && !line.startsWith("+++") ->
                                addStyle(SpanStyle(color = colors.diffAdd), offset, end)
                            line.startsWith("-") && !line.startsWith("---") ->
                                addStyle(SpanStyle(color = colors.diffDel), offset, end)
                        }
                        offset = end + 1
                    }
                }
                lang in setOf("json", "jsonc", "json5") -> {
                    jsonTokenRegex.findAll(code).forEach { m ->
                        val prop = m.groups[1]
                        val str = m.groups[2]
                        val lit = m.groups[3]
                        val num = m.groups[4]
                        when {
                            prop != null -> addStyle(SpanStyle(color = colors.property), prop.range.first, prop.range.last + 1)
                            str != null -> addStyle(SpanStyle(color = colors.string), str.range.first, str.range.last + 1)
                            lit != null -> addStyle(SpanStyle(color = colors.literal, fontWeight = FontWeight.Medium), lit.range.first, lit.range.last + 1)
                            num != null -> addStyle(SpanStyle(color = colors.number), num.range.first, num.range.last + 1)
                        }
                    }
                }
                else -> {
                    val allowHashComment = lang in setOf("", "py", "python", "sh", "bash", "zsh", "shell", "yaml", "yml", "toml", "ruby", "rb", "perl", "r", "dockerfile", "ini", "conf")
                    val allowDashComment = lang in setOf("sql", "lua", "haskell")
                    codeTokenRegex.findAll(code).forEach { m ->
                        val comment = m.groups[1]
                        val str = m.groups[2]
                        val num = m.groups[3]
                        val ident = m.groups[4]
                        when {
                            comment != null -> {
                                val text = comment.value
                                val validComment = text.startsWith("//") || text.startsWith("/*") ||
                                    (text.startsWith("#") && allowHashComment) ||
                                    (text.startsWith("--") && allowDashComment)
                                if (validComment) {
                                    addStyle(SpanStyle(color = colors.comment, fontStyle = FontStyle.Italic), comment.range.first, comment.range.last + 1)
                                }
                            }
                            str != null -> addStyle(SpanStyle(color = colors.string), str.range.first, str.range.last + 1)
                            num != null -> addStyle(SpanStyle(color = colors.number), num.range.first, num.range.last + 1)
                            ident != null -> {
                                val word = ident.value.lowercase()
                                when {
                                    word in commonKeywords ->
                                        addStyle(SpanStyle(color = colors.keyword, fontWeight = FontWeight.Medium), ident.range.first, ident.range.last + 1)
                                    word in commonLiterals ->
                                        addStyle(SpanStyle(color = colors.literal, fontWeight = FontWeight.Medium), ident.range.first, ident.range.last + 1)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
