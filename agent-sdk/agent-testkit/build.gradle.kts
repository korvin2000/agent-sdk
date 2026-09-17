plugins { id("sdk.java-conventions") }

description = "Test doubles for hosts and extensions: ScriptedProvider, RecordingSink, FakeClock, FakeTool."

dependencies {
    api(project(":agent-core"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
