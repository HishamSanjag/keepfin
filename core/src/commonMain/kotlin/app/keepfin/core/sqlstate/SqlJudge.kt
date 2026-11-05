package app.keepfin.core.sqlstate

import app.keepfin.core.sync.Interpretation
import app.keepfin.core.sync.Op
import app.keepfin.core.sync.OpDecode
import app.keepfin.core.sync.OpTextReader
import app.keepfin.core.sync.decodeOp
import app.keepfin.core.sync.judgeOp
import app.keepfin.core.sync.sha256Hex
import app.keepfin.core.sync.utf8LengthOrMinusOne

/** What admission needs to know about one op line, judged with no state (see [judgeLine]). */
sealed class Judged {
    /** The line cannot be read as an op; [problem] is the decode problem's name. */
    class Malformed(val problem: String) : Judged()

    /**
     * [kind]: 0 applied, 1 kept (an unknown kind), 2 invalid ([reason]). [hash] is set only when the text is not plain
     * UTF-8, so the host must not hash the text itself.
     */
    class Op(
        val actor: String,
        val seq: Long,
        val size: Int,
        val hlc: String,
        val hash: String?,
        val kind: Int,
        val reason: String?,
        val items: List<String>,
        val statements: List<SqlStatement>,
        /** The op itself when it is a valid membership op (kind 0): membership, not the state, reacts to it. */
        val memberOp: app.keepfin.core.sync.Op? = null,
    ) : Judged()
}

/** Judges one op line: decode, validate, interpret, and turn the delta into the statements that join it. */
fun judgeLine(opText: String): Judged {
    var size = utf8LengthOrMinusOne(opText)
    var op: Op? = if (size >= 0) OpTextReader(opText).read() else null
    var hash: String? = null
    if (op == null) {
        val bytes = opText.encodeToByteArray()
        when (val d = decodeOp(bytes)) {
            is OpDecode.Bad -> return Judged.Malformed(d.problem.name)
            is OpDecode.Ok -> op = d.op
        }
        size = bytes.size
        hash = sha256Hex(bytes)
    }
    return when (val judged = judgeOp(op, size)) {
        is Interpretation.Applied -> Judged.Op(
            op.id.actor, op.id.seq, size, op.hlc.wire(), hash, 0, null,
            judged.delta.items.keys.toList(), SqlState.deltaToSql(judged.delta),
            if (app.keepfin.core.sync.isMemberKind(op.kind)) op else null,
        )
        is Interpretation.Unknown -> Judged.Op(op.id.actor, op.id.seq, size, op.hlc.wire(), hash, 1, null, emptyList(), emptyList())
        is Interpretation.Invalid -> Judged.Op(op.id.actor, op.id.seq, size, op.hlc.wire(), hash, 2, judged.reason, emptyList(), emptyList())
    }
}
