package app.keepfin.core

import app.keepfin.core.json.JsonArray
import app.keepfin.core.json.JsonObject
import app.keepfin.core.json.JsonString
import app.keepfin.core.json.JsonValue
import app.keepfin.core.json.parseJson

internal class FixtureCase(
    val file: String,
    val name: String,
    val input: JsonObject,
    val expected: JsonValue,
    val platforms: List<String>?,
)

internal fun requireFixturesRoot(): String =
    fixturesRoot() ?: error(
        "The fixtures folder is not set. Run the tests with Gradle from core/ (./gradlew jvmTest jsNodeTest); " +
            "the build passes the path of fixtures/engine as keepfin.fixtures (JVM) and KEEPFIN_FIXTURES (JS).",
    )

private fun jsonFiles(dir: String): List<String> {
    val out = ArrayList<String>()
    for (entry in listDirectory(dir).sortedBy { it.name }) {
        val full = "$dir/${entry.name}"
        if (entry.isDirectory) {
            if (entry.name != "samples") out.addAll(jsonFiles(full))
        } else if (entry.name.endsWith(".json")) out.add(full)
    }
    return out
}

/** Every case of every `*.json` under fixtures/engine/<category>, skipping directories named `samples`. */
internal fun loadCases(category: String): List<FixtureCase> {
    val root = requireFixturesRoot()
    val dir = "$root/$category"
    val cases = ArrayList<FixtureCase>()
    for (file in jsonFiles(dir)) {
        val label = "$category/${file.substringAfterLast('/').removeSuffix(".json")}"
        val names = HashSet<String>()
        val doc = parseJson(readTextFile(file)) as JsonObject
        for (item in (doc.members.getValue("cases") as JsonArray).items) {
            val obj = (item as JsonObject).members
            val name = (obj.getValue("name") as JsonString).value
            check(names.add(name)) { "duplicate case name \"$name\" in $label" }
            cases.add(
                FixtureCase(
                    file = label,
                    name = name,
                    input = obj.getValue("input") as JsonObject,
                    expected = obj.getValue("expected"),
                    platforms = (obj["platforms"] as? JsonArray)?.items?.map { (it as JsonString).value },
                ),
            )
        }
    }
    return cases
}

internal fun readSample(category: String, file: String): String = readTextFile("${requireFixturesRoot()}/$category/samples/$file")

/**
 * A handler turns a case's `input` into the result that `expected` is compared with.
 * The core implements the desktop (canonical) behaviour, so a case limited to other platforms is skipped.
 */
internal typealias Handler = (JsonObject) -> JsonValue

internal class CategoryResult(val ran: Int, val skipped: Int, val failures: List<String>)

internal fun runCategory(
    category: String,
    handler: Handler,
    compare: (JsonValue, JsonValue, String, MutableList<String>) -> Unit = ::compareDefault,
    platform: String = "desktop",
    /** A case limited to other platforms is skipped; the providers core implements both apps' naming, so it runs both. */
    platforms: Set<String> = setOf(platform),
): CategoryResult {
    val cases = loadCases(category)
    check(cases.isNotEmpty()) { "fixtures/engine/$category has no cases" }
    val failures = ArrayList<String>()
    var ran = 0
    var skipped = 0
    for (case in cases) {
        if (case.platforms != null && case.platforms.none { it in platforms }) {
            skipped++
            continue
        }
        ran++
        val problems = ArrayList<String>()
        try {
            compare(handler(case.input), case.expected, case.name, problems)
        } catch (error: Throwable) {
            problems.add("${case.name}: threw ${error::class.simpleName}: ${error.message}")
        }
        problems.forEach { failures.add("${case.file}: $it") }
    }
    return CategoryResult(ran, skipped, failures)
}

private fun compareDefault(actual: JsonValue, expected: JsonValue, where: String, failures: MutableList<String>) =
    compareSubset(actual, expected, where, failures)
