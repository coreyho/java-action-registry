/**
 * PF4J + Spring Boot 热注册实现代码
 *
 * 包含:
 * 1. ActionPlugin 扩展点接口
 * 2. SpringPluginManager 配置
 * 3. ActionDispatchService 分发服务
 * 4. PluginController 管理 API
 * 5. 示例插件实现
 */

// ==================== 1. 插件 API 定义 (action-api) ====================

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
     * 例如: storage.file, order.service
     */
    String getNamespace();

    /**
     * 获取插件版本
     * 例如: 1.0.0
     */
    String getVersion();

    /**
     * 获取插件提供的 Actions
     */
    List<ActionDefinition> getActions();

    /**
     * 执行 Action
     *
     * @param actionName Action 名称
     * @param params 输入参数
     * @return 执行结果
     */
    Object execute(String actionName, Map<String, Object> params);
}

/**
 * Action 定义
 */
package com.example.action;

import lombok.Data;
import java.util.Map;

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

// ==================== 2. 主应用配置 (action-server) ====================

package com.example.server.config;

import org.pf4j.spring.SpringPluginManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * PF4J Spring 配置
 */
@Configuration
public class PluginConfig {

    @Bean
    public SpringPluginManager pluginManager() {
        return new SpringPluginManager(Paths.get("./plugins"));
    }

    @Bean
    public PluginLoader pluginLoader(SpringPluginManager pluginManager) {
        return new PluginLoader(pluginManager);
    }
}

/**
 * 插件加载器
 */
package com.example.server.config;

import org.pf4j.spring.SpringPluginManager;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

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
        pluginManager.getPlugins().forEach(p -> {
            System.out.println("  - " + p.getPluginId() + " [" + p.getPluginState() + "]");
        });
    }
}

// ==================== 3. Action 分发服务 ====================

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
        ActionPlugin plugin = findPlugin(namespace);
        if (plugin == null) {
            throw new RuntimeException("Plugin not found: " + namespace);
        }

        boolean actionExists = plugin.getActions().stream()
            .anyMatch(a -> a.getName().equals(actionName));
        if (!actionExists) {
            throw new RuntimeException("Action not found: " + actionName + " in plugin " + namespace);
        }

        return plugin.execute(actionName, params);
    }

    /**
     * 批量执行 Actions
     */
    public Map<String, Object> executeBatch(List<ActionRequest> requests) {
        Map<String, Object> results = new HashMap<>();
        for (ActionRequest request : requests) {
            try {
                Object result = execute(
                    request.getNamespace(),
                    request.getActionName(),
                    request.getParams()
                );
                results.put(request.getRequestId(), result);
            } catch (Exception e) {
                results.put(request.getRequestId(), Map.of(
                    "error", true,
                    "message", e.getMessage()
                ));
            }
        }
        return results;
    }

    private ActionPlugin findPlugin(String namespace) {
        List<ActionPlugin> plugins = pluginManager.getExtensions(ActionPlugin.class);
        return plugins.stream()
            .filter(p -> p.getNamespace().equals(namespace))
            .findFirst()
            .orElse(null);
    }
}

/**
 * Action 执行请求
 */
package com.example.server.service;

import lombok.Data;
import java.util.Map;

@Data
public class ActionRequest {
    private String requestId;
    private String namespace;
    private String actionName;
    private Map<String, Object> params;
}

// ==================== 4. 插件管理 API ====================

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

@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    private final SpringPluginManager pluginManager;
    private final Path pluginPath = Paths.get("./plugins");

    public PluginController(SpringPluginManager pluginManager) {
        this.pluginManager = pluginManager;
    }

    @GetMapping
    public List<PluginInfo> listPlugins() {
        return pluginManager.getPlugins().stream()
            .map(this::toPluginInfo)
            .collect(Collectors.toList());
    }

    @GetMapping("/{pluginId}")
    public PluginInfo getPlugin(@PathVariable String pluginId) {
        PluginWrapper plugin = pluginManager.getPlugin(pluginId);
        if (plugin == null) {
            throw new RuntimeException("Plugin not found: " + pluginId);
        }
        return toPluginInfo(plugin);
    }

    @PostMapping
    public ResponseEntity<PluginInfo> uploadPlugin(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "autoStart", defaultValue = "true") boolean autoStart) throws IOException {

        if (!Files.exists(pluginPath)) {
            Files.createDirectories(pluginPath);
        }

        String fileName = UUID.randomUUID() + ".jar";
        Path targetPath = pluginPath.resolve(fileName);
        file.transferTo(targetPath);

        String pluginId = pluginManager.loadPlugin(targetPath);

        if (autoStart) {
            pluginManager.startPlugin(pluginId);
        }

        return ResponseEntity.ok(toPluginInfo(pluginManager.getPlugin(pluginId)));
    }

    @PostMapping("/{pluginId}/start")
    public ResponseEntity<Void> startPlugin(@PathVariable String pluginId) {
        PluginState state = pluginManager.startPlugin(pluginId);
        if (state != PluginState.STARTED) {
            throw new RuntimeException("Failed to start plugin: " + pluginId);
        }
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{pluginId}/stop")
    public ResponseEntity<Void> stopPlugin(@PathVariable String pluginId) {
        PluginState state = pluginManager.stopPlugin(pluginId);
        if (state != PluginState.STOPPED) {
            throw new RuntimeException("Failed to stop plugin: " + pluginId);
        }
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{pluginId}")
    public ResponseEntity<Void> unloadPlugin(@PathVariable String pluginId) {
        pluginManager.stopPlugin(pluginId);
        boolean unloaded = pluginManager.unloadPlugin(pluginId);
        if (!unloaded) {
            throw new RuntimeException("Failed to unload plugin: " + pluginId);
        }
        return ResponseEntity.noContent().build();
    }

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
            plugin.getPluginState().toString(),
            plugin.getDescriptor().getProvider()
        );
    }

    public record PluginInfo(String id, String name, String version, String state, String provider) {}
}

// ==================== 5. 示例插件实现 ====================

package com.example.plugin;

import com.example.action.ActionDefinition;
import com.example.action.ActionPlugin;
import org.pf4j.Extension;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
            new ActionDefinition("delete", "删除文件"),
            new ActionDefinition("preview", "预览文件")
        );
    }

    @Override
    public Object execute(String actionName, Map<String, Object> params) {
        return switch (actionName) {
            case "upload" -> upload(params);
            case "download" -> download(params);
            case "delete" -> delete(params);
            case "preview" -> preview(params);
            default -> throw new RuntimeException("Unknown action: " + actionName);
        };
    }

    private Object upload(Map<String, Object> params) {
        String fileName = (String) params.get("fileName");
        String contentType = (String) params.get("contentType");
        Long size = Long.valueOf(params.get("size").toString());

        Map<String, Object> result = new HashMap<>();
        result.put("fileId", UUID.randomUUID().toString());
        result.put("fileName", fileName);
        result.put("contentType", contentType);
        result.put("size", size);
        result.put("url", "/api/files/" + fileName);
        result.put("createdAt", System.currentTimeMillis());

        return result;
    }

    private Object download(Map<String, Object> params) {
        String fileId = (String) params.get("fileId");

        Map<String, Object> result = new HashMap<>();
        result.put("fileId", fileId);
        result.put("content", "file content bytes...");
        result.put("contentType", "application/octet-stream");

        return result;
    }

    private Object delete(Map<String, Object> params) {
        String fileId = (String) params.get("fileId");

        return Map.of(
            "fileId", fileId,
            "deleted", true,
            "deletedAt", System.currentTimeMillis()
        );
    }

    private Object preview(Map<String, Object> params) {
        String fileId = (String) params.get("fileId");

        return Map.of(
            "fileId", fileId,
            "previewUrl", "/api/files/" + fileId + "/preview",
            "expireAt", System.currentTimeMillis() + 3600000
        );
    }
}

// ==================== 6. 实体类定义 ====================

package com.example.server.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "plugin_metadata")
public class PluginMetadata {

    @Id
    private String pluginId;

    @Column(nullable = false)
    private String namespace;

    @Column(nullable = false)
    private String name;

    private String description;

    private String vendor;

    @Convert(converter = JsonConverter.class)
    private Object configSchema;

    @Convert(converter = JsonConverter.class)
    private Object runtimeConfig;

    private Integer loadCount = 0;

    private LocalDateTime lastLoadedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}

package com.example.server.entity;

import lombok.Data;
import javax.persistence.*;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "plugin_action")
public class PluginAction {

    @Id
    private String id;

    @Column(name = "plugin_id")
    private String pluginId;

    @Column(nullable = false)
    private String namespace;

    @Column(name = "action_name", nullable = false)
    private String actionName;

    @Column(name = "action_title")
    private String actionTitle;

    private String description;

    @Convert(converter = JsonConverter.class)
    private Object inputSchema;

    @Convert(converter = JsonConverter.class)
    private Object outputSchema;

    private Boolean enabled = true;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}

// ==================== 7. Repository 接口 ====================

package com.example.server.repository;

import com.example.server.entity.PluginMetadata;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PluginMetadataRepository extends JpaRepository<PluginMetadata, String> {

    Optional<PluginMetadata> findByNamespace(String namespace);

    Optional<PluginMetadata> findByPluginId(String pluginId);
}

package com.example.server.repository;

import com.example.server.entity.PluginAction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PluginActionRepository extends JpaRepository<PluginAction, String> {

    List<PluginAction> findByPluginId(String pluginId);

    List<PluginAction> findByNamespace(String namespace);

    List<PluginAction> findByEnabledTrue();
}

// ==================== 8. 工具类 ====================

package com.example.server.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.persistence.AttributeConverter;
import javax.persistence.Converter;

@Converter(autoApply = true)
public class JsonConverter implements AttributeConverter<Object, String> {

    private static final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(Object attribute) {
        if (attribute == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to convert to JSON", e);
        }
    }

    @Override
    public Object convertToEntityAttribute(String dbData) {
        if (dbData == null) {
            return null;
        }
        try {
            return mapper.readValue(dbData, Object.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse JSON", e);
        }
    }
}

// ==================== 9. 异常处理 ====================

package com.example.server.exception;

public class PluginException extends RuntimeException {
    public PluginException(String message) {
        super(message);
    }

    public PluginException(String message, Throwable cause) {
        super(message, cause);
    }
}

package com.example.server.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.LocalDateTime;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", true,
            "message", e.getMessage(),
            "timestamp", LocalDateTime.now()
        ));
    }

    @ExceptionHandler(PluginException.class)
    public ResponseEntity<Map<String, Object>> handlePluginException(PluginException e) {
        return ResponseEntity.badRequest().body(Map.of(
            "error", true,
            "type", "PLUGIN_ERROR",
            "message", e.getMessage(),
            "timestamp", LocalDateTime.now()
        ));
    }
}
