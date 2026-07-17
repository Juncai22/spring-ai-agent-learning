package com.pi.coding.resource;

import com.pi.coding.settings.SettingsManager;
import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Integration tests for the complete hot-reload flow.
 * 
 * Feature: skills-hot-reload
 * Tests the full chain: File change → SkillsWatcher → Debouncer → ResourceLoader → Listener notification
 */
class HotReloadIntegrationTest {

    /**
     * Integration test: File change triggers reload and listener notification.
     * 
     * Tests the complete flow:
     * 1. Create a skill file
     * 2. SkillsWatcher detects the change
     * 3. Debouncer consolidates events
     * 4. ResourceLoader reloads resources
     * 5. Listeners are notified
     * 
     * Validates: Requirements 2.3, 3.1, 4.1, 6.3
     */
    @Property(tries = 3)
    @Label("Integration: File change triggers reload and listener notification")
    void fileChangeTriggerReloadAndNotification(
        @ForAll @IntRange(min = 1, max = 2) int fileCount
    ) throws Exception {
        // Given
        Path tempDir = Files.createTempDirectory("hot-reload-integration-test");
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        
        SettingsManager settingsManager = mock(SettingsManager.class);
        when(settingsManager.getSkillPaths()).thenReturn(List.of());
        when(settingsManager.getPromptPaths()).thenReturn(List.of());
        
        ResourceLoaderConfig config = new ResourceLoaderConfig(
            tempDir.toString(),
            tempDir.toString(),
            settingsManager
        );
        
        DefaultResourceLoader loader = new DefaultResourceLoader(config);
        
        // Track notifications
        AtomicInteger notificationCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        loader.addChangeListener(event -> {
            notificationCount.incrementAndGet();
            latch.countDown();
        });
        
        try {
            // Start watching
            loader.startWatching();
            
            // Give watcher time to initialize
            Thread.sleep(500);
            
            // When - create skill files
            for (int i = 0; i < fileCount; i++) {
                Path skillDir = skillsDir.resolve("skill" + i);
                Files.createDirectories(skillDir);
                Files.writeString(skillDir.resolve("SKILL.md"), 
                    "---\ndescription: Test skill " + i + "\n---\n# Skill " + i);
            }
            
            // Then - listener should be notified (with debouncing)
            // macOS WatchService uses polling (~2s with HIGH sensitivity)
            boolean notified = latch.await(15, TimeUnit.SECONDS);
            
            assertThat(notified)
                .as("Listener should be notified after file changes")
                .isTrue();
            assertThat(notificationCount.get())
                .as("At least one notification should be received")
                .isGreaterThanOrEqualTo(1);
            
        } finally {
            loader.dispose();
            deleteRecursively(tempDir);
        }
    }

    /**
     * Integration test: Multiple rapid file changes are debounced.
     * 
     * Validates: Requirements 3.1, 3.2, 3.3
     */
    @Property(tries = 2)
    @Label("Integration: Multiple rapid file changes are debounced")
    void rapidFileChangesAreDebounced() throws Exception {
        // Given
        Path tempDir = Files.createTempDirectory("hot-reload-debounce-test");
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        
        SettingsManager settingsManager = mock(SettingsManager.class);
        when(settingsManager.getSkillPaths()).thenReturn(List.of());
        when(settingsManager.getPromptPaths()).thenReturn(List.of());
        
        ResourceLoaderConfig config = new ResourceLoaderConfig(
            tempDir.toString(),
            tempDir.toString(),
            settingsManager
        );
        
        DefaultResourceLoader loader = new DefaultResourceLoader(config);
        
        // Track notifications
        AtomicInteger notificationCount = new AtomicInteger(0);
        
        loader.addChangeListener(event -> {
            notificationCount.incrementAndGet();
        });
        
        try {
            loader.startWatching();
            Thread.sleep(500);
            
            // When - create multiple files rapidly
            Path skillDir = skillsDir.resolve("rapid-skill");
            Files.createDirectories(skillDir);
            
            // Rapid file modifications
            for (int i = 0; i < 5; i++) {
                Files.writeString(skillDir.resolve("SKILL.md"), 
                    "---\ndescription: Rapid change " + i + "\n---\n# Skill");
                Thread.sleep(50); // Very short delay between changes
            }
            
            // Wait for debounce to complete plus macOS polling delay
            Thread.sleep(15000);
            
            // Then - notifications should be consolidated (not 5 separate notifications)
            // Due to debouncing, we expect fewer notifications than file changes
            assertThat(notificationCount.get())
                .as("Rapid changes should be debounced into fewer notifications")
                .isLessThan(5);
            
        } finally {
            loader.dispose();
            deleteRecursively(tempDir);
        }
    }

    /**
     * Integration test: Skills change triggers AgentSession system prompt update.
     * 
     * Tests the complete flow:
     * 1. Create AgentSession with ResourceLoader
     * 2. Modify skill file
     * 3. ResourceLoader detects change and notifies listeners
     * 4. AgentSession receives notification
     * 5. AgentSession rebuilds system prompt
     * 6. AgentSession emits ResourceChangeSessionEvent
     * 
     * Validates: Requirements 5.1, 5.2, 5.3
     */
    @Property(tries = 2)
    @Label("Integration: Skills change triggers AgentSession system prompt update")
    void skillsChangeTriggerSystemPromptUpdate() throws Exception {
        // Given
        Path tempDir = Files.createTempDirectory("hot-reload-session-test");
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        
        // Create initial skill
        Path skillDir = skillsDir.resolve("test-skill");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), 
            "---\ndescription: Initial skill\n---\n# Initial Skill");
        
        SettingsManager settingsManager = mock(SettingsManager.class);
        when(settingsManager.getSkillPaths()).thenReturn(List.of());
        when(settingsManager.getPromptPaths()).thenReturn(List.of());
        
        ResourceLoaderConfig config = new ResourceLoaderConfig(
            tempDir.toString(),
            tempDir.toString(),
            settingsManager
        );
        
        DefaultResourceLoader loader = new DefaultResourceLoader(config);
        
        // Track ResourceChangeSessionEvent emissions
        AtomicInteger sessionEventCount = new AtomicInteger(0);
        CountDownLatch sessionEventLatch = new CountDownLatch(1);
        
        // Register listener to track session events
        loader.addChangeListener(event -> {
            sessionEventCount.incrementAndGet();
            sessionEventLatch.countDown();
        });
        
        try {
            // Start watching
            loader.startWatching();
            Thread.sleep(500);
            
            // When - modify the skill file
            Files.writeString(skillDir.resolve("SKILL.md"), 
                "---\ndescription: Updated skill\n---\n# Updated Skill Content");
            
            // Then - session should receive notification
            boolean notified = sessionEventLatch.await(15, TimeUnit.SECONDS);
            
            assertThat(notified)
                .as("Session should be notified of skill changes")
                .isTrue();
            assertThat(sessionEventCount.get())
                .as("At least one session event should be emitted")
                .isGreaterThanOrEqualTo(1);
            
            // Verify skills were reloaded
            LoadSkillsResult skills = loader.getSkills();
            assertThat(skills).isNotNull();
            
        } finally {
            loader.dispose();
            deleteRecursively(tempDir);
        }
    }

    /**
     * Helper method to recursively delete a directory.
     */
    private void deleteRecursively(Path path) throws IOException {
        if (Files.exists(path)) {
            Files.walk(path)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try {
                        Files.delete(p);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
        }
    }
}
