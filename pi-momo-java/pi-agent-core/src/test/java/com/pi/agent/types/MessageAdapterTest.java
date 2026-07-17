package com.pi.agent.types;

import com.pi.ai.core.types.AssistantMessage;
import com.pi.ai.core.types.Message;
import com.pi.ai.core.types.StopReason;
import com.pi.ai.core.types.ToolResultMessage;
import com.pi.ai.core.types.Usage;
import com.pi.ai.core.types.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AgentMessage} interface and {@link MessageAdapter} record.
 *
 * <p><b>Validates: Requirements 8.1, 8.2, 8.3, 8.4, 8.5, 38.1, 38.2, 38.4</b>
 */
class MessageAdapterTest {

    // ---- wrap() ----

    @Test
    void wrap_userMessage_delegatesRoleAndTimestamp() {
        long ts = System.currentTimeMillis();
        UserMessage user = new UserMessage("hello", ts);

        AgentMessage wrapped = MessageAdapter.wrap(user);

        assertThat(wrapped.role()).isEqualTo("user");
        assertThat(wrapped.timestamp()).isEqualTo(ts);
    }

    @Test
    void wrap_assistantMessage_delegatesRoleAndTimestamp() {
        long ts = 1_700_000_000_000L;
        AssistantMessage assistant = AssistantMessage.builder()
                .content(List.of())
                .api("anthropic-messages")
                .provider("anthropic")
                .model("claude-3")
                .usage(new Usage(10, 20, 0, 0, 30, null))
                .stopReason(StopReason.STOP)
                .timestamp(ts)
                .build();

        AgentMessage wrapped = MessageAdapter.wrap(assistant);

        assertThat(wrapped.role()).isEqualTo("assistant");
        assertThat(wrapped.timestamp()).isEqualTo(ts);
    }

    @Test
    void wrap_toolResultMessage_delegatesRoleAndTimestamp() {
        long ts = 1_700_000_000_000L;
        ToolResultMessage toolResult = new ToolResultMessage(
                "tc-1", "myTool", List.of(), null, false, ts);

        AgentMessage wrapped = MessageAdapter.wrap(toolResult);

        assertThat(wrapped.role()).isEqualTo("toolResult");
        assertThat(wrapped.timestamp()).isEqualTo(ts);
    }

    @Test
    void wrap_nullMessage_throwsNullPointerException() {
        assertThatThrownBy(() -> MessageAdapter.wrap(null))
                .isInstanceOf(NullPointerException.class);
    }

    // ---- unwrap() ----

    @Test
    void unwrap_messageAdapter_returnsOriginalMessage() {
        long ts = System.currentTimeMillis();
        UserMessage original = new UserMessage("hello", ts);
        AgentMessage wrapped = MessageAdapter.wrap(original);

        Message unwrapped = MessageAdapter.unwrap(wrapped);

        assertThat(unwrapped).isSameAs(original);
    }

    @Test
    void unwrap_customAgentMessage_throwsIllegalArgumentException() {
        AgentMessage custom = new AgentMessage() {
            @Override
            public String role() {
                return "custom";
            }

            @Override
            public long timestamp() {
                return 0;
            }
        };

        assertThatThrownBy(() -> MessageAdapter.unwrap(custom))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a wrapped Message");
    }

    // ---- isLlmMessage() ----

    @Test
    void isLlmMessage_wrappedMessage_returnsTrue() {
        AgentMessage wrapped = MessageAdapter.wrap(new UserMessage("hi", 0));

        assertThat(MessageAdapter.isLlmMessage(wrapped)).isTrue();
    }

    @Test
    void isLlmMessage_customMessage_returnsFalse() {
        AgentMessage custom = new AgentMessage() {
            @Override
            public String role() {
                return "notification";
            }

            @Override
            public long timestamp() {
                return 0;
            }
        };

        assertThat(MessageAdapter.isLlmMessage(custom)).isFalse();
    }

    // ---- AgentMessage non-sealed extensibility (Req 38.1) ----

    @Test
    void agentMessage_allowsCustomImplementation() {
        AgentMessage custom = new AgentMessage() {
            @Override
            public String role() {
                return "artifact";
            }

            @Override
            public long timestamp() {
                return 42L;
            }
        };

        assertThat(custom.role()).isEqualTo("artifact");
        assertThat(custom.timestamp()).isEqualTo(42L);
    }

    // ---- MessageAdapter preserves original Message (Req 38.2) ----

    @Test
    void messageAdapter_preservesOriginalMessage() {
        long ts = 1_700_000_000_000L;
        AssistantMessage original = AssistantMessage.builder()
                .content(List.of())
                .api("openai")
                .provider("openai")
                .model("gpt-4")
                .usage(new Usage(5, 10, 0, 0, 15, null))
                .stopReason(StopReason.STOP)
                .timestamp(ts)
                .build();

        MessageAdapter adapter = new MessageAdapter(original);

        assertThat(adapter.message()).isSameAs(original);
        assertThat(MessageAdapter.unwrap(adapter)).isSameAs(original);
    }

    // ---- record equality ----

    @Test
    void messageAdapter_equalityBasedOnWrappedMessage() {
        UserMessage msg = new UserMessage("test", 100L);
        MessageAdapter a = new MessageAdapter(msg);
        MessageAdapter b = new MessageAdapter(msg);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }
}
