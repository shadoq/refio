package pl.jclab.refio.cli.tui.state

import org.junit.jupiter.api.Test
import pl.jclab.refio.core.security.RegexSafetyValidator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The approval service matches a rule against the call's arguments written as `key=value` pairs joined by
 * spaces, so these tests feed the pattern exactly that text.
 */
class FolderTrustRuleTest {

    private fun allows(folder: String, args: String) = Regex(FolderTrustRule.pattern(folder)).containsMatchIn(args)

    @Test
    fun `folder rule covers files in the folder and below it`() {
        assertTrue(allows("src/main/", "path=src/main/A.kt content=x"))
        assertTrue(allows("src/main/", "path=src/main/kotlin/B.kt"))
    }

    @Test
    fun `folder rule does not cover a sibling folder that shares the prefix`() {
        assertFalse(allows("src/main/", "path=src/main2/x.kt"))
    }

    @Test
    fun `climbing out with two dots is never covered`() {
        assertFalse(allows("src/main/", "path=src/main/../../etc/passwd"))
        assertNull(FolderTrustRule.folderOf("src/../x.kt"))
    }

    @Test
    fun `a path with a space cannot hide a climb out of the folder`() {
        assertFalse(allows("src/main/", "path=src/main/ok ../../../x.kt content=y"))
        assertFalse(allows("src/main/", "path=src/main/ok foo=../../../x.kt content=y"))
        assertFalse(allows("src/main/", "path=src/main/ok\\..\\..\\x.kt"))
        assertFalse(allows("src/main/", "path=src/main/x/.."))
    }

    @Test
    fun `dots that are not a path segment do not block the rule`() {
        assertTrue(allows("src/main/", "path=src/main/A.kt content=for (i in 0..9) println(\"...\")"))
    }

    @Test
    fun `folder named in another argument does not satisfy the rule`() {
        assertFalse(allows("src/main/", "content=path=src/main/x path=build/out.kt"))
        assertFalse(allows("src/main/", "new_string=a path=src/main/A.kt"))
    }

    @Test
    fun `special characters in the folder are matched literally`() {
        assertTrue(allows("a+b(1)/", "path=a+b(1)/x.kt"))
        assertFalse(allows("a+b(1)/", "path=aab1/x.kt"))
        assertTrue(allows("lib.v1/", "path=lib.v1/x.kt"))
        assertFalse(allows("lib.v1/", "path=libXv1/x.kt"))
        assertTrue(allows("c++[x]/", "path=c++[x]/y.h"))
    }

    @Test
    fun `a file in the project root gets no rule, because the rule could not be kept to the root`() {
        assertNull(FolderTrustRule.folderOf("README.md"))
        assertNull(FolderTrustRule.folderOf("./README.md"))
        assertNull(FolderTrustRule.folderOf("/README.md"))
        assertEquals("src/", FolderTrustRule.folderOf("./src/A.kt"))
    }

    @Test
    fun `paths with a space cannot become a rule`() {
        assertNull(FolderTrustRule.folderOf("my docs/a.md"))
    }

    @Test
    fun `a rule reads back as its folder`() {
        assertEquals("src/main/", FolderTrustRule.describe(FolderTrustRule.pattern("src/main/")))
        assertEquals("lib.v1/", FolderTrustRule.describe(FolderTrustRule.pattern("lib.v1/")))
        assertNull(FolderTrustRule.describe("rm -rf"))
    }

    @Test
    fun `rules pass the approval service's pattern safety check`() {
        for (folder in listOf("src/main/kotlin/pl/jclab/refio/", "c++[x]/", "a+b(1)/")) {
            RegexSafetyValidator.validate(FolderTrustRule.pattern(folder))
        }
    }
}
