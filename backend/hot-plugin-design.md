# Java Jar 包热注册机制设计 (基于 PF4J + Spring)

## 1. 概述

### 1.1 设计目标

基于 [PF4J](https://github.com/pf4j/pf4j) 和 [pf4j-spring](https://github.com/clyoudu/pf4j-spring) 实现 Java Jar 包热注册能力，支持在运行时动态加载、卸载和更新 Action 插件，无需重启服务。

### 1.2 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| 插件框架 | PF4J 3.12+ | 成熟的 Java 插件框架 |
| Spring 集成 | pf4j-spring | PF4J 的 Spring 集成方案 |
| 类加载 | SpringPluginClassLoader | 支持 Spring Bean 的类加载器 |
| 生命周期 | DefaultPluginManager | PF4J 内置生命周期管理 |

### 1.3 核心特性

- **热加载**：运行时动态加载 Jar 包
- **Spring 集成**：插件内支持 Spring 注解和依赖注入
- **类隔离**：每个插件独立的 ClassLoader
- **版本管理**：支持多版本插件共存
- **无损卸载**：安全卸载已加载的插件

### 1.4 架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Action Registry Service                              │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Spring Plugin Manager (PF4J)                     │   │
│  │                                                                     │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐    │   │
│  │  │   Load     │  │   Start    │  │   Stop     │  │  Unload    │    │   │
│  │  │  Plugins   │  │  Plugins   │  │  Plugins   │  │  Plugins   │    │   │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘    │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Plugin Directory (插件目录)                       │   │
│  │                                                                     │   │
│  │   file-storage-plugin-1.0.0.jar                                    │   │
│  │   user-management-plugin-2.1.0.jar                                 │   │
│  │   order-service-plugin-1.5.0.jar                                   │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              Spring ApplicationContext (插件上下文)                   │   │
│  │                                                                     │   │
│  │   ┌─────────────────┐        ┌─────────────────┐                   │   │
│  │   │ Plugin-1 Context│        │ Plugin-2 Context│                   │   │
│  │   │  ┌───────────┐  │        │  ┌───────────┐  │                   │   │
│  │   │  │ @Service  │  │        │  │ @Service  │  │                   │   │
│  │   │  │ @Component│  │        │  │ @Component│  │                   │   │
│  │   │  │ @Autowired│  │        │  │ @Autowired│  │                   │   │
│  │   │  └───────────┘  │        │  └───────────┘  │                   │   │
│  │   └─────────────────┘        └─────────────────┘                   │   │
│  │                                                                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                    Global Action Registry                           │   │
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

| 概念 | 说明 | 对应 PF4J 组件 |
|------|------|----------------|
| **Plugin** | 热加载的 Jar 包插件 | `Plugin` 接口 |
| **Extension** | 插件暴露的扩展点 | `@Extension` 注解 |
| **ExtensionPoint** | 扩展点接口定义 | `ExtensionPoint` 接口 |
| **PluginManager** | 插件生命周期管理 | `SpringPluginManager` |
| **PluginState** | 插件状态 | `PluginState` 枚举 |

### 2.1 PF4J 生命周期状态

```
CREATED → RESOLVED → STARTED → STOPPED → UNLOADED
   ↑          ↑          ↑          ↑
   └──────────┴──────────┴──────────┘
           (可逆向转换)
```

| 状态 | 说明 |
|------|------|
| CREATED | 插件已创建，但未解析依赖 |
| RESOLVED | 依赖已解析，可以启动 |
| STARTED | 插件已启动，正在运行 |
| STOPPED | 插件已停止 |
| UNLOADED | 插件已卸载 |
| FAILED | 插件启动/运行失败 |

---

## 3. 项目结构

```
project/
├── action-api/                          # 插件 API 模块 (共享)
│   ├── src/main/java/com/example/action/
│   │   ├── ActionPlugin.java            # 插件扩展点接口
│   │   ├── ActionDefinition.java        # Action 定义
│   │   └── ActionExecutor.java          # Action 执行接口
│   └── pom.xml
│
├── action-server/                       # 主应用
│   ├── src/main/java/com/example/server/
│   │   ├── ActionServerApplication.java
│   │   ├── config/
│   │   │   └── PluginConfig.java        # PF4J Spring 配置
│   │   ├── controller/
│   │   │   └── PluginController.java    # 插件管理 API
│   │   └── service/
│   │       └── ActionDispatchService.java # Action 分发
│   └── pom.xml
│
├── plugins/                             # 插件示例
│   ├── file-storage-plugin/
│   │   ├── src/main/java/
│   │   │   └── FileStoragePlugin.java   # 插件实现
│   │   └── pom.xml
│   └── user-plugin/
│       └── ...
│
└── pom.xml
```

---

## 4. 核心实现

### 4.1 插件 API 定义 (action-api)

```java
package com.example.action;

import org.pf4j.ExtensionPoint;
import java.util.List;
import java.util.Map;

/**
 * Action 插件扩展点
 * 所有 Action 插件必须实现此接口
 */
public interface ActionPlugin extends ExtensionPoint {

    /**
     * 获取插件命名空间
     */
    String getNamespace();

    /**
     * 获取插件版本
     */
    String getVersion();

    /**
     * 获取插件提供的 Actions
     */
    List<ActionDefinition> getActions();

    /**
     * 执行 Action
     */
    Object execute(String actionName, Map<String, Object> params);
}
```

```java
package com.example.action;

import lombok.Data;
import java.util.Map;

/**
 * Action 定义
 */
@Data
public class ActionDefinition {
    private String name;
    private String title;
    private String description;
    private Map<String, Object> inputSchema;
    private Map<String, Object> outputSchema;

    public ActionDefinition(String name, String title) {
        this.name = name;
        this.title = title;
    }
}
```

### 4.2 主应用配置 (action-server)

#### Maven 依赖

```xml
<dependencies>
    <!-- 插件 API -->
    <dependency>
        <groupId>com.example</groupId>
        <artifactId>action-api</artifactId>
        <version>${project.version}</version>
    </dependency>

    <!-- PF4J Spring -->
    <dependency>
        <groupId>org.pf4j</groupId>
        <artifactId>pf4j-spring</artifactId>
        <version>0.9.0</version>
    </dependency>

    <!-- Spring Boot -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
</dependencies>
```

#### PF4J Spring 配置

```java
package com.example.server.config;

import org.pf4j.spring.SpringPluginManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Paths;

/**
 * PF4J Spring 配置
 */
@Configuration
public class PluginConfig {

    /**
     * 配置 SpringPluginManager
     */
    @Bean
    public SpringPluginManager pluginManager() {
        // 插件存放目录
        return new SpringPluginManager(
            Paths.get("./plugins")
        );
    }

    /**
     * 启动时加载所有插件
     */
    @Bean
    public PluginLoader pluginLoader(SpringPluginManager pluginManager) {
        return new PluginLoader(pluginManager);
    }
}
```

```java
package com.example.server.config;

import org.pf4j.spring.SpringPluginManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * 插件加载器
 */
@Component
public class PluginLoader implements CommandLineRunner {

    private final SpringPluginManager pluginManager;

    public PluginLoader(SpringPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @Override
    public void run(String... args) {
        // 加载所有插件
        pluginManager.loadPlugins();

        // 启动所有插件
        pluginManager.startPlugins();

        System.out.println("Loaded " + pluginManager.getPlugins().size() + " plugins");
    }
}
```

### 4.3 Action 分发服务

```java
package com.example.server.service;

import com.example.action.ActionDefinition;
import com.example.action.ActionPlugin;
import org.pf4j.spring.SpringPluginManager;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Action 分发服务
 */
@Service
public class ActionDispatchService {

    private final SpringPluginManager pluginManager;

    public ActionDispatchService(SpringPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 获取所有可用的 Actions
     */
    public Map<String, List<ActionDefinition>> getAllActions() {
        Map<String, List<ActionDefinition>> result = new HashMap<>();

        List<ActionPlugin> plugins = pluginManager.getExtensions(ActionPlugin.class);
        for (ActionPlugin plugin : plugins) {
            result.put(plugin.getNamespace(), plugin.getActions());
        }

        return result;
    }

    /**
     * 执行 Action
     */
    public Object execute(String namespace, String actionName, Map<String, Object> params) {
        // 查找对应插件
        ActionPlugin plugin = findPlugin(namespace);
        if (plugin == null) {
            throw new RuntimeException("Plugin not found: " + namespace);
        }

        // 验证 Action 是否存在
        boolean actionExists = plugin.getActions().stream()
            .anyMatch(a -> a.getName().equals(actionName));
        if (!actionExists) {
            throw new RuntimeException("Action not found: " + actionName);
        }

        // 执行 Action
        return plugin.execute(actionName, params);
    }

    /**
     * 查找插件
     */
    private ActionPlugin findPlugin(String namespace) {
        List<ActionPlugin> plugins = pluginManager.getExtensions(ActionPlugin.class);
        return plugins.stream()
            .filter(p -> p.getNamespace().equals(namespace))
            .findFirst()
            .orElse(null);
    }
}
```

### 4.4 插件管理 API

```java
package com.example.server.controller;

import org.pf4j.PluginState;
import org.pf4j.PluginWrapper;
import org.pf4j.spring.SpringPluginManager;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 插件管理 API
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final SpringPluginManager pluginManager;
    private final Path pluginPath = Paths.get("./plugins");

    public PluginController(SpringPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    /**
     * 获取所有插件
     */
    @GetMapping
    public List<PluginInfo> listPlugins() {
        return pluginManager.getPlugins().stream()
            .map(this::toPluginInfo)
            .collect(Collectors.toList());
    }

    /**
     * 获取插件详情
     */
    @GetMapping("/{pluginId}")
    public PluginInfo getPlugin(@PathVariable String pluginId) {
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            throw new RuntimeException("Plugin not found: " + pluginId);
        }
        return toPluginInfo(plugin);
    }

    /**
     * 上传并加载插件
     */
    @PostMapping
    public ResponseEntity<PluginInfo> uploadPlugin(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "autoStart", defaultValue = "true") boolean autoStart) throws IOException {

        // 确保目录存在
        if (!Files.exists(pluginPath)) {
            Files.createDirectories(pluginPath);
        }

        // 保存文件
        String fileName = UUID.randomUUID() + ".jar";
        Path targetPath = pluginPath.resolve(fileName);
        file.transferTo(targetPath);

        // 加载插件
        String pluginId = pluginManager.loadPlugin(targetPath);

        // 自动启动
        if (autoStart) {
            pluginManager.startPlugin(pluginId);
        }

        return ResponseEntity.ok(toPluginInfo(pluginManager.getPlugin(pluginId)));
    }

    /**
     * 启动插件
     */
    @PostMapping("/{pluginId}/start")
    public ResponseEntity<Void> startPlugin(@PathVariable String pluginId) {
        PluginState state = pluginManager.startPlugin(pluginId);
        if (state != PluginState.STARTED) {
            throw new RuntimeException("Failed to start plugin: " + pluginId);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 停止插件
     */
    @PostMapping("/{pluginId}/stop")
    public ResponseEntity<Void> stopPlugin(@PathVariable String pluginId) {
        PluginState state = pluginManager.stopPlugin(pluginId);
        if (state != PluginState.STOPPED) {
            throw new RuntimeException("Failed to stop plugin: " + pluginId);
        }
        return ResponseEntity.ok().build();
    }

    /**
     * 卸载插件
     */
    @DeleteMapping("/{pluginId}")
    public ResponseEntity<Void> unloadPlugin(@PathVariable String pluginId) {
        // 先停止
        pluginManager.stopPlugin(pluginId);

        // 卸载
        boolean unloaded = pluginManager.unloadPlugin(pluginId);
        if (!unloaded) {
            throw new RuntimeException("Failed to unload plugin: " + pluginId);
        }

        return ResponseEntity.noContent().build();
    }

    /**
     * 重启插件
     */
    @PostMapping("/{pluginId}/restart")
    public ResponseEntity<Void> restartPlugin(@PathVariable String pluginId) {
        pluginManager.stopPlugin(pluginId);
        PluginState state = pluginManager.startPlugin(pluginId);
        if (state != PluginState.STARTED) {
            throw new RuntimeException("Failed to restart plugin: " + pluginId);
        }
        return ResponseEntity.ok().build();
    }

    private PluginInfo toPluginInfo(PluginWrapper plugin) {
        return new PluginInfo(
            plugin.getPluginId(),
            plugin.getDescriptor().getPluginId(),
            plugin.getDescriptor().getVersion(),
            plugin.getPluginState().toString()
        );
    }

    // DTO
    public record PluginInfo(String id, String name, String version, String state) {}
}
```

---

## 5. 插件开发

### 5.1 插件 Maven 配置

```xml
<project>
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.example.plugins</groupId>
    <artifactId>file-storage-plugin</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <dependencies>
        <!-- 插件 API (provided - 由主应用提供) -->
        <dependency>
            <groupId>com.example</groupId>
            <artifactId>action-api</artifactId>
            <version>1.0.0</version>
            <scope>provided</scope>
        </dependency>

        <!-- PF4J (provided) -->
        <dependency>
            <groupId>org.pf4j</groupId>
            <artifactId>pf4j</artifactId>
            <version>3.12.0</version>
            <scope>provided</scope>
        </dependency>

        <!-- Spring (provided) -->
        <dependency>
            <groupId>org.springframework</groupId>
            <artifactId>spring-context</artifactId>
            <version>6.0.0</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <!-- PF4J 插件标识 -->
                            <Plugin-Id>file-storage-plugin</Plugin-Id>
                            <Plugin-Version>1.0.0</Plugin-Version>
                            <Plugin-Class>com.example.plugin.FileStoragePlugin</Plugin-Class>
                            <Plugin-Provider>Example Corp</Plugin-Provider>
                            <Plugin-Dependencies/>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

### 5.2 插件实现示例

```java
package com.example.plugin;

import com.example.action.ActionDefinition;
import com.example.action.ActionPlugin;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件存储插件
 */
@Extension
@Component
public class FileStoragePlugin implements ActionPlugin {

    @Override
    public String getNamespace() {
        return "storage.file";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public List<ActionDefinition> getActions() {
        return Arrays.asList(
            new ActionDefinition("upload", "上传文件"),
            new ActionDefinition("download", "下载文件"),
            new ActionDefinition("delete", "删除文件")
        );
    }

    @Override
    public Object execute(String actionName, Map<String, Object> params) {
        return switch (actionName) {
            case "upload" -> upload(params);
            case "download" -> download(params);
            case "delete" -> delete(params);
            default -> throw new RuntimeException("Unknown action: " + actionName);
        };
    }

    private Object upload(Map<String, Object> params) {
        String fileName = (String) params.get("fileName");
        // 实现上传逻辑
        Map<String, Object> result = new HashMap<>();
        result.put("fileId", UUID.randomUUID().toString());
        result.put("url", "/files/" + fileName);
        return result;
    }

    private Object download(Map<String, Object> params) {
        String fileId = (String) params.get("fileId");
        // 实现下载逻辑
        return Map.of("content", "file content");
    }

    private Object delete(Map<String, Object> params) {
        String fileId = (String) params.get("fileId");
        // 实现删除逻辑
        return Map.of("success", true);
    }
}
```

---

## 6. 数据模型

### 6.1 插件信息表

```sql
-- 使用 PF4J 内置的插件管理，但可扩展存储额外信息
CREATE TABLE plugin_metadata (
    plugin_id           VARCHAR(64) PRIMARY KEY,
    namespace           VARCHAR(128) NOT NULL,
    name                VARCHAR(100) NOT NULL,
    description         TEXT,
    vendor              VARCHAR(100),

    -- 扩展配置
    config_schema       JSON,
    runtime_config      JSON,

    -- 统计信息
    load_count          INT DEFAULT 0,
    last_loaded_at      DATETIME,

    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Action 注册表 (与 PF4J 插件关联)
CREATE TABLE plugin_action (
    id                  VARCHAR(64) PRIMARY KEY,
    plugin_id           VARCHAR(64) NOT NULL,
    namespace           VARCHAR(128) NOT NULL,
    action_name         VARCHAR(64) NOT NULL,
    action_title        VARCHAR(100),
    description         TEXT,

    input_schema        JSON,
    output_schema       JSON,

    enabled             BOOLEAN DEFAULT TRUE,
    created_at          DATETIME DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_plugin_action (plugin_id, action_name),
    INDEX idx_namespace (namespace)
);
```

---

## 7. API 概览

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/plugins` | 获取所有插件 |
| GET | `/api/plugins/{id}` | 获取插件详情 |
| POST | `/api/plugins` | 上传插件 |
| POST | `/api/plugins/{id}/start` | 启动插件 |
| POST | `/api/plugins/{id}/stop` | 停止插件 |
| POST | `/api/plugins/{id}/restart` | 重启插件 |
| DELETE | `/api/plugins/{id}` | 卸载插件 |

---

## 8. 配置示例

### 8.1 application.yml

```yaml
server:
  port: 8080

# 插件配置
plugin:
  path: ./plugins
  auto-load: true
  auto-start: true
```

### 8.2 打包命令

```bash
# 1. 安装 API 模块
cd action-api
mvn install

# 2. 构建主应用
cd ../action-server
mvn package

# 3. 构建插件
cd ../plugins/file-storage-plugin
mvn package

# 4. 复制插件到目录
cp target/file-storage-plugin-1.0.0.jar ../../action-server/plugins/
```

---

## 9. 注意事项

### 9.1 依赖管理

- `action-api` 必须声明为 `provided` scope
- Spring 相关依赖也应声明为 `provided`
- 插件之间的依赖通过 `Plugin-Dependencies` manifest 声明

### 9.2 类加载

- 主应用类由 AppClassLoader 加载
- 插件类由 SpringPluginClassLoader 加载
- 共享接口/类必须放在 action-api 中

### 9.3 资源释放

插件卸载时需要注意：
- 关闭数据库连接池
- 停止后台线程
- 清理临时文件
- 注销事件监听器

---

## 10. 参考资源

- [PF4J GitHub](https://github.com/pf4j/pf4j)
- [pf4j-spring GitHub](https://github.com/clyoudu/pf4j-spring)
- [PF4J 文档](https://pf4j.org/doc/)
