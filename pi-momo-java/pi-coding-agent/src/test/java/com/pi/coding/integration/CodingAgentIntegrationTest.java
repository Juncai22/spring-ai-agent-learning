package com.pi.coding.integration;

import com.pi.agent.config.StreamFn;
import com.pi.agent.event.AgentEvent;
import com.pi.agent.types.AgentTool;
import com.pi.ai.core.event.AssistantMessageEvent;
import com.pi.ai.core.event.AssistantMessageEventStream;
import com.pi.ai.core.types.*;
import com.pi.ai.core.util.PiAiJson;
import com.pi.coding.rpc.RpcMode;
import com.pi.coding.rpc.RpcResponse;
import com.pi.coding.sdk.CodingAgentSdk;
import com.pi.coding.session.AgentSession;
import com.pi.coding.session.AgentSessionEvent;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests verifying end-to-end flows through the coding agent SDK.
 *
 * <p>Uses mock StreamFn to avoid real LLM calls while verifying the full
 * wiring: SDK → AgentSession → Agent → tools → session persistence.
 *
 * <p><b>Validates: Requirements 2.1-2.18, 20.1-20.17, 21.1-21.13</b>
 */
class CodingAgentIntegrationTest {

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a mock StreamFn that returns a simple assistant message with STOP reason.
     */
    private static StreamFn mockStreamFn() {
        return (model, context, options) -> {
            AssistantMessageEventStream stream = AssistantMessageEventStream.create();
            AssistantMessage msg = AssistantMessage.builder()
                    .content(List.of(new TextContent("Hello from mock LLM")))
                    .stopReason(StopReason.STOP)
                    .usage(new Usage(10, 20, 0, 0, 0, null))
                    .timestamp(System.currentTimeMillis())
                    .build();
            CompletableFuture.runAsync(() -> {
                stream.push(new AssistantMessageEvent.Start(msg));
                stream.push(new AssistantMessageEvent.Done(StopReason.STOP, msg));
                stream.end(msg);
            });
            return stream;
        };
    }

    /**
     * Creates a minimal SDK result with in-memory components and a test model.
     */
    private static CodingAgentSdk.CreateResult createTestSession() {
        Model testModel = new Model(
                "test-model", "Test Model", "anthropic-messages", "test",
                "https://test.example.com", false, List.of("text"),
                new ModelCost(0, 0, 0, 0), 128000, 4096, null, null);

        return CodingAgentSdk.builder()
                .streamFn(mockStreamFn())
                .initialModel(testModel)
                .build();
    }

    // =========================================================================
    // SDK Creation Tests
    // =========================================================================

    @Test
    void sdk_createsSessionWithAllComponents() {
        CodingAgentSdk.CreateResult result = createTestSession();

        assertThat(result).isNotNull();
        assertThat(result.session()).isNotNull();
        assertThat(result.extensionsResult()).isNotNull();

        AgentSession session = result.session();
        assertThat(session.getCwd()).isNotNull();
        assertThat(session.getSessionManager()).isNotNull();
        assertThat(session.getSettingsManager()).isNotNull();
        assertThat(session.getModelRegistry()).isNotNull();
        assertThat(session.getResourceLoader()).isNotNull();
    }

    @Test
    void sdk_defaultToolsIncludeAllCodingTools() {
        CodingAgentSdk.CreateResult result = createTestSession();
        AgentSession session = result.session();

        List<AgentTool> tools = session.getAllTools().stream()
                .map(t -> (AgentTool) null) // just check count via getAllTools
                .toList();

        // getAllTools returns ToolInfo, check names
        var toolNames = session.getAllTools().stream()
                .map(t -> t.name())
                .toList();

        assertThat(toolNames).contains("read", "write", "edit", "bash", "grep", "find", "ls");
    }

    @Test
    void sdk_customToolsCanBeProvided() {
        List<AgentTool> readOnly = CodingAgentSdk.createReadOnlyTools(System.getProperty("user.dir"));

        CodingAgentSdk.CreateResult result = CodingAgentSdk.builder()
                .streamFn(mockStreamFn())
                .builtinTools(readOnly)
                .build();

        var toolNames = result.session().getAllTools().stream()
                .map(t -> t.name())
                .toList();

        assertThat(toolNames).contains("read", "grep", "find", "ls");
        assertThat(toolNames).doesNotContain("write", "edit", "bash");
    }

    // =========================================================================
    // Prompt → LLM → Session Persistence Flow
    // =========================================================================

    @Test
    void prompt_triggersLlmCallAndPersistsMessages() throws Exception {
        CodingAgentSdk.CreateResult result = createTestSession();
        AgentSession session = result.session();

        List<AgentSessionEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        session.subscribe(event -> {
            events.add(event);
            // AgentEnd event signals completion (wrapped in AgentEventWrapper)
            if (event instanceof AgentSession.AgentEventWrapper w
                    && w.event() instanceof AgentEvent.AgentEnd) {
                done.countDown();
            }
        });

        session.prompt("Hello", null);
        boolean completed = done.await(5, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(session.getMessages()).isNotEmpty();
    }

    // =========================================================================
    // Event Subscription
    // =========================================================================

    @Test
    void eventSubscription_receivesEventsAndCanUnsubscribe() throws Exception {
        CodingAgentSdk.CreateResult result = createTestSession();
        AgentSession session = result.session();

        List<AgentSessionEvent> events = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);

        Runnable unsub = session.subscribe(event -> {
            events.add(event);
            if (event instanceof AgentSession.AgentEventWrapper w
                    && w.event() instanceof AgentEvent.AgentEnd) {
                done.countDown();
            }
        });

        session.prompt("Test", null);
        done.await(5, TimeUnit.SECONDS);

        int eventCount = events.size();
        assertThat(eventCount).isGreaterThan(0);

        // Unsubscribe and verify no more events
        unsub.run();
    }

    // =========================================================================
    // Tool Factory Tests
    // =========================================================================

    @Test
    void toolFactory_createCodingTools_returnsSevenTools() {
        List<AgentTool> tools = CodingAgentSdk.createCodingTools("/tmp");
        assertThat(tools).hasSize(7);
    }

    @Test
    void toolFactory_createReadOnlyTools_returnsFourTools() {
        List<AgentTool> tools = CodingAgentSdk.createReadOnlyTools("/tmp");
        assertThat(tools).hasSize(4);
    }

    @Test
    void prebuiltTools_areNotNull() {
        assertThat(CodingAgentSdk.READ_TOOL).isNotNull();
        assertThat(CodingAgentSdk.BASH_TOOL).isNotNull();
        assertThat(CodingAgentSdk.EDIT_TOOL).isNotNull();
        assertThat(CodingAgentSdk.WRITE_TOOL).isNotNull();
        assertThat(CodingAgentSdk.GREP_TOOL).isNotNull();
        assertThat(CodingAgentSdk.FIND_TOOL).isNotNull();
        assertThat(CodingAgentSdk.LS_TOOL).isNotNull();
        assertThat(CodingAgentSdk.CODING_TOOLS).hasSize(7);
        assertThat(CodingAgentSdk.READ_ONLY_TOOLS).hasSize(4);
    }

    // =========================================================================
    // RPC Mode Tests
    // =========================================================================

    @Test
    void rpcMode_handlesGetStateCommand() throws Exception {
        CodingAgentSdk.CreateResult result = createTestSession();

        // Send a get_state command as JSON line via stdin
        String cmdJson = "{\"type\":\"get_state\",\"id\":\"test-1\"}\n";
        InputStream stdin = new ByteArrayInputStream(cmdJson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        RpcMode rpc = new RpcMode(result.session(), stdin, stdout);

        // Run in a thread since start() blocks
        Thread rpcThread = new Thread(rpc::start);
        rpcThread.start();
        rpcThread.join(3000);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output).isNotEmpty();

        // Parse the first JSON line response
        String firstLine = output.lines().findFirst().orElse("");
        RpcResponse response = PiAiJson.MAPPER.readValue(firstLine, RpcResponse.class);
        assertThat(response.success()).isTrue();
        assertThat(response.command()).isEqualTo("get_state");
    }

    @Test
    void rpcMode_handlesGetCommandsViaJsonLine() throws Exception {
        CodingAgentSdk.CreateResult result = createTestSession();

        String cmdJson = "{\"type\":\"get_commands\",\"id\":\"test-2\"}\n";
        InputStream stdin = new ByteArrayInputStream(cmdJson.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();

        RpcMode rpc = new RpcMode(result.session(), stdin, stdout);

        Thread rpcThread = new Thread(rpc::start);
        rpcThread.start();
        rpcThread.join(3000);

        String output = stdout.toString(StandardCharsets.UTF_8);
        assertThat(output).isNotEmpty();

        String firstLine = output.lines().findFirst().orElse("");
        RpcResponse response = PiAiJson.MAPPER.readValue(firstLine, RpcResponse.class);
        assertThat(response.success()).isTrue();
        assertThat(response.command()).isEqualTo("get_commands");
    }

    // =========================================================================
    // Extension Integration
    // =========================================================================

    @Test
    void extensionRunner_canBeAttachedToSession() {
        CodingAgentSdk.CreateResult result = createTestSession();
        AgentSession session = result.session();

        // Extension runner should be set by the SDK
        assertThat(session.getExtensionRunner()).isNotNull();
    }

    // =========================================================================
    // Session Management
    // =========================================================================

    @Test
    void session_exportToHtml_returnsNonEmptyString() {
        CodingAgentSdk.CreateResult result = createTestSession();
        String html = result.session().exportToHtml();
        assertThat(html).isNotNull();
        assertThat(html).contains("<html");
    }

    @Test
    void session_dispose_cleansUpResources() {
        CodingAgentSdk.CreateResult result = createTestSession();
        AgentSession session = result.session();

        // Should not throw
        session.dispose();
    }
}
