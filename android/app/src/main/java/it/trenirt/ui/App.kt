@file:OptIn(ExperimentalMaterial3Api::class)

package it.trenirt.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import it.trenirt.BuildConfig
import it.trenirt.api.ViaggiaTrenoApi
import it.trenirt.api.ViaggiaTrenoApi.StationTrain
import it.trenirt.api.ViaggiaTrenoApi.TrainDetail
import it.trenirt.api.ViaggiaTrenoApi.TrainStop
import it.trenirt.viewmodel.StationListFilter
import it.trenirt.viewmodel.TreniViewModel
import it.trenirt.viewmodel.UiState
import java.text.SimpleDateFormat
import java.util.*

private class Palette(
    val bg: Color, val card: Color, val border: Color, val text: Color, val muted: Color,
    val accent: Color, val green: Color, val red: Color, val orange: Color
)

private val DarkPalette = Palette(
    bg = Color(0xFF0d1117), card = Color(0xFF161b22), border = Color(0xFF30363d),
    text = Color(0xFFc9d1d9), muted = Color(0xFF8b949e), accent = Color(0xFF58a6ff),
    green = Color(0xFF3fb950), red = Color(0xFFf85149), orange = Color(0xFFd29922)
)

// Light theme for outdoor/daytime use, where a black screen is hard to read in sunlight.
private val LightPalette = Palette(
    bg = Color(0xFFffffff), card = Color(0xFFf6f8fa), border = Color(0xFFd0d7de),
    text = Color(0xFF1f2328), muted = Color(0xFF656d76), accent = Color(0xFF0969da),
    green = Color(0xFF1a7f37), red = Color(0xFFcf222e), orange = Color(0xFF9a6700)
)

/** Backs every color used in the UI with mutable Compose state so toggling the theme updates
 *  every composable that reads C.xxx, without threading a palette parameter through the whole
 *  tree. [applyDark] must run before the rest of the tree composes each time isDarkTheme changes. */
object C {
    var bg by mutableStateOf(DarkPalette.bg)
    var card by mutableStateOf(DarkPalette.card)
    var border by mutableStateOf(DarkPalette.border)
    var text by mutableStateOf(DarkPalette.text)
    var muted by mutableStateOf(DarkPalette.muted)
    var accent by mutableStateOf(DarkPalette.accent)
    var green by mutableStateOf(DarkPalette.green)
    var red by mutableStateOf(DarkPalette.red)
    var orange by mutableStateOf(DarkPalette.orange)

    fun applyDark(isDark: Boolean) {
        val p = if (isDark) DarkPalette else LightPalette
        bg = p.bg; card = p.card; border = p.border; text = p.text; muted = p.muted
        accent = p.accent; green = p.green; red = p.red; orange = p.orange
    }
}

fun delayColor(delay: Int): Color = when {
    delay > 0 -> C.orange
    delay < 0 -> C.accent
    else -> C.green
}

/** [schedHHmm]'s instant on the day identified by [referenceDayMs] (its own midnight, ms) —
 *  used to tell "hasn't reached its scheduled time yet" apart from "confirmed on time", since
 *  a delay of 0 means either depending on whether the train has actually run yet. */
fun scheduledInstant(schedHHmm: String?, referenceDayMs: Long): Date? {
    if (schedHHmm.isNullOrBlank()) return null
    val parts = schedHHmm.split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
    if (referenceDayMs > 0) cal.timeInMillis = referenceDayMs
    cal.set(Calendar.HOUR_OF_DAY, h)
    cal.set(Calendar.MINUTE, m)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.time
}

@Composable
fun TreniRTApp(vm: TreniViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    C.applyDark(state.isDarkTheme)

    // Hardware/gesture back should behave like the in-app "← Indietro" button — return to the
    // previous screen instead of exiting the app straight out of train detail or help.
    BackHandler(enabled = state.showDetail || state.showHelp) {
        if (state.showHelp) vm.hideHelp() else vm.goBack()
    }

    val scheme = if (state.isDarkTheme) darkColorScheme(
        background = C.bg, surface = C.card, primary = C.accent,
        onBackground = C.text, onSurface = C.text, onPrimary = C.bg,
        error = C.red, surfaceVariant = C.card, outline = C.border
    ) else lightColorScheme(
        background = C.bg, surface = C.card, primary = C.accent,
        onBackground = C.text, onSurface = C.text, onPrimary = Color.White,
        error = C.red, surfaceVariant = C.card, outline = C.border
    )

    MaterialTheme(colorScheme = scheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = C.bg) {
            // Edge-to-edge is enabled in MainActivity, so pad by the system bars ourselves —
            // otherwise the header sits under the status bar clock/icons and the last list item
            // sits under the gesture/navigation bar.
            Column(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Text("🚆 TreniRT", color = C.accent, fontSize = 22.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = C.muted, fontSize = 11.sp)
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.toggleTheme() }, modifier = Modifier.size(36.dp)) {
                        Text(if (state.isDarkTheme) "☀️" else "🌙", fontSize = 18.sp)
                    }
                    IconButton(onClick = { vm.showHelp() }, modifier = Modifier.size(36.dp)) {
                        Text("❓", fontSize = 16.sp)
                    }
                }

                when {
                    state.showHelp -> HelpScreen(vm::hideHelp)
                    state.showDetail && state.trainDetail != null -> {
                        TrainDetailScreen(state.trainDetail!!, state.currentTrainReferenceDay, state.isLoading, vm::goBack, vm::refreshTrainDetail, vm::onStationClicked)
                    }
                    state.mode == "train" -> {
                        TrainSearchTab(state, vm)
                    }
                    else -> {
                        StationSearchTab(state, vm)
                    }
                }
            }
        }
    }
}

// ── Station Search ──────────────────────────────────────────────────

@Composable
fun StationSearchTab(state: UiState, vm: TreniViewModel) {
    val hasStation = state.selectedStation != null
    // Tab switcher
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabButton("Stazione", true, {}, Modifier.weight(1f))
        TabButton("Numero Treno", false, { vm.switchToTrainMode() }, Modifier.weight(1f))
    }
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = state.stationQuery,
        onValueChange = { vm.onStationQueryChanged(it) },
        label = { Text("Cerca stazione...") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.accent, unfocusedBorderColor = C.border,
            cursorColor = C.accent, focusedTextColor = C.text, unfocusedTextColor = C.text,
            focusedLabelColor = C.accent, unfocusedLabelColor = C.muted
        ),
        singleLine = true,
        trailingIcon = if (hasStation) {
            { IconButton(onClick = { vm.clearStation() }) { Icon(Icons.Filled.Close, contentDescription = "Cancella", tint = C.muted) } }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (state.stationSuggestions.size == 1) vm.selectStation(state.stationSuggestions[0])
        })
    )

    // Suggestions list
    if (!hasStation && state.stationSuggestions.isNotEmpty()) {
        LazyColumn(modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth()) {
            items(state.stationSuggestions) { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectStation(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(s.name, color = C.text, fontSize = 14.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(s.code, color = C.muted, fontSize = 12.sp)
                }
                Divider(color = C.border, thickness = 0.5.dp)
            }
        }
    }

    // Recent origin→destination shortcuts — fastest path when in a hurry
    if (!hasStation && state.stationQuery.isEmpty() && state.recentTrips.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Ricerche recenti", color = C.muted, fontSize = 12.sp)
        Column(modifier = Modifier.fillMaxWidth()) {
            state.recentTrips.forEach { trip ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectRecentTrip(trip) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(trip.origin.name, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(" → ", color = C.accent, fontSize = 13.sp)
                    Text(trip.destination.name, color = C.text, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Divider(color = C.border, thickness = 0.5.dp)
            }
        }
    }

    // Optional destination filter — only meaningful once an origin station is picked
    if (hasStation) {
        val destinationSelected = state.selectedDestination != null
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = state.destinationQuery,
            onValueChange = { vm.onDestinationQueryChanged(it) },
            label = { Text(if (state.filter == StationListFilter.DEPARTURES) "Destinazione (opzionale)" else "Provenienza (opzionale)") },
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = C.accent, unfocusedBorderColor = C.border,
                cursorColor = C.accent, focusedTextColor = C.text, unfocusedTextColor = C.text,
                focusedLabelColor = C.accent, unfocusedLabelColor = C.muted
            ),
            singleLine = true,
            trailingIcon = if (destinationSelected) {
                { IconButton(onClick = { vm.clearDestination() }) { Icon(Icons.Filled.Close, contentDescription = "Rimuovi filtro", tint = C.muted) } }
            } else null,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                if (state.destinationSuggestions.size == 1) vm.selectDestination(state.destinationSuggestions[0])
            })
        )
        if (!destinationSelected && state.destinationSuggestions.isNotEmpty()) {
            LazyColumn(modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth()) {
                items(state.destinationSuggestions) { s ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { vm.selectDestination(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(s.name, color = C.text, fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(s.code, color = C.muted, fontSize = 12.sp)
                    }
                    Divider(color = C.border, thickness = 0.5.dp)
                }
            }
        }
        if (destinationSelected) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { vm.swapStations() }) {
                    Text("⇅ Inverti partenza/destinazione", color = C.accent, fontSize = 12.sp)
                }
            }
        }
    }

    // Filters + time
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        FilterButton("Partenze", state.filter == StationListFilter.DEPARTURES) { vm.setFilter(StationListFilter.DEPARTURES) }
        FilterButton("Arrivi", state.filter == StationListFilter.ARRIVALS) { vm.setFilter(StationListFilter.ARRIVALS) }
        Spacer(modifier = Modifier.weight(1f))
        if (hasStation) {
            IconButton(onClick = { vm.loadStationTrains() }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna", tint = C.accent)
            }
        }
        TimePickerField(state.timeOverride, vm::setTimeOverride)
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.accent)
        }
    } else if (state.error != null && state.stationTrains.isEmpty()) {
        Text(state.error!!, color = C.orange, modifier = Modifier.padding(16.dp))
    } else if (state.isCheckingStops) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(color = C.accent, modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Verifica fermate...", color = C.muted, fontSize = 13.sp)
        }
    } else if (state.selectedDestination != null && state.displayedTrains.isEmpty() && !state.stopVerificationUnavailable) {
        Text("Nessun treno, nemmeno con cambio, per ${state.selectedDestination.name}", color = C.orange, modifier = Modifier.padding(16.dp))
    } else {
        if (state.error != null) {
            Text(state.error!!, color = C.orange, fontSize = 12.sp)
        }
        if (state.selectedDestination != null && state.stopVerificationUnavailable) {
            Text(
                "⚠️ Non riesco a verificare le fermate per questo orario (dati non ancora disponibili) — " +
                    "ecco tutti i treni, controlla tu quali fermano a ${state.selectedDestination.name}",
                color = C.orange, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp)
            )
        }
        val listState = rememberLazyListState()
        LaunchedEffect(listState, state.displayedTrains.size, state.selectedStation, state.noMoreTrainsToLoad) {
            if (state.noMoreTrainsToLoad) return@LaunchedEffect
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                .collect { lastVisible ->
                    if (lastVisible != null && state.displayedTrains.isNotEmpty() && lastVisible >= state.displayedTrains.size - 3) {
                        vm.loadMoreTrains()
                    }
                }
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            items(state.displayedTrains) { train ->
                Column {
                    TrainCard(train) { vm.loadTrainDetail(train.codOrigine, train.numeroTreno, train.dataPartenzaTreno) }
                    state.connectionInfo[train.numeroTreno]?.let { conn ->
                        val sdf = SimpleDateFormat("HH:mm", Locale.ITALIAN)
                        Text(
                            "🔄 Cambio a ${conn.transferStationName} (arrivo ${sdf.format(Date(conn.transferArrivalTime))}) " +
                                "→ ${conn.connectingCategory} ${conn.connectingNumber} delle ${sdf.format(Date(conn.transferDepartureTime))}",
                            color = C.accent, fontSize = 11.sp,
                            modifier = Modifier.padding(start = 6.dp, bottom = 6.dp)
                        )
                    }
                }
            }
            if (state.isLoadingMore) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = C.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

// ── Train Search ─────────────────────────────────────────────────────

@Composable
fun TrainSearchTab(state: UiState, vm: TreniViewModel) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TabButton("Stazione", false, { vm.switchToStationMode() }, Modifier.weight(1f))
        TabButton("Numero Treno", true, {}, Modifier.weight(1f))
    }
    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = state.trainQuery,
        onValueChange = { vm.onTrainQueryChanged(it) },
        label = { Text("Numero treno (es. 9584)") },
        modifier = Modifier.fillMaxWidth(),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = C.accent, unfocusedBorderColor = C.border,
            cursorColor = C.accent, focusedTextColor = C.text, unfocusedTextColor = C.text,
            focusedLabelColor = C.accent, unfocusedLabelColor = C.muted
        ),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {
            if (state.trainSuggestions.size == 1) vm.selectTrain(state.trainSuggestions[0])
        })
    )

    if (state.trainSuggestions.isNotEmpty()) {
        LazyColumn(modifier = Modifier.heightIn(max = 180.dp).fillMaxWidth()) {
            items(state.trainSuggestions) { s ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectTrain(s) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${s.number} — ${s.originName}", color = C.text, fontSize = 14.sp)
                }
                Divider(color = C.border, thickness = 0.5.dp)
            }
        }
    }

    // Recent train searches — fastest path when in a hurry
    if (state.trainQuery.isEmpty() && state.recentTrains.isNotEmpty()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text("Ricerche recenti", color = C.muted, fontSize = 12.sp)
        Column(modifier = Modifier.fillMaxWidth()) {
            state.recentTrains.forEach { t ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.selectTrain(t) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${t.number} — ${t.originName}", color = C.text, fontSize = 13.sp)
                }
                Divider(color = C.border, thickness = 0.5.dp)
            }
        }
    }

    if (state.isLoading) {
        Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = C.accent)
        }
    }
}

@Composable
fun TimePickerField(currentTime: Date?, onTimeSet: (Date?) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val timeStr = currentTime?.let { SimpleDateFormat("HH:mm", Locale.ITALIAN).format(it) } ?: "Adesso"

    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { showDialog = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = if (currentTime != null) C.accent else C.muted),
            border = androidx.compose.foundation.BorderStroke(1.dp, C.border),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
        ) {
            Text("🕐 $timeStr", fontSize = 13.sp)
        }
        if (currentTime != null) {
            IconButton(onClick = { onTimeSet(null) }, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Reimposta a adesso", tint = C.muted, modifier = Modifier.size(16.dp))
            }
        }
    }

    if (showDialog) {
        val initCal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome")).apply { currentTime?.let { time = it } }
        val pickerState = rememberTimePickerState(
            initialHour = initCal.get(Calendar.HOUR_OF_DAY),
            initialMinute = initCal.get(Calendar.MINUTE),
            is24Hour = true
        )
        Dialog(onDismissRequest = { showDialog = false }) {
            Surface(shape = RoundedCornerShape(16.dp), color = C.card) {
                Column(modifier = Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Seleziona orario", color = C.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))
                    TimePicker(state = pickerState)
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { showDialog = false }) { Text("Annulla", color = C.muted) }
                        TextButton(onClick = {
                            val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
                            cal.set(Calendar.HOUR_OF_DAY, pickerState.hour)
                            cal.set(Calendar.MINUTE, pickerState.minute)
                            cal.set(Calendar.SECOND, 0)
                            onTimeSet(cal.time)
                            showDialog = false
                        }) { Text("OK", color = C.accent) }
                    }
                }
            }
        }
    }
}

// ── Train card in station list ───────────────────────────────────────

@Composable
fun TrainCard(train: StationTrain, onClick: () -> Unit) {
    val isCancelled = train.provvedimento == 1
    val isPartialCancel = train.provvedimento == 2 || train.riprogrammazione == "Y"
    val delay = train.ritardo
    val category = train.categoriaDescrizione.trim()
    val number = train.numeroTreno
    val label = if (category.isNotEmpty()) "$category $number" else "Treno $number"
    val dest = train.destinazione ?: train.origine ?: "—"
    val schedTime = train.compOrarioPartenza ?: train.compOrarioArrivo ?: "—"
    val realTime = train.compOrarioPartenzaZeroEffettivo ?: train.compOrarioArrivoZeroEffettivo
    val platform = train.binarioEffettivoPartenzaDescrizione ?: train.binarioEffettivoArrivoDescrizione
    // delay == 0 is ambiguous: it means either "confirmed on time" or "hasn't run yet, so no
    // delay info exists at all". Compare the scheduled time to the actual clock to tell them apart
    // instead of defaulting to the (potentially false) "in orario" claim.
    val notYetDue = !isCancelled && delay == 0 &&
        scheduledInstant(schedTime, train.dataPartenzaTreno)?.after(Date()) == true
    val delayCol = if (notYetDue) C.muted else delayColor(delay)
    val delayText = when {
        delay > 0 -> "+${delay}'"
        delay < 0 -> "${delay}'"
        notYetDue -> "Non partito"
        else -> "In orario"
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        colors = CardDefaults.cardColors(containerColor = C.card),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCancelled) Text("❌ ", fontSize = 13.sp, color = C.red)
                Text(label, color = if (isCancelled) C.red else C.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (platform != null) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Bin $platform", color = C.accent, fontSize = 12.sp,
                        modifier = Modifier.background(C.accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                }
                Spacer(modifier = Modifier.weight(1f))
                Text(delayText, color = delayCol, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(dest, color = if (isCancelled) C.red.copy(alpha = 0.6f) else C.muted, fontSize = 13.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (isCancelled) TextDecoration.LineThrough else null)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⏰ $schedTime", color = if (isCancelled) C.red.copy(alpha = 0.6f) else C.text, fontSize = 13.sp)
                if (realTime != null && realTime != schedTime) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("→ $realTime", color = delayCol, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                if (isPartialCancel && !isCancelled) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("PARZ. CANCELLATO", color = C.orange, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
                if (train.inStazione) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text("🟢 In stazione", color = C.green, fontSize = 11.sp)
                }
            }
        }
    }
}

// ── Help ─────────────────────────────────────────────────────────────

@Composable
fun HelpScreen(onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TextButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = C.text)
            Spacer(modifier = Modifier.width(4.dp))
            Text("Indietro", color = C.text)
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            HelpTitle("🚆 Come funziona TreniRT")
            HelpBody("È un'app per vedere in tempo reale gli orari dei treni italiani, usando gli stessi dati di ViaggiaTreno (Trenitalia). Niente account, niente pubblicità: apri, cerchi, guardi il treno.")

            HelpTitle("Cercare per stazione")
            HelpBody("Scrivi il nome della stazione e scegli dai suggerimenti. Puoi vedere le Partenze o gli Arrivi, e scegliere un orario diverso da \"adesso\" toccando il pulsante con l'orologio.")
            HelpBody("Se aggiungi anche una destinazione (o provenienza), l'app ti mostra solo i treni che ci arrivano davvero — anche quelli che richiedono un cambio a metà strada, indicandoti dove scendere e che treno prendere dopo.")

            HelpTitle("Cercare per numero treno")
            HelpBody("Scrivi il numero e vedi tutte le fermate di quel treno, con orari previsti e reali, ritardo e dove si trova adesso. Funziona anche per un treno partito da ore.")

            HelpTitle("Ricerche recenti")
            HelpBody("Le ultime combinazioni partenza→destinazione e gli ultimi numeri treno cercati restano salvati come scorciatoie, per non dover riscrivere tutto quando sei di corsa.")

            HelpTitle("Aggiornamento dei dati")
            HelpBody("La lista si aggiorna da sola ogni minuto. C'è anche un pulsante di aggiornamento manuale (🔄) se vuoi essere sicuro di avere l'ultimissimo dato subito.")

            HelpTitle("⏳ Un limite da conoscere: la ricerca nel passato")
            HelpBody("Se cerchi un treno per NUMERO, l'app può mostrartelo anche ore dopo che è partito: quel dato resta disponibile per tutta la giornata.")
            HelpBody("Se invece cerchi per STAZIONE, la situazione è diversa: quella lista è una specie di \"tabellone dal vivo\", legata all'orologio reale del momento — non è un archivio consultabile. Se chiedi un orario di più di un paio d'ore fa, il tabellone risulta vuoto, perché quel dato semplicemente non esiste più da nessuna parte (non è colpa dell'app: Trenitalia stessa non lo mette a disposizione).")
            HelpBody("C'è un'eccezione: se l'app ha già mostrato quei treni in questa sessione (ad esempio con \"adesso\"), li tiene a memoria e te li fa rivedere anche dopo che sono partiti. Ma se apri l'app e chiedi subito un orario passato senza che l'app li abbia mai visti dal vivo, ti conviene cercare per numero treno invece che per stazione.")

            HelpTitle("Tema chiaro / scuro")
            HelpBody("Il pulsante ☀️/🌙 in alto cambia il tema: scuro per la sera, chiaro per usarla sotto il sole senza fatica. La scelta resta salvata.")
        }
    }
}

@Composable
private fun HelpTitle(text: String) {
    Text(text, color = C.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
}

@Composable
private fun HelpBody(text: String) {
    Text(text, color = C.text, fontSize = 13.sp, lineHeight = 19.sp, modifier = Modifier.padding(bottom = 8.dp))
}

// ── Train Detail ─────────────────────────────────────────────────────

@Composable
fun TrainDetailScreen(detail: TrainDetail, referenceDay: Long, isLoading: Boolean, onBack: () -> Unit, onRefresh: () -> Unit, onStationClick: (String, String) -> Unit) {
    val isCancelled = detail.provvedimento == 1
    val isPartialCancel = detail.provvedimento == 2
    val delay = detail.ritardo
    val notYetDue = !isCancelled && delay == 0 &&
        scheduledInstant(detail.compOrarioPartenza, referenceDay)?.after(Date()) == true

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Indietro", tint = C.text)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Indietro", color = C.text)
            }
            Spacer(modifier = Modifier.weight(1f))
            if (isLoading) {
                CircularProgressIndicator(color = C.accent, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Aggiorna", tint = C.accent)
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Text(detail.categoria, color = C.muted, fontSize = 13.sp)
            Text("${detail.origine} → ${detail.destinazione}", color = C.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                when {
                    notYetDue -> "🕐 Non ancora partito"
                    delay == 0 -> "✅ In orario"
                    delay > 0 -> "⚠️ +$delay min"
                    else -> "$delay min"
                },
                color = if (notYetDue) C.muted else delayColor(delay), fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            if (isCancelled) Text("CANCELLATO", color = C.red, fontWeight = FontWeight.Bold)
            else if (isPartialCancel) Text("PARZIALMENTE CANCELLATO", color = C.orange, fontWeight = FontWeight.Bold)

            if (detail.stazioneUltimoRilevamento.isNotEmpty() && detail.stazioneUltimoRilevamento != "--") {
                val lastTime = if (detail.oraUltimoRilevamento > 0) {
                    SimpleDateFormat("HH:mm", Locale.ITALIAN).format(Date(detail.oraUltimoRilevamento))
                } else null
                Text("📍 ${detail.stazioneUltimoRilevamento}${if (lastTime != null) " alle $lastTime" else ""}", color = C.muted, fontSize = 12.sp)
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(detail.fermate) { stop -> StopRow(stop, onStationClick) }
        }
    }
}

@Composable
fun StopRow(stop: TrainStop, onStationClick: (String, String) -> Unit) {
    val isCancelled = stop.actualFermataType == 3
    val isOrigin = stop.tipoFermata == "P"
    val isDest = stop.tipoFermata == "A"
    val hasArrived = stop.arrivoReale > 0
    val hasDeparted = stop.partenzaReale > 0
    val isPassed = hasDeparted || hasArrived

    val dotColor = when {
        isCancelled -> C.red
        isPassed -> C.green
        else -> C.border
    }
    val sdf = SimpleDateFormat("HH:mm", Locale.ITALIAN)

    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
        Box(modifier = Modifier.padding(top = 4.dp, end = 10.dp).size(10.dp).background(dotColor, RoundedCornerShape(5.dp)))

        Column(modifier = Modifier.weight(1f)) {
            // Station name - clickable if it has an ID
            if (stop.id.isNotEmpty()) {
                Text(
                    stop.stazione,
                    color = C.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onStationClick(stop.id, stop.stazione) }
                )
            } else {
                Text(stop.stazione, color = if (isCancelled) C.red else C.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }

            when {
                isOrigin -> StopTimeRow(
                    schedLabel = "Part.", sched = if (stop.partenza_teorica > 0) sdf.format(Date(stop.partenza_teorica)) else "—",
                    real = if (stop.partenzaReale > 0) sdf.format(Date(stop.partenzaReale)) else null,
                    delay = stop.ritardoPartenza,
                    platform = stop.binarioEffettivoPartenzaDescrizione ?: stop.binarioProgrammatoPartenzaDescrizione
                )
                isDest -> StopTimeRow(
                    schedLabel = "Arr.", sched = if (stop.arrivo_teorico > 0) sdf.format(Date(stop.arrivo_teorico)) else "—",
                    real = if (stop.arrivoReale > 0) sdf.format(Date(stop.arrivoReale)) else null,
                    delay = maxOf(stop.ritardoArrivo, stop.ritardo),
                    platform = stop.binarioEffettivoArrivoDescrizione ?: stop.binarioProgrammatoArrivoDescrizione
                )
                else -> {
                    val arrSched = if (stop.arrivo_teorico > 0) sdf.format(Date(stop.arrivo_teorico)) else "—"
                    val arrReal = if (stop.arrivoReale > 0) sdf.format(Date(stop.arrivoReale)) else null
                    val depSched = if (stop.partenza_teorica > 0) sdf.format(Date(stop.partenza_teorica)) else "—"
                    val depReal = if (stop.partenzaReale > 0) sdf.format(Date(stop.partenzaReale)) else null
                    val platform = stop.binarioEffettivoArrivoDescrizione ?: stop.binarioEffettivoPartenzaDescrizione ?: stop.binarioProgrammatoArrivoDescrizione

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("↓$arrSched", color = C.text, fontSize = 11.sp)
                        if (arrReal != null && arrReal != arrSched) Text(" → $arrReal", color = delayColor(stop.ritardoArrivo), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (stop.ritardoArrivo > 0) Text(" +${stop.ritardoArrivo}'", color = C.orange, fontSize = 10.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("↓$depSched", color = C.text, fontSize = 11.sp)
                        if (depReal != null && depReal != depSched) Text(" → $depReal", color = delayColor(stop.ritardoPartenza), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (platform != null) { Spacer(modifier = Modifier.width(4.dp)); Text("Bin $platform", color = C.accent, fontSize = 10.sp) }
                    }
                }
            }
            if (isCancelled) Text("SOPPRESSA", color = C.red, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StopTimeRow(schedLabel: String, sched: String, real: String?, delay: Int, platform: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$schedLabel: $sched", color = C.text, fontSize = 12.sp)
        if (real != null && real != sched) Text(" → $real", color = delayColor(delay), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        if (delay > 0) Text(" +${delay}'", color = C.orange, fontSize = 11.sp)
        else if (delay < 0) Text(" ${delay}'", color = C.accent, fontSize = 11.sp)
        else if (real != null) Text(" in orario", color = C.green, fontSize = 11.sp)
        if (platform != null) { Spacer(modifier = Modifier.width(6.dp)); Text("Bin $platform", color = C.accent, fontSize = 11.sp) }
    }
}

// ── Reusable components ─────────────────────────────────────────────

@Composable
fun TabButton(text: String, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = if (active) C.accent else C.card, contentColor = if (active) Color.White else C.text),
        shape = RoundedCornerShape(8.dp)
    ) { Text(text, fontSize = 13.sp) }
}

@Composable
fun FilterButton(text: String, active: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = if (active) C.accent else C.card, contentColor = if (active) Color.White else C.text),
        shape = RoundedCornerShape(6.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
    ) { Text(text, fontSize = 12.sp) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TreniRTApp() }
    }
}