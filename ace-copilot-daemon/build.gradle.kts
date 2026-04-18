// ace-copilot-daemon: Persistent daemon process — boot, lock, session management, UDS listener

dependencies {
    implementation(project(":ace-copilot-core"))
    implementation(project(":ace-copilot-infra"))
    implementation(project(":ace-copilot-llm"))
    implementation(project(":ace-copilot-tools"))
    implementation(project(":ace-copilot-memory"))
    implementation(project(":ace-copilot-security"))
    implementation(project(":ace-copilot-mcp"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")
}
