package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonNumber
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.signin.SignIn
import app.keepfin.core.signin.SignInCookie
import app.keepfin.core.signin.SignInSource

// fixtures/engine/signin: see SCHEMA.md there.

private fun str(o: JsonObject, key: String): String? = (o.members[key] as? JsonString)?.value

private fun source(o: JsonObject) = SignInSource(str(o, "kind") ?: "", str(o, "browser"), str(o, "profile"), str(o, "label"))

internal fun signinHandler(input: JsonObject): JsonValue {
    if (input.members.containsKey("autoOn")) {
        fun list(key: String) = (input.members[key] as JsonArray).items.map { source(it as JsonObject) }
        fun flag(key: String) = (input.members.getValue(key) as app.keepfin.core.json.JsonBool).value
        val chosen = SignIn.retry(str(input, "url") ?: "", str(input, "action"), flag("autoOn"), flag("manualOverride"), list("available"), list("tried"))
        return obj("retry" to jstr(chosen?.let { SignIn.notice(it) + (it.profile?.let { p -> " [$p]" } ?: "") }))
    }
    str(input, "url")?.let { return obj("site" to jstr(SignIn.siteOf(it)?.key)) }
    (input.members["cookies"] as? JsonArray)?.let { list ->
        val site = SignIn.site(str(input, "site") ?: "") ?: return obj("signedIn" to jstr("unknown site"))
        val cookies = list.items.map { c ->
            c as JsonObject
            SignInCookie(str(c, "name") ?: "", str(c, "domain") ?: "", (c.members["expiry"] as JsonNumber).value.toLong())
        }
        val now = (input.members.getValue("now") as JsonNumber).value.toLong()
        return obj("signedIn" to app.keepfin.core.json.JsonBool(SignIn.signedIn(site, cookies, now)))
    }
    (input.members["sources"] as? JsonArray)?.let { list ->
        return obj("order" to arr(SignIn.order(list.items.map { source(it as JsonObject) }).map { jstr(SignIn.notice(it) + (it.profile?.let { p -> " [$p]" } ?: "")) }))
    }
    return obj("notice" to jstr(SignIn.notice(source(input.members.getValue("source") as JsonObject))))
}
