data class Tree(val value: Int, val left: Tree? = null, val right: Tree? = null)

class Zipper private constructor(private val focus: Tree, private val crumbs: List<Crumb>) {
    private class Crumb(val wentLeft: Boolean, val value: Int, val sibling: Tree?)

    companion object {
        fun fromTree(tree: Tree): Zipper = Zipper(tree, emptyList())
    }

    fun value(): Int = focus.value

    fun left(): Zipper? = focus.left?.let { Zipper(it, crumbs + Crumb(true, focus.value, focus.right)) }

    fun right(): Zipper? = focus.right?.let { Zipper(it, crumbs + Crumb(false, focus.value, focus.left)) }

    fun up(): Zipper? {
        val crumb = crumbs.lastOrNull() ?: return null
        val parent = if (crumb.wentLeft) Tree(crumb.value, focus, crumb.sibling) else Tree(crumb.value, crumb.sibling, focus)
        return Zipper(parent, crumbs.dropLast(1))
    }

    fun setValue(value: Int): Zipper = Zipper(focus.copy(value = value), crumbs)

    fun setLeft(tree: Tree?): Zipper = Zipper(focus.copy(left = tree), crumbs)

    fun setRight(tree: Tree?): Zipper = Zipper(focus.copy(right = tree), crumbs)

    fun focusTree(): Tree = focus

    fun replace(tree: Tree): Zipper = Zipper(tree, crumbs)

    fun toTree(): Tree {
        var z = this
        while (true) z = z.up() ?: return z.focus
    }

    private fun path(): List<Boolean> = crumbs.map { it.wentLeft }

    override fun equals(other: Any?): Boolean =
        other is Zipper && toTree() == other.toTree()

    override fun hashCode(): Int = toTree().hashCode()
}
