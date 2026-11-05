package app.keepfin.folder

import app.keepfin.core.sqlstate.PartSource
import app.keepfin.core.sync.Admission
import app.keepfin.core.sync.CheckpointPart
import app.keepfin.core.sync.Hlc
import app.keepfin.core.sync.Observed
import app.keepfin.core.sync.OpLog
import app.keepfin.core.sync.PartResult
import app.keepfin.core.sync.decodePart

/** The in-memory core [OpLog] as a merge state: the oracle the SQL state is checked against, and the state of the store tests. */class OpLogState : MergeState {
    private var log = OpLog()

    override fun admit(lines: List<String>): List<AdmitKind> = lines.map { kindOf(log.admit(it)) }

    private fun kindOf(a: Admission): AdmitKind = when (a) {
        is Admission.Admitted -> AdmitKind.Admitted
        is Admission.Duplicate -> AdmitKind.Duplicate
        is Admission.Kept -> AdmitKind.Kept
        is Admission.Invalid -> AdmitKind.Invalid
        is Admission.Equivocation -> AdmitKind.Equivocation
        is Admission.SetAside -> AdmitKind.SetAside
        // set aside by membership (unsigned after genesis, unauthorized, revoked): kept, never applied
        is Admission.SetAsideBy -> AdmitKind.SetAside
        is Admission.Covered -> AdmitKind.Covered
        is Admission.Malformed -> AdmitKind.Malformed
    }

    override fun versionVector(): Map<String, Long> = log.versionVector

    override fun maxHlc(): Hlc? = log.maxHlc

    override fun loadParts(parts: List<PartSource>, libraryId: String, equivocations: Map<String, Long>): Boolean {
        val decoded = ArrayList<CheckpointPart>()
        for (source in parts) {
            val r = decodePart(source.bytes(), libraryId)
            if (r is PartResult.Ok) decoded += r.part // an unreadable part is only a cache miss
        }
        if (decoded.isEmpty()) return false
        val loaded = OpLog.fromParts(decoded, libraryId, equivocations) ?: return false
        log = loaded
        return true
    }

    override fun dirtyShards(): List<String> = log.dirtyShards()

    override fun partBytes(libraryId: String, shard: String): ByteArray? = log.partBytes(libraryId, shard)

    override fun findings(): Set<String> = log.findings

    override fun viewsJson(): String = log.viewsJson()

    override fun observed(items: List<String>, playlists: List<String>, spaces: List<String>, setElements: List<Pair<String, String>>): Observed =
        log.observed(items, playlists, spaces, setElements)
}
