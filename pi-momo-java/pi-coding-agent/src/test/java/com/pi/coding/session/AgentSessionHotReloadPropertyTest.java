package com.pi.coding.session;

import com.pi.agent.Agent;
import com.pi.agent.types.AgentState;
import com.pi.agent.types.AgentTool;
import com.pi.ai.core.types.Model;
import com.pi.coding.model.CodingModelRegistry;
import com.pi.coding.resource.*;
import com.pi.coding.settings.SettingsManager;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Property-based tests for AgentSession hot-reload functionality.
 * 
 * Feature: skills-hot-reload
 */
class AgentSessionHotReloadPropertyTest {

    /**
     * Property 10: 系统提示词更新链
     * For any resource change event, AgentSession should:
     * (1) Receive the notification from ResourceLoader
     * (2) Rebuild the system prompt
     * (3) Emit a ResourceChangeSessionEvent to listeners
     * 
     * Validates: Requirements 5.1, 5.2, 5.3
     */
    @Property(tries = 5)
    @Label("Feature: skills-hot-reload, Property 10: 系统提示词更新链")
    void systemPromptUpdateChain(
        @ForAll @IntRange(min = 1, max = 3) int changeCount
    ) throws Exception {
        // Given - mock dependencies
        Agent agent = mock(Agent.class);
        AgentState agentState = mock(AgentState.class);
        when(agent.getState()).thenReturn(agentState);
        when(agentState.getModel()).thenReturn(new Model(
            "test-model", "Test Model", "anthropic-messages", "test", 
            null, false, List.of("text"), null, 100000, 4096, null, null
        ));
        when(agentState.getThinkingLevel()).thenReturn(null);
        when(agentState.getTools()).thenReturn(List.of());
        when(agent.subscribe(any())).thenReturn(() -> {});
        
        SessionManager sessionManager = mock(SessionManager.class);
        SettingsManager settingsManager = mock(SettingsManager.class);
        CodingModelRegistry modelRegistry = mock(CodingModelRegistry.class);
        
        // Create a test ResourceLoader that supports listeners
        TestResourceLoader resourceLoader = new TestResourceLoader();
        
        AgentSessionConfig config = new AgentSessionConfig(
            agent,
            sessionManager,
            settingsManager,
            "/test/cwd",
            List.of(),  // scopedModels
            resourceLoader,
            List.of(),  // customTools
            modelRegistry,
            List.of()   // initialActiveToolNames
        );
        
        AgentSession session = new AgentSession(config);
        
        // Track events received by session listeners
        AtomicInteger eventCount = new AtomicInteger(0);
        List<AgentSessionEvent> receivedEvents = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(changeCount);
        
        session.subscribe(event -> {
            if (event instanceof AgentSession.ResourceChangeSessionEvent) {
                eventCount.incrementAndGet();
                receivedEvents.add(event);
                latch.countDown();
            }
        });
        
        // When - simulate resource changes
        for (int i = 0; i < changeCount; i++) {
            resourceLoader.simulateChange();
        }
        
        // Then - session should receive all change events
        boolean received = latch.await(5, TimeUnit.SECONDS);
        assertThat(received)
            .as("Session should receive %d resource change events", changeCount)
            .isTrue();
        assertThat(eventCount.get()).isEqualTo(changeCount);
        assertThat(receivedEvents).hasSize(changeCount);
        
        // Verify system prompt was rebuilt (setSystemPrompt called)
        verify(agent, atLeast(changeCount)).setSystemPrompt(anyString());
        
        // Cleanup
        session.dispose();
    }

    /**
     * Test ResourceLoader implementation that supports change listeners.
     */
    private static class TestResourceLoader implements ResourceLoader {
        private final List<ResourceChangeListener> listeners = new CopyOnWriteArrayList<>();
        private final LoadSkillsResult skillsResult = new LoadSkillsResult(List.of(), List.of());
        private final LoadPromptsResult promptsResult = new LoadPromptsResult(List.of(), List.of());
        
        @Override
        public java.util.concurrent.CompletableFuture<Void> reload() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
        
        @Override
        public com.pi.coding.extension.LoadExtensionsResult getExtensions() {
            return new com.pi.coding.extension.LoadExtensionsResult(List.of(), List.of());
        }
        
        @Override
        public LoadSkillsResult getSkills() {
            return skillsResult;
        }
        
        @Override
        public LoadPromptsResult getPrompts() {
            return promptsResult;
        }
        
        @Override
        public List<ContextFile> getAgentsFiles() {
            return List.of();
        }
        
        @Override
        public String getSystemPrompt() {
            return null;
        }
        
        @Override
        public List<String> getAppendSystemPrompt() {
            return List.of();
        }
        
        @Override
        public List<ResourceDiagnostic> getDiagnostics() {
            return List.of();
        }
        
        @Override
        public void extendResources(ResourceExtensionPaths paths) {
        }
        
        @Override
        public void addChangeListener(ResourceChangeListener listener) {
            listeners.add(listener);
        }
        
        @Override
        public void removeChangeListener(ResourceChangeListener listener) {
            listeners.remove(listener);
        }
        
        /**
         * Simulate a resource change by notifying all listeners.
         */
        void simulateChange() {
            ResourceChangeEvent event = ResourceChangeEvent.of(
                skillsResult, promptsResult, List.of()
            );
            for (ResourceChangeListener listener : listeners) {
                listener.onResourceChanged(event);
            }
        }
    }
}
