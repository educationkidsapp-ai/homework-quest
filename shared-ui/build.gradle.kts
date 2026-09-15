import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.library)
}

/**
 * Design system + every child-mode composable (stops, pot, journey widgets) + parent/admin components.
 * No database, no platform services, so the admin panel can reuse it on the web (wasmJs target added in Phase 5).
 */
kotlin {
    jvmToolchain(17)
    androidTarget { @OptIn(ExperimentalKotlinGradlePluginApi::class) compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    jvm("desktop")
    iosArm64(); iosSimulatorArm64()
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }
    js(IR) { browser() }

    sourceSets {
        commonMain.dependencies {
            api(projects.sharedApi)
            api(compose.runtime)
            api(compose.foundation)
            api(compose.material3)
            api(compose.materialIconsExtended)
            api(compose.ui)
            api(compose.components.resources)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        val desktopMain by getting { dependencies { implementation(compose.desktop.currentOs) } }
        val desktopTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation(kotlin("test-junit"))
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

android {
    lint { checkReleaseBuilds = false; abortOnError = false }
    namespace = "quest.ui"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "quest.ui.resources"
    generateResClass = always
}

// ---------------------------------------------------------------------------
// design/tokens.json  ->  quest.ui.design.DesignTokens
// ---------------------------------------------------------------------------
// `../design/tokens.json` is the single source both front-ends read: the Angular
// dashboard generates its CSS custom properties from it (dashboard/tools/tokens.mjs,
// `pnpm tokens`), this task generates the Kotlin object. Token names are
// `hq.<group>.<name>`; here they become camelCase properties, so
// `hq.color.accent-strong` -> `DesignTokens.colorAccentStrong` and
// `hq.worldPalettes.math.primary` -> `DesignTokens.worldPalettesMathPrimary`.
//
// Kotlin types come from the VALUE SHAPE, never from a hard-coded list of groups,
// so a school theme may add groups or tokens without touching this build file:
//     "#RRGGBB" / "#RRGGBBAA" / rgb(a)(...)  -> Color
//     number + "px"                          -> Dp   (TextUnit/sp in the `font` group)
//     number + "ms"                          -> Int  (+ a `<name>Millis` Long)
//     bare number                            -> Int / Double
//     anything else                          -> String
// Every token - recognised shape or not - is also in `DesignTokens.all`, keyed by
// its full `hq.…` name, so unknown tokens are forwarded instead of dropped.
//
// `:shared-ui:checkTokens` (TokensDriftTest) asserts that the hand-written
// `Tokens.kt` / `Theme.kt` mapping still agrees with the generated values.
val generateDesignTokens by tasks.registering {
    group = "build"
    description = "Generates quest/ui/design/DesignTokens.kt from design/tokens.json"
    val tokensFile = rootProject.layout.projectDirectory.file("design/tokens.json").asFile
    val outDir = layout.buildDirectory.dir("generated/tokens/kotlin")
    inputs.file(tokensFile)
    outputs.dir(outDir)
    doLast {
        @Suppress("UNCHECKED_CAST")
        val root = groovy.json.JsonSlurper().parse(tokensFile) as Map<String, Any?>

        // --- flatten: depth-first; `{ "value": … }` is a leaf, everything else a group ---
        val leaves = mutableListOf<Pair<List<String>, Map<*, *>>>()
        fun walk(node: Map<*, *>, path: List<String>) {
            for ((rawKey, child) in node) {
                val key = rawKey.toString()
                if (path.isEmpty() && key == "meta") continue
                if (child !is Map<*, *>) continue
                val next = path + key
                if (child.containsKey("value")) leaves += next to child else walk(child, next)
            }
        }
        walk(root, emptyList())
        require(leaves.isNotEmpty()) { "design/tokens.json contains no tokens" }

        // --- helpers ---
        fun identifier(path: List<String>): String {
            val parts = path.flatMap { it.split('-', '_', '.', ' ') }.filter { it.isNotEmpty() }
            val camel = parts.mapIndexed { i, s ->
                if (i == 0) s.replaceFirstChar { c -> c.lowercaseChar() } else s.replaceFirstChar { c -> c.uppercaseChar() }
            }.joinToString("")
            val cleaned = camel.filter { it.isLetterOrDigit() || it == '_' }
            return if (cleaned.isEmpty() || cleaned.first().isDigit()) "_$cleaned" else cleaned
        }

        fun number(v: Any?): String = when (v) {
            is java.math.BigDecimal -> v.stripTrailingZeros().toPlainString()
            is java.math.BigInteger -> v.toString()
            is Number -> {
                val d = v.toDouble()
                if (!d.isNaN() && !d.isInfinite() && d == Math.floor(d)) d.toLong().toString() else d.toString()
            }
            else -> v.toString()
        }

        fun rawValue(v: Any?, unit: String?): String = if (v is Number && unit != null) "${number(v)}$unit" else v.toString()

        fun quote(s: String): String = "\"" + s
            .replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\${'$'}")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

        /** `#RGB`, `#RRGGBB`, `#RRGGBBAA`, `rgb(r,g,b)` and `rgba(r,g,b,a)` -> a Compose `Color(0xAARRGGBB)`. */
        fun colorLiteral(value: String): String? {
            val t = value.trim()
            if (t.startsWith("#")) {
                val h = t.substring(1)
                if (h.isEmpty() || !h.all { it.isDigit() || it in "abcdefABCDEF" }) return null
                val argb = when (h.length) {
                    3 -> "FF" + h.map { c -> "$c$c" }.joinToString("")
                    6 -> "FF$h"
                    8 -> h.substring(6, 8) + h.substring(0, 6)   // #RRGGBBAA -> AARRGGBB
                    else -> return null
                }
                return "Color(0x${argb.uppercase()}L)"
            }
            val inner = Regex("^rgba?\\(([^)]*)\\)$").find(t)?.groupValues?.get(1) ?: return null
            val parts = inner.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 3) return null
            val rgb = parts.take(3).map { p -> p.toDoubleOrNull()?.toInt() ?: return null }
            if (rgb.any { it !in 0..255 }) return null
            val alpha = Math.round((parts.getOrNull(3)?.toDoubleOrNull() ?: 1.0) * 255.0).toInt().coerceIn(0, 255)
            return "Color(0x%02X%02X%02X%02XL)".format(alpha, rgb[0], rgb[1], rgb[2])
        }

        /** The typed declarations for one token; an unrecognised shape yields none (the token stays in `all`). */
        fun declarations(id: String, group: String, value: Any?, unit: String?): List<String> = when (value) {
            is String -> colorLiteral(value)?.let { listOf("val $id: Color = $it") } ?: listOf("val $id: String = ${quote(value)}")
            is Boolean -> listOf("val $id: Boolean = $value")
            is Number -> {
                val n = number(value)
                when (unit) {
                    "px" -> if (group == "font" || group == "type") listOf("val $id: TextUnit = $n.sp") else listOf("val $id: Dp = $n.dp")
                    "ms" -> listOf("val $id: Int = $n", "val ${id}Millis: Long = ${n}L")
                    null -> if (n.contains('.')) listOf("val $id: Double = $n") else listOf("val $id: Int = $n")
                    else -> listOf("val $id: String = ${quote(rawValue(value, unit))}")
                }
            }
            else -> emptyList()
        }

        // --- render ---
        val sb = StringBuilder()
        sb.append("package quest.ui.design\n\n")
        sb.append("import androidx.compose.ui.graphics.Color\n")
        sb.append("import androidx.compose.ui.unit.Dp\n")
        sb.append("import androidx.compose.ui.unit.TextUnit\n")
        sb.append("import androidx.compose.ui.unit.dp\n")
        sb.append("import androidx.compose.ui.unit.sp\n\n")
        sb.append("// GENERATED from design/tokens.json by `:shared-ui:generateDesignTokens` - do not edit.\n")
        sb.append("// The Angular dashboard generates its CSS custom properties from the same file.\n")
        sb.append("// `:shared-ui:checkTokens` fails when the hand-written Tokens.kt / Theme.kt mapping drifts from these values.\n\n")
        sb.append("@Suppress(\"unused\", \"MemberVisibilityCanBePrivate\", \"ObjectPropertyName\")\n")
        sb.append("object DesignTokens {\n")

        val seen = mutableSetOf<String>()
        var currentGroup: String? = null
        val allEntries = mutableListOf<String>()
        for ((path, leaf) in leaves) {
            val group = path.first()
            if (group != currentGroup) {
                sb.append(if (currentGroup == null) "" else "\n").append("    // ---- $group ----\n")
                currentGroup = group
            }
            val id = identifier(path)
            val unit = leaf["unit"]?.toString()
            val value = leaf["value"]
            val comment = leaf["comment"]?.toString()?.replace("*/", "* /")?.replace(Regex("\\s+"), " ")
            val name = "hq." + path.joinToString(".")
            allEntries += "        ${quote(name)} to ${quote(rawValue(value, unit))},"
            if (comment != null) sb.append("    /** $comment */\n")
            // A token whose shape has no Kotlin type, or whose camelCase name collides with one already emitted,
            // is skipped here and reached through `all` instead - a new token must never break the build.
            val decls = declarations(id, group, value, unit)
                .filter { decl -> seen.add(decl.substringAfter("val ").substringBefore(":").trim()) }
            if (decls.isEmpty()) sb.append("    // $name = ${rawValue(value, unit)} - reachable through `all` only.\n")
            for (decl in decls) sb.append("    $decl\n")
        }

        sb.append("\n    /** Every token, keyed by its `hq.<group>.<name>` name, with the raw value design/tokens.json spells. */\n")
        sb.append("    val all: Map<String, String> = mapOf(\n")
        allEntries.forEach { sb.append(it).append('\n') }
        sb.append("    )\n")
        sb.append("}\n")

        val out = outDir.get().asFile.resolve("quest/ui/design/DesignTokens.kt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
        logger.lifecycle("tokens: wrote ${leaves.size} tokens to ${out.relativeTo(rootProject.projectDir)}")
    }
}
kotlin.sourceSets.commonMain { kotlin.srcDir(generateDesignTokens) }

/**
 * The plan's drift command. The assertions live in `TokensDriftTest` (shared-ui/src/desktopTest) so CI's App job
 * runs them as well: `:shared:desktopTest` depends on `:shared-ui:desktopTest`.
 */
val checkTokens by tasks.registering {
    group = "verification"
    description = "Fails when Tokens.kt / Theme.kt hard-code a value design/tokens.json spells differently"
    dependsOn(tasks.named("desktopTest"))
}
