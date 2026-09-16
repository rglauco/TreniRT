// SPDX-License-Identifier: AGPL-3.0-or-later
package it.trenirt.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.annotations.SerializedName
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Client per l'endpoint non ufficiale che alimenta la pagina "Italo in Viaggio" del sito
 *  Italo (NTV). A differenza di ViaggiaTreno, Italo NON è tracciato lì: i suoi treni non
 *  compaiono né nell'autocomplete per numero né in nessun tabellone stazione — è un
 *  operatore "open access" fuori dal giro Trenitalia/RFI che ViaggiaTreno copre.
 *  Endpoint non documentato, senza garanzie di stabilità: può cambiare o smettere di
 *  funzionare senza preavviso. Usato solo come fallback quando ViaggiaTreno non trova nulla
 *  per il numero cercato. */
object ItaloApi {
    private const val TAG = "TreniRT"
    private const val BASE = "https://italoinviaggio.italotreno.com/api/RicercaTrenoService"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val gson: Gson = GsonBuilder().setLenient().create()

    data class Stop(
        val LocationCode: String = "",
        val LocationDescription: String = "",
        val EstimatedDepartureTime: String? = null,
        val ActualDepartureTime: String? = null,
        val EstimatedArrivalTime: String? = null,
        val ActualArrivalTime: String? = null,
        val ActualArrivalPlatform: String? = null
    )

    data class Disruption(
        val DelayAmount: Int = 0,
        val RunningState: Int = 0
    )

    data class TrainSchedule(
        val TrainNumber: String = "",
        val DepartureDate: String? = null,
        val DepartureStationDescription: String = "",
        val ArrivalDate: String? = null,
        val ArrivalStationDescription: String = "",
        @SerializedName("Distruption") val disruption: Disruption? = null,
        val StazionePartenza: Stop? = null,
        val StazioniFerme: List<Stop> = emptyList(),
        val StazioniNonFerme: List<Stop> = emptyList()
    )

    data class SearchResponse(
        val IsEmpty: Boolean = true,
        val TrainSchedule: TrainSchedule? = null
    )

    /** Returns null both on network failure and when Italo has no data for [trainNumber]
     *  (not running today, wrong number, etc.) — callers can't and don't need to tell those
     *  apart, same convention as [ViaggiaTrenoApi.getTrainDetail]. */
    fun searchTrain(trainNumber: String): TrainSchedule? = try {
        val req = Request.Builder().url("$BASE?TrainNumber=${trainNumber.trim()}").build()
        val body = client.newCall(req).execute().use { it.body?.string() }
        val parsed = body?.let { gson.fromJson(it, SearchResponse::class.java) }
        if (parsed == null || parsed.IsEmpty) null else parsed.TrainSchedule
    } catch (e: Exception) {
        Log.e(TAG, "Network error searching Italo train $trainNumber", e)
        null
    }

    // "01:00" è il segnaposto usato da questa API per "non applicabile" (es. l'arrivo alla
    // stazione di partenza, o la partenza dalla stazione di arrivo) — va trattato come assente,
    // non come le 01:00 di notte.
    private fun parseTimeToday(hhmm: String?): Long {
        if (hhmm.isNullOrBlank() || hhmm == "01:00") return 0L
        val parts = hhmm.split(":")
        if (parts.size != 2) return 0L
        val h = parts[0].toIntOrNull() ?: return 0L
        val m = parts[1].toIntOrNull() ?: return 0L
        val cal = Calendar.getInstance(TimeZone.getTimeZone("Europe/Rome"))
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, m)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    // Italo non espone un ritardo per singola fermata: va derivato dalla differenza fra orario
    // stimato e reale, altrimenti StopRow (che senza un valore esplicito assume "in orario" per
    // le fermate già transitate) colorerebbe di verde anche un arrivo/partenza reale in ritardo.
    private fun delayMinutes(estimatedMillis: Long, actualMillis: Long): Int =
        if (estimatedMillis > 0 && actualMillis > 0) ((actualMillis - estimatedMillis) / 60_000L).toInt() else 0

    private fun Stop.toTrainStop(tipoFermata: String): ViaggiaTrenoApi.TrainStop {
        val partenzaTeorica = parseTimeToday(EstimatedDepartureTime)
        val arrivoTeorico = parseTimeToday(EstimatedArrivalTime)
        val partenzaReale = parseTimeToday(ActualDepartureTime)
        val arrivoReale = parseTimeToday(ActualArrivalTime)
        return ViaggiaTrenoApi.TrainStop(
            stazione = LocationDescription,
            id = LocationCode,
            tipoFermata = tipoFermata,
            partenza_teorica = partenzaTeorica,
            arrivo_teorico = arrivoTeorico,
            partenzaReale = partenzaReale,
            arrivoReale = arrivoReale,
            ritardoPartenza = delayMinutes(partenzaTeorica, partenzaReale),
            ritardoArrivo = delayMinutes(arrivoTeorico, arrivoReale),
            binarioEffettivoArrivoDescrizione = ActualArrivalPlatform,
            binarioEffettivoPartenzaDescrizione = ActualArrivalPlatform
        )
    }

    /** Riusa il modello dati di ViaggiaTreno (TrainDetail/TrainStop) così la UI esistente
     *  (TrainDetailScreen, StopRow) funziona identica senza saper nulla di questa fonte —
     *  [ViaggiaTrenoApi.TrainDetail.operatore] la marca esplicitamente come "Italo" per i
     *  punti che vogliono segnalare che il dato non viene da ViaggiaTreno. */
    fun TrainSchedule.toTrainDetail(): ViaggiaTrenoApi.TrainDetail {
        val origin = StazionePartenza?.toTrainStop("P")
        val stops = (origin?.let { listOf(it) } ?: emptyList()) +
            StazioniFerme.map { it.toTrainStop("F") } +
            StazioniNonFerme.mapIndexed { i, s -> s.toTrainStop(if (i == StazioniNonFerme.lastIndex) "A" else "F") }

        // "Ultima rilevazione" = l'ultima fermata di cui abbiamo un arrivo reale, stesso
        // significato che ha stazioneUltimoRilevamento per ViaggiaTreno.
        val lastArrived = stops.lastOrNull { it.arrivoReale > 0 }

        return ViaggiaTrenoApi.TrainDetail(
            numeroTreno = TrainNumber.toIntOrNull() ?: 0,
            categoria = "ITA",
            origine = DepartureStationDescription,
            destinazione = ArrivalStationDescription,
            ritardo = disruption?.DelayAmount ?: 0,
            stazioneUltimoRilevamento = lastArrived?.stazione ?: "",
            oraUltimoRilevamento = lastArrived?.arrivoReale ?: 0L,
            fermate = stops,
            compOrarioPartenza = DepartureDate ?: "",
            compOrarioArrivo = ArrivalDate ?: "",
            nonPartito = disruption?.RunningState == 0,
            operatore = "Italo"
        )
    }
}
