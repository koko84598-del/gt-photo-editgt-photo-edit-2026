package com.example

import android.graphics.Bitmap
import android.graphics.Color
import com.example.data.ColorEngine
import com.example.data.ImageProcessor
import com.example.model.ColorAdjustments
import com.example.model.ColorGradeWheel
import com.example.model.ColorGradingParams
import com.example.model.CropTransformParams
import com.example.model.CurvePoint
import com.example.model.GradingRange
import com.example.model.HslAdjustment
import com.example.model.HslColorRange
import com.example.model.HslMixerParams
import com.example.model.ToneCurveChannel
import com.example.model.ToneCurveParams
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ColorEngineTest {

  private fun uniformBitmap(color: Int, size: Int = 24): Bitmap =
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

  private fun rgbFromHue(hueDeg: Float, sat: Float = 0.85f, light: Float = 0.5f): Int {
    val (r, g, b) = ColorEngine.hslToRgb(hueDeg, sat, light)
    return Color.rgb(
      (r * 255f).toInt().coerceIn(0, 255),
      (g * 255f).toInt().coerceIn(0, 255),
      (b * 255f).toInt().coerceIn(0, 255),
    )
  }

  private fun rgbLuminance(pixel: Int): Float =
    (0.299f * Color.red(pixel) + 0.587f * Color.green(pixel) + 0.114f * Color.blue(pixel))

  @Test
  fun `all 8 hsl color ranges selectively adjust matching hue and leave distant hue untouched`() {
    HslColorRange.entries.forEach { range ->
      val targetColor = rgbFromHue(range.centerHueDegrees)
      val oppositeColor = rgbFromHue((range.centerHueDegrees + 180f) % 360f)

      val hslSatBoost =
        HslMixerParams().withRange(range, HslAdjustment(hue = 45f, saturation = -60f, luminance = 50f))

      val targetOut =
        ImageProcessor.processBitmap(
          source = uniformBitmap(targetColor),
          transform = CropTransformParams(),
          adjustments = ColorAdjustments(),
          hslMixer = hslSatBoost,
        )
      val oppositeOut =
        ImageProcessor.processBitmap(
          source = uniformBitmap(oppositeColor),
          transform = CropTransformParams(),
          adjustments = ColorAdjustments(),
          hslMixer = hslSatBoost,
        )

      val outPixel = targetOut.getPixel(12, 12)
      assertNotEquals("Expected ${range.label} target pixel to change", targetColor, outPixel)

      val oppPixel = oppositeOut.getPixel(12, 12)
      assertTrue(
        "Opposite hue should remain unchanged for ${range.label}",
        abs(Color.red(oppositeColor) - Color.red(oppPixel)) <= 2 &&
          abs(Color.green(oppositeColor) - Color.green(oppPixel)) <= 2 &&
          abs(Color.blue(oppositeColor) - Color.blue(oppPixel)) <= 2,
      )
    }
  }

  @Test
  fun `hsl mixer transitions smoothly between neighboring ranges without affecting grays`() {
    val params = HslMixerParams().withRange(HslColorRange.RED, HslAdjustment(luminance = 80f))
    val lut = ColorEngine.buildHslLut(params)

    // Peak at Red (0 deg), gradual falloff through 15 deg, zero by Orange (30 deg)
    assertTrue(lut.lumShifts[0] > lut.lumShifts[15])
    assertTrue(lut.lumShifts[15] > 0f)
    assertEquals(0f, lut.lumShifts[30], 0.0001f)

    // Near-neutral gray remains untouched
    val gray = Color.rgb(128, 128, 128)
    val grayOut =
      ImageProcessor.processBitmap(
        source = uniformBitmap(gray),
        transform = CropTransformParams(),
        adjustments = ColorAdjustments(),
        hslMixer = params,
      )
    assertEquals(gray, grayOut.getPixel(12, 12))
  }

  @Test
  fun `tone curve supports composite rgb and per-channel curves with monotone stability`() {
    // 1. Composite RGB lift curve boosts mid-gray equally across R/G/B
    val liftedCurve =
      ToneCurveParams().withPoints(
        ToneCurveChannel.RGB,
        listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.75f), CurvePoint(1f, 1f)),
      )
    val midGray = Color.rgb(128, 128, 128)
    val liftedOut =
      ImageProcessor.processBitmap(
        source = uniformBitmap(midGray),
        transform = CropTransformParams(),
        adjustments = ColorAdjustments(),
        toneCurve = liftedCurve,
      )
    val lp = liftedOut.getPixel(10, 10)
    assertTrue("Expected composite RGB curve to lift midtone", Color.red(lp) > 175)
    assertEquals(Color.red(lp), Color.green(lp))
    assertEquals(Color.green(lp), Color.blue(lp))

    // 2. Red-only curve boosts Red without altering Green or Blue
    val redBoost =
      ToneCurveParams().withPoints(
        ToneCurveChannel.RED,
        listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.85f), CurvePoint(1f, 1f)),
      )
    val redOut =
      ImageProcessor.processBitmap(
        source = uniformBitmap(midGray),
        transform = CropTransformParams(),
        adjustments = ColorAdjustments(),
        toneCurve = redBoost,
      ).getPixel(10, 10)
    assertTrue(Color.red(redOut) > 195)
    assertEquals(128, Color.green(redOut))
    assertEquals(128, Color.blue(redOut))

    // 3. Monotone S-curve never overshoots [0..1] or reverses slope
    val steepSCurve =
      listOf(
        CurvePoint(0f, 0f),
        CurvePoint(0.25f, 0.05f),
        CurvePoint(0.75f, 0.95f),
        CurvePoint(1f, 1f),
      )
    var prevY = -1f
    for (i in 0..100) {
      val x = i / 100f
      val y = ColorEngine.evaluateMonotoneCurve(steepSCurve, x)
      assertTrue("Curve out of bounds at x=$x: y=$y", y in 0f..1f)
      assertTrue("Curve must stay monotone non-decreasing at x=$x", y >= prevY - 1e-5f)
      prevY = y
    }

    // 4. Unordered or sparse points are sanitized safely
    val sanitized =
      ToneCurveParams.sanitizePoints(
        listOf(CurvePoint(0.8f, 0.9f), CurvePoint(0.2f, 0.1f), CurvePoint(1f, 1f), CurvePoint(0f, 0f))
      )
    assertEquals(4, sanitized.size)
    assertTrue(sanitized[1].x < sanitized[2].x)
  }

  @Test
  fun `color grading targets shadows, midtones, and highlights selectively`() {
    val shadowTone = uniformBitmap(Color.rgb(25, 25, 25))
    val midTone = uniformBitmap(Color.rgb(128, 128, 128))
    val highlightTone = uniformBitmap(Color.rgb(235, 235, 235))

    // Cool blue tint on Shadows only
    val shadowGrade =
      ColorGradingParams(shadows = ColorGradeWheel(hueDegrees = 240f, saturation = 0.85f, luminance = 0f))

    val gradedShadow =
      ImageProcessor.processBitmap(shadowTone, CropTransformParams(), ColorAdjustments(), colorGrading = shadowGrade)
        .getPixel(10, 10)
    val gradedHighlightWithShadow =
      ImageProcessor.processBitmap(highlightTone, CropTransformParams(), ColorAdjustments(), colorGrading = shadowGrade)
        .getPixel(10, 10)

    assertTrue(
      "Expected blue > red in shadows",
      Color.blue(gradedShadow) - Color.red(gradedShadow) > 25,
    )
    assertTrue(
      "Highlights should stay unaffected by shadow grading",
      abs(Color.blue(gradedHighlightWithShadow) - Color.red(gradedHighlightWithShadow)) <= 1,
    )

    // Warm red-amber tint on Highlights only
    val highlightGrade =
      ColorGradingParams(highlights = ColorGradeWheel(hueDegrees = 20f, saturation = 0.8f, luminance = 0f))
    val gradedHighlight =
      ImageProcessor.processBitmap(highlightTone, CropTransformParams(), ColorAdjustments(), colorGrading = highlightGrade)
        .getPixel(10, 10)
    val gradedShadowWithHighlight =
      ImageProcessor.processBitmap(shadowTone, CropTransformParams(), ColorAdjustments(), colorGrading = highlightGrade)
        .getPixel(10, 10)

    assertTrue(
      "Expected red > blue in highlights",
      Color.red(gradedHighlight) - Color.blue(gradedHighlight) > 15,
    )
    assertTrue(
      "Shadows should stay unaffected by highlight grading",
      abs(Color.red(gradedShadowWithHighlight) - Color.blue(gradedShadowWithHighlight)) <= 1,
    )

    // Midtone luminance shift targets mid-gray strongly
    val midGrade =
      ColorGradingParams(midtones = ColorGradeWheel(hueDegrees = 120f, saturation = 0.6f, luminance = 0.5f))
    val gradedMid =
      ImageProcessor.processBitmap(midTone, CropTransformParams(), ColorAdjustments(), colorGrading = midGrade)
        .getPixel(10, 10)
    assertTrue(rgbLuminance(gradedMid) > 140f)
    assertTrue(Color.green(gradedMid) > Color.red(gradedMid))
  }

  @Test
  fun `combined hsl, tone curve, color grading and crop preserve source bitmap immutability`() {
    val src = uniformBitmap(Color.rgb(200, 60, 60), size = 40)
    val hsl = HslMixerParams().withRange(HslColorRange.RED, HslAdjustment(saturation = -30f))
    val curve =
      ToneCurveParams().withPoints(
        ToneCurveChannel.BLUE,
        listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.7f), CurvePoint(1f, 1f)),
      )
    val grading =
      ColorGradingParams(midtones = ColorGradeWheel(hueDegrees = 180f, saturation = 0.4f, luminance = 0.1f))

    val out =
      ImageProcessor.processBitmap(
        source = src,
        transform = CropTransformParams(rotation90Degrees = 90),
        adjustments = ColorAdjustments(contrast = 0.15f),
        hslMixer = hsl,
        toneCurve = curve,
        colorGrading = grading,
      )

    assertEquals(40, out.width)
    assertEquals(40, out.height)
    assertNotEquals(src.getPixel(10, 10), out.getPixel(10, 10))
    // Source bitmap remains untouched
    assertEquals(Color.rgb(200, 60, 60), src.getPixel(10, 10))
  }
}
