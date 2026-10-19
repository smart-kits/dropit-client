package xyz.smart_kits.dropit.shared

import kotlin.test.Test
import kotlin.test.assertEquals

class LettersTest {
    private fun letter(id: String, at: Long) = Letter(id = id, mine = false, at = at, text = id)

    @Test
    fun clearingTakesOnlyTheNamedLettersAndKeepsTheOrder() {
        val list = listOf(letter("c", 3), letter("b", 2), letter("a", 1))
        assertEquals(listOf("c", "a"), withoutLetters(list, setOf("b")).map { it.id })
        assertEquals(emptyList(), withoutLetters(list, setOf("a", "b", "c")))
        assertEquals(list, withoutLetters(list, setOf("zzz")))
        assertEquals(list, withoutLetters(list, emptySet()))
    }

    @Test
    fun aClearedLetterComesBackInItsPlace() {
        val list = listOf(letter("c", 3), letter("b", 2), letter("a", 1))
        val cleared = withoutLetters(list, setOf("b"))
        val back = withLetter(cleared, list[1], 10)
        assertEquals(listOf("c", "b", "a"), back.map { it.id })
    }
}
