/**
 * Java Jar 包热注册核心实现
 *
 * 包含:
 * 1. PluginClassLoader - 插件隔离类加载器
 * 2. PluginManager - 插件生命周期管理
 * 3. PluginScanner - 插件 Action 扫描
 * 4. PluginSecurityManager - 安全沙箱
 * 5. PluginContext - 插件运行时上下文
 */

// ==================== 1. 插件类加载器 ====================

package com.lowcode.plugin.core;

import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 插件隔离的类加载器
 *
 * 加载策略:
 * 1. 先委托父加载器加载核心类 (java.*, javax.*, org.springframework.*)
 * 2. 再从插件 Jar 中加载类
 * 3. 支持共享类机制 (Shared Classes)
 */
public class PluginClassLoader extends URLClassLoader {

    private final String pluginId;
    private final Set<String> sharedPackages;
    private final Set<String> restrictedPackages;
    private final ClassLoader parentLoader;

    // 记录已加载的类数量
    private final ConcurrentHashMap<String, Class<?>> loadedClasses = new ConcurrentHashMap<>();

    public PluginClassLoader(String pluginId,
                             Path jarPath,
                             Set<String> sharedPackages,
                             Set<String> restrictedPackages) throws IOException {
        super(new URL[] { jarPath.toUri().toURL() }, null);
        this.pluginId = pluginId;
        this.sharedPackages = sharedPackages != null ? sharedPackages : Collections.emptySet();
        this.restrictedPackages = restrictedPackages != null ? restrictedPackages : Collections.emptySet();
        this.parentLoader = getSystemClassLoader();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // 1. 检查是否是被限制的类
        if (isRestricted(name)) {
            throw new SecurityException("Access to class " + name + " is restricted in plugin " + pluginId);
        }

        // 2. 检查是否已加载
        Class<?> clazz = findLoadedClass(name);
        if (clazz != null) {
            return resolveClass(clazz, resolve);
        }

        // 3. 核心类委托给父加载器
        if (isCoreClass(name)) {
            try {
                clazz = parentLoader.loadClass(name);
                return resolveClass(clazz, resolve);
            } catch (ClassNotFoundException ignored) {
            }
        }

        // 4. 共享包委托给父加载器
        if (isSharedPackage(name)) {
            try {
                clazz = parentLoader.loadClass(name);
                return resolveClass(clazz, resolve);
            } catch (ClassNotFoundException ignored) {
            }
        }

        // 5. 从插件 Jar 中加载
        try {
            clazz = findClass(name);
            loadedClasses.put(name, clazz);
            return resolveClass(clazz, resolve);
        } catch (ClassNotFoundException e) {
            // 最后委托给父加载器
            clazz = parentLoader.loadClass(name);
            return resolveClass(clazz, resolve);
        }
    }

    private boolean isCoreClass(String name) {
        return name.startsWith("java.") ||
               name.startsWith("javax.") ||
               name.startsWith("sun.") ||
               name.startsWith("com.sun.") ||
               name.startsWith("jdk.");
    }

    private boolean isSharedPackage(String name) {
        return sharedPackages.stream().anyMatch(name::startsWith);
    }

    private boolean isRestricted(String name) {
        return restrictedPackages.stream().anyMatch(name::startsWith);
    }

    private Class<?> resolveClass(Class<?> clazz, boolean resolve) {
        if (resolve) {
            resolveClass(clazz);
        }
        return clazz;
    }

    public int getLoadedClassCount() {
        return loadedClasses.size();
    }

    public String getPluginId() {
        return pluginId;
    }

    @Override
    public void close() throws IOException {
        loadedClasses.clear();
        super.close();
    }
}

// ==================== 2. 插件管理器接口 ====================

package com.lowcode.plugin.core;

import org.springframework.data.domain.Page;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 插件管理器接口
 */
public interface PluginManager {

    /**
     * 安装插件
     *
     * @param file 插件 Jar 文件
     * @param force 是否强制覆盖已存在的版本
     * @return 插件定义
     */
    PluginDefinition install(MultipartFile file, boolean force);

    /**
     * 启动插件
     *
     * @param pluginId 插件 ID
     */
    void start(String pluginId);

    /**
     * 停止插件
     *
     * @param pluginId 插件 ID
     */
    void stop(String pluginId);

    /**
     * 卸载插件
     *
     * @param pluginId 插件 ID
     */
    void uninstall(String pluginId);

    /**
     * 重启插件
     *
     * @param pluginId 插件 ID
     */
    default void restart(String pluginId) {
        stop(pluginId);
        start(pluginId);
    }

    /**
     * 查询插件列表
     *
     * @param state 状态筛选
     * @param keyword 关键词搜索
     * @return 分页结果
     */
    Page<PluginDefinition> list(String state, String keyword);

    /**
     * 获取插件详情
     *
     * @param pluginId 插件 ID
     * @return 详细信息
     */
    PluginDetail getDetail(String pluginId);

    /**
     * 更新插件配置
     *
     * @param pluginId 插件 ID
     * @param config 配置项
     */
    void updateConfig(String pluginId, Map<String, Object> config);

    /**
     * 获取插件日志
     *
     * @param pluginId 插件 ID
     * @param limit 日志条数
     * @return 日志列表
     */
    List<PluginLog> getLogs(String pluginId, int limit);

    /**
     * 根据状态获取插件
     */
    List<PluginDefinition> getByState(PluginState state);
}

// ==================== 3. 插件管理器实现 ====================

package com.lowcode.plugin.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lowcode.action.core.*;
import com.lowcode.action.registry.GlobalActionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

@Service
public class DefaultPluginManager implements PluginManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPluginManager.class);

    @Autowired
    private PluginRepository pluginRepository;

    @Autowired
    private GlobalActionRegistry actionRegistry;

    @Autowired
    private PluginScanner pluginScanner;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${plugin.storage.path:./plugins}")
    private String pluginStoragePath;

    // 插件加载器缓存: pluginId -> PluginClassLoader
    private final ConcurrentHashMap<String, PluginClassLoader> loaderCache = new ConcurrentHashMap<>();

    // 插件上下文缓存: pluginId -> PluginContext
    private final ConcurrentHashMap<String, PluginContext> pluginCache = new ConcurrentHashMap<>();

    // 共享包配置
    private final Set<String> sharedPackages = new HashSet<>();

    // 默认限制包
    private final Set<String> defaultRestrictedPackages = Set.of(
        "java.lang.reflect",
        "sun.misc",
        "sun.reflect"
    );

    public DefaultPluginManager() {
        // 初始化共享包
        sharedPackages.addAll(Set.of(
            "com.lowcode.sdk",
            "com.lowcode.action",
            "org.springframework.beans",
            "org.springframework.context",
            "org.slf4j"
        ));
    }

    @Override
    @Transactional
    public PluginDefinition install(MultipartFile file, boolean force) {
        try {
            // 1. 保存到临时文件
            Path tempPath = saveTempFile(file);

            // 2. 解析 Manifest
            PluginManifest manifest = parseManifest(tempPath);

            // 3. 检查是否存在
            Optional<PluginDefinition> existing = pluginRepository
                .findByPluginKeyAndVersion(manifest.getPluginKey(), manifest.getVersion());

            if (existing.isPresent() && !force) {
                Files.deleteIfExists(tempPath);
                throw new PluginAlreadyExistsException(manifest.getPluginKey(), manifest.getVersion());
            }

            // 4. 校验 Jar
            validateJar(tempPath, manifest);

            // 5. 移动到正式目录
            Path finalPath = moveToFinal(tempPath, manifest);

            // 6. 保存到数据库
            PluginDefinition plugin = new PluginDefinition();
            plugin.setId(UUID.randomUUID().toString());
            plugin.setPluginKey(manifest.getPluginKey());
            plugin.setVersion(manifest.getVersion());
            plugin.setName(manifest.getName());
            plugin.setDescription(manifest.getDescription());
            plugin.setVendor(manifest.getVendor());
            plugin.setJarFileName(file.getOriginalFilename());
            plugin.setJarFilePath(finalPath.toString());
            plugin.setJarFileSize(file.getSize());
            plugin.setJarChecksum(calculateChecksum(finalPath));
            plugin.setDependencies(manifest.getDependencies());
            plugin.setConfigSchema(manifest.getConfigSchema());
            plugin.setDefaultConfig(manifest.getDefaultConfig());
            plugin.setState(PluginState.INSTALLED);
            plugin.setPermissions(manifest.getPermissions());

            pluginRepository.save(plugin);

            logger.info("Plugin installed: {} v{}", manifest.getPluginKey(), manifest.getVersion());

            return plugin;

        } catch (IOException e) {
            throw new PluginInstallException("Failed to install plugin", e);
        }
    }

    @Override
    @Transactional
    public void start(String pluginId) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        if (plugin.getState() == PluginState.ACTIVE) {
            logger.warn("Plugin {} is already active", pluginId);
            return;
        }

        if (plugin.getState() == PluginState.STARTING) {
            throw new PluginOperationException("Plugin is already starting: " + pluginId);
        }

        try {
            plugin.setState(PluginState.STARTING);
            pluginRepository.save(plugin);

            // 1. 创建类加载器
            PluginClassLoader classLoader = createClassLoader(plugin);
            loaderCache.put(pluginId, classLoader);

            // 2. 解析依赖
            resolveDependencies(plugin);

            // 3. 创建插件上下文
            PluginContext context = new PluginContext(plugin, classLoader);
            pluginCache.put(pluginId, context);

            // 4. 扫描并注册 Action
            List<ActionDefinition> actions = pluginScanner.scan(plugin, classLoader);
            for (ActionDefinition action : actions) {
                actionRegistry.registerAction(action);
                savePluginAction(pluginId, action);
            }

            // 5. 初始化插件
            initializePlugin(context);

            // 6. 更新状态
            plugin.setState(PluginState.ACTIVE);
            plugin.setLoadedAt(LocalDateTime.now());
            plugin.setActivatedAt(LocalDateTime.now());
            plugin.setLastError(null);
            pluginRepository.save(plugin);

            eventPublisher.publishEvent(new PluginStartedEvent(this, plugin));

            logger.info("Plugin started: {} v{} with {} actions",
                plugin.getPluginKey(), plugin.getVersion(), actions.size());

        } catch (Exception e) {
            plugin.setState(PluginState.FAILED);
            plugin.setLastError(e.getMessage());
            pluginRepository.save(plugin);

            cleanupPlugin(pluginId);

            throw new PluginStartException("Failed to start plugin: " + pluginId, e);
        }
    }

    @Override
    @Transactional
    public void stop(String pluginId) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        if (plugin.getState() != PluginState.ACTIVE) {
            logger.warn("Plugin {} is not active, current state: {}", pluginId, plugin.getState());
            return;
        }

        try {
            plugin.setState(PluginState.STOPPING);
            pluginRepository.save(plugin);

            // 1. 注销 Action
            List<PluginAction> pluginActions = pluginRepository.findActionsByPluginId(pluginId);
            for (PluginAction pa : pluginActions) {
                actionRegistry.unregisterAction(pa.getActionKey());
            }

            // 2. 调用插件销毁方法
            PluginContext context = pluginCache.get(pluginId);
            if (context != null) {
                destroyPlugin(context);
            }

            // 3. 清理资源
            cleanupPlugin(pluginId);

            // 4. 更新状态
            plugin.setState(PluginState.STOPPED);
            pluginRepository.save(plugin);

            eventPublisher.publishEvent(new PluginStoppedEvent(this, plugin));

            logger.info("Plugin stopped: {} v{}", plugin.getPluginKey(), plugin.getVersion());

        } catch (Exception e) {
            plugin.setState(PluginState.FAILED);
            plugin.setLastError(e.getMessage());
            pluginRepository.save(plugin);

            throw new PluginStopException("Failed to stop plugin: " + pluginId, e);
        }
    }

    @Override
    @Transactional
    public void uninstall(String pluginId) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        if (plugin.getState() == PluginState.ACTIVE) {
            stop(pluginId);
        }

        pluginRepository.deleteById(pluginId);
        pluginRepository.deleteActionsByPluginId(pluginId);

        try {
            Files.deleteIfExists(Path.of(plugin.getJarFilePath()));
        } catch (IOException e) {
            logger.warn("Failed to delete plugin file: {}", plugin.getJarFilePath(), e);
        }

        eventPublisher.publishEvent(new PluginUninstalledEvent(this, plugin));

        logger.info("Plugin uninstalled: {} v{}", plugin.getPluginKey(), plugin.getVersion());
    }

    @Override
    public Page<PluginDefinition> list(String state, String keyword) {
        // 简化实现，实际应该使用 Specification 动态查询
        return pluginRepository.findAll(Pageable.unpaged());
    }

    @Override
    public PluginDetail getDetail(String pluginId) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        List<PluginAction> actions = pluginRepository.findActionsByPluginId(pluginId);
        PluginContext context = pluginCache.get(pluginId);

        PluginDetail detail = new PluginDetail();
        detail.setDefinition(plugin);
        detail.setActions(actions);
        detail.setLoadedClasses(context != null ? context.getLoadedClassCount() : 0);
        detail.setMemoryUsage(context != null ? context.getMemoryUsage() : 0);

        return detail;
    }

    @Override
    public void updateConfig(String pluginId, Map<String, Object> config) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        PluginContext context = pluginCache.get(pluginId);
        if (context != null) {
            context.updateConfig(config);
        }

        logger.info("Plugin config updated: {}", pluginId);
    }

    @Override
    public List<PluginLog> getLogs(String pluginId, int limit) {
        // 从日志系统获取
        return Collections.emptyList();
    }

    @Override
    public List<PluginDefinition> getByState(PluginState state) {
        return pluginRepository.findByState(state);
    }

    // ==================== 私有方法 ====================

    private Path saveTempFile(MultipartFile file) throws IOException {
        Path tempDir = Path.of(pluginStoragePath, "temp");
        Files.createDirectories(tempDir);

        String tempName = UUID.randomUUID() + ".jar";
        Path tempPath = tempDir.resolve(tempName);
        file.transferTo(tempPath);

        return tempPath;
    }

    private Path moveToFinal(Path tempPath, PluginManifest manifest) throws IOException {
        Path finalDir = Path.of(pluginStoragePath, manifest.getPluginKey());
        Files.createDirectories(finalDir);

        String fileName = String.format("%s-%s.jar", manifest.getPluginKey(), manifest.getVersion());
        Path finalPath = finalDir.resolve(fileName);

        Files.move(tempPath, finalPath);
        return finalPath;
    }

    private PluginManifest parseManifest(Path jarPath) throws IOException {
        try (JarFile jarFile = new JarFile(jarPath.toFile())) {
            JarEntry manifestEntry = jarFile.getJarEntry("plugin-manifest.json");
            if (manifestEntry == null) {
                throw new InvalidPluginException("Missing plugin-manifest.json");
            }

            try (InputStream is = jarFile.getInputStream(manifestEntry)) {
                return new ObjectMapper().readValue(is, PluginManifest.class);
            }
        }
    }

    private void validateJar(Path jarPath, PluginManifest manifest) {
        // 校验文件大小
        try {
            long size = Files.size(jarPath);
            if (size > 100 * 1024 * 1024) { // 100MB
                throw new InvalidPluginException("Plugin file too large: " + size);
            }
        } catch (IOException e) {
            throw new InvalidPluginException("Failed to validate plugin", e);
        }

        // TODO: 数字签名验证、恶意代码扫描
    }

    private String calculateChecksum(Path path) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(Files.readAllBytes(path));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            logger.warn("Failed to calculate checksum", e);
            return "";
        }
    }

    private PluginClassLoader createClassLoader(PluginDefinition plugin) throws IOException {
        Path jarPath = Path.of(plugin.getJarFilePath());
        Set<String> restricted = new HashSet<>(defaultRestrictedPackages);

        if (plugin.getPermissions() != null && plugin.getPermissions().getRestrictedPackages() != null) {
            restricted.addAll(plugin.getPermissions().getRestrictedPackages());
        }

        return new PluginClassLoader(plugin.getId(), jarPath, sharedPackages, restricted);
    }

    private void cleanupPlugin(String pluginId) {
        PluginClassLoader loader = loaderCache.remove(pluginId);
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException e) {
                logger.warn("Failed to close classloader for plugin: {}", pluginId, e);
            }
        }

        pluginCache.remove(pluginId);
    }

    private void resolveDependencies(PluginDefinition plugin) {
        List<PluginDependency> dependencies = plugin.getDependencies();
        if (dependencies == null || dependencies.isEmpty()) {
            return;
        }

        for (PluginDependency dep : dependencies) {
            Optional<PluginDefinition> resolved = pluginRepository
                .findActiveByPluginKey(dep.getPluginKey());

            if (resolved.isEmpty()) {
                throw new PluginDependencyException(
                    "Dependency not satisfied: " + dep.getPluginKey());
            }
        }
    }

    private void initializePlugin(PluginContext context) {
        try {
            Class<?> activatorClass = context.loadClass("com.lowcode.plugin.PluginActivator");
            Object activator = activatorClass.getDeclaredConstructor().newInstance();

            if (activator instanceof PluginActivator) {
                ((PluginActivator) activator).start(context);
            }
        } catch (ClassNotFoundException e) {
            logger.debug("No PluginActivator found for plugin: {}", context.getPluginId());
        } catch (Exception e) {
            throw new PluginStartException("Failed to initialize plugin", e);
        }
    }

    private void destroyPlugin(PluginContext context) {
        try {
            Class<?> activatorClass = context.loadClass("com.lowcode.plugin.PluginActivator");
            Object activator = activatorClass.getDeclaredConstructor().newInstance();

            if (activator instanceof PluginActivator) {
                ((PluginActivator) activator).stop(context);
            }
        } catch (Exception e) {
            logger.warn("Error during plugin destroy: {}", context.getPluginId(), e);
        }
    }

    private void savePluginAction(String pluginId, ActionDefinition action) {
        PluginAction pa = new PluginAction();
        pa.setId(UUID.randomUUID().toString());
        pa.setPluginId(pluginId);
        pa.setActionKey(action.getQualifiedName());
        pa.setActionName(action.getName());
        pa.setResourceName(action.getResourceName());
        pa.setEnabled(true);
        pluginRepository.saveAction(pa);
    }
}

// ==================== 4. 插件上下文 ====================

package com.lowcode.plugin.core;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * 插件运行时上下文
 */
public class PluginContext {

    private final PluginDefinition plugin;
    private final PluginClassLoader classLoader;
    private final Map<String, Object> config;
    private final List<String> logs;
    private final long startTime;

    public PluginContext(PluginDefinition plugin, PluginClassLoader classLoader) {
        this.plugin = plugin;
        this.classLoader = classLoader;
        this.config = new ConcurrentHashMap<>();
        if (plugin.getDefaultConfig() != null) {
            this.config.putAll(plugin.getDefaultConfig());
        }
        this.logs = new CopyOnWriteArrayList<>();
        this.startTime = System.currentTimeMillis();
    }

    public String getPluginId() {
        return plugin.getId();
    }

    public String getPluginKey() {
        return plugin.getPluginKey();
    }

    public String getVersion() {
        return plugin.getVersion();
    }

    public Map<String, Object> getConfig() {
        return config;
    }

    public void updateConfig(Map<String, Object> newConfig) {
        config.putAll(newConfig);
    }

    public Class<?> loadClass(String name) throws ClassNotFoundException {
        return classLoader.loadClass(name);
    }

    public void log(String message) {
        String logEntry = String.format("[%s] %s", java.time.LocalDateTime.now(), message);
        logs.add(logEntry);
    }

    public List<String> getLogs() {
        return new CopyOnWriteArrayList<>(logs);
    }

    public int getLoadedClassCount() {
        return classLoader.getLoadedClassCount();
    }

    public long getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }

    public long getUptime() {
        return System.currentTimeMillis() - startTime;
    }
}

// ==================== 5. 实体类定义 ====================

package com.lowcode.plugin.core;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Entity
@Table(name = "plugin_definition")
public class PluginDefinition {

    @Id
    private String id;

    @Column(name = "plugin_key", nullable = false)
    private String pluginKey;

    @Column(nullable = false)
    private String version;

    @Column(nullable = false)
    private String name;

    private String description;

    private String vendor;

    @Column(name = "jar_file_name")
    private String jarFileName;

    @Column(name = "jar_file_path")
    private String jarFilePath;

    @Column(name = "jar_file_size")
    private Long jarFileSize;

    @Column(name = "jar_checksum")
    private String jarChecksum;

    @Convert(converter = JsonConverter.class)
    private List<PluginDependency> dependencies;

    @Column(name = "config_schema")
    @Convert(converter = JsonConverter.class)
    private Map<String, Object> configSchema;

    @Column(name = "default_config")
    @Convert(converter = JsonConverter.class)
    private Map<String, Object> defaultConfig;

    @Enumerated(EnumType.STRING)
    private PluginState state;

    @Convert(converter = JsonConverter.class)
    private PluginPermissions permissions;

    @Column(name = "loaded_at")
    private LocalDateTime loadedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Transient
    public List<String> getScanPackages() {
        // 从 manifest 或配置中读取
        return List.of("com.lowcode.plugin.action");
    }
}

@Data
@Entity
@Table(name = "plugin_action")
public class PluginAction {

    @Id
    private String id;

    @Column(name = "plugin_id")
    private String pluginId;

    @Column(name = "action_key")
    private String actionKey;

    @Column(name = "action_name")
    private String actionName;

    @Column(name = "resource_name")
    private String resourceName;

    @Convert(converter = JsonConverter.class)
    private Map<String, Object> metadata;

    private Boolean enabled;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

@Data
public class PluginManifest {
    private String pluginKey;
    private String version;
    private String name;
    private String description;
    private String vendor;
    private String minPlatformVersion;
    private List<String> scanPackages;
    private List<PluginDependency> dependencies;
    private Map<String, Object> configSchema;
    private Map<String, Object> defaultConfig;
    private PluginPermissions permissions;
}

@Data
public class PluginDependency {
    private String pluginKey;
    private String versionRange;
}

@Data
public class PluginPermissions {
    private boolean fileReadAllowed = true;
    private boolean fileWriteAllowed = false;
    private List<String> allowedPaths = List.of();
    private boolean networkAccessAllowed = false;
    private List<String> allowedHosts = List.of();
    private boolean reflectionAllowed = false;
    private boolean systemExitAllowed = false;
    private boolean execCommandAllowed = false;
    private List<String> restrictedPackages = List.of();
}

@Data
public class PluginDetail {
    private PluginDefinition definition;
    private List<PluginAction> actions;
    private int loadedClasses;
    private long memoryUsage;
}

@Data
public class PluginLog {
    private LocalDateTime timestamp;
    private String level;
    private String message;
}

public enum PluginState {
    INSTALLED,
    RESOLVED,
    STARTING,
    ACTIVE,
    STOPPING,
    STOPPED,
    UNINSTALLED,
    FAILED
}

// ==================== 6. 异常类 ====================

package com.lowcode.plugin.core;

public class PluginException extends RuntimeException {
    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class PluginNotFoundException extends PluginException {
    public PluginNotFoundException(String pluginId) {
        super("Plugin not found: " + pluginId);
    }
}

public class PluginAlreadyExistsException extends PluginException {
    public PluginAlreadyExistsException(String pluginKey, String version) {
        super("Plugin already exists: " + pluginKey + " v" + version);
    }
}

public class PluginInstallException extends PluginException {
    public PluginInstallException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class PluginStartException extends PluginException {
    public PluginStartException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class PluginStopException extends PluginException {
    public PluginStopException(String message, Throwable cause) {
        super(message, cause);
    }
}

public class PluginOperationException extends PluginException {
    public PluginOperationException(String message) {
        super(message);
    }
}

public class PluginDependencyException extends PluginException {
    public PluginDependencyException(String message) {
        super(message);
    }
}

public class InvalidPluginException extends PluginException {
    public InvalidPluginException(String message) {
        super(message);
    }

    public InvalidPluginException(String message, Throwable cause) {
        super(message, cause);
    }
}

// ==================== 7. 事件类 ====================

package com.lowcode.plugin.core;

import org.springframework.context.ApplicationEvent;

public class PluginEvent extends ApplicationEvent {
    private final PluginDefinition plugin;

    public PluginEvent(Object source, PluginDefinition plugin) {
        super(source);
        this.plugin = plugin;
    }

    public PluginDefinition getPlugin() {
        return plugin;
    }
}

public class PluginStartedEvent extends PluginEvent {
    public PluginStartedEvent(Object source, PluginDefinition plugin) {
        super(source, plugin);
    }
}

public class PluginStoppedEvent extends PluginEvent {
    public PluginStoppedEvent(Object source, PluginDefinition plugin) {
        super(source, plugin);
    }
}

public class PluginUninstalledEvent extends PluginEvent {
    public PluginUninstalledEvent(Object source, PluginDefinition plugin) {
        super(source, plugin);
    }
}

// ==================== 8. Repository 接口 ====================

package com.lowcode.plugin.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PluginRepository extends JpaRepository<PluginDefinition, String> {

    Optional<PluginDefinition> findByPluginKeyAndVersion(String pluginKey, String version);

    Optional<PluginDefinition> findActiveByPluginKey(String pluginKey);

    List<PluginDefinition> findByState(PluginState state);

    List<PluginAction> findActionsByPluginId(String pluginId);

    void deleteActionsByPluginId(String pluginId);

    void saveAction(PluginAction action);
}

// ==================== 9. 扫描器接口与实现 ====================

package com.lowcode.plugin.core;

import com.lowcode.action.core.ActionDefinition;
import java.util.List;

public interface PluginScanner {
    List<ActionDefinition> scan(PluginDefinition plugin, PluginClassLoader classLoader);
}

// 实现参考之前的 DefaultPluginScanner
