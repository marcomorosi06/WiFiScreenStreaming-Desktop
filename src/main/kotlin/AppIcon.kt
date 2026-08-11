/*
 * Copyright (c) 2026 Marco Morosi
 *
 * Licensed under the EUPL, Version 1.2 or – as soon they will be approved by
 * the European Commission - subsequent versions of the EUPL (the "Licence");
 * You may not use this work except in compliance with the Licence.
 * You may obtain a copy of the Licence at:
 *
 * https://joinup.ec.europa.eu/software/page/eupl
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the Licence is distributed on an "AS IS" basis,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Licence for the specific language governing permissions and
 * limitations under the Licence.
 */
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.res.loadSvgPainter
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.Density
import java.awt.Image
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

object AppIcon {

    private const val RESOURCE = "app_icon.png"

    private const val LOGO = "logo.svg"

    private val bitmap: BufferedImage by lazy { load() ?: blank() }

    val painter: Painter by lazy { BitmapPainter(bitmap.toComposeImageBitmap()) }

    fun logo(density: Density): Painter =
        runCatching { useResource(LOGO) { loadSvgPainter(it, density) } }.getOrElse { painter }

    fun awtImages(): List<Image> {
        val source = bitmap
        return listOf(16, 24, 32, 48, 64, 128, 256).mapNotNull { size ->
            runCatching { scaled(source, size) }.getOrNull()
        }
    }

    private fun scaled(source: BufferedImage, size: Int): BufferedImage {
        val target = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val g = target.createGraphics()
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_INTERPOLATION,
            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
        )
        g.setRenderingHint(
            java.awt.RenderingHints.KEY_RENDERING,
            java.awt.RenderingHints.VALUE_RENDER_QUALITY
        )
        g.drawImage(source, 0, 0, size, size, null)
        g.dispose()
        return target
    }

    private fun load(): BufferedImage? {
        val loaders = listOfNotNull(
            Thread.currentThread().contextClassLoader,
            AppIcon::class.java.classLoader,
            ClassLoader.getSystemClassLoader()
        )
        for (loader in loaders) {
            val stream = loader.getResourceAsStream(RESOURCE)
                ?: AppIcon::class.java.getResourceAsStream("/$RESOURCE")
                ?: continue
            val image = runCatching { stream.use { ImageIO.read(it) } }.getOrNull()
            if (image != null) return image
        }
        return null
    }

    private fun blank(): BufferedImage = BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)
}
