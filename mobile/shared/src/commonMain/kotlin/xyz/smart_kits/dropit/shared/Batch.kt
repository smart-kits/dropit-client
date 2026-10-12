package xyz.smart_kits.dropit.shared

// One drop from a phone: some text and/or some files, sent as one batch.
// A share sheet can't be edited the way the extension's popup can, so instead of refusing the whole
// drop, what can't go (an 11th file, a file over the plan's limit) is left out and said so.

/** A file as the app holds it after copying it in: a name for people, its type and size */
data class FileInfo(val name: String?, val mime: String?, val size: Long)

sealed interface Item {
    val group: Group?

    data class Text(val kind: String, val raw: String, val meta: Meta?, override val group: Group?) : Item
    data class File(val index: Int, val info: FileInfo, val from: From?, override val group: Group?) : Item
}

data class BatchPlan(
    val items: List<Item>,
    /** Files past the first [FILES_MAX], by their index in what was shared */
    val overCount: List<Int>,
    /** Files over the plan's single-item limit, by index */
    val tooLarge: List<Int>,
) {
    val isEmpty get() = items.isEmpty()
}

/**
 * Work out what to send. `title` is what the sharing app called it (a page title); it goes with a link,
 * or as the source of selected text when `fromUrl` is known. `itemLimit` comes from the service
 * (`/v1/me` item_bytes_limit); null when unknown — the service still refuses what is too large.
 * `groupId` is kept by the caller so a retry later sends the rest under the same batch.
 */
fun planBatch(
    text: String?,
    files: List<FileInfo>,
    title: String? = null,
    fromUrl: String? = null,
    itemLimit: Long? = null,
    groupId: String = randomGroupId(),
): BatchPlan {
    val body = text?.takeIf { it.isNotBlank() }
    val over = files.indices.drop(FILES_MAX)
    val kept = files.indices.take(FILES_MAX)
    val tooLarge = kept.filter { itemLimit != null && files[it].size > itemLimit }
    val sendable = kept - tooLarge.toSet()
    val groups = groupsFor((if (body != null) 1 else 0) + sendable.size, groupId)
    val from = fromMeta(fromUrl, title)
    val items = buildList {
        var g = 0
        if (body != null) {
            val kind = kindOf(body)
            val meta = when {
                kind == "url" -> Meta(title = clip(squash(title), TITLE_MAX).ifEmpty { null })
                else -> Meta(from = from)
            }.copy(group = groups[g])
            add(Item.Text(kind, rawFor(kind, body), fitMeta(meta), groups[g++]))
        }
        for (i in sendable) add(Item.File(i, files[i], from, groups[g++]))
    }
    return BatchPlan(items, over, tooLarge)
}
