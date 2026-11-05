@file:JsModule("node:fs")

package app.keepfin.core

internal external fun readFileSync(path: String, encoding: String): String

internal external fun readdirSync(path: String, options: dynamic): Array<dynamic>
