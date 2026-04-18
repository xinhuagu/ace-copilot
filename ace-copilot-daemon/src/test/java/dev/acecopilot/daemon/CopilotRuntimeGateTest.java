package dev.acecopilot.daemon;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Truth-table coverage for the {@link AceCopilotConfig#effectiveCopilotRuntime()}
 * safety gate (Phase 1, issue #3). The gate must collapse misconfigured
 * session-without-ack back to {@code "chat"}; this is the only thing
 * standing between a mistyped config and a PermissionManager bypass, so
 * it is worth a unit test even though the rest of the session runtime
 * is covered by integration tests.
 */
class CopilotRuntimeGateTest {

    @Test
    void defaultsToChat() throws Exception {
        var config = blankConfig();
        assertThat(config.copilotRuntime()).isEqualTo("chat");
        assertThat(config.copilotRuntimeAcceptUnsandboxed()).isFalse();
        assertThat(config.effectiveCopilotRuntime()).isEqualTo("chat");
    }

    @Test
    void sessionWithoutAckFallsBackToChat() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "session");
        setField(config, "copilotRuntimeAcceptUnsandboxed", false);

        assertThat(config.copilotRuntime()).isEqualTo("session");
        assertThat(config.effectiveCopilotRuntime())
                .as("session mode must refuse to activate without explicit ack")
                .isEqualTo("chat");
    }

    @Test
    void ackWithoutSessionStillChat() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "chat");
        setField(config, "copilotRuntimeAcceptUnsandboxed", true);

        assertThat(config.effectiveCopilotRuntime())
                .as("ack flag alone must not promote chat to session")
                .isEqualTo("chat");
    }

    @Test
    void sessionPlusAckActivates() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "session");
        setField(config, "copilotRuntimeAcceptUnsandboxed", true);

        assertThat(config.effectiveCopilotRuntime())
                .as("both flags true is the one combination that activates session")
                .isEqualTo("session");
    }

    @Test
    void sessionLiteralIsCaseInsensitive() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "SESSION");
        setField(config, "copilotRuntimeAcceptUnsandboxed", true);

        assertThat(config.effectiveCopilotRuntime()).isEqualTo("session");
    }

    /**
     * Constructs {@link AceCopilotConfig} directly (bypassing
     * {@link AceCopilotConfig#load}) so the test is insulated from the
     * developer's real {@code ~/.ace-copilot/config.json}, which may
     * already set {@code copilotRuntime="session"} for manual testing.
     */
    private static AceCopilotConfig blankConfig() throws Exception {
        Constructor<AceCopilotConfig> ctor = AceCopilotConfig.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field f = AceCopilotConfig.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }
}
