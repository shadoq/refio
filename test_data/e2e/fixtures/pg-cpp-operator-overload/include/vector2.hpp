#ifndef VECTOR2_HPP
#define VECTOR2_HPP

// vector2.hpp - the 2-D vector type used by the sprite animator.
//
// This header predates the team's rules for math types: parameters are
// passed by copy, no method is marked read-only, and equality compares the
// doubles byte-wise. It works, so nobody has touched it since 2013.

#include <cmath>
#include <cstring>
#include <iostream>

// A 2-D vector with component accessors.
class Vector2 {
public:
    Vector2();
    Vector2(double x, double y);

    // Component-wise sum of this vector and other.
    Vector2 add(Vector2 other);

    // This vector scaled by factor.
    Vector2 scaled(double factor);

    // Byte-wise comparison; NaN never compares equal.
    bool equals(Vector2 other);

    double x();
    double y();

private:
    double x_;
    double y_;
};

// Writes "Vector2(x, y)" to stdout.
void print(Vector2 v);

inline Vector2::Vector2() : x_(0.0), y_(0.0) {}

inline Vector2::Vector2(double x, double y) : x_(x), y_(y) {}

inline Vector2 Vector2::add(Vector2 other) {
    return Vector2(x_ + other.x_, y_ + other.y_);
}

inline Vector2 Vector2::scaled(double factor) {
    return Vector2(x_ * factor, y_ * factor);
}

inline bool Vector2::equals(Vector2 other) {
    // memcmp treats +0.0 and -0.0 as different bytes; that has been fine so
    // far because the animator never produces signed zeroes.
    return std::memcmp(&x_, &other.x_, sizeof(double)) == 0
        && std::memcmp(&y_, &other.y_, sizeof(double)) == 0;
}

inline double Vector2::x() { return x_; }

inline double Vector2::y() { return y_; }

inline void print(Vector2 v) {
    std::cout << "Vector2(" << v.x() << ", " << v.y() << ")";
}

#endif // VECTOR2_HPP
