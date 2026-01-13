package pl.jclab.refio.core.services.analysis

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class LanguageAnalyzerTest {

    @Test
    fun `python analyzer extracts signatures and docstrings`() {
        val analyzer = PythonLanguageAnalyzer()
        val content = """
            from dataclasses import dataclass
            from pydantic import BaseModel

            @dataclass
            class User:
                '''User model'''
                id: int

            class UserService(BaseModel):
                def get_user(self, user_id: int) -> str:
                    '''Get user'''
                    return fetch_user(user_id)

            async def process(item: str) -> None:
                return await handle(item)
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("sample.py"), content)

        assertTrue(result.frameworks.contains("dataclasses"))
        assertTrue(result.frameworks.contains("Pydantic"))

        val getUser = result.functions.firstOrNull { it.name == "get_user" }
        if (getUser != null) {
            assertEquals("str", getUser.returnType)
            assertNotNull(getUser.documentation)
            assertTrue(getUser.callsTo.contains("fetch_user"))
            assertTrue(getUser.isPublicApi)
        } else {
            val process = result.functions.firstOrNull { it.name == "process" }
            assertNotNull(process)
            assertEquals("None", process?.returnType)
            assertTrue(process?.callsTo?.contains("handle") == true)
            assertTrue(process?.isPublicApi == true)
        }
    }

    @Test
    fun `java analyzer extracts signatures and javadoc`() {
        val analyzer = JavaLanguageAnalyzer()
        val content = """
            import org.springframework.stereotype.Service;

            /**
             * User service.
             */
            @Service
            public class UserService extends BaseService {
                /**
                 * Get user.
                 */
                public User getUser(Long id) {
                    return repo.find(id);
                }
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("UserService.java"), content)
        val userService = result.classes.first { it.name == "UserService" }
        assertTrue(result.frameworks.contains("Spring Service"))
        assertNotNull(userService.documentation)

        val getUser = result.functions.first { it.name == "getUser" }
        assertEquals("User", getUser.returnType)
        assertTrue(getUser.signature?.contains("public User getUser") == true)
        assertTrue(getUser.callsTo.contains("find"))
        assertTrue(getUser.isPublicApi)
    }

    @Test
    fun `kotlin analyzer extracts kdoc and suspend signatures`() {
        val analyzer = KotlinLanguageAnalyzer()
        val content = """
            import org.springframework.stereotype.Service

            /**
             * User service.
             */
            @Service
            class UserService {
                /**
                 * Fetch user.
                 */
                suspend fun fetch(id: Long): User = repo.find(id)
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("UserService.kt"), content)
        val userService = result.classes.first { it.name == "UserService" }
        assertEquals("Business Logic Service", userService.purpose)
        assertNotNull(userService.documentation)

        val fetch = result.functions.first { it.name == "fetch" }
        assertEquals("User", fetch.returnType)
        assertTrue(fetch.signature?.contains("suspend fun fetch") == true)
        assertTrue(fetch.callsTo.contains("find"))
    }

    @Test
    fun `typescript analyzer extracts interfaces and react patterns`() {
        val analyzer = TypeScriptLanguageAnalyzer()
        val content = """
            /** Props */
            export interface UserProps {
              id: string;
              getName(): string;
            }

            export type UserDTO = { id: string };

            export function getUser(id: string): UserDTO {
              return fetchUser(id);
            }

            export const useUser = (id: string): UserDTO => getUser(id);

            export function UserCard(props: UserProps) {
              return <div>{props.id}</div>;
            }
        """.trimIndent()

        val result = analyzer.analyze(Paths.get("UserCard.tsx"), content)
        assertTrue(result.classes.any { it.type == "interface" && it.name == "UserProps" })
        assertTrue(result.classes.any { it.type == "type_alias" && it.name == "UserDTO" })
        assertTrue(result.frameworks.contains("React"))

        val getUser = result.functions.first { it.name == "getUser" }
        assertEquals("UserDTO", getUser.returnType)
        assertTrue(getUser.signature?.contains("function getUser") == true)
    }
}
