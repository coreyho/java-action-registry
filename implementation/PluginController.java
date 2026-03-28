package com.lowcode.plugin.api;

import com.lowcode.plugin.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 插件管理 API
 */
@RestController
@RequestMapping("/api/plugins")
public class PluginController {

    @Autowired
    private PluginManager pluginManager;

    /**
     * 上传并安装插件
     *
     * @param file 插件 Jar 文件
     * @param force 是否强制覆盖已存在的版本
     * @param autoStart 是否自动启动
     */
    @PostMapping
    public ResponseEntity<PluginDefinition> uploadPlugin(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "force", defaultValue = "false") boolean force,
            @RequestParam(value = "autoStart", defaultValue = "true") boolean autoStart) {

        PluginDefinition plugin = pluginManager.install(file, force);

        if (autoStart && plugin.getState() == PluginState.INSTALLED) {
            pluginManager.start(plugin.getId());
            plugin = pluginManager.getDetail(plugin.getId()).getDefinition();
        }

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
     * 重启插件
     */
    @PostMapping("/{pluginId}/restart")
    public ResponseEntity<Void> restartPlugin(@PathVariable String pluginId) {
        pluginManager.restart(pluginId);
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
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {

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
     * 获取插件配置 Schema
     */
    @GetMapping("/{pluginId}/config-schema")
    public ResponseEntity<Map<String, Object>> getConfigSchema(@PathVariable String pluginId) {
        PluginDetail detail = pluginManager.getDetail(pluginId);
        return ResponseEntity.ok(detail.getDefinition().getConfigSchema());
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

    /**
     * 批量启动插件
     */
    @PostMapping("/batch/start")
    public ResponseEntity<BatchOperationResult> batchStart(
            @RequestBody List<String> pluginIds) {

        BatchOperationResult result = new BatchOperationResult();
        for (String pluginId : pluginIds) {
            try {
                pluginManager.start(pluginId);
                result.addSuccess(pluginId);
            } catch (Exception e) {
                result.addFailure(pluginId, e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    /**
     * 批量停止插件
     */
    @PostMapping("/batch/stop")
    public ResponseEntity<BatchOperationResult> batchStop(
            @RequestBody List<String> pluginIds) {

        BatchOperationResult result = new BatchOperationResult();
        for (String pluginId : pluginIds) {
            try {
                pluginManager.stop(pluginId);
                result.addSuccess(pluginId);
            } catch (Exception e) {
                result.addFailure(pluginId, e.getMessage());
            }
        }
        return ResponseEntity.ok(result);
    }

    // ==================== 响应类 ====================

    public static class BatchOperationResult {
        private final List<String> success = new java.util.ArrayList<>();
        private final Map<String, String> failures = new java.util.HashMap<>();

        public void addSuccess(String pluginId) {
            success.add(pluginId);
        }

        public void addFailure(String pluginId, String error) {
            failures.put(pluginId, error);
        }

        public List<String> getSuccess() {
            return success;
        }

        public Map<String, String> getFailures() {
            return failures;
        }

        public boolean isAllSuccess() {
            return failures.isEmpty();
        }
    }
}
