package geom

/**
 * A 2-D shape.
 *
 * Contract: [area] returns the ENCLOSED AREA. For a circle of radius r that is
 * pi * r * r — NOT the circumference (2 * pi * r).
 */
interface Shape {
    fun area(): Double
}
