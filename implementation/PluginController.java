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
 * 插件管理 API (PF4J 版本)
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
        pluginManager.stopPlugin(pluginId);
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
            plugin.getPluginState().toString(),
            plugin.getDescriptor().getProvider()
        );
    }

    public record PluginInfo(String id, String name, String version, String state, String provider) {}
}
