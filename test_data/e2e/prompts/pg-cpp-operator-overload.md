`include/vector2.hpp` holds the Vector2 type used by the sprite animator, and it predates
the team's rules for math types: values are passed by copy, no method is declared
read-only, equality is a byte-wise `memcmp`, and output goes through a free `print`
function. The animator's newer code wants to write `a + b`, `v * 2.0`, `a == b` and
`std::cout << v` like with any normal value type.

Your task:

1. Add to `include/vector2.hpp`: `operator+` and `operator-` (component-wise), `operator*`
   (scalar multiplication), `operator==` and `operator!=`, and a stream output
   `operator<<` declared as a friend of the class.
2. Make the class const-correct: `Vector2` parameters become `const Vector2&`, and every
   method that does not mutate state (the operators, `add`, `scaled`, `equals`, the
   getters) becomes a `const` member function.
3. Add `dot(const Vector2&)` and `length()` as `const` member functions, and reimplement
   equality by comparing the components instead of comparing bytes.
4. Keep the existing `add` / `scaled` / `equals` / `print` API working - they may delegate
   to the new operators - so the unchanged demo in `src/main.cpp` still compiles.

Do not modify `src/main.cpp` or the README: the change belongs in `include/vector2.hpp`.
