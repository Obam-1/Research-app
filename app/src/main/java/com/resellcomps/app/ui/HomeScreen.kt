package com.resellcomps.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.resellcomps.app.Images
import com.resellcomps.app.MainViewModel
import com.resellcomps.core.IdentifyPrompt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val Glass = Color.Black.copy(alpha = 0.55f)
private val ScanGreen = Color(0xFF81C784)

/**
 * The in-app camera: live preview with corner guides and a step prompt, a shutter for
 * several shots in a row (item, then tag, then extras), and a dark tray with the shots,
 * delete-last and Scan, which identifies all the photos together.
 */
@Composable
fun HomeScreen(vm: MainViewModel, photos: List<ByteArray>, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val room = IdentifyPrompt.MAX_PHOTOS - photos.size

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) askPermission.launch(Manifest.permission.CAMERA) }

    val pickPhotos = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(IdentifyPrompt.MAX_PHOTOS)
    ) { uris -> vm.addPhotos(uris) }

    val controller = remember {
        LifecycleCameraController(context).apply { setEnabledUseCases(CameraController.IMAGE_CAPTURE) }
    }
    var capturing by remember { mutableStateOf(false) }
    var square by remember { mutableStateOf(vm.settings.squarePhotos) }

    fun shoot() {
        if (capturing || room <= 0) return
        capturing = true
        controller.takePicture(
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageCapturedCallback() {
                override fun onCaptureSuccess(image: ImageProxy) {
                    val bitmap = image.toBitmap()
                    val rotation = image.imageInfo.rotationDegrees
                    val cutSquare = square
                    image.close()
                    scope.launch {
                        val jpeg = withContext(Dispatchers.Default) { Images.fromCapture(bitmap, rotation, cutSquare) }
                        vm.addCapturedPhoto(jpeg)
                        capturing = false
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    capturing = false
                    Toast.makeText(context, "Couldn't take the photo: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    Box(modifier.background(Color.Black)) {
        // In 1:1 the preview is a full-width square, showing exactly the square the photo keeps.
        val frame = if (square) Modifier.align(Alignment.Center).padding(bottom = 120.dp).fillMaxWidth().aspectRatio(1f)
        else Modifier.fillMaxSize()

        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        setController(controller)
                    }
                },
                modifier = frame,
            )
            // Camera runs only while this screen is showing.
            DisposableEffect(controller, lifecycleOwner) {
                controller.bindToLifecycle(lifecycleOwner)
                onDispose { controller.unbind() }
            }
        } else {
            Column(
                Modifier.align(Alignment.Center).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    "Allow camera access to take photos here, or pick photos from your gallery.",
                    color = Color.White, textAlign = TextAlign.Center,
                )
                Button(onClick = { askPermission.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
            }
        }

        // Frame guides and the step prompt.
        CornerGuides(
            Modifier.align(Alignment.Center).padding(bottom = 120.dp).fillMaxWidth(0.84f).aspectRatio(if (square) 1f else 0.78f)
        )
        if (room > 0) {
            Text(
                when (photos.size) {
                    0 -> "Photograph the whole item"
                    1 -> "Now a close-up of the tag"
                    else -> "Add any additional photos"
                },
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).padding(bottom = 120.dp)
                    .clip(RoundedCornerShape(50)).background(Glass).padding(horizontal = 22.dp, vertical = 12.dp),
            )
        }

        // Type a search instead of taking photos.
        Row(
            Modifier.align(Alignment.TopStart).padding(12.dp).clip(RoundedCornerShape(50)).background(Glass)
                .clickable { vm.startTextSearch() }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Keyboard, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("Type instead", color = Color.White, style = MaterialTheme.typography.labelLarge)
        }

        // Photo shape: full frame or square (1:1).
        Text(
            "1:1",
            color = if (square) Color(0xFF0B2E13) else Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).clip(RoundedCornerShape(50))
                .background(if (square) ScanGreen else Glass)
                .clickable {
                    square = !square
                    vm.settings.squarePhotos = square
                }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Gallery · shutter · flip camera
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                RoundGlassButton(onClick = {
                    pickPhotos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Icon(Icons.Filled.PhotoLibrary, contentDescription = "Pick from gallery", tint = Color.White) }

                Box(
                    Modifier.size(78.dp).clip(CircleShape).border(4.dp, Color.White.copy(alpha = 0.9f), CircleShape)
                        .clickable(enabled = hasPermission && !capturing && room > 0) { shoot() },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(62.dp).clip(CircleShape)
                            .background(if (capturing || room <= 0) Color.White.copy(alpha = 0.35f) else Color.White)
                    )
                }

                RoundGlassButton(onClick = {
                    controller.cameraSelector =
                        if (controller.cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA) CameraSelector.DEFAULT_FRONT_CAMERA
                        else CameraSelector.DEFAULT_BACK_CAMERA
                }) { Icon(Icons.Filled.Cameraswitch, contentDescription = "Switch camera", tint = Color.White) }
            }

            // Tray: shots so far, delete last, quick Lens, Scan.
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Glass).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(photos) { i, photo ->
                        val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
                        bitmap?.let {
                            Image(
                                bitmap = it, contentDescription = "Photo ${i + 1}",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.size(54.dp).clip(RoundedCornerShape(14.dp)),
                            )
                        }
                    }
                    if (room > 0) {
                        item { EmptySlot() }
                    }
                }
                IconButton(onClick = vm::removeLastPhoto, enabled = photos.isNotEmpty()) {
                    Icon(
                        Icons.Outlined.Delete, contentDescription = "Delete last photo",
                        tint = if (photos.isNotEmpty()) Color(0xFFFF8A80) else Color.White.copy(alpha = 0.3f),
                    )
                }
                // Quick Lens: straight to Google Lens with every photo in one picture. No AI, no
                // key, no waiting; the saved Lens question is copied to paste in Lens.
                OutlinedButton(
                    onClick = {
                        GoogleLens.open(context, Images.collage(photos), vm.settings.lensQuestion.ifBlank { null })
                    },
                    enabled = photos.isNotEmpty(),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White, disabledContentColor = Color.White.copy(alpha = 0.4f),
                    ),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = if (photos.isNotEmpty()) 0.7f else 0.25f)),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) { Text("Lens", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { vm.identify("") },
                    enabled = photos.isNotEmpty(),
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = ScanGreen, contentColor = Color(0xFF0B2E13),
                        disabledContainerColor = Color.White.copy(alpha = 0.15f), disabledContentColor = Color.White.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.heightIn(min = 52.dp),
                ) {
                    Text(if (photos.size > 1) "Scan (${photos.size})" else "Scan", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/** Thin white L-shaped corners marking where to frame the item. */
@Composable
private fun CornerGuides(modifier: Modifier) {
    Canvas(modifier) {
        val len = 34.dp.toPx()
        val stroke = 3.dp.toPx()
        val c = Color.White.copy(alpha = 0.85f)
        val w = size.width
        val h = size.height
        fun l(a: Offset, b: Offset) = drawLine(c, a, b, strokeWidth = stroke, cap = StrokeCap.Round)
        l(Offset(0f, 0f), Offset(len, 0f)); l(Offset(0f, 0f), Offset(0f, len))
        l(Offset(w, 0f), Offset(w - len, 0f)); l(Offset(w, 0f), Offset(w, len))
        l(Offset(0f, h), Offset(len, h)); l(Offset(0f, h), Offset(0f, h - len))
        l(Offset(w, h), Offset(w - len, h)); l(Offset(w, h), Offset(w, h - len))
    }
}

@Composable
private fun RoundGlassButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(52.dp).clip(CircleShape).background(Glass).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** The dashed "next photo" slot in the tray. */
@Composable
private fun EmptySlot() {
    Box(
        Modifier.size(54.dp).drawBehind {
            drawRoundRect(
                color = Color.White.copy(alpha = 0.45f),
                cornerRadius = CornerRadius(14.dp.toPx()),
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
            )
        }
    )
}

@Composable
fun PhotoPreview(photo: ByteArray) {
    val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo, 0, photo.size)?.asImageBitmap() }
    bitmap?.let {
        Image(
            bitmap = it, contentDescription = "Your photo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
        )
    }
}
