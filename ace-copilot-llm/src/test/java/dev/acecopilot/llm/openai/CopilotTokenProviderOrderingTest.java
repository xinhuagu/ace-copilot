package dev.acecopilot.llm.openai;

import org.junit.jupiter.api.Test;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the GitHub token resolution policy used by both the CLI's
 * first-time-login pre-flight and the daemon's runtime cascade.
 *
 * <p>Two invariants are critical and exercised here:
 * <ol>
 *   <li>Pre-flight only honours cached device-code token + {@code gh auth token}.</li>
 *   <li>Runtime cascade order is {@code cached → gh CLI → apiKey →
 *       GITHUB_TOKEN → GH_TOKEN}, so the source that lets the user past
 *       pre-flight is the same source the Copilot quota gets billed to.</li>
 * </ol>
 */
class CopilotTokenProviderOrderingTest {

    private static Supplier<String> of(String value) {
        return () -> value;
    }

    // ---- pre-flight ---------------------------------------------------

    @Test
    void preflight_prefersCached_overGhCli() {
        var token = CopilotTokenProvider.firstPreflightTokenCandidate(
                of("from-cache"), of("from-gh"));
        assertThat(token).isEqualTo("from-cache");
    }

    @Test
    void preflight_fallsBackToGhCli_whenCachedAbsent() {
        var token = CopilotTokenProvider.firstPreflightTokenCandidate(
                of(null), of("from-gh"));
        assertThat(token).isEqualTo("from-gh");
    }

    @Test
    void preflight_returnsNull_whenBothAbsent() {
        var token = CopilotTokenProvider.firstPreflightTokenCandidate(
                of(null), of(""));
        assertThat(token).isNull();
    }

    @Test
    void preflight_treatsBlankAsAbsent() {
        var token = CopilotTokenProvider.firstPreflightTokenCandidate(
                of("   "), of("from-gh"));
        assertThat(token).isEqualTo("from-gh");
    }

    // ---- runtime cascade ----------------------------------------------

    @Test
    void runtimeCascade_orderIsCachedThenGhThenConfigThenGithubTokenThenGhToken() {
        var list = CopilotTokenProvider.collectGithubTokenCandidates(
                "from-config",
                of("from-cache"),
                of("from-gh"),
                of("from-github-token"),
                of("from-gh-token"));
        assertThat(list).containsExactly(
                "from-cache",
                "from-gh",
                "from-config",
                "from-github-token",
                "from-gh-token");
    }

    @Test
    void runtimeCascade_skipsNullAndBlankSources() {
        var list = CopilotTokenProvider.collectGithubTokenCandidates(
                null,
                of("from-cache"),
                of(null),
                of(""),
                of("from-gh-token"));
        assertThat(list).containsExactly("from-cache", "from-gh-token");
    }

    @Test
    void runtimeCascade_dedupesIdenticalTokens() {
        var dup = "duplicate";
        var list = CopilotTokenProvider.collectGithubTokenCandidates(
                dup, of(dup), of(dup), of(dup), of(dup));
        assertThat(list).containsExactly(dup);
    }

    @Test
    void runtimeCascade_returnsEmpty_whenAllSourcesAbsent() {
        var list = CopilotTokenProvider.collectGithubTokenCandidates(
                null, of(null), of(""), of("  "), of(null));
        assertThat(list).isEmpty();
    }

    // ---- pre-flight / runtime alignment (the headline guarantee) ------

    @Test
    void runtime_picksSameSourceAsPreflight_whenCachedPresent() {
        Supplier<String> cached = of("from-cache");
        Supplier<String> gh = of("from-gh");

        var preflight = CopilotTokenProvider.firstPreflightTokenCandidate(cached, gh);
        var runtimeFirst = CopilotTokenProvider.collectGithubTokenCandidates(
                "from-config", cached, gh,
                of("from-github-token"), of("from-gh-token")).getFirst();

        assertThat(preflight).isEqualTo("from-cache");
        assertThat(runtimeFirst)
                .as("runtime must bill the same account that satisfied pre-flight")
                .isEqualTo(preflight);
    }

    @Test
    void runtime_picksSameSourceAsPreflight_whenOnlyGhPresent() {
        Supplier<String> cached = of(null);
        Supplier<String> gh = of("from-gh");

        var preflight = CopilotTokenProvider.firstPreflightTokenCandidate(cached, gh);
        var runtimeFirst = CopilotTokenProvider.collectGithubTokenCandidates(
                "from-config", cached, gh,
                of("from-github-token"), of("from-gh-token")).getFirst();

        assertThat(preflight).isEqualTo("from-gh");
        assertThat(runtimeFirst)
                .as("env/config tokens must not silently override the gh-CLI account "
                        + "that satisfied pre-flight")
                .isEqualTo(preflight);
    }
}
