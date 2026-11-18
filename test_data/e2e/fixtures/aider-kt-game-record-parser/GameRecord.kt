data class Node(
    val properties: Map<String, List<String>> = emptyMap(),
    val children: List<Node> = emptyList(),
)

fun parseRecord(text: String): Node {
    TODO("not implemented")
}
