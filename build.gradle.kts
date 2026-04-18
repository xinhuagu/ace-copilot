plugins {
    java
    id("org.graalvm.buildtools.native") version "0.10.4" apply false
}

import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.testing.Test

allprojects {
    repositories {
        mavenCentral()
    }
}

subprojects {
    // Skip java plugin for BOM (java-platform)
    if (name == "ace-copilot-bom") return@subprojects

    apply(plugin = "java-library")

    dependencies {
        // All modules use the BOM for version management
        implementation(platform(project(":ace-copilot-bom")))
        testImplementation(platform(project(":ace-copilot-bom")))
        annotationProcessor(platform(project(":ace-copilot-bom")))

        // Common test dependencies
        testImplementation("org.junit.jupiter:junit-jupiter")
        testImplementation("org.assertj:assertj-core")
        testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    }

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(21)
        }
    }

    tasks.withType<JavaCompile> {
        options.compilerArgs.addAll(listOf("--enable-preview"))
        options.encoding = "UTF-8"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        jvmArgs("--enable-preview")
        // Ensure forked test JVMs have enough memory on Windows CI runners
        maxHeapSize = "1g"
    }

    tasks.withType<JavaExec> {
        jvmArgs("--enable-preview")
    }

    if (name == "ace-copilot-daemon" || name == "ace-copilot-memory" || name == "ace-copilot-core") {
        val sourceSets = extensions.getByType(SourceSetContainer::class.java)
        val includes = when (name) {
            "ace-copilot-daemon" -> listOf(
                    "dev.acecopilot.daemon.CandidatePipelineIntegrationTest",
                    "dev.acecopilot.daemon.StreamingAgentHandlerCandidateInjectionTest",
                    "dev.acecopilot.daemon.AceCopilotConfigPersistenceTest"
            )
            "ace-copilot-memory" -> listOf(
                    "dev.acecopilot.memory.CandidateStoreTest",
                    "dev.acecopilot.memory.CandidateStateMachineTest",
                    "dev.acecopilot.memory.CandidatePromptAssemblerTest"
            )
            else -> listOf(
                    "dev.acecopilot.core.agent.SkillRegistryTest",
                    "dev.acecopilot.core.agent.SkillContentResolverTest",
                    "dev.acecopilot.core.agent.AgentLoopIntegrationTest"
            )
        }

        tasks.register<Test>("continuousLearningSmokeTest") {
            group = "verification"
            description = "Runs focused smoke tests for pre-merge quality gates."
            testClassesDirs = sourceSets.getByName("test").output.classesDirs
            classpath = sourceSets.getByName("test").runtimeClasspath
            filter {
                includes.forEach { includeTestsMatching(it) }
                isFailOnNoMatchingTests = true
            }
        }
    }
}

tasks.register("continuousLearningSmoke") {
    group = "verification"
    description = "Runs end-to-end continuous-learning smoke tests across core modules."
    dependsOn(
            ":ace-copilot-daemon:continuousLearningSmokeTest",
            ":ace-copilot-memory:continuousLearningSmokeTest",
            ":ace-copilot-core:continuousLearningSmokeTest"
    )
}

tasks.register<Exec>("replayQualityGate") {
    group = "verification"
    description = "Validates replay quality report against hard thresholds."
    dependsOn("generateReplayReport")

    val replayReport = providers.gradleProperty("replayReport")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/replay-latest.json")
    val strict = providers.gradleProperty("replayGateStrict")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(true)
    val minSuccessRateDelta = providers.gradleProperty("replayMinSuccessRateDelta").orElse("-0.10")
    val maxTokenDelta = providers.gradleProperty("replayMaxTokenDelta").orElse("200.00")
    val maxLatencyDeltaMs = providers.gradleProperty("replayMaxLatencyDeltaMs").orElse("500.00")
    val failOnLatency = providers.gradleProperty("replayFailOnLatency")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(false)
    val maxFailureDistDelta = providers.gradleProperty("replayMaxFailureDistDelta").orElse("2.50")
    val maxTokenEstimationErrorRatio = providers.gradleProperty("replayMaxTokenEstimationErrorRatio").orElse("0.65")
    val replayBaseline = providers.gradleProperty("replayBaseline")
            .orElse("${rootDir}/docs/reports/samples/learning-quality-gate-baseline.json")
    val minPromotionRate = providers.gradleProperty("replayMinPromotionRate").orElse("0.00")
    val maxDemotionRate = providers.gradleProperty("replayMaxDemotionRate").orElse("0.35")
    val maxRollbackRate = providers.gradleProperty("replayMaxRollbackRate").orElse("0.20")
    val enforceAntiPatternFpRate = providers.gradleProperty("replayEnforceAntiPatternFalsePositiveRate")
            .map { it.toBooleanStrictOrNull() ?: false }
            .orElse(true)
    val maxAntiPatternFpRate = providers.gradleProperty("replayMaxAntiPatternFalsePositiveRate").orElse("0.50")

    commandLine(
            "bash", "${rootDir}/scripts/replay-quality-gate.sh",
            "--report", replayReport.get(),
            "--min-success-rate-delta", minSuccessRateDelta.get(),
            "--max-token-delta", maxTokenDelta.get(),
            "--max-latency-delta-ms", maxLatencyDeltaMs.get(),
            "--fail-on-latency", failOnLatency.get().toString(),
            "--max-failure-dist-delta", maxFailureDistDelta.get(),
            "--max-token-estimation-error-ratio", maxTokenEstimationErrorRatio.get(),
            "--baseline", replayBaseline.get(),
            "--min-promotion-rate", minPromotionRate.get(),
            "--max-demotion-rate", maxDemotionRate.get(),
            "--max-rollback-rate", maxRollbackRate.get(),
            "--enforce-anti-pattern-fp-rate", enforceAntiPatternFpRate.get().toString(),
            "--max-anti-pattern-fp-rate", maxAntiPatternFpRate.get()
    )
    if (strict.get()) args("--strict")
    isIgnoreExitValue = !strict.get()
}

tasks.register<Exec>("generateReplayReport") {
    group = "verification"
    description = "Generates replay quality report from learning=off/on case results."
    dependsOn("generateReplayCases")

    val replayCasesInput = providers.gradleProperty("replayCasesInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/replay-cases.json")
    val replayCasesManifestInput = providers.gradleProperty("replayCasesManifestInput")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/replay-cases.manifest.json")
    val replayReport = providers.gradleProperty("replayReport")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/replay-latest.json")
    val replayAntiPatternFeedbackPath = providers.gradleProperty("replayAntiPatternFeedbackPath")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/anti-pattern-gate-feedback.json")
    val replayCandidateTransitionsPath = providers.gradleProperty("replayCandidateTransitionsPath")
            .orElse("${rootDir}/.ace-copilot/memory/candidate-transitions.jsonl")
    val replayPromptsInput = providers.gradleProperty("replayPromptsInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootDir}/docs/reports/samples/replay-prompts-sample.json")

    commandLine(
            "bash",
            "${rootDir}/scripts/generate-replay-report.sh",
            "--input", replayCasesInput.get(),
            "--manifest", replayCasesManifestInput.get(),
            "--anti-pattern-feedback", replayAntiPatternFeedbackPath.get(),
            "--candidate-transitions", replayCandidateTransitionsPath.get(),
            "--replay-prompts", replayPromptsInput.get(),
            "--output", replayReport.get()
    )
}

tasks.register<Exec>("validateReplaySuite") {
    group = "verification"
    description = "Validates replay prompt suite schema and category coverage."

    val replayPromptsInput = providers.gradleProperty("replayPromptsInput")
            .orElse(providers.gradleProperty("replayInput"))
            .orElse("${rootDir}/docs/reports/samples/replay-prompts-sample.json")
    val replaySuiteMinPerCategory = providers.gradleProperty("replaySuiteMinPerCategory").orElse("3")

    commandLine(
            "bash",
            "${rootDir}/scripts/validate-replay-suite.sh",
            "--input", replayPromptsInput.get(),
            "--min-per-category", replaySuiteMinPerCategory.get()
    )
}

tasks.register("generateReplayCases") {
    group = "verification"
    description = "Runs replay prompts (off/on) and emits replay-cases JSON."
    dependsOn("validateReplaySuite", ":ace-copilot-cli:runReplayCases")
}

tasks.register<JavaExec>("benchmarkScorecard") {
    group = "verification"
    description = "Evaluates self-learning benchmark scorecard from replay and runtime metrics."
    dependsOn("generateReplayReport")
    mainClass.set("dev.acecopilot.daemon.BenchmarkScorecardCli")
    classpath = project(":ace-copilot-daemon").sourceSets["main"].runtimeClasspath

    val replayReport = providers.gradleProperty("replayReport")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/replay-latest.json")
    val runtimeMetrics = providers.gradleProperty("runtimeMetrics")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/runtime-latest.json")
    val scorecardOutput = providers.gradleProperty("scorecardOutput")
            .orElse("${rootDir}/.ace-copilot/metrics/continuous-learning/benchmark-scorecard.json")

    jvmArgs("--enable-preview")
    args("--replay-report", replayReport.get(),
         "--runtime-metrics", runtimeMetrics.get(),
         "--output", scorecardOutput.get())
}

tasks.register("preMergeCheck") {
    group = "verification"
    description = "Single pre-merge quality gate: build, smoke, replay gate, and benchmark scorecard."
    dependsOn("build", "continuousLearningSmoke", "replayQualityGate", "benchmarkScorecard")
    // Both gates run: replayQualityGate covers manifest verification, token calibration,
    // and anti-pattern FP thresholds that benchmarkScorecard does not yet check.
    // benchmarkScorecard adds the broader 8-metric contract (effectiveness/efficiency/safety).
    // When scorecard fully subsumes replay gate checks, replayQualityGate can be removed.
}
