package dev.acecopilot.daemon;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for {@link AceCopilotConfig#effectiveCopilotRuntime()}.
 *
 * <p>Phase 1 (#3) gated session activation on an extra
 * {@code copilotRuntimeAcceptUnsandboxed} ack flag because the sidecar
 * auto-approved every SDK permission request. Phase 2 (#4) replaced that
 * with a real permission bridge, so the gate is no longer needed and the
 * ack field is now ignored (kept only for backward compat).
 */
class CopilotRuntimeGateTest {

    @Test
    void defaultsToChat() throws Exception {
        var config = blankConfig();
        assertThat(config.copilotRuntime()).isEqualTo("chat");
        assertThat(config.effectiveCopilotRuntime()).isEqualTo("chat");
    }

    @Test
    void sessionActivates() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "session");

        assertThat(config.effectiveCopilotRuntime())
                .as("Phase 2 removed the Phase 1 ack gate; session = session")
                .isEqualTo("session");
    }

    @Test
    void ackFlagIgnored() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "chat");
        setField(config, "copilotRuntimeAcceptUnsandboxed", true);

        assertThat(config.effectiveCopilotRuntime())
                .as("ack flag alone must not promote chat to session")
                .isEqualTo("chat");
    }

    @Test
    void sessionLiteralIsCaseInsensitive() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "SESSION");

        assertThat(config.effectiveCopilotRuntime()).isEqualTo("session");
    }

    @Test
    void unknownRuntimeFallsBackToChat() throws Exception {
        var config = blankConfig();
        setField(config, "copilotRuntime", "nonsense");

        assertThat(config.effectiveCopilotRuntime()).isEqualTo("chat");
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
