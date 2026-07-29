// SPDX-License-Identifier: AGPL-3.0-or-later
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
 *  then continue on [connectingCategory] [connectingNumber]. [finalTime] is the connecting
 *  train's own time at the filter-destination station — its arrival there when browsing
 *  departures, its departure from there when browsing arrivals (the far end of that leg). */
data class TrainConnection(
    val transferStationName: String,
    val transferArrivalTime: Long,
    val transferDepartureTime: Long,
    val connectingCategory: String,
    val connectingNumber: Int,
    val finalTime: Long
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
    // For directly-matched entries in stopMatchedTrains (no change of train): the time at the
    // destination-filter station, keyed by numeroTreno — see VerifyResult.destinationTimes.
    val destinationTimes: Map<Int, Long> = emptyMap(),
    // True when we couldn't fetch stop data for any candidate (ViaggiaTreno has no live data
    // for trains that far in the future — typically a schedule for a following day). In that
    // case we fall back to showing the raw, unverified board instead of an empty list.
    val stopVerificationUnavailable: Boolean = false,
    val isLoadingMore: Boolean = false,
    // True once a loadMoreTrains() call comes back with nothing new — stops the UI from
    // re-triggering pagination forever once the schedule for the day is exhausted (or, with a
    // very short list, immediately — see loadMoreTrains()).
    val noMoreTrainsToLoad: Boolean = false,
    // Most-recently-used origin/destination pairs, newest first
    val recentTrips: List<RecentTrip> = emptyList(),
    // Most-recently-searched train numbers, newest first
    val recentTrains: List<ViaggiaTrenoApi.TrainSuggestion> = emptyList(),
    // Identifies the train currently shown in the detail screen, so it can be refreshed
    val currentTrainOriginCode: String? = null,
    val currentTrainNumber: Int? = null,
    val currentTrainReferenceDay: Long = 0L,
    val isDarkTheme: Boolean = true,
    val showHelp: Boolean = false
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
        private const val KEY_DARK_THEME = "dark_theme"
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

    // Identifies which (station, filter, day) the current stationTrains list accumulates for —
    // see mergeBoard().
    private var boardCacheKey: String? = null

    init {
        _state.value = _state.value.copy(
            recentTrips = loadRecent(KEY_RECENT_TRIPS),
            recentTrains = loadRecent(KEY_RECENT_TRAINS),
            isDarkTheme = prefs.getBoolean(KEY_DARK_THEME, true)
        )
    }

    fun toggleTheme() {
        val newValue = !_state.value.isDarkTheme
        _state.value = _state.value.copy(isDarkTheme = newValue)
        prefs.edit().putBoolean(KEY_DARK_THEME, newValue).apply()
    }

    fun showHelp() {
        _state.value = _state.value.copy(showHelp = true)
    }

    fun hideHelp() {
        _state.value = _state.value.copy(showHelp = false)
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
            destinationTimes = emptyMap(),
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
            destinationTimes = emptyMap(),
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
            destinationTimes = emptyMap(),
            stopVerificationUnavailable = false,
            isCheckingStops = false
        )
        loadStationTrains()
    }

    fun clearStation() {
        stopCheckJob?.cancel()
        stationSearchJob?.cancel()
        boardCacheKey = null
        _state.value = _state.value.copy(
            selectedStation = null, stationQuery = "", stationSuggestions = emptyList(),
            stationTrains = emptyList(), error = null,
            selectedDestination = null, destinationQuery = "", destinationSuggestions = emptyList(),
            stopMatchedTrains = null, connectionInfo = emptyMap(), destinationTimes = emptyMap(),
            stopVerificationUnavailable = false, isCheckingStops = false
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
            stopMatchedTrains = null, connectionInfo = emptyMap(), destinationTimes = emptyMap(),
            stopVerificationUnavailable = false, isCheckingStops = false
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
                connectionInfo = emptyMap(), destinationTimes = emptyMap(), stopVerificationUnavailable = false
            )
            return
        }
        _state.value = _state.value.copy(
            isCheckingStops = true, stopMatchedTrains = null,
            connectionInfo = emptyMap(), destinationTimes = emptyMap(), stopVerificationUnavailable = false
        )
        stopCheckJob = viewModelScope.launch(Dispatchers.IO) {
            val result = verifyCandidates(trains, origin, dest, listFilter, time)
            if (_state.value.selectedDestination == dest) {
                _state.value = _state.value.copy(
                    stopMatchedTrains = result.matched,
                    connectionInfo = result.connections,
                    destinationTimes = result.destinationTimes,
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
                    destinationTimes = _state.value.destinationTimes + result.destinationTimes,
                    stopVerificationUnavailable = _state.value.stopVerificationUnavailable || result.verificationUnavailable
                )
            }
        }
    }

    private class VerifyResult(
        val matched: List<ViaggiaTrenoApi.StationTrain>,
        val connections: Map<Int, TrainConnection>,
        // For directly-matched trains (not connections): the time at the destination-filter
        // station — its arrival there when browsing departures, its departure from there when
        // browsing arrivals (the other, not-yet-known end of the segment). Keyed by numeroTreno.
        val destinationTimes: Map<Int, Long>,
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

        // Quick-matched trains skipped the detail fetch above, but pinning down the exact time at
        // the destination station always needs the stop list — fetch it now for just this (small,
        // already-filtered) subset instead of the whole candidate list.
        val stopsByTrain = candidateStops.associate { (train, stops) -> train.numeroTreno to stops }
        val quickMatchedStops = fetchStopsFor(quickMatched.filter { it.numeroTreno !in stopsByTrain })
            .associate { (train, stops) -> train.numeroTreno to stops }
        val allStopsByTrain = stopsByTrain + quickMatchedStops
        val destinationTimes = mutableMapOf<Int, Long>()
        for (num in quickMatched.map { it.numeroTreno } + directNumbers) {
            val stops = allStopsByTrain[num] ?: continue
            val originIdx = stops.indexOfFirst { it.id == origin.code || stationNamesMatch(it.stazione, origin.name) }
            val destIdx = stops.indexOfFirst { it.id == dest.code || stationNamesMatch(it.stazione, dest.name) }
            if (originIdx == -1 || destIdx == -1) continue
            val effectiveTime = if (listFilter == StationListFilter.DEPARTURES) effectiveArrival(stops[destIdx]) else effectiveDeparture(stops[destIdx])
            if (effectiveTime > 0) destinationTimes[num] = effectiveTime
        }

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
        return VerifyResult(matched, connections, destinationTimes, verificationUnavailable)
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

                // Reject connections whose continuing train backtracks through the station we
                // started from — pointless (and a sign the transfer point is in the wrong
                // direction entirely) if it has to pass back through the origin to reach dest.
                val legRange = if (listFilter == StationListFilter.DEPARTURES) (matchIdx + 1) until destIdx else (destIdx + 1) until matchIdx
                val backtracksThroughOrigin = legRange.any { i -> cStops[i].id == origin.code || stationNamesMatch(cStops[i].stazione, origin.name) }
                if (backtracksThroughOrigin) continue

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
                    val finalTime = if (listFilter == StationListFilter.DEPARTURES) effectiveArrival(cStops[destIdx]) else effectiveDeparture(cStops[destIdx])
                    if (finalTime > 0) {
                        return TrainConnection(transferStop.stazione, arrival, departure, cTrain.categoriaDescrizione, cTrain.numeroTreno, finalTime)
                    }
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
        _state.value = _state.value.copy(isLoading = true, error = null, noMoreTrainsToLoad = false)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val boardDate = time ?: Date()
                val trains = if (filter == StationListFilter.DEPARTURES)
                    ViaggiaTrenoApi.getDepartures(station.code, time)
                else
                    ViaggiaTrenoApi.getArrivals(station.code, time)
                // Merge BEFORE deciding whether to fall back to tomorrow: ViaggiaTreno's live
                // board always reflects a window around actual real time regardless of the
                // timestamp queried, so a past "today" request commonly comes back empty even
                // when we already have (still-relevant, not yet evicted) trains cached from an
                // earlier fetch this session — e.g. a train that has since departed. Falling back
                // to tomorrow in that case would discard those in favor of an unrelated schedule.
                val merged = mergeBoard(trains, station.code, filter, boardDate)

                if (merged.isEmpty() && time != null && time.before(Date())) {
                    // Nothing fresh and nothing usable cached for today — the chosen past clock
                    // time is most likely meant as "the next time this happens", i.e. tomorrow.
                    val tomorrow = Date(time.time + 86400000)
                    val trainsTomorrow = if (filter == StationListFilter.DEPARTURES)
                        ViaggiaTrenoApi.getDepartures(station.code, tomorrow)
                    else
                        ViaggiaTrenoApi.getArrivals(station.code, tomorrow)
                    val mergedTomorrow = mergeBoard(trainsTomorrow, station.code, filter, tomorrow)
                    _state.value = _state.value.copy(
                        stationTrains = mergedTomorrow,
                        isLoading = false,
                        effectiveBoardDate = tomorrow,
                        error = if (mergedTomorrow.isEmpty()) "Nessun treno trovato" else "Orario nel passato — orari di domani"
                    )
                } else {
                    _state.value = _state.value.copy(
                        stationTrains = merged,
                        isLoading = false,
                        effectiveBoardDate = boardDate,
                        error = if (merged.isEmpty()) "Nessun treno trovato" else null
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
     *  scrolls near the end of the list, so later solutions (including connections) surface too.
     *  Guarded by [UiState.noMoreTrainsToLoad]: the UI's scroll-triggered auto-load condition
     *  ("near the bottom") is trivially satisfied forever by a short list (e.g. a 1-item fallback
     *  board), so without this the same fetch would re-fire in a tight loop. Setting the flag the
     *  first time a fetch yields nothing new — whether because the list was already exhaustive or
     *  the day's schedule is genuinely exhausted — stops further attempts until the next fresh
     *  [loadStationTrains] call (station/filter/time change or manual refresh) clears it. */
    fun loadMoreTrains() {
        val station = _state.value.selectedStation ?: return
        if (_state.value.isLoading || _state.value.isLoadingMore || _state.value.noMoreTrainsToLoad) return
        val trains = _state.value.stationTrains
        val last = trains.lastOrNull() ?: return
        val filter = _state.value.filter
        val lastTimeStr = if (filter == StationListFilter.DEPARTURES) last.compOrarioPartenza else last.compOrarioArrivo
        val referenceDate = _state.value.effectiveBoardDate ?: Date()
        val nextAnchor = nextAnchorFrom(lastTimeStr, referenceDate)
        if (nextAnchor == null) {
            _state.value = _state.value.copy(noMoreTrainsToLoad = true)
            return
        }

        _state.value = _state.value.copy(isLoadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val more = if (filter == StationListFilter.DEPARTURES)
                    ViaggiaTrenoApi.getDepartures(station.code, nextAnchor)
                else
                    ViaggiaTrenoApi.getArrivals(station.code, nextAnchor)
                val existingKeys = trains.map { it.numeroTreno to it.codOrigine }.toSet()
                val merged = mergeBoard(more, station.code, filter, referenceDate)
                val newOnes = merged.filter { (it.numeroTreno to it.codOrigine) !in existingKeys }
                _state.value = _state.value.copy(
                    stationTrains = merged,
                    isLoadingMore = false,
                    noMoreTrainsToLoad = newOnes.isEmpty()
                )
                if (_state.value.selectedDestination != null && newOnes.isNotEmpty()) {
                    appendStopCheck(newOnes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading more trains", e)
                _state.value = _state.value.copy(isLoadingMore = false)
            }
        }
    }

    private fun dayKeyFor(date: Date): String {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
        cal.time = date
        return "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.DAY_OF_YEAR)}"
    }

    private fun minutesOfDay(hhmm: String?): Int? {
        if (hhmm.isNullOrBlank()) return null
        val parts = hhmm.split(":")
        if (parts.size != 2) return null
        val h = parts[0].toIntOrNull() ?: return null
        val m = parts[1].toIntOrNull() ?: return null
        return h * 60 + m
    }

    /** ViaggiaTreno's live board only shows a rolling window around the actual current real-world
     *  time and drops a train the moment it departs — there is no way to ask it for history, even
     *  by passing a past timestamp (past a certain grace period it just returns empty). To avoid
     *  already-departed trains vanishing on the next refresh, accumulate results across fetches
     *  for the same (station, filter, day) instead of replacing the list outright. Entries more
     *  than 3 hours stale are pruned so the list doesn't grow forever — but only when the board is
     *  for *today*, since that "stale relative to actual now" concept doesn't apply to a future
     *  day's schedule (e.g. the "past time -> tomorrow" fallback, or scrolling far ahead). */
    private fun mergeBoard(
        fresh: List<ViaggiaTrenoApi.StationTrain>,
        stationCode: String,
        filter: StationListFilter,
        boardDate: Date
    ): List<ViaggiaTrenoApi.StationTrain> {
        val key = "$stationCode|$filter|${dayKeyFor(boardDate)}"
        val previous = if (boardCacheKey == key) _state.value.stationTrains else emptyList()
        boardCacheKey = key

        val merged = LinkedHashMap<Pair<Int, String>, ViaggiaTrenoApi.StationTrain>()
        for (t in previous) merged[t.numeroTreno to t.codOrigine] = t
        for (t in fresh) merged[t.numeroTreno to t.codOrigine] = t // fresh data wins on conflict

        val isToday = dayKeyFor(boardDate) == dayKeyFor(Date())
        val values = if (isToday) {
            val nowCal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
            val nowMin = nowCal.get(Calendar.HOUR_OF_DAY) * 60 + nowCal.get(Calendar.MINUTE)
            merged.values.filter { t ->
                val sched = minutesOfDay(if (filter == StationListFilter.DEPARTURES) t.compOrarioPartenza else t.compOrarioArrivo)
                sched == null || sched >= nowMin - 180
            }
        } else {
            merged.values
        }
        return values.sortedBy { t ->
            minutesOfDay(if (filter == StationListFilter.DEPARTURES) t.compOrarioPartenza else t.compOrarioArrivo) ?: Int.MAX_VALUE
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

    private suspend fun showTrainDetail(originCode: String, number: Int, referenceDay: Long, recordAs: ViaggiaTrenoApi.TrainSuggestion) {
        try {
            val detail = ViaggiaTrenoApi.getTrainDetail(originCode, number, referenceDay)
            _state.value = _state.value.copy(
                trainDetail = detail, isLoading = false, showDetail = true,
                currentTrainOriginCode = originCode, currentTrainNumber = number,
                currentTrainReferenceDay = referenceDay
            )
            recordRecentTrain(recordAs)
        } catch (e: Exception) {
            Log.e(TAG, "Error loading train detail", e)
            _state.value = _state.value.copy(isLoading = false, error = "Errore di caricamento")
        }
    }

    fun selectTrain(suggestion: ViaggiaTrenoApi.TrainSuggestion) {
        _state.value = _state.value.copy(trainSuggestions = emptyList(), isLoading = true, showDetail = false, mode = "train")
        viewModelScope.launch(Dispatchers.IO) {
            val num = suggestion.number.toIntOrNull() ?: return@launch
            showTrainDetail(suggestion.originCode, num, suggestion.resolvedReferenceDay(), suggestion)
        }
    }

    /** Recent-train entries can be days old: the saved day is no longer meaningful, so re-run a
     *  live search for the number instead of trusting it — same as if the user just typed it. */
    fun selectRecentTrain(suggestion: ViaggiaTrenoApi.TrainSuggestion) {
        _state.value = _state.value.copy(isLoading = true, showDetail = false, mode = "train")
        viewModelScope.launch(Dispatchers.IO) {
            val fresh = try {
                ViaggiaTrenoApi.searchTrain(suggestion.number)
                    .let { results -> results.firstOrNull { it.originCode == suggestion.originCode } ?: results.firstOrNull() }
            } catch (e: Exception) {
                Log.e(TAG, "Error refreshing recent train", e)
                null
            }
            val num = fresh?.number?.toIntOrNull()
            if (fresh == null || num == null) {
                _state.value = _state.value.copy(isLoading = false, error = "Treno ${suggestion.number} non trovato per oggi")
                return@launch
            }
            showTrainDetail(fresh.originCode, num, fresh.resolvedReferenceDay(), fresh)
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