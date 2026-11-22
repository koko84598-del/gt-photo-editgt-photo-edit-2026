package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.example.data.GalleryExporter
import com.example.data.LocalProjectRepository
import com.example.data.MediaStoreGalleryExporter
import com.example.data.PhotoDecoder
import com.example.data.TextLayerCompositor
import com.example.model.AdjustmentType
import com.example.model.ColorGradeWheel
import com.example.model.CropAspectRatio
import com.example.model.CropRect
import com.example.model.CurvePoint
import com.example.model.EditorCategory
import com.example.model.EditorFont
import com.example.model.EditorSnapshot
import com.example.model.ExportFormat
import com.example.model.ExportResult
import com.example.model.GradingRange
import com.example.model.HslAdjustment
import com.example.model.HslColorRange
import com.example.model.ImportedPhoto
import com.example.model.ToneCurveChannel
import com.example.viewmodel.EditorHistoryManager
import com.example.viewmodel.EditorViewModel
import java.io.ByteArrayOutputStream
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
class Milestone4PersistenceAndExportTest {

  private val testDispatcher = StandardTestDispatcher()
  private lateinit var context: Context
  private lateinit var repository: LocalProjectRepository

  @Before
  fun setUp() {
    Dispatchers.setMain(testDispatcher)
    context = ApplicationProvider.getApplicationContext()
    repository = LocalProjectRepository(context, testDispatcher)
  }

  @After
  fun tearDown() {
    Dispatchers.resetMain()
  }

  private fun createHighResPhoto(
    uri: Uri,
    width: Int = 2400,
    height: Int = 1600,
    fillColor: Int = Color.rgb(40, 110, 180),
  ): ImportedPhoto {
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(fillColor) }
    return ImportedPhoto(
      uriString = uri.toString(),
      displayName = "bagan_temple.jpg",
      originalWidth = width,
      originalHeight = height,
      bitmap = bmp,
    )
  }

  @Test
  fun `project persistence saves and restores all edits, layers, and original image reference`() =
    runTest {
      val sourceUri = Uri.parse("content://media/external/images/media/901")
      val decoder =
        object : PhotoDecoder {
          override suspend fun decode(uri: Uri): Result<ImportedPhoto> =
            Result.success(createHighResPhoto(uri, width = 1600, height = 1200))
        }

      val vm1 = EditorViewModel(decoder, testDispatcher, projectRepository = repository)
      vm1.importPhoto(sourceUri)
      advanceUntilIdle()

      vm1.selectCategory(EditorCategory.ADJUST)
      vm1.setCropAspectRatio(CropAspectRatio.RATIO_4_5)
      vm1.rotateBy90()
      vm1.toggleFlipHorizontal()
      vm1.updateStraightenDegrees(7.5f)
      vm1.updatePerspectiveVertical(0.25f)
      vm1.updateColorAdjustment(AdjustmentType.EXPOSURE, 0.75f)
      vm1.updateColorAdjustment(AdjustmentType.TEMPERATURE, -0.35f)
      vm1.updateHslRange(
        HslColorRange.ORANGE,
        HslAdjustment(hue = 18f, saturation = 45f, luminance = 12f),
      )
      vm1.addToneCurvePoint(ToneCurveChannel.RGB, CurvePoint(0.5f, 0.72f))
      vm1.updateColorGrading(
        GradingRange.SHADOWS,
        ColorGradeWheel(hueDegrees = 210f, saturation = 0.65f, luminance = -0.15f),
      )

      vm1.addTextLayer("မင်္ဂလာပါ Bagan", EditorFont.PADAUK)
      val layerId = vm1.uiState.value.selectedLayerId!!
      vm1.toggleLayerBold(layerId)
      vm1.toggleLayerItalic(layerId)
      vm1.updateLayerFontSize(layerId, 42f)
      vm1.updateLayerOpacity(layerId, 0.85f)
      vm1.toggleLayerLock(layerId)

      vm1.saveProject("Bagan Golden Hour")
      advanceUntilIdle()

      val savedId = vm1.uiState.value.currentProjectId
      assertNotNull(savedId)
      assertEquals("Bagan Golden Hour", vm1.uiState.value.currentProjectName)

      // Open project in a brand-new ViewModel instance
      val vm2 = EditorViewModel(decoder, testDispatcher, projectRepository = repository)
      advanceUntilIdle()
      assertTrue(vm2.uiState.value.savedProjects.any { it.id == savedId })

      vm2.openProject(savedId!!)
      advanceUntilIdle()

      val restored = vm2.uiState.value
      assertEquals(sourceUri.toString(), restored.importedPhoto?.uriString)
      assertEquals(90, restored.cropTransform.rotation90Degrees)
      assertTrue(restored.cropTransform.flipHorizontal)
      assertEquals(CropAspectRatio.RATIO_4_5, restored.cropTransform.aspectRatio)
      assertEquals(7.5f, restored.cropTransform.straightenDegrees, 0.01f)
      assertEquals(0.75f, restored.colorAdjustments.exposure, 0.01f)
      assertEquals(-0.35f, restored.colorAdjustments.temperature, 0.01f)
      assertEquals(45f, restored.hslMixer.forRange(HslColorRange.ORANGE).saturation, 0.01f)
      assertEquals(3, restored.toneCurve.pointsFor(ToneCurveChannel.RGB).size)
      assertEquals(210f, restored.colorGrading.shadows.hueDegrees, 0.01f)
      assertEquals(1, restored.textLayers.size)
      val restoredLayer = restored.textLayers.single()
      assertEquals("မင်္ဂလာပါ Bagan", restoredLayer.text)
      assertEquals(EditorFont.PADAUK, restoredLayer.font)
      assertTrue(restoredLayer.isBold)
      assertTrue(restoredLayer.isItalic)
      assertTrue(restoredLayer.isLocked)
      assertEquals(42f, restoredLayer.fontSizeSp, 0.01f)
      assertEquals(0.85f, restoredLayer.opacity, 0.01f)
      assertNull(restored.missingSourceMessage)

      // Delete saved project removes it cleanly
      vm2.deleteSavedProject(savedId)
      advanceUntilIdle()
      assertFalse(vm2.uiState.value.savedProjects.any { it.id == savedId })
    }

  @Test
  fun `opening project with missing original photo restores edits gracefully and supports relinking`() =
    runTest {
      val missingUri = Uri.parse("content://media/external/images/media/deleted_404")
      val workingDecoder =
        object : PhotoDecoder {
          override suspend fun decode(uri: Uri): Result<ImportedPhoto> =
            if (uri == missingUri) {
              Result.failure(IllegalStateException("File not found"))
            } else {
              Result.success(createHighResPhoto(uri, width = 800, height = 600))
            }
        }
      val initialDecoder =
        object : PhotoDecoder {
          override suspend fun decode(uri: Uri): Result<ImportedPhoto> =
            Result.success(createHighResPhoto(uri, width = 800, height = 600))
        }

      val vmSave = EditorViewModel(initialDecoder, testDispatcher, projectRepository = repository)
      vmSave.importPhoto(missingUri)
      advanceUntilIdle()
      vmSave.updateColorAdjustment(AdjustmentType.CONTRAST, 0.45f)
      vmSave.addTextLayer("Saved Layer", EditorFont.INTER)
      vmSave.saveProject("Missing Source Test")
      advanceUntilIdle()
      val projectId = vmSave.uiState.value.currentProjectId!!

      // Reopen when original URI is gone
      val vmOpen = EditorViewModel(workingDecoder, testDispatcher, projectRepository = repository)
      vmOpen.openProject(projectId)
      advanceUntilIdle()

      val stateAfterOpen = vmOpen.uiState.value
      assertNull(stateAfterOpen.importedPhoto)
      assertNotNull(stateAfterOpen.missingSourceMessage)
      assertEquals(0.45f, stateAfterOpen.colorAdjustments.contrast, 0.01f)
      assertEquals(1, stateAfterOpen.textLayers.size)
      assertEquals("Saved Layer", stateAfterOpen.textLayers.single().text)

      // Relink new photo keeps restored edits and layers
      val replacementUri = Uri.parse("content://media/external/images/media/new_photo")
      vmOpen.relinkPhoto(replacementUri)
      advanceUntilIdle()

      val stateAfterRelink = vmOpen.uiState.value
      assertNotNull(stateAfterRelink.importedPhoto)
      assertNull(stateAfterRelink.missingSourceMessage)
      assertEquals(0.45f, stateAfterRelink.colorAdjustments.contrast, 0.01f)
      assertEquals("Saved Layer", stateAfterRelink.textLayers.single().text)
    }

  @Test
  fun `undo and redo reliably revert and replay text, crop, transform, and color edits within bounded memory`() =
    runTest {
      val uri = Uri.parse("content://media/external/images/media/77")
      val srcPhoto = createHighResPhoto(uri, width = 200, height = 100, fillColor = Color.rgb(100, 100, 100))
      val decoder =
        object : PhotoDecoder {
          override suspend fun decode(uri: Uri): Result<ImportedPhoto> = Result.success(srcPhoto)
        }
      val vm = EditorViewModel(decoder, testDispatcher)
      vm.importPhoto(uri)
      advanceUntilIdle()

      assertFalse(vm.uiState.value.canUndo)
      assertFalse(vm.uiState.value.canRedo)

      // Step 1: Add text layer
      vm.addTextLayer("Layer 1", EditorFont.SPACE_GROTESK)
      val id1 = vm.uiState.value.selectedLayerId!!
      // Step 2: Edit text
      vm.updateSelectedLayerText("Edited Text")
      // Step 3: Move text
      vm.moveTextLayer(id1, 0.15f, -0.1f)
      // Step 4: Duplicate
      vm.duplicateLayer(id1)
      val id2 = vm.uiState.value.selectedLayerId!!
      // Step 5: Toggle visibility & lock
      vm.toggleLayerVisibility(id1)
      vm.toggleLayerLock(id2)
      // Step 6: Rotate crop & adjust brightness + HSL + grading
      vm.rotateBy90()
      vm.updateColorAdjustment(AdjustmentType.BRIGHTNESS, 0.6f)
      vm.updateHslRange(HslColorRange.RED, HslAdjustment(saturation = 40f))
      vm.addToneCurvePoint(ToneCurveChannel.RGB, CurvePoint(0.5f, 0.8f))
      vm.updateColorGrading(GradingRange.MIDTONES, ColorGradeWheel(180f, 0.5f, 0.2f))
      // Step 7: Delete layer 2
      vm.deleteLayer(id2)
      advanceUntilIdle()

      assertTrue(vm.uiState.value.canUndo)
      assertFalse(vm.uiState.value.canRedo)
      assertEquals(1, vm.uiState.value.textLayers.size)

      // Undo delete restores layer 2
      vm.undo()
      assertEquals(2, vm.uiState.value.textLayers.size)
      assertTrue(vm.uiState.value.canRedo)

      // Redo delete removes layer 2 again
      vm.redo()
      assertEquals(1, vm.uiState.value.textLayers.size)

      // Undo back through all steps to clean state
      var steps = 0
      while (vm.uiState.value.canUndo && steps < 30) {
        vm.undo()
        steps++
      }
      advanceUntilIdle()
      assertFalse(vm.uiState.value.canUndo)
      assertTrue(vm.uiState.value.textLayers.isEmpty())
      assertTrue(vm.uiState.value.cropTransform.isIdentity)
      assertTrue(vm.uiState.value.colorAdjustments.isIdentity)
      assertTrue(vm.uiState.value.hslMixer.isIdentity)
      assertTrue(vm.uiState.value.toneCurve.isIdentity)
      assertTrue(vm.uiState.value.colorGrading.isIdentity)
      // Original bitmap stays completely intact
      assertEquals(Color.rgb(100, 100, 100), srcPhoto.bitmap.getPixel(50, 50))

      // Verify bounded memory capacity in EditorHistoryManager (max 50)
      val bounded = EditorHistoryManager(maxEntries = 50)
      var prev = EditorSnapshot()
      for (i in 1..80) {
        val next = prev.copy(colorAdjustments = prev.colorAdjustments.withValue(AdjustmentType.BRIGHTNESS, i / 100f))
        bounded.recordTransition(prev, next, coalesceKey = null)
        prev = next
      }
      assertEquals(50, bounded.undoSize)
    }

  @Test
  fun `gallery export renders full resolution, supports jpeg quality and png transparency, and handles errors`() =
    runTest {
      val uri = Uri.parse("content://media/external/images/media/555")
      // Simulate preview-sized initial decode (e.g. 1080x720) vs true full-res 2400x1600
      val previewSizedPhoto = createHighResPhoto(uri, width = 1080, height = 720, fillColor = Color.rgb(60, 120, 180)).copy(
        originalWidth = 2400,
        originalHeight = 1600,
      )
      val fullResBitmap =
        Bitmap.createBitmap(2400, 1600, Bitmap.Config.ARGB_8888).apply {
          eraseColor(Color.argb(0, 0, 0, 0)) // transparent border
          for (y in 200 until 1400) {
            for (x in 200 until 2200) {
              setPixel(x, y, Color.rgb(80, 140, 200))
            }
          }
        }

      val decoder =
        object : PhotoDecoder {
          override suspend fun decode(uri: Uri): Result<ImportedPhoto> = Result.success(previewSizedPhoto)

          override suspend fun decodeFullResolution(uri: Uri): Result<ImportedPhoto> =
            Result.success(
              ImportedPhoto(
                uriString = uri.toString(),
                displayName = "transparent_art.png",
                originalWidth = 2400,
                originalHeight = 1600,
                bitmap = fullResBitmap,
              )
            )
        }

      val realExporter = MediaStoreGalleryExporter(context, testDispatcher)
      var capturedBitmap: Bitmap? = null
      var capturedQuality: Int = -1
      val spyExporter =
        object : GalleryExporter {
          override suspend fun exportToGallery(
            bitmap: Bitmap,
            sourceDisplayName: String,
            format: ExportFormat,
            jpegQuality: Int,
          ): Result<ExportResult> {
            capturedBitmap = bitmap
            capturedQuality = jpegQuality
            return realExporter.exportToGallery(bitmap, sourceDisplayName, format, jpegQuality)
          }
        }

      val vm =
        EditorViewModel(
          photoDecoder = decoder,
          processingDispatcher = testDispatcher,
          galleryExporter = spyExporter,
          appContext = context,
        )
      vm.importPhoto(uri)
      advanceUntilIdle()

      vm.rotateBy90()
      vm.updateColorAdjustment(AdjustmentType.BRIGHTNESS, 0.25f)
      vm.addTextLayer("Visible Title", EditorFont.SPACE_GROTESK)
      vm.addTextLayer("Hidden Title", EditorFont.INTER)
      val hiddenId = vm.uiState.value.selectedLayerId!!
      vm.toggleLayerVisibility(hiddenId)

      // 1. Export PNG -> full rotated original resolution 1600x2400, transparency preserved!
      vm.exportToGallery(ExportFormat.PNG, jpegQuality = 100)
      advanceUntilIdle()

      val pngOut = capturedBitmap!!
      assertEquals(1600, pngOut.width)
      assertEquals(2400, pngOut.height)
      assertTrue(pngOut.hasAlpha())
      assertEquals(0, Color.alpha(pngOut.getPixel(5, 5)))
      assertNotNull(vm.uiState.value.statusMessage)
      assertTrue(vm.uiState.value.statusMessage!!.contains("PNG"))

      // 2. Export JPEG at 82% quality -> verify quality & output size
      capturedBitmap = null
      vm.exportToGallery(ExportFormat.JPEG, jpegQuality = 82)
      advanceUntilIdle()
      val jpegOut = capturedBitmap!!
      assertEquals(82, capturedQuality)
      assertEquals(1600, jpegOut.width)
      assertEquals(2400, jpegOut.height)
      assertTrue(vm.uiState.value.statusMessage!!.contains("JPEG"))

      // 3. Export failure surfaces error message gracefully
      val failingExporter =
        object : GalleryExporter {
          override suspend fun exportToGallery(
            bitmap: Bitmap,
            sourceDisplayName: String,
            format: ExportFormat,
            jpegQuality: Int,
          ): Result<ExportResult> = Result.failure(IllegalStateException("Disk full"))
        }
      val vmFail =
        EditorViewModel(
          photoDecoder = decoder,
          processingDispatcher = testDispatcher,
          galleryExporter = failingExporter,
          appContext = context,
        )
      vmFail.importPhoto(uri)
      advanceUntilIdle()
      vmFail.exportToGallery(ExportFormat.JPEG, 90)
      advanceUntilIdle()
      assertFalse(vmFail.uiState.value.isExporting)
      assertEquals("Disk full", vmFail.uiState.value.photoError)

      // Verify PNG stream encoding preserves transparency
      val bos = ByteArrayOutputStream()
      pngOut.compress(Bitmap.CompressFormat.PNG, 100, bos)
      val decodedPng = BitmapFactory.decodeByteArray(bos.toByteArray(), 0, bos.size())
      assertEquals(0, Color.alpha(decodedPng.getPixel(5, 5)))
      assertTrue(TextLayerCompositor.compositeVisibleTextLayers(fullResBitmap, emptyList(), context, true).hasAlpha())
    }
}
