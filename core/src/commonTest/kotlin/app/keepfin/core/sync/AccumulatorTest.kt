package app.keepfin.core.sync

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// StateAccumulator is a faster joinAll: it must give exactly what folding State.join gives, in any order.
class AccumulatorTest {
    @Test
    fun accumulatorEqualsFoldedJoin() {
        for (seed in 0 until 30) {
            val deltas = OpGen(7000 + seed, 4).generate(200).map { it.toDelta() }
            var folded = State.EMPTY
            for (d in deltas) folded = folded.join(d)
            assertEquals(folded, joinAll(deltas), "seed=$seed in order")
            val shuffled = deltas.shuffled(Random(seed))
            assertEquals(folded, joinAll(shuffled), "seed=$seed shuffled")
            // Starting from a non-empty state, as a checkpoint base does.
            val half = deltas.size / 2
            var base = State.EMPTY
            for (d in deltas.take(half)) base = base.join(d)
            val acc = StateAccumulator(base)
            for (d in shuffled) acc.add(d)
            assertEquals(folded, acc.snapshot(), "seed=$seed from a base")
        }
    }
}
