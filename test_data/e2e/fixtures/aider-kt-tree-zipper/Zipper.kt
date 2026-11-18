data class Tree(val value: Int, val left: Tree? = null, val right: Tree? = null)

class Zipper private constructor() {
    companion object {
        fun fromTree(tree: Tree): Zipper = TODO("not implemented")
    }

    fun value(): Int = TODO("not implemented")
    fun left(): Zipper? = TODO("not implemented")
    fun right(): Zipper? = TODO("not implemented")
    fun up(): Zipper? = TODO("not implemented")
    fun setValue(value: Int): Zipper = TODO("not implemented")
    fun setLeft(tree: Tree?): Zipper = TODO("not implemented")
    fun setRight(tree: Tree?): Zipper = TODO("not implemented")
    fun focusTree(): Tree = TODO("not implemented")
    fun replace(tree: Tree): Zipper = TODO("not implemented")
    fun toTree(): Tree = TODO("not implemented")
}
