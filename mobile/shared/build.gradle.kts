plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// What every dropit app on a phone agrees on: requests, answers, batches, error sentences.
// Android uses the JVM build directly; iOS builds are compiled and tested from day one so that
// nothing platform-specific slips into common code.
kotlin {
    jvmToolchain(17)
    jvm()
    iosArm64()
    iosSimulatorArm64()

    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "DropitShared" }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.coroutines.core)
            api(libs.serialization.json)
            implementation(libs.datetime)
            api(libs.ktor.client.core)
            implementation(libs.okio)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// Test vectors shared with the browser extension's tests (clients/test/vectors) are compiled into the
// common tests as a string constant, so the same cases run on the JVM and in the iOS simulator.
val generateVectors by tasks.registering {
    val src = rootDir.resolve("../test/vectors/payload.json")
    val out = layout.buildDirectory.dir("generated/vectors")
    inputs.file(src)
    outputs.dir(out)
    doLast {
        val text = src.readText()
        val escaped = buildString {
            for (c in text) when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '$' -> append("\\$")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        // String constants are limited in size: join short pieces at run time
        val pieces = escaped.chunked(4000).let { chunks ->
            // never split an escape sequence
            val fixed = mutableListOf<String>()
            var carry = ""
            for (ch in chunks) {
                var piece = carry + ch
                carry = ""
                var back = 0
                while (back < piece.length && piece[piece.length - 1 - back] == '\\') back++
                if (back % 2 == 1) { carry = "\\"; piece = piece.dropLast(1) }
                if (piece.isNotEmpty() && piece.last().isHighSurrogate()) { carry = piece.last() + carry; piece = piece.dropLast(1) }
                fixed += piece
            }
            if (carry.isNotEmpty()) fixed += carry
            fixed
        }
        val file = out.get().file("xyz/smart_kits/dropit/shared/Vectors.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            append("// Generated from clients/test/vectors/payload.json — do not edit\n")
            append("package xyz.smart_kits.dropit.shared\n\n")
            append("internal val VECTORS_JSON: String = listOf(\n")
            for (p in pieces) append("    \"").append(p).append("\",\n")
            append(").joinToString(\"\")\n")
        })
    }
}
kotlin.sourceSets.commonTest { kotlin.srcDir(generateVectors) }

// Talks to a real local service when DROPIT_DEV_URL is set (e.g. http://127.0.0.1:8799)
val devUrl = providers.environmentVariable("DROPIT_DEV_URL")
tasks.named<Test>("jvmTest") {
    devUrl.orNull?.let { environment("DROPIT_DEV_URL", it) }
}
