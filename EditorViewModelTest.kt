package com.example

import android.graphics.Bitmap
import android.net.Uri
import com.example.data.PhotoDecoder
import com.example.model.AdjustmentType
import com.example.model.CropAspectRatio
import com.example.model.EditorCategory
import com.example.model.EditorFont
import com.example.model.ImportedPhoto
import com.example.viewmodel.EditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EditorViewModelTest {

  private val testDispatcher = StandardTestDispatcher()

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun fakeDecoder(shouldFail: Boolean = false): PhotoDecoder =
    object : PhotoDecoder {
      override suspend fun decode(uri: Uri): Result<ImportedPhoto> {
        return if (shouldFail) {
          Result.failure(IllegalStateException("Corrupted image"))
        } else {
          val bmp = Bitmap.createBitmap(240, 120, Bitmap.Config.ARGB_8888)
          Result.success(
            ImportedPhoto(
              uriString = uri.toString(),
              displayName = "studio_portrait.jpg",
              originalWidth = 4032,
              originalHeight = 3024,
              bitmap = bmp,
            )
          )
        }
      }
    }

  @Test
  fun `import photo preserves original dimensions`() = runTest {
    val vm = EditorViewModel(fakeDecoder(), testDispatcher)
    vm.importPhoto(Uri.parse("content://media/external/images/media/42"))
    advanceUntilIdle()

    val photo = vm.uiState.value.importedPhoto
    assertNotNull(photo)
    assertEquals(4032, photo?.originalWidth)
    assertEquals(3024, photo?.originalHeight)
    assertEquals("studio_portrait.jpg", photo?.displayName)
    assertNull(vm.uiState.value.photoError)
  }

  @Test
  fun `import photo failure exposes error message`() = runTest {
    val vm = EditorViewModel(fakeDecoder(shouldFail = true), testDispatcher)
    vm.importPhoto(Uri.parse("content://invalid/1"))
    advanceUntilIdle()

    assertNull(vm.uiState.value.importedPhoto)
    assertEquals("Corrupted image", vm.uiState.value.photoError)
  }

  @Test
  fun `independent burmese and english text layers edit drag lock and delete`() = runTest {
    val vm = EditorViewModel(fakeDecoder(), testDispatcher)

    vm.addTextLayer("Hello Studio", EditorFont.SPACE_GROTESK)
    val englishId = vm.uiState.value.selectedLayerId!!

    vm.addTextLayer("မင်္ဂလာပါ", EditorFont.NOTO_SANS_MYANMAR)
    val burmeseId = vm.uiState.value.selectedLayerId!!

    assertEquals(2, vm.uiState.value.textLayers.size)
    assertEquals(EditorCategory.TEXT, vm.uiState.value.activeCategory)

    // Edit Burmese text independently
    vm.updateSelectedLayerText("ရန်ကုန်မြို့")
    val editedBurmese = vm.uiState.value.textLayers.first { it.id == burmeseId }
    val untouchedEnglish = vm.uiState.value.textLayers.first { it.id == englishId }
    assertEquals("ရန်ကုန်မြို့", editedBurmese.text)
    assertEquals("Hello Studio", untouchedEnglish.text)

    // Move Burmese layer and verify clamping + independence
    val startX = editedBurmese.xFraction
    vm.moveTextLayer(burmeseId, 0.2f, -0.1f)
    val movedBurmese = vm.uiState.value.textLayers.first { it.id == burmeseId }
    assertTrue(movedBurmese.xFraction > startX)
    assertEquals(
      untouchedEnglish.xFraction,
      vm.uiState.value.textLayers.first { it.id == englishId }.xFraction,
    )

    // Lock prevents movement
    vm.toggleLayerLock(burmeseId)
    val lockedX = vm.uiState.value.textLayers.first { it.id == burmeseId }.xFraction
    vm.moveTextLayer(burmeseId, 0.2f, 0.2f)
    assertEquals(lockedX, vm.uiState.value.textLayers.first { it.id == burmeseId }.xFraction, 0.0001f)

    // Delete removes only the deleted layer
    vm.deleteLayer(englishId)
    assertEquals(1, vm.uiState.value.textLayers.size)
    assertFalse(vm.uiState.value.textLayers.any { it.id == englishId })
  }

  @Test
  fun `crop, rotate, flip, straighten, perspective and color adjustments update non-destructively and reset cleanly`() =
    runTest {
      val vm = EditorViewModel(fakeDecoder(), testDispatcher)
      vm.importPhoto(Uri.parse("content://gallery/test.jpg"))
      advanceUntilIdle()

      // Switch to Crop & apply 1:1 ratio + rotate 90 + flip + straighten + perspective
      vm.selectCategory(EditorCategory.CROP)
      vm.setCropAspectRatio(CropAspectRatio.RATIO_1_1)
      vm.rotateBy90()
      vm.toggleFlipHorizontal()
      vm.updateStraightenDegrees(12f)
      vm.updatePerspectiveVertical(0.4f)
      advanceUntilIdle()

      assertEquals(90, vm.uiState.value.cropTransform.rotation90Degrees)
      assertTrue(vm.uiState.value.cropTransform.flipHorizontal)
      assertEquals(12f, vm.uiState.value.cropTransform.straightenDegrees, 0.01f)

      // Leaving Crop applies crop to previewBitmap -> square 1:1 output
      vm.selectCategory(EditorCategory.ADJUST)
      advanceUntilIdle()
      val croppedPreview = vm.uiState.value.previewBitmap!!
      assertEquals(croppedPreview.width, croppedPreview.height)

      // Update multiple color adjustments & verify single reset vs full reset
      vm.updateColorAdjustment(AdjustmentType.EXPOSURE, 0.8f)
      vm.updateColorAdjustment(AdjustmentType.SATURATION, -0.5f)
      vm.updateColorAdjustment(AdjustmentType.VIGNETTE, 0.6f)
      advanceUntilIdle()

      assertEquals(0.8f, vm.uiState.value.colorAdjustments.exposure, 0.001f)
      vm.resetColorAdjustment(AdjustmentType.EXPOSURE)
      assertEquals(0f, vm.uiState.value.colorAdjustments.exposure, 0.001f)
      assertEquals(-0.5f, vm.uiState.value.colorAdjustments.saturation, 0.001f)

      vm.resetAllColorAdjustments()
      assertTrue(vm.uiState.value.colorAdjustments.isIdentity)

      vm.resetCropTransform()
      advanceUntilIdle()
      assertTrue(vm.uiState.value.cropTransform.isIdentity)
      // Original bitmap dimensions preserved
      assertEquals(240, vm.uiState.value.importedPhoto?.bitmap?.width)
      assertEquals(120, vm.uiState.value.importedPhoto?.bitmap?.height)
    }

  @Test
  fun `milestone 3 hsl mixer, tone curve points, and color grading update preview and reset cleanly`() =
    runTest {
      val vm = EditorViewModel(fakeDecoder(), testDispatcher)
      vm.importPhoto(Uri.parse("content://gallery/color_test.jpg"))
      advanceUntilIdle()

      // 1. HSL Color Mixer: update Red and Blue, reset Red only, then reset all
      vm.updateHslRange(
        com.example.model.HslColorRange.RED,
        com.example.model.HslAdjustment(hue = 30f, saturation = 50f, luminance = -20f),
      )
      vm.updateHslRange(
        com.example.model.HslColorRange.BLUE,
        com.example.model.HslAdjustment(saturation = -40f),
      )
      advanceUntilIdle()

      assertEquals(30f, vm.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.RED).hue, 0.01f)
      assertEquals(-40f, vm.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.BLUE).saturation, 0.01f)

      vm.resetHslRange(com.example.model.HslColorRange.RED)
      assertTrue(vm.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.RED).isIdentity)
      assertFalse(vm.uiState.value.hslMixer.isIdentity)

      vm.resetAllHsl()
      assertTrue(vm.uiState.value.hslMixer.isIdentity)

      // 2. RGB Tone Curve: add, drag/update, remove interior point, reset channel, reset all
      vm.addToneCurvePoint(com.example.model.ToneCurveChannel.RGB, com.example.model.CurvePoint(0.5f, 0.7f))
      vm.addToneCurvePoint(com.example.model.ToneCurveChannel.RED, com.example.model.CurvePoint(0.4f, 0.65f))
      advanceUntilIdle()

      assertEquals(3, vm.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RGB).size)
      vm.updateToneCurvePoint(com.example.model.ToneCurveChannel.RGB, 1, com.example.model.CurvePoint(0.55f, 0.8f))
      assertEquals(0.8f, vm.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RGB)[1].y, 0.01f)

      vm.removeToneCurvePoint(com.example.model.ToneCurveChannel.RGB, 1)
      assertEquals(2, vm.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RGB).size)

      vm.resetToneCurveChannel(com.example.model.ToneCurveChannel.RED)
      assertTrue(
        com.example.model.ToneCurveParams.isChannelIdentity(
          vm.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RED)
        )
      )

      vm.addToneCurvePoint(com.example.model.ToneCurveChannel.BLUE, com.example.model.CurvePoint(0.3f, 0.5f))
      vm.resetAllToneCurves()
      assertTrue(vm.uiState.value.toneCurve.isIdentity)

      // 3. Color Grading: update Shadows & Highlights, reset Shadows, reset all
      vm.updateColorGrading(
        com.example.model.GradingRange.SHADOWS,
        com.example.model.ColorGradeWheel(hueDegrees = 220f, saturation = 0.7f, luminance = -0.2f),
      )
      vm.updateColorGrading(
        com.example.model.GradingRange.HIGHLIGHTS,
        com.example.model.ColorGradeWheel(hueDegrees = 45f, saturation = 0.5f, luminance = 0.25f),
      )
      advanceUntilIdle()
      assertNotNull(vm.renderFullResolutionEditedBitmap())

      vm.resetColorGradingRange(com.example.model.GradingRange.SHADOWS)
      assertTrue(vm.uiState.value.colorGrading.shadows.isIdentity)
      assertFalse(vm.uiState.value.colorGrading.highlights.isIdentity)

      vm.resetAllColorGrading()
      assertTrue(vm.uiState.value.colorGrading.isIdentity)
    }
}
