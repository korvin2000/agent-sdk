plugins { id("sdk.java-conventions") }

description = "Basic read, write, edit and bash tools with workspace-aware project instructions."

dependencies {
    api(project(":agent-core"))
    testImplementation(project(":agent-testkit"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
