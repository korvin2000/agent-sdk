plugins { id("sdk.java-conventions") }

description = "Optional MCP client module: remote tool discovery, namespacing, content mapping and connection lifecycle."

dependencies {
    api(project(":agent-core"))
    implementation("io.modelcontextprotocol.sdk:mcp-core:2.0.1")
    runtimeOnly("io.modelcontextprotocol.sdk:mcp-json-jackson2:2.0.1")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

// No module-info: the Jackson supplier is located by ServiceLoader and reflects into record
// constructors, which is exactly the shape JPMS makes brittle (§2.3, §4.14.6).
tasks.jar { manifest { attributes("Automatic-Module-Name" to "sdk.agent.mcp") } }
