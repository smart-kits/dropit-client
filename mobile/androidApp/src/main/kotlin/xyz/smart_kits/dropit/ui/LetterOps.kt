package xyz.smart_kits.dropit.ui

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.smart_kits.dropit.R
import xyz.smart_kits.dropit.app
import xyz.smart_kits.dropit.receive.Downloader
import xyz.smart_kits.dropit.shared.Act
import xyz.smart_kits.dropit.shared.FileState
import xyz.smart_kits.dropit.shared.Letter
import xyz.smart_kits.dropit.shared.LetterFile
import xyz.smart_kits.dropit.shared.SaveFailure
import xyz.smart_kits.dropit.shared.state
import xyz.smart_kits.dropit.work.Outbox
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first

/**
 * What the list's buttons do, and what is in flight: files being saved, why a save failed, the one line a letter says about
 * what just happened. Every path ends in something the person can see (the row's own line, or the thing they asked for):
 * nothing returns quietly. The screen reads [saving], [failures], [notes] and [copied]; the actions come from the shared
 * `primary` / `menu`.
 */
class LetterOps(private val ctx: Context, private val scope: CoroutineScope) {
    /** The seqs being saved now */
    var saving by mutableStateOf(setOf<Long>())
        private set

    /** The last try at each file that did not work; cleared when it is tried again */
    val failures = mutableStateMapOf<Long, SaveFailure>()

    /** A line a letter says in red until it is stale: what was asked of it could not be done */
    val notes = mutableStateMapOf<String, Int>()

    /** When today's downloads start again (epoch ms), once the service has said; null until a save meets the limit */
    var limitUntil by mutableStateOf<Long?>(null)
        private set

    /** The letter whose words were just copied: its button shows a check for a moment */
    var copied by mutableStateOf<String?>(null)
        private set

    /** The letters whose send is being tried again now: their button is a spinner until the try comes back */
    var retrying by mutableStateOf(setOf<String>())
        private set

    /** Opens the panel for sending a letter again: the screen sets it (it owns the panel) */
    var onResend: (Letter) -> Unit = {}

    fun note(letterId: String, text: Int) {
        notes[letterId] = text
        scope.launch { delay(5_000); if (notes[letterId] == text) notes.remove(letterId) }
    }

    private fun find(letterId: String, seq: Long): LetterFile? =
        ctx.app.letters.all.value.firstOrNull { it.id == letterId }?.files?.firstOrNull { it.seq == seq }

    private fun fail(seq: Long, why: SaveFailure): LetterFile? { failures[seq] = why; return null }

    /** Downloads are used up: say when they start again, and let the button work again from then on */
    private fun limitHit(seq: Long, retryAfterSec: Long?): LetterFile? {
        if (retryAfterSec != null) {
            val ms = retryAfterSec.coerceIn(0, 86_400) * 1000
            limitUntil = System.currentTimeMillis() + ms
            scope.launch {
                delay(ms)
                failures.entries.removeAll { it.value == SaveFailure.LIMIT }
            }
        }
        return fail(seq, SaveFailure.LIMIT)
    }

    /**
     * The file on the phone, saving it first if it is not there yet (sharing and opening do not ask the person to save first).
     * Null when it cannot be had; the row says why.
     */
    suspend fun ensureSaved(letter: Letter, seq: Long): LetterFile? {
        val file = find(letter.id, seq) ?: run { note(letter.id, R.string.file_failed); return null }
        if (letter.mine) { note(letter.id, R.string.file_failed); return null }
        file.saved?.let { uri ->
            if (withContext(Dispatchers.IO) { ctx.app.downloader.stillThere(uri) }) return file
            ctx.app.letters.update(letter.id, seq) { it.copy(saved = null) }
            note(letter.id, R.string.file_gone)
            return null
        }
        if (file.state(System.currentTimeMillis()) == FileState.EXPIRED) { note(letter.id, R.string.st_expired); return null }
        saving = saving + seq
        failures.remove(seq); notes.remove(letter.id)
        val result = try { ctx.app.downloader.save(letter, file) } finally { saving = saving - seq }
        return when (result) {
            is Downloader.Result.Saved -> find(letter.id, seq)
            Downloader.Result.Expired -> { note(letter.id, R.string.st_expired); null }
            is Downloader.Result.LimitToday -> limitHit(seq, result.retryAfterSec)
            Downloader.Result.Offline -> fail(seq, SaveFailure.OFFLINE)
            Downloader.Result.Unsupported -> fail(seq, SaveFailure.UNSUPPORTED)
            Downloader.Result.Failed -> fail(seq, SaveFailure.FAILED)
        }
    }

    /** Files that can still be saved, tried one after another; each shows its own state meanwhile */
    fun saveAll(letter: Letter) {
        val now = System.currentTimeMillis()
        val todo = letter.files.filter { !letter.mine && it.state(now) == FileState.AVAILABLE && failures[it.seq] != SaveFailure.UNSUPPORTED }
        val seqs = todo.mapNotNull { it.seq }.toSet()
        if (seqs.isEmpty()) { note(letter.id, R.string.file_failed); return }
        saving = saving + seqs
        scope.launch {
            try {
                for (s in seqs) ensureSaved(letter, s)
            } finally {
                saving = saving - seqs
            }
        }
    }

    fun open(letter: Letter, file: LetterFile) {
        val seq = file.seq ?: run { note(letter.id, R.string.file_failed); return }
        scope.launch {
            val saved = ensureSaved(letter, seq) ?: return@launch
            if (!ctx.openSaved(saved)) note(letter.id, R.string.no_app_open)
        }
    }

    fun copyText(letter: Letter) {
        val text = letter.text ?: run { note(letter.id, R.string.file_failed); return }
        ctx.copy(text)
        flash(letter.id)
    }

    fun copyImage(letter: Letter, file: LetterFile) {
        val seq = file.seq ?: run { note(letter.id, R.string.file_failed); return }
        scope.launch {
            val saved = ensureSaved(letter, seq) ?: return@launch
            if (ctx.copyImage(saved)) flash(letter.id) else note(letter.id, R.string.file_gone)
        }
    }

    /** Several texts as one, oldest first, a blank line between: for copying or sharing a selection */
    fun joinedText(letters: List<Letter>): String? =
        letters.sortedBy { it.at }.mapNotNull { it.text }.takeIf { it.isNotEmpty() }?.joinToString("\n\n")

    fun copyTexts(letters: List<Letter>) {
        ctx.copy(joinedText(letters) ?: return)
    }

    /**
     * Files go out as they are on the phone, so each is saved first; if one of them cannot be, nothing is shared and its row says why.
     * Words alone go as text; words with files travel with them.
     */
    fun share(letters: List<Letter>) {
        scope.launch {
            val now = System.currentTimeMillis()
            val files = mutableListOf<LetterFile>()
            for (l in letters) {
                if (l.mine) continue
                for (f in l.files) {
                    val seq = f.seq ?: continue
                    if (f.state(now) == FileState.EXPIRED) continue
                    files += ensureSaved(l, seq) ?: return@launch   // ensureSaved has said why on the row
                }
            }
            val text = joinedText(letters)
            if (files.isEmpty()) {
                if (text != null) ctx.shareText(text)
                else letters.firstOrNull()?.let { l ->
                    // Nothing to hand over: only files that expired, or that this phone sent and keeps no copy of
                    note(l.id, if (l.files.any { !l.mine && it.state(now) == FileState.EXPIRED }) R.string.st_expired else R.string.share_nothing)
                }
                return@launch
            }
            if (!ctx.shareFiles(files, text)) letters.firstOrNull()?.let { note(it.id, R.string.no_app_open) }
        }
    }

    /** What a button does. Clearing belongs to the screen, which holds the undo. */
    fun perform(act: Act, letter: Letter, clear: () -> Unit) {
        when (act) {
            Act.COPY -> copyText(letter)
            Act.OPEN_LINK -> if (letter.text?.let(ctx::openLink) != true) note(letter.id, R.string.no_browser)
            Act.SAVE, Act.SAVE_ALL, Act.RETRY -> saveAll(letter)
            Act.OPEN_FILE -> letter.files.firstOrNull()?.let { open(letter, it) }
            Act.SHARE -> share(listOf(letter))
            Act.COPY_IMAGE -> letter.files.firstOrNull()?.let { copyImage(letter, it) }
            Act.CLEAR -> clear()
            Act.RESEND -> onResend(letter)
            Act.RETRY_SEND -> retrySend(letter, toAll = false)
            Act.SEND_ALL -> retrySend(letter, toAll = true)
            Act.SAVING, Act.NONE -> {}
        }
    }

    /**
     * A send that stopped goes on from where it stopped: the held copies are sent again, and only what did not go (the service already
     * has the rest). [toAll]: the devices it was addressed to are gone, so what was held goes to everyone this time. The card is
     * replaced by the sent letter when it works, and says why when it does not.
     */
    fun retrySend(letter: Letter, toAll: Boolean) {
        val outbox = Outbox(ctx)
        val job = outbox.load(letter.id) ?: run { note(letter.id, R.string.retry_gone); return }
        if (letter.id in retrying) return
        retrying = retrying + letter.id
        scope.launch {
            try {
                if (toAll) outbox.save(job.copy(to = emptyList(), toNames = emptyList()))
                // This try, by its own number: the unique name still holds the earlier, failed one until the new one has taken its place
                val run = outbox.enqueue(letter.id)
                WorkManager.getInstance(ctx).getWorkInfoByIdFlow(run).first { it?.state?.isFinished == true }
            } finally {
                retrying = retrying - letter.id
            }
        }
    }

    /** One file of a batch: saved, open it; not yet, save it */
    fun tapFile(letter: Letter, file: LetterFile) {
        if (file.saved != null) open(letter, file) else {
            val seq = file.seq ?: run { note(letter.id, R.string.file_failed); return }
            scope.launch { ensureSaved(letter, seq) }
        }
    }

    private fun flash(letterId: String) {
        copied = letterId
        scope.launch { delay(1_600); if (copied == letterId) copied = null }
    }
}
