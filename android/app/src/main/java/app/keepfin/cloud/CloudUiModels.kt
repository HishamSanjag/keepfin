package app.keepfin.cloud

import android.content.Intent
import android.content.IntentSender
import kotlinx.coroutines.flow.StateFlow

// What the Copies screens know about cloud accounts. Immutable; nothing here is a token or a password.

/** A provider the person can choose. [available] is false when this build has no client id for it ("Not available in this build"). */
data class CloudProviderUi(val id: String, val name: String, val available: Boolean, val about: String)

data class CloudAccountUi(
    val id: String,
    val provider: String,
    val providerName: String,
    val label: String,
    val needsSignIn: Boolean,
    /** Copies on this account may use mobile data (off: unmetered networks only). */
    val mobileData: Boolean,
    val copies: Int,
    val freeBytes: Long? = null,
    val totalBytes: Long? = null,
    /** WebDAV: what the server proved it can do, in plain words. */
    val support: List<String> = emptyList(),
)

data class CloudUi(val providers: List<CloudProviderUi> = emptyList(), val accounts: List<CloudAccountUi> = emptyList())

/** A replica of this library that an account already holds (another device put it there). [knownPlaceId] is set when this library already lists it as a copy. */
data class FoundReplicaUi(val name: String, val root: String, val replicaId: String, val knownPlaceId: String?)

/** The result of one step of a sign-in. The screen launches [Launch] or [LaunchSender] and hands the result back. */
sealed interface SignInStep {
    class Launch(val intent: Intent) : SignInStep
    class LaunchSender(val sender: IntentSender) : SignInStep
    class Done(val account: CloudAccountUi) : SignInStep
    class Failed(val message: String) : SignInStep
    object Cancelled : SignInStep
}

class WebdavConnected(val account: CloudAccountUi, val support: List<String>)

class RevokeNoteUi(val revoked: Boolean, val note: String, val url: String?)

/** What the cloud parts of the screens do. [CloudRuntime] implements them on a phone; tests pass fakes. All calls block: run them off the main thread. */
interface CloudActions {
    val state: StateFlow<CloudUi>

    fun refresh()

    /** Starts a sign-in (or "Sign in again" when [accountId] is given). */
    fun beginSignIn(provider: String, accountId: String?): SignInStep

    /** Finishes a sign-in with what the launched screen returned. */
    fun finishSignIn(provider: String, accountId: String?, result: Intent?): SignInStep

    /** Connects a WebDAV server: the address, user and app password are checked, then the server is probed for what it supports. */
    fun connectWebdav(url: String, user: String, password: String): WebdavConnected

    /** Disconnect on this phone: forgets the sign-in here; the files on the account stay. */
    fun disconnect(accountId: String)

    /** Also asks the provider to end the grant (signs out the other devices); says where to go when it cannot. */
    fun removeAccess(accountId: String): RevokeNoteUi

    fun setMobileData(accountId: String, allowed: Boolean)

    /** Replicas of this library on the account (a second device signing in to the same account finds them). */
    fun find(accountId: String, webdavFolder: String?): List<FoundReplicaUi>

    /** The address of a copy at [folder] on the account (what a draft carries instead of a folder address). */
    fun addressFor(accountId: String, folder: String): String

    /** A folder name made safe for every provider from the name the person gave. */
    fun folderNameFor(name: String): String
}
