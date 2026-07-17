package com.pi.agent;

import com.pi.agent.types.AgentMessage;
import com.pi.agent.types.AgentOptions;
import com.pi.agent.types.QueueMode;
import net.jqwik.api.*;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Feature: pi-agent-core-java, Property 12: 队列模式出队语义
 *
 * <p>For any non-empty steering or followUp queue, when QueueMode is ONE_AT_A_TIME,
 * each dequeue operation should return exactly one message (the head of the queue);
 * when QueueMode is ALL, each dequeue operation should return all messages in the
 * queue and clear the queue.
 *
 * <p><b>Validates: Requirements 28.3, 28.4, 29.3, 29.4</b>
 */
class QueueModePropertyTest {

    // ==================== Generators ====================

    /**
     * Generates a list of 1-10 AgentMessage instances with random content.
     */
    @Provide
    Arbitrary<List<AgentMessage>> messageList() {
        Arbitrary<String> contentArbitrary = Arbitraries.strings()
                .alpha()
                .ofMinLength(1)
                .ofMaxLength(10);
        
        Arbitrary<AgentMessage> messageArbitrary = contentArbitrary
                .map(QueueModePropertyTest::createTestMessage);
        
        return messageArbitrary.list().ofMinSize(1).ofMaxSize(10);
    }

    // ==================== Helpers ====================

    /**
     * Creates a simple test AgentMessage with the given content identifier.
     */
    private static AgentMessage createTestMessage(String content) {
        return new AgentMessage() {
            private final long ts = System.currentTimeMillis();
            
            @Override
            public String role() {
                return "user";
            }

            @Override
            public long timestamp() {
                return ts;
            }

            @Override
            public String toString() {
                return "TestMessage[" + content + "]";
            }
        };
    }

    // ==================== Steering Queue Property Tests ====================

    /**
     * Property 12: ONE_AT_A_TIME mode dequeues exactly one message from steering queue.
     *
     * <p>Verifies that when steeringMode is ONE_AT_A_TIME, dequeueSteeringMessages()
     * returns exactly one message (the first in queue), and the remaining messages
     * stay in the queue.
     *
     * <p><b>Validates: Requirements 28.3</b>
     */
    @Property(tries = 100)
    void steeringQueueOneAtATime_dequeuesExactlyOne(@ForAll("messageList") List<AgentMessage> messages) {
        // Create agent with ONE_AT_A_TIME steering mode (default)
        AgentOptions options = AgentOptions.builder()
                .steeringMode(QueueMode.ONE_AT_A_TIME)
                .build();
        Agent agent = new Agent(options);

        // Add all messages to steering queue
        for (AgentMessage msg : messages) {
            agent.steer(msg);
        }

        // Dequeue should return exactly one message
        List<AgentMessage> dequeued = agent.dequeueSteeringMessages();

        assertThat(dequeued).hasSize(1);
        assertThat(dequeued.get(0)).isEqualTo(messages.get(0));

        // Remaining messages should still be in queue
        // Dequeue remaining to verify
        List<AgentMessage> remaining = new ArrayList<>();
        List<AgentMessage> next;
        while (!(next = agent.dequeueSteeringMessages()).isEmpty()) {
            remaining.addAll(next);
        }

        assertThat(remaining).hasSize(messages.size() - 1);
        for (int i = 1; i < messages.size(); i++) {
            assertThat(remaining.get(i - 1)).isEqualTo(messages.get(i));
        }
    }

    /**
     * Property 12: ALL mode dequeues all messages from steering queue and clears it.
     *
     * <p>Verifies that when steeringMode is ALL, dequeueSteeringMessages() returns
     * all messages in the queue and the queue is empty afterwards.
     *
     * <p><b>Validates: Requirements 28.4</b>
     */
    @Property(tries = 100)
    void steeringQueueAll_dequeuesAllAndClears(@ForAll("messageList") List<AgentMessage> messages) {
        // Create agent with ALL steering mode
        AgentOptions options = AgentOptions.builder()
                .steeringMode(QueueMode.ALL)
                .build();
        Agent agent = new Agent(options);

        // Add all messages to steering queue
        for (AgentMessage msg : messages) {
            agent.steer(msg);
        }

        // Dequeue should return all messages
        List<AgentMessage> dequeued = agent.dequeueSteeringMessages();

        assertThat(dequeued).hasSize(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            assertThat(dequeued.get(i)).isEqualTo(messages.get(i));
        }

        // Queue should be empty now
        List<AgentMessage> afterDequeue = agent.dequeueSteeringMessages();
        assertThat(afterDequeue).isEmpty();
    }

    // ==================== FollowUp Queue Property Tests ====================

    /**
     * Property 12: ONE_AT_A_TIME mode dequeues exactly one message from followUp queue.
     *
     * <p>Verifies that when followUpMode is ONE_AT_A_TIME, dequeueFollowUpMessages()
     * returns exactly one message (the first in queue), and the remaining messages
     * stay in the queue.
     *
     * <p><b>Validates: Requirements 29.3</b>
     */
    @Property(tries = 100)
    void followUpQueueOneAtATime_dequeuesExactlyOne(@ForAll("messageList") List<AgentMessage> messages) {
        // Create agent with ONE_AT_A_TIME followUp mode (default)
        AgentOptions options = AgentOptions.builder()
                .followUpMode(QueueMode.ONE_AT_A_TIME)
                .build();
        Agent agent = new Agent(options);

        // Add all messages to followUp queue
        for (AgentMessage msg : messages) {
            agent.followUp(msg);
        }

        // Dequeue should return exactly one message
        List<AgentMessage> dequeued = agent.dequeueFollowUpMessages();

        assertThat(dequeued).hasSize(1);
        assertThat(dequeued.get(0)).isEqualTo(messages.get(0));

        // Remaining messages should still be in queue
        // Dequeue remaining to verify
        List<AgentMessage> remaining = new ArrayList<>();
        List<AgentMessage> next;
        while (!(next = agent.dequeueFollowUpMessages()).isEmpty()) {
            remaining.addAll(next);
        }

        assertThat(remaining).hasSize(messages.size() - 1);
        for (int i = 1; i < messages.size(); i++) {
            assertThat(remaining.get(i - 1)).isEqualTo(messages.get(i));
        }
    }

    /**
     * Property 12: ALL mode dequeues all messages from followUp queue and clears it.
     *
     * <p>Verifies that when followUpMode is ALL, dequeueFollowUpMessages() returns
     * all messages in the queue and the queue is empty afterwards.
     *
     * <p><b>Validates: Requirements 29.4</b>
     */
    @Property(tries = 100)
    void followUpQueueAll_dequeuesAllAndClears(@ForAll("messageList") List<AgentMessage> messages) {
        // Create agent with ALL followUp mode
        AgentOptions options = AgentOptions.builder()
                .followUpMode(QueueMode.ALL)
                .build();
        Agent agent = new Agent(options);

        // Add all messages to followUp queue
        for (AgentMessage msg : messages) {
            agent.followUp(msg);
        }

        // Dequeue should return all messages
        List<AgentMessage> dequeued = agent.dequeueFollowUpMessages();

        assertThat(dequeued).hasSize(messages.size());
        for (int i = 0; i < messages.size(); i++) {
            assertThat(dequeued.get(i)).isEqualTo(messages.get(i));
        }

        // Queue should be empty now
        List<AgentMessage> afterDequeue = agent.dequeueFollowUpMessages();
        assertThat(afterDequeue).isEmpty();
    }
}
