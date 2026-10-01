package com.aria.air

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Duration
import java.time.LocalDate

/** JSON round trip, backups, re-import de-duplication, duplicates and passport stats (Data.kt). */
class StoreTest : AirTest() {
    @get:Rule val tmp = TemporaryFolder()

    @Before
    fun emptyStore() {
        Store.file = java.io.File(tmp.root, "flights.json")
        Store.flights.clear()
        Prefs.name = ""; Prefs["dupIgnore"] = ""
    }

    @After
    fun cleanUp() {
        Store.flights.clear()
        Prefs.name = ""; Prefs["dupIgnore"] = ""
    }

    /** Every field set, times on whole milliseconds as JSON keeps them. */
    private fun full(): Flight {
        val f = flight(hours(-1))
        return f.copy(
            depEst = f.depSched!!.plusMillis(1), depActual = f.depSched!!.plusSeconds(60), arrEst = f.arrSched!!.plusSeconds(30),
            arrActual = f.arrSched!!.plusSeconds(90), depTerminal = "3", depGate = "F12", arrTerminal = "B", arrGate = "B5", baggage = "4",
            aircraft = "B38M", reg = "N12345", cancelled = true, diverted = true, seat = "21C", booking = "TESTAB", notes = "window\n\"quoted\"",
            friend = "Sam", source = "import", work = true,
            depWx = Wx(12.5, 3, 20.0, f.depSched!!, NOW), arrWx = Wx(-2.0, 71, 0.0, f.arrSched!!, NOW),
            inbound = Inbound("UAL523", "DEN", "SFO", 39.5, -110.25, 30000, 450, false, NOW, NOW, landedAt = NOW.minusSeconds(5), firstSeen = NOW.minusSeconds(60)),
            live = Live(40.0, -100.0, 35000, 480, 72.5, -64, NOW, false, "B38M", "N12345"),
            updated = NOW, alerted = "departed,delay30",
        )
    }

    @Test
    fun jsonRoundTrip() {
        val f = full()
        assertEquals(f, flightFrom(JSONObject(f.toJson().toString())))
        val bare = flight(null)
        assertEquals(bare, flightFrom(JSONObject(bare.toJson().toString())))
        assertFalse(bare.toJson().has("seat"))  // blanks aren't written
        val noEta = f.copy(inbound = f.inbound!!.copy(eta = null, landedAt = null))
        assertEquals(noEta, flightFrom(JSONObject(noEta.toJson().toString())))
    }

    @Test
    fun oldBackupsStillLoad() {
        // An early backup: no source, no firstSeen on the inbound plane, no wind in the weather.
        val j = JSONObject(
            """{"id":"a","airline":"UA","number":"100","date":"2026-10-02","from":"SFO","to":"BOS",
               "depWx":{"t":10,"code":0,"at":1,"fetched":2},
               "inbound":{"callsign":"UAL523","lat":1,"lon":2,"at":1000}}""",
        )
        val f = flightFrom(j)
        assertEquals("manual", f.source)
        assertEquals(0.0, f.depWx!!.windKmh, 0.0)
        assertEquals(f.inbound!!.at, f.inbound!!.firstSeen)
        assertNull(f.inbound!!.eta)
    }

    @Test
    fun backupFile() {
        Prefs.name = "Jane Doe"
        val f = full()
        val text = Store.json(listOf(f, flight(null)))
        assertTrue(Store.isBackup(text))
        assertEquals("Jane Doe", JSONObject(text).getString("name"))
        assertFalse(Store.isBackup("""{"flights":[]}"""))
        assertFalse(Store.isBackup("Flight Date,Flight Code"))
        assertEquals(f, Store.parse(text)[0])
        assertEquals(2, Store.parse(text).size)
        // A broken entry is skipped, not the whole file.
        assertEquals(1, Store.parse("""{"app":"air","flights":[{"id":"x"},${flight(null).toJson()}]}""").size)
    }

    @Test
    fun restoreSkipsWhatIsAlreadyHere() {
        val a = flight(hours(2))
        Store.addAll(listOf(a))
        val sameFlightOtherId = a.copy(id = "other")
        val friends = a.copy(id = "friend", friend = "Sam")
        val text = JSONObject().put("app", "air").put("name", "Jane Doe")
            .put("flights", org.json.JSONArray(listOf(a, sameFlightOtherId, friends).map { it.toJson() })).toString()
        assertEquals(1, Store.import(text))
        assertEquals(listOf(a.id, "friend"), Store.flights.map { it.id })
        assertEquals("Jane Doe", Prefs.name)  // taken from the backup when none is set
        Prefs.name = "John Doe"
        Store.import(text)
        assertEquals("John Doe", Prefs.name)  // but never overwritten
    }

    @Test
    fun addAllDeduplicates() {
        val a = flight(hours(2))
        assertEquals(1, Store.addAll(listOf(a, a.copy(id = "dup"))))
        assertEquals(0, Store.addAll(listOf(a.copy(id = "again"))))
        assertEquals(1, Store.addAll(listOf(a.copy(id = "sam", friend = "Sam"))))
        assertEquals(a.id, Store.findSame(a.copy(id = "x"))!!.id)
        assertEquals("sam", Store.findSame(a.copy(id = "y", friend = "SAM"))!!.id)  // case-insensitive
        assertNull(Store.findSame(a.copy(date = a.date.plusDays(1))))
        assertEquals(listOf("Sam"), Store.friends)
    }

    @Test
    fun upsertAndRemove() {
        val a = flight(hours(2))
        Store.upsert(a)
        assertEquals(listOf(a), Store.parse(Store.file.readText()))  // saved
        Store.upsert(a.copy(seat = "1A"))
        assertEquals("1A", Store.get(a.id)!!.seat)
        assertEquals(1, Store.flights.size)
        Store.remove(a.id)
        assertNull(Store.get(a.id))
    }

    // ---------- Duplicates ----------

    @Test
    fun duplicatesMerge() {
        val d = LocalDate.of(2026, 1, 2)
        val a = Flight(airline = "UA", number = "1", date = d, from = "SFO", to = "BOS", source = "byAir", notes = "aisle")
        val b = a.copy(id = "other", seat = "21C", booking = "ABCDEF", work = true, source = "import", notes = "aisle\nsnacks")
        val c = a.copy(id = "friend", friend = "Alex")
        assertEquals("Same flight", Dupes.reason(a, b))
        assertEquals("Same flight", Dupes.reason(a, a.copy(id = "y", friend = "")))
        assertEquals("Same flight, different traveler", Dupes.reason(a, c))
        assertNull(Dupes.reason(a, a.copy(id = "x", date = d.plusDays(1))))
        val m = a.mergedWith(b)
        assertEquals(a.id, m.id); assertEquals("21C", m.seat); assertEquals("ABCDEF", m.booking); assertTrue(m.work)
        assertEquals("aisle\naisle\nsnacks", m.notes)
        assertEquals("byAir", m.source)
    }

    @Test
    fun codesharesNeedTheSameRouteAndTime() {
        val a = flight(hours(2))
        val dl = a.copy(id = "dl", airline = "DL", number = "5000")
        assertEquals("Same route and time (codeshare?)", Dupes.reason(a, dl))
        assertEquals("Same route and time (codeshare?)", Dupes.reason(a, dl.copy(depSched = a.depSched!!.plus(Duration.ofMinutes(30)))))
        assertNull(Dupes.reason(a, dl.copy(depSched = a.depSched!!.plus(Duration.ofMinutes(31)))))
        assertNull(Dupes.reason(a, dl.copy(to = "JFK")))
        assertEquals("Same route and time (codeshare?)", Dupes.reason(a, dl.copy(depSched = null)))  // no time: route is enough
    }

    @Test
    fun groupsIgnoreAndMerge() {
        val a = flight(hours(2)).copy(source = "byAir")
        val b = a.copy(id = "b", seat = "21C", booking = "TESTAB", depGate = "F12")
        val c = a.copy(id = "c", airline = "DL", number = "5000", notes = "codeshare")
        val other = flight(hours(30)).copy(id = "other", date = TODAY.plusDays(1))
        listOf(a, b, c, other).forEach { Store.flights.add(it) }
        val groups = Dupes.groups(Store.flights)
        assertEquals(1, groups.size)
        assertEquals(listOf("b", a.id, "c").sorted(), groups[0].map { it.id }.sorted())
        assertEquals("b", groups[0].first().id)  // most complete first
        assertTrue(b.completeness() > a.completeness())
        assertTrue(a.completeness() > a.copy(friend = "Sam").completeness())  // mine counts more

        Dupes.merge(groups[0])
        assertEquals(listOf("b", "other"), Store.flights.map { it.id }.sorted())
        assertEquals("codeshare", Store.get("b")!!.notes)

        Store.flights.add(a)
        Dupes.ignore(Dupes.groups(Store.flights)[0])
        assertTrue(Dupes.groups(Store.flights).isEmpty())
    }

    // ---------- Passport ----------

    @Test
    fun stats() {
        val sfoBos = flight(hours(-48))
        val bosSfo = flight(hours(-24), from = "BOS", to = "SFO").copy(airline = "DL")
        val lhr = flight(hours(-72), block = hours(11), to = "LHR")
        val s = stats(listOf(sfoBos, bosSfo, lhr))
        assertEquals(3, s.flights)
        assertEquals(sfoBos.distanceKm * 2 + lhr.distanceKm, s.km, 1e-6)
        assertEquals(hours(21), s.air)
        assertEquals(mapOf("SFO" to 3, "BOS" to 2, "LHR" to 1), s.airports)
        assertEquals(mapOf("UA" to 2, "DL" to 1), s.airlines)
        assertEquals(setOf("US", "GB"), s.countries)
        assertEquals(mapOf("BOS – SFO" to 2, "LHR – SFO" to 1), s.routes)
        assertEquals(lhr, s.longest)
        assertEquals(Duration.ZERO, stats(listOf(sfoBos.copy(arrSched = sfoBos.depSched!!.minusSeconds(60)))).air) // bad data
        assertNull(stats(emptyList()).longest)
    }
}
