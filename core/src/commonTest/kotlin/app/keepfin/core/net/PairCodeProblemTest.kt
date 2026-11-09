package app.keepfin.core.net

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairCodeProblemTest {
    @Test fun aReadableCodeHasNoProblem() {
        for (t in listOf("7Q4MX-2XKDP", "7q4mx 2xkdp", " 7Q4MX2XKDP ", "7Q4MX-2XKDO", "IL4MX-2XKDP")) {
            assertNull(pairCodeProblem(t), t)
            assertNotNull(parsePairCode(t), t)
        }
    }

    @Test fun problemsAreInWords() {
        assertEquals("Type the code shown on the other device.", pairCodeProblem("  - "))
        assertTrue(pairCodeProblem("7Q4MX-2XKD")!!.startsWith("That code is too short"))
        assertTrue(pairCodeProblem("7Q4MX-2XKDPP")!!.startsWith("That code is too long"))
        assertTrue(pairCodeProblem("7Q4MU-2XKDP")!!.startsWith("That is not a Keepfin code"))
        assertTrue(pairCodeProblem("192.168.1.20")!!.startsWith("That is not a Keepfin code"))
    }

    @Test fun theProblemAndTheParserAgree() {
        for (t in listOf("", "7Q4MX", "7Q4MX-2XKDP", "U7Q4MX2XKDP", "7Q4MX-2XKD#", "0000000000", "zzzzzzzzzz")) {
            assertEquals(parsePairCode(t) == null, pairCodeProblem(t) != null, t)
        }
    }
}
