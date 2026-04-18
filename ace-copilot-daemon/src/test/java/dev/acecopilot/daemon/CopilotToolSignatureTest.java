package dev.acecopilot.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.acecopilot.llm.copilot.CopilotAcpClient;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Java-side tool signature used by {@code StreamingAgentHandler}
 * to pre-warn on mid-session tool-catalog change (PR #9 review, issue #12).
 * The sidecar independently computes an equivalent signature — divergence
 * between the two breaks the pre-warning, so the signature function
 * deserves unit-level coverage even though the dispatch code doesn't.
 */
class CopilotToolSignatureTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static String sig(List<CopilotAcpClient.ToolDescriptor> tools) throws Exception {
        Method m = StreamingAgentHandler.class
                .getDeclaredMethod("computeToolSignature", List.class);
        m.setAccessible(true);
        return (String) m.invoke(null, tools);
    }

    private static CopilotAcpClient.ToolDescriptor td(String name, String desc, JsonNode schema) {
        return new CopilotAcpClient.ToolDescriptor(name, desc, schema);
    }

    @Test
    void emptyYieldsStableToken() throws Exception {
        assertThat(sig(List.of()))
                .as("an empty tool set must have a deterministic signature that is not mistaken for a name")
                .isEqualTo("empty");
    }

    @Test
    void sameSetDifferentOrderHasSameSignature() throws Exception {
        var schema = MAPPER.createObjectNode().put("type", "object");
        var a = List.of(td("read_file", "Read", schema), td("write_file", "Write", schema));
        var b = List.of(td("write_file", "Write", schema), td("read_file", "Read", schema));
        assertThat(sig(a))
                .as("tool list order must not affect signature — sidecar sorts too")
                .isEqualTo(sig(b));
    }

    @Test
    void addingToolChangesSignature() throws Exception {
        var schema = MAPPER.createObjectNode().put("type", "object");
        var before = List.of(td("read_file", "Read", schema));
        var after = List.of(
                td("read_file", "Read", schema),
                td("mcp_late_tool", "Async MCP arrival", schema));
        assertThat(sig(before))
                .as("an MCP tool arriving between turns must force a signature mismatch → pre-warn")
                .isNotEqualTo(sig(after));
    }

    @Test
    void descriptionChangeIsSignificant() throws Exception {
        var schema = MAPPER.createObjectNode().put("type", "object");
        var before = List.of(td("bash", "Run shell", schema));
        var after = List.of(td("bash", "Run shell with sandboxing", schema));
        assertThat(sig(before))
                .as("description matters — agents read descriptions to pick tools, so catalog change includes it")
                .isNotEqualTo(sig(after));
    }

    @Test
    void schemaChangeIsSignificant() throws Exception {
        var open = MAPPER.createObjectNode().put("type", "object");
        var strict = MAPPER.createObjectNode().put("type", "object");
        strict.putArray("required").add("path");
        var before = List.of(td("read_file", "Read", open));
        var after = List.of(td("read_file", "Read", strict));
        assertThat(sig(before)).isNotEqualTo(sig(after));
    }
}
