package it.trenirt.viewmodel

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import it.trenirt.api.ViaggiaTrenoApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.*

enum class StationListFilter { DEPARTURES, ARRIVALS }

data class RecentTrip(
    val origin: ViaggiaTrenoApi.StationSuggestion,
    val destination: ViaggiaTrenoApi.StationSuggestion
)

/** Describes a one-change itinerary: ride the displayed train to [transferStationName],
 *  then continue on [connectingCategory] [connectingNumber]. */
data class TrainConnection(
    val transferStationName: String,
    val transferArrivalTime: Long,
    val transferDepartureTime: Long,
    val connectingCategory: String,
    val connectingNumber: Int
)

data class UiState(
    val stationQuery: String = "",
    val stationSuggestions: List<ViaggiaTrenoApi.StationSuggestion> = emptyList(),
    val selectedStation: ViaggiaTrenoApi.StationSuggestion? = null,
    val stationTrains: List<ViaggiaTrenoApi.StationTrain> = emptyList(),
    val filter: StationListFilter = StationListFilter.DEPARTURES,
    val timeOverride: Date? = null,
    // The date actually used for the currently-shown board — differs from timeOverride when the
    // "past time -> tomorrow" fallback kicked in. Pagination anchors off this, not timeOverride.
    val effectiveBoardDate: Date? = null,
    val isLoading: Boolean = false,
    val trainQuery: String = "",
    val trainSuggestions: List<ViaggiaTrenoApi.TrainSuggestion> = emptyList(),
    val trainDetail: ViaggiaTrenoApi.TrainDetail? = null,
    val showDetail: Boolean = false,
    // Which tab to show when going back from train detail
    val mode: String = "station", // "station" or "train"
    val error: String? = null,
    // Optional destination filter (other end of the journey). Matching against every
    // intermediate stop (not just the train's terminus) requires a per-train detail fetch,
    // so the verified result lands separately from the initial station-board load.
    val destinationQuery: String = "",
    val destinationSuggestions: List<ViaggiaTrenoApi.StationSuggestion> = emptyList(),
    val selectedDestination: ViaggiaTrenoApi.StationSuggestion? = null,
    val isCheckingStops: Boolean = false,
    // null = no destination filter active; non-null (possibly empty) = verified result
    val stopMatchedTrains: List<ViaggiaTrenoApi.StationTrain>? = null,
    // Connection details for entries in stopMatchedTrains that require a change of train, keyed by numeroTreno
    val connectionInfo: Map<Int, TrainConnection> = emptyMap(),
    // True when we couldn't fetch stop data for any candidate (ViaggiaTreno has no live data
    // for trains that far in the future — typically a schedule for a following day). In that
    // case we fall back to showing the raw, unverified board instead of an empty list.
    val stopVerificationUnavailable: Boolean = false,
    val isLoadingMore: Boolean = false,
    // Most-recently-used origin/destination pairs, newest first
    val recentTrips: List<RecentTrip> = emptyList(),
    // Most-recently-searched train numbers, newest first
    val recentTrains: List<ViaggiaTrenoApi.TrainSuggestion> = emptyList(),
    // Identifies the train currently shown in the detail screen, so it can be refreshed
    val currentTrainOriginCode: String? = null,
    val currentTrainNumber: Int? = null,
    val currentTrainReferenceDay: Long = 0L
) {
    /** What the list should actually show right now. */
    val displayedTrains: List<ViaggiaTrenoApi.StationTrain>
        get() = when {
            selectedDestination == null -> stationTrains
            stopVerificationUnavailable && stopMatchedTrains.isNullOrEmpty() -> stationTrains
            else -> stopMatchedTrains ?: emptyList()
        }
}

private fun stationNamesMatch(trainField: String?, target: String): Boolean {
    if (trainField.isNullOrBlank()) return false
    val a = trainField.trim().uppercase(Locale.ITALIAN)
    val b = target.trim().uppercase(Locale.ITALIAN)
    return a == b || a.contains(b) || b.contains(a)
}

// The same train number recurs daily, so andamentoTreno needs the specific run's midnight to
// disambiguate — fall back to today only if the API genuinely didn't give us one.
private fun ViaggiaTrenoApi.StationTrain.resolvedReferenceDay(): Long =
    dataPartenzaTreno.takeIf { it > 0 } ?: ViaggiaTrenoApi.todayMidnightTs()

private fun ViaggiaTrenoApi.TrainSuggestion.resolvedReferenceDay(): Long =
    referenceDay.takeIf { it > 0 } ?: ViaggiaTrenoApi.todayMidnightTs()

class TreniViewModel(app: Application) : AndroidViewModel(app) {
    companion object {
        private const val TAG = "TreniRT"
        private const val PREFS_NAME = "trenirt_prefs"
        private const val KEY_RECENT_TRIPS = "recent_trips"
        private const val KEY_RECENT_TRAINS = "recent_trains"
        private const val MAX_RECENT = 10
        private const val MIN_TRANSFER_MS = 60_000L // 1 minute — don't suggest impossible connections
        private const val MAX_TRANSFER_WAIT_MS = 90 * 60_000L // 90 minutes — cap how long a wait is worth suggesting
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private val prefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    private var stationSearchJob: Job? = null
    private var trainSearchJob: Job? = null
    private var destinationSearchJob: Job? = null
    private var stopCheckJob: Job? = null
    private val stopCheckSemaphore = Semaphore(6)

    init {
        _state.value = _state.value.copy(
            recentTrips = loadRecent(KEY_RECENT_TRIPS),
            recentTrains = loadRecent(KEY_RECENT_TRAINS)
        )
    }

    private inline fun <reified T> loadRecent(key: String): List<T> {
        val json = prefs.getString(key, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<T>>() {}.type
            gson.fromJson<List<T>>(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error loading $key", e)
            emptyList()
        }
    }

    private fun recordRecentTrip(origin: ViaggiaTrenoApi.StationSuggestion, destination: ViaggiaTrenoApi.StationSuggestion) {
        val updated = _state.value.recentTrips.toMutableList()
        updated.removeAll { it.origin.code == origin.code && it.destination.code == destination.code }
        updated.add(0, RecentTrip(origin, destination))
        val trimmed = updated.take(MAX_RECENT)
        _state.value = _state.value.copy(recentTrips = trimmed)
        prefs.edit().putString(KEY_RECENT_TRIPS, gson.toJson(trimmed)).apply()
    }

    private fun recordRecentTrain(train: ViaggiaTrenoApi.TrainSuggestion) {
        val updated = _state.value.recentTrains.toMutableList()
        updated.removeAll { it.number == train.number && it.originCode == train.originCode }
        updated.add(0, train)
        val trimmed = updated.take(MAX_RECENT)
        _state.value = _state.value.copy(recentTrains = trimmed)
        prefs.edit().putString(KEY_RECENT_TRAINS, gson.toJson(trimmed)).apply()
    }

    fun selectRecentTrip(trip: RecentTrip) {
        stopCheckJob?.cancel()
        _state.value = _state.value.copy(
            selectedStation = trip.origin,
            stationQuery = trip.origin.name,
            stationSuggestions = emptyList(),
            selectedDestination = trip.destination,
            destinationQuery = trip.destination.name,
            destinationSuggestions = emptyList(),
            showDetail = false,
            mode = "station",
            stopMatchedTrains = null,
            connectionInfo = emptyMap(),
            stopVerificationUnavailable = false
        )
        loadStationTrains()
        recordRecentTrip(trip.origin, trip.destination)
    }

    /** Swaps origin and destination — handy when you realize you picked the wrong direction. */
    fun swapStations() {
        val origin = _state.value.selectedStation ?: return
        val destination = _state.value.selectedDestination ?: return
        stopCheckJob?.cancel()
        _state.value = _state.value.copy(
            selectedStation = destination,
            stationQuery = destination.name,
            selectedDestination = origin,
            destinationQuery = origin.name,
            stationSuggestions = emptyList(),
            destinationSuggestions = emptyList(),
            stopMatchedTrains = null,
            connectionInfo = emptyMap(),
            stopVerificationUnavailable = false,
            showDetail = false
        )
        loadStationTrains()
        recordRecentTrip(destination, origin)
    }

    // --- Station ---
    fun onStationQueryChanged(query: String) {
        // Don't re-trigger search if user just selected a station (name matches)
        if (_state.value.selectedStation != null && query == _state.value.selectedStation!!.name) return
        _state.value = _state.value.copy(stationQuery = query, selectedStation = null, stationSuggestions = emptyList())
        stationSearchJob?.cancel()
        if (query.length < 2) return
        stationSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            if (_state.value.stationQuery != query) return@launch
            try {
                val results = ViaggiaTrenoApi.autocompleteStation(query)
                if (_state.value.stationQuery == query) {
                    _state.value = _state.value.copy(stationSuggestions = results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Station search error", e)
            }
        }
    }

    fun selectStation(suggestion: ViaggiaTrenoApi.StationSuggestion) {
        stopCheckJob?.cancel()
        _state.value = _state.value.copy(
            selectedStation = suggestion,
            stationQuery = suggestion.name,
            stationSuggestions = emptyList(),
            showDetail = false,
            mode = "station",
            destinationQuery = "",
            destinationSuggestions = emptyList(),
            selectedDestination = null,
            stopMatchedTrains = null,
            connectionInfo = emptyMap(),
            stopVerificationUnavailable = false,
            isCheckingStops = false
        )
        loadStationTrains()
    }

    fun clearStation() {
        stopCheckJob?.cancel()
        stationSearchJob?.cancel()
        _state.value = _state.value.copy(
            selectedStation = null, stationQuery = "", stationSuggestions = emptyList(),
            stationTrains = emptyList(), error = null,
            selectedDestination = null, destinationQuery = "", destinationSuggestions = emptyList(),
            stopMatchedTrains = null, connectionInfo = emptyMap(), stopVerificationUnavailable = false, isCheckingStops = false
        )
    }

    fun setFilter(filter: StationListFilter) {
        _state.value = _state.value.copy(filter = filter)
        if (_state.value.selectedStation != null) loadStationTrains()
    }

    fun setTimeOverride(date: Date?) {
        _state.value = _state.value.copy(timeOverride = date)
        if (_state.value.selectedStation != null) loadStationTrains()
    }

    // --- Optional destination filter ---
    fun onDestinationQueryChanged(query: String) {
        if (_state.value.selectedDestination != null && query == _state.value.selectedDestination!!.name) return
        _state.value = _state.value.copy(destinationQuery = query, selectedDestination = null, destinationSuggestions = emptyList())
        destinationSearchJob?.cancel()
        if (query.length < 2) return
        destinationSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(300)
            if (_state.value.destinationQuery != query) return@launch
            try {
                val results = ViaggiaTrenoApi.autocompleteStation(query)
                if (_state.value.destinationQuery == query) {
                    _state.value = _state.value.copy(destinationSuggestions = results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Destination search error", e)
            }
        }
    }

    fun selectDestination(suggestion: ViaggiaTrenoApi.StationSuggestion) {
        _state.value = _state.value.copy(
            selectedDestination = suggestion,
            destinationQuery = suggestion.name,
            destinationSuggestions = emptyList()
        )
        refreshStopCheck()
        _state.value.selectedStation?.let { recordRecentTrip(it, suggestion) }
    }

    fun clearDestination() {
        stopCheckJob?.cancel()
        _state.value = _state.value.copy(
            selectedDestination = null, destinationQuery = "", destinationSuggestions = emptyList(),
            stopMatchedTrains = null, connectionInfo = emptyMap(), stopVerificationUnavailable = false, isCheckingStops = false
        )
    }

    /** Verifies which currently-loaded trains can actually get you to the selected destination —
     *  either directly (fetching each candidate's full stop list, since a train's `fermate`
     *  covers its whole run and a stop only counts if it comes after our station in travel
     *  direction, or before it when we're the one arriving) — or with a single change of train,
     *  by cross-referencing against the board at the destination station. */
    private fun refreshStopCheck() {
        val dest = _state.value.selectedDestination ?: return
        val origin = _state.value.selectedStation ?: return
        val trains = _state.value.stationTrains
        val listFilter = _state.value.filter
        val time = _state.value.timeOverride
        stopCheckJob?.cancel()
        if (trains.isEmpty()) {
            _state.value = _state.value.copy(
                stopMatchedTrains = emptyList(), isCheckingStops = false,
                connectionInfo = emptyMap(), stopVerificationUnavailable = false
            )
            return
        }
        _state.value = _state.value.copy(
            isCheckingStops = true, stopMatchedTrains = null,
            connectionInfo = emptyMap(), stopVerificationUnavailable = false
        )
        stopCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val result = verifyCandidates(trains, origin, dest, listFilter, time)
            if (_state.value.selectedDestination == dest) {
                _state.value = _state.value.copy(
                    stopMatchedTrains = result.matched,
                    connectionInfo = result.connections,
                    isCheckingStops = false,
                    stopVerificationUnavailable = result.verificationUnavailable
                )
            }
        }
    }

    /** Same verification as [refreshStopCheck] but for a batch of newly paged-in trains, merging
     *  the result into what's already been verified instead of recomputing everything. */
    private fun appendStopCheck(newTrains: List<ViaggiaTrenoApi.StationTrain>) {
        val dest = _state.value.selectedDestination ?: return
        val origin = _state.value.selectedStation ?: return
        val listFilter = _state.value.filter
        val time = _state.value.timeOverride
        viewModelScope.launch(Dispatchers.IO) {
            val result = verifyCandidates(newTrains, origin, dest, listFilter, time)
            if (_state.value.selectedDestination == dest) {
                _state.value = _state.value.copy(
                    stopMatchedTrains = (_state.value.stopMatchedTrains ?: emptyList()) + result.matched,
                    connectionInfo = _state.value.connectionInfo + result.connections,
                    stopVerificationUnavailable = _state.value.stopVerificationUnavailable || result.verificationUnavailable
                )
            }
        }
    }

    private class VerifyResult(
        val matched: List<ViaggiaTrenoApi.StationTrain>,
        val connections: Map<Int, TrainConnection>,
        val verificationUnavailable: Boolean
    )

    private suspend fun verifyCandidates(
        candidates: List<ViaggiaTrenoApi.StationTrain>,
        origin: ViaggiaTrenoApi.StationSuggestion,
        dest: ViaggiaTrenoApi.StationSuggestion,
        listFilter: StationListFilter,
        time: Date?
    ): VerifyResult {
        // Trains whose listed terminus already matches need no detail fetch at all.
        val (quickMatched, needsDetail) = candidates.partition { train ->
            val terminus = if (listFilter == StationListFilter.DEPARTURES) train.destinazione else train.origine
            stationNamesMatch(terminus, dest.name)
        }
        val candidateStops = fetchStopsFor(needsDetail)
        val directNumbers = candidateStops.filter { (_, stops) -> stopIsReachable(stops, origin, dest, listFilter) }
            .map { it.first.numeroTreno }.toSet()
        val unresolved = candidateStops.filter { it.first.numeroTreno !in directNumbers }

        val matched = (quickMatched + candidateStops.filter { it.first.numeroTreno in directNumbers }.map { it.first }).toMutableList()
        val connections = mutableMapOf<Int, TrainConnection>()

        if (unresolved.isNotEmpty()) {
            val connectingBoard = try {
                if (listFilter == StationListFilter.DEPARTURES) ViaggiaTrenoApi.getArrivals(dest.code, time)
                else ViaggiaTrenoApi.getDepartures(dest.code, time)
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching connecting board at ${dest.name}", e)
                emptyList()
            }
            val connectingStops = fetchStopsFor(connectingBoard)
            for ((train, oStops) in unresolved) {
                val connection = findConnection(oStops, origin, dest, listFilter, connectingStops)
                if (connection != null) {
                    matched.add(train)
                    connections[train.numeroTreno] = connection
                }
            }
        }

        // ViaggiaTreno only activates live data for a train shortly before/during its run, so
        // when checking a future schedule (e.g. the "past time -> tomorrow" fallback) almost
        // everything 204s. A rare early activation shouldn't count as "verification worked" —
        // treat it as unavailable unless most candidates actually resolved.
        val verificationUnavailable = needsDetail.size >= 3 && candidateStops.size < needsDetail.size / 3
        return VerifyResult(matched, connections, verificationUnavailable)
    }

    /** Fetches full stop lists for a batch of trains concurrently, dropping any that fail. */
    private suspend fun fetchStopsFor(
        trains: List<ViaggiaTrenoApi.StationTrain>
    ): List<Pair<ViaggiaTrenoApi.StationTrain, List<ViaggiaTrenoApi.TrainStop>>> = coroutineScope {
        trains.map { train ->
            async {
                val stops = stopCheckSemaphore.withPermit {
                    try {
                        ViaggiaTrenoApi.getTrainDetail(train.codOrigine, train.numeroTreno, train.resolvedReferenceDay())?.fermate
                    } catch (e: Exception) {
                        Log.e(TAG, "Error fetching stops for train ${train.numeroTreno}", e)
                        null
                    }
                }
                train to stops
            }
        }.awaitAll().mapNotNull { (train, stops) -> if (stops.isNullOrEmpty()) null else train to stops }
    }

    private fun stopIsReachable(
        stops: List<ViaggiaTrenoApi.TrainStop>?,
        origin: ViaggiaTrenoApi.StationSuggestion,
        dest: ViaggiaTrenoApi.StationSuggestion,
        listFilter: StationListFilter
    ): Boolean {
        if (stops.isNullOrEmpty()) return false
        val originIdx = stops.indexOfFirst { it.id == origin.code || stationNamesMatch(it.stazione, origin.name) }
        val destIdx = stops.indexOfFirst { it.id == dest.code || stationNamesMatch(it.stazione, dest.name) }
        if (originIdx == -1 || destIdx == -1) return false
        return if (listFilter == StationListFilter.DEPARTURES) destIdx > originIdx else destIdx < originIdx
    }

    /** Looks for a single-change connection: a stop on [originStops] (after our station when
     *  departing, before it when arriving) that also appears — in the right order relative to
     *  the destination — on one of the [connectingStops] boarded at (or heading to) the
     *  destination station, with a plausible transfer gap between the two. */
    private fun findConnection(
        originStops: List<ViaggiaTrenoApi.TrainStop>,
        origin: ViaggiaTrenoApi.StationSuggestion,
        dest: ViaggiaTrenoApi.StationSuggestion,
        listFilter: StationListFilter,
        connectingStops: List<Pair<ViaggiaTrenoApi.StationTrain, List<ViaggiaTrenoApi.TrainStop>>>
    ): TrainConnection? {
        val originIdx = originStops.indexOfFirst { it.id == origin.code || stationNamesMatch(it.stazione, origin.name) }
        if (originIdx == -1) return null
        val candidateTransferStops = if (listFilter == StationListFilter.DEPARTURES) originStops.drop(originIdx + 1) else originStops.take(originIdx)

        for (transferStop in candidateTransferStops) {
            if (transferStop.id.isEmpty()) continue
            for ((cTrain, cStops) in connectingStops) {
                val matchIdx = cStops.indexOfFirst { it.id == transferStop.id }
                if (matchIdx == -1) continue
                val destIdx = cStops.indexOfFirst { it.id == dest.code || stationNamesMatch(it.stazione, dest.name) }
                if (destIdx == -1) continue
                val validOrder = if (listFilter == StationListFilter.DEPARTURES) destIdx > matchIdx else destIdx < matchIdx
                if (!validOrder) continue

                val arrival: Long
                val departure: Long
                if (listFilter == StationListFilter.DEPARTURES) {
                    arrival = effectiveArrival(transferStop)
                    departure = effectiveDeparture(cStops[matchIdx])
                } else {
                    departure = effectiveDeparture(transferStop)
                    arrival = effectiveArrival(cStops[matchIdx])
                }
                if (arrival <= 0 || departure <= 0) continue
                val waitMs = departure - arrival
                if (waitMs in MIN_TRANSFER_MS..MAX_TRANSFER_WAIT_MS) {
                    return TrainConnection(transferStop.stazione, arrival, departure, cTrain.categoriaDescrizione, cTrain.numeroTreno)
                }
            }
        }
        return null
    }

    private fun effectiveArrival(stop: ViaggiaTrenoApi.TrainStop): Long =
        stop.arrivoReale.takeIf { it > 0 } ?: stop.arrivo_teorico

    private fun effectiveDeparture(stop: ViaggiaTrenoApi.TrainStop): Long =
        stop.partenzaReale.takeIf { it > 0 } ?: stop.partenza_teorica

    fun loadStationTrains() {
        val station = _state.value.selectedStation ?: return
        val filter = _state.value.filter
        val time = _state.value.timeOverride
        _state.value = _state.value.copy(isLoading = true, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trains = if (filter == StationListFilter.DEPARTURES)
                    ViaggiaTrenoApi.getDepartures(station.code, time)
                else
                    ViaggiaTrenoApi.getArrivals(station.code, time)

                if (time != null && time.before(Date()) && trains.isEmpty()) {
                    val tomorrow = Date(time.time + 86400000)
                    val trainsTomorrow = if (filter == StationListFilter.DEPARTURES)
                        ViaggiaTrenoApi.getDepartures(station.code, tomorrow)
                    else
                        ViaggiaTrenoApi.getArrivals(station.code, tomorrow)
                    _state.value = _state.value.copy(
                        stationTrains = trainsTomorrow,
                        isLoading = false,
                        effectiveBoardDate = tomorrow,
                        error = if (trainsTomorrow.isEmpty()) "Nessun treno trovato" else "Orario nel passato — orari di domani"
                    )
                } else {
                    _state.value = _state.value.copy(
                        stationTrains = trains,
                        isLoading = false,
                        effectiveBoardDate = time ?: Date(),
                        error = if (trains.isEmpty()) "Nessun treno trovato" else null
                    )
                }
                if (_state.value.selectedDestination != null) refreshStopCheck()
            } catch (e: Exception) {
                Log.e(TAG, "Error loading station trains", e)
                _state.value = _state.value.copy(isLoading = false, error = "Errore di caricamento")
            }
        }
    }

    /** Loads the next batch of trains past the last one currently shown — called as the user
     *  scrolls near the end of the list, so later solutions (including connections) surface too. */
    fun loadMoreTrains() {
        val station = _state.value.selectedStation ?: return
        if (_state.value.isLoading || _state.value.isLoadingMore) return
        val trains = _state.value.stationTrains
        val last = trains.lastOrNull() ?: return
        val filter = _state.value.filter
        val lastTimeStr = if (filter == StationListFilter.DEPARTURES) last.compOrarioPartenza else last.compOrarioArrivo
        val referenceDate = _state.value.effectiveBoardDate ?: Date()
        val nextAnchor = nextAnchorFrom(lastTimeStr, referenceDate) ?: return

        _state.value = _state.value.copy(isLoadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val more = if (filter == StationListFilter.DEPARTURES)
                    ViaggiaTrenoApi.getDepartures(station.code, nextAnchor)
                else
                    ViaggiaTrenoApi.getArrivals(station.code, nextAnchor)
                val existingKeys = trains.map { it.numeroTreno to it.codOrigine }.toSet()
                val newOnes = more.filter { (it.numeroTreno to it.codOrigine) !in existingKeys }
                _state.value = _state.value.copy(stationTrains = trains + newOnes, isLoadingMore = false)
                if (_state.value.selectedDestination != null && newOnes.isNotEmpty()) {
                    appendStopCheck(newOnes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more trains", e)
                _state.value = _state.value.copy(isLoadingMore = false)
            }
        }
    }

    /** One minute past the last-shown train's time, on the same day it was fetched for. */
    private fun nextAnchorFrom(hhmm: String?, referenceDate: Date): Date? {
        if (hhmm.isNullOrBlank()) return null
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
        cal.time = referenceDate
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.add(Calendar.MINUTE, 1)
        return cal.time
    }

    // --- Train ---
    fun onTrainQueryChanged(query: String) {
        _state.value = _state.value.copy(trainQuery = query, trainSuggestions = emptyList())
        trainSearchJob?.cancel()
        if (query.isBlank()) return
        trainSearchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(500)
            if (_state.value.trainQuery != query) return@launch
            try {
                val results = ViaggiaTrenoApi.searchTrain(query)
                if (_state.value.trainQuery == query) {
                    _state.value = _state.value.copy(trainSuggestions = results)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Train search error", e)
            }
        }
    }

    fun selectTrain(suggestion: ViaggiaTrenoApi.TrainSuggestion) {
        _state.value = _state.value.copy(trainSuggestions = emptyList(), isLoading = true, showDetail = false, mode = "train")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val num = suggestion.number.toIntOrNull() ?: return@launch
                val referenceDay = suggestion.resolvedReferenceDay()
                val detail = ViaggiaTrenoApi.getTrainDetail(suggestion.originCode, num, referenceDay)
                _state.value = _state.value.copy(
                    trainDetail = detail, isLoading = false, showDetail = true,
                    currentTrainOriginCode = suggestion.originCode, currentTrainNumber = num,
                    currentTrainReferenceDay = referenceDay
                )
                recordRecentTrain(suggestion)
            } catch (e: Exception) {
                Log.e(TAG, "Error loading train detail", e)
                _state.value = _state.value.copy(isLoading = false, error = "Errore di caricamento")
            }
        }
    }

    fun loadTrainDetail(originCode: String, trainNumber: Int, referenceDay: Long) {
        val effectiveDay = referenceDay.takeIf { it > 0 } ?: ViaggiaTrenoApi.todayMidnightTs()
        _state.value = _state.value.copy(isLoading = true, showDetail = false, mode = "station")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detail = ViaggiaTrenoApi.getTrainDetail(originCode, trainNumber, effectiveDay)
                _state.value = _state.value.copy(
                    trainDetail = detail, showDetail = true, isLoading = false,
                    currentTrainOriginCode = originCode, currentTrainNumber = trainNumber,
                    currentTrainReferenceDay = effectiveDay
                )
            } catch (e: Exception) {
                Log.e(TAG, "Error loading train detail", e)
                _state.value = _state.value.copy(isLoading = false, error = "Errore di caricamento")
            }
        }
    }

    /** Re-fetches the currently displayed train's progress without leaving the detail screen. */
    fun refreshTrainDetail() {
        val originCode = _state.value.currentTrainOriginCode ?: return
        val trainNumber = _state.value.currentTrainNumber ?: return
        val referenceDay = _state.value.currentTrainReferenceDay
        _state.value = _state.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val detail = ViaggiaTrenoApi.getTrainDetail(originCode, trainNumber, referenceDay)
                _state.value = _state.value.copy(trainDetail = detail, isLoading = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing train detail", e)
                _state.value = _state.value.copy(isLoading = false, error = "Errore di caricamento")
            }
        }
    }

    /** Called when a station name is clicked in the train detail stop list */
    fun onStationClicked(code: String, name: String) {
        val suggestion = ViaggiaTrenoApi.StationSuggestion(name = name, code = code)
        _state.value = _state.value.copy(
            selectedStation = suggestion,
            stationQuery = name,
            stationSuggestions = emptyList(),
            showDetail = false,
            mode = "station",
            filter = StationListFilter.DEPARTURES
        )
        loadStationTrains()
    }

    fun goBack() {
        _state.value = _state.value.copy(showDetail = false, trainDetail = null)
    }

    fun switchToTrainMode() {
        _state.value = _state.value.copy(mode = "train", trainQuery = "", trainSuggestions = emptyList(), showDetail = false, stationSuggestions = emptyList())
    }

    fun switchToStationMode() {
        _state.value = _state.value.copy(mode = "station", trainQuery = "", trainSuggestions = emptyList(), showDetail = false)
    }

    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }
}