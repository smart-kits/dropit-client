package xyz.smart_kits.dropit.shared

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * U-V1 · The cases in clients/test/vectors/payload.json, whose expected values come from the browser
 * extension. The extension's tests read the same file: one rule, one set of answers, every client.
 */
class VectorsTest {
    private val v = json.parseToJsonElement(VECTORS_JSON).jsonObject
    private fun cases(name: String) = v.getValue(name).jsonArray.map { it.jsonArray }
    private val JsonElement.s get() = jsonPrimitive.contentOrNull
    private fun meta(e: JsonElement): Meta? = if (e is JsonNull) null else json.decodeFromJsonElement<Meta>(e)

    @Test fun constants() {
        val c = v.getValue("constants").jsonObject
        assertEquals(c.getValue("META_BUDGET").jsonPrimitive.int, META_BUDGET)
        assertEquals(c.getValue("TITLE_MAX").jsonPrimitive.int, TITLE_MAX)
        assertEquals(c.getValue("DESCRIPTION_MAX").jsonPrimitive.int, DESCRIPTION_MAX)
        assertEquals(c.getValue("FROM_URL_MAX").jsonPrimitive.int, FROM_URL_MAX)
        assertEquals(c.getValue("FILES_MAX").jsonPrimitive.int, FILES_MAX)
        assertEquals(c.getValue("GROUP_MAX").jsonPrimitive.int, GROUP_MAX)
    }

    @Test fun kindOf() = cases("kindOf").forEach { (input, want) ->
        assertEquals(want.s, kindOf(input.s), "kindOf(${input.s})")
    }

    @Test fun squash() = cases("squash").forEach { (input, want) ->
        assertEquals(want.s, squash(input.s), "squash(${input.s})")
    }

    @Test fun clip() = cases("clip").forEach { (s, n, want) ->
        assertEquals(want.s, clip(s.s, n.jsonPrimitive.int), "clip(${s.s}, $n)")
    }

    @Test fun pageMeta() = cases("pageMeta").forEach { (input, want) ->
        val o = input.jsonObject
        assertEquals(meta(want), pageMeta(o["title"]?.s, o["description"]?.s))
    }

    @Test fun fromMeta() = cases("fromMeta").forEach { (url, title, want) ->
        val expected = if (want is JsonNull) null else json.decodeFromJsonElement<From>(want)
        assertEquals(expected, fromMeta(url.s, title.s), "fromMeta(${url.s?.take(40)})")
    }

    @Test fun fitMeta() = cases("fitMeta").forEachIndexed { k, (input, limit, want) ->
        val got = fitMeta(meta(input), limit.jsonPrimitive.int)
        assertEquals(meta(want), got, "fitMeta case $k")
        if (got != null) assertTrue(got.jsonBytes() <= limit.jsonPrimitive.int, "case $k within budget")
    }

    @Test fun metaBytesMatchJavaScript() = cases("fitMeta").forEach { (_, _, want) ->
        // The extension measures JSON.stringify(meta); the JSON it produced is right here
        if (want !is JsonNull) assertEquals(utf8Bytes(want.toString()), meta(want)!!.jsonBytes(), want.toString().take(60))
    }

    @Test fun groupsFor() {
        cases("groupsFor").forEach { (n, id, want) ->
            val expected = want.jsonArray.map { if (it is JsonNull) null else json.decodeFromJsonElement<Group>(it) }
            assertEquals(expected, groupsFor(n.jsonPrimitive.int, id.s!!))
        }
        v.getValue("groupsForRefused").jsonArray.forEach { n ->
            assertFailsWith<BatchTooLarge> { groupsFor(n.jsonPrimitive.int) }
        }
    }

    @Test fun seqRange() = cases("seqRange").forEach { (seqs, want) ->
        assertEquals(want.s, seqRange(seqs.jsonArray.map { it.jsonPrimitive.long }))
    }

    @Test fun sizes() {
        cases("mb").forEach { (n, want) -> assertEquals(want.s, mb(n.jsonPrimitive.double.toLong())) }
        cases("fileSize").forEach { (n, want) -> assertEquals(want.s, fileSize(n.jsonPrimitive.double.toLong())) }
    }

    @Test fun endpointList() = cases("endpointList").forEach { (stored, defaults, want) ->
        val list = (stored as? JsonArray)?.map { it.s!! }
        assertEquals(want.jsonArray.map { it.s }, endpointList(list, defaults.jsonArray.map { it.s!! }))
    }

    @Test fun errorText() = v.getValue("errorText").jsonArray.map { it.jsonObject }.forEach { c ->
        val now = LocalDateTime.parse(c.getValue("now_local").s!!).toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()
        val got = errorMessage(c["code"]?.s, c.getValue("data").jsonObject, c.getValue("status").jsonPrimitive.int, now)
        assertEquals(c.getValue("expect").s, got.toString(), c.toString())
    }

    @Test fun everyErrorCodeHasASentence() = v.getValue("errorCodes").jsonArray.forEach { code ->
        assertTrue(code.s in Strings.EN.errors && code.s in Strings.ZH.errors, "missing ${code.s}")
    }

    @Test fun deviceKey() = cases("deviceKey").forEach { (kind, facts, want) ->
        val list = facts.jsonArray.map { p -> p.jsonPrimitive.let { if (it.isString) it.content else it.long } }
        assertEquals(want.s, deviceKey(kind.s!!, list))
    }
}
