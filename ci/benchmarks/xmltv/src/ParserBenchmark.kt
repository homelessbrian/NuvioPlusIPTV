package com.nuvio.tv.livetv.data

import com.nuvio.tv.livetv.model.EpgProgram
import java.io.ByteArrayInputStream
import java.lang.management.ManagementFactory
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.runner.JUnitCore

private const val FROM = 1794225600000L // 2026-11-09T12:00:00Z
private const val TO = FROM + 86_400_000L
private data class Capture(val result: XmltvResult, val runs: List<Pair<String, List<EpgProgram>>>, val selections: Int, val asks: Map<String, Int>)

private fun capture(xml: String, baseline: Boolean, streaming: Boolean = false, aliases: Boolean = false, unlisted: Boolean = false): Capture {
    val runs = ArrayList<Pair<String, List<EpgProgram>>>()
    var selections = 0
    val asks = HashMap<String, Int>()
    val select: (Map<String, XmltvChannel>) -> Set<String>? = { channels ->
        selections++
        if (aliases) channels.values.filter { "Sports alias" in it.displayNames }.map { it.id }.toSet() else null
    }
    val keep: ((String) -> Boolean)? = if (unlisted) ({ id -> asks[id] = (asks[id] ?: 0) + 1; id.endsWith("a") }) else null
    val sink: ((String, List<EpgProgram>) -> Unit)? = if (streaming) ({ id, programmes -> runs += id to programmes }) else null
    val input = ByteArrayInputStream(xml.toByteArray())
    val result = if (baseline) BaselineXmltvParser.parse(input, FROM, TO, keep, sink, select)
        else XmltvParser.parse(input, FROM, TO, keep, sink, select)
    return Capture(result, runs, selections, asks)
}

private fun programme(channel: String, start: String = "20261109120000 +0000", stop: String = "20261109130000 +0000", body: String = "<title>Show</title>") =
    "<programme channel='$channel' start='$start' stop='$stop'>$body</programme>"

private fun semantics() {
    check(Instant.parse("2026-11-09T12:00:00Z").toEpochMilli() == FROM)
    val channel = "<channel id='provider:a'><display-name>Primary</display-name><display-name>Sports alias</display-name><icon src='https://example.invalid/a.png'/></channel>"
    val casts = (1..15).joinToString("") { "<actor>Actor $it</actor>" }
    val desc = "x".repeat(850)
    val metadata = "<title>  Match (1999)  </title><title lang='fr'>Ignored second title</title>" +
        "<sub-title>Episode title</sub-title><desc>$desc</desc><category> Sports </category>" +
        "<episode-num system='xmltv_ns'>1.4.</episode-num><episode-num system='onscreen'>S2 E5</episode-num>" +
        "<date>20261109</date><icon src='https://example.invalid/show.png'/><credits>$casts</credits>"
    val fixtures = listOf(
        "<tv>$channel${programme("provider:a", body = metadata)}</tv>",
        "<tv>$channel${programme("provider:a", body = "<title>A &amp; B</title><desc>one <b>two</b> &amp; three<![CDATA[ <four>]]></desc>")}</tv>",
        "<tv>$channel${programme("provider:a", "20261109180000 +0530", "20261109190000 +0530")}</tv>",
        "<tv>$channel${programme("provider:a", "202611090400 -0800", "202611090500 -0800", "<title>Minutes only</title><date>1991</date>")}</tv>",
        "<tv>$channel${programme("provider:a", "20261109130000 +0000", "20261109140000 +0000", "<title>Later</title>")}${programme("provider:a")}${programme("provider:a", body = "<title>Tie</title>")}</tv>",
        "<tv>$channel${programme("provider:a")}${programme("provider:b")}${programme("provider:a", "20261109140000 +0000", "20261109150000 +0000")}</tv>",
        "<tv>${programme("a")}${programme("b")}${programme("a")}${programme("b")}</tv>",
        "<tv>$channel${programme("provider:a", "20261109110000 +0000", "20261109120000 +0000")}${programme("provider:a", "20261110120000 +0000", "20261110130000 +0000")}${programme("provider:a")}</tv>",
        "<tv>$channel<programme channel='provider:a' start='20261109120000 +0000' stop='20261109130000 +0000'><title>Unclosed</title>${programme("provider:a", "20261109130000 +0000", "20261109140000 +0000")}</tv>",
        "<tv>$channel${programme("provider:a", body = "<title>Empty metadata</title><credits/><desc/><category/>")}</tv>"
    )
    var comparisons = 0
    fixtures.forEach { xml ->
        for (streaming in listOf(false, true)) for (aliases in listOf(false, true)) for (unlisted in listOf(false, true)) {
            val expected = capture(xml, true, streaming, aliases, unlisted)
            val actual = capture(xml, false, streaming, aliases, unlisted)
            check(expected == actual) { "Semantic difference in fixture ${fixtures.indexOf(xml)} / $streaming / $aliases / $unlisted" }
            comparisons++
        }
    }
    val result = capture(fixtures.first(), false).result
    check(result.channels.getValue("provider:a").displayNames == listOf("Primary", "Sports alias"))
    val show = result.programs.getValue("provider:a").single()
    check(show.year == 1999 && show.episode == "S2 E5" && show.category == "Sports")
    check(show.people == (1..12).map { "Actor $it" } && show.description!!.length == 801)
    check(show.title == "Match (1999)" && show.icon == "https://example.invalid/show.png")
    val mixed = capture(fixtures[4], false).result.programs.getValue("provider:a")
    check(mixed.map { it.title } == listOf("Show", "Tie", "Later"))
    val asks = capture(fixtures[6], false, unlisted = true)
    check(asks.asks == mapOf("a" to 1, "b" to 1) && asks.result.programChannelIds == setOf("a", "b"))
    check(asks.result.programs.keys == setOf("a"))
    val providerA = capture("<tv>${programme("shared", body = "<title>Provider A</title>")}</tv>", false)
    val providerB = capture("<tv>${programme("shared", body = "<title>Provider B</title>")}</tv>", false)
    check(providerA.result.programs.getValue("shared").single().title == "Provider A")
    check(providerB.result.programs.getValue("shared").single().title == "Provider B")
    println("semanticEquivalence=$comparisons fixture/mode comparisons; aliases, offsets, metadata, split/unordered runs, selection, provider isolation passed")
    val unit = JUnitCore.runClasses(XmltvParserTimeTest::class.java, XmltvParserEpisodeTest::class.java)
    unit.failures.forEach { println(it.trace) }
    check(unit.wasSuccessful()) { "Timestamp/episode checks failed" }
    println("junit=${unit.runCount} tests passed (including20k timestamp compatibility cases)")
}

private fun feed(channels: Int): ByteArray {
    val time = DateTimeFormatter.ofPattern("yyyyMMddHHmmss ' +0000'").withZone(ZoneOffset.UTC)
    val times = (0..24).map { time.format(Instant.ofEpochMilli(FROM + it * 1_800_000L)) }.map { it.replace("  +", " +") }
    val xml = StringBuilder(channels * 24 * 300)
    xml.append("<tv>")
    repeat(channels) { ch -> xml.append("<channel id='provider").append(ch % 4).append(":").append(ch).append("'><display-name>Channel ").append(ch).append("</display-name><display-name>Alias ").append(ch).append("</display-name></channel>") }
    repeat(channels) { ch -> repeat(24) { slot ->
        xml.append("<programme channel='provider").append(ch % 4).append(":").append(ch).append("' start='").append(times[slot]).append("' stop='").append(times[slot + 1]).append("'>")
        xml.append("<title>Team ").append(ch % 32).append(" vs Team ").append((ch + slot) % 32).append(" (1999)</title><desc>Programme information with enough text to exercise common guide descriptions and preserve every listing for Search and Sports.</desc><category>Sports</category>")
        if (slot % 3 == 0) xml.append("<sub-title>Round ").append(slot).append("</sub-title>")
        if (slot % 4 == 0) xml.append("<episode-num system='xmltv_ns'>1.").append(slot).append(".</episode-num>")
        if (slot % 8 == 0) xml.append("<credits><actor>Actor One</actor><director>Director Two</director></credits>")
        xml.append("</programme>")
    } }
    xml.append("</tv>")
    return xml.toString().toByteArray()
}

private data class Measurement(val ms: Double, val allocated: Long, val count: Int, val checksum: Long, val channels: Int)
private val allocation = (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).also { it.isThreadAllocatedMemoryEnabled = true }
private fun measure(xml: ByteArray, baseline: Boolean): Measurement {
    System.gc(); Thread.sleep(40)
    var count = 0
    var checksum = 0L
    val sink: (String, List<EpgProgram>) -> Unit = { id, programmes ->
        checksum = checksum * 31 + id.hashCode()
        programmes.forEach { p -> count++; checksum = checksum * 31 + p.hashCode() }
    }
    val before = allocation.getThreadAllocatedBytes(Thread.currentThread().id)
    val started = System.nanoTime()
    val result = if (baseline) BaselineXmltvParser.parse(ByteArrayInputStream(xml), FROM, TO, onPrograms = sink) { null }
        else XmltvParser.parse(ByteArrayInputStream(xml), FROM, TO, onPrograms = sink) { null }
    val elapsed = (System.nanoTime() - started) / 1_000_000.0
    val allocated = allocation.getThreadAllocatedBytes(Thread.currentThread().id) - before
    check(result.error == null && result.programs.isEmpty()) { result.error ?: "Unexpected programme retention" }
    result.channels.values.forEach { checksum = checksum * 31 + it.hashCode() }
    check(result.programChannelIds.size == result.channels.size)
    return Measurement(elapsed, allocated, count, checksum, result.channels.size)
}

fun main(args: Array<String>) {
    println("runtime=${System.getProperty("java.runtime.version")},vm=${System.getProperty("java.vm.name")},os=${System.getProperty("os.name")},arch=${System.getProperty("os.arch")},backend=KXml2-2.3.0,heapLimit=1536MiB,collector=G1")
    semantics()
    if ("--check-only" in args) return
    val warm = feed(512)
    repeat(3) { measure(warm, true); measure(warm, false) }
    for ((size, rounds) in listOf(4000 to 5, 16000 to 5)) {
        val xml = feed(size)
        val before = ArrayList<Measurement>()
        val after = ArrayList<Measurement>()
        repeat(rounds) { round ->
            for (baseline in if (round % 2 == 0) listOf(true, false) else listOf(false, true)) {
                val result = measure(xml, baseline)
                check(result.channels == size && result.count == size * 24)
                (if (baseline) before else after).add(result)
                println("run,$size,${if (baseline) "baseline" else "changed"},$round,${"%.3f".format(java.util.Locale.ROOT, result.ms)},${result.allocated},${result.count},${result.checksum}")
            }
        }
        check((before + after).map { it.checksum }.distinct().size == 1) { "Large-feed output checksum changed" }
        val baseMs = before.map { it.ms }.sorted()[rounds / 2]
        val fixMs = after.map { it.ms }.sorted()[rounds / 2]
        val baseBytes = before.map { it.allocated }.sorted()[rounds / 2]
        val fixBytes = after.map { it.allocated }.sorted()[rounds / 2]
        val p95Index = kotlin.math.ceil(rounds * 0.95).toInt() - 1
        println("summary,$size,trials=$rounds,programmes=${size * 24},feedBytes=${xml.size},baselineMedianMs=$baseMs,changedMedianMs=$fixMs,baselineP95Ms=${before.map { it.ms }.sorted()[p95Index]},changedP95Ms=${after.map { it.ms }.sorted()[p95Index]},speedupPercent=${100 * (baseMs - fixMs) / baseMs},baselineAllocated=$baseBytes,changedAllocated=$fixBytes,allocationReductionPercent=${100.0 * (baseBytes - fixBytes) / baseBytes}")
    }
}
