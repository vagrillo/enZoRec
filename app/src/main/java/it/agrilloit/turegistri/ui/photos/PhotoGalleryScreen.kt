package it.agrilloit.turegistri.ui.photos

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.FileProvider
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.TextSecondary

/**
 * Galleria delle foto scattate con YouRec: griglia dalla più recente,
 * visore a tutto schermo con condivisione ed eliminazione.
 */
@Composable
fun PhotoGalleryScreen(
    photos: List<Uri>,
    onBack: () -> Unit,
    onDelete: (Uri) -> Unit,
) {
    var viewer by remember { mutableStateOf<Uri?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Indietro",
                tint = Color.White,
                modifier = Modifier.clickable(onClick = onBack),
            )
            Spacer(Modifier.width(12.dp))
            Text("Foto YouRec", color = Color.White, fontSize = 18.sp)
            Spacer(Modifier.weight(1f))
            Text("${photos.size}", color = TextSecondary, fontSize = 13.sp)
        }

        if (photos.isEmpty()) {
            Text(
                "Nessuna foto: scattane una dal pulsante accanto al REC",
                color = TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(18.dp),
            )
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.fillMaxSize(),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(photos, key = { it.toString() }) { uri ->
                    PhotoThumb(uri = uri, sizePx = 320) {
                        viewer = uri
                    }
                }
            }
        }
    }

    viewer?.let { uri ->
        Dialog(onDismissRequest = { viewer = null }) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(SurfaceDark)
                    .padding(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Foto", color = Color.White, fontSize = 15.sp)
                    Spacer(Modifier.weight(1f))
                    Icon(
                        Icons.Filled.IosShare,
                        contentDescription = "Condividi",
                        tint = Color.White,
                        modifier = Modifier
                            .clickable {
                                val share = android.content.Intent(Intent.ACTION_SEND).apply {
                                    type = "image/jpeg"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                context.startActivity(
                                    android.content.Intent.createChooser(share, "Condividi foto")
                                )
                            }
                            .padding(6.dp),
                    )
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Elimina",
                        tint = PrimaryAccent,
                        modifier = Modifier
                            .clickable {
                                onDelete(uri)
                                viewer = null
                            }
                            .padding(6.dp),
                    )
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Chiudi",
                        tint = TextSecondary,
                        modifier = Modifier
                            .clickable { viewer = null }
                            .padding(6.dp),
                    )
                }
                Spacer(Modifier.height(8.dp))
                PhotoThumb(uri = uri, sizePx = 1080, modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f))
            }
        }
    }
}

@Composable
fun PhotoThumb(
    uri: Uri,
    sizePx: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bmp = remember(uri) {
        val repo = it.agrilloit.turegistri.data.PhotosRepository(context)
        repo.thumbnail(uri, sizePx)
    }
    Box(
        modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(if (onClick != null) 0.dp else 10.dp))
            .background(it.agrilloit.turegistri.ui.theme.DividerDark)
            .let { m -> if (onClick != null) m.clickable(onClick = onClick) else m },
    ) {
        bmp?.let {
            Image(
                it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
