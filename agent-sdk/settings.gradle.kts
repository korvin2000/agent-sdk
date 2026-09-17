rootProject.name = "agent-sdk"

include("agent-core", "agent-tools", "agent-testkit")
include("agent-mcp")

dependencyResolutionManagement {
    repositories { mavenCentral() }
}
