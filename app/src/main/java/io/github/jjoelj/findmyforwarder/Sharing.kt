package io.github.jjoelj.findmyforwarder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

// Find My keys sharing to a single alias, not to a person: someone with an address and a
// phone number is two independent shares. The daemon re-reads fmfd after every change and
// returns the list that actually took, so `following` — not the HTTP status — is the truth.

/** The iPhone predates the sharing routes (LocationSpoofServer < v0.3.0). */
class SharingUnsupportedException :
    IOException("Sharing needs LocationSpoofServer v0.3.0 on the iPhone")

/**
 * Who can see my location, shared the way [AppStatus] is: the friends list merges it in,
 * the per-friend switch reads it, and Add writes to it. Null until the first answer.
 */
object Following {
    private val _handles = MutableStateFlow<List<String>?>(null)
    val handles: StateFlow<List<String>?> = _handles

    private val _unsupported = MutableStateFlow(false)
    val unsupported: StateFlow<Boolean> = _unsupported

    // Both are touched from the main dispatcher only, so a plain counter is enough.
    private var writes = 0L

    fun set(list: List<String>) {
        writes++
        _handles.value = list
    }

    /** Swallows failures: a missing follower list must not break the friends screen. */
    suspend fun refresh(context: Context) {
        val seen = writes
        try {
            val list = fetchFollowing(context)
            // A share landed while this was in flight — that answer is newer than ours.
            if (seen == writes) _handles.value = list
        } catch (_: SharingUnsupportedException) {
            _unsupported.value = true
        } catch (e: Exception) {
            FileLogger.w("Failed to read following list: ${e.message}")
        }
    }
}

/** Handles verbatim as the server spells them — /unshare only accepts that spelling. */
fun parseFollowing(body: String): List<String> {
    val root = JSONObject(body)
    if (!root.optBoolean("ok")) {
        val message = root.optString("message")
        if (message.contains("not found", ignoreCase = true)) throw SharingUnsupportedException()
        throw IOException(message.ifBlank { "Server reported an error" })
    }
    val arr = root.optJSONArray("following") ?: return emptyList()
    return List(arr.length()) { arr.getString(it) }
}

/** Membership test that ignores formatting, since the two lists spell handles differently. */
fun List<String>.hasHandle(handle: String) =
    any { normalizeHandle(it) == normalizeHandle(handle) }

/** Someone I share with who doesn't share back is in /following but never in /friends. */
fun followerOnlyHandles(friends: List<Friend>, following: List<String>): List<String> {
    val known = friends.flatMap { it.handles }.mapTo(mutableSetOf()) { normalizeHandle(it) }
    return following.filter { normalizeHandle(it) !in known }
}

private suspend fun following(
    context: Context,
    path: String,
    handle: String? = null,
): List<String> = withContext(Dispatchers.IO) {
    val prefs = SharedPreferencesProvider(context)
    val url = friendsUrl(prefs, path, handle)
        ?: throw IOException("Invalid base URL; check Settings")
    friendsClient.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
        when {
            response.code == 403 -> throw IOException("Token rejected — scan the QR code again")
            response.code == 404 -> throw SharingUnsupportedException()
            // 400s come back as plain text ("missing handle. use /share?handle=..").
            !response.isSuccessful ->
                throw IOException(response.body.string().trim().ifBlank { "HTTP ${response.code}" })
        }
        parseFollowing(response.body.string())
    }
}

/** Handles that can currently see my location. */
suspend fun fetchFollowing(context: Context) = following(context, "/following")

// ponytail: always indefinite — no hours= until there's a duration picker to feed it.
suspend fun setSharing(context: Context, handle: String, share: Boolean) =
    following(context, if (share) "/share" else "/unshare", handle)

/**
 * Stops sharing with a person rather than an alias, and returns the follower list the
 * iPhone reports afterwards. Stopping means stopping every alias they answer to —
 * leaving one on is exactly the "I unshared but Find My still shows sharing" case.
 */
suspend fun unsharePerson(context: Context, friend: Friend, known: List<String>): List<String> {
    var result = known
    // Unshare with the server's spelling (bare +digits), not the contact's formatted one.
    for (handle in known.filter { listed -> friend.handles.any { normalizeHandle(it) == normalizeHandle(listed) } }) {
        result = setSharing(context, handle, false)
    }
    return result
}

/** Rejects what fmfd would only reject after a round trip; it still decides if it exists. */
internal fun handleLooksValid(handle: String) =
    "@" in handle || handle.count { it.isDigit() } >= 7

internal fun canReadContacts(context: Context) = ContextCompat.checkSelfPermission(
    context, Manifest.permission.READ_CONTACTS
) == PackageManager.PERMISSION_GRANTED

/** Rows for people who can see me but never show up in /friends, named from contacts. */
suspend fun followerOnlyFriends(
    context: Context,
    friends: List<Friend>,
    following: List<String>,
): List<Friend> = withContext(Dispatchers.IO) {
    followerOnlyHandles(friends, following).map { handle ->
        val info = if (canReadContacts(context)) resolveContact(context, handle)
        else ContactInfo(null, null)
        Friend(
            handle = handle,
            lat = null,
            lon = null,
            address = null,
            fullAddress = null,
            timestamp = 0,
            valid = false,
            name = info.name,
            photoUri = info.photoUri,
            aliases = info.phones,
            followsMe = true,
        )
    }
}

/**
 * The server resolves any phone number to the right Apple ID, so phones win outright;
 * emails only matter when the contact has no phone. More than one left means asking.
 */
internal fun preferredHandles(handles: List<String>): List<String> =
    handles.filter { "@" !in it }.ifEmpty { handles }

/** Every email and phone on a picked contact. */
private suspend fun contactHandles(context: Context, contactUri: Uri): List<String> =
    withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val id = resolver.query(contactUri, arrayOf(ContactsContract.Contacts._ID), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
            ?: return@withContext emptyList()
        // DATA1 holds the address for an email row and the number for a phone row.
        resolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.Data.DATA1),
            "${ContactsContract.Data.CONTACT_ID}=? AND ${ContactsContract.Data.MIMETYPE} IN (?,?)",
            arrayOf(
                id,
                ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            ),
            null
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    c.getString(0)?.takeIf { it.isNotBlank() }?.let { add(if ("@" in it) it.trim() else toE164(it)) }
                }
            }
        }.orEmpty().distinctBy { normalizeHandle(it) }
    }

/** Per-person sharing switch, on the friend's detail sheet. */
@Composable
fun SharingToggle(friend: Friend, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val known by Following.handles.collectAsState()
    val unsupported by Following.unsupported.collectAsState()
    var busy by remember(friend.handle) { mutableStateOf(false) }
    var error by remember(friend.handle) { mutableStateOf<String?>(null) }
    var choices by remember(friend.handle) { mutableStateOf<List<String>?>(null) }

    // shareTo null means stop sharing with every alias.
    fun change(shareTo: String?) {
        busy = true
        error = null
        choices = null
        scope.launch {
            try {
                val now = if (shareTo != null) setSharing(context, shareTo, true)
                else unsharePerson(context, friend, known.orEmpty())
                Following.set(now)
                // A 200 doesn't mean fmfd agreed; the returned list is what did.
                if (friend.handles.any { now.hasHandle(it) } != (shareTo != null)) {
                    error = if (shareTo != null) "Find My didn't start sharing"
                    else "Find My still shows sharing"
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't change sharing"
                FileLogger.e("Sharing change failed for ${friend.handle}: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    choices?.let { options ->
        AlertDialog(
            onDismissRequest = { choices = null },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choices = null }) { Text("Cancel") } },
            title = { Text(if (options.all { "@" in it }) "Which email?" else "Which number?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    options.forEach { handle ->
                        Text(
                            text = handle,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { change(handle) }
                                .padding(vertical = 10.dp)
                        )
                    }
                }
            }
        )
    }

    if (unsupported) {
        Text(
            "Location sharing needs LocationSpoofServer v0.3.0 on the iPhone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier
        )
        return
    }

    val sharingWith = friend.handles.filter { known.orEmpty().hasHandle(it) }
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Share my location", style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = when {
                        busy -> "Updating…"
                        known == null -> "Checking…"
                        // Two aliases are two shares; both have to go off to stop sharing.
                        sharingWith.size > 1 -> "Sharing with ${sharingWith.joinToString(", ")}"
                        sharingWith.size == 1 ->
                            "They can see your location. Turning this off notifies them."
                        else -> "Turning this on notifies them, same as in Find My."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = sharingWith.isNotEmpty(),
                enabled = known != null && !busy,
                onCheckedChange = { want ->
                    if (!want) return@Switch change(null)
                    // Phones over emails; with several left, the user picks — never guess.
                    val options = preferredHandles(friend.handles)
                    if (options.size == 1) change(options.first()) else choices = options
                }
            )
        }
        error?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Start sharing with someone new: straight to the contact picker, with a dialog only when
 * there's something to decide — which of their handles, or typing one by hand.
 */
@Composable
fun AddSharingFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var choices by remember { mutableStateOf<List<String>?>(null) }
    var typed by remember { mutableStateOf<String?>(null) }

    fun share(handle: String) {
        busy = true
        error = null
        scope.launch {
            try {
                val now = setSharing(context, handle, true)
                Following.set(now)
                // The daemon re-reads fmfd, so this is what actually took.
                if (!now.hasHandle(handle)) {
                    error = "Find My didn't start sharing with $handle"
                } else {
                    onDone()
                }
            } catch (e: Exception) {
                error = e.message ?: "Couldn't start sharing"
                FileLogger.e("Sharing change failed for $handle: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickContact()) { uri ->
        if (uri == null) {
            onDone()
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            val handles = preferredHandles(contactHandles(context, uri))
            when (handles.size) {
                // A contact with no email or phone can't be an Apple ID; let them type one.
                0 -> typed = ""
                1 -> share(handles.first())
                else -> choices = handles
            }
        }
    }
    val contactsPermission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) picker.launch(null) else typed = ""
        }

    LaunchedEffect(Unit) {
        if (canReadContacts(context)) picker.launch(null)
        else contactsPermission.launch(Manifest.permission.READ_CONTACTS)
    }

    val pending = choices
    val manual = typed
    if (pending == null && manual == null && error == null) return

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        confirmButton = {
            if (manual != null) {
                TextButton(
                    onClick = {
                        val handle = manual.trim()
                        if (!handleLooksValid(handle)) {
                            error = "Enter an Apple ID email or phone number"
                        } else {
                            share(handle)
                        }
                    },
                    enabled = manual.isNotBlank() && !busy,
                ) { Text(if (busy) "Working…" else "Share") }
            }
        },
        dismissButton = { TextButton(onClick = onDone, enabled = !busy) { Text("Cancel") } },
        title = { Text(if (pending != null) "Which handle is their Apple ID?" else "Share my location") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pending?.forEach { handle ->
                    Text(
                        text = handle,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !busy) { share(handle) }
                            .padding(vertical = 10.dp)
                    )
                }
                if (manual != null) {
                    OutlinedTextField(
                        value = manual,
                        onValueChange = { typed = it },
                        label = { Text("Apple ID email or phone") },
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    "They get a notification when sharing starts, same as in Find My.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    )
}
