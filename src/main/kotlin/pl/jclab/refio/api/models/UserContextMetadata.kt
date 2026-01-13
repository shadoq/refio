package pl.jclab.refio.api.models

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import pl.jclab.refio.core.utils.GsonInstance

/**
 * Metadata stored alongside user chat messages describing attached context references.
 */
data class UserContextMetadata(
    val type: String = TYPE,
    @SerializedName("context_refs")
    val contextRefs: List<ContextReference> = emptyList(),
    @SerializedName("context_summary")
    val contextSummary: String? = null
) {
    companion object {
        const val TYPE: String = "user_context"

        private val gson get() = GsonInstance.gson

        fun toJson(refs: List<ContextReference>, summary: String): String {
            val metadata = UserContextMetadata(
                type = TYPE,
                contextRefs = refs,
                contextSummary = summary
            )
            return gson.toJson(metadata)
        }

        fun fromJson(json: String?): UserContextMetadata? {
            if (json.isNullOrBlank()) return null

            return try {
                val element: JsonElement = gson.fromJson(json, JsonElement::class.java)
                if (!element.isJsonObject) return null

                val obj: JsonObject = element.asJsonObject
                if (obj.get("type")?.asString != TYPE) return null

                gson.fromJson(json, UserContextMetadata::class.java)
            } catch (e: Exception) {
                null
            }
        }
    }
}

