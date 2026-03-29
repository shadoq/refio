package pl.jclab.refio.core.services.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class CppLanguageAnalyzerTest {

    private val analyzer = CppLanguageAnalyzer()

    @Test
    fun `matches cpp c and header extensions and rejects others`() {
        assertTrue(analyzer.matches(Paths.get("main.cpp")))
        assertTrue(analyzer.matches(Paths.get("main.cc")))
        assertTrue(analyzer.matches(Paths.get("main.c")))
        assertTrue(analyzer.matches(Paths.get("header.h")))
        assertTrue(analyzer.matches(Paths.get("header.hpp")))
        assertFalse(analyzer.matches(Paths.get("main.kt")))
        assertFalse(analyzer.matches(Paths.get("Main.java")))
    }

    @Test
    fun `extracts struct with name and type`() {
        val content = """
            struct Point {
                double x;
                double y;
            };
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("point.h"), content)
        val point = result.classes.first { it.name == "Point" }
        assertEquals("struct", point.type)
        assertEquals(1, point.startLine)
    }

    @Test
    fun `extracts class with public inheritance`() {
        val content = """
            class Dog : public Animal {
            public:
                void bark();
            };
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("dog.h"), content)
        val dog = result.classes.first { it.name == "Dog" }
        assertEquals("class", dog.type)
        assertEquals("Animal", dog.superclass)
    }

    @Test
    fun `extracts free function with return type and parameters`() {
        val content = """
            int add(int a, int b) {
                return a + b;
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("math.cpp"), content)
        val add = result.functions.first { it.name == "add" }
        assertEquals("int", add.returnType)
        assertEquals(2, add.parameters.size)
        assertEquals("a", add.parameters[0].name)
        assertEquals("int", add.parameters[0].type)
        assertEquals("b", add.parameters[1].name)
        assertTrue(add.signature?.contains("int add(int a, int b)") == true)
    }

    @Test
    fun `extracts include directives as imports`() {
        val content = """
            #include <iostream>
            #include "myfile.h"

            int main() {
                return 0;
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("main.cpp"), content)
        val modules = result.imports.map { it.module }
        assertTrue(modules.contains("iostream"))
        assertTrue(modules.contains("myfile.h"))
    }

    @Test
    fun `extracts regular enum and enum class`() {
        val content = """
            enum Color {
                RED,
                GREEN,
                BLUE
            };

            enum class Direction : int {
                UP,
                DOWN
            };
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("enums.h"), content)
        val color = result.classes.first { it.name == "Color" }
        assertEquals("enum", color.type)

        val direction = result.classes.first { it.name == "Direction" && it.type == "enum_class" }
        assertEquals("enum_class", direction.type)
    }

    @Test
    fun `estimates complexity greater than one for branching function`() {
        val content = """
            int process(int x) {
                if (x > 0) {
                    for (int i = 0; i < x; i++) {
                        while (x > 10) {
                            x--;
                        }
                    }
                }
                switch (x) {
                    case 0: return 0;
                    default: return 1;
                }
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("process.cpp"), content)
        val process = result.functions.first { it.name == "process" }
        assertNotNull(process.complexity)
        assertTrue(process.complexity!! > 1, "Expected complexity > 1 but was ${process.complexity}")
    }

    @Test
    fun `detects namespace as framework marker via STL includes`() {
        val content = """
            #include <vector>
            #include <map>

            namespace mylib {
                void init();
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("lib.cpp"), content)
        assertTrue(result.frameworks.contains("STL-Containers"))
    }

    @Test
    fun `extracts block documentation comment above class`() {
        val content = """
            /**
             * Represents a 2D point.
             */
            struct Point {
                double x;
                double y;
            };
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("point.h"), content)
        val point = result.classes.first { it.name == "Point" }
        assertNotNull(point.documentation)
        assertTrue(point.documentation!!.contains("2D point"))
    }

    @Test
    fun `detects Factory pattern from class name`() {
        val content = """
            class UserFactory {
            public:
                User create();
            };
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("factory.h"), content)
        val factory = result.classes.first { it.name == "UserFactory" }
        assertTrue(factory.patterns.contains("Factory"))
        assertEquals("Object Factory", factory.purpose)
    }

    @Test
    fun `extracts define macros`() {
        val content = """
            #define MAX_SIZE 1024
            #define MIN(a, b) ((a) < (b) ? (a) : (b))
            #define ENABLED

            int main() {
                return 0;
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("config.h"), content)
        val macrosElement = result.classes.firstOrNull { it.type == "macros" }
        assertNotNull(macrosElement, "Should have a macros pseudo-element")
        val fields = macrosElement!!.fields
        assertEquals(3, fields.size)

        val maxSize = fields.first { it.name == "MAX_SIZE" }
        assertEquals("macro", maxSize.type)
        assertEquals("1024", maxSize.initializer)

        val min = fields.first { it.name == "MIN" }
        assertEquals("macro(a, b)", min.type)
        assertTrue(min.initializer?.contains("(a) < (b)") == true)

        val enabled = fields.first { it.name == "ENABLED" }
        assertEquals("macro", enabled.type)
    }

    @Test
    fun `extracts namespaces`() {
        val content = """
            namespace outer {
                namespace inner {
                    void foo();
                }
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("ns.cpp"), content)
        val namespaces = result.classes.filter { it.type == "namespace" }
        assertTrue(namespaces.any { it.name == "outer" }, "Should find 'outer' namespace")
        assertTrue(namespaces.any { it.name == "inner" }, "Should find 'inner' namespace")
    }

    @Test
    fun `extracts typedef`() {
        val content = """
            typedef unsigned long ulong;
            typedef std::vector<int> IntVec;

            int main() {
                return 0;
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("types.h"), content)
        val typedefs = result.classes.filter { it.type == "typedef" }
        assertTrue(typedefs.any { it.name == "ulong" && it.superclass == "unsigned long" },
            "Should extract ulong typedef")
        assertTrue(typedefs.any { it.name == "IntVec" && it.superclass?.contains("vector") == true },
            "Should extract IntVec typedef")
    }

    @Test
    fun `extracts using alias`() {
        val content = """
            using StringList = std::vector<std::string>;
            using Callback = std::function<void(int)>;

            int main() {
                return 0;
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("aliases.h"), content)
        val aliases = result.classes.filter { it.type == "using_alias" }
        assertTrue(aliases.any { it.name == "StringList" && it.superclass?.contains("vector") == true },
            "Should extract StringList using alias")
        assertTrue(aliases.any { it.name == "Callback" && it.superclass?.contains("function") == true },
            "Should extract Callback using alias")
    }
}
