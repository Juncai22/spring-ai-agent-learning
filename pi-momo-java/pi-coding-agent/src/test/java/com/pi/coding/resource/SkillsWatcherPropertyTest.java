package com.pi.coding.resource;

import net.jqwik.api.*;
import net.jqwik.api.constraints.IntRange;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for {@link SkillsWatcher}.
 * 
 * Feature: skills-hot-reload
 */
class SkillsWatcherPropertyTest {

    /**
     * Property 3: 文件系统事件检测
     * For any file operation (create, modify, delete) in a monitored directory,
     * SkillsWatcher should detect the corresponding WatchEvent and trigger callback.
     * 
     * Validates: Requirements 2.3, 2.4, 2.5
     * 
     * Note: On macOS, WatchService uses polling with ~2 second interval (with HIGH sensitivity).
     */
    @Property(tries = 10)
    @Label("Feature: skills-hot-reload, Property 3: 文件系统事件检测")
    void detectsFileSystemEvents(
        @ForAll("fileOperations") String operation
    ) throws Exception {
        // Given
        Path tempDir = Files.createTempDirectory("skills-watcher-test");
        // Create both user-level and project-level skills directories
        Path userSkillsDir = tempDir.resolve("skills");
        Path projectSkillsDir = tempDir.resolve(".kiro/skills");
        Files.createDirectories(userSkillsDir);
        Files.createDirectories(projectSkillsDir);
        
        Path testFile = userSkillsDir.resolve("SKILL.md");
        
        // For modify and delete operations, create the file first BEFORE starting watcher
        if ("modify".equals(operation) || "delete".equals(operation)) {
            Files.writeString(testFile, "---\ndescription: initial\n---\n# Initial");
        }
        
        AtomicInteger callbackCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        SkillsWatcherConfig config = new SkillsWatcherConfig(
            tempDir.toString(),
            tempDir.toString(),
            50, // Shorter debounce for faster test
            () -> {
                callbackCount.incrementAndGet();
                latch.countDown();
            }
        );
        
        SkillsWatcher watcher = new SkillsWatcher(config);
        
        try {
            watcher.start();
            
            // Give watcher time to initialize
            Thread.sleep(300);
            
            // When - perform file operation on user skills dir
            switch (operation) {
                case "create" -> Files.writeString(testFile, "---\ndescription: test\n---\n# Test");
                case "modify" -> Files.writeString(testFile, "---\ndescription: modified\n---\n# Modified");
                case "delete" -> Files.delete(testFile);
            }
            
            // Then - callback should be triggered
            // macOS WatchService uses polling (~2s with HIGH sensitivity, ~10s default)
            boolean triggered = latch.await(12, TimeUnit.SECONDS);
            assertThat(triggered)
                .as("Callback should be triggered for operation: %s", operation)
                .isTrue();
            assertThat(callbackCount.get()).isGreaterThanOrEqualTo(1);
        } finally {
            watcher.stop();
            deleteRecursively(tempDir);
        }
    }
    
    @Provide
    Arbitrary<String> fileOperations() {
        return Arbitraries.of("create", "modify", "delete");
    }

    /**
     * Property 4: 子目录递归监控
     * For any newly created subdirectory in a monitored directory,
     * SkillsWatcher should automatically register it for monitoring.
     * 
     * Validates: Requirements 2.6
     * 
     * Note: On macOS, WatchService uses polling with ~2 second interval (with HIGH sensitivity).
     */
    @Property(tries = 5)
    @Label("Feature: skills-hot-reload, Property 4: 子目录递归监控")
    void recursivelyMonitorsNewSubdirectories(
        @ForAll @IntRange(min = 1, max = 3) int depth
    ) throws Exception {
        // Given
        Path tempDir = Files.createTempDirectory("skills-watcher-recursive-test");
        Path userSkillsDir = tempDir.resolve("skills");
        Path projectSkillsDir = tempDir.resolve(".kiro/skills");
        Files.createDirectories(userSkillsDir);
        Files.createDirectories(projectSkillsDir);
        
        AtomicInteger callbackCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(1);
        
        SkillsWatcherConfig config = new SkillsWatcherConfig(
            tempDir.toString(),
            tempDir.toString(),
            50, // Shorter debounce for faster test
            () -> {
                callbackCount.incrementAndGet();
                latch.countDown();
            }
        );
        
        SkillsWatcher watcher = new SkillsWatcher(config);
        
        try {
            watcher.start();
            Thread.sleep(300);
            
            // When - create nested subdirectory and file in user skills dir
            Path nestedDir = userSkillsDir;
            for (int i = 0; i < depth; i++) {
                nestedDir = nestedDir.resolve("level" + i);
            }
            Files.createDirectories(nestedDir);
            
            // Wait for directory registration - macOS polling needs time
            Thread.sleep(3000);
            
            // Create SKILL.md in the nested directory
            Path skillFile = nestedDir.resolve("SKILL.md");
            Files.writeString(skillFile, "---\ndescription: nested skill\n---\n# Nested");
            
            // Then - callback should be triggered for the nested file
            // macOS WatchService uses polling (~2s with HIGH sensitivity, ~10s default)
            boolean triggered = latch.await(12, TimeUnit.SECONDS);
            assertThat(triggered)
                .as("Callback should be triggered for nested directory at depth %d", depth)
                .isTrue();
        } finally {
            watcher.stop();
            deleteRecursively(tempDir);
        }
    }

    /**
     * Property 12: 生命周期资源清理
     * For any SkillsWatcher, after calling stop():
     * (1) WatchService should be closed
     * (2) All pending debounce timers should be cancelled
     * (3) isRunning() should return false
     * 
     * Validates: Requirements 7.3, 7.4, 7.5
     */
    @Property(tries = 20)
    @Label("Feature: skills-hot-reload, Property 12: 生命周期资源清理")
    void lifecycleResourceCleanup(
        @ForAll @IntRange(min = 0, max = 5) int pendingEvents
    ) throws Exception {
        // Given
        Path tempDir = Files.createTempDirectory("skills-watcher-lifecycle-test");
        Path skillsDir = tempDir.resolve("skills");
        Files.createDirectories(skillsDir);
        
        AtomicInteger callbackCount = new AtomicInteger(0);
        
        SkillsWatcherConfig config = new SkillsWatcherConfig(
            tempDir.toString(),
            tempDir.toString(),
            500, // Longer debounce to ensure pending events
            () -> callbackCount.incrementAndGet()
        );
        
        SkillsWatcher watcher = new SkillsWatcher(config);
        
        try {
            watcher.start();
            assertThat(watcher.isRunning()).isTrue();
            
            Thread.sleep(100);
            
            // Create some pending events
            for (int i = 0; i < pendingEvents; i++) {
                Path testFile = skillsDir.resolve("test" + i + ".md");
                Files.writeString(testFile, "test content " + i);
            }
            
            // When - stop immediately (before debounce completes)
            watcher.stop();
            
            // Then
            assertThat(watcher.isRunning()).isFalse();
            
            // Wait to ensure no callbacks are triggered after stop
            int countBeforeWait = callbackCount.get();
            Thread.sleep(700); // Longer than debounce delay
            int countAfterWait = callbackCount.get();
            
            // Callback count should not increase after stop
            assertThat(countAfterWait).isEqualTo(countBeforeWait);
        } finally {
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
