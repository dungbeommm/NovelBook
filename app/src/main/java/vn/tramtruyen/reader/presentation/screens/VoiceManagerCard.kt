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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import vn.tramtruyen.reader.presentation.LocalBottomBarInset
import vn.tramtruyen.reader.presentation.PlayerViewModel
import vn.tramtruyen.reader.presentation.UiState
import vn.tramtruyen.reader.presentation.components.Radii
import java.util.Locale
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceManagerPage(vm: PlayerViewModel, ui: UiState, onOpenClone: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val catalog by VBookTtsRuntime.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var language by rememberSaveable { mutableStateOf(AllVoices) }
    var tool by rememberSaveable { mutableStateOf(AllVoices) }
    var gender by rememberSaveable { mutableStateOf(AllVoices) }
    var availability by rememberSaveable { mutableStateOf(AllVoices) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteModelId by rememberSaveable { mutableStateOf<String?>(null) }

    LaunchedEffect(context) {
        VBookTtsRuntime.loadCatalog(context)
        vm.refreshVoiceCatalog()
    }

    val allVoices = ui.voiceGroups.flatMap { it.voices }
    val modelVoices = allVoices.filter { it.id.startsWith("vbook:") }
        .groupBy { it.id.substringAfter(':').substringBefore(':') }
    val serviceGroups = ui.voiceGroups.mapNotNull { group ->
        val voices = group.voices.filterNot { it.id.startsWith("vbook:") }
        if (voices.isEmpty()) null else group.copy(voices = voices)
    }
    val search = voiceSearchKey(query.trim())
    fun matchesQuery(vararg labels: String) = search.isEmpty() || labels.any {
        voiceSearchKey(it).contains(search)
    }
    fun matches(candidate: String, selected: String) = selected == AllVoices || candidate == selected

    val languageOptions = (catalog.models.map { languageLabel(it.model.language) } +
        allVoices.map { languageLabel(it.language) }).distinct().sorted()
    val toolOptions = (catalog.models.map { VBookTtsRuntime.engineLabel(it.model.modelType) } +
        serviceGroups.map { it.name }).distinct().sorted()
    val genderOptions = (catalog.models.map { genderLabel(it.model.gender) } +
        allVoices.map { genderLabel(it.gender) }).distinct().sorted()
    val visibleModels = catalog.models.filter { row ->
        val model = row.model
        val engine = VBookTtsRuntime.engineLabel(model.modelType)
        matches(languageLabel(model.language), language) &&
            matches(engine, tool) &&
            (matches(genderLabel(model.gender), gender) || modelVoices[model.id].orEmpty().any {
                matches(genderLabel(it.gender), gender)
            }) &&
            (matchesQuery(model.name, model.description, engine) ||
                model.catalogSpeakerNames.any { matchesQuery(it) } ||
                modelVoices[model.id].orEmpty().any { matchesQuery(it.label) })
    }
    val installed = visibleModels.filter { it.installed && availability != Downloadable && availability != Services }
    val downloads = visibleModels.filter { !it.installed && availability != Installed && availability != Services }
    val services = serviceGroups.mapNotNull { group ->
        val voices = group.voices.filter {
            matches(group.name, tool) && matches(languageLabel(it.language), language) &&
                matches(genderLabel(it.gender), gender) && matchesQuery(it.label, it.sub, group.name)
        }
        if (voices.isEmpty() || availability == Installed || availability == Downloadable) null else group.copy(voices = voices)
    }
    val totalVoices = catalog.models.sumOf { it.voiceCount } + serviceGroups.sumOf { it.voices.size }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 24.dp + LocalBottomBarInset.current),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item("search") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    shape = RoundedCornerShape(Radii.lg),
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    placeholder = { Text("Tìm trong $totalVoices giọng") },
                    trailingIcon = if (query.isEmpty()) null else {
                        { IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, "Xóa tìm kiếm") } }
                    },
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    VoiceFilterMenu("Ngôn ngữ", language, listOf(AllVoices) + languageOptions) { language = it }
                    VoiceFilterMenu("Công cụ", tool, listOf(AllVoices) + toolOptions) { tool = it }
                    VoiceFilterMenu("Giới tính", gender, listOf(AllVoices) + genderOptions) { gender = it }
                    VoiceFilterMenu("Trạng thái", availability, listOf(AllVoices, Installed, Downloadable, Services)) { availability = it }
                }
            }
        }
        item("status") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!catalog.runtimeReady) {
                    Text("Giọng ngoại tuyến cần bản cài ARM64.", color = cs.error, style = MaterialTheme.typography.bodySmall)
                }
                val status = ui.voiceActionStatus ?: catalog.message
                if (!status.isNullOrBlank()) {
                    Text(status, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
                if (catalog.loadingCatalog && catalog.models.isEmpty()) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        item("my_voices") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                VoiceSectionTitle("Giọng của tôi", 0)
                Surface(onClick = onOpenClone, shape = RoundedCornerShape(Radii.lg), color = cs.tertiaryContainer) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.GraphicEq, null, tint = cs.onTertiaryContainer)
                        Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                            Text("Giọng từ bản ghi", style = MaterialTheme.typography.titleMedium, color = cs.onTertiaryContainer)
                            Text("Chọn tệp âm thanh và xem yêu cầu", style = MaterialTheme.typography.bodySmall, color = cs.onTertiaryContainer)
                        }
                        Icon(Icons.Outlined.ChevronRight, null, tint = cs.onTertiaryContainer)
                    }
                }
            }
        }
        if (installed.isNotEmpty()) {
            item("installed") { VoiceSectionTitle(Installed, installed.size) }
            items(installed, key = { it.model.id }) { row ->
                val voices = modelVoices[row.model.id].orEmpty().filter { voice ->
                    matches(genderLabel(voice.gender), gender) &&
                        (matchesQuery(row.model.name, row.model.description, VBookTtsRuntime.engineLabel(row.model.modelType)) ||
                            matchesQuery(voice.label))
                }
                val open = expanded == row.model.id || (search.isNotEmpty() && expanded == null)
                VoicePackageCard(
                    row = row,
                    voices = voices,
                    expanded = open,
                    onToggle = { expanded = if (open) "" else row.model.id },
                    onAction = { deleteModelId = row.model.id },
                    ui = ui,
                    vm = vm,
                )
            }
        }
        if (downloads.isNotEmpty()) {
            item("downloads") { VoiceSectionTitle(Downloadable, downloads.size) }
            items(downloads, key = { it.model.id }) { row ->
                VoicePackageCard(
                    row = row,
                    voices = emptyList(),
                    expanded = false,
                    onToggle = null,
                    onAction = { vm.updateVoiceModel(row.model.id, install = true) },
                    ui = ui,
                    vm = vm,
                    downloadEnabled = catalog.runtimeReady,
                )
            }
        }
        if (services.isNotEmpty()) {
            item("services") { VoiceSectionTitle(Services, services.sumOf { it.voices.size }) }
            items(services, key = { "provider:${it.name}" }) { group ->
                val key = "provider:${group.name}"
                val open = expanded == key || (search.isNotEmpty() && expanded == null)
                Surface(shape = RoundedCornerShape(Radii.lg), color = cs.surfaceContainer) {
                    Column {
                        ListItem(
                            headlineContent = { Text(group.name) },
                            supportingContent = { Text("${group.voices.size} giọng") },
                            leadingContent = { Icon(Icons.Outlined.RecordVoiceOver, null) },
                            trailingContent = { Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null) },
                            modifier = Modifier.clickable { expanded = if (open) "" else key },
                            colors = ListItemDefaults.colors(containerColor = cs.surfaceContainer),
                        )
                        if (open) group.voices.forEach { VoiceRow(it, ui.selectedVoice, vm) }
                    }
                }
            }
        }
        if (installed.isEmpty() && downloads.isEmpty() && services.isEmpty() && !catalog.loadingCatalog) {
            item("empty") { Text("Không tìm thấy giọng hoặc gói phù hợp.", color = cs.onSurfaceVariant) }
        }
    }

    val pending = catalog.models.firstOrNull { it.model.id == deleteModelId && it.installed }
    if (pending != null) {
        val selected = ui.selectedVoice.startsWith("vbook:${pending.model.id}:")
        AlertDialog(
            onDismissRequest = { deleteModelId = null },
            title = { Text(if (selected) "Gói giọng đang được chọn" else "Xóa ${pending.model.name}?") },
            text = { Text(if (selected) "Chọn giọng khác trước khi xóa gói này." else "Bạn có thể tải lại gói này sau.") },
            confirmButton = {
                TextButton(onClick = {
                    if (!selected) vm.updateVoiceModel(pending.model.id, install = false)
                    deleteModelId = null
                }) { Text(if (selected) "Đóng" else "Xóa gói") }
            },
            dismissButton = if (selected) null else {
                { TextButton(onClick = { deleteModelId = null }) { Text("Hủy") } }
            },
        )
    }
}

private const val AllVoices = "Tất cả"
private const val Installed = "Đã cài"
private const val Downloadable = "Có thể tải"
private const val Services = "Nguồn giọng khác"
private val voiceDiacritics = Regex("\\p{M}+")

private fun voiceSearchKey(value: String): String =
    java.text.Normalizer.normalize(value.lowercase(Locale.ROOT).replace('đ', 'd'), java.text.Normalizer.Form.NFD)
        .replace(voiceDiacritics, "")

@Composable
private fun VoiceSectionTitle(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(top = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        CountBadge(count)
    }
}

@Composable
private fun VoicePackageCard(
    row: VBookModelRow,
    voices: List<VoiceOption>,
    expanded: Boolean,
    onToggle: (() -> Unit)?,
    onAction: () -> Unit,
    ui: UiState,
    vm: PlayerViewModel,
    downloadEnabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val engine = VBookTtsRuntime.engineLabel(row.model.modelType)
    Surface(shape = RoundedCornerShape(Radii.lg), color = cs.surfaceContainer) {
        Column {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(cs.secondaryContainer, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = cs.onSecondaryContainer)
                }
                Column(
                    Modifier.weight(1f).then(if (onToggle != null) Modifier.clickable(onClick = onToggle) else Modifier)
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Text(row.model.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "$engine · ${row.voiceCount} giọng · ${formatModelSize(row.model.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                if (onToggle != null) {
                    IconButton(onClick = onToggle) {
                        Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, if (expanded) "Thu gọn giọng" else "Mở danh sách giọng")
                    }
                }
                val preview = row.model.previewUrl
                if (!row.installed && preview != null) {
                    IconButton(onClick = { vm.previewVoiceModel(preview) }) {
                        Icon(Icons.Outlined.PlayArrow, "Nghe mẫu ${row.model.name}")
                    }
                }
                if (row.progress != null) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledTonalIconButton(onClick = onAction, enabled = row.installed || downloadEnabled) {
                        if (row.installed) Icon(Icons.Outlined.DeleteOutline, "Xóa ${row.model.name}")
                        else Icon(Icons.Outlined.Download, "Tải ${row.model.name}")
                    }
                }
            }
            if (row.progress != null) {
                LinearProgressIndicator(progress = { row.progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            }
            if (expanded) {
                HorizontalDivider(color = cs.outlineVariant)
                if (voices.isEmpty()) {
                    Text(
                        "Bật nguồn giọng ngoại tuyến trong mục Nguồn giọng đọc để sử dụng.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = cs.onSurfaceVariant,
                    )
                }
                voices.forEach { voice -> VoiceRow(voice.copy(label = voice.label.removePrefix(row.model.name + " · ")), ui.selectedVoice, vm) }
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
    "mul" -> "Đa ngôn ngữ"
    "khác", "" -> "Khác"
    else -> language
}

private fun genderLabel(gender: String): String = when (gender.uppercase()) {
    "MALE" -> "Nam"
    "FEMALE" -> "Nữ"
    "NEUTRAL" -> "Trung tính"
    "MULTI" -> "Nhiều giọng"
    "OTHER", "" -> "Khác"
    else -> "Khác"
}

@Composable
fun VoiceClonePage() {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    var recording by rememberSaveable { mutableStateOf("") }
    var showTips by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) recording = displayName(context, uri)
    }
    Column(
        Modifier.fillMaxSize().padding(bottom = LocalBottomBarInset.current),
    ) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Surface(shape = RoundedCornerShape(Radii.lg), color = cs.tertiaryContainer) {
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.GraphicEq, null, tint = cs.onTertiaryContainer)
                    Text("Bản ghi làm mẫu", style = MaterialTheme.typography.titleLarge, color = cs.onTertiaryContainer)
                    Text("Chọn đoạn nói của một người, dài 10–30 giây, thu ở nơi yên tĩnh.", color = cs.onTertiaryContainer)
                }
            }
            OutlinedButton(onClick = { picker.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.UploadFile, null)
                Spacer(Modifier.width(10.dp))
                Text(recording.ifBlank { "Chọn tệp MP3, WAV, M4A hoặc FLAC" })
            }
            HorizontalDivider()
            TextButton(onClick = { showTips = !showTips }) {
                Text(if (showTips) "Ẩn hướng dẫn thu âm" else "Xem hướng dẫn thu âm")
            }
            AnimatedVisibility(showTips) {
                Text("Đọc rõ và đều. Để micro gần vừa đủ, tắt nhạc nền và tránh nơi có tiếng vọng.", color = cs.onSurfaceVariant)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Tính năng tạo giọng từ bản ghi chưa khả dụng trong NovelBook.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) { Text("Tạo giọng") }
        }
    }
}
private fun displayName(context: Context, uri: Uri): String {
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Tệp ghi âm"
}
