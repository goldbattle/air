package com.aria.air.data

// One aircraft by its tail number: what it is, how old, and how its recent flights ran.

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import org.json.JSONArray
import org.json.JSONObject

/** Facts about one airframe, from AeroDataBox. */
data class TailInfo(
    val model: String = "", val type: String = "", val airline: String = "", val ageYears: Double? = null,
    val delivered: LocalDate? = null, val firstFlight: LocalDate? = null, val seats: Int? = null,
    val engines: String = "", val hex: String = "", val serial: String = "",
)

/** One recent flight of the aircraft, with its schedule and what really happened. */
data class TailLeg(
    val flight: String, val from: String, val to: String,
    val depSched: Instant? = null, val depActual: Instant? = null,
    val arrSched: Instant? = null, val arrActual: Instant? = null, val cancelled: Boolean = false,
) {
    /** Minutes late (+) or early (-) at arrival, null until it has landed. */
    val arrDelay: Long? get() = arrSched?.let { s -> arrActual?.let { Duration.between(s, it).toMinutes() } }

    /** Minutes late (+) or early (-) leaving, null until it has left. */
    val depDelay: Long? get() = depSched?.let { s -> depActual?.let { Duration.between(s, it).toMinutes() } }
}

/** What we know about [reg]: the airframe, its last week of flights (newest first), from which service and when. */
data class TailReport(val reg: String, val info: TailInfo?, val legs: List<TailLeg>, val at: Instant, val source: String) {
    /** Legs that have landed, so their punctuality is known. */
    val landed get() = legs.filter { it.arrDelay != null }

    /** Landed within 15 minutes of schedule, the airlines' own definition of on time. */
    val onTime get() = landed.count { it.arrDelay!! < 15 }

    /** Average arrival delay over the landed legs, early ones counting as zero; null with none landed. */
    val averageDelay: Long? get() = landed.takeIf { it.isNotEmpty() }?.let { l -> l.sumOf { maxOf(0L, it.arrDelay!!) } / l.size }

    fun toJson(): JSONObject = JSONObject().put("reg", reg).put("at", at.epochSecond).put("source", source).apply {
        info?.let { i ->
            put("info", JSONObject().put("model", i.model).put("type", i.type).put("airline", i.airline)
                .put("age", i.ageYears ?: JSONObject.NULL).put("delivered", i.delivered?.toString() ?: "")
                .put("first", i.firstFlight?.toString() ?: "").put("seats", i.seats ?: JSONObject.NULL)
                .put("engines", i.engines).put("hex", i.hex).put("serial", i.serial))
        }
        put("legs", JSONArray(legs.map { l ->
            JSONObject().put("f", l.flight).put("from", l.from).put("to", l.to).put("c", l.cancelled)
                .put("ds", l.depSched?.epochSecond ?: 0).put("da", l.depActual?.epochSecond ?: 0)
                .put("as", l.arrSched?.epochSecond ?: 0).put("aa", l.arrActual?.epochSecond ?: 0)
        }))
    }

    companion object {
        /** Reads what [toJson] wrote; null when damaged. */
        fun fromJson(j: JSONObject): TailReport? = runCatching {
            fun t(o: JSONObject, k: String) = o.optLong(k).takeIf { it > 0 }?.let(Instant::ofEpochSecond)
            fun d(o: JSONObject, k: String) = o.optString(k).takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
            val info = j.optJSONObject("info")?.let { i ->
                TailInfo(
                    i.optString("model"), i.optString("type"), i.optString("airline"),
                    if (i.isNull("age")) null else i.optDouble("age"), d(i, "delivered"), d(i, "first"),
                    if (i.isNull("seats")) null else i.optInt("seats"), i.optString("engines"), i.optString("hex"), i.optString("serial"),
                )
            }
            val legs = j.getJSONArray("legs").let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
                    TailLeg(o.getString("f"), o.getString("from"), o.getString("to"), t(o, "ds"), t(o, "da"), t(o, "as"), t(o, "aa"), o.optBoolean("c"))
                }
            }
            TailReport(j.getString("reg"), info, legs, Instant.ofEpochSecond(j.getLong("at")), j.optString("source"))
        }.getOrNull()
    }
}
