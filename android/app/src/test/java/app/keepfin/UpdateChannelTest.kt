package app.keepfin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateChannelTest {
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = tag.contains("-"), apk: String? = "Keepfin-Android-${tag.removePrefix("v")}.apk"): JSONObject {
        val assets = JSONArray()
        if (apk != null) assets.put(
            JSONObject().put("name", apk).put("size", 1000)
                .put("browser_download_url", "https://github.com/HishamSanjag/keepfin/releases/download/$tag/$apk"),
        )
        return JSONObject().put("tag_name", tag).put("draft", draft).put("prerelease", prerelease).put("body", "notes $tag").put("assets", assets)
    }

    private fun list(vararg items: JSONObject) = JSONArray().also { a -> items.forEach { a.put(it) } }

    @Test fun missingOrUnknownChannelMeansStable() {
        assertEquals(UpdateChannel.STABLE, UpdateChannel.of(null))
        assertEquals(UpdateChannel.STABLE, UpdateChannel.of("nightly"))
        assertEquals(UpdateChannel.BETA, UpdateChannel.of("beta"))
    }

    @Test fun feedsFollowTheChannel() {
        assertEquals("https://api.github.com/repos/HishamSanjag/keepfin/releases/latest", UpdateRelease.feed(UpdateChannel.STABLE))
        assertEquals("https://api.github.com/repos/HishamSanjag/keepfin/releases?per_page=10", UpdateRelease.feed(UpdateChannel.BETA))
    }

    @Test fun stableRejectsPrereleasesAndBetaTags() {
        assertEquals("0.5.0", UpdateRelease.parse(release("v0.5.0")).version)
        assertTrue(runCatching { UpdateRelease.parse(release("v0.5.0-beta.1")) }.isFailure)
        assertTrue(runCatching { UpdateRelease.parse(release("v0.5.0", prerelease = true)) }.isFailure)
    }

    @Test fun betaAcceptsPrereleaseTagsAndStableTags() {
        assertEquals("0.5.0-beta.2", UpdateRelease.parse(release("v0.5.0-beta.2"), UpdateChannel.BETA).version)
        assertEquals("0.5.0", UpdateRelease.parse(release("v0.5.0"), UpdateChannel.BETA).version)
    }

    @Test fun betaPicksTheNewestByReleaseOrderingRegardlessOfListOrder() {
        val picked = UpdateRelease.select(list(release("v0.4.2"), release("v0.5.0-beta.1"), release("v0.5.0-beta.2")), UpdateChannel.BETA)
        assertEquals("0.5.0-beta.2", picked?.version)
        val withFinal = UpdateRelease.select(list(release("v0.5.0-beta.2"), release("v0.5.0"), release("v0.4.2"), release("v0.5.0-beta.1")), UpdateChannel.BETA)
        assertEquals("0.5.0", withFinal?.version)
        val tenth = UpdateRelease.select(list(release("v0.4.9"), release("v0.4.10")), UpdateChannel.BETA)
        assertEquals("0.4.10", tenth?.version)
    }

    @Test fun draftsAreSkipped() {
        val picked = UpdateRelease.select(list(release("v0.5.0-beta.3", draft = true), release("v0.5.0-beta.1")), UpdateChannel.BETA)
        assertEquals("0.5.0-beta.1", picked?.version)
    }

    @Test fun malformedTagsAndReleasesWithoutTheRightApkAreRejected() {
        val bad = list(
            release("v0.9.0-rc.1"), release("0.9.0"), release("v0.9"), release("v1.0.0-beta"), release("v1.0.0-beta.x"),
            release("v2.0.0", apk = null), release("v2.1.0", apk = "Keepfin-Android-9.9.9.apk"), release("v0.5.0-beta.1"),
        )
        assertEquals("0.5.0-beta.1", UpdateRelease.select(bad, UpdateChannel.BETA)?.version)
        assertNull(UpdateRelease.select(list(release("v0.9.0-rc.1"), release("v3.0.0", apk = null)), UpdateChannel.BETA))
        assertNull(UpdateRelease.select(JSONArray(), UpdateChannel.BETA))
    }

    @Test fun anApkFromAnotherTagOrHostIsRejected() {
        val hostile = release("v0.6.0-beta.1")
        hostile.getJSONArray("assets").getJSONObject(0).put("browser_download_url", "https://evil.example/releases/download/v0.6.0-beta.1/Keepfin-Android-0.6.0-beta.1.apk")
        assertEquals("0.5.0-beta.1", UpdateRelease.select(list(hostile, release("v0.5.0-beta.1")), UpdateChannel.BETA)?.version)
    }

    @Test fun neverOffersADowngrade() {
        // Beta build on the stable channel: the latest stable is older, so nothing is newer.
        assertFalse(UpdateRelease.newer("0.4.2", "0.5.0-beta.1"))
        // Stable build on the beta channel: an earlier beta of the same version is not newer.
        assertFalse(UpdateRelease.newer("0.5.0-beta.2", "0.5.0"))
        assertTrue(UpdateRelease.newer("0.5.0", "0.5.0-beta.2"))
        assertTrue(UpdateRelease.isPrerelease("0.5.0-beta.1"))
        assertFalse(UpdateRelease.isPrerelease("0.5.0"))
    }
}
