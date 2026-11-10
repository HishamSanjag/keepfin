package app.keepfin.cloud

import android.content.Context
import android.content.Intent
import app.keepfin.copies.CloudHooks
import app.keepfin.copies.CloudCardUi
import app.keepfin.folder.FolderAccess
import app.keepfin.folder.FolderError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

internal val PROVIDER_NAMES = mapOf("drive" to "Google Drive", "graph" to "OneDrive", "dropbox" to "Dropbox", "webdav" to "WebDAV")
private val PROVIDER_ABOUT = mapOf(
    "drive" to "Keepfin sees only the files it creates itself.",
    "graph" to "Keepfin gets its own app folder and sees nothing else.",
    "dropbox" to "Keepfin gets its own app folder and sees nothing else.",
    "webdav" to "A folder on your own server (Nextcloud, a NAS, any WebDAV address).",
)

/**
 * Cloud accounts and the cloud copies of this phone: the accounts (tokens in the Keystore), the folders the copies engine opens over
 * them, and what the Copies screens show and do about them. One per Keepfin profile, made by `CopiesRuntime`.
 */
internal class CloudRuntime(
    private val context: Context?,
    deviceDir: File,
    private val ids: CloudIds = CloudIds.fromBuild(),
    box: SecretBox = KeystoreBox(),
    /** Sends provider URLs somewhere else (tests and the emulator interop point everything at local fakes). */
    private val rewrite: ((String) -> String)? = null,
    /** Replaces the network rule (tests). */
    private val gate: ((accountId: String, mobileData: Boolean) -> String?)? = null,
    /** The library's id and this phone's replica id, to recognise replicas on an account. */
    private val library: () -> Pair<String, String>? = { null },
    /** The copies this library lists: replica id -> place id. */
    private val knownPlaces: () -> Map<String, String> = { emptyMap() },
    /** The addresses of the cloud copies on this phone. */
    private val addresses: () -> List<String> = { emptyList() },
    private val now: () -> Long = System::currentTimeMillis,
) : CloudActions, CloudHooks {
    private val dir = File(deviceDir, "cloud").also { it.mkdirs() }
    private val spool = File(dir, "staging")
    private val tokens = TokenClient(rewrite, now)
    val accounts = CloudAccounts(File(dir, "accounts.json"), box, ids, tokens, now, googleRefresh = { context?.let { CloudSignIn.googleSilent(it) } })
    private val signIn: CloudSignIn? = context?.let { CloudSignIn(it, ids, tokens) }

    private class Entry(val http: CloudHttp, val client: CloudClient) { @Volatile var probed = false }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val opened = ConcurrentHashMap<String, CloudFolderAccess>()
    private val quotas = ConcurrentHashMap<String, Triple<Long?, Long?, Long>>()
    private val support = ConcurrentHashMap<String, List<String>>()
    private val flow = MutableStateFlow(CloudUi())
    override val state: StateFlow<CloudUi> = flow.asStateFlow()

    // ----------------------------------------------------------------------------------------------------- opening

    private fun entryFor(accountId: String): Entry {
        entries[accountId]?.let { return it }
        synchronized(this) {
            entries[accountId]?.let { return it }
            val view = accounts.get(accountId) ?: throw FolderError.NeedsAccess("This account is not signed in on this phone any more.")
            val acct = accounts.account(accountId, view.provider)
            val gateFn: () -> String? = {
                val mobile = accounts.get(accountId)?.mobileData == true
                gate?.invoke(accountId, mobile) ?: if (gate != null) null else context?.let { NetworkRule.reason(it, mobile) }
            }
            val http = CloudHttp(acct, HttpOptions(rewrite = rewrite, gate = gateFn, now = now))
            val client = CloudClient(http, accounts.webdav(accountId)?.first, FileJournal(File(dir, "uploads-$accountId.json")), now, spool)
            return Entry(http, client).also { entries[accountId] = it }
        }
    }

    /** A WebDAV server proves what it honours once per start (the probe leaves nothing behind). */
    private fun probe(accountId: String, e: Entry) {
        if (e.client.provider != "webdav" || e.probed) return
        try {
            e.client.probeWebdav(".keepfin-probe-${java.lang.Long.toString(now(), 36)}")
            e.probed = true
        } catch (x: CloudError) {
            // not reachable now: the capabilities stay conservative until the next open
        }
    }

    override fun open(address: String): FolderAccess {
        val (accountId, root) = CloudAddress.parse(address) ?: throw FolderError.UnsupportedProvider(address)
        val view = accounts.get(accountId) ?: throw FolderError.NeedsAccess("This account is not signed in on this phone any more.")
        if (view.needsSignIn) throw FolderError.NeedsAccess("Sign in again to keep using this account.")
        opened[address]?.let { return it }
        synchronized(this) {
            opened[address]?.let { return it }
            val e = entryFor(accountId)
            probe(accountId, e)
            val access = CloudFolderAccess(CloudTransport(e.client, root, now), e.client, spool, now)
            opened[address] = access
            return access
        }
    }

    override fun isCloud(address: String?): Boolean = CloudAddress.isCloud(address)

    override fun label(address: String): String {
        val (accountId, root) = CloudAddress.parse(address) ?: return address
        val a = accounts.get(accountId)
        val provider = PROVIDER_NAMES[a?.provider] ?: "Cloud"
        return "$provider · ${a?.label ?: "account"} / ${root.replace("/", " / ")}"
    }

    override fun card(address: String): CloudCardUi? {
        val (accountId, _) = CloudAddress.parse(address) ?: return null
        val a = accounts.get(accountId) ?: return CloudCardUi(accountId, "Cloud", "an account that is not on this phone", true, false)
        val waiting = if (a.needsSignIn) null else gate?.invoke(accountId, a.mobileData) ?: if (gate != null) null else context?.let { NetworkRule.reason(it, a.mobileData) }
        return CloudCardUi(accountId, PROVIDER_NAMES[a.provider] ?: "Cloud", a.label, a.needsSignIn, a.mobileData, waiting, a.provider)
    }

    // ---------------------------------------------------------------------------------------------- what the screens show

    override fun refresh() {
        val perAccount = addresses().mapNotNull { CloudAddress.parse(it)?.first }.groupingBy { it }.eachCount()
        val providers = listOf("drive", "graph", "dropbox", "webdav").map { CloudProviderUi(it, PROVIDER_NAMES.getValue(it), ids.has(it), if (ids.has(it)) PROVIDER_ABOUT.getValue(it) else "Not available in this build.") }
        val list = accounts.list().map { a ->
            val q = quotas[a.id]
            CloudAccountUi(
                a.id, a.provider, PROVIDER_NAMES[a.provider] ?: a.provider,
                if (a.provider == "webdav") "${a.user ?: ""} at ${UrlPolicy.hostOf(a.server)}".trim() else a.label,
                a.needsSignIn, a.mobileData, perAccount[a.id] ?: 0, q?.first, q?.second, support[a.id].orEmpty(),
            )
        }
        flow.value = CloudUi(providers, list)
    }

    /** Free and total space of the account, kept for ten minutes; the cards show it. */
    private fun quota(accountId: String, force: Boolean = false) {
        val q = quotas[accountId]
        if (q != null && !force && now() - q.third < 10 * 60_000) return
        val view = accounts.get(accountId) ?: return
        if (view.needsSignIn) return
        try {
            val got = entryFor(accountId).client.quota()
            quotas[accountId] = Triple(got.freeBytes, got.totalBytes, now())
            if (got.account != null && view.provider != "webdav" && view.label != got.account) accounts.setLabel(accountId, got.account)
        } catch (e: Exception) {
            // the card shows no number
        }
        refresh()
    }

    /** Refreshes the cards' numbers for every account, off the caller's thread. */
    fun refreshQuotas() {
        Thread { for (a in accounts.list()) quota(a.id) }.also { it.isDaemon = true }.start()
    }

    // ----------------------------------------------------------------------------------------------------- sign-in

    override fun beginSignIn(provider: String, accountId: String?): SignInStep {
        val s = signIn ?: return SignInStep.Failed("Signing in is not available here.")
        if (provider == "webdav") return SignInStep.Failed("WebDAV uses an address and an app password.")
        if (accountId != null && accounts.get(accountId)?.provider != provider) return SignInStep.Failed("This account is not on this phone any more.")
        val b = s.begin(provider)
        b.googleToken?.let { return completeToken(provider, accountId, it) }
        return b.step!!
    }

    override fun finishSignIn(provider: String, accountId: String?, result: Intent?): SignInStep {
        val s = signIn ?: return SignInStep.Failed("Signing in is not available here.")
        return try {
            val t = if (provider == "drive") (s.finishGoogle(result) ?: return SignInStep.Cancelled) else s.finishBrowser(provider, result)
            completeToken(provider, accountId, t)
        } catch (e: SignInException) {
            if (e.message == "The sign-in was cancelled.") SignInStep.Cancelled else SignInStep.Failed(e.message ?: "The sign-in failed.")
        } catch (e: CloudError) {
            SignInStep.Failed(if (e.kind == Kind.NEEDS_SIGN_IN) "The provider did not accept the sign-in. Try again." else "Keepfin could not reach the provider. Try again.")
        } catch (e: Exception) {
            SignInStep.Failed("The sign-in failed. ${e.message ?: ""}".trim())
        }
    }

    private fun completeToken(provider: String, accountId: String?, t: TokenSet): SignInStep {
        if (accountId != null) {
            val view = accounts.replaceTokens(accountId, t)
            entries.remove(accountId)
            opened.keys.filter { CloudAddress.parse(it)?.first == accountId }.forEach { opened.remove(it) }
            refresh()
            quota(accountId, force = true)
            return SignInStep.Done(uiOf(view))
        }
        val probe = accounts.addOAuth(provider, t, PROVIDER_NAMES.getValue(provider), play = provider == "drive")
        try {
            val q = entryFor(probe.id).client.quota()
            quotas[probe.id] = Triple(q.freeBytes, q.totalBytes, now())
            if (q.account != null) {
                val same = accounts.list().firstOrNull { it.id != probe.id && it.provider == provider && it.label == q.account }
                if (same != null) {
                    // the same account signed in a second time: one account per sign-in, so the new tokens replace the old
                    accounts.replaceTokens(same.id, t)
                    accounts.disconnect(probe.id)
                    entries.remove(probe.id)
                    entries.remove(same.id)
                    refresh()
                    return SignInStep.Done(uiOf(accounts.get(same.id)!!))
                }
                accounts.setLabel(probe.id, q.account)
            }
        } catch (e: Exception) {
            // the account is signed in even if its quota could not be read yet
        }
        refresh()
        return SignInStep.Done(uiOf(accounts.get(probe.id)!!))
    }

    private fun uiOf(a: AccountView): CloudAccountUi {
        refresh()
        return flow.value.accounts.first { it.id == a.id }
    }

    // ------------------------------------------------------------------------------------------------------- WebDAV

    override fun connectWebdav(url: String, user: String, password: String): WebdavConnected {
        val address = UrlPolicy.validateWebdav(url)
        if (user.isBlank()) throw SignInException("Enter the user name for the server.")
        if (password.isEmpty()) throw SignInException("Enter the app password for the server.")
        val account = accounts.addWebdav(address, user.trim(), password, "${user.trim()} at ${UrlPolicy.hostOf(address)}")
        try {
            val e = entryFor(account.id)
            try {
                if (e.client.stat(Ref(path = "")) == null) throw CloudError(Kind.NOT_FOUND, 404, op = "connect")
            } catch (x: CloudError) {
                when (x.kind) {
                    Kind.NEEDS_SIGN_IN -> throw SignInException("The server did not accept this user name and password.")
                    Kind.NOT_FOUND -> throw SignInException("The server answered, but there is nothing at this address.")
                    else -> throw SignInException("Keepfin could not reach the server at this address.")
                }
            }
            val verdict = try { e.client.probeWebdav(".keepfin-probe-${java.lang.Long.toString(now(), 36)}").also { e.probed = true } } catch (x: Exception) { null }
            val lines = describeProbe(verdict)
            support[account.id] = lines
            refresh()
            return WebdavConnected(uiOf(account), lines)
        } catch (e: Exception) {
            accounts.disconnect(account.id)
            entries.remove(account.id)
            throw e
        }
    }

    // ------------------------------------------------------------------------------------- disconnect, revoke, rules

    override fun disconnect(accountId: String) {
        accounts.disconnect(accountId)
        entries.remove(accountId)
        quotas.remove(accountId)
        support.remove(accountId)
        opened.keys.filter { CloudAddress.parse(it)?.first == accountId }.forEach { opened.remove(it) }
        refresh()
    }

    override fun removeAccess(accountId: String): RevokeNoteUi {
        val r = accounts.revoke(accountId)
        entries.remove(accountId)
        quotas.remove(accountId)
        support.remove(accountId)
        opened.keys.filter { CloudAddress.parse(it)?.first == accountId }.forEach { opened.remove(it) }
        refresh()
        return RevokeNoteUi(r.revoked, r.note, r.url)
    }

    override fun setMobileData(accountId: String, allowed: Boolean) {
        accounts.setMobileData(accountId, allowed)
        refresh()
    }

    // ---------------------------------------------------------------------------------------------------- replicas

    override fun find(accountId: String, webdavFolder: String?): List<FoundReplicaUi> {
        val view = accounts.get(accountId) ?: throw IllegalStateException("This account is not on this phone any more.")
        if (view.needsSignIn) throw IllegalStateException("Sign in again to look at this account.")
        val (libraryId, mine) = library() ?: return emptyList()
        val e = entryFor(accountId)
        probe(accountId, e)
        val known = knownPlaces()
        val out = ArrayList<FoundReplicaUi>()
        fun check(root: String, name: String) {
            val text = try { CloudTransport(e.client, root, now).readSmall("$KEEPFIN_DIR/folder.json") } catch (x: CloudError) { null } ?: return
            val json = try { JSONObject(text) } catch (x: Exception) { return }
            if (json.optString("libraryId") == libraryId && json.optString("replicaId").isNotEmpty() && json.optString("replicaId") != mine) {
                out.add(FoundReplicaUi(name, root, json.getString("replicaId"), known[json.getString("replicaId")]))
            }
        }
        if (view.provider == "webdav") {
            val f = webdavFolder?.trim('/')?.takeIf { it.isNotEmpty() } ?: return out
            check(f, f.substringAfterLast('/'))
            return out
        }
        val names = try { CloudTransport(e.client, "Keepfin", now).listChildFolders("") } catch (x: CloudError) { null } ?: emptyList()
        for (n in names.take(30)) check("Keepfin/$n", n)
        return out
    }

    override fun addressFor(accountId: String, folder: String): String {
        val a = accounts.get(accountId) ?: throw IllegalStateException("This account is not on this phone any more.")
        val clean = folder.trim('/').trim()
        return CloudAddress.make(accountId, if (a.provider == "webdav") clean.ifEmpty { "Keepfin" } else "Keepfin/${folderNameFor(clean)}")
    }

    /** Letters, digits, spaces and a few marks; never empty (the same rule as on the desktop). */
    override fun folderNameFor(name: String): String {
        val cleaned = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC).replace(Regex("[^\\p{L}\\p{N} ._()-]"), "").replace(Regex("\\s+"), " ").trim().trimStart('.').take(60).trim()
        return cleaned.ifEmpty { "Library" }
    }

    companion object {
        fun describeProbe(v: JSONObject?): List<String> {
            if (v == null) return listOf("Keepfin could not test this server. It will keep every earlier version and never overwrite a file in place.")
            val out = ArrayList<String>()
            out.add(if (v.optBoolean("cas")) "Checks that a file has not changed before replacing it." else "Does not check files before replacing them: Keepfin keeps the old bytes and never overwrites in place.")
            out.add(if (v.optBoolean("createOnly") || v.optBoolean("noReplaceMove")) "Never writes over a file that is already there." else "May write over a file that appeared at the same moment: only one device should sync at a time.")
            out.add(if (v.optBoolean("range")) "Can continue an interrupted download." else "Cannot continue an interrupted download: Keepfin starts the file again.")
            return out
        }
    }
}
