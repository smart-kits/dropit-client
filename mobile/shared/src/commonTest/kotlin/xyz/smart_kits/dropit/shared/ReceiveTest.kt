package xyz.smart_kits.dropit.shared

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a pull turns into on the phone: letters, downloads, notices */
class ReceiveTest {
    private val me = "d_me"
    private fun text(seq: Long, from: String = "d_other", raw: String = "t$seq", group: Group? = null) =
        Incoming(seq, deviceId = from, kind = "text", text = raw, bytes = raw.length.toLong(), at = 1_000 + seq, source = "cli", group = group)
    private fun file(seq: Long, name: String, size: Long, mime: String = "image/jpeg", group: Group? = null) =
        Incoming(seq, deviceId = "d_other", kind = "image", url = "https://x.test/v1/files/$seq?s=1", bytes = size, at = 1_000 + seq,
            filename = name, mime = mime, group = group)
    private fun arrive(items: List<Incoming>, known: List<Letter> = emptyList(), cursor: Long = 0, unmetered: Boolean = true) =
        arrive(items, me, known, cursor, unmetered)

    @Test fun `U-D1 this phone's own item is skipped and the cursor still moves past it`() {
        val a = arrive(listOf(text(5, from = me), text(6)), cursor = 4)
        assertEquals(6, a.cursor)
        assertEquals(listOf("s6"), a.letters.map { it.id })
        assertEquals(listOf("s6"), a.notices.map { it.id })
    }

    @Test fun `U-D2 only its own - nothing to show - cursor moves`() {
        val a = arrive(listOf(text(7, from = me)), cursor = 6)
        assertEquals(7, a.cursor)
        assertTrue(a.letters.isEmpty() && a.notices.isEmpty() && a.downloads.isEmpty())
    }

    @Test fun `U-D3 three items sent together are one letter and one notice`() {
        val g = { i: Int -> Group("gA", i, 3) }
        val a = arrive(listOf(text(1, group = g(1)), file(2, "a.jpg", 10, group = g(2)), file(3, "b.jpg", 10, group = g(3))))
        assertEquals(1, a.letters.size)
        val l = a.letters[0]
        assertEquals("gA", l.id)
        assertEquals(listOf(1L, 2L, 3L), l.seqs)
        assertEquals("t1", l.text)
        assertEquals(listOf("a.jpg", "b.jpg"), l.files.map { it.name })
        assertEquals(1, a.notices.size)
        assertEquals(Message("noticeTextAndFiles", listOf("2")), a.notices[0].summary)
    }

    @Test fun `U-D4 the rest of a batch fills in the same letter without a second notice`() {
        val g = { i: Int -> Group("gB", i, 3) }
        val first = arrive(listOf(file(1, "a.jpg", 10, group = g(1)), file(2, "b.jpg", 10, group = g(2))))
        assertEquals(1, first.notices.size)
        val later = arrive(listOf(file(3, "c.jpg", 10, group = g(3))), known = first.letters, cursor = first.cursor)
        assertEquals(listOf("gB"), later.letters.map { it.id })
        assertEquals(listOf("a.jpg", "b.jpg", "c.jpg"), later.letters[0].files.map { it.name })
        assertTrue(later.notices.isEmpty())
        assertEquals(listOf(3L), later.downloads.map { it.seq })          // only the new piece is fetched
    }

    @Test fun `pulling the same items again changes nothing`() {
        val first = arrive(listOf(text(1), text(2)))
        val again = arrive(listOf(text(1), text(2)), known = first.letters, cursor = 0)
        assertTrue(again.letters.isEmpty() && again.notices.isEmpty())
        assertEquals(2, again.cursor)
    }

    @Test fun `a letter this phone sent is not mistaken for one that arrived`() {
        val mine = Letter(id = "gC", mine = true, at = 1, seqs = listOf(1))
        val a = arrive(listOf(text(2, group = Group("gC", 2, 2))), known = listOf(mine))
        assertEquals(1, a.notices.size)
        assertFalse(a.letters[0].mine)
    }

    @Test fun `U-D5 four separate items become one count`() {
        val a = arrive((1L..4L).map { text(it) })
        assertEquals(4, a.letters.size)
        assertEquals(listOf(Notice("backlog", summary = Message("noticeBacklog", listOf("4")), opensApp = true)), a.notices)
    }

    @Test fun `U-D6 three separate items are three notices`() {
        val a = arrive((1L..3L).map { text(it) })
        assertEquals(listOf("s1", "s2", "s3"), a.notices.map { it.id })
    }

    @Test fun `a backlog counts items - a batch being several`() {
        val g = { i: Int -> Group("gD", i, 2) }
        val a = arrive(listOf(text(1), text(2), text(3), file(4, "a.jpg", 1, group = g(1)), file(5, "b.jpg", 1, group = g(2))))
        assertEquals(4, a.letters.size)
        assertEquals(Message("noticeBacklog", listOf("5")), a.notices.single().summary)
    }

    @Test fun `U-D7 what downloads by itself`() {
        assertTrue(autoDownload(50L shl 20, unmetered = true))
        assertTrue(autoDownload(19L shl 20, unmetered = false))
        assertFalse(autoDownload(21L shl 20, unmetered = false))
        val a = arrive(listOf(file(1, "small.jpg", 19L shl 20), file(2, "big.mp4", 21L shl 20, mime = "video/mp4")), unmetered = false)
        assertEquals(listOf(1L), a.downloads.map { it.seq })
        assertEquals(listOf(NoticeAction.OPEN, NoticeAction.SHARE), a.notices[0].actions)
        assertEquals(listOf(NoticeAction.DOWNLOAD, NoticeAction.SHARE), a.notices[1].actions)
    }

    @Test fun `U-D8 the phone keeps a week`() {
        val day = 86_400_000L
        val now = 100 * day
        val kept = keepRecent(listOf(Letter("old", false, now - 8 * day), Letter("new", false, now - 6 * day)), now)
        assertEquals(listOf("new"), kept.map { it.id })
    }

    @Test fun `U-D9 a full page means there is more - keep pulling`() = runTest {
        val asked = mutableListOf<String>()
        val pages = mapOf(
            0L to """{"items":[{"seq":1,"device_id":"d","kind":"text","raw":"a","bytes":1,"created_at":5}],"has_more":true,"next_after":1}""",
            1L to """{"items":[{"seq":2,"device_id":"d","kind":"text","raw":"b","bytes":1,"created_at":6}],"has_more":true,"next_after":2}""",
            2L to """{"items":[],"has_more":false,"next_after":2}""",
        )
        val all = pullAll(0, "push") { spec ->
            asked += spec.target
            parse(200, pages.getValue(spec.query.first { it.first == "after" }.second.toLong()))
        }
        assertEquals(listOf(1L, 2L), all.items.map { it.seq })
        assertEquals(2, all.next)
        assertEquals(3, asked.size)
        assertTrue(asked.all { it.endsWith("&why=push") && "limit=$PULL_LIMIT" in it }, asked.toString())
    }

    @Test fun `a service that says more without moving on is not asked forever`() = runTest {
        var calls = 0
        val all = pullAll(3, null) { calls++; parse(200, """{"items":[],"has_more":true,"next_after":3}""") }
        assertEquals(1, calls)
        assertEquals(3, all.next)
    }

    @Test fun `a page as the service sends it`() {
        val page = pullPage(parse(200, """{"items":[
            {"seq":7,"device_id":"d_a","kind":"url","bytes":21,"meta":{"title":"A page","description":"x"},"client_ts":1,"source":"extension","created_at":9,"raw":"https://example.com/a"},
            {"seq":8,"device_id":"d_a","kind":"image","bytes":4096,"meta":{"filename":"p.png","mime":"image/png","group":{"id":"g1","i":2,"n":2},"from":{"url":"https://example.com/p","title":"P"}},"source":"extension","created_at":10,"raw":"","url":"https://x.test/v1/files/8?s=1"},
            {"seq":9,"device_id":"d_a","kind":"text","bytes":1,"meta":{"from":{"url":"javascript:alert(1)","title":"bad"}},"source":"cli","created_at":11,"raw":"t"},
            "not an item"],"has_more":false,"next_after":9}"""), 6)
        assertEquals(listOf(7L, 8L, 9L), page.items.map { it.seq })
        assertEquals("A page", page.items[0].title)
        assertEquals("https://example.com/a", page.items[0].text)
        val f = page.items[1]
        assertTrue(f.isFile && f.text == null)
        assertEquals(Group("g1", 2, 2), f.group)
        assertEquals(From("https://example.com/p", "P"), f.from)
        assertNull(page.items[2].from)                       // only web addresses count as where something came from
        assertEquals(9, page.next)
    }

    // ── What a notice says ──

    @Test fun `U-N1 text - its first lines - Copy first`() {
        val n = noticeFor(Letter("s1", false, 1, kind = "text", text = "hello\nworld"))
        assertEquals("hello\nworld", n.body)
        assertEquals(listOf(NoticeAction.COPY, NoticeAction.SHARE), n.actions)
        assertNull(n.title); assertNull(n.from); assertFalse(n.opensApp)
        assertTrue(noticeFor(Letter("s2", false, 1, kind = "text", text = "x".repeat(1000))).body!!.length <= NOTICE_TEXT_MAX)
    }

    @Test fun `U-N2 text picked up on a page says where from`() {
        val n = noticeFor(Letter("s1", false, 1, kind = "text", text = "quote", fromTitle = "A page", fromUrl = "https://example.com"))
        assertEquals(Message("noticeFrom", listOf("A page")), n.from)
        assertEquals("From A page", Strings.EN.render(n.from!!))
        assertEquals("来自 A page", Strings.ZH.render(n.from!!))
    }

    @Test fun `U-N3 a link - title and address - Open first`() {
        val n = noticeFor(Letter("s1", false, 1, kind = "url", text = "https://example.com/a", title = "A page"))
        assertEquals("A page", n.title)
        assertEquals("https://example.com/a", n.body)
        assertEquals(listOf(NoticeAction.OPEN, NoticeAction.COPY, NoticeAction.SHARE), n.actions)
        assertNull(noticeFor(Letter("s2", false, 1, kind = "url", text = "https://example.com/a")).title)
    }

    @Test fun `U-N4 a file - Open once it is on the phone - Download until then`() {
        val on = Letter("s1", false, 1, files = listOf(LetterFile("p.jpg", 10, "image/jpeg", 1, saved = "content://x")))
        val off = Letter("s2", false, 1, files = listOf(LetterFile("big.zip", 99, "application/zip", 2)))
        assertEquals("p.jpg", noticeFor(on).title)
        assertEquals(listOf(NoticeAction.OPEN, NoticeAction.SHARE), noticeFor(on).actions)
        assertEquals(listOf(NoticeAction.DOWNLOAD, NoticeAction.SHARE), noticeFor(off).actions)
    }

    @Test fun `U-N5 a batch says what it holds and opens the app`() {
        val files = List(3) { LetterFile("f$it", 1, null, it.toLong()) }
        val n = noticeFor(Letter("g", false, 1, kind = "text", text = "note", files = files))
        assertEquals(Message("noticeTextAndFiles", listOf("3")), n.summary)
        assertEquals("3 files and a text", Strings.EN.render(n.summary!!))
        assertEquals("3 个文件和一段文字", Strings.ZH.render(n.summary!!))
        assertTrue(n.opensApp && n.actions.isEmpty() && n.body == null)       // the text itself stays out of the summary
        assertEquals("2 个文件", Strings.ZH.render(noticeFor(Letter("g2", false, 1, files = files.take(2))).summary!!))
        assertEquals("1 file and a text", Strings.EN.render(Message("noticeTextAndFiles", listOf("1"))))
    }

    @Test fun `U-N6 the lock screen never shows what arrived`() {
        assertEquals("dropit · 新内容", Strings.ZH.render(LOCKED_NOTICE))
        assertEquals("dropit · something new", Strings.EN.render(LOCKED_NOTICE))
        assertEquals("收到 23 条", Strings.ZH.render(Message("noticeBacklog", listOf("23"))))
    }

    @Test fun `the requests`() {
        assertEquals("/v1/pull?after=5&limit=50", Requests.pull(5).target)
        assertEquals("""{"kind":"fcm","token":"abc"}""", Requests.registerPush("abc").body.toString())
        assertEquals("""{"token":""}""", Requests.registerPush(null).body.toString())
        assertEquals("PUT", Requests.registerPush("abc").method)
        assertEquals("/v1/ws?ticket=a.b%2Bc&can=end", Requests.wsTarget("a.b+c"))
        assertFalse(Requests.testPush().retriable)           // a second test push would be a second signal
    }
}

// ── Saving what arrives: how long the service has it, and a fresh address when it is asked for ──
class SavingTest {
    private fun item(seq: Long, at: Long) = Incoming(seq = seq, url = "https://x.test/v1/files/$seq?e=1", bytes = 10, at = at, filename = "a.pdf", mime = "application/pdf")

    @Test
    fun aFileLetterKnowsWhenTheServiceLetsGoOfIt() {
        val a = arrive(listOf(item(1, at = 1_000_000)), null, emptyList(), 0, unmetered = false, retentionMs = 86_400_000, cellularMax = -1)
        assertEquals(1_000_000 + 86_400_000, a.letters.single().files.single().expires)
        assertTrue(a.downloads.isEmpty())   // saved on a tap: each one counts against the day's downloads
        val none = arrive(listOf(item(2, at = 5)), null, emptyList(), 0, unmetered = false)
        assertNull(none.letters.single().files.single().expires)
    }

    @Test
    fun nothingDownloadsByItselfWhenTheAppSaysSo() {
        val a = arrive(listOf(item(1, 1)), null, emptyList(), 0, unmetered = false, cellularMax = -1)
        assertTrue(a.downloads.isEmpty())
        // a file that waits for a tap says Download in its notice
        assertEquals(NoticeAction.DOWNLOAD, a.notices.single().actions.first())
    }

    @Test
    fun aFileIsSavedExpiredOrAvailable() {
        val f = LetterFile("a.pdf", 10, "application/pdf", 1, expires = 1_000)
        assertEquals(FileState.AVAILABLE, f.state(999))
        assertEquals(FileState.EXPIRED, f.state(1_000))
        assertEquals(FileState.SAVED, f.copy(saved = "content://x").state(5_000))   // on the phone: the service's end no longer matters
        assertEquals(FileState.AVAILABLE, f.copy(expires = null).state(Long.MAX_VALUE))
    }

    @Test
    fun theFreshAddressIsTheItemAgainOrNothingWhenExpired() {
        val body = """{"items":[{"seq":7,"kind":"file","bytes":10,"created_at":1,"url":"https://x.test/v1/files/7?e=2","meta":{"filename":"a.pdf"}}],"has_more":false,"next_after":7}"""
        val answer = parse(200, body)
        assertEquals("https://x.test/v1/files/7?e=2", freshAddress(answer, 7))
        assertNull(freshAddress(answer, 8))                                                   // another item
        assertNull(freshAddress(parse(200, """{"items":[],"has_more":false,"next_after":6}"""), 7))   // expired: the pull leaves it out
        val spec = Requests.itemAgain(7)
        assertEquals("/v1/pull?after=6&limit=1&why=manual", spec.target)
    }
}
