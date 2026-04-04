package pl.jclab.refio.core.services

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import javax.imageio.ImageIO

class ImagePreparationService {
    companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val MAX_DIMENSION = 2048
        val SUPPORTED_TYPES = setOf("image/png", "image/jpeg", "image/gif", "image/webp")
    }

    data class PreparedImage(
        val mediaType: String,
        val base64Data: String,
        val originalSizeBytes: Int,
        val preparedSizeBytes: Int
    )

    fun prepare(fileBytes: ByteArray, mediaType: String): PreparedImage {
        require(mediaType in SUPPORTED_TYPES) { "Unsupported image type: $mediaType" }
        require(fileBytes.size <= MAX_IMAGE_BYTES) { "Image too large: ${fileBytes.size} bytes" }

        val resized = resizeIfNeeded(fileBytes, mediaType)
        val base64 = Base64.getEncoder().encodeToString(resized)

        return PreparedImage(
            mediaType = mediaType,
            base64Data = base64,
            originalSizeBytes = fileBytes.size,
            preparedSizeBytes = resized.size
        )
    }

    private fun resizeIfNeeded(bytes: ByteArray, mediaType: String): ByteArray {
        val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return bytes
        if (image.width <= MAX_DIMENSION && image.height <= MAX_DIMENSION) {
            return bytes
        }

        val scale = MAX_DIMENSION.toDouble() / maxOf(image.width, image.height)
        val newW = (image.width * scale).toInt().coerceAtLeast(1)
        val newH = (image.height * scale).toInt().coerceAtLeast(1)
        val imageType = if (image.type == BufferedImage.TYPE_CUSTOM) BufferedImage.TYPE_INT_ARGB else image.type
        val scaled = BufferedImage(newW, newH, imageType)
        val g = scaled.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, 0, 0, newW, newH, null)
        g.dispose()

        val out = ByteArrayOutputStream()
        val format = when (mediaType) {
            "image/png" -> "png"
            "image/gif" -> "gif"
            "image/webp" -> "png"
            else -> "jpg"
        }
        ImageIO.write(scaled, format, out)
        return out.toByteArray()
    }
}
