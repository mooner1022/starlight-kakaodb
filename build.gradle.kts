import java.security.MessageDigest

// Top-level build file where you can add configuration options common to all sub-projects/modules.
// Versions follow the StarLight app: PluginCore is compiled with its Kotlin, and older compilers cannot read it.
// KSP extracts the KDoc StarLight AI reads (slplugin 0.5.0 runs its docs processor when KSP is applied); KSP 2.3.13
// refuses AGP before 8.12, so AGP is the StarLight app's.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.library") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
    id("io.github.mooner1022.slplugin") version "0.5.0" apply false
}

/**
 * Identifies the shared UID helper's code: :sharedd, with the :rootd sources it compiles. :app, :sharedd and :setup all
 * get it as BuildConfig.SHARED_HELPER_BUILD (they read it with rootProject.extra), and the plugin warns when the
 * installed helper (patched and installed by hand, so easily left behind) reports another one. Carriage returns are
 * skipped: checkouts with CRLF line endings build the same helper.
 */
extra["sharedHelperBuild"] = run {
    val digest = MessageDigest.getInstance("SHA-256")
    val sources =
        files("rootd/src/main/java", "rootd/src/protocol/java", "sharedd/src/main", "sharedd/build.gradle.kts")
    fun pathOf(file: File) = relativePath(file).replace('\\', '/')
    for (file in sources.asFileTree.files.sortedBy(::pathOf)) {
        digest.update(pathOf(file).toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
        digest.update(file.readBytes().filter { it != '\r'.code.toByte() }.toByteArray())
    }
    digest.digest().joinToString("") { "%02x".format(it) }.take(12)
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
