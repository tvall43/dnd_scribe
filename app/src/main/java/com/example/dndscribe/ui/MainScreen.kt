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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
import androidx.compose.material.icons.automirrored.filled.VolumeUp
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

@Composable
private fun LabeledActionButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    accentColor: Color = Gold,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = accentColor,
            disabledContentColor = accentColor.copy(alpha = 0.45f)
        ),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, contentDescription = label, tint = accentColor)
            Text(label)
        }
    }
}

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
                    LabeledActionButton(label = "Save", icon = Icons.Default.Save, onClick = { viewModel.saveCurrentSession() }, enabled = !isRecording)
                    if (selectedTab == 1) {
                        LabeledActionButton(label = "Add note", icon = Icons.AutoMirrored.Filled.NoteAdd, onClick = { viewModel.generateNote() })
                    }
                    if (selectedTab == 2) {
                        LabeledActionButton(label = "Final summary", icon = Icons.Default.AutoAwesome, onClick = { viewModel.generateFinal() })
                    }
                    LabeledActionButton(
                        label = if (isRecording) "Stop" else "Record",
                        icon = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        onClick = { viewModel.toggleRecording() },
                        accentColor = if (isRecording) Color.Red else Gold
                    )
                    LabeledActionButton(label = "Settings", icon = Icons.Default.Settings, onClick = { showSettings = true })
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

    val settingsPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { viewModel.importSettings(it, context) }
    }

    if (selectedSession != null) {
        val isSpeaking by viewModel.isSpeaking.collectAsState()
        SessionDetailView(
            session = selectedSession!!,
            onBack = { selectedSession = null },
            onSpeakSummary = { text -> viewModel.speakSummary(text) },
            onStopSpeaking = { viewModel.stopSpeaking() },
            isSpeaking = isSpeaking
        )
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
                LabeledActionButton(label = "Export all", icon = Icons.Default.Share, onClick = { viewModel.exportArchives(context) })
                LabeledActionButton(label = "Backup settings", icon = Icons.Default.SettingsBackupRestore, onClick = { viewModel.exportSettings(context) })
                LabeledActionButton(label = "Restore settings", icon = Icons.Default.Restore, onClick = { settingsPickerLauncher.launch("application/json") })
                LabeledActionButton(label = "Import JSON", icon = Icons.Default.FileUpload, onClick = { filePickerLauncher.launch("application/json") })
                LabeledActionButton(label = "Pull cloud", icon = Icons.Default.CloudDownload, onClick = { viewModel.pullFromCloud(context) })
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(sessions) { session ->
                    ListItem(
                        headlineContent = { Text(session.name, fontWeight = FontWeight.Bold) },
                        supportingContent = { 
                            Text(SimpleDateFormat("MMM dd, yyyy", Locale.getDefault()).format(Date(session.date))) 
                        },
                        trailingContent = {
                            TextButton(onClick = { viewModel.deleteSession(session) }) {
                                Text("Delete", color = Color.Gray)
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
fun SessionDetailView(
    session: com.example.dndscribe.data.local.SessionEntity,
    onBack: () -> Unit,
    onSpeakSummary: (String) -> Unit = {},
    onStopSpeaking: () -> Unit = {},
    isSpeaking: Boolean = false
) {
    Column(modifier = Modifier.fillMaxSize().background(Parchment)) {
        TopAppBar(
            title = { Text(session.name, fontSize = 16.sp) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (isSpeaking) {
                    LabeledActionButton(label = "Stop", icon = Icons.Default.Stop, onClick = onStopSpeaking, accentColor = Color.Red)
                } else {
                    LabeledActionButton(label = "Speak", icon = Icons.AutoMirrored.Filled.VolumeUp, onClick = { onSpeakSummary(session.finalSummary) }, accentColor = Ink)
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

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("TTS Config", fontWeight = FontWeight.Bold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = config.useLlmUrlForTts,
                            onCheckedChange = { viewModel.updateConfig(config.copy(useLlmUrlForTts = it)) }
                        )
                        Text("Use LLM URL for TTS")
                    }
                    if (!config.useLlmUrlForTts) {
                        TextField(value = config.ttsUrl, onValueChange = { viewModel.updateConfig(config.copy(ttsUrl = it)) }, label = { Text("TTS Base URL") })
                        TextField(value = config.ttsApiKey, onValueChange = { viewModel.updateConfig(config.copy(ttsApiKey = it)) }, label = { Text("TTS API Key") })
                    }
                    TextField(value = config.ttsModel, onValueChange = { viewModel.updateConfig(config.copy(ttsModel = it)) }, label = { Text("TTS Model") })
                    TextField(value = config.ttsVoice, onValueChange = { viewModel.updateConfig(config.copy(ttsVoice = it)) }, label = { Text("TTS Voice") })
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Close") } }
    )
}
