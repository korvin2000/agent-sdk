plugins { id("sdk.java-conventions") }

description = "Coding-agent SDK core: message model, event stream, pure turn machine, run engine, tool contract, hooks, provider SPI."

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// Mechanical enforcement of core purity, not discipline: agent-core has NO runtime dependencies.
val checkCorePurity by tasks.registering {
    description = "Fails if agent-core has any runtime dependency (it must stay a zero-dependency library)."
    val ids = configurations.runtimeClasspath.map { cp -> cp.resolvedConfiguration.resolvedArtifacts.map { it.moduleVersion.id.toString() } }
    doLast {
        val deps = ids.get()
        require(deps.isEmpty()) { "agent-core runtime classpath is polluted: $deps" }
    }
}
tasks.named("check") { dependsOn(checkCorePurity) }
