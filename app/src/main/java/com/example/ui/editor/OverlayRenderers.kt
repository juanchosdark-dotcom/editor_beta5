package com.example.ui.editor

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureInPictureAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.core.model.MediaAsset
import com.example.core.model.OverlayBitmapRasterizer
import com.example.core.model.OverlayContentKind
import com.example.core.model.TimelineClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders an active overlay clip (transparent PNG/JPG image, sticker/logo badge, or PiP video
 * frame) inside the editor viewer monitor while preserving alpha transparency.
 */
@Composable
internal fun OverlayClipVisual(
  clip: TimelineClip,
  projectRootDir: File,
  assets: List<MediaAsset>,
  playheadMs: Long,
  modifier: Modifier = Modifier,
) {
  val cfg = clip.videoConfig ?: return
  val asset = clip.assetId?.let { id -> assets.firstOrNull { it.assetId == id } }
  val sourceFile = asset?.let { File(projectRootDir, it.relativePath) }
  val overlayKind = cfg.overlayKind

  val cropKey = "${cfg.cropLeft}_${cfg.cropTop}_${cfg.cropRight}_${cfg.cropBottom}"
  val decodedBitmap by produceState<Bitmap?>(
    initialValue = null,
    key1 = clip.clipId,
    key2 = "${overlayKind.name}_${cfg.stickerId}_${sourceFile?.absolutePath}_$cropKey",
  ) {
    value =
      withContext(Dispatchers.IO) {
        val raw =
          when (overlayKind) {
            OverlayContentKind.STICKER, OverlayContentKind.LOGO ->
              OverlayBitmapRasterizer.renderStickerBitmap(
                stickerId = cfg.stickerId,
                widthPx = 360,
                heightPx = 140,
              )
            OverlayContentKind.IMAGE ->
              sourceFile
                ?.takeIf { it.exists() }
                ?.let { file -> decodeScaledBitmap(file, maxDimension = 960) }
            OverlayContentKind.VIDEO_PIP ->
              sourceFile
                ?.takeIf { it.exists() }
                ?.let { file ->
                  val sampleUs =
                    ((playheadMs - clip.timelineStartMs).coerceAtLeast(0L) + clip.sourceInMs) * 1_000L
                  extractVideoFrame(file, sampleUs)
                }
            OverlayContentKind.NONE -> null
          }
        raw?.let { cropBitmapNormalized(it, cfg.cropLeft, cfg.cropTop, cfg.cropRight, cfg.cropBottom) }
      }
  }

  val bmp = decodedBitmap?.takeIf { !it.isRecycled }

  Box(
    modifier = modifier.fillMaxSize(),
    contentAlignment = Alignment.Center,
  ) {
    if (bmp != null) {
      Image(
        bitmap = bmp.asImageBitmap(),
        contentDescription = clip.label,
        contentScale = ContentScale.Fit,
        modifier =
          Modifier
            .fillMaxSize()
            .let {
              if (overlayKind == OverlayContentKind.VIDEO_PIP) {
                it.clip(RoundedCornerShape(8.dp))
              } else {
                it
              }
            },
      )
      if (overlayKind == OverlayContentKind.VIDEO_PIP) {
        Surface(
          color = Color(0xCC0B0D11),
          shape = RoundedCornerShape(4.dp),
          border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)),
          modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
        ) {
          Text(
            text = "PiP",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
          )
        }
      }
    } else {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.88f),
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)),
        modifier = Modifier.fillMaxSize(),
      ) {
        Column(
          modifier = Modifier.fillMaxSize().padding(8.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
        ) {
          Icon(
            imageVector =
              if (overlayKind == OverlayContentKind.VIDEO_PIP) {
                Icons.Default.PictureInPictureAlt
              } else {
                Icons.Default.Image
              },
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
          )
          Row(
            modifier = Modifier.padding(top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = clip.label,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

private fun decodeScaledBitmap(file: File, maxDimension: Int): Bitmap? =
  runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    var sampleSize = 1
    while (bounds.outWidth / sampleSize > maxDimension || bounds.outHeight / sampleSize > maxDimension) {
      sampleSize *= 2
    }
    BitmapFactory.Options().apply {
      inSampleSize = sampleSize
      inPreferredConfig = Bitmap.Config.ARGB_8888
    }.let { opts -> BitmapFactory.decodeFile(file.absolutePath, opts) }
  }.getOrNull()

private fun extractVideoFrame(file: File, timeUs: Long): Bitmap? =
  runCatching {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      retriever.getFrameAtTime(timeUs.coerceAtLeast(0L), MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        ?: retriever.getFrameAtTime(0L)
    } finally {
      runCatching { retriever.release() }
    }
  }.getOrNull()

private fun cropBitmapNormalized(
  source: Bitmap,
  cropLeft: Float,
  cropTop: Float,
  cropRight: Float,
  cropBottom: Float,
): Bitmap {
  if (cropLeft <= 0.005f && cropTop <= 0.005f && cropRight >= 0.995f && cropBottom >= 0.995f) {
    return source
  }
  val sw = source.width.coerceAtLeast(2)
  val sh = source.height.coerceAtLeast(2)
  val l = (cropLeft.coerceIn(0f, 0.85f) * sw).toInt().coerceIn(0, sw - 2)
  val t = (cropTop.coerceIn(0f, 0.85f) * sh).toInt().coerceIn(0, sh - 2)
  val r = (cropRight.coerceIn(cropLeft + 0.1f, 1f) * sw).toInt().coerceIn(l + 1, sw)
  val b = (cropBottom.coerceIn(cropTop + 0.1f, 1f) * sh).toInt().coerceIn(t + 1, sh)
  val cw = (r - l).coerceAtLeast(1)
  val ch = (b - t).coerceAtLeast(1)
  return runCatching { Bitmap.createBitmap(source, l, t, cw, ch) }.getOrDefault(source)
}
