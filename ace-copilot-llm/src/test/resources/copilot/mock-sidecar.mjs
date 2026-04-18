// Test double for ace-copilot-sidecar/sidecar.mjs.
//
// Speaks the same LSP-framed JSON-RPC protocol on stdio but does not
// import @github/copilot-sdk or touch the network. Used by
// CopilotAcpClientSmokeTest to exercise the Java client end-to-end.
//
// Behavior:
//   - initialize         -> { protocolVersion: "0.1" }
//   - session.sendAndWait -> emits 2 session/text deltas and 2 session/usage
//                           events (initiator=user with premiumUsed=N,
//                           then initiator=agent with premiumUsed=N+1),
//                           then replies with the canned assistant content.
//   - shutdown           -> responds null and exits.

function writeMessage(obj) {
  const body = JSON.stringify(obj);
  const buf = Buffer.from(body, "utf8");
  process.stdout.write(`Content-Length: ${buf.length}\r\n\r\n`);
  process.stdout.write(buf);
}

let buffer = Buffer.alloc(0);
process.stdin.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  while (true) {
    const sep = buffer.indexOf("\r\n\r\n");
    if (sep < 0) return;
    const m = /Content-Length:\s*(\d+)/i.exec(buffer.slice(0, sep).toString("utf8"));
    if (!m) { process.exit(2); }
    const len = parseInt(m[1], 10);
    const bodyStart = sep + 4;
    if (buffer.length < bodyStart + len) return;
    const body = buffer.slice(bodyStart, bodyStart + len).toString("utf8");
    buffer = buffer.slice(bodyStart + len);
    handle(JSON.parse(body));
  }
});

function handle(msg) {
  const { id, method } = msg;
  switch (method) {
    case "initialize": {
      writeMessage({ jsonrpc: "2.0", id, result: { protocolVersion: "0.1" } });
      return;
    }
    case "session.sendAndWait": {
      writeMessage({ jsonrpc: "2.0", method: "session/text", params: { delta: "Hello " } });
      writeMessage({
        jsonrpc: "2.0", method: "session/usage",
        params: { model: "test-model", initiator: "user", premiumUsed: 42, premiumLimit: 100 },
      });
      writeMessage({ jsonrpc: "2.0", method: "session/text", params: { delta: "world." } });
      writeMessage({
        jsonrpc: "2.0", method: "session/usage",
        params: { model: "test-model", initiator: "agent", premiumUsed: 43, premiumLimit: 100 },
      });
      writeMessage({
        jsonrpc: "2.0", id,
        result: { content: "Hello world.", stopReason: "COMPLETE" },
      });
      return;
    }
    case "shutdown": {
      writeMessage({ jsonrpc: "2.0", id, result: null });
      process.exit(0);
    }
    default: {
      writeMessage({
        jsonrpc: "2.0", id,
        error: { code: -32601, message: "unknown method: " + method },
      });
    }
  }
}
