package vn.tramtruyen.reader.presentation.screens

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import vn.tramtruyen.reader.model.VoiceGroup
import vn.tramtruyen.reader.model.VoiceOption
import vn.tramtruyen.reader.engine.speech.VBookModelRow
import vn.tramtruyen.reader.engine.speech.VBookTtsRuntime
import vn.tramtruyen.reader.presentation.PlayerViewModel
import vn.tramtruyen.reader.presentation.UiState
import vn.tramtruyen.reader.presentation.components.Radii
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun VoiceManagerCard(vm: PlayerViewModel, ui: UiState) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var languageFilter by rememberSaveable { mutableStateOf("Tất cả") }
    var providerFilter by rememberSaveable { mutableStateOf("Tất cả") }
    var genderFilter by rememberSaveable { mutableStateOf("Tất cả") }
    var expandedProvider by rememberSaveable(ui.voiceGroups.firstOrNull()?.name) {
        mutableStateOf(ui.voiceGroups.firstOrNull()?.name)
    }
    val collapsedFilteredProviders = remember { mutableStateListOf<String>() }
    var showCloneSheet by rememberSaveable { mutableStateOf(false) }
    var showVBookModels by rememberSaveable { mutableStateOf(true) }
    val vbookState by VBookTtsRuntime.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) {
        VBookTtsRuntime.loadCatalog(context)
        vm.refreshVoiceCatalog()
    }
    val allVoices = ui.voiceGroups.flatMap { group -> group.voices }
    val languageOptions = allVoices.map { it.language.ifBlank { "Khác" } }.distinct().sorted()
    val providerOptions = ui.voiceGroups.map { it.name }.distinct().sorted()
    val genderOptions = allVoices.map { it.gender.ifBlank { "Khác" } }.distinct().sorted()
    val visibleGroups = ui.voiceGroups.mapNotNull { group ->
        if (providerFilter != "Tất cả" && group.name != providerFilter) return@mapNotNull null
        val voices = group.voices.filter { voice ->
            val matchesQuery = query.isBlank() || listOf(
                voice.label, voice.sub, voice.id, voice.provider, voice.language, voice.gender,
            ).any { it.contains(query.trim(), ignoreCase = true) }
            val matchesLanguage = languageFilter == "Tất cả" ||
                (voice.language.ifBlank { "Khác" } == languageFilter)
            val matchesGender = genderFilter == "Tất cả" ||
                (voice.gender.ifBlank { "Khác" } == genderFilter)
            matchesQuery && matchesLanguage && matchesGender
        }
        if (voices.isEmpty()) null else group.copy(voices = voices)
    }

    Surface(
        shape = RoundedCornerShape(Radii.xl),
        color = cs.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(cs.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.RecordVoiceOver, null, tint = cs.onPrimaryContainer)
                }
                Spacer(Modifier.width(13.dp))
                Column(Modifier.weight(1f)) {
                    Text("Quản lý giọng đọc", style = MaterialTheme.typography.titleMedium)
                    Text("${allVoices.size} giọng đang sẵn có", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(28.dp),
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text("Tìm kiếm ${allVoices.size} giọng nói") },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Xóa tìm kiếm") }
                    }
                },
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                VoiceFilterMenu(
                    label = "Ngôn ngữ", selected = languageFilter,
                    options = listOf("Tất cả") + languageOptions,
                    display = ::languageLabel,
                    onSelected = { languageFilter = it },
                )
                VoiceFilterMenu(
                    label = "Công cụ", selected = providerFilter,
                    options = listOf("Tất cả") + providerOptions,
                    onSelected = { providerFilter = it },
                )
                VoiceFilterMenu(
                    label = "Giới tính", selected = genderFilter,
                    options = listOf("Tất cả") + genderOptions,
                    display = ::genderLabel,
                    onSelected = { genderFilter = it },
                )
            }

            Surface(
                shape = RoundedCornerShape(Radii.xl),
                color = cs.surfaceVariant.copy(alpha = 0.42f),
            ) {
                Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!vbookState.runtimeReady) {
                        Text("Bản cài này thiếu thư viện giọng đọc ARM64", style = MaterialTheme.typography.bodySmall, color = cs.error)
                    }
                    if (vbookState.message != null) {
                        Text(vbookState.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = cs.primary)
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable { showVBookModels = !showVBookModels },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Mô hình VBook", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = cs.primary)
                        CountBadge(vbookState.models.size)
                        Spacer(Modifier.width(8.dp))
                        Icon(
                            if (showVBookModels) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                            contentDescription = if (showVBookModels) "Thu gọn model VBook" else "Mở model VBook",
                            tint = cs.primary,
                        )
                    }
                    AnimatedVisibility(showVBookModels) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (vbookState.loadingCatalog && vbookState.models.isEmpty()) {
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Đang tải danh mục giọng VBook…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            }
                            val modelRows = vbookState.models.filter { row ->
                                val model = row.model
                                (query.isBlank() || listOf(model.name, model.id, model.description, model.modelType, model.language)
                                    .any { it.contains(query.trim(), ignoreCase = true) }) &&
                                    (languageFilter == "Tất cả" || languageLabel(model.language) == languageFilter) &&
                                    (providerFilter == "Tất cả" || providerFilter.contains("VBook", ignoreCase = true))
                            }
                            modelRows.forEach { row -> VBookModelRowCard(row, context, vm) }
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Giọng của tôi", style = MaterialTheme.typography.titleMedium, color = cs.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                CountBadge(0)
            }
            Surface(
                onClick = { showCloneSheet = true },
                shape = RoundedCornerShape(Radii.xl),
                color = cs.surfaceVariant.copy(alpha = 0.55f),
            ) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).clip(CircleShape).background(cs.primaryContainer), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Add, null, tint = cs.onPrimaryContainer)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Tạo giọng", style = MaterialTheme.typography.titleSmall, color = cs.primary)
                        Text("Tạo bản sao từ một đoạn ghi âm ngắn", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Đang có sẵn", style = MaterialTheme.typography.titleMedium, color = cs.primary, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(10.dp))
                CountBadge(visibleGroups.sumOf { it.voices.size })
            }
            if (visibleGroups.isEmpty()) {
                Text(
                    "Không tìm thấy giọng phù hợp. Hãy đổi bộ lọc hoặc bật một provider bên dưới.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
            }
            visibleGroups.forEach { group ->
                val filtering = query.isNotBlank() || languageFilter != "Tất cả" ||
                    genderFilter != "Tất cả" || providerFilter != "Tất cả"
                val isExpanded = if (filtering) group.name !in collapsedFilteredProviders
                    else expandedProvider == group.name
                Surface(
                    shape = RoundedCornerShape(Radii.xl),
                    color = cs.surfaceVariant.copy(alpha = 0.42f),
                ) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                if (filtering) {
                                    if (isExpanded) collapsedFilteredProviders.add(group.name)
                                    else collapsedFilteredProviders.remove(group.name)
                                } else {
                                    expandedProvider = if (isExpanded) null else group.name
                                }
                            }.padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(group.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${group.voices.size} giọng · ${if (group.name.contains("VBook", true)) "mô hình trên thiết bị" else "dịch vụ giọng đọc"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = cs.onSurfaceVariant,
                                    maxLines = 1,
                                )
                            }
                            CountBadge(group.voices.size)
                            Spacer(Modifier.width(8.dp))
                            Icon(
                                if (isExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = if (isExpanded) "Thu gọn ${group.name}" else "Mở ${group.name}",
                                tint = cs.primary,
                            )
                        }
                        AnimatedVisibility(visible = isExpanded) {
                            Column {
                                HorizontalDivider(color = cs.outlineVariant.copy(alpha = .65f))
                                group.voices.forEach { voice ->
                                    VoiceRow(voice, ui.selectedVoice, vm)
                                }
                            }
                        }
                    }
                }
            }
            ui.voiceActionStatus?.let { message ->
                Text(message, style = MaterialTheme.typography.bodySmall, color = cs.error)
            }
        }
    }

    if (showCloneSheet) {
        VoiceCloneSheet(context = context, onDismiss = { showCloneSheet = false })
    }
}

@Composable
private fun VBookModelRowCard(row: VBookModelRow, context: Context, vm: PlayerViewModel) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    Surface(shape = RoundedCornerShape(Radii.lg), color = cs.surface.copy(alpha = .72f)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(row.model.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text(
                        listOf(row.model.description, VBookTtsRuntime.engineLabel(row.model.modelType), "${row.voiceCount} giọng", formatModelSize(row.model.sizeBytes))
                            .filter { it.isNotBlank() }.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                        maxLines = 2,
                    )
                }
                when {
                    row.progress != null -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    row.installed -> {
                        IconButton(onClick = {
                            scope.launch { runCatching { VBookTtsRuntime.removeModel(row.model.id) }; vm.refreshVoiceCatalog() }
                        }) { Icon(Icons.Outlined.DeleteOutline, "Xóa ${row.model.name}", tint = cs.error) }
                    }
                    else -> IconButton(onClick = {
                        scope.launch {
                            runCatching { VBookTtsRuntime.installModel(row.model.id) }
                            vm.refreshVoiceCatalog()
                        }
                    }) { Icon(Icons.Outlined.Download, "Tải ${row.model.name}", tint = cs.primary) }
                }
            }
            if (row.progress != null) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { row.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

private fun formatModelSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}

@Composable
private fun VoiceFilterMenu(
    label: String,
    selected: String,
    options: List<String>,
    display: (String) -> String = { it },
    onSelected: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(if (selected == "Tất cả") label else display(selected), maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Outlined.KeyboardArrowDown, null, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(if (option == "Tất cả") "Tất cả $label" else display(option)) },
                    trailingIcon = { if (option == selected) Icon(Icons.Filled.Check, null) },
                    onClick = { onSelected(option); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun VoiceRow(voice: VoiceOption, selectedVoice: String, vm: PlayerViewModel) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalIconButton(onClick = { vm.previewVoice(voice.id) }, modifier = Modifier.size(42.dp)) {
            Icon(Icons.Outlined.PlayArrow, "Nghe thử ${voice.label}", tint = cs.primary)
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
            Text(voice.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                listOf(genderLabel(voice.gender), languageLabel(voice.language), voice.sub.substringAfter(" · ", ""))
                    .filter { it.isNotBlank() }.distinct().joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (voice.id == selectedVoice) {
            Icon(Icons.Filled.Check, "Đang chọn", tint = cs.primary, modifier = Modifier.padding(horizontal = 10.dp))
        } else {
            TextButton(onClick = { vm.setVoice(voice.id) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Chọn")
            }
        }
    }
}

@Composable
private fun CountBadge(count: Int) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = CircleShape, color = cs.secondaryContainer) {
        Text(
            count.toString(),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
            color = cs.onSecondaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

private fun languageLabel(language: String): String = when (language.lowercase().substringBefore('-')) {
    "vi", "vie" -> "Tiếng Việt"
    "en", "eng" -> "Tiếng Anh"
    "zh", "cmn" -> "Tiếng Trung"
    "ja", "jpn" -> "Tiếng Nhật"
    "ko", "kor" -> "Tiếng Hàn"
    "khác", "" -> "Khác"
    else -> language
}

private fun genderLabel(gender: String): String = when (gender.uppercase()) {
    "MALE" -> "Nam"
    "FEMALE" -> "Nữ"
    "NEUTRAL" -> "Trung tính"
    "MULTI" -> "Nhiều giọng"
    "OTHER", "" -> "Khác"
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun VoiceCloneSheet(context: Context, onDismiss: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    var recordingName by rememberSaveable { mutableStateOf("") }
    var showTips by rememberSaveable { mutableStateOf(true) }
    val recordingPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        recordingName = uri?.let { displayName(context, it) }.orEmpty()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.ArrowBack, "Quay lại") }
                Text("Nhân bản giọng nói", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier.size(88.dp).align(Alignment.CenterHorizontally).clip(CircleShape).background(cs.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Outlined.AutoAwesome, null, tint = cs.onPrimaryContainer, modifier = Modifier.size(42.dp)) }
            Text(
                "Chọn một đoạn ghi âm ngắn làm mẫu giọng. Ghi âm rõ, đều và chỉ có một người nói để có kết quả tốt hơn.",
                style = MaterialTheme.typography.bodyLarge,
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AssistChip(onClick = {}, enabled = false, label = { Text("10–30 giây") })
                AssistChip(onClick = {}, enabled = false, label = { Text("Một người nói") })
                AssistChip(onClick = {}, enabled = false, label = { Text("Không tạp âm") })
            }
            Text("Bản ghi âm", style = MaterialTheme.typography.titleMedium, color = cs.primary, fontWeight = FontWeight.SemiBold)
            Surface(
                onClick = { recordingPicker.launch(arrayOf("audio/*")) },
                shape = RoundedCornerShape(Radii.xl),
                color = cs.surfaceVariant.copy(alpha = .5f),
            ) {
                Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.UploadFile, null, tint = cs.primary, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (recordingName.isBlank()) "Chọn file ghi âm" else recordingName, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("MP3, WAV, M4A, FLAC…", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
            Surface(shape = RoundedCornerShape(Radii.xl), color = cs.surfaceVariant.copy(alpha = .42f)) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        Modifier.fillMaxWidth().clickable { showTips = !showTips },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Info, null, tint = cs.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Mẹo để giọng giống nhất", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        Icon(if (showTips) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown, null)
                    }
                    AnimatedVisibility(showTips) {
                        Column(Modifier.padding(start = 34.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Tip("Đọc tự nhiên trong 10–30 giây, như đang đọc to một trang sách.")
                            Tip("Chọn nơi yên tĩnh; tắt nhạc và tránh giọng người khác, tiếng quạt hoặc tiếng vọng.")
                            Tip("Nói rõ, đều, tốc độ vừa phải; không hét và không thì thầm.")
                            Tip("Đặt micro gần vừa đủ, tránh tiếng gió và âm thanh bị vỡ.")
                        }
                    }
                }
            }
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth().height(58.dp)) {
                Text("Tạo giọng", fontSize = 18.sp)
            }
            Text(
                if (recordingName.isBlank()) {
                    "NovelBook chưa kết nối dịch vụ nhân bản giọng nói. Các giọng có thể dùng hiện nằm trong danh sách quản lý."
                } else {
                    "Đã chọn tệp mẫu. Để tạo giọng từ tệp này, ứng dụng cần được kết nối với dịch vụ nhân bản giọng nói."
                },
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Tip(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text("•", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Tệp ghi âm"
}
