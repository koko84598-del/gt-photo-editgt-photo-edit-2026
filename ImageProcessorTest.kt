package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.ImageProcessor
import com.example.model.AdjustmentType
import com.example.model.ColorAdjustments
import com.example.model.CropAspectRatio
import com.example.model.CropRect
import com.example.model.CropTransformParams
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ImageProcessorTest {

  private fun createQuadrantBitmap(width: Int = 200, height: Int = 100): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    for (y in 0 until height) {
      for (x in 0 until width) {
        val color =
          when {
            x < width / 2 && y < height / 2 -> Color.RED
            x >= width / 2 && y < height / 2 -> Color.GREEN
            x < width / 2 && y >= height / 2 -> Color.BLUE
            else -> Color.YELLOW
          }
        bmp.setPixel(x, y, color)
      }
    }
    return bmp
  }

  private fun createUniformBitmap(color: Int, width: Int = 60, height: Int = 60): Bitmap {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    bmp.eraseColor(color)
    return bmp
  }

  @Test
  fun `aspect ratio presets compute exact ratios on landscape and portrait dimensions`() {
    val presets =
      listOf(
        CropAspectRatio.RATIO_1_1 to 1f,
        CropAspectRatio.RATIO_4_5 to 4f / 5f,
        CropAspectRatio.RATIO_3_4 to 3f / 4f,
        CropAspectRatio.RATIO_9_16 to 9f / 16f,
        CropAspectRatio.RATIO_16_9 to 16f / 9f,
      )

    val src = createQuadrantBitmap(width = 400, height = 200)
    presets.forEach { (preset, expectedRatio) ->
      val rect = ImageProcessor.computeCropRectForAspectRatio(400, 200, preset)
      val out =
        ImageProcessor.processBitmap(
          source = src,
          transform = CropTransformParams(cropRect = rect, aspectRatio = preset),
          adjustments = ColorAdjustments(),
          applyCrop = true,
        )
      val actualRatio = out.width.toFloat() / out.height.toFloat()
      assertEquals("Ratio mismatch for ${preset.label}", expectedRatio, actualRatio, 0.03f)
    }

    val origRect = ImageProcessor.computeCropRectForAspectRatio(400, 200, CropAspectRatio.ORIGINAL)
    assertTrue(origRect.isFullFrame)
  }

  @Test
  fun `free crop produces requested sub-rectangle without mutating source`() {
    val src = createQuadrantBitmap(width = 200, height = 100)
    val out =
      ImageProcessor.processBitmap(
        source = src,
        transform = CropTransformParams(cropRect = CropRect(0.25f, 0.2f, 0.75f, 0.8f)),
        adjustments = ColorAdjustments(),
        applyCrop = true,
      )
    assertEquals(100, out.width)
    assertEquals(60, out.height)
    // Original stays intact
    assertEquals(200, src.width)
    assertEquals(100, src.height)
    assertEquals(Color.RED, src.getPixel(10, 10))
  }

  @Test
  fun `rotate 90, 180 and 270 transform dimensions and pixel positions`() {
    val src = createQuadrantBitmap(width = 200, height = 100)

    val rot90 =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(rotation90Degrees = 90),
        ColorAdjustments(),
      )
    assertEquals(100, rot90.width)
    assertEquals(200, rot90.height)
    // Top-left (RED) moves to top-right on 90° clockwise rotation
    assertEquals(Color.RED, rot90.getPixel(rot90.width - 10, 10))

    val rot180 =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(rotation90Degrees = 180),
        ColorAdjustments(),
      )
    assertEquals(200, rot180.width)
    assertEquals(100, rot180.height)
    // Top-left (RED) moves to bottom-right on 180° rotation
    assertEquals(Color.RED, rot180.getPixel(rot180.width - 10, rot180.height - 10))

    val rot270 =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(rotation90Degrees = 270),
        ColorAdjustments(),
      )
    assertEquals(100, rot270.width)
    assertEquals(200, rot270.height)
    // Top-left (RED) moves to bottom-left on 270° clockwise rotation
    assertEquals(Color.RED, rot270.getPixel(10, rot270.height - 10))
  }

  @Test
  fun `horizontal and vertical flip swap quadrant pixels accurately`() {
    val src = createQuadrantBitmap(width = 120, height = 80)

    val flippedH =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(flipHorizontal = true),
        ColorAdjustments(),
      )
    assertEquals(Color.GREEN, flippedH.getPixel(10, 10))
    assertEquals(Color.RED, flippedH.getPixel(110, 10))

    val flippedV =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(flipVertical = true),
        ColorAdjustments(),
      )
    assertEquals(Color.BLUE, flippedV.getPixel(10, 10))
    assertEquals(Color.RED, flippedV.getPixel(10, 70))
  }

  @Test
  fun `straighten and perspective transforms alter pixels non-destructively`() {
    val src = createQuadrantBitmap(width = 160, height = 100)
    val straightened =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(straightenDegrees = 15f),
        ColorAdjustments(),
      )
    assertEquals(160, straightened.width)
    assertEquals(100, straightened.height)
    assertNotSame(src, straightened)

    val perspective =
      ImageProcessor.processBitmap(
        src,
        CropTransformParams(perspectiveVertical = 0.6f, perspectiveHorizontal = -0.3f),
        ColorAdjustments(),
      )
    assertEquals(160, perspective.width)
    assertEquals(100, perspective.height)
    assertNotSame(src, perspective)
  }

  @Test
  fun `all 12 color adjustments produce expected measurable pixel changes`() {
    val midGray = Color.rgb(120, 120, 120)
    val mutedTeal = Color.rgb(90, 140, 140)

    // 1. Brightness
    val brightOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(midGray),
        CropTransformParams(),
        ColorAdjustments(brightness = 0.5f),
      )
    assertTrue(Color.red(brightOut.getPixel(30, 30)) > 120)

    // 2. Contrast
    val contrastSrc = createUniformBitmap(Color.rgb(170, 170, 170))
    val contrastOut =
      ImageProcessor.processBitmap(
        contrastSrc,
        CropTransformParams(),
        ColorAdjustments(contrast = 0.6f),
      )
    assertTrue(Color.red(contrastOut.getPixel(30, 30)) > 170)

    // 3. Saturation (-1 -> grayscale)
    val satOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(Color.rgb(200, 80, 60)),
        CropTransformParams(),
        ColorAdjustments(saturation = -1f),
      )
    val pSat = satOut.getPixel(30, 30)
    assertTrue(abs(Color.red(pSat) - Color.blue(pSat)) <= 2)

    // 4. Exposure
    val expSrc = createUniformBitmap(Color.rgb(80, 80, 80))
    val expOut =
      ImageProcessor.processBitmap(
        expSrc,
        CropTransformParams(),
        ColorAdjustments(exposure = 1f),
      )
    assertTrue(Color.red(expOut.getPixel(30, 30)) >= 150)

    // 5. Highlights (affects bright pixel more than dark pixel)
    val hlBright =
      ImageProcessor.processBitmap(
        createUniformBitmap(Color.rgb(220, 220, 220)),
        CropTransformParams(),
        ColorAdjustments(highlights = -0.8f),
      )
    val hlDark =
      ImageProcessor.processBitmap(
        createUniformBitmap(Color.rgb(30, 30, 30)),
        CropTransformParams(),
        ColorAdjustments(highlights = -0.8f),
      )
    assertTrue(220 - Color.red(hlBright.getPixel(30, 30)) > 30 - Color.red(hlDark.getPixel(30, 30)))

    // 6. Shadows (lifts dark pixel more than bright pixel)
    val shDark =
      ImageProcessor.processBitmap(
        createUniformBitmap(Color.rgb(40, 40, 40)),
        CropTransformParams(),
        ColorAdjustments(shadows = 0.8f),
      )
    assertTrue(Color.red(shDark.getPixel(30, 30)) > 75)

    // 7. Temperature (warm increases red relative to blue)
    val tempOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(midGray),
        CropTransformParams(),
        ColorAdjustments(temperature = 0.8f),
      )
    val pTemp = tempOut.getPixel(30, 30)
    assertTrue(Color.red(pTemp) > Color.blue(pTemp))

    // 8. Tint (positive shifts magenta: R/B > G)
    val tintOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(midGray),
        CropTransformParams(),
        ColorAdjustments(tint = 0.8f),
      )
    val pTint = tintOut.getPixel(30, 30)
    assertTrue(Color.red(pTint) > Color.green(pTint))

    // 9. Vibrance (boosts muted color saturation)
    val vibOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(mutedTeal),
        CropTransformParams(),
        ColorAdjustments(vibrance = 0.9f),
      )
    val pVib = vibOut.getPixel(30, 30)
    assertTrue(Color.green(pVib) - Color.red(pVib) > 50)

    // 10. Hue (rotates color channels)
    val hueOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(Color.rgb(220, 40, 40)),
        CropTransformParams(),
        ColorAdjustments(hue = 120f),
      )
    val pHue = hueOut.getPixel(30, 30)
    assertTrue(Color.green(pHue) > Color.red(pHue))

    // 11. Vignette (corner darker than center)
    val vigOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(Color.rgb(180, 180, 180), width = 100, height = 100),
        CropTransformParams(),
        ColorAdjustments(vignette = 0.9f),
      )
    assertTrue(Color.red(vigOut.getPixel(50, 50)) > Color.red(vigOut.getPixel(2, 2)) + 30)

    // 12. Grain (introduces spatial pixel variation)
    val grainOut =
      ImageProcessor.processBitmap(
        createUniformBitmap(midGray),
        CropTransformParams(),
        ColorAdjustments(grain = 0.8f),
      )
    val samples = (0 until 20).map { Color.red(grainOut.getPixel(it * 2, it * 2)) }.toSet()
    assertTrue("Expected grain to vary pixel values", samples.size > 2)

    // Combined crop + rotate + color adjustment check
    val combinedSrc = createQuadrantBitmap(width = 200, height = 100)
    val combinedOut =
      ImageProcessor.processBitmap(
        source = combinedSrc,
        transform =
          CropTransformParams(
            cropRect = CropRect(0f, 0f, 0.5f, 1f),
            rotation90Degrees = 90,
          ),
        adjustments = ColorAdjustments().withValue(AdjustmentType.BRIGHTNESS, 0.25f),
        applyCrop = true,
      )
    assertEquals(50, combinedOut.width)
    assertEquals(200, combinedOut.height)
    assertNotEquals(Color.RED, combinedOut.getPixel(10, 10))
  }
}
