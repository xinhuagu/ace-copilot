// ace-copilot-llm: LLM provider implementations — Anthropic Claude, OpenAI, Ollama

dependencies {
    implementation(project(":ace-copilot-core"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")

    runtimeOnly("ch.qos.logback:logback-classic")
}

// Phase 1 (issue #3) manual verification harness for CopilotAcpClient.
// Run: ACE_COPILOT_GH_ACCOUNT=<gh-acct> ./gradlew :ace-copilot-llm:runCopilotAcpMain
tasks.register<JavaExec>("runCopilotAcpMain") {
    group = "verification"
    description = "Manual verification for CopilotAcpClient (spawns the Node sidecar, runs one sendAndWait)"
    mainClass.set("dev.acecopilot.llm.copilot.CopilotAcpClientMain")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs = listOf("--enable-preview")
    standardInput = System.`in`
    // Run from repo root so the default sidecarDir resolves correctly.
    workingDir = rootProject.projectDir
}
