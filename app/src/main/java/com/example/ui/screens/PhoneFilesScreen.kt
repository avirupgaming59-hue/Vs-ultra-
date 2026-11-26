package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.CodeLanguage
import com.example.data.model.PhoneCodeFile
import com.example.ui.components.mouseWheelHorizontalScroll
import com.example.ui.components.mouseWheelScroll
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.viewmodel.PhoneFilesUiState
import java.util.Locale

@Composable
fun PhoneFilesScreen(
    uiState: PhoneFilesUiState,
    hasLinkedSafFolder: Boolean,
    onPickPhoneFolder: () -> Unit,
    onResetToAppWorkspace: () -> Unit,
    onImportPhoneFile: () -> Unit,
    onSelectLanguage: (CodeLanguage?) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onCreateFile: (name: String, language: CodeLanguage) -> Unit,
    onOpenFile: (PhoneCodeFile) -> Unit,
    onSendToVsCode: (PhoneCodeFile) -> Unit,
    onDeleteFile: (PhoneCodeFile) -> Unit,
    onUpdateEditingContent: (String) -> Unit,
    onSaveEditingFile: () -> Unit,
    onSaveAndSendEditingFile: () -> Unit,
    onCloseEditor: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (uiState.editingFile != null) {
        LocalCodeEditorScreen(
            file = uiState.editingFile,
            content = uiState.editingContent,
            isSaving = uiState.isSavingFile,
            onContentChange = onUpdateEditingContent,
            onSave = onSaveEditingFile,
            onSaveAndSend = onSaveAndSendEditingFile,
            onBack = onCloseEditor,
            modifier = modifier,
        )
        return
    }

    var showCreateDialog by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<PhoneCodeFile?>(null) }

    val filteredFiles = remember(uiState.files, uiState.selectedLanguage, uiState.searchQuery) {
        uiState.files.filter { file ->
            val matchesLang = uiState.selectedLanguage == null || file.language == uiState.selectedLanguage
            val matchesQuery = uiState.searchQuery.isBlank() ||
                file.displayName.contains(uiState.searchQuery, ignoreCase = true)
            matchesLang && matchesQuery
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        floatingActionButton = {
            SmallFloatingActionButton(
                onClick = { showCreateDialog = true },
                modifier = Modifier.testTag("create_phone_file_fab"),
            ) {
                Icon(
                    Icons.Filled.Add,
                    contentDescription = "New code file on phone",
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .widthIn(max = 760.dp)
                    .padding(horizontal = 12.dp),
            ) {
                Spacer(modifier = Modifier.height(8.dp))

                // Folder Location Card
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.FolderOpen,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Phone Storage Source",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    text = uiState.folderDisplayName,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        val folderActionsScroll = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(folderActionsScroll)
                                .mouseWheelHorizontalScroll(folderActionsScroll),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            AssistChip(
                                onClick = onPickPhoneFolder,
                                label = { Text("Link Phone Folder") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.CreateNewFolder,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                modifier = Modifier.testTag("link_phone_folder_button"),
                            )
                            AssistChip(
                                onClick = onImportPhoneFile,
                                label = { Text("Import from Phone") },
                                leadingIcon = {
                                    Icon(
                                        Icons.Filled.FileOpen,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                },
                                modifier = Modifier.testTag("import_phone_file_button"),
                            )
                            if (hasLinkedSafFolder) {
                                AssistChip(
                                    onClick = onResetToAppWorkspace,
                                    label = { Text("Use App Workspace") },
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Search input
                OutlinedTextField(
                    value = uiState.searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Search phone files...") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_phone_files_input"),
                )

                Spacer(modifier = Modifier.height(6.dp))

                // Language Filter Chips
                val langChipScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(langChipScroll)
                        .mouseWheelHorizontalScroll(langChipScroll),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                FilterChip(
                    selected = uiState.selectedLanguage == null,
                    onClick = { onSelectLanguage(null) },
                    label = { Text("All (${uiState.files.size})") },
                    modifier = Modifier.testTag("lang_chip_all"),
                )
                CodeLanguage.entries.forEach { lang ->
                    val count = uiState.files.count { it.language == lang }
                    if (count > 0 || lang in listOf(
                            CodeLanguage.PYTHON,
                            CodeLanguage.TYPESCRIPT,
                            CodeLanguage.JAVASCRIPT,
                            CodeLanguage.KOTLIN,
                            CodeLanguage.HTML,
                        )
                    ) {
                        FilterChip(
                            selected = uiState.selectedLanguage == lang,
                            onClick = {
                                onSelectLanguage(if (uiState.selectedLanguage == lang) null else lang)
                            },
                            label = {
                                Text(if (count > 0) "${lang.displayName} ($count)" else lang.displayName)
                            },
                            modifier = Modifier.testTag("lang_chip_${lang.name}"),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(6.dp))
            }

            if (!uiState.errorMessage.isNullOrBlank()) {
                Card(
                    shape = RoundedCornerShape(10.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                ) {
                    Text(
                        text = uiState.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(10.dp),
                    )
                }
            }

            if (filteredFiles.isEmpty()) {
                val emptyScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(emptyScroll)
                        .mouseWheelScroll(emptyScroll),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        modifier = Modifier.widthIn(max = 320.dp).padding(vertical = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(36.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (uiState.isLoading) "Loading files..." else "No matching files on phone",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = "Create a new Python, TypeScript, Kotlin, or HTML file on your phone, or link an existing phone folder and send files directly into VS Code.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Button(
                            onClick = { showCreateDialog = true },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Text("New Code File")
                        }
                    }
                }
            } else {
                val filesListState = rememberLazyListState()
                LazyColumn(
                    state = filesListState,
                    modifier = Modifier
                        .weight(1f)
                        .mouseWheelScroll(filesListState),
                    contentPadding = PaddingValues(bottom = 72.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredFiles, key = { it.id }) { file ->
                        PhoneFileCard(
                            file = file,
                            onEdit = { onOpenFile(file) },
                            onSend = { onSendToVsCode(file) },
                            onDelete = { fileToDelete = file },
                        )
                    }
                }
            }
            }
        }
    }

    if (showCreateDialog) {
        CreateLanguageFileDialog(
            initialLanguage = uiState.selectedLanguage ?: CodeLanguage.PYTHON,
            onDismiss = { showCreateDialog = false },
            onCreate = { name, lang ->
                showCreateDialog = false
                onCreateFile(name, lang)
            },
        )
    }

    fileToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text("Delete Phone File") },
            text = { Text("Delete '${target.displayName}' from your phone storage?") },
            confirmButton = {
                Button(
                    onClick = {
                        fileToDelete = null
                        onDeleteFile(target)
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun PhoneFileCard(
    file: PhoneCodeFile,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(file.language.badgeColor)
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = file.language.badgeLabel,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        color = Color.White,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = file.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${file.language.displayName} • ${formatBytes(file.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                IconButton(onClick = onDelete, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = "Delete ${file.displayName}",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                OutlinedButton(
                    onClick = onEdit,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Edit", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }

                Button(
                    onClick = onSend,
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("send_to_vscode_${file.displayName}"),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                    )
                    Spacer(modifier = Modifier.width(5.dp))
                    Text("Send to VS Code", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun CreateLanguageFileDialog(
    initialLanguage: CodeLanguage,
    onDismiss: () -> Unit,
    onCreate: (name: String, language: CodeLanguage) -> Unit,
) {
    var selectedLanguage by remember { mutableStateOf(initialLanguage) }
    var fileName by remember { mutableStateOf("main.${initialLanguage.primaryExtension}") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Phone File by Language") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Select Language", style = MaterialTheme.typography.labelLarge)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CodeLanguage.entries.filter { it != CodeLanguage.OTHER }.forEach { lang ->
                        FilterChip(
                            selected = selectedLanguage == lang,
                            onClick = {
                                selectedLanguage = lang
                                val base = fileName.substringBeforeLast('.', "main").ifBlank { "main" }
                                fileName = "$base.${lang.primaryExtension}"
                            },
                            label = { Text(lang.displayName) },
                        )
                    }
                }

                OutlinedTextField(
                    value = fileName,
                    onValueChange = { fileName = it },
                    label = { Text("File Name") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_file_name_input"),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(fileName, selectedLanguage) },
                modifier = Modifier.testTag("confirm_create_file_button"),
            ) {
                Text("Create on Phone")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocalCodeEditorScreen(
    file: PhoneCodeFile,
    content: String,
    isSaving: Boolean,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onSaveAndSend: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)

    val lineCount = remember(content) { content.count { it == '\n' } + 1 }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = file.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${file.language.displayName} • $lineCount lines (Phone)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("close_local_editor_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to files")
                    }
                },
                actions = {
                    IconButton(
                        onClick = onSave,
                        enabled = !isSaving,
                        modifier = Modifier.testTag("save_local_file_button"),
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save to phone")
                    }
                    Button(
                        onClick = onSaveAndSend,
                        enabled = !isSaving,
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .testTag("save_and_send_button"),
                    ) {
                        Text("Send")
                    }
                },
            )
        },
    ) { innerPadding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(8.dp),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            val editorScroll = rememberScrollState()
            BasicTextField(
                value = content,
                onValueChange = onContentChange,
                textStyle = TextStyle(
                    fontFamily = JetBrainsMonoFontFamily,
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(editorScroll)
                    .mouseWheelScroll(editorScroll)
                    .padding(10.dp)
                    .testTag("local_code_editor_field"),
            )
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(Locale.US, "%.1f KB", kb)
    return String.format(Locale.US, "%.1f MB", kb / 1024.0)
}
