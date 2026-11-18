data class Node(
    val properties: Map<String, List<String>> = emptyMap(),
    val children: List<Node> = emptyList(),
)

fun parseRecord(text: String): Node {
    val parser = RecordParser(text)
    parser.skipWhitespace()
    val root = parser.tree()
    parser.skipWhitespace()
    require(parser.atEnd()) { "unexpected text after the record at ${parser.position()}" }
    return root
}

private class RecordParser(private val s: String) {
    private var i = 0

    fun position() = i

    fun atEnd() = i >= s.length

    private fun peek(): Char? = if (i < s.length) s[i] else null

    fun skipWhitespace() {
        while (i < s.length && s[i] in " \t\r\n") i++
    }

    fun tree(): Node {
        require(peek() == '(') { "expected '(' at $i" }
        i++
        skipWhitespace()
        val nodes = ArrayList<Map<String, List<String>>>()
        while (peek() == ';') {
            nodes.add(node())
            skipWhitespace()
        }
        require(nodes.isNotEmpty()) { "a tree needs at least one node" }
        val variations = ArrayList<Node>()
        while (peek() == '(') {
            variations.add(tree())
            skipWhitespace()
        }
        require(peek() == ')') { "expected ')' at $i" }
        i++
        var result = Node(nodes.last(), variations)
        for (k in nodes.size - 2 downTo 0) result = Node(nodes[k], listOf(result))
        return result
    }

    private fun node(): Map<String, List<String>> {
        i++
        skipWhitespace()
        val props = LinkedHashMap<String, List<String>>()
        while (true) {
            val c = peek()
            if (c == null || c !in 'A'..'Z') break
            val start = i
            while (i < s.length && s[i] in 'A'..'Z') i++
            val key = s.substring(start, i)
            require(key !in props) { "duplicate key $key" }
            skipWhitespace()
            require(peek() == '[') { "property $key has no value" }
            val values = ArrayList<String>()
            while (peek() == '[') {
                values.add(value())
                skipWhitespace()
            }
            props[key] = values
        }
        return props
    }

    private fun value(): String {
        i++
        val sb = StringBuilder()
        while (true) {
            require(i < s.length) { "unterminated value" }
            val c = s[i++]
            if (c == '\\') {
                require(i < s.length) { "unterminated value" }
                sb.append(s[i++])
            } else if (c == ']') {
                return sb.toString()
            } else {
                sb.append(c)
            }
        }
    }
}
