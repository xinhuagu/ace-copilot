// ace-copilot-test: Shared test utilities and fixtures

dependencies {
    implementation(project(":ace-copilot-core"))
    implementation(project(":ace-copilot-daemon"))

    implementation("org.junit.jupiter:junit-jupiter")
    implementation("org.assertj:assertj-core")
    implementation("org.mockito:mockito-core")
    implementation("org.mockito:mockito-junit-jupiter")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")
}
