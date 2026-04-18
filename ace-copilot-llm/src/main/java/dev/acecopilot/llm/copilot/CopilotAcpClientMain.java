package dev.acecopilot.llm.copilot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Manual verification harness for {@link CopilotAcpClient} (Phase 1, issue #3).
 *
 * <p>Run from the repo root:
 * <pre>
 *   ./gradlew :ace-copilot-llm:compileJava
 *   java --enable-preview -cp "$(./gradlew -q :ace-copilot-llm:printRuntimeClasspath)" \
 *     dev.acecopilot.llm.copilot.CopilotAcpClientMain
 * </pre>
 *
 * <p>Or more simply, since the daemon already has the classpath wired up:
 * <pre>
 *   ACE_COPILOT_GH_ACCOUNT=xinhua-gu_acncp \
 *     ./ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli \
 *     --main dev.acecopilot.llm.copilot.CopilotAcpClientMain
 * </pre>
 *
 * <p>Picks up configuration from env vars (in priority order):
 * <ul>
 *   <li>{@code ACE_COPILOT_SIDECAR_DIR} — path to {@code ace-copilot-sidecar/} (default: repo-relative)</li>
 *   <li>{@code ACE_COPILOT_COPILOT_MODEL} — model to use (default: {@code claude-haiku-4.5})</li>
 *   <li>{@code ACE_COPILOT_COPILOT_PROMPT} — prompt to send (default: a built-in tool-exercising prompt)</li>
 *   <li>{@code ACE_COPILOT_GITHUB_TOKEN} — explicit token, if set</li>
 *   <li>{@code ACE_COPILOT_GH_ACCOUNT} — gh account name to resolve token via {@code gh auth token --user <acct>}</li>
 * </ul>
 */
public final class CopilotAcpClientMain {

    private static final String DEFAULT_MODEL = "claude-haiku-4.5";

    private static final String DEFAULT_PROMPT = """
            List the top-level files in the current directory and tell me, in one sentence,
            what kind of project this looks like. Use your tools — do not guess.
            """;

    private CopilotAcpClientMain() {}

    public static void main(String[] args) throws IOException {
        Path sidecarDir = resolveSidecarDir();
        if (!Files.exists(sidecarDir.resolve("sidecar.mjs"))) {
            System.err.println("sidecar.mjs not found under " + sidecarDir.toAbsolutePath());
            System.err.println("Set ACE_COPILOT_SIDECAR_DIR to the ace-copilot-sidecar/ directory.");
            System.exit(2);
        }
        if (!Files.exists(sidecarDir.resolve("node_modules"))) {
            System.err.println("node_modules not installed at " + sidecarDir.toAbsolutePath());
            System.err.println("Run: (cd " + sidecarDir + " && npm install)");
            System.exit(2);
        }

        String model = envOrDefault("ACE_COPILOT_COPILOT_MODEL", DEFAULT_MODEL);
        String prompt = envOrDefault("ACE_COPILOT_COPILOT_PROMPT", DEFAULT_PROMPT).trim();
        String token = resolveGithubToken();

        System.out.println("[main] sidecarDir: " + sidecarDir.toAbsolutePath());
        System.out.println("[main] model:      " + model);
        System.out.println("[main] token:      " + (token != null ? "(resolved, len=" + token.length() + ")" : "(null, SDK will use logged-in user)"));
        System.out.println("[main] prompt:     " + prompt.replaceAll("\\s+", " "));
        System.out.println();

        long started = System.currentTimeMillis();
        try (var client = new CopilotAcpClient(sidecarDir, token)) {
            System.out.println("[main] sending...");
            System.out.print("[assistant] ");
            var result = client.sendAndWait(model, prompt, System.out::print);
            System.out.println();
            System.out.println();
            System.out.println("[main] ---------- RESULT ----------");
            System.out.println("  stopReason:             " + result.stopReason());
            System.out.println("  usage events:           " + result.usageEventCount());
            if (result.firstUsage() != null) {
                System.out.println("  first usage initiator:  " + result.firstUsage().initiator());
                System.out.println("  first usage premium:    " + result.firstUsage().premiumUsed());
            }
            if (result.lastUsage() != null) {
                System.out.println("  last usage initiator:   " + result.lastUsage().initiator());
                System.out.println("  last usage premium:     " + result.lastUsage().premiumUsed());
            }
            System.out.println("  premium delta (session):" + result.premiumDelta());
            System.out.println("  wall clock:             " + (System.currentTimeMillis() - started) + "ms");
            if (result.premiumDelta() == 0 && result.usageEventCount() > 1) {
                System.out.println("  note: delta 0 across multiple usage events is expected when");
                System.out.println("        the first reported count is post-increment (see probe-usage.mjs).");
                System.out.println("        Run twice back-to-back to see the between-sessions +1.");
            }
        }
    }

    private static Path resolveSidecarDir() {
        String env = System.getenv("ACE_COPILOT_SIDECAR_DIR");
        if (env != null && !env.isBlank()) return Path.of(env);
        return Path.of("ace-copilot-sidecar").toAbsolutePath();
    }

    private static String envOrDefault(String name, String fallback) {
        String v = System.getenv(name);
        return (v != null && !v.isBlank()) ? v : fallback;
    }

    private static String resolveGithubToken() {
        String explicit = System.getenv("ACE_COPILOT_GITHUB_TOKEN");
        if (explicit != null && !explicit.isBlank()) return explicit.trim();
        String ghAccount = System.getenv("ACE_COPILOT_GH_ACCOUNT");
        if (ghAccount == null || ghAccount.isBlank()) return null;
        try {
            var pb = new ProcessBuilder("gh", "auth", "token", "--user", ghAccount);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out;
            try (var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = r.readLine();
            }
            int exit = p.waitFor();
            if (exit == 0 && out != null && !out.isBlank()) return out.trim();
            System.err.println("[main] gh auth token --user " + ghAccount + " failed (exit=" + exit + ")");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("[main] could not resolve gh token: " + e.getMessage());
        }
        return null;
    }
}
