package com.example

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.swipeRight
import com.example.data.PhotoDecoder
import com.example.model.AdjustmentType
import com.example.model.ImportedPhoto
import com.example.ui.EditorRoute
import com.example.ui.theme.GTPhotoEditTheme
import com.example.viewmodel.EditorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w411dp-h915dp-420dpi")
class EditorUiRobolectricTest {

  @get:Rule val composeTestRule = createComposeRule()

  private val fakeDecoder =
    object : PhotoDecoder {
      override suspend fun decode(uri: Uri): Result<ImportedPhoto> {
        val bitmap = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        return Result.success(
          ImportedPhoto(
            uriString = uri.toString(),
            displayName = "yangon_sunset.jpg",
            originalWidth = 4000,
            originalHeight = 3000,
            bitmap = bitmap,
          )
        )
      }
    }

  @Test
  fun `full milestone 1 workflow - import photo, switch tabs with fixed preview, add edit drag and delete burmese and english layers`() {
    val viewModel = EditorViewModel(fakeDecoder)
    var pickerLaunched = false

    composeTestRule.setContent {
      GTPhotoEditTheme {
        EditorRoute(
          viewModel = viewModel,
          onLaunchPhotoPicker = {
            pickerLaunched = true
            viewModel.importPhoto(Uri.parse("content://gallery/yangon_sunset.jpg"))
          },
        )
      }
    }

    composeTestRule.waitForIdle()

    // 1. Empty state preview shows Import CTA; tapping triggers gallery import
    composeTestRule.onNodeWithTag("photo_preview_stage").assertIsDisplayed()
    composeTestRule.onNodeWithTag("import_photo_button").assertIsDisplayed().performClick()
    composeTestRule.waitForIdle()

    assertTrue(pickerLaunched)
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("top_bar_resolution").assertIsDisplayed()
    composeTestRule.onNodeWithText("4000 × 3000 px").assertIsDisplayed()

    // 2. Switch bottom navigation to Text; fixed photo preview remains visible
    composeTestRule.onNodeWithTag("nav_text").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("text_panel").assertIsDisplayed()

    // 3. Add independent English and Burmese text layers
    composeTestRule.onNodeWithTag("add_english_text_button").performClick()
    composeTestRule.waitForIdle()
    val englishLayerId = viewModel.uiState.value.selectedLayerId!!

    composeTestRule.onNodeWithTag("add_burmese_text_button").performClick()
    composeTestRule.waitForIdle()
    val burmeseLayerId = viewModel.uiState.value.selectedLayerId!!

    assertEquals(2, viewModel.uiState.value.textLayers.size)
    composeTestRule.onNodeWithTag("text_layer_$englishLayerId").assertIsDisplayed()
    composeTestRule.onNodeWithTag("text_layer_$burmeseLayerId").assertIsDisplayed()

    // 4. Edit selected Burmese layer text
    composeTestRule.onNodeWithTag("selected_text_input").performTextReplacement("ရွှေတိဂုံ")
    composeTestRule.waitForIdle()
    assertEquals(
      "ရွှေတိဂုံ",
      viewModel.uiState.value.textLayers.first { it.id == burmeseLayerId }.text,
    )

    // 5. Drag Burmese layer on preview stage
    val xBefore = viewModel.uiState.value.textLayers.first { it.id == burmeseLayerId }.xFraction
    composeTestRule.onNodeWithTag("text_layer_$burmeseLayerId").performTouchInput { swipeRight() }
    composeTestRule.waitForIdle()
    val xAfter = viewModel.uiState.value.textLayers.first { it.id == burmeseLayerId }.xFraction
    assertTrue("Expected layer to move right after drag", xAfter > xBefore)

    // 6. Switch to Layers tab; photo preview still visible; delete English layer
    composeTestRule.onNodeWithTag("nav_layers").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("layers_panel").assertIsDisplayed()

    composeTestRule.onNodeWithTag("layer_delete_$englishLayerId").performClick()
    composeTestRule.waitForIdle()
    assertEquals(1, viewModel.uiState.value.textLayers.size)
    assertEquals(burmeseLayerId, viewModel.uiState.value.textLayers.single().id)
  }

  @Test
  fun `milestone 2 workflow - crop aspect ratios, rotate, flip, color adjustments and resets with fixed preview`() {
    val viewModel = EditorViewModel(fakeDecoder)

    composeTestRule.setContent {
      GTPhotoEditTheme {
        EditorRoute(
          viewModel = viewModel,
          onLaunchPhotoPicker = {
            viewModel.importPhoto(Uri.parse("content://gallery/yangon_sunset.jpg"))
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("import_photo_button").performClick()
    composeTestRule.waitForIdle()

    // Switch to Crop category
    composeTestRule.onNodeWithTag("nav_crop").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("interactive_crop_overlay").assertIsDisplayed()
    composeTestRule.onNodeWithTag("crop_panel").assertIsDisplayed()

    // Select 1:1 aspect ratio, rotate 90°, and flip horizontal
    composeTestRule.onNodeWithTag("aspect_ratio_ratio_1_1").performClick()
    composeTestRule.onNodeWithTag("rotate_90_button").performClick()
    composeTestRule.onNodeWithTag("flip_horizontal_button").performClick()
    composeTestRule.waitForIdle()

    assertEquals(90, viewModel.uiState.value.cropTransform.rotation90Degrees)
    assertTrue(viewModel.uiState.value.cropTransform.flipHorizontal)

    // Switch to Adjust category; preview remains visible and displays cropped bitmap
    composeTestRule.onNodeWithTag("nav_adjust").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("adjust_panel").assertIsDisplayed()

    // Drag slider thumb from center (0f) to right (+positive brightness)
    composeTestRule.onNodeWithTag("active_adjustment_slider").performTouchInput {
      swipe(
        start = center,
        end = androidx.compose.ui.geometry.Offset(right * 0.9f, centerY),
        durationMillis = 250L,
      )
    }
    composeTestRule.waitForIdle()
    val actualBrightness = viewModel.uiState.value.colorAdjustments.brightness
    assertTrue("Expected brightness > 0f after dragging slider right, was $actualBrightness", actualBrightness > 0f)

    composeTestRule.onNodeWithTag("reset_single_adjustment_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(0f, viewModel.uiState.value.colorAdjustments.brightness, 0.001f)

    viewModel.updateColorAdjustment(AdjustmentType.SATURATION, 0.5f)
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("reset_all_adjustments_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.colorAdjustments.isIdentity)
  }

  @Test
  fun `milestone 3 workflow - hsl mixer, tone curve, and color grading update state and keep preview visible`() {
    val viewModel = EditorViewModel(fakeDecoder)

    composeTestRule.setContent {
      GTPhotoEditTheme {
        EditorRoute(
          viewModel = viewModel,
          onLaunchPhotoPicker = {
            viewModel.importPhoto(Uri.parse("content://gallery/yangon_sunset.jpg"))
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("import_photo_button").performClick()
    composeTestRule.waitForIdle()

    // 1. HSL Color Mixer
    composeTestRule.onNodeWithTag("nav_hsl").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("hsl_panel").assertIsDisplayed()

    composeTestRule.onNodeWithTag("hsl_swatch_green").performClick()
    composeTestRule.onNodeWithTag("hsl_saturation_slider").performTouchInput {
      swipe(
        start = center,
        end = androidx.compose.ui.geometry.Offset(right * 0.9f, centerY),
        durationMillis = 200L,
      )
    }
    composeTestRule.waitForIdle()
    assertTrue(
      viewModel.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.GREEN).saturation > 0f
    )

    composeTestRule.onNodeWithTag("hsl_swatch_blue").performClick()
    composeTestRule.onNodeWithTag("hsl_luminance_slider").performTouchInput {
      swipe(
        start = center,
        end = androidx.compose.ui.geometry.Offset(left + (right - left) * 0.15f, centerY),
        durationMillis = 200L,
      )
    }
    composeTestRule.waitForIdle()
    assertTrue(
      viewModel.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.BLUE).luminance < 0f
    )

    // Reset selected (Blue) leaves Green intact, then Reset All clears everything
    composeTestRule.onNodeWithTag("reset_hsl_range_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.BLUE).isIdentity)
    assertTrue(
      viewModel.uiState.value.hslMixer.forRange(com.example.model.HslColorRange.GREEN).saturation > 0f
    )

    composeTestRule.onNodeWithTag("reset_all_hsl_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.hslMixer.isIdentity)

    // 2. RGB Tone Curve
    composeTestRule.onNodeWithTag("nav_curve").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("tone_curve_panel").assertIsDisplayed()

    composeTestRule.onNodeWithTag("tone_curve_canvas").performTouchInput {
      down(androidx.compose.ui.geometry.Offset(centerX, height * 0.25f))
      up()
    }
    composeTestRule.waitForIdle()
    assertEquals(
      3,
      viewModel.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RGB).size,
    )

    // Remove selected interior point
    composeTestRule.onNodeWithTag("remove_curve_point_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(
      2,
      viewModel.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RGB).size,
    )

    // Switch to Red channel, add point, then Reset All
    composeTestRule.onNodeWithTag("curve_channel_red").performClick()
    composeTestRule.onNodeWithTag("tone_curve_canvas").performTouchInput {
      down(androidx.compose.ui.geometry.Offset(centerX, height * 0.3f))
      up()
    }
    composeTestRule.waitForIdle()
    assertEquals(
      3,
      viewModel.uiState.value.toneCurve.pointsFor(com.example.model.ToneCurveChannel.RED).size,
    )
    composeTestRule.onNodeWithTag("reset_all_curves_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.toneCurve.isIdentity)

    // 3. Color Grading
    composeTestRule.onNodeWithTag("nav_grading").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("imported_photo_image").assertIsDisplayed()
    composeTestRule.onNodeWithTag("color_grading_panel").assertIsDisplayed()

    composeTestRule.onNodeWithTag("grading_color_wheel").performTouchInput {
      down(androidx.compose.ui.geometry.Offset(right * 0.8f, centerY))
      up()
    }
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.colorGrading.shadows.saturation > 0.2f)

    composeTestRule.onNodeWithTag("grading_range_highlights").performClick()
    composeTestRule.onNodeWithTag("grading_saturation_slider").performTouchInput {
      swipe(
        start = androidx.compose.ui.geometry.Offset(left + (right - left) * 0.1f, centerY),
        end = androidx.compose.ui.geometry.Offset(right * 0.85f, centerY),
        durationMillis = 200L,
      )
    }
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.colorGrading.highlights.saturation > 0.2f)

    composeTestRule.onNodeWithTag("reset_grading_range_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.colorGrading.highlights.isIdentity)
    assertTrue(viewModel.uiState.value.colorGrading.shadows.saturation > 0.2f)

    composeTestRule.onNodeWithTag("reset_all_grading_button").performClick()
    composeTestRule.waitForIdle()
    assertTrue(viewModel.uiState.value.colorGrading.isIdentity)
  }

  @Test
  fun `milestone 4 workflow - undo redo buttons, save and open project dialogs, and gallery export dialog`() {
    val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
    val repo = com.example.data.LocalProjectRepository(context)
    var exportedFormat: com.example.model.ExportFormat? = null
    var exportedWidth = 0
    val spyExporter =
      object : com.example.data.GalleryExporter {
        override suspend fun exportToGallery(
          bitmap: Bitmap,
          sourceDisplayName: String,
          format: com.example.model.ExportFormat,
          jpegQuality: Int,
        ): Result<com.example.model.ExportResult> {
          exportedFormat = format
          exportedWidth = bitmap.width
          return Result.success(
            com.example.model.ExportResult(
              outputUriString = "content://media/external/images/media/1",
              displayName = "exported.${format.extension}",
              width = bitmap.width,
              height = bitmap.height,
              format = format,
            )
          )
        }
      }
    val viewModel =
      EditorViewModel(
        photoDecoder = fakeDecoder,
        projectRepository = repo,
        galleryExporter = spyExporter,
        appContext = context,
      )

    composeTestRule.setContent {
      GTPhotoEditTheme {
        EditorRoute(
          viewModel = viewModel,
          onLaunchPhotoPicker = {
            viewModel.importPhoto(Uri.parse("content://gallery/yangon_sunset.jpg"))
          },
        )
      }
    }

    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("import_photo_button").performClick()
    composeTestRule.waitForIdle()

    // Perform edits: add Burmese text + rotate 90°
    composeTestRule.onNodeWithTag("nav_text").performClick()
    composeTestRule.onNodeWithTag("add_burmese_text_button").performClick()
    composeTestRule.onNodeWithTag("nav_crop").performClick()
    composeTestRule.onNodeWithTag("rotate_90_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(90, viewModel.uiState.value.cropTransform.rotation90Degrees)

    // Undo and Redo buttons work from UI
    composeTestRule.onNodeWithTag("undo_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(0, viewModel.uiState.value.cropTransform.rotation90Degrees)

    composeTestRule.onNodeWithTag("redo_button").performClick()
    composeTestRule.waitForIdle()
    assertEquals(90, viewModel.uiState.value.cropTransform.rotation90Degrees)

    // Save Project via dialog
    composeTestRule.onNodeWithTag("save_project_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("project_name_input").performTextReplacement("Yangon Sunset Edit")
    composeTestRule.onNodeWithTag("confirm_save_project_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      viewModel.uiState.value.currentProjectId != null
    }
    val savedId = viewModel.uiState.value.currentProjectId!!

    // Clear photo, then Open saved project via dialog
    viewModel.clearPhoto()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("open_project_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("open_project_item_$savedId").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      viewModel.uiState.value.importedPhoto != null && viewModel.uiState.value.textLayers.isNotEmpty()
    }
    assertEquals(90, viewModel.uiState.value.cropTransform.rotation90Degrees)
    assertEquals(1, viewModel.uiState.value.textLayers.size)

    // Export to Gallery via dialog
    composeTestRule.onNodeWithTag("export_button").performClick()
    composeTestRule.waitForIdle()
    composeTestRule.onNodeWithTag("export_format_png").performClick()
    composeTestRule.onNodeWithTag("confirm_export_button").performClick()
    composeTestRule.waitUntil(timeoutMillis = 5_000) {
      org.robolectric.shadows.ShadowLooper.idleMainLooper()
      exportedFormat != null
    }
    assertEquals(com.example.model.ExportFormat.PNG, exportedFormat)
    assertEquals(200, exportedWidth) // 300x200 rotated 90 degrees -> width = 200
  }
}
