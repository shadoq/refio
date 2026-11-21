# Instructions

Implement an immutable zipper over a binary tree of integers. A zipper is a tree together with one focused node. It lets you move around the tree and make changes at the focus without ever mutating anything: every operation returns a new zipper.

## Types

```kotlin
data class Tree(val value: Int, val left: Tree? = null, val right: Tree? = null)

class Zipper {
    companion object { fun fromTree(tree: Tree): Zipper }   // focus on the root

    fun value(): Int                  // value of the focused node
    fun left(): Zipper?               // focus on the left child, or null when there is none
    fun right(): Zipper?              // focus on the right child, or null when there is none
    fun up(): Zipper?                 // focus on the parent, or null at the root
    fun setValue(value: Int): Zipper  // same tree, focused node gets a new value
    fun setLeft(tree: Tree?): Zipper  // focused node gets a new left subtree (null removes it)
    fun setRight(tree: Tree?): Zipper // focused node gets a new right subtree (null removes it)
    fun focusTree(): Tree             // the subtree rooted at the focus
    fun replace(tree: Tree): Zipper   // the subtree rooted at the focus is replaced by `tree`
    fun toTree(): Tree                // the whole tree, as it is now, regardless of the focus
}
```

## Rules

- Navigating off the tree is not an error: `left()` / `right()` return `null` when the child is missing and `up()` returns `null` at the root.
- All changes are visible through `up()`: after `fromTree(t).left()!!.setValue(9).up()!!.toTree()` the left child of the root holds `9`.
- Zippers never change: operating on a zipper leaves it, and the `Tree` it was built from, exactly as they were.
- `setLeft` / `setRight` / `replace` do not move the focus: it stays on the same node (for `replace`, on the root of the new subtree).
- Equality is structural. Two zippers are equal when they focus the same position (the same sequence of left / right steps from the root) in equal trees. `hashCode` must agree with `equals`. Equality does not depend on how a zipper was reached: `z.left()!!.up()!! == z` for every zipper `z` whose focus has a left child.
