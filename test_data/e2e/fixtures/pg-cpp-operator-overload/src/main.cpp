// Smoke-test demo for the animator's vector math.

#include "vector2.hpp"

#include <iostream>

int main() {
    Vector2 a(1.0, 2.0);
    Vector2 b(3.5, -1.0);

    Vector2 sum = a.add(b);
    Vector2 doubled = a.scaled(2.0);

    std::cout << "sum: ";
    print(sum);
    std::cout << "\n";

    std::cout << "doubled: ";
    print(doubled);
    std::cout << "\n";

    if (a.equals(b)) {
        std::cout << "a equals b\n";
    } else {
        std::cout << "a does not equal b\n";
    }

    Vector2 zero;
    if (zero.equals(Vector2())) {
        std::cout << "zero vector round-trips\n";
    }

    return 0;
}
