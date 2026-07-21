package it.trenirt.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

object ViaggiaTrenoApi {
    private const val TAG = "TreniRT"
    private const val BASE = "http://www.viaggiatreno.it/infomobilita/resteasy/viaggiatreno"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    // Lenient Gson that won't crash on unexpected types
    private val gson: Gson = GsonBuilder()
        .setLenient()
        .serializeNulls()
        .create()

    /** Throws on network failure so callers can tell "request failed" apart from "no data". */
    private fun get(path: String): String? {
        val url = "$BASE/$path"
        val req = Request.Builder().url(url).build()
        val resp = client.newCall(req).execute()
        return if (resp.code == 204) null else resp.body?.string()
    }

    /** Same as [get] but swallows network errors — for autocomplete calls where a transient failure should just yield no suggestions. */
    private fun getOrNull(path: String): String? = try {
        get(path)
    } catch (e: Exception) {
        Log.e(TAG, "Network error for $BASE/$path", e)
        null
    }

    // --- Station autocomplete (pipe-separated format) ---
    data class StationSuggestion(val name: String, val code: String)

    fun autocompleteStation(query: String): List<StationSuggestion> {
        val body = getOrNull("autocompletaStazione/${enc(query)}") ?: return emptyList()
        return try {
            body.trim().lines().filter { it.contains("|") && it.split("|").size >= 2 }.map { line ->
                val parts = line.split("|")
                StationSuggestion(name = parts[0].trim(), code = parts[1].trim())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing station autocomplete", e)
            emptyList()
        }
    }

    // --- Train search (pipe-separated) ---
    // referenceDay is the midnight timestamp ViaggiaTreno associates with this specific run —
    // needed by andamentoTreno since the same train number recurs daily.
    data class TrainSuggestion(val number: String, val originCode: String, val originName: String, val referenceDay: Long = 0L)

    fun searchTrain(number: String): List<TrainSuggestion> {
        val body = getOrNull("cercaNumeroTrenoTrenoAutocomplete/${enc(number)}") ?: return emptyList()
        return try {
            body.trim().lines().filter { it.contains("|") && it.split("|").size >= 2 }.map { line ->
                val parts = line.split("|")
                val label = parts[0].trim()
                val meta = parts[1]
                val metaParts = meta.split("-")
                val trainNum = metaParts.getOrNull(0)?.trim() ?: ""
                val originCode = metaParts.getOrNull(1)?.trim() ?: ""
                val referenceDay = metaParts.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                TrainSuggestion(number = trainNum, originCode = originCode, originName = label, referenceDay = referenceDay)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing train search", e)
            emptyList()
        }
    }

    // --- Departures / Arrivals ---
    data class StationTrain(
        val numeroTreno: Int = 0,
        val categoriaDescrizione: String = "",
        val destinazione: String? = null,
        val origine: String? = null,
        val codOrigine: String = "",
        val ritardo: Int = 0,
        val provvedimento: Int = 0,
        val compOrarioPartenza: String? = null,
        val compOrarioArrivo: String? = null,
        val compOrarioPartenzaZeroEffettivo: String? = null,
        val compOrarioArrivoZeroEffettivo: String? = null,
        val binarioEffettivoPartenzaDescrizione: String? = null,
        val binarioEffettivoArrivoDescrizione: String? = null,
        val binarioProgrammatoPartenzaDescrizione: String? = null,
        val binarioProgrammatoArrivoDescrizione: String? = null,
        val inStazione: Boolean = false,
        val nonPartito: Boolean = false,
        val compNumeroTreno: String = "",
        val dataPartenzaTreno: Long = 0,
        val riprogrammazione: String = "N"
    )

    private fun formatVtTime(date: Date): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("Europe/Rome")
        return sdf.format(date)
    }

    // Note: parsing failures are intentionally NOT caught here — they propagate to the
    // ViewModel so a malformed/unexpected response surfaces as a real error instead of
    // being indistinguishable from a legitimate "no trains" empty list.
    fun getDepartures(stationCode: String, time: Date? = null): List<StationTrain> {
        val t = formatVtTime(time ?: Date())
        val body = get("partenze/$stationCode/${enc(t)}") ?: return emptyList()
        val type = object : TypeToken<List<StationTrain>>() {}.type
        return gson.fromJson<List<StationTrain>>(body, type) ?: emptyList()
    }

    fun getArrivals(stationCode: String, time: Date? = null): List<StationTrain> {
        val t = formatVtTime(time ?: Date())
        val body = get("arrivi/$stationCode/${enc(t)}") ?: return emptyList()
        val type = object : TypeToken<List<StationTrain>>() {}.type
        return gson.fromJson<List<StationTrain>>(body, type) ?: emptyList()
    }

    // --- Train detail ---
    data class TrainDetail(
        val numeroTreno: Int = 0,
        val categoria: String = "",
        val origine: String = "",
        val destinazione: String = "",
        val ritardo: Int = 0,
        val tipoTreno: String = "",
        val provvedimento: Int = 0,
        val stazioneUltimoRilevamento: String = "",
        val oraUltimoRilevamento: Long = 0,
        val fermate: List<TrainStop> = emptyList(),
        val compOrarioPartenza: String = "",
        val compOrarioArrivo: String = "",
        val nonPartito: Boolean = false
    )

    data class TrainStop(
        val stazione: String = "",
        val id: String = "",
        val tipoFermata: String = "F",
        val ritardo: Int = 0,
        val ritardoPartenza: Int = 0,
        val ritardoArrivo: Int = 0,
        val partenza_teorica: Long = 0,
        val arrivo_teorico: Long = 0,
        val partenzaReale: Long = 0,
        val arrivoReale: Long = 0,
        val actualFermataType: Int = 1,
        val binarioEffettivoPartenzaDescrizione: String? = null,
        val binarioEffettivoArrivoDescrizione: String? = null,
        val binarioProgrammatoPartenzaDescrizione: String? = null,
        val binarioProgrammatoArrivoDescrizione: String? = null
    )

    /** [referenceDay] must be the midnight (ms) of the specific day this train instance runs —
     *  the same train number recurs daily, so andamentoTreno needs it to disambiguate which
     *  run's live data to return. Defaults to today for calls that have no better information. */
    fun getTrainDetail(originCode: String, trainNumber: Int, referenceDay: Long = todayMidnightTs()): TrainDetail? {
        val body = get("andamentoTreno/$originCode/$trainNumber/$referenceDay") ?: return null
        return gson.fromJson(body, TrainDetail::class.java)
    }

    // --- Helpers ---
    // URLEncoder targets query-string/form encoding and turns spaces into '+', which is only
    // meaningful in that context — here the encoded value goes into a URL *path* segment, where
    // a literal '+' is NOT decoded back to a space by the server. Fix up to proper %20 escaping.
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun todayMidnightTs(): Long {
        val cal = java.util.Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}