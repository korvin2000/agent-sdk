plugins { id("sdk.java-conventions") }

description = "The four base coding tools (read, write, edit, bash), path/truncation support and the kon-style system prompt (AGENTS.md, skills, git context)."

dependencies {
    api(project(":agent-core"))
    testImplementation(project(":agent-testkit"))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}
