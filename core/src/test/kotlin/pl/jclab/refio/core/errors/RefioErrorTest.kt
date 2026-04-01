package pl.jclab.refio.core.errors

import kotlin.test.Test
import kotlin.test.assertTrue

class RefioErrorTest {

    @Test
    fun `provider not configured should include provider and key in message`() {
        val error = RefioError.ProviderNotConfigured("zai", "api_key")

        assertTrue(error.message.orEmpty().contains("zai"))
        assertTrue(error.message.orEmpty().contains("api_key"))
    }
}
