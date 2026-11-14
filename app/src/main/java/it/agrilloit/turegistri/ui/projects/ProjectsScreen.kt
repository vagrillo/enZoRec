package it.agrilloit.turegistri.ui.projects

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import it.agrilloit.turegistri.data.ProjectEntity
import it.agrilloit.turegistri.data.ProjectsRepository
import it.agrilloit.turegistri.ui.theme.BackgroundDark
import it.agrilloit.turegistri.ui.theme.DividerDark
import it.agrilloit.turegistri.ui.theme.GreenBadge
import it.agrilloit.turegistri.ui.theme.PrimaryAccent
import it.agrilloit.turegistri.ui.theme.SecondaryAccent
import it.agrilloit.turegistri.ui.theme.SurfaceDark
import it.agrilloit.turegistri.ui.theme.TextSecondary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProjectsViewModel(app: Application) : AndroidViewModel(app) {
    val repository = ProjectsRepository(app)
    val projects = repository.projects
    val takeCounts = MutableStateFlow<Map<String, Int>>(emptyMap())

    init {
        viewModelScope.launch {
            projects.collect {
                takeCounts.value = repository.takeCounts()
            }
        }
    }

    fun createAndTouch(onCreated: () -> Unit) = viewModelScope.launch {
        repository.createProject(null)
        onCreated()
    }

    fun continueProject(id: String) = viewModelScope.launch { repository.touch(id) }

    fun rename(id: String, name: String) = viewModelScope.launch { repository.rename(id, name) }

    fun delete(id: String) = viewModelScope.launch { repository.delete(id) }

    fun duplicate(id: String) = viewModelScope.launch { repository.duplicate(id) }

    fun importVideo(uri: Uri, onDone: (String) -> Unit) = viewModelScope.launch {
        val context = getApplication<Application>()
        val imported = withContext(Dispatchers.IO) {
            val dir = File(ProjectsRepository.sessionsRoot(context), "import").apply { mkdirs() }
            val out = File(dir, "import_${System.currentTimeMillis()}.mp4")
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            out
        }
        val project = repository.importVideo(imported, null)
        onDone(project.id)
    }
}

@Composable
fun ProjectsScreen(
    onOpenEditor: (String) -> Unit,
    onGoRecord: () -> Unit,
) {
    val vm: ProjectsViewModel = viewModel()
    val projects by vm.projects.collectAsState(initial = emptyList())
    val counts by vm.takeCounts.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renameFor by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleteFor by remember { mutableStateOf<ProjectEntity?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) vm.importVideo(uri) { }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundDark),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row {
                Text("you", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text("rec", color = PrimaryAccent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.weight(1f))
            Text(
                "PROJECTS",
                color = TextSecondary,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
            )
        }
        Text(
            "Pick up where you left off.",
            color = TextSecondary,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 18.dp),
        )
        Text(
            "Privacy policy",
            color = PrimaryAccent,
            fontSize = 11.sp,
            modifier = Modifier
                .padding(horizontal = 18.dp, vertical = 6.dp)
                .clickable { },
        )

        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(PrimaryAccent.copy(alpha = 0.85f))
                    .clickable(onClick = onGoRecord)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("+ New project", color = Color.White, fontSize = 14.sp)
            }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .border(1.dp, PrimaryAccent.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
                    .clickable {
                        importLauncher.launch(arrayOf("video/*"))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Download,
                        contentDescription = null,
                        tint = PrimaryAccent,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("Import", color = Color.White, fontSize = 14.sp)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyColumn(Modifier.fillMaxSize()) {
            items(projects, key = { it.id }) { project ->
                ProjectCard(
                    project = project,
                    takeCount = counts[project.id] ?: 0,
                    onContinue = {
                        vm.continueProject(project.id)
                        onGoRecord()
                    },
                    onEdit = { onOpenEditor(project.id) },
                    onRename = { renameFor = project },
                    onDelete = { deleteFor = project },
                    onDuplicate = { vm.duplicate(project.id) },
                    menuOpen = menuFor == project.id,
                    onMenuToggle = { menuFor = if (menuFor == project.id) null else project.id },
                )
            }
            if (projects.isEmpty()) {
                item {
                    Text(
                        "Nessun progetto: registra il primo take",
                        color = TextSecondary,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(18.dp),
                    )
                }
            }
        }
    }

    renameFor?.let { project ->
        RenameDialog(
            current = project.name,
            onConfirm = { name ->
                vm.rename(project.id, name)
                renameFor = null
            },
            onDismiss = { renameFor = null },
        )
    }
    deleteFor?.let { project ->
        AlertDialog(
            onDismissRequest = { deleteFor = null },
            title = { Text("Eliminare il progetto?", color = Color.White) },
            text = { Text("«${project.name}» e tutti i take registrati verranno rimossi.", color = TextSecondary) },
            confirmButton = {
                Text(
                    "Elimina",
                    color = PrimaryAccent,
                    modifier = Modifier
                        .clickable {
                            vm.delete(project.id)
                            deleteFor = null
                        }
                        .padding(8.dp),
                )
            },
            dismissButton = {
                Text(
                    "Annulla",
                    color = SecondaryAccent,
                    modifier = Modifier.clickable { deleteFor = null }.padding(8.dp),
                )
            },
            containerColor = SurfaceDark,
        )
    }
}

@Composable
private fun ProjectCard(
    project: ProjectEntity,
    takeCount: Int,
    onContinue: () -> Unit,
    onEdit: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
    menuOpen: Boolean,
    onMenuToggle: () -> Unit,
) {
    val context = LocalContext.current
    var thumb by remember(project.thumbnailPath) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(project.thumbnailPath) {
        project.thumbnailPath?.let { path ->
            thumb = withContext(Dispatchers.IO) {
                runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
            }
        }
    }
    val date = SimpleDateFormat("d MMM - HH:mm", Locale.US).format(Date(project.createdAt))

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(DividerDark),
            ) {
                thumb?.let {
                    Image(
                        it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        project.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                    if (project.exported) {
                        Spacer(Modifier.width(6.dp))
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "Esportato",
                            tint = GreenBadge,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
                Text(date, color = TextSecondary, fontSize = 12.sp)
                Text(
                    "$takeCount take · " + if (project.editJson.isNotEmpty()) "modificato" else "da montare",
                    color = TextSecondary,
                    fontSize = 12.sp,
                )
            }
            Box {
                Icon(
                    Icons.Filled.MoreVert,
                    contentDescription = "Opzioni",
                    tint = TextSecondary,
                    modifier = Modifier.clickable(onClick = onMenuToggle),
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = onMenuToggle,
                    containerColor = SurfaceDark,
                ) {
                    DropdownMenuItem(
                        text = { Text("Rinomina", color = Color.White) },
                        onClick = {
                            onMenuToggle()
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Duplica", color = Color.White) },
                        onClick = {
                            onMenuToggle()
                            onDuplicate()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Elimina", color = PrimaryAccent) },
                        onClick = {
                            onMenuToggle()
                            onDelete()
                        },
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(PrimaryAccent)
                    .clickable(onClick = onContinue)
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Continue recording", color = Color.White, fontSize = 13.sp)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(20.dp))
                    .background(SecondaryAccent)
                    .clickable(onClick = onEdit)
                    .padding(vertical = 11.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.Tune,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Edit", color = Color.White, fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun RenameDialog(current: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rinomina progetto", color = Color.White) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = SecondaryAccent,
                ),
            )
        },
        confirmButton = {
            Text(
                "Salva",
                color = SecondaryAccent,
                modifier = Modifier.clickable { onConfirm(name) }.padding(8.dp),
            )
        },
        dismissButton = {
            Text("Annulla", color = TextSecondary, modifier = Modifier.clickable(onClick = onDismiss).padding(8.dp))
        },
        containerColor = SurfaceDark,
    )
}
