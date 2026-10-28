package com.kivan.tether

import com.kivan.tether.CallLog.Piece
import org.junit.Assert.assertEquals
import org.junit.Test

class CallLogTest {
    private fun fold(vararg ps: Piece) = ps.fold(emptyList<Piece>(), CallLog::fold)

    @Test
    fun aSpeakersGrowingLineIsOnePieceWithAllOfIt() {
        val got = fold(
            Piece(1, "aviv", "Add dark mode", 10),
            Piece(1, "jarvis", "Sure.", 20),
            Piece(1, "jarvis", "Sure. I'll start on the piano app now.", 30),
            Piece(1, "jarvis", "Sure. I'll start on the piano app now. It should take about ten minutes.", 40),
            Piece(1, "aviv", "Thanks", 50),
        )
        assertEquals(
            listOf(
                Piece(1, "aviv", "Add dark mode", 10),
                Piece(1, "jarvis", "Sure. I'll start on the piano app now. It should take about ten minutes.", 20),
                Piece(1, "aviv", "Thanks", 50),
            ),
            got,
        )
    }

    @Test
    fun aNewCallStartsANewPiece() {
        val got = fold(Piece(1, "jarvis", "Bye.", 10), Piece(2, "jarvis", "Hello.", 20))
        assertEquals(2, got.size)
    }
}
