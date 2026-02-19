package dev.aceclaw.core.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link SkillContentResolver} — argument substitution, command preprocessing,
 * and environment variable substitution.
 */
class SkillContentResolverTest {

    @TempDir
    Path workDir;

    @Test
    void substituteArguments() {
        var resolver = new SkillContentResolver(workDir);
        String body = "Deploy to $ARGUMENTS. Region is $1, stage is $2.";

        String result = resolver.substituteArguments(body, "us-west-2 production");

        assertThat(result).isEqualTo("Deploy to us-west-2 production. Region is us-west-2, stage is production.");
    }

    @Test
    void substituteNoArguments() {
        var resolver = new SkillContentResolver(workDir);
        String body = "Run with args: $ARGUMENTS";

        String result = resolver.substituteArguments(body, null);

        assertThat(result).isEqualTo("Run with args: ");
    }

    @Test
    void substituteEmptyArguments() {
        var resolver = new SkillContentResolver(workDir);
        String body = "Deploy to $ARGUMENTS";

        String result = resolver.substituteArguments(body, "  ");

        assertThat(result).isEqualTo("Deploy to ");
    }

    @Test
    void substituteSingleArgument() {
        var resolver = new SkillContentResolver(workDir);
        String body = "Target: $1 (full: $ARGUMENTS)";

        String result = resolver.substituteArguments(body, "staging");

        assertThat(result).isEqualTo("Target: staging (full: staging)");
    }

    @Test
    void executeCommand() {
        var resolver = new SkillContentResolver(workDir);
        String body = "Current dir: \n!`echo hello`\nDone.";

        String result = resolver.executeCommands(body);

        assertThat(result).isEqualTo("Current dir: \nhello\nDone.");
    }

    @Test
    void executeCommandFailure() {
        var resolver = new SkillContentResolver(workDir);
        String body = "Result: !`false`";

        String result = resolver.executeCommands(body);

        assertThat(result).contains("[Command failed: exit code");
    }

    @Test
    void commandTimeout() {
        var resolver = new SkillContentResolver(workDir);
        // sleep 20 will exceed the 10s timeout
        String body = "!`sleep 20`";

        String result = resolver.executeCommands(body);

        assertThat(result).contains("[Command timed out");
    }

    @Test
    void noCommandsInBody() {
        var resolver = new SkillContentResolver(workDir);
        String body = "No commands here, just plain text.\n\nMultiple lines.";

        String result = resolver.executeCommands(body);

        assertThat(result).isEqualTo(body);
    }

    @Test
    void multipleCommands() {
        var resolver = new SkillContentResolver(workDir);
        String body = "First: !`echo one`\nSecond: !`echo two`";

        String result = resolver.executeCommands(body);

        assertThat(result).isEqualTo("First: one\nSecond: two");
    }

    @Test
    void fullResolveWithArgumentsAndCommands() {
        var resolver = new SkillContentResolver(workDir);
        var config = new SkillConfig(
                "test-skill", "Test skill", null,
                SkillConfig.ExecutionContext.INLINE, null, null,
                SkillConfig.DEFAULT_MAX_TURNS, true, false,
                "Deploy $1 to $ARGUMENTS\nVersion: !`echo 1.0.0`",
                workDir
        );

        String result = resolver.resolve(config, "staging");

        assertThat(result).contains("Deploy staging to staging");
        assertThat(result).contains("Version: 1.0.0");
    }

    @Test
    void resolveEmptyBody() {
        var resolver = new SkillContentResolver(workDir);
        var config = new SkillConfig(
                "empty", "Empty skill", null,
                SkillConfig.ExecutionContext.INLINE, null, null,
                SkillConfig.DEFAULT_MAX_TURNS, true, false,
                "",
                workDir
        );

        String result = resolver.resolve(config, "args");

        assertThat(result).isEmpty();
    }

    @Test
    void resolveEnvironmentVars() {
        var resolver = new SkillContentResolver(workDir);
        var config = new SkillConfig(
                "envtest", "Env test", null,
                SkillConfig.ExecutionContext.INLINE, null, null,
                SkillConfig.DEFAULT_MAX_TURNS, true, false,
                "Project dir: ${ACECLAW_PROJECT_DIR}",
                workDir
        );

        String result = resolver.resolve(config, null);

        assertThat(result).contains(workDir.toAbsolutePath().toString());
    }
}
