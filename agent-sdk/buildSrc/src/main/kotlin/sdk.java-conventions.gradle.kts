import java.util.zip.ZipFile

// Shared conventions for every module: Java 26 toolchain, --release 26, UTF-8, lint-as-error,
// preview features banned, JUnit platform, and the THIRD-PARTY-NOTICES shipping gate.
plugins {
    `java-library`
}

group = "sdk.agent"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(26) }
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 26
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf(
        "-Xlint:all,-serial,-requires-automatic,-this-escape,-missing-explicit-ctor",
        "-Werror"))
    doFirst {
        // A preview class file pins every consumer to JDK 26 exactly; for a library that is disqualifying.
        require(options.compilerArgs.none { it == "--enable-preview" }) {
            "Preview features are banned: they pin every consumer to JDK 26 exactly."
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("file.encoding", "UTF-8")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Every shipped jar carries the third-party notices (MIT attribution for the verbatim model-facing strings).
val noticesFile = rootProject.file("THIRD-PARTY-NOTICES.md")
tasks.named<Jar>("jar") {
    from(noticesFile) { into("META-INF") }
}

val licenseNotices by tasks.registering {
    description = "Fails if the module jar does not carry META-INF/THIRD-PARTY-NOTICES.md."
    val jarFile = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    dependsOn(tasks.named("jar"))
    inputs.file(jarFile)
    doLast {
        ZipFile(jarFile.get().asFile).use { zip ->
            require(zip.getEntry("META-INF/THIRD-PARTY-NOTICES.md") != null) {
                "${jarFile.get().asFile.name} lacks META-INF/THIRD-PARTY-NOTICES.md (shipping gate)."
            }
        }
    }
}
tasks.named("check") { dependsOn(licenseNotices) }
