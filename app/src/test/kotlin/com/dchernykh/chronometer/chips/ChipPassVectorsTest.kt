package com.dchernykh.chronometer.chips

import androidx.room.Room
import androidx.work.testing.WorkManagerTestInitHelper
import com.dchernykh.chronometer.data.AppDatabase
import com.dchernykh.chronometer.data.CutoffRepository
import com.dchernykh.chronometer.data.SettingsStore
import com.dchernykh.chronometer.io.BackupWriter
import com.dchernykh.chronometer.source.SourceEvent
import com.dchernykh.chronometer.util.TimeFormatter
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

private const val BASE_MS = 1_767_261_600_000L

/**
 * The shared cases (resources/chip-pass-vectors.json, a copy of ChronometerPython's
 * specs/001-event-sources/contracts/chip-pass-vectors.json) run through the repository and
 * Room, one read at a time and in batches: one rule on both chronometers (spec SC-001).
 */
@RunWith(RobolectricTestRunner::class)
class ChipPassVectorsTest {
    private val context = RuntimeEnvironment.getApplication()
    private val cases =
        JSONObject(checkNotNull(javaClass.getResource("/chip-pass-vectors.json")).readText()).getJSONArray("cases")

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @Test
    fun everyCaseOneReadAtATime() = runCases(batched = false)

    @Test
    fun everyCaseInBatches() = runCases(batched = true)

    private fun runCases(batched: Boolean) =
        runTest {
            for (i in 0 until cases.length()) {
                val case = cases.getJSONObject(i)
                val events = case.getJSONArray("events").let { all -> (0 until all.length()).map(all::getJSONObject) }
                assertEquals(case.getString("name"), expected(case), crossings(case, events, batched))
            }
            assertEquals(14, cases.length())
        }

    private fun expected(case: JSONObject): List<Pair<String, String>> {
        val crossings = case.getJSONArray("crossings")
        return (0 until crossings.length()).map {
            val crossing = crossings.getJSONArray(it)
            crossing.getString(0) to TimeFormatter.currentTimeString(BASE_MS + crossing.getLong(1))
        }
    }

    private suspend fun crossings(
        case: JSONObject,
        events: List<JSONObject>,
        batched: Boolean,
    ): List<Pair<String, String>> {
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val folder = File(context.cacheDir, "vectors-${System.nanoTime()}").apply { mkdirs() }
            File(folder, "start.txt").writeText(startTxt(case.getJSONObject("map")))
            val store = SettingsStore(context)
            store.save(
                store.load().copy(folderPath = folder.path, deadTimeS = seconds(case.optLong("dead_time_ms", 5000))),
            )
            val chips =
                ChipRepository(
                    db,
                    store,
                    BackupWriter(),
                    CutoffRepository(context, db, store, BackupWriter()),
                )
            val waiting = mutableListOf<SourceEvent.ChipRead>()
            for (event in events) {
                if (event.has("read")) {
                    val read = event.getJSONArray("read")
                    waiting += SourceEvent.ChipRead(read.getString(0), BASE_MS + read.getLong(1))
                }
                if (!batched || !event.has("read")) {
                    chips.apply(waiting.toList())
                    waiting.clear()
                }
                if (event.has("dead_time_ms")) {
                    store.save(store.load().copy(deadTimeS = seconds(event.getLong("dead_time_ms"))))
                }
                if (event.has("map")) {
                    File(folder, "start.txt").writeText(startTxt(event.getJSONObject("map")))
                    chips.reloadMap()
                }
            }
            chips.apply(waiting)
            return db.cutoffDao().getAll().map { it.number to it.timeStr }
        } finally {
            db.close()
        }
    }

    private fun seconds(ms: Long) = (ms / 1_000).toInt()

    private fun startTxt(map: JSONObject): String =
        map
            .keys()
            .asSequence()
            .toList()
            .groupBy { map.getString(it) }
            .entries
            .joinToString("") { (bib, chips) -> "$bib#R#M#3#1#1990#T#C##0 0:0:0.000#r#${chips.joinToString(",")}#\n" }
}
