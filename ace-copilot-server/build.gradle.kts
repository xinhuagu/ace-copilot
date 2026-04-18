// ace-copilot-server: WebSocket listener for IDE/remote clients

dependencies {
    implementation(project(":ace-copilot-daemon"))

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
