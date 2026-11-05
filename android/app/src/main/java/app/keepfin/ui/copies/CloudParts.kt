@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package app.keepfin.ui.copies

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.keepfin.cloud.CloudAccountUi
import app.keepfin.cloud.CloudActions
import app.keepfin.cloud.CloudProviderUi
import app.keepfin.cloud.FoundReplicaUi
import app.keepfin.cloud.SignInStep
import app.keepfin.ui.Format
import app.keepfin.ui.components.RowSeparator
import app.keepfin.ui.components.SwitchRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object CloudTags {
    const val CHOICE = "copies-cloud"
    const val PROVIDER = "cloud-provider-"
    const val ACCOUNT = "cloud-account"
    const val SIGN_IN = "cloud-sign-in"
    const val SIGN_IN_AGAIN = "cloud-sign-in-again"
    const val WEBDAV_URL = "cloud-webdav-url"
    const val WEBDAV_USER = "cloud-webdav-user"
    const val WEBDAV_PASSWORD = "cloud-webdav-password"
    const val WEBDAV_CONNECT = "cloud-webdav-connect"
    const val FOLDER_NAME = "cloud-folder-name"
    const val MOBILE_DATA = "cloud-mobile-data"
    const val DISCONNECT = "cloud-disconnect"
    const val REMOVE_ACCESS = "cloud-remove-access"
    const val FOUND = "cloud-found"
    const val USE_ACCOUNT = "cloud-use-account"
    const val SECTION = "cloud-accounts"
}

/**
 * Starts a sign-in and hands its steps to [onStep]. The browser (Microsoft, Dropbox) and Google's consent screen are launched here; the
 * rest happens in [CloudActions]. The returned function takes the provider and, for "Sign in again", the account.
 */
@Composable
fun rememberCloudSignIn(cloud: CloudActions, scope: CoroutineScope, onStep: (SignInStep) -> Unit): (String, String?) -> Unit {
    var current by remember { mutableStateOf<Pair<String, String?>?>(null) }
    val intentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val c = current ?: return@rememberLauncherForActivityResult
        scope.launch { onStep(withContext(Dispatchers.IO) { runCatching { cloud.finishSignIn(c.first, c.second, res.data) }.getOrElse { SignInStep.Failed(it.message ?: "The sign-in failed.") } }) }
    }
    val senderLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        val c = current ?: return@rememberLauncherForActivityResult
        scope.launch { onStep(withContext(Dispatchers.IO) { runCatching { cloud.finishSignIn(c.first, c.second, res.data) }.getOrElse { SignInStep.Failed(it.message ?: "The sign-in failed.") } }) }
    }
    return { provider, accountId ->
        current = provider to accountId
        scope.launch {
            val step = withContext(Dispatchers.IO) { runCatching { cloud.beginSignIn(provider, accountId) }.getOrElse { SignInStep.Failed(it.message ?: "The sign-in could not be started.") } }
            when (step) {
                is SignInStep.Launch -> intentLauncher.launch(step.intent)
                is SignInStep.LaunchSender -> senderLauncher.launch(IntentSenderRequest.Builder(step.sender).build())
                else -> onStep(step)
            }
        }
    }
}

/** Settings > Storage > Copies: the cloud accounts this phone is signed in to. */
@Composable
internal fun CloudAccountsSection(cloud: CloudActions, onError: (String?) -> Unit) {
    val ui by cloud.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    LaunchedEffect(cloud) { withContext(Dispatchers.IO) { runCatching { cloud.refresh() } } }
    if (ui.accounts.isEmpty()) return
    RowSeparator()
    Column(Modifier.fillMaxWidth().padding(16.dp).testTag(CloudTags.SECTION), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Cloud accounts", fontSize = 17.sp, style = MaterialTheme.typography.titleMedium)
        for (a in ui.accounts) AccountCard(a, cloud, scope, onError)
    }
}

@Composable
private fun AccountCard(a: CloudAccountUi, cloud: CloudActions, scope: CoroutineScope, onError: (String?) -> Unit) {
    var confirm by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val uri = LocalUriHandler.current
    val signIn = rememberCloudSignIn(cloud, scope) { step -> if (step is SignInStep.Failed) onError(step.message) else onError(null) }
    fun io(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { runCatching(block).onFailure { onError(it.message) } } }
    Column(Modifier.fillMaxWidth().testTag(CloudTags.ACCOUNT).semantics(mergeDescendants = true) { contentDescription = "${a.providerName}. ${a.label}" }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(a.label.ifEmpty { a.providerName }, fontSize = 16.sp, style = MaterialTheme.typography.titleSmall)
        Text(if (a.needsSignIn) "${a.providerName} · signed out" else "${a.providerName} · signed in", fontSize = 13.sp, color = if (a.needsSignIn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        val space = when {
            a.freeBytes != null && a.totalBytes != null -> "${Format.bytes(a.freeBytes)} free of ${Format.bytes(a.totalBytes)}"
            a.freeBytes != null -> "${Format.bytes(a.freeBytes)} free"
            else -> null
        }
        val line = listOfNotNull(if (a.copies == 1) "1 copy" else if (a.copies > 1) "${a.copies} copies" else "No copies yet", space).joinToString(" · ")
        Text(line, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (s in a.support) Text(s, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Use mobile data", a.mobileData, { v -> io { cloud.setMobileData(a.id, v) } }, subtitle = "Off: copies on this account only sync on Wi-Fi", tag = CloudTags.MOBILE_DATA)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (a.needsSignIn && a.provider != "webdav") Button(onClick = { signIn(a.provider, a.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.SIGN_IN_AGAIN)) { Text("Sign in again") }
            OutlinedButton(onClick = { confirm = "disconnect" }, modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.DISCONNECT)) { Text("Sign out") }
            TextButton(onClick = { confirm = "revoke" }, modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.REMOVE_ACCESS)) { Text("Remove Keepfin's access") }
        }
    }
    when (confirm) {
        "disconnect" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Sign out of ${a.label.ifEmpty { a.providerName }}?") },
            text = { Text("Keepfin forgets the sign-in on this phone. The files already on the account stay there, and your other devices keep working. Copies on this account wait until you sign in again.") },
            confirmButton = { TextButton(onClick = { confirm = null; io { cloud.disconnect(a.id) } }) { Text("Sign out") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
        "revoke" -> AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Remove Keepfin's access?") },
            text = { Text("This asks ${a.providerName} to end Keepfin's access to the account. That also signs out your other devices. The files on the account stay.") },
            confirmButton = { TextButton(onClick = { confirm = null; scope.launch { note = withContext(Dispatchers.IO) { runCatching { cloud.removeAccess(a.id) }.map { r -> r.note + (r.url?.let { "\n$it" } ?: "") }.getOrElse { it.message } } } }) { Text("Remove access") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
    note?.let { n ->
        AlertDialog(onDismissRequest = { note = null }, title = { Text("Done") }, text = { Text(n) }, confirmButton = { TextButton(onClick = { note = null }) { Text("Close") } })
    }
}

/**
 * The cloud path of "Add a copy": pick a service, sign in (or connect a server), then name the place on the account. [onAddress] gets the
 * copy's address once the person has chosen a place (the wizard then checks it like a folder), or null while the choice is incomplete.
 */
@Composable
internal fun CloudPlaceChooser(cloud: CloudActions, onAddress: (String?, String) -> Unit, onAttach: (placeId: String, address: String) -> Unit) {
    val ui by cloud.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var provider by remember { mutableStateOf<String?>(null) }
    var accountId by remember { mutableStateOf<String?>(null) }
    var folder by remember { mutableStateOf("Library") }
    var found by remember { mutableStateOf<List<FoundReplicaUi>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val signIn = rememberCloudSignIn(cloud, scope) { step ->
        busy = false
        when (step) {
            is SignInStep.Done -> { accountId = step.account.id; message = null }
            is SignInStep.Failed -> message = step.message
            else -> message = null
        }
    }
    LaunchedEffect(cloud) { withContext(Dispatchers.IO) { runCatching { cloud.refresh() } } }
    val accountsHere = ui.accounts.filter { it.provider == provider }
    val account = ui.accounts.firstOrNull { it.id == accountId }
    // once an account is chosen the places on it are looked at, and the address follows the folder name
    LaunchedEffect(accountId, account?.needsSignIn) {
        found = emptyList()
        val id = accountId
        if (id != null && account?.needsSignIn == false) {
            busy = true
            found = withContext(Dispatchers.IO) { runCatching { cloud.find(id, if (account.provider == "webdav") folder else null) }.getOrDefault(emptyList()) }
            busy = false
        }
    }
    LaunchedEffect(accountId, folder, account?.needsSignIn) {
        kotlinx.coroutines.delay(400) // typing: the place is looked at once the name rests
        val id = accountId
        if (id != null && account?.needsSignIn == false && folder.isNotBlank()) onAddress(withContext(Dispatchers.IO) { runCatching { cloud.addressFor(id, folder) }.getOrNull() }, folder.trim()) else onAddress(null, folder.trim())
    }
    Column(Modifier.fillMaxWidth().testTag(CloudTags.CHOICE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Choose a service", fontSize = 15.sp)
        for (p in ui.providers) ProviderRow(p, provider == p.id) { if (p.available) { provider = p.id; accountId = null; message = null } }
        val pr = provider
        if (pr != null) {
            if (pr == "webdav") {
                for (a in accountsHere) AccountRow(a, accountId == a.id) { accountId = a.id }
                if (accountId == null) {
                    Text("Connect a server with its address and an app password. Keepfin stores the password encrypted on this phone only.", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(url, { url = it }, label = { Text("Server address (https://…)") }, singleLine = true, keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag(CloudTags.WEBDAV_URL))
                    OutlinedTextField(user, { user = it }, label = { Text("User name") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(CloudTags.WEBDAV_USER))
                    OutlinedTextField(password, { password = it }, label = { Text("App password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag(CloudTags.WEBDAV_PASSWORD))
                    Button(
                        enabled = !busy && url.isNotBlank() && user.isNotBlank() && password.isNotEmpty(),
                        onClick = {
                            scope.launch {
                                busy = true
                                message = null
                                val r = withContext(Dispatchers.IO) { runCatching { cloud.connectWebdav(url, user, password) } }
                                busy = false
                                r.onSuccess { accountId = it.account.id; password = "" }.onFailure { message = it.message }
                            }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.WEBDAV_CONNECT),
                    ) { Text("Connect") }
                } else TextButton(onClick = { accountId = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Use another server") }
            } else {
                for (a in accountsHere) AccountRow(a, accountId == a.id) { accountId = a.id }
                Button(
                    enabled = !busy,
                    onClick = { busy = true; message = null; signIn(pr, null) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.SIGN_IN),
                ) { Text(if (accountsHere.isEmpty()) "Sign in with ${ui.providers.first { it.id == pr }.name}" else "Sign in with another account") }
            }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 14.sp) }
        if (account != null) {
            if (account.needsSignIn) {
                Text("This account needs you to sign in again.", color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                if (account.provider != "webdav") Button(onClick = { busy = true; signIn(account.provider, account.id) }, modifier = Modifier.heightIn(min = 48.dp).testTag(CloudTags.SIGN_IN_AGAIN)) { Text("Sign in again") }
            } else {
                if (found.isNotEmpty()) {
                    Text("Already on this account", fontSize = 15.sp)
                    for (f in found) Row(Modifier.fillMaxWidth().testTag(CloudTags.FOUND), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.name, Modifier.weight(1f), fontSize = 14.sp)
                        TextButton(
                            onClick = { scope.launch { val a = withContext(Dispatchers.IO) { runCatching { cloud.addressFor(account.id, f.name) }.getOrNull() }; if (a != null) { folder = f.name; if (f.knownPlaceId != null) onAttach(f.knownPlaceId, a) } } },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text(if (f.knownPlaceId != null) "Use on this phone" else "Use this") }
                    }
                }
                OutlinedTextField(
                    folder, { folder = it }, label = { Text(if (account.provider == "webdav") "Folder on the server" else "Folder name") }, singleLine = true,
                    supportingText = { Text(if (account.provider == "webdav") "A folder under the server address, for example Keepfin" else "Made under Keepfin on the account") },
                    modifier = Modifier.fillMaxWidth().testTag(CloudTags.FOLDER_NAME),
                )
            }
        }
    }
}

@Composable
private fun ProviderRow(p: CloudProviderUi, selected: Boolean, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CloudTags.PROVIDER + p.id).clickable(enabled = p.available, onClick = onPick), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onPick, enabled = p.available)
        Column(Modifier.weight(1f)) {
            Text(p.name, fontSize = 15.sp, color = if (p.available) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(p.about, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AccountRow(a: CloudAccountUi, selected: Boolean, onPick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(CloudTags.USE_ACCOUNT), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onPick)
        Column(Modifier.weight(1f)) {
            Text(a.label.ifEmpty { a.providerName }, fontSize = 15.sp)
            if (a.needsSignIn) Text("Signed out", fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
            else a.freeBytes?.let { Text("${Format.bytes(it)} free", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

