package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a letter's button and sheet offer, for every state a file can be in, received or sent, one file or a drop of several */
class LetterActionsTest {
    private val now = 1_000L

    private fun file(seq: Long, state: FileState, mime: String? = "application/pdf") = LetterFile(
        name = "f$seq", size = 10, mime = mime, seq = seq,
        saved = if (state == FileState.SAVED) "content://saved/$seq" else null,
        expires = if (state == FileState.EXPIRED) 0 else null,
    )

    private fun got(vararg files: LetterFile, text: String? = null, kind: String? = null) =
        Letter(id = "g", mine = false, at = 1, seqs = files.mapNotNull { it.seq }, kind = kind, text = text, files = files.toList())

    private fun sent(vararg files: LetterFile, text: String? = null, kind: String? = null) =
        Letter(id = "s", mine = true, at = 1, kind = kind, text = text, files = files.toList())

    private fun Letter.menuActs(saving: Set<Long> = emptySet(), failures: Map<Long, SaveFailure> = emptyMap()) = menu(now, saving, failures).map { it.act }

    private val saved1 get() = file(1, FileState.SAVED)
    private val saved2 get() = file(2, FileState.SAVED)
    private val fresh1 get() = file(1, FileState.AVAILABLE)
    private val fresh2 get() = file(2, FileState.AVAILABLE)
    private val gone1 get() = file(1, FileState.EXPIRED)
    private val gone2 get() = file(2, FileState.EXPIRED)

    // ---- received, one file ----

    @Test fun `one received file - saved opens and shares and is copyable when a picture`() {
        assertEquals(LetterOffer(Act.OPEN_FILE), got(saved1).primary(now))
        assertEquals(listOf(Act.OPEN_FILE, Act.SHARE, Act.CLEAR), got(saved1).menuActs())
        assertEquals(listOf(Act.OPEN_FILE, Act.SHARE, Act.COPY_IMAGE, Act.CLEAR), got(file(1, FileState.SAVED, "image/png")).menuActs())
    }

    @Test fun `one received file - expired can only be cleared`() {
        assertEquals(LetterOffer(Act.CLEAR), got(gone1).primary(now))
        assertEquals(listOf(Act.CLEAR), got(gone1).menuActs())
        assertEquals(listOf(Act.CLEAR), got(file(1, FileState.EXPIRED, "image/png")).menuActs())   // no copy of a picture that is gone
    }

    @Test fun `one received file - available saves and shares saving it first and copies a picture`() {
        assertEquals(LetterOffer(Act.SAVE), got(fresh1).primary(now))
        assertEquals(listOf(Act.SAVE, Act.SHARE, Act.CLEAR), got(fresh1).menuActs())
        assertEquals(listOf(Act.SAVE, Act.SHARE, Act.COPY_IMAGE, Act.CLEAR), got(file(1, FileState.AVAILABLE, "image/jpeg")).menuActs())
    }

    @Test fun `one received file - a failed try is retried except when today's downloads are used up`() {
        val l = got(fresh1)
        assertEquals(LetterOffer(Act.RETRY), l.primary(now, failures = mapOf(1L to SaveFailure.OFFLINE)))
        assertEquals(LetterOffer(Act.RETRY), l.primary(now, failures = mapOf(1L to SaveFailure.FAILED)))
        assertEquals(LetterOffer(Act.RETRY, enabled = false), l.primary(now, failures = mapOf(1L to SaveFailure.LIMIT)))
        assertEquals(listOf(Act.RETRY, Act.SHARE, Act.CLEAR), l.menuActs(failures = mapOf(1L to SaveFailure.OFFLINE)))
        assertEquals(false, l.menu(now, failures = mapOf(1L to SaveFailure.LIMIT)).first().enabled)
    }

    @Test fun `one received file - an old phone cannot save so nothing is offered but clearing`() {
        val l = got(fresh1)
        val f = mapOf(1L to SaveFailure.UNSUPPORTED)
        assertEquals(LetterOffer(Act.NONE), l.primary(now, failures = f))
        assertEquals(listOf(Act.CLEAR), l.menuActs(failures = f))
    }

    @Test fun `one received file - while saving the button is busy and cannot be pressed`() {
        val l = got(fresh1)
        assertEquals(LetterOffer(Act.SAVING, enabled = false), l.primary(now, saving = setOf(1L)))
        assertEquals(listOf(Act.SAVING, Act.SHARE, Act.CLEAR), l.menuActs(saving = setOf(1L)))
    }

    @Test fun `a saving mark on a file that is already saved changes nothing`() {
        assertEquals(LetterOffer(Act.OPEN_FILE), got(saved1).primary(now, saving = setOf(1L)))
    }

    // ---- sent ----

    @Test fun `sent files have no button - only their words can be copied`() {
        assertEquals(LetterOffer(Act.NONE), sent(file(1, FileState.SENT)).primary(now))
        assertEquals(listOf(Act.CLEAR), sent(file(1, FileState.SENT)).menuActs())
        assertEquals(listOf(Act.CLEAR), sent(file(1, FileState.SENT), file(2, FileState.SENT)).menuActs())
        assertEquals(LetterOffer(Act.NONE), sent(file(1, FileState.SENT), file(2, FileState.SENT)).primary(now))
    }

    @Test fun `sent words with files - copy and share and clear`() {
        val l = sent(file(1, FileState.SENT), file(2, FileState.SENT), text = "note", kind = "text")
        assertEquals(LetterOffer(Act.COPY), l.primary(now))
        assertEquals(listOf(Act.COPY, Act.SHARE, Act.CLEAR), l.menuActs())
    }

    @Test fun `a sent file ignores any saving or failure mark`() {
        val l = sent(file(1, FileState.SENT))
        assertEquals(LetterOffer(Act.NONE), l.primary(now, saving = setOf(1L), failures = mapOf(1L to SaveFailure.LIMIT)))
    }

    @Test fun `words alone - sent or received`() {
        assertEquals(LetterOffer(Act.COPY), sent(text = "hi", kind = "text").primary(now))
        assertEquals(LetterOffer(Act.COPY), got(text = "hi", kind = "text").primary(now))
        assertEquals(listOf(Act.COPY, Act.SHARE, Act.CLEAR), got(text = "hi", kind = "text").menuActs())
        // a received link opens; a link this phone sent is copied
        assertEquals(LetterOffer(Act.OPEN_LINK), got(text = "https://a.com", kind = "url").primary(now))
        assertEquals(listOf(Act.OPEN_LINK, Act.COPY, Act.SHARE, Act.CLEAR), got(text = "https://a.com", kind = "url").menuActs())
        assertEquals(LetterOffer(Act.COPY), sent(text = "https://a.com", kind = "url").primary(now))
    }

    @Test fun `a letter with nothing in it offers only clearing`() {
        assertEquals(LetterOffer(Act.NONE), got().primary(now))
        assertEquals(listOf(Act.CLEAR), got().menuActs())
    }

    // ---- sent: again, and a send that stopped ----

    @Test fun `U-T29 sent files can be sent again - and not once the service has dropped them`() {
        val l = sent(file(1, FileState.SENT))
        assertEquals(LetterOffer(Act.RESEND), l.primary(now, resend = ResendAvail.OK))
        assertEquals(LetterOffer(Act.RESEND, enabled = false), l.primary(now, resend = ResendAvail.EXPIRED))
        assertEquals(LetterOffer(Act.NONE), l.primary(now, resend = ResendAvail.NONE))
        assertEquals(listOf(Act.RESEND, Act.CLEAR), l.menu(now, resend = ResendAvail.OK).map { it.act })
        assertEquals(false, l.menu(now, resend = ResendAvail.EXPIRED).first().enabled)
        assertEquals(listOf(Act.RESEND, Act.CLEAR), sent(file(1, FileState.SENT), file(2, FileState.SENT)).menu(now, resend = ResendAvail.OK).map { it.act })
    }

    @Test fun `U-T30 sent words are copied and can be sent again from the sheet first`() {
        val l = sent(text = "hi", kind = "text")
        assertEquals(LetterOffer(Act.COPY), l.primary(now, resend = ResendAvail.OK))
        assertEquals(listOf(Act.RESEND, Act.COPY, Act.SHARE, Act.CLEAR), l.menu(now, resend = ResendAvail.OK).map { it.act })
        assertEquals(listOf(Act.COPY, Act.SHARE, Act.CLEAR), l.menu(now).map { it.act })
        // four blocks at most: a link that can be sent again is not opened from its own sheet
        val link = sent(text = "https://a.com", kind = "url")
        assertEquals(listOf(Act.RESEND, Act.COPY, Act.SHARE, Act.CLEAR), link.menu(now, resend = ResendAvail.OK).map { it.act })
        assertEquals(listOf(Act.OPEN_LINK, Act.COPY, Act.SHARE, Act.CLEAR), link.menu(now).map { it.act })
    }

    @Test fun `U-T31 a send that stopped is retried or sent to everyone when its devices are gone or waits for a plan`() {
        val retry = sent(file(1, FileState.SENT), file(2, FileState.SENT)).copy(failure = SendFailure("offline", files = listOf(1)))
        assertEquals(LetterOffer(Act.RETRY_SEND), retry.primary(now))
        assertEquals(listOf(Act.RETRY_SEND, Act.CLEAR), retry.menu(now).map { it.act })

        val gone = sent(text = "hi", kind = "text").copy(failure = SendFailure("gone", words = true, target = true))
        assertEquals(LetterOffer(Act.SEND_ALL), gone.primary(now))
        assertEquals(listOf(Act.SEND_ALL, Act.COPY, Act.SHARE, Act.CLEAR), gone.menu(now).map { it.act })

        val limit = sent(text = "hi", kind = "text").copy(failure = SendFailure("limit", words = true, retry = false))
        assertEquals(LetterOffer(Act.RETRY_SEND, enabled = false), limit.primary(now))
        // nothing of "again" is offered on a card that never went
        assertEquals(LetterOffer(Act.RETRY_SEND), retry.primary(now, resend = ResendAvail.OK))
        assertEquals(listOf(Act.RETRY_SEND, Act.CLEAR), retry.menu(now, resend = ResendAvail.OK).map { it.act })
    }

    @Test fun `U-T32 what was received ignores sending again and failures of sending`() {
        val l = got(saved1)
        assertEquals(LetterOffer(Act.OPEN_FILE), l.primary(now, resend = ResendAvail.OK))
        assertEquals(listOf(Act.OPEN_FILE, Act.SHARE, Act.CLEAR), l.menu(now, resend = ResendAvail.OK).map { it.act })
        assertEquals(LetterOffer(Act.OPEN_FILE), l.copy(failure = SendFailure("x")).primary(now))
    }

    // ---- received, a drop of several ----

    @Test fun `a batch is several files or words with a file`() {
        assertEquals(false, got(fresh1).isBatch)
        assertEquals(true, got(fresh1, fresh2).isBatch)
        assertEquals(true, got(fresh1, text = "note", kind = "text").isBatch)
        assertEquals(false, got(text = "note", kind = "text").isBatch)
    }

    @Test fun `batch - files still to save are saved together`() {
        assertEquals(LetterOffer(Act.SAVE_ALL), got(fresh1, fresh2).primary(now))
        assertEquals(listOf(Act.SAVE_ALL, Act.SHARE, Act.CLEAR), got(fresh1, fresh2).menuActs())
        // some already saved: the rest still go together
        assertEquals(LetterOffer(Act.SAVE_ALL), got(saved1, fresh2).primary(now))
        // some expired: what is left can still be saved
        assertEquals(LetterOffer(Act.SAVE_ALL), got(gone1, fresh2).primary(now))
    }

    @Test fun `batch - everything saved is shared`() {
        assertEquals(LetterOffer(Act.SHARE), got(saved1, saved2).primary(now))
        assertEquals(listOf(Act.SHARE, Act.CLEAR), got(saved1, saved2).menuActs())
        assertEquals(LetterOffer(Act.SHARE), got(saved1, gone2).primary(now))   // what is on the phone goes on being shareable
    }

    @Test fun `batch - everything expired can only be cleared`() {
        assertEquals(LetterOffer(Act.CLEAR), got(gone1, gone2).primary(now))
        assertEquals(listOf(Act.CLEAR), got(gone1, gone2).menuActs())
    }

    @Test fun `batch - retry only when every file left failed and not when all of them hit today's limit`() {
        val l = got(fresh1, fresh2)
        assertEquals(LetterOffer(Act.RETRY), l.primary(now, failures = mapOf(1L to SaveFailure.OFFLINE, 2L to SaveFailure.FAILED)))
        assertEquals(LetterOffer(Act.RETRY, enabled = false), l.primary(now, failures = mapOf(1L to SaveFailure.LIMIT, 2L to SaveFailure.LIMIT)))
        assertEquals(LetterOffer(Act.RETRY), l.primary(now, failures = mapOf(1L to SaveFailure.LIMIT, 2L to SaveFailure.OFFLINE)))
        // one failed, one not tried yet: there is still something to save
        assertEquals(LetterOffer(Act.SAVE_ALL), l.primary(now, failures = mapOf(1L to SaveFailure.OFFLINE)))
        // a saved file does not count: the failed one is all that is left
        assertEquals(LetterOffer(Act.RETRY), got(saved1, fresh2).primary(now, failures = mapOf(2L to SaveFailure.OFFLINE)))
    }

    @Test fun `batch - unsupported files are not pending`() {
        val un = mapOf(1L to SaveFailure.UNSUPPORTED, 2L to SaveFailure.UNSUPPORTED)
        assertEquals(LetterOffer(Act.NONE), got(fresh1, fresh2).primary(now, failures = un))
        assertEquals(listOf(Act.CLEAR), got(fresh1, fresh2).menuActs(failures = un))
        // with one saved file there is still something to share
        assertEquals(LetterOffer(Act.SHARE), got(saved1, fresh2).primary(now, failures = mapOf(2L to SaveFailure.UNSUPPORTED)))
    }

    @Test fun `batch - saving anything makes the button busy`() {
        assertEquals(LetterOffer(Act.SAVING, enabled = false), got(fresh1, fresh2).primary(now, saving = setOf(2L)))
        assertEquals(listOf(Act.SAVING, Act.SHARE, Act.CLEAR), got(fresh1, fresh2).menuActs(saving = setOf(2L)))
    }

    @Test fun `batch with words - copying the words comes after the next step or is the step when nothing is left to save`() {
        assertEquals(LetterOffer(Act.SAVE_ALL), got(fresh1, text = "note", kind = "text").primary(now))
        assertEquals(listOf(Act.SAVE_ALL, Act.COPY, Act.SHARE, Act.CLEAR), got(fresh1, text = "note", kind = "text").menuActs())
        assertEquals(LetterOffer(Act.COPY), got(saved1, text = "note", kind = "text").primary(now))
        assertEquals(listOf(Act.COPY, Act.SHARE, Act.CLEAR), got(saved1, text = "note", kind = "text").menuActs())
        assertEquals(LetterOffer(Act.COPY), got(gone1, text = "note", kind = "text").primary(now))
        assertEquals(listOf(Act.COPY, Act.CLEAR), got(gone1, text = "note", kind = "text").menuActs())
    }

    @Test fun `a picture in a batch has no copy-image block`() {
        val l = got(file(1, FileState.SAVED, "image/png"), file(2, FileState.SAVED, "image/png"))
        assertEquals(listOf(Act.SHARE, Act.CLEAR), l.menuActs())
    }

    // ---- file views ----

    @Test fun `a file view lays saving and failure only over a file that can still be saved`() {
        val l = got(fresh1, saved2)
        assertEquals(FileView(FileState.AVAILABLE, saving = true), l.viewOf(l.files[0], now, saving = setOf(1L)))
        assertEquals(FileView(FileState.AVAILABLE, failure = SaveFailure.OFFLINE), l.viewOf(l.files[0], now, failures = mapOf(1L to SaveFailure.OFFLINE)))
        assertEquals(FileView(FileState.SAVED), l.viewOf(l.files[1], now, saving = setOf(2L), failures = mapOf(2L to SaveFailure.FAILED)))
        assertEquals(FileView(FileState.SENT), sent(file(1, FileState.SENT)).let { it.viewOf(it.files[0], now) })
    }

    @Test fun `a file whose end passes while the list is open turns expired`() {
        val f = LetterFile(name = "x", size = 1, seq = 1, expires = 5_000)
        assertEquals(FileState.AVAILABLE, f.state(4_999))
        assertEquals(FileState.EXPIRED, f.state(5_000))
        assertEquals(FileState.SAVED, f.copy(saved = "content://x").state(9_999))   // on the phone beats gone from the service
    }
}
