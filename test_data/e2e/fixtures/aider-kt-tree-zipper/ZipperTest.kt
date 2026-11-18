import kotlin.system.exitProcess

private var failures = 0

private fun test(name: String, body: () -> Unit) {
    try {
        body()
        println("ok   $name")
    } catch (t: Throwable) {
        failures++
        println("FAIL $name: $t")
    }
}

private fun <T> eq(actual: T, expected: T) {
    if (actual != expected) throw AssertionError("expected $expected but was $actual")
}

private fun ne(a: Any?, b: Any?) {
    if (a == b) throw AssertionError("expected $a and $b to differ")
}

private fun leaf(v: Int) = Tree(v)

//        1
//      /   \
//     2     3
//      \   /
//       4 5
private val sample = Tree(1, Tree(2, null, leaf(4)), Tree(3, leaf(5), null))

fun main() {
    test("fromTree focuses the root") {
        eq(Zipper.fromTree(sample).value(), 1)
        eq(Zipper.fromTree(sample).toTree(), sample)
    }
    test("moves down to existing children") {
        eq(Zipper.fromTree(sample).left()!!.value(), 2)
        eq(Zipper.fromTree(sample).right()!!.value(), 3)
        eq(Zipper.fromTree(sample).left()!!.right()!!.value(), 4)
        eq(Zipper.fromTree(sample).right()!!.left()!!.value(), 5)
    }
    test("navigating off the tree gives null") {
        val root = Zipper.fromTree(sample)
        eq(root.up(), null)
        eq(root.left()!!.left(), null)
        eq(root.right()!!.right(), null)
        eq(root.left()!!.right()!!.left(), null)
        eq(root.left()!!.right()!!.right(), null)
    }
    test("up returns to the parent from either side") {
        val root = Zipper.fromTree(sample)
        eq(root.left()!!.up()!!.value(), 1)
        eq(root.right()!!.left()!!.up()!!.up()!!.value(), 1)
        eq(root.left()!!.right()!!.up()!!.value(), 2)
    }
    test("setValue is visible through up and toTree") {
        val z = Zipper.fromTree(sample).left()!!.setValue(9)
        eq(z.value(), 9)
        eq(z.up()!!.toTree(), Tree(1, Tree(9, null, leaf(4)), Tree(3, leaf(5), null)))
        eq(z.toTree(), Tree(1, Tree(9, null, leaf(4)), Tree(3, leaf(5), null)))
    }
    test("setValue deep in the tree keeps every sibling") {
        val z = Zipper.fromTree(sample).right()!!.left()!!.setValue(50)
        eq(z.toTree(), Tree(1, Tree(2, null, leaf(4)), Tree(3, leaf(50), null)))
    }
    test("setLeft and setRight replace or remove subtrees without moving the focus") {
        val root = Zipper.fromTree(sample)
        val a = root.setLeft(null)
        eq(a.value(), 1)
        eq(a.left(), null)
        eq(a.toTree(), Tree(1, null, Tree(3, leaf(5), null)))
        val b = root.right()!!.setRight(Tree(7, leaf(8), null))
        eq(b.value(), 3)
        eq(b.toTree(), Tree(1, Tree(2, null, leaf(4)), Tree(3, leaf(5), Tree(7, leaf(8), null))))
        eq(b.right()!!.left()!!.value(), 8)
    }
    test("replace swaps the focused subtree and keeps the focus on its root") {
        val z = Zipper.fromTree(sample).left()!!.replace(Tree(20, leaf(21), leaf(22)))
        eq(z.value(), 20)
        eq(z.focusTree(), Tree(20, leaf(21), leaf(22)))
        eq(z.toTree(), Tree(1, Tree(20, leaf(21), leaf(22)), Tree(3, leaf(5), null)))
    }
    test("focusTree is the subtree below the focus, toTree the whole tree") {
        val z = Zipper.fromTree(sample).right()!!
        eq(z.focusTree(), Tree(3, leaf(5), null))
        eq(z.toTree(), sample)
    }
    test("zippers are immutable") {
        val root = Zipper.fromTree(sample)
        val changed = root.setValue(100).setLeft(null).setRight(leaf(9))
        eq(root.value(), 1)
        eq(root.toTree(), sample)
        eq(changed.toTree(), Tree(100, null, leaf(9)))
        val child = root.left()!!
        child.setValue(77)
        eq(child.value(), 2)
        eq(root.toTree(), sample)
    }
    test("equal after a round trip down and up") {
        val root = Zipper.fromTree(sample)
        eq(root.left()!!.up()!!, root)
        eq(root.right()!!.left()!!.up()!!.up()!!, root)
        eq(root.left()!!.right()!!.up()!!, root.left()!!)
        eq(root.left()!!.up()!!.hashCode(), root.hashCode())
    }
    test("equal after changes that cancel out") {
        val root = Zipper.fromTree(sample)
        eq(root.left()!!.setValue(9).setValue(2).up()!!, root)
        eq(Zipper.fromTree(sample), Zipper.fromTree(sample.copy()))
    }
    test("different focus in the same tree is not equal") {
        val root = Zipper.fromTree(sample)
        ne(root, root.left()!!)
        ne(root.left()!!, root.right()!!)
        ne(root.left()!!.right()!!, root.right()!!.left()!!)
    }
    test("same focus value in different trees is not equal") {
        val root = Zipper.fromTree(sample)
        ne(root.left()!!, root.left()!!.up()!!.setRight(null).left()!!)
        ne(root.setValue(2), root)
    }
    test("same tree reached with the focus in a different place stays distinct after edits") {
        val a = Zipper.fromTree(Tree(1, leaf(1), leaf(1))).left()!!
        val b = Zipper.fromTree(Tree(1, leaf(1), leaf(1))).right()!!
        ne(a, b)
        eq(a.toTree(), b.toTree())
    }
    println(if (failures == 0) "ALL PASSED" else "$failures FAILED")
    if (failures > 0) exitProcess(1)
}
