package xyz.smart_kits.dropit.shared

// A QR code encoder for exactly what dropit needs: versions 1–5, error correction L, byte mode — up to 106
// bytes, and a pairing link is about 40. At this size every code is a single error-correction block, so
// nothing is interleaved, and there is at most one alignment pattern.
//
// It is the web inbox's encoder, line for line. The tests pin the same fingerprints as the web tests, which
// were checked bit by bit against an independent encoder and by actually decoding the picture.

object Qr {
    /** version → (data codewords, error-correction codewords), at level L */
    private val VERSIONS = mapOf(1 to (19 to 7), 2 to (34 to 10), 3 to (55 to 15), 4 to (80 to 20), 5 to (108 to 26))
    private val ALIGN_AT = mapOf(2 to 18, 3 to 22, 4 to 26, 5 to 30)      // version 1 has no alignment pattern
    private const val ECC_L = 0b01
    private val PAD = intArrayOf(0xEC, 0x11)

    // ── GF(256) ──
    private val EXP = IntArray(512)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x
            LOG[x] = i
            x = (x shl 1) xor (if (x and 0x80 != 0) 0x11d else 0)
        }
        for (i in 255 until 512) EXP[i] = EXP[i - 255]
    }

    private fun mul(a: Int, b: Int) = if (a != 0 && b != 0) EXP[LOG[a] + LOG[b]] else 0

    private fun generator(n: Int): IntArray {
        var poly = intArrayOf(1)
        for (i in 0 until n) {
            val next = IntArray(poly.size + 1)
            // Times (x + α^i). The coefficients run from the highest power down, so "times x" stays in place
            // and "times α^i" moves one along. The other way round gives the mirrored polynomial: the code
            // still draws, and never scans.
            poly.forEachIndexed { j, c ->
                next[j] = next[j] xor c
                next[j + 1] = next[j + 1] xor mul(c, EXP[i])
            }
            poly = next
        }
        return poly
    }

    internal fun ecc(data: IntArray, n: Int): IntArray {
        val gen = generator(n)
        val out = IntArray(n)
        for (byte in data) {
            val factor = byte xor out[0]
            for (i in 0 until n - 1) out[i] = out[i + 1]
            out[n - 1] = 0
            if (factor != 0) for (i in 0 until n) out[i] = out[i] xor mul(gen[i + 1], factor)
        }
        return out
    }

    // ── Data ──
    private fun encode(text: String): Pair<Int, IntArray> {
        val bytes = text.encodeToByteArray()
        val version = VERSIONS.keys.sorted().firstOrNull { bytes.size + 2 <= VERSIONS.getValue(it).first }
            ?: throw IllegalArgumentException("Too long for this encoder: ${bytes.size} bytes, at most 106")
        val (dataLen, eccLen) = VERSIONS.getValue(version)
        val bits = ArrayList<Int>()
        fun push(value: Int, n: Int) { for (i in n - 1 downTo 0) bits += (value shr i) and 1 }
        push(0b0100, 4)                 // byte mode
        push(bytes.size, 8)             // the count is 8 bits wide for versions 1–9
        for (b in bytes) push(b.toInt() and 0xFF, 8)
        push(0, minOf(4, dataLen * 8 - bits.size))      // terminator
        while (bits.size % 8 != 0) bits += 0
        val codewords = ArrayList<Int>()
        for (i in bits.indices step 8) codewords += bits.subList(i, i + 8).fold(0) { n, b -> (n shl 1) or b }
        // Padding always starts with 0xEC, whatever the length so far
        var k = 0
        while (codewords.size < dataLen) codewords += PAD[k++ % 2]
        val data = codewords.toIntArray()
        return version to (data + ecc(data, eccLen))
    }

    // ── Modules ──
    private val FINDER = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 1, 1), intArrayOf(1, 0, 0, 0, 0, 0, 1), intArrayOf(1, 0, 1, 1, 1, 0, 1), intArrayOf(1, 0, 1, 1, 1, 0, 1),
        intArrayOf(1, 0, 1, 1, 1, 0, 1), intArrayOf(1, 0, 0, 0, 0, 0, 1), intArrayOf(1, 1, 1, 1, 1, 1, 1),
    )

    /** -1 where nothing is placed yet */
    private fun skeleton(version: Int): Array<IntArray> {
        val size = 17 + version * 4
        val m = Array(size) { IntArray(size) { -1 } }
        fun put(r: Int, c: Int, v: Int) { if (r in 0 until size && c in 0 until size) m[r][c] = v }
        for ((br, bc) in listOf(0 to 0, 0 to size - 7, size - 7 to 0)) {
            FINDER.forEachIndexed { r, row -> row.forEachIndexed { c, v -> put(br + r, bc + c, v) } }
            for (i in -1..7) { put(br + i, bc - 1, 0); put(br + i, bc + 7, 0) }
            for (i in -1..7) { put(br - 1, bc + i, 0); put(br + 7, bc + i, 0) }
        }
        for (i in 8 until size - 8) {           // timing patterns
            val v = if (i % 2 == 0) 1 else 0
            m[6][i] = v
            m[i][6] = v
        }
        m[size - 8][8] = 1                       // the fixed dark module
        ALIGN_AT[version]?.let { a ->
            for (r in -2..2) for (c in -2..2) m[a + r][a + c] = if (maxOf(kotlin.math.abs(r), kotlin.math.abs(c)) != 1) 1 else 0
        }
        return m
    }

    /** The format information's cells are claimed first, or data would be laid into them */
    private fun formatCells(size: Int): List<Pair<Int, Int>> = buildList {
        for (i in 0..5) { add(8 to i); add(i to 8) }
        add(8 to 7); add(8 to 8); add(7 to 8)
        for (i in 0 until 8) add(8 to size - 1 - i)
        for (i in 0 until 7) add(size - 1 - i to 8)
    }

    private fun placeData(m: Array<IntArray>, codewords: IntArray) {
        val size = m.size
        val bits = ArrayList<Int>(codewords.size * 8)
        for (b in codewords) for (i in 7 downTo 0) bits += (b shr i) and 1
        var idx = 0
        var upward = true
        var right = size - 1
        while (right > 0) {
            if (right == 6) right = 5            // skip the column the vertical timing pattern is in
            for (step in 0 until size) {
                val row = if (upward) size - 1 - step else step
                for (col in intArrayOf(right, right - 1)) {
                    if (m[row][col] == -1) m[row][col] = bits.getOrElse(idx++) { 0 }
                }
            }
            upward = !upward
            right -= 2
        }
    }

    private val MASKS: List<(Int, Int) -> Boolean> = listOf(
        { r, c -> (r + c) % 2 == 0 },
        { r, _ -> r % 2 == 0 },
        { _, c -> c % 3 == 0 },
        { r, c -> (r + c) % 3 == 0 },
        { r, c -> (r / 2 + c / 3) % 2 == 0 },
        { r, c -> (r * c) % 2 + (r * c) % 3 == 0 },
        { r, c -> ((r * c) % 2 + (r * c) % 3) % 2 == 0 },
        { r, c -> ((r + c) % 2 + (r * c) % 3) % 2 == 0 },
    )

    /** BCH(15,5), then XOR 0x5412, as the standard says */
    internal fun formatBits(mask: Int): Int {
        val data = (ECC_L shl 3) or mask
        var rem = data shl 10
        for (i in 4 downTo 0) if ((rem shr (i + 10)) and 1 != 0) rem = rem xor (0x537 shl i)
        return ((data shl 10) or rem) xor 0x5412
    }

    /**
     * The format information is drawn twice, and the coordinates are easy to mirror by mistake: the first
     * copy runs down beside the top-left finder and then left along it; the second runs along the top-right
     * and up the bottom-left. Both start from the lowest bit.
     */
    private fun applyFormat(m: Array<IntArray>, mask: Int) {
        val size = m.size
        val bits = formatBits(mask)
        fun at(i: Int) = (bits shr i) and 1
        for (i in 0..5) m[i][8] = at(i)
        m[7][8] = at(6)
        m[8][8] = at(7)
        m[8][7] = at(8)
        for (i in 9..14) m[8][14 - i] = at(i)
        for (i in 0..7) m[8][size - 1 - i] = at(i)
        for (i in 8..14) m[size - 15 + i][8] = at(i)
    }

    // ── Mask scoring: the standard's four penalties ──
    private fun penalty(m: Array<IntArray>): Int {
        val size = m.size
        var score = 0
        fun runs(get: (Int, Int) -> Int) {
            for (a in 0 until size) {
                var run = 1
                for (b in 1 until size) {
                    if (get(a, b) == get(a, b - 1)) run++
                    else { if (run >= 5) score += run - 2; run = 1 }
                }
                if (run >= 5) score += run - 2
            }
        }
        runs { r, c -> m[r][c] }
        runs { c, r -> m[r][c] }
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            val v = m[r][c]
            if (v == m[r][c + 1] && v == m[r + 1][c] && v == m[r + 1][c + 1]) score += 3
        }
        // 1011101 0000, both ways round
        val bad = listOf(intArrayOf(1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0), intArrayOf(0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1))
        for (pat in bad) for (a in 0 until size) {
            var b = 0
            while (b + 10 < size) {
                if (pat.indices.all { m[a][b + it] == pat[it] }) score += 40
                if (pat.indices.all { m[b + it][a] == pat[it] }) score += 40
                b++
            }
        }
        val dark = m.sumOf { row -> row.count { it != 0 } }
        // The same floating-point steps as the web encoder, so the two never round apart
        score += kotlin.math.floor(kotlin.math.abs(dark * 100.0 / (size * size) - 50) / 5).toInt() * 10
        return score
    }

    /** The code as rows of 0 and 1; 1 is dark. `mask` is for tests only. */
    fun matrix(text: String, mask: Int? = null): Array<IntArray> {
        val (version, codewords) = encode(text)
        val base = skeleton(version)
        for ((r, c) in formatCells(base.size)) if (base[r][c] == -1) base[r][c] = 0
        val reserved = Array(base.size) { r -> BooleanArray(base.size) { c -> base[r][c] != -1 } }
        placeData(base, codewords)
        var best: Array<IntArray>? = null
        var bestScore = Int.MAX_VALUE
        for (k in mask?.let(::listOf) ?: (0..7).toList()) {
            val candidate = Array(base.size) { r -> IntArray(base.size) { c -> if (!reserved[r][c] && MASKS[k](r, c)) base[r][c] xor 1 else base[r][c] } }
            applyFormat(candidate, k)
            val score = penalty(candidate)
            if (best == null || score < bestScore) { best = candidate; bestScore = score }
        }
        return best!!
    }
}
