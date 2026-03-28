# Java Jar 包热注册机制设计

## 1. 概述

### 1.1 设计目标

支持在运行时动态加载、卸载和更新外部 Jar 包中的 Action 组件，无需重启服务。

### 1.2 核心特性

- **热加载**：运行时动态加载 Jar 包
- **类隔离**：每个 Jar 包独立的 ClassLoader，避免依赖冲突
- **版本管理**：支持多版本共存和灰度切换
- **安全沙箱**：限制插件的权限和资源使用
- **无损卸载**：支持安全卸载已加载的插件

### 1.3 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                          Action Registry Service                            │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      Plugin Manager (插件管理器)                      │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐     │   │
│  │  │  上传存储   │  │  加载器池   │  │  生命周期   │  │  安全沙箱   │     │   │
│  │  │  Storage   │  │  LoaderPool│  │  Lifecycle │  │  Sandbox   │     │   │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                   Plugin Registry (插件注册表)                        │   │
│  │                                                                     │   │
│  │   Plugin-1 (v1.0.0)          Plugin-2 (v2.1.0)                      │   │
│  │   ┌─────────────────┐        ┌─────────────────┐                   │   │
│  │   │ PluginClassLoader│       │ PluginClassLoader│                   │   │
│  │   │  ┌───────────┐  │        │  ┌───────────┐  │                   │   │
│  │   │  │  Jar File │  │        │  │  Jar File │  │                   │   │
│  │   │  │  ├─Action1│  │        │  │  ├─Action3│  │                   │   │
│  │   │  │  ├─Action2│  │        │  │  ├─Action4│  │                   │   │
│  │   │  │  └─Service│  │        │  │  └─Service│  │                   │   │
│  │   │  └───────────┘  │        │  └───────────┘  │                   │   │
│  │   └─────────────────┘        └─────────────────┘                   │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Global Registry (全局注册表)                       │   │
│  │                                                                     │   │
│  │   ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐           │   │
│  │   │ 内置Action│  │Plugin-1  │  │Plugin-2  │  │ 远程Action│           │   │
│  │   │ Action-A │  │ Action1  │  │ Action3  │  │ Action-X │           │   │
│  │   │ Action-B │  │ Action2  │  │ Action4  │  │ Action-Y │           │   │
│  │   └──────────┘  └──────────┘  └──────────┘  └──────────┘           │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 核心概念

| 概念 | 说明 | 示例 |
|------|------|------|
| **Plugin** | 热加载的 Jar 包插件 | file-storage-plugin.jar |
| **PluginClassLoader** | 插件隔离的类加载器 | 父类加载器为 AppClassLoader |
| **PluginContext** | 插件运行时上下文 | 包含配置、状态、元数据 |
| **PluginState** | 插件生命周期状态 | INSTALLED → RESOLVED → ACTIVE → STOPPED |

---

## 3. 数据模型

### 3.1 插件定义表

```sql
CREATE TABLE plugin_definition (
    id                  VARCHAR(64) PRIMARY KEY,
    plugin_key          VARCHAR(128) NOT NULL,      -- 插件标识: vendor.plugin-name
    version             VARCHAR(32) NOT NULL,
    name                VARCHAR(100) NOT NULL,
    description         TEXT,
    vendor              VARCHAR(100),

    -- 文件信息
    jar_file_name       VARCHAR(255) NOT NULL,
    jar_file_path       VARCHAR(500) NOT NULL,
    jar_file_size       BIGINT,
    jar_checksum        VARCHAR(64),                -- SHA-256

    -- 依赖信息
    dependencies        JSON,                       -- [{"pluginKey": "", "versionRange": ""}]

    -- 配置
    config_schema       JSON,                       -- 配置项 Schema
    default_config      JSON,

    -- 状态
    state               VARCHAR(20) NOT NULL DEFAULT 'INSTALLED',
    -- INSTALLED: 已上传
    -- RESOLVED: 依赖已解析
    -- STARTING: 启动中
    -- ACTIVE: 运行中
    -- STOPPING: 停止中
    -- STOPPED: 已停止
    -- UNINSTALLED: 已卸载
    -- FAILED: 失败

    -- 运行时信息
    loaded_at           DATETIME,
    activated_at        DATETIME,
    last_error          TEXT,

    -- 权限配置
    permissions         JSON,                       -- 沙箱权限

    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_plugin_key_version (plugin_key, version),
    INDEX idx_state (state)
);

-- 插件 Action 映射表
CREATE TABLE plugin_action (
    id                  VARCHAR(64) PRIMARY KEY,
    plugin_id           VARCHAR(64) NOT NULL,
    action_key          VARCHAR(255) NOT NULL,      -- namespace.action:version
    action_name         VARCHAR(64) NOT NULL,
    resource_name       VARCHAR(64) NOT NULL,

    -- Action 元数据
    metadata            JSON,

    -- 状态
    enabled             BOOLEAN DEFAULT TRUE,

    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,

    FOREIGN KEY (plugin_id) REFERENCES plugin_definition(id),
    UNIQUE KEY uk_plugin_action (plugin_id, action_key),
    INDEX idx_action_key (action_key)
);
```

---

## 4. API 设计

### 4.1 插件管理 API

```java
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    @Autowired
    private PluginManager pluginManager;

    /**
     * 上传插件
     */
    @PostMapping
    public ResponseEntity<PluginDefinition> uploadPlugin(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "force", defaultValue = "false") boolean force) {

        PluginDefinition plugin = pluginManager.install(file, force);
        return ResponseEntity.status(HttpStatus.CREATED).body(plugin);
    }

    /**
     * 启动插件
     */
    @PostMapping("/{pluginId}/start")
    public ResponseEntity<Void> startPlugin(@PathVariable String pluginId) {
        pluginManager.start(pluginId);
        return ResponseEntity.ok().build();
    }

    /**
     * 停止插件
     */
    @PostMapping("/{pluginId}/stop")
    public ResponseEntity<Void> stopPlugin(@PathVariable String pluginId) {
        pluginManager.stop(pluginId);
        return ResponseEntity.ok().build();
    }

    /**
     * 卸载插件
     */
    @DeleteMapping("/{pluginId}")
    public ResponseEntity<Void> uninstallPlugin(@PathVariable String pluginId) {
        pluginManager.uninstall(pluginId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 查询插件列表
     */
    @GetMapping
    public ResponseEntity<Page<PluginDefinition>> listPlugins(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String keyword) {

        return ResponseEntity.ok(pluginManager.list(state, keyword));
    }

    /**
     * 获取插件详情
     */
    @GetMapping("/{pluginId}")
    public ResponseEntity<PluginDetail> getPlugin(@PathVariable String pluginId) {
        return ResponseEntity.ok(pluginManager.getDetail(pluginId));
    }

    /**
     * 更新插件配置
     */
    @PutMapping("/{pluginId}/config")
    public ResponseEntity<Void> updateConfig(
            @PathVariable String pluginId,
            @RequestBody Map<String, Object> config) {

        pluginManager.updateConfig(pluginId, config);
        return ResponseEntity.ok().build();
    }

    /**
     * 获取插件日志
     */
    @GetMapping("/{pluginId}/logs")
    public ResponseEntity<List<PluginLog>> getPluginLogs(
            @PathVariable String pluginId,
            @RequestParam(defaultValue = "100") int limit) {

        return ResponseEntity.ok(pluginManager.getLogs(pluginId, limit));
    }
}
```

---

## 5. 核心实现

### 5.1 插件类加载器

```java
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

    // 父加载器 (通常是 AppClassLoader)
    private final ClassLoader parentLoader;

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
            throw new ClassNotFoundException("Access to class " + name + " is restricted in plugin " + pluginId);
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
            } catch (ClassNotFoundException e) {
                // 父加载器找不到，继续尝试自己加载
            }
        }

        // 4. 共享包委托给父加载器
        if (isSharedPackage(name)) {
            try {
                clazz = parentLoader.loadClass(name);
                return resolveClass(clazz, resolve);
            } catch (ClassNotFoundException e) {
                // 父加载器找不到，尝试自己加载
            }
        }

        // 5. 从插件 Jar 中加载
        try {
            clazz = findClass(name);
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

    /**
     * 获取插件加载的类数量 (用于监控)
     */
    public int getLoadedClassCount() {
        // 通过反射获取已加载的类数量
        return -1; // 需要具体实现
    }

    @Override
    public void close() throws IOException {
        super.close();
    }
}
```

### 5.2 插件管理器

```java
/**
 * 插件生命周期管理器
 */
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
    private PluginStorage pluginStorage;

    @Autowired
    private ApplicationEventPublisher eventPublisher;

    @Value("${plugin.storage.path:./plugins}")
    private String pluginStoragePath;

    // 插件加载器缓存: pluginId -> PluginClassLoader
    private final ConcurrentHashMap<String, PluginClassLoader> loaderCache = new ConcurrentHashMap<>();

    // 插件实例缓存: pluginId -> PluginContext
    private final ConcurrentHashMap<String, PluginContext> pluginCache = new ConcurrentHashMap<>();

    @Override
    @Transactional
    public PluginDefinition install(MultipartFile file, boolean force) {
        try {
            // 1. 保存文件到临时目录
            Path tempPath = pluginStorage.saveTemp(file);

            // 2. 解析 Jar 包元数据
            PluginManifest manifest = parseManifest(tempPath);

            // 3. 检查是否已存在
            Optional<PluginDefinition> existing = pluginRepository
                .findByPluginKeyAndVersion(manifest.getPluginKey(), manifest.getVersion());

            if (existing.isPresent() && !force) {
                pluginStorage.deleteTemp(tempPath);
                throw new PluginAlreadyExistsException(manifest.getPluginKey(), manifest.getVersion());
            }

            // 4. 校验 Jar 包完整性
            validateJar(tempPath, manifest);

            // 5. 移动到正式目录
            Path finalPath = pluginStorage.moveToFinal(tempPath, manifest);

            // 6. 创建数据库记录
            PluginDefinition plugin = PluginDefinition.builder()
                .id(UUID.randomUUID().toString())
                .pluginKey(manifest.getPluginKey())
                .version(manifest.getVersion())
                .name(manifest.getName())
                .description(manifest.getDescription())
                .vendor(manifest.getVendor())
                .jarFileName(file.getOriginalFilename())
                .jarFilePath(finalPath.toString())
                .jarFileSize(file.getSize())
                .jarChecksum(calculateChecksum(finalPath))
                .dependencies(manifest.getDependencies())
                .configSchema(manifest.getConfigSchema())
                .defaultConfig(manifest.getDefaultConfig())
                .state(PluginState.INSTALLED)
                .permissions(manifest.getPermissions())
                .build();

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

        // 状态检查
        if (plugin.getState() == PluginState.ACTIVE) {
            logger.warn("Plugin {} is already active", pluginId);
            return;
        }

        if (plugin.getState() == PluginState.STARTING) {
            throw new PluginOperationException("Plugin is already starting: " + pluginId);
        }

        try {
            // 更新状态
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
            actions.forEach(action -> {
                actionRegistry.registerAction(action);
                savePluginAction(pluginId, action);
            });

            // 5. 初始化插件 (调用插件的初始化方法)
            initializePlugin(context);

            // 6. 更新状态
            plugin.setState(PluginState.ACTIVE);
            plugin.setLoadedAt(LocalDateTime.now());
            plugin.setActivatedAt(LocalDateTime.now());
            plugin.setLastError(null);
            pluginRepository.save(plugin);

            // 发布事件
            eventPublisher.publishEvent(new PluginStartedEvent(this, plugin));

            logger.info("Plugin started: {} v{}", plugin.getPluginKey(), plugin.getVersion());

        } catch (Exception e) {
            plugin.setState(PluginState.FAILED);
            plugin.setLastError(e.getMessage());
            pluginRepository.save(plugin);

            // 清理资源
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
            pluginActions.forEach(pa -> {
                actionRegistry.unregisterAction(pa.getActionKey());
            });

            // 2. 调用插件的销毁方法
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

        // 如果插件在运行，先停止
        if (plugin.getState() == PluginState.ACTIVE) {
            stop(pluginId);
        }

        // 删除数据库记录
        pluginRepository.deleteById(pluginId);
        pluginRepository.deleteActionsByPluginId(pluginId);

        // 删除文件
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
        // 实现分页查询
        return pluginRepository.findAll(Pageable.unpaged());
    }

    @Override
    public PluginDetail getDetail(String pluginId) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        List<PluginAction> actions = pluginRepository.findActionsByPluginId(pluginId);
        PluginContext context = pluginCache.get(pluginId);

        return PluginDetail.builder()
            .definition(plugin)
            .actions(actions)
            .loadedClasses(context != null ? context.getLoadedClassCount() : 0)
            .memoryUsage(context != null ? context.getMemoryUsage() : 0)
            .build();
    }

    @Override
    public void updateConfig(String pluginId, Map<String, Object> config) {
        PluginDefinition plugin = pluginRepository.findById(pluginId)
            .orElseThrow(() -> new PluginNotFoundException(pluginId));

        // 验证配置
        validateConfig(plugin.getConfigSchema(), config);

        // 更新配置
        PluginContext context = pluginCache.get(pluginId);
        if (context != null) {
            context.updateConfig(config);
        }

        logger.info("Plugin config updated: {}", pluginId);
    }

    // ==================== 私有方法 ====================

    private PluginClassLoader createClassLoader(PluginDefinition plugin) throws IOException {
        Path jarPath = Path.of(plugin.getJarFilePath());

        // 从配置中读取共享包和限制包
        Set<String> sharedPackages = loadSharedPackages();
        Set<String> restrictedPackages = loadRestrictedPackages(plugin);

        return new PluginClassLoader(
            plugin.getId(),
            jarPath,
            sharedPackages,
            restrictedPackages
        );
    }

    private void cleanupPlugin(String pluginId) {
        // 移除类加载器
        PluginClassLoader loader = loaderCache.remove(pluginId);
        if (loader != null) {
            try {
                loader.close();
            } catch (IOException e) {
                logger.warn("Failed to close classloader for plugin: {}", pluginId, e);
            }
        }

        // 移除上下文
        pluginCache.remove(pluginId);
    }

    private void resolveDependencies(PluginDefinition plugin) {
        // 检查依赖是否满足
        List<PluginDependency> dependencies = plugin.getDependencies();
        for (PluginDependency dep : dependencies) {
            Optional<PluginDefinition> resolved = pluginRepository
                .findActiveByPluginKey(dep.getPluginKey());

            if (resolved.isEmpty()) {
                throw new PluginDependencyException(
                    "Dependency not satisfied: " + dep.getPluginKey());
            }

            // 版本范围检查
            if (!dep.getVersionRange().matches(resolved.get().getVersion())) {
                throw new PluginDependencyException(
                    "Dependency version mismatch: " + dep.getPluginKey() +
                    " required " + dep.getVersionRange() +
                    " but found " + resolved.get().getVersion());
            }
        }
    }

    private void initializePlugin(PluginContext context) {
        // 调用插件的初始化接口
        try {
            Class<?> activatorClass = context.loadClass("com.lowcode.plugin.PluginActivator");
            Object activator = activatorClass.getDeclaredConstructor().newInstance();

            if (activator instanceof PluginActivator) {
                ((PluginActivator) activator).start(context);
            }
        } catch (ClassNotFoundException e) {
            // 插件没有提供 Activator，忽略
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
        // 校验文件签名 (可选)
        // 校验文件大小
        // 校验恶意代码 (使用 ASM 扫描危险操作)
    }

    private String calculateChecksum(Path path) throws IOException {
        // 计算 SHA-256
        return ""; // 具体实现
    }

    private void validateConfig(JsonNode schema, Map<String, Object> config) {
        // 使用 JSON Schema 验证配置
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

    private Set<String> loadSharedPackages() {
        // 从配置加载共享包列表
        return Set.of(
            "com.lowcode.sdk",
            "com.lowcode.action",
            "org.springframework.beans",
            "org.springframework.context"
        );
    }

    private Set<String> loadRestrictedPackages(PluginDefinition plugin) {
        // 合并默认限制和插件特定的限制
        Set<String> restricted = new HashSet<>();
        restricted.add("java.lang.reflect");  // 限制反射
        restricted.add("sun.misc");
        restricted.add("java.net.Socket");    // 限制网络 (除非显式授权)

        // 从插件权限配置中添加限制
        if (plugin.getPermissions() != null) {
            List<String> additional = plugin.getPermissions().getRestrictedPackages();
            if (additional != null) {
                restricted.addAll(additional);
            }
        }

        return restricted;
    }
}
```

### 5.3 插件扫描器

```java
/**
 * 扫描插件 Jar 包中的 Action
 */
@Component
public class DefaultPluginScanner implements PluginScanner {

    private static final Logger logger = LoggerFactory.getLogger(DefaultPluginScanner.class);

    @Override
    public List<ActionDefinition> scan(PluginDefinition plugin, PluginClassLoader classLoader) {
        List<ActionDefinition> actions = new ArrayList<>();

        try {
            // 1. 从 manifest 中读取扫描路径
            List<String> scanPackages = plugin.getScanPackages();
            if (scanPackages == null || scanPackages.isEmpty()) {
                scanPackages = List.of("com.lowcode.plugin.action"); // 默认路径
            }

            // 2. 扫描 Jar 包中的类
            Path jarPath = Path.of(plugin.getJarFilePath());

            try (JarFile jarFile = new JarFile(jarPath.toFile())) {
                Enumeration<JarEntry> entries = jarFile.entries();

                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    String entryName = entry.getName();

                    // 只处理 .class 文件
                    if (!entryName.endsWith(".class")) {
                        continue;
                    }

                    // 转换为类名
                    String className = entryName.replace("/", ".")
                                                .replace(".class", "");

                    // 检查是否在扫描包路径下
                    if (!isInScanPackages(className, scanPackages)) {
                        continue;
                    }

                    // 加载类并扫描 Action
                    try {
                        Class<?> clazz = classLoader.loadClass(className);
                        List<ActionDefinition> classActions = scanClass(clazz, plugin);
                        actions.addAll(classActions);
                    } catch (Throwable e) {
                        logger.warn("Failed to scan class: {}", className, e);
                    }
                }
            }

            logger.info("Scanned {} actions from plugin {} v{}",
                actions.size(), plugin.getPluginKey(), plugin.getVersion());

            return actions;

        } catch (IOException e) {
            throw new PluginScanException("Failed to scan plugin: " + plugin.getPluginKey(), e);
        }
    }

    private List<ActionDefinition> scanClass(Class<?> clazz, PluginDefinition plugin) {
        List<ActionDefinition> actions = new ArrayList<>();

        // 检查是否有 @ActionResource 注解
        ActionResource resourceAnnotation = clazz.getAnnotation(ActionResource.class);
        if (resourceAnnotation == null) {
            return actions;
        }

        String resourceName = resourceAnnotation.value();
        String namespace = resourceAnnotation.namespace();

        // 创建 Bean 实例
        Object bean = createBean(clazz);
        if (bean == null) {
            logger.warn("Failed to create bean for class: {}", clazz.getName());
            return actions;
        }

        // 扫描所有方法
        for (Method method : clazz.getDeclaredMethods()) {
            Action actionAnnotation = method.getAnnotation(Action.class);
            if (actionAnnotation == null) {
                continue;
            }

            try {
                ActionDefinition action = buildActionDefinition(
                    plugin, namespace, resourceName,
                    actionAnnotation, method, bean
                );
                actions.add(action);
            } catch (Exception e) {
                logger.warn("Failed to build action for method: {}", method.getName(), e);
            }
        }

        return actions;
    }

    private ActionDefinition buildActionDefinition(
            PluginDefinition plugin,
            String namespace,
            String resourceName,
            Action actionAnnotation,
            Method method,
            Object target) {

        String actionName = actionAnnotation.name();
        String qualifiedName = String.format("%s.%s.%s:%s",
            namespace, resourceName, actionName, plugin.getVersion());

        // 构建处理器
        ActionHandler handler = createHandler(method, target, plugin.getId());

        return DefaultActionDefinition.builder()
            .name(actionName)
            .title(actionAnnotation.title())
            .description(actionAnnotation.description())
            .resourceName(resourceName)
            .serviceName(plugin.getPluginKey())
            .metadata(buildMetadata(method))
            .handler(handler)
            .remote(false)
            .build();
    }

    private ActionHandler createHandler(Method method, Object target, String pluginId) {
        return context -> {
            try {
                // 解析参数
                Object[] args = resolveArguments(method, context);

                // 调用方法
                Object result = method.invoke(target, args);

                return result;
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                if (cause instanceof ActionException) {
                    throw (ActionException) cause;
                }
                throw new ActionExecutionException("Action execution failed in plugin: " + pluginId, cause);
            } catch (Exception e) {
                throw new ActionExecutionException("Action execution failed in plugin: " + pluginId, e);
            }
        };
    }

    private Object[] resolveArguments(Method method, ActionContext context) {
        Parameter[] parameters = method.getParameters();
        Object[] args = new Object[parameters.length];

        for (int i = 0; i < parameters.length; i++) {
            Parameter param = parameters[i];
            Class<?> paramType = param.getType();

            // 如果是 ActionContext 类型，直接注入
            if (ActionContext.class.isAssignableFrom(paramType)) {
                args[i] = context;
                continue;
            }

            // 从参数中获取
            ActionParam paramAnnotation = param.getAnnotation(ActionParam.class);
            if (paramAnnotation != null) {
                String paramName = paramAnnotation.value();
                Object value = context.getParam(paramName);

                // 类型转换
                args[i] = convertType(value, paramType);
            }
        }

        return args;
    }

    private Object convertType(Object value, Class<?> targetType) {
        if (value == null) {
            return null;
        }

        if (targetType.isInstance(value)) {
            return value;
        }

        // 基本类型转换
        // 使用 Spring ConversionService 或其他转换器
        return value;
    }

    private Object createBean(Class<?> clazz) {
        try {
            // 简单实现：使用无参构造器
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            logger.error("Failed to create bean for class: {}", clazz.getName(), e);
            return null;
        }
    }

    private boolean isInScanPackages(String className, List<String> scanPackages) {
        return scanPackages.stream().anyMatch(className::startsWith);
    }

    private ActionMetadata buildMetadata(Method method) {
        // 构建输入/输出 Schema
        return ActionMetadata.builder()
            .version("1.0.0")
            .build();
    }
}
```

### 5.4 安全沙箱

```java
/**
 * 插件安全沙箱
 * 限制插件的操作权限
 */
@Component
public class PluginSecurityManager {

    // 线程本地存储当前执行的插件 ID
    private static final ThreadLocal<String> currentPlugin = new ThreadLocal<>();

    /**
     * 在插件上下文中执行代码
     */
    public <T> T executeInSandbox(String pluginId, Callable<T> callable) throws Exception {
        String previousPlugin = currentPlugin.get();
        currentPlugin.set(pluginId);

        try {
            // 设置安全管理器 (如果需要)
            return callable.call();
        } finally {
            if (previousPlugin == null) {
                currentPlugin.remove();
            } else {
                currentPlugin.set(previousPlugin);
            }
        }
    }

    /**
     * 检查当前是否有插件在执行
     */
    public static boolean isInPluginContext() {
        return currentPlugin.get() != null;
    }

    /**
     * 获取当前执行的插件 ID
     */
    public static String getCurrentPluginId() {
        return currentPlugin.get();
    }

    /**
     * 检查插件是否有特定权限
     */
    public boolean checkPermission(String pluginId, PluginPermission permission) {
        PluginDefinition plugin = getPlugin(pluginId);
        if (plugin == null || plugin.getPermissions() == null) {
            return false;
        }

        return plugin.getPermissions().hasPermission(permission);
    }

    /**
     * 检查文件系统访问权限
     */
    public void checkFileAccess(String pluginId, String path, FileAccessType accessType) {
        PluginDefinition plugin = getPlugin(pluginId);

        // 默认只允许访问插件自己的工作目录
        List<String> allowedPaths = plugin.getPermissions() != null
            ? plugin.getPermissions().getAllowedPaths()
            : Collections.emptyList();

        boolean allowed = allowedPaths.stream().anyMatch(path::startsWith);

        if (!allowed) {
            throw new SecurityException(
                "Plugin " + pluginId + " does not have permission to " +
                accessType + " path: " + path);
        }
    }

    /**
     * 检查网络访问权限
     */
    public void checkNetworkAccess(String pluginId, String host, int port) {
        PluginDefinition plugin = getPlugin(pluginId);

        boolean networkAllowed = plugin.getPermissions() != null
            && plugin.getPermissions().isNetworkAccessAllowed();

        if (!networkAllowed) {
            throw new SecurityException(
                "Plugin " + pluginId + " does not have network access permission");
        }

        // 检查白名单
        List<String> allowedHosts = plugin.getPermissions().getAllowedHosts();
        if (allowedHosts != null && !allowedHosts.isEmpty()) {
            boolean hostAllowed = allowedHosts.stream().anyMatch(host::matches);
            if (!hostAllowed) {
                throw new SecurityException(
                    "Plugin " + pluginId + " cannot access host: " + host);
            }
        }
    }

    private PluginDefinition getPlugin(String pluginId) {
        // 从数据库或缓存获取
        return null;
    }
}

/**
 * 插件权限定义
 */
@Data
public class PluginPermissions {

    // 文件系统权限
    private boolean fileReadAllowed = true;
    private boolean fileWriteAllowed = false;
    private List<String> allowedPaths = new ArrayList<>();

    // 网络权限
    private boolean networkAccessAllowed = false;
    private List<String> allowedHosts = new ArrayList<>();

    // 反射权限
    private boolean reflectionAllowed = false;

    // 系统权限
    private boolean systemExitAllowed = false;
    private boolean execCommandAllowed = false;

    // 限制访问的包
    private List<String> restrictedPackages = new ArrayList<>();

    public boolean hasPermission(PluginPermission permission) {
        return switch (permission) {
            case FILE_READ -> fileReadAllowed;
            case FILE_WRITE -> fileWriteAllowed;
            case NETWORK_ACCESS -> networkAccessAllowed;
            case REFLECTION -> reflectionAllowed;
            case SYSTEM_EXIT -> systemExitAllowed;
            case EXEC_COMMAND -> execCommandAllowed;
        };
    }
}

enum PluginPermission {
    FILE_READ,
    FILE_WRITE,
    NETWORK_ACCESS,
    REFLECTION,
    SYSTEM_EXIT,
    EXEC_COMMAND
}

enum FileAccessType {
    READ,
    WRITE,
    DELETE
}
```

---

## 6. 配置文件

### 6.1 插件清单 (plugin-manifest.json)

```json
{
  "pluginKey": "com.example.file-storage",
  "version": "1.2.0",
  "name": "文件存储插件",
  "description": "提供文件上传、下载、预览等功能",
  "vendor": "Example Corp",
  "minPlatformVersion": "2.0.0",

  "scanPackages": [
    "com.example.filestorage.action"
  ],

  "dependencies": [
    {
      "pluginKey": "com.example.common-utils",
      "versionRange": "[1.0.0,2.0.0)"
    }
  ],

  "configSchema": {
    "type": "object",
    "properties": {
      "storageType": {
        "type": "string",
        "enum": ["local", "oss", "s3"],
        "default": "local"
      },
      "maxFileSize": {
        "type": "integer",
        "default": 104857600
      }
    }
  },

  "defaultConfig": {
    "storageType": "local",
    "maxFileSize": 104857600
  },

  "permissions": {
    "fileReadAllowed": true,
    "fileWriteAllowed": true,
    "networkAccessAllowed": true,
    "allowedHosts": ["*.aliyun.com", "*.amazonaws.com"],
    "reflectionAllowed": false,
    "systemExitAllowed": false,
    "execCommandAllowed": false,
    "restrictedPackages": [
      "java.lang.reflect",
      "sun.misc"
    ]
  }
}
```

### 6.2 应用配置 (application.yml)

```yaml
plugin:
  # 存储配置
  storage:
    path: ./plugins
    max-file-size: 100MB

  # 类加载器配置
  classloader:
    # 共享的包 (这些包由父加载器加载)
    shared-packages:
      - com.lowcode.sdk
      - com.lowcode.action
      - org.springframework.beans
      - org.springframework.context
      - org.slf4j

    # 默认限制访问的包
    restricted-packages:
      - java.lang.reflect
      - sun.misc
      - java.net.Socket

  # 安全沙箱配置
  security:
    enabled: true
    default-file-access: read-only
    default-network-access: false

  # 热加载配置
  hot-reload:
    enabled: true
    watch-interval: 5000ms

  # 资源限制
  resource:
    max-memory-per-plugin: 128MB
    max-threads-per-plugin: 10
```

---

## 7. 生命周期状态图

```
                    upload/install
    ┌─────────────────────────────────┐
    │                                 ▼
    │                           ┌──────────┐
    │                           │ INSTALLED│
    │                           └────┬─────┘
    │                                │ resolve dependencies
    │                                ▼
    │                           ┌──────────┐
    │                           │ RESOLVED │
    │                           └────┬─────┘
    │                                │ start
    │                                ▼
    │  ┌─────────────────────┐  ┌──────────┐
    │  │◄────── error ───────┤  │ STARTING │
    │  │                     │  └────┬─────┘
    │  │                     │       │ init success
    │  ▼                     │       ▼
┌───┴───┐                   │  ┌──────────┐
│ FAILED│                   │  │  ACTIVE  │◄────────┐
└───────┘                   │  └────┬─────┘         │
                            │       │               │
                            │       │ stop          │ update config
                            │       ▼               │
                            │  ┌──────────┐         │
                            │  │ STOPPING │         │
                            │  └────┬─────┘         │
                            │       │               │
                            │       ▼               │
                            │  ┌──────────┐         │
                            └──┤ STOPPED  ├─────────┘
                               └────┬─────┘
                                    │ uninstall
                                    ▼
                               ┌──────────┐
                               │UNINSTALLED│
                               └──────────┘
```

---

## 8. 使用示例

### 8.1 上传并启动插件

```bash
# 上传插件
curl -X POST http://localhost:8080/api/plugins \
  -F "file=@file-storage-plugin-1.2.0.jar"

# 响应
{
  "id": "plugin-123",
  "pluginKey": "com.example.file-storage",
  "version": "1.2.0",
  "state": "INSTALLED"
}

# 启动插件
curl -X POST http://localhost:8080/api/plugins/plugin-123/start

# 查看插件详情
curl http://localhost:8080/api/plugins/plugin-123

# 响应
{
  "definition": { ... },
  "actions": [
    {
      "actionKey": "storage.file.upload:1.2.0",
      "actionName": "upload"
    },
    {
      "actionKey": "storage.file.download:1.2.0",
      "actionName": "download"
    }
  ],
  "loadedClasses": 156,
  "memoryUsage": 24576000
}

# 停止插件
curl -X POST http://localhost:8080/api/plugins/plugin-123/stop

# 卸载插件
curl -X DELETE http://localhost:8080/api/plugins/plugin-123
```

### 8.2 插件代码示例

```java
// PluginActivator.java - 插件生命周期管理
package com.example.filestorage;

import com.lowcode.plugin.PluginActivator;
import com.lowcode.plugin.PluginContext;

public class FileStorageActivator implements PluginActivator {

    private FileStorageService service;

    @Override
    public void start(PluginContext context) {
        // 读取配置
        Map<String, Object> config = context.getConfig();
        String storageType = (String) config.get("storageType");

        // 初始化服务
        service = new FileStorageService(storageType);
        service.initialize();

        context.log("File storage plugin started with type: " + storageType);
    }

    @Override
    public void stop(PluginContext context) {
        // 清理资源
        if (service != null) {
            service.shutdown();
        }

        context.log("File storage plugin stopped");
    }
}

// FileResource.java - Action 定义
package com.example.filestorage.action;

import com.lowcode.action.annotation.*;

@ActionResource(
    value = "file",
    namespace = "storage",
    description = "文件管理"
)
public class FileResource {

    @Action(
        name = "upload",
        title = "上传文件",
        description = "上传文件到存储系统"
    )
    public UploadResult upload(
            @ActionParam("fileName") String fileName,
            @ActionParam("content") byte[] content,
            @ActionParam(value = "folder", defaultValue = "/") String folder) {

        // 实现上传逻辑
        return new UploadResult(fileId, url);
    }

    @Action(
        name = "download",
        title = "下载文件",
        description = "下载文件内容"
    )
    public byte[] download(
            @ActionParam("fileId") String fileId) {

        // 实现下载逻辑
        return content;
    }
}
```

---

## 9. 注意事项

### 9.1 类加载隔离

- 每个插件使用独立的 ClassLoader
- 核心类（Java 标准库、Spring 框架）由父加载器加载
- 插件间共享的接口/类需配置到 `shared-packages`

### 9.2 内存泄漏防范

- 插件卸载时清理所有线程
- 移除所有注册的监听器和回调
- 关闭插件创建的数据库连接等资源

### 9.3 安全风险

- 始终使用安全沙箱限制插件权限
- 对上传的 Jar 包进行病毒扫描
- 验证 Jar 包的数字签名（可选）
- 限制插件的内存和 CPU 使用

### 9.4 兼容性

- 插件需声明最低平台版本要求
- 插件依赖的其他插件需明确版本范围
- 平台升级时需检查插件兼容性
