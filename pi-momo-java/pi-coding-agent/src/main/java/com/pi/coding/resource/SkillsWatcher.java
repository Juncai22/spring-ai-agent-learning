package com.pi.coding.resource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;

import static java.nio.file.StandardWatchEventKinds.*;

/**
 * Skills 目录文件监听器。
 * 监控用户级和项目级 Skills 目录的文件变化。
 *
 * <p>监听器会监控以下目录：
 * <ul>
 *   <li>用户级 Skills 目录：{agentDir}/skills</li>
 *   <li>项目级 Skills 目录：{cwd}/.kiro/skills</li>
 * </ul>
 *
 * <p>当检测到文件创建、修改或删除时，会通过防抖器触发回调。
 * 防抖机制确保短时间内的多次文件变化只触发一次回调。
 *
 * <p>示例用法：
 * <pre>{@code
 * SkillsWatcherConfig config = new SkillsWatcherConfig(
 *     "/home/user/.kiro",
 *     "/path/to/project",
 *     () -> System.out.println("Skills changed!")
 * );
 * SkillsWatcher watcher = new SkillsWatcher(config);
 * watcher.start();
 * 
 * // ... 应用运行 ...
 * 
 * watcher.stop();
 * }</pre>
 */
public class SkillsWatcher {
    
    private static final Logger logger = LoggerFactory.getLogger(SkillsWatcher.class);
    
    private final SkillsWatcherConfig config;
    private final Debouncer debouncer;
    
    private volatile WatchService watchService;
    private volatile Thread watchThread;
    private volatile boolean running;
    private final Map<WatchKey, Path> watchKeys;
    
    /**
     * 创建 Skills 文件监听器。
     *
     * @param config 监听器配置
     */
    public SkillsWatcher(SkillsWatcherConfig config) {
        this.config = config;
        this.debouncer = new Debouncer(config.debounceDelayMs());
        this.watchKeys = new HashMap<>();
        this.running = false;
    }
    
    /**
     * 启动文件监听。
     *
     * <p>此方法会：
     * <ul>
     *   <li>创建 WatchService</li>
     *   <li>注册用户级和项目级 Skills 目录</li>
     *   <li>启动守护线程监听文件变化</li>
     * </ul>
     *
     * <p>如果监听器已经在运行，此方法不会有任何效果。
     */
    public synchronized void start() {
        if (running) {
            logger.debug("SkillsWatcher is already running");
            return;
        }
        
        try {
            watchService = FileSystems.getDefault().newWatchService();
            
            // 注册用户级 Skills 目录
            registerDirectoryTree(Paths.get(config.getUserSkillsDir()));
            
            // 注册项目级 Skills 目录
            registerDirectoryTree(Paths.get(config.getProjectSkillsDir()));
            
            running = true;
            
            // 启动监听线程
            watchThread = new Thread(this::watchLoop, "skills-watcher");
            watchThread.setDaemon(true);
            watchThread.start();
            
            logger.info("SkillsWatcher started, monitoring {} directories", watchKeys.size());
        } catch (IOException e) {
            logger.error("Failed to start SkillsWatcher", e);
            cleanup();
        }
    }
    
    /**
     * 停止文件监听并释放资源。
     *
     * <p>此方法会：
     * <ul>
     *   <li>停止监听线程</li>
     *   <li>关闭 WatchService</li>
     *   <li>取消所有待执行的防抖任务</li>
     * </ul>
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        
        running = false;
        
        // 中断监听线程
        if (watchThread != null) {
            watchThread.interrupt();
            try {
                watchThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            watchThread = null;
        }
        
        cleanup();
        logger.info("SkillsWatcher stopped");
    }
    
    /**
     * 检查监听器是否正在运行。
     *
     * @return 如果正在运行返回 true
     */
    public boolean isRunning() {
        return running;
    }
    
    /**
     * 递归注册目录及其子目录到 WatchService。
     */
    private void registerDirectoryTree(Path dir) {
        if (!Files.exists(dir)) {
            logger.debug("Skills directory does not exist, skipping: {}", dir);
            return;
        }
        
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path subDir, BasicFileAttributes attrs) {
                    registerDirectory(subDir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            logger.warn("Failed to walk directory tree: {}", dir, e);
        }
    }
    
    /**
     * 注册单个目录到 WatchService。
     * 使用 HIGH 敏感度以加速 macOS 上的文件监控（macOS 使用轮询机制）。
     */
    private void registerDirectory(Path dir) {
        try {
            // 使用 SensitivityWatchEventModifier.HIGH 来加速 macOS 上的轮询
            // macOS 默认轮询间隔是 10 秒，HIGH 可以降低到约 2 秒
            WatchEvent.Kind<?>[] events = {ENTRY_CREATE, ENTRY_MODIFY, ENTRY_DELETE};
            WatchKey key;
            try {
                // 尝试使用 HIGH 敏感度（仅在 Oracle/OpenJDK 上可用）
                @SuppressWarnings("unchecked")
                WatchEvent.Modifier high = (WatchEvent.Modifier) 
                    Class.forName("com.sun.nio.file.SensitivityWatchEventModifier")
                        .getField("HIGH")
                        .get(null);
                key = dir.register(watchService, events, high);
            } catch (Exception e) {
                // 如果不支持 SensitivityWatchEventModifier，使用默认注册
                key = dir.register(watchService, events);
            }
            watchKeys.put(key, dir);
            logger.debug("Registered watch for: {}", dir);
        } catch (IOException e) {
            logger.warn("Failed to register watch for {}: {}", dir, e.getMessage());
        }
    }
    
    /**
     * 监听循环，在守护线程中运行。
     */
    private void watchLoop() {
        while (running) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (ClosedWatchServiceException e) {
                break;
            }
            
            Path dir = watchKeys.get(key);
            if (dir == null) {
                key.reset();
                continue;
            }
            
            boolean hasRelevantChanges = false;
            
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                
                if (kind == OVERFLOW) {
                    hasRelevantChanges = true;
                    continue;
                }
                
                @SuppressWarnings("unchecked")
                WatchEvent<Path> pathEvent = (WatchEvent<Path>) event;
                Path filename = pathEvent.context();
                Path fullPath = dir.resolve(filename);
                
                logger.debug("File event: {} - {}", kind.name(), fullPath);
                
                // 如果是新创建的目录，递归注册
                if (kind == ENTRY_CREATE && Files.isDirectory(fullPath)) {
                    registerDirectoryTree(fullPath);
                }
                
                // 检查是否是相关的文件变化（.md 文件或目录）
                if (isRelevantChange(fullPath, kind)) {
                    hasRelevantChanges = true;
                }
            }
            
            // 如果有相关变化，通过防抖器触发回调
            if (hasRelevantChanges) {
                debouncer.submit(config.onChangeCallback());
            }
            
            // 重置 key 以继续接收事件
            boolean valid = key.reset();
            if (!valid) {
                watchKeys.remove(key);
                if (watchKeys.isEmpty()) {
                    logger.warn("All watch keys are invalid, stopping watcher");
                    break;
                }
            }
        }
    }
    
    /**
     * 检查文件变化是否与 Skills 相关。
     */
    private boolean isRelevantChange(Path path, WatchEvent.Kind<?> kind) {
        String filename = path.getFileName().toString();
        
        // SKILL.md 文件变化
        if (filename.equals("SKILL.md")) {
            return true;
        }
        
        // 其他 .md 文件变化
        if (filename.endsWith(".md")) {
            return true;
        }
        
        // 目录变化（可能包含 SKILL.md）
        if (kind == ENTRY_CREATE || kind == ENTRY_DELETE) {
            return Files.isDirectory(path) || !Files.exists(path);
        }
        
        return false;
    }
    
    /**
     * 清理资源。
     */
    private void cleanup() {
        // 关闭防抖器
        debouncer.shutdown();
        
        // 关闭 WatchService
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException e) {
                logger.warn("Error closing WatchService", e);
            }
            watchService = null;
        }
        
        // 清空 watch keys
        watchKeys.clear();
    }
}
