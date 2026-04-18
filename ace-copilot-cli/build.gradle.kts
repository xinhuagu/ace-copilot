// ace-copilot-cli: Thin client — connects to daemon via UDS, JLine3 REPL, Picocli commands

plugins {
    application
    id("org.graalvm.buildtools.native")
}

import org.gradle.api.tasks.JavaExec

dependencies {
    implementation(project(":ace-copilot-core"))
    implementation(project(":ace-copilot-daemon"))
    implementation(project(":ace-copilot-llm"))

    implementation("info.picocli:picocli")
    annotationProcessor("info.picocli:picocli-codegen")

    implementation("org.jline:jline")
    implementation("org.jline:jline-terminal")
    implementation("org.jline:jline-terminal-jni")
    implementation("org.jline:jline-reader")

    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.commonmark:commonmark")
    implementation("org.commonmark:commonmark-ext-gfm-tables")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough")
    implementation("org.slf4j:slf4j-api")
    runtimeOnly("ch.qos.logback:logback-classic")
}

application {
    mainClass = "dev.acecopilot.cli.AceCopilotMain"
    applicationDefaultJvmArgs = listOf("--enable-preview", "-Dlogback.configurationFile=logback-cli.xml")
}

// Ensure the Copilot SDK sidecar's npm dependencies are installed before the
// CLI distribution is assembled, so app.home/sidecar/node_modules exists at
// runtime when a user opts into copilotRuntime=session (issue #3).
//
// Skipped automatically when npm is not available — users without npm either
// run in source mode (which resolves to <user.dir>/ace-copilot-sidecar/) or
// override via ACE_COPILOT_SIDECAR_DIR. A warning is logged so they know why
// the session path will not work out of the box.
val sidecarDir = rootProject.file("ace-copilot-sidecar")

// Cross-platform detection of `npm` on PATH. `sh -c "command -v npm"` doesn't
// exist on Windows runners, so drive the check directly from the JVM.
fun isNpmAvailable(): Boolean = try {
    val probe = ProcessBuilder(if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm", "--version")
        .redirectErrorStream(true)
        .start()
    probe.inputStream.readAllBytes()
    probe.waitFor() == 0
} catch (_: Exception) {
    false
}

val installSidecarDeps = tasks.register<Exec>("installSidecarDeps") {
    group = "build"
    description = "Installs @github/copilot-sdk dependencies into ace-copilot-sidecar/node_modules."
    workingDir = sidecarDir
    commandLine(
        if (org.gradle.internal.os.OperatingSystem.current().isWindows) "npm.cmd" else "npm",
        "install", "--omit=dev", "--no-audit", "--no-fund"
    )
    inputs.file(sidecarDir.resolve("package.json"))
    inputs.file(sidecarDir.resolve("package-lock.json"))
    outputs.dir(sidecarDir.resolve("node_modules"))
    onlyIf {
        val hasNpm = isNpmAvailable()
        if (!hasNpm) {
            logger.warn(
                "npm not found on PATH; skipping Copilot sidecar dep install. " +
                "Users who opt into copilotRuntime=\"session\" must provide a populated " +
                "ACE_COPILOT_SIDECAR_DIR at runtime."
            )
        }
        hasNpm
    }
}

// Include user-facing scripts, VERSION file, and the Copilot sidecar in the distribution.
distributions {
    main {
        contents {
            from(rootProject.file("tui.sh")) { into("") }
            from(rootProject.file("restart.sh")) { into("") }
            from(rootProject.file("update.sh")) { into("") }
            from(provider {
                val versionFile = layout.buildDirectory.file("VERSION").get().asFile
                versionFile.parentFile.mkdirs()
                versionFile.writeText(project.version.toString())
                versionFile
            }) {
                into("")
            }
            // Copilot SDK sidecar (issue #3). Resolved at runtime from <app.home>/sidecar/.
            from(sidecarDir) {
                into("sidecar")
                include("sidecar.mjs", "package.json", "package-lock.json", "node_modules/**")
            }
        }
    }
}

tasks.named("installDist") { dependsOn(installSidecarDeps) }
tasks.named("distTar")     { dependsOn(installSidecarDeps) }
tasks.named("distZip")     { dependsOn(installSidecarDeps) }

graalvmNative {
    binaries {
        named("main") {
            imageName = "ace-copilot"
            mainClass = "dev.acecopilot.cli.AceCopilotMain"
            buildArgs.addAll(
                "--enable-preview",
                "-O2",
                "--no-fallback",
                "-H:+ReportExceptionStackTraces"
            )
        }
    }
}

tasks.register<JavaExec>("runReplayCases") {
    group = "verification"
    description = "Executes replay prompts in learning off/on modes and writes replay-cases JSON."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("dev.acecopilot.cli.ReplayCasesRunnerMain")
    workingDir = rootProject.projectDir

    val replayPromptsInput = providers.gradleProperty("replayPromptsInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootProject.projectDir}/docs/reports/samples/replay-prompts-sample.json")
    val replayCasesOutput = providers.gradleProperty("replayCasesOutput")
            .orElse("${rootProject.projectDir}/.ace-copilot/metrics/continuous-learning/replay-cases.json")
    val replayCasesManifestOutput = providers.gradleProperty("replayCasesManifestOutput")
            .orElse("${rootProject.projectDir}/.ace-copilot/metrics/continuous-learning/replay-cases.manifest.json")
    val replayProject = providers.gradleProperty("replayProject")
            .orElse(rootProject.projectDir.toString())
    val replayTimeoutMs = providers.gradleProperty("replayTimeoutMs").orElse("180000")
    val replayAutoApprovePermissions = providers.gradleProperty("replayAutoApprovePermissions").orElse("true")
    val replayDelayMs = providers.gradleProperty("replayDelayMs").orElse("2000")

    args(
            "--input", replayPromptsInput.get(),
            "--output", replayCasesOutput.get(),
            "--manifest-output", replayCasesManifestOutput.get(),
            "--project", replayProject.get(),
            "--timeout-ms", replayTimeoutMs.get(),
            "--delay-ms", replayDelayMs.get(),
            "--auto-approve-permissions", replayAutoApprovePermissions.get()
    )
}
