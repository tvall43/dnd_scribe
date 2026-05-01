package com.example.dndscribe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dndscribe.ui.theme.Gold
import com.example.dndscribe.ui.theme.Ink
import com.example.dndscribe.ui.theme.Parchment
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = viewModel()) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Transcript", "Notes", "Final", "Archives")
    val isRecording by viewModel.isRecording.collectAsState()
    var showSettings by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("⚔️ DnD Scribe", color = Gold, fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { viewModel.saveCurrentSession() }, enabled = !isRecording) {
                        Icon(Icons.Default.Save, contentDescription = "Save", tint = Gold)
                    }
                    if (selectedTab == 1) {
                        IconButton(onClick = { viewModel.generateNote() }) {
                            Icon(Icons.Default.NoteAdd, contentDescription = "Add note now", tint = Gold)
                        }
                    }
                    if (selectedTab == 2) {
                        IconButton(onClick = { viewModel.generateFinal() }) {
                            Icon(Icons.Default.AutoAwesome, contentDescription = "Generate final summary", tint = Gold)
                        }
                    }
                    IconButton(onClick = { viewModel.toggleRecording() }) {
                        Icon(
                            if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                            contentDescription = if (isRecording) "Stop recording" else "Start recording",
                            tint = if (isRecording) Color.Red else Gold
                        )
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Gold)
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(containerColor = Ink)
            )
        },
        containerColor = Ink
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (isRecording) {
                Text(
                    "RECORDING",
                    color = Color.Red,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Black,
                contentColor = Gold,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = Gold
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, fontSize = 12.sp) }
                    )
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(Parchment)
            ) {
                when (selectedTab) {
                    0 -> TranscriptPane(viewModel)
                    1 -> NotesPane(viewModel)
                    2 -> FinalPane(viewModel)
                    3 -> ArchivesPane(viewModel)
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(viewModel = viewModel, onDismiss = { showSettings = false })
    }
}

@Composable
fun TranscriptPane(viewModel: MainViewModel) {
    val transcript by viewModel.currentTranscript.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()
    OutlinedTextField(
        value = transcript,
        onValueChange = { viewModel.updateTranscript(it) },
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        enabled = !isRecording,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            disabledBorderColor = Color.Transparent,
            disabledTextColor = Ink
        ),
        maxLines = Int.MAX_VALUE,
        placeholder = {
            Text(if (isRecording) "Transcript is locked while recording..." else "Transcript will appear here...")
        }
    )
}

@Composable
fun NotesPane(viewModel: MainViewModel) {
    val notes by viewModel.currentNotes.collectAsState()
    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = notes,
            onValueChange = { viewModel.updateNotes(it) },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedTextColor = Ink,
                unfocusedTextColor = Ink
            ),
            maxLines = Int.MAX_VALUE,
            placeholder = { Text("Running notes will appear here...") }
        )
    }
}

@Composable
fun FinalPane(viewModel: MainViewModel) {
    val summary by viewModel.finalSummary.collectAsState()
    val isGenerating by viewModel.isGeneratingFinal.collectAsState()
    val isRecording by viewModel.isRecording.collectAsState()

    OutlinedTextField(
        value = summary,
        onValueChange = { viewModel.updateFinalSummary(it) },
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        enabled = !isGenerating && !isRecording,
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = Ink,
            unfocusedTextColor = Ink,
            disabledBorderColor = Color.Transparent,
            disabledTextColor = Ink
        ),
        maxLines = Int.MAX_VALUE,
        placeholder = {
            Text(
                when {
                    isGenerating -> "Consulting the sands..."
                    isRecording -> "Final summary is locked while recording..."
                    else -> "Final summary will appear here..."
                }
            )
        }
    )
}

@Composable
fun ArchivesPane(viewModel: MainViewModel) {
    val sessions by viewModel.filteredSessions.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    var selectedSession by remember { mutableStateOf<com.example.dndscribe.data.local.SessionEntity?>(null) }
    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importArchives(it, context) }
    }

    if (selectedSession != null) {
        SessionDetailView(session = selectedSession!!, onBack = { selectedSession = null })
    } else {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = searchQuery,
                    onValueChange = { viewModel.onSearchQueryChange(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search sessions...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(alpha = 0.5f),
                        unfocusedContainerColor = Color.White.copy(alpha = 0.5f)
                    )
                )
                IconButton(onClick = { viewModel.exportArchives(context) }) {
                    Icon(Icons.Default.Share, contentDescription = "Export All", tint = Gold)
                }
                IconButton(onClick = { filePickerLauncher.launch("application/json") }) {
                    Icon(Icons.Default.FileUpload, contentDescription = "Import", tint = Gold)
                }
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions) { session ->
                    ListItem(
                        headlineContent = { Text(session.name, fontWeight = FontWeight.Bold) },
                        supportingContent = { 
                            Text(SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(session.date))) 
                        },
                        trailingContent = {
                            IconButton(onClick = { viewModel.deleteSession(session) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray)
                            }
                        },
                        modifier = Modifier.clickable { selectedSession = session }
                    )
                    HorizontalDivider(color = Gold.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailView(session: com.example.dndscribe.data.local.SessionEntity, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().background(Parchment)) {
        TopAppBar(
            title = { Text(session.name, fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Gold, titleContentColor = Ink)
        )
        var detailTab by remember { mutableIntStateOf(0) }
        TabRow(selectedTabIndex = detailTab, containerColor = Ink, contentColor = Gold) {
            Tab(selected = detailTab == 0, onClick = { detailTab = 0 }, text = { Text("Summary") })
            Tab(selected = detailTab == 1, onClick = { detailTab = 1 }, text = { Text("Notes") })
            Tab(selected = detailTab == 2, onClick = { detailTab = 2 }, text = { Text("Transcript") })
        }
        SelectionContainer {
            Box(modifier = Modifier.weight(1f).padding(16.dp)) {
                LazyColumn {
                    item {
                        val text = when (detailTab) {
                            0 -> session.finalSummary
                            1 -> session.notes
                            else -> session.fullTranscript
                        }
                        Text(text, color = Ink, lineHeight = 22.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsDialog(viewModel: MainViewModel, onDismiss: () -> Unit) {
    val config by viewModel.config.collectAsState()
    val context = LocalContext.current
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Settings") },
        text = {
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                item {
                    Text("LLM Config", fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
                    TextField(value = config.llmUrl, onValueChange = { viewModel.updateConfig(config.copy(llmUrl = it)) }, label = { Text("LLM Base URL") })
                    TextField(value = config.llmApiKey, onValueChange = { viewModel.updateConfig(config.copy(llmApiKey = it)) }, label = { Text("LLM API Key") })
                    TextField(value = config.llmModel, onValueChange = { viewModel.updateConfig(config.copy(llmModel = it)) }, label = { Text("Model Name") })
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = config.allowInsecureHttp, onCheckedChange = { viewModel.updateConfig(config.copy(allowInsecureHttp = it)) })
                        Text("Allow insecure HTTP endpoints")
                    }
                    AnimatedVisibility(visible = config.allowInsecureHttp) {
                        Text(
                            "Warning: API keys, transcripts, and summaries can cross the network without TLS when you use http:// URLs.",
                            color = MaterialTheme.colorScheme.error,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = config.syncApiSettings, onCheckedChange = { viewModel.updateConfig(config.copy(syncApiSettings = it)) })
                        Text("Use LLM URL for Whisper")
                    }
                    
                    if (!config.syncApiSettings) {
                        TextField(value = config.whisperUrl, onValueChange = { viewModel.updateConfig(config.copy(whisperUrl = it)) }, label = { Text("Whisper Base URL") })
                        TextField(value = config.whisperApiKey, onValueChange = { viewModel.updateConfig(config.copy(whisperApiKey = it)) }, label = { Text("Whisper API Key") })
                    }
                    TextField(value = config.whisperModel, onValueChange = { viewModel.updateConfig(config.copy(whisperModel = it)) }, label = { Text("Whisper Model Name") })
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Timers", fontWeight = FontWeight.Bold)
                    TextField(value = config.chunkSec.toString(), onValueChange = { viewModel.updateConfig(config.copy(chunkSec = it.toIntOrNull() ?: 15)) }, label = { Text("Chunk (sec)") })
                    TextField(value = config.notesIntervalMin.toString(), onValueChange = { viewModel.updateConfig(config.copy(notesIntervalMin = it.toIntOrNull() ?: 10)) }, label = { Text("Notes (min)") })
                    TextField(value = config.finalIntervalMin.toString(), onValueChange = { viewModel.updateConfig(config.copy(finalIntervalMin = it.toIntOrNull() ?: 120)) }, label = { Text("Final (min)") })

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Note Context", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = config.includePreviousNotesContext,
                            onCheckedChange = {
                                viewModel.updateConfig(config.copy(includePreviousNotesContext = it))
                            }
                        )
                        Text("Include previous notes with new transcript")
                    }
                    if (config.includePreviousNotesContext) {
                        TextField(
                            value = config.previousNotesContextCount.toString(),
                            onValueChange = {
                                viewModel.updateConfig(
                                    config.copy(previousNotesContextCount = it.toIntOrNull()?.coerceAtLeast(1) ?: 1)
                                )
                            },
                            label = { Text("Previous note entries") }
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Cloud Backup", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = config.cloudBackupEnabled,
                            onCheckedChange = { viewModel.updateConfig(config.copy(cloudBackupEnabled = it)) }
                        )
                        Text("Sync sessions to server")
                    }
                    if (config.cloudBackupEnabled) {
                        TextField(
                            value = config.cloudUrl,
                            onValueChange = { viewModel.updateConfig(config.copy(cloudUrl = it)) },
                            label = { Text("Cloud Base URL") }
                        )
                        TextField(
                            value = config.cloudApiKey,
                            onValueChange = { viewModel.updateConfig(config.copy(cloudApiKey = it)) },
                            label = { Text("Cloud API Key") }
                        )
                        TextField(
                            value = config.cloudDeviceId,
                            onValueChange = { viewModel.updateConfig(config.copy(cloudDeviceId = it.ifBlank { "android" })) },
                            label = { Text("Cloud Device ID") }
                        )
                        Button(
                            onClick = { viewModel.syncNow(context) },
                            colors = ButtonDefaults.buttonColors(containerColor = Gold),
                            modifier = Modifier.padding(top = 8.dp)
                        ) {
                            Text("Sync Now", color = Ink)
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } }
    )
}
