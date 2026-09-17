plugins { id("sdk.java-conventions") }

description = "Test doubles for hosts and extensions: ScriptedProvider, RecordingSink, FakeClock, InMemoryFileVersionOracle."

dependencies {
    api(project(":agent-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
