package geom

import kotlin.math.PI

class Circle(private val radius: Double) : Shape {
    // BUG: this returns the circumference (2 * pi * r), not the area.
    override fun area(): Double = 2 * PI * radius
}
