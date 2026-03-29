# 低代码平台 Action 注册机制设计

## 1. 架构概述

### 1.1 设计定位

面向低代码平台的 Action 注册与调用机制，特点：
- **前后端一体化**：前端组件与后端 Action 统一注册、联动调用
- **控制面集中管理**：独立的注册中心，支持多环境、多版本
- **数据面轻量嵌入**：业务服务通过 SDK 接入，低侵入
- **可视化编排**：支持设计时配置与运行时执行分离

### 1.2 整体架构

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              低代码平台                                       │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                        设计器 (Designer)                            │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │ 页面设计器    │  │ 流程编排器    │  │ 数据模型设计 │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│                                    ▼                                        │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │                      控制面 (Control Plane)                         │   │
│  │                                                                     │   │
│  │  ┌─────────────────────────────────────────────────────────────┐   │   │
│  │  │              Action Registry Service                        │   │   │
│  │  │                                                             │   │   │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐              │   │   │
│  │  │  │   组件注册  │ │   Action   │ │   版本     │              │   │   │
│  │  │  │   中心      │ │   注册中心  │ │   管理     │              │   │   │
│  │  │  └────────────┘ └────────────┘ └────────────┘              │   │   │
│  │  │                                                             │   │   │
│  │  │  ┌────────────┐ ┌────────────┐ ┌────────────┐              │   │   │
│  │  │  │  Schema    │ │   权限     │ │   审计     │              │   │   │
│  │  │  │  校验      │ │   管理     │ │   日志     │              │   │   │
│  │  │  └────────────┘ └────────────┘ └────────────┘              │   │   │
│  │  └─────────────────────────────────────────────────────────────┘   │   │
│  │                                                                     │   │
│  │  API:                                                               │   │
│  │    POST /api/components          # 注册组件                         │   │
│  │    POST /api/actions             # 注册 Action                      │   │
│  │    POST /api/bindings            # 绑定前后端                       │   │
│  │    GET  /api/catalog             # 查询目录                         │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                    │                                        │
│              ┌─────────────────────┼─────────────────────┐                  │
│              │                     │                     │                  │
│              ▼                     ▼                     ▼                  │
│  ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐         │
│  │   业务服务 A     │    │   业务服务 B     │    │   业务服务 C     │         │
│  │                 │    │                 │    │                 │         │
│  │ ┌─────────────┐ │    │ ┌─────────────┐ │    │ ┌─────────────┐ │         │
│  │ │  SDK        │ │    │ │  SDK        │ │    │ │  SDK        │ │         │
│  │ │             │ │    │ │             │ │    │ │             │ │         │
│  │ │ • 注解扫描   │ │    │ │ • 注解扫描   │ │    │ │ • 注解扫描   │ │         │
│  │ │ • 自动注册   │ │    │ │ • 自动注册   │ │    │ │ • 自动注册   │ │         │
│  │ │ • 调用执行   │ │    │ │ • 调用执行   │ │    │ │ • 调用执行   │ │         │
│  │ │ • 本地缓存   │ │    │ │ • 本地缓存   │ │    │ │ • 本地缓存   │ │         │
│  │ └─────────────┘ │    │ └─────────────┘ │    │ └─────────────┘ │         │
│  │                 │    │                 │    │                 │         │
│  │ • user:create   │    │ • order:create  │    │ • file:upload   │         │
│  │ • user:get      │    │ • order:cancel  │    │ • file:download │         │
│  │ • user:list     │    │ • order:query   │    │ • file:preview  │         │
│  └─────────────────┘    └─────────────────┘    └─────────────────┘         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.3 核心概念

| 概念 | 说明 | 示例 |
|------|------|------|
| **Component** | 前端组件 | 表单、表格、文件阅览器 |
| **Action** | 后端操作 | 创建用户、查询订单、上传文件 |
| **Binding** | 前后端绑定关系 | 组件属性 ↔ Action 参数 |
| **Namespace** | 命名空间（领域.上下文） | crm.customer、order.fulfillment |
| **Catalog** | 组件/Action 目录 | 可发现、可搜索的能力清单 |
| **Plugin** | 热加载的 Jar 包插件 | file-storage-plugin.jar |

---

## 2. 组件设计

### 2.1 组件元数据

```typescript
interface ComponentDefinition {
  // 基础信息
  id: string;                    // 唯一标识
  namespace: string;             // 命名空间
  name: string;                  // 组件名
  version: string;               // 版本号
  title: string;                 // 显示标题
  description: string;           // 描述
  icon: string;                  // 图标
  category: 'basic' | 'form' | 'data' | 'chart' | 'custom';

  // 前端实现
  frontend: {
    type: 'react' | 'vue' | 'angular';
    entry: string;               // 入口文件 URL
    css?: string;                // 样式文件 URL
    dependencies: string[];      // 依赖包
  };

  // 属性定义（JSON Schema）
  propsSchema: JSONSchema7;

  // 事件定义
  events: EventDefinition[];

  // 关联的 Action
  actions?: ComponentAction[];

  // 设计时配置
  designTime: {
    resizable: boolean;
    draggable: boolean;
    defaultWidth: number;
    defaultHeight: number;
  };
}

// 组件关联的 Action
interface ComponentAction {
  name: string;                  // 组件内 Action 名
  title: string;                 // 显示标题
  actionKey: string;             // 后端 Action 标识
  trigger: 'auto' | 'manual' | 'event';
  inputMapping: Record<string, ParamMapping>;
  outputMapping: Record<string, ResultMapping>;
}
```

### 2.2 组件属性 Schema 示例

```typescript
const FileViewerSchema: JSONSchema7 = {
  type: 'object',
  title: '文件阅览器',
  properties: {
    fileId: {
      type: 'string',
      title: '文件ID',
      description: '文件唯一标识',
      'x-component': 'Input'
    },
    fileUrl: {
      type: 'string',
      title: '文件URL',
      'x-component': 'Input'
    },
    viewerType: {
      type: 'string',
      title: '阅览器类型',
      enum: ['auto', 'image', 'pdf', 'video'],
      default: 'auto',
      'x-component': 'Select',
      'x-component-props': {
        options: [
          { label: '自动检测', value: 'auto' },
          { label: '图片', value: 'image' },
          { label: 'PDF', value: 'pdf' },
          { label: '视频', value: 'video' }
        ]
      }
    },
    showToolbar: {
      type: 'boolean',
      title: '显示工具栏',
      default: true,
      'x-component': 'Switch'
    },
    allowDownload: {
      type: 'boolean',
      title: '允许下载',
      default: false,
      'x-component': 'Switch'
    },
    watermark: {
      type: 'object',
      title: '水印配置',
      properties: {
        enabled: { type: 'boolean', title: '启用', default: false },
        text: { type: 'string', title: '文字' },
        opacity: { type: 'number', title: '透明度', default: 0.15 }
      }
    }
  }
};
```

---

## 3. Action 设计

### 3.1 Action 元数据

```yaml
apiVersion: action.lowcode.io/v1
kind: Action
metadata:
  namespace: storage.file
  name: get
  version: 1.0.0
  title: 获取文件信息
  description: 根据文件ID获取文件元数据
  labels:
    category: storage
    feature: file-management

spec:
  type: SYNC
  protocol: HTTP

  endpoint:
    service: storage-service
    path: /api/files/{fileId}
    method: GET
    timeoutMs: 5000

  inputSchema:
    type: object
    required: [fileId]
    properties:
      fileId:
        type: string
        description: 文件唯一标识
        pattern: '^[a-zA-Z0-9_-]{10,50}$'
      includePreviewUrl:
        type: boolean
        default: false

  outputSchema:
    type: object
    required: [fileId, fileName, fileType]
    properties:
      fileId: { type: string }
      fileName: { type: string }
      fileType: { type: string, enum: [IMAGE, PDF, VIDEO] }
      fileSize: { type: integer }
      previewUrl: { type: string, format: uri }

  errorSchema:
    type: object
    properties:
      code:
        type: string
        enum: [FILE_NOT_FOUND, PERMISSION_DENIED]
      message: { type: string }

  retryPolicy:
    maxAttempts: 3
    backoffMs: 1000

  auth:
    required: true
    scopes: [file:read]

status:
  state: ACTIVE
  createdAt: "2024-01-15T08:00:00Z"
```

### 3.2 Java 实现

```java
@ActionResource(
    namespace = "storage.file",
    description = "文件管理"
)
@Component
public class FileResource {

    @Autowired
    private FileService fileService;

    @Action(
        name = "get",
        title = "获取文件信息",
        inputSchema = @Schema(validation = @Validation(required = "fileId")),
        outputSchema = @Schema(classType = FileInfo.class)
    )
    public FileInfo getFile(
            @ActionParam("fileId") String fileId,
            @ActionParam(value = "includePreviewUrl", defaultValue = "false")
            boolean includePreviewUrl) {

        return fileService.getFile(fileId, includePreviewUrl);
    }

    @Action(
        name = "preview-url",
        title = "获取预览URL",
        auth = @Auth(required = true, scopes = "file:preview")
    )
    public PreviewUrlInfo getPreviewUrl(
            @ActionParam("fileId") String fileId,
            @ActionParam(value = "expiresIn", defaultValue = "3600") int expiresIn) {

        return fileService.generatePreviewUrl(fileId, expiresIn);
    }
}
```

---

## 4. 前后端绑定

### 4.1 绑定配置

```typescript
interface BindingDefinition {
  id: string;
  componentId: string;
  actionKey: string;

  trigger: {
    type: 'mount' | 'change' | 'event' | 'manual';
    event?: string;
    debounce?: number;
  };

  // 参数映射
  inputMapping: {
    [actionParam: string]: {
      source: 'prop' | 'state' | 'context' | 'static';
      path: string;
      transform?: string;  // 表达式
    }
  };

  // 结果映射
  outputMapping: {
    [componentProp: string]: {
      source: 'data' | 'error' | 'meta';
      path: string;
    }
  };

  // 状态管理
  stateMapping?: {
    loading: string;
    error: string;
    data: string;
  };

  // 错误处理
  errorHandling: {
    strategy: 'throw' | 'silent' | 'fallback';
    fallback?: any;
    notify: boolean;
  };
}
```

### 4.2 绑定示例

```json
{
  "id": "binding-001",
  "componentId": "file-viewer-1",
  "actionKey": "storage.file.get:1.0.0",

  "trigger": {
    "type": "mount"
  },

  "inputMapping": {
    "fileId": { "source": "prop", "path": "fileId" },
    "includePreviewUrl": { "source": "static", "value": true }
  },

  "outputMapping": {
    "fileInfo": { "source": "data", "path": "data" },
    "loading": { "source": "meta", "path": "loading" }
  },

  "stateMapping": {
    "loading": "loading",
    "error": "error",
    "data": "fileInfo"
  },

  "errorHandling": {
    "strategy": "fallback",
    "fallback": { "fileName": "未知文件" },
    "notify": true
  }
}
```

---

## 5. 控制面设计

### 5.1 核心 API

```java
@RestController
@RequestMapping("/api")
public class RegistryController {

    // ========== 组件管理 ==========

    @PostMapping("/components")
    public ResponseEntity<ComponentDefinition> registerComponent(
            @RequestBody @Valid ComponentDefinition component) {
        ComponentDefinition registered = componentService.register(component);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @GetMapping("/components/{id}")
    public ResponseEntity<ComponentDefinition> getComponent(@PathVariable String id) {
        return componentService.findById(id)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/components")
    public ResponseEntity<Page<ComponentDefinition>> queryComponents(
            @RequestParam(required = false) String namespace,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String keyword) {

        ComponentQuery query = ComponentQuery.builder()
            .namespace(namespace)
            .category(category)
            .keyword(keyword)
            .build();

        return ResponseEntity.ok(componentService.query(query));
    }

    // ========== Action 管理 ==========

    @PostMapping("/actions")
    public ResponseEntity<ActionDefinition> registerAction(
            @RequestBody @Valid ActionDefinition action) {
        ActionDefinition registered = actionService.register(action);
        return ResponseEntity.status(HttpStatus.CREATED).body(registered);
    }

    @GetMapping("/actions/{namespace}/{name}")
    public ResponseEntity<ActionDefinition> getAction(
            @PathVariable String namespace,
            @PathVariable String name,
            @RequestParam(required = false) String version) {

        String actionKey = version != null
            ? String.format("%s.%s:%s", namespace, name, version)
            : String.format("%s.%s", namespace, name);

        return actionService.findByKey(actionKey)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/actions/{key}/activate")
    public ResponseEntity<Void> activateAction(@PathVariable String key) {
        actionService.activate(key);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/actions/{key}/deprecate")
    public ResponseEntity<Void> deprecateAction(
            @PathVariable String key,
            @RequestBody DeprecateRequest request) {
        actionService.deprecate(key, request);
        return ResponseEntity.ok().build();
    }

    // ========== 目录查询 ==========

    @GetMapping("/catalog")
    public ResponseEntity<Catalog> getCatalog(
            @RequestParam(required = false) String namespace) {
        return ResponseEntity.ok(catalogService.getCatalog(namespace));
    }

    @GetMapping("/catalog/{namespace}/components")
    public ResponseEntity<List<ComponentDefinition>> getNamespaceComponents(
            @PathVariable String namespace) {
        return ResponseEntity.ok(catalogService.getComponents(namespace));
    }

    @GetMapping("/catalog/{namespace}/actions")
    public ResponseEntity<List<ActionDefinition>> getNamespaceActions(
            @PathVariable String namespace) {
        return ResponseEntity.ok(catalogService.getActions(namespace));
    }
}
```

### 5.2 数据模型

```sql
-- 组件定义表
CREATE TABLE component_definition (
    id              VARCHAR(64) PRIMARY KEY,
    namespace       VARCHAR(128) NOT NULL,
    name            VARCHAR(64) NOT NULL,
    version         VARCHAR(32) NOT NULL,
    title           VARCHAR(100) NOT NULL,
    description     TEXT,
    category        VARCHAR(32),
    icon            VARCHAR(200),

    -- 前端实现
    frontend_type   VARCHAR(20),
    frontend_entry  VARCHAR(500),
    frontend_css    VARCHAR(500),
    dependencies    JSON,

    -- Schema
    props_schema    JSON NOT NULL,
    events_schema   JSON,
    actions_schema  JSON,
    design_time     JSON,

    -- 状态
    state           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_component (namespace, name, version),
    INDEX idx_namespace (namespace),
    INDEX idx_state (state)
);

-- Action 定义表
CREATE TABLE action_definition (
    id              VARCHAR(64) PRIMARY KEY,
    namespace       VARCHAR(128) NOT NULL,
    name            VARCHAR(64) NOT NULL,
    version         VARCHAR(32) NOT NULL,
    title           VARCHAR(100) NOT NULL,
    description     TEXT,

    -- 执行配置
    spec_type       VARCHAR(20) NOT NULL,
    spec_protocol   VARCHAR(20) NOT NULL,
    endpoint        JSON NOT NULL,

    -- Schema
    input_schema    JSON NOT NULL,
    output_schema   JSON NOT NULL,
    error_schema    JSON,

    -- 策略
    retry_policy    JSON,
    auth_config     JSON,

    -- 状态
    state           VARCHAR(20) NOT NULL DEFAULT 'DRAFT',

    -- 统计
    total_calls     BIGINT DEFAULT 0,
    success_rate    DECIMAL(5,2),
    avg_latency_ms  INT,

    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY uk_action (namespace, name, version),
    INDEX idx_namespace (namespace),
    INDEX idx_state (state)
);

-- 绑定定义表
CREATE TABLE binding_definition (
    id              VARCHAR(64) PRIMARY KEY,
    component_id    VARCHAR(64) NOT NULL,
    action_key      VARCHAR(255) NOT NULL,

    trigger_type    VARCHAR(20) NOT NULL,
    trigger_config  JSON,

    input_mapping   JSON NOT NULL,
    output_mapping  JSON NOT NULL,
    state_mapping   JSON,
    error_handling  JSON,

    created_at      DATETIME DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_component (component_id),
    INDEX idx_action (action_key)
);
```

---

## 6. SDK 设计

### 6.1 核心组件

```java
/**
 * Action SDK 核心
 */
public class ActionSdk {

    /**
     * 组件扫描器 - 扫描 @ActionResource 注解
     */
    @Component
    public class ComponentScanner {

        public List<ActionMetadata> scan(String basePackage) {
            // 扫描指定包下的所有 @ActionResource 类
            // 提取 Action 元数据
        }
    }

    /**
     * 注册客户端 - 向控制面注册
     */
    @Component
    public class RegistryClient {

        @Value("${action.registry.server-url}")
        private String registryUrl;

        public void registerComponent(ComponentDefinition component) {
            // POST /api/components
        }

        public void registerAction(ActionDefinition action) {
            // POST /api/actions
        }

        public void heartbeat(String componentId) {
            // 定期心跳续租
        }
    }

    /**
     * 本地目录 - 缓存控制面数据
     */
    @Component
    public class LocalCatalog {

        private final LoadingCache<String, ComponentDefinition> componentCache;
        private final LoadingCache<String, ActionDefinition> actionCache;

        public Optional<ComponentDefinition> getComponent(String id) {
            return Optional.ofNullable(componentCache.get(id));
        }

        public Optional<ActionDefinition> getAction(String key) {
            return Optional.ofNullable(actionCache.get(key));
        }

        public void refresh(String key) {
            componentCache.refresh(key);
            actionCache.refresh(key);
        }
    }

    /**
     * 调用执行器
     */
    @Component
    public class InvocationExecutor {

        public <I, O> ActionResponse<O> invoke(String actionKey, I input) {
            // 1. 从目录获取 Action 元数据
            ActionDefinition action = catalog.getAction(actionKey)
                .orElseThrow(() -> new ActionNotFoundException(actionKey));

            // 2. 检查状态
            if (!action.isInvocable()) {
                throw new ActionStateException(action.getState());
            }

            // 3. 选择协议适配器
            ProtocolAdapter adapter = protocolFactory.getAdapter(
                action.getSpec().getProtocol()
            );

            // 4. 执行调用
            return adapter.invoke(action, input);
        }
    }
}
```

### 6.2 Spring Boot Starter

```yaml
# application.yml
action:
  sdk:
    enabled: true
    service-name: storage-service

  registry:
    server-url: http://action-registry:8080
    api-key: ${REGISTRY_API_KEY}
    heartbeat-interval: 30s
    auto-register: true

  catalog:
    cache-ttl: 5m
    preload-on-startup: true

  invocation:
    default-timeout: 5000
    retry:
      enabled: true
      max-attempts: 3
```

---

## 7. 调用协议

### 7.1 统一调用请求

```http
POST /api/invoke/{namespace}.{action}
Content-Type: application/json
X-Action-Version: 1.0.0
X-Request-Id: req-123456
Authorization: Bearer {jwt}

{
  "input": {
    "fileId": "file_abc123",
    "includePreviewUrl": true
  },
  "options": {
    "timeoutMs": 3000,
    "retryPolicy": {
      "maxAttempts": 2
    }
  }
}
```

### 7.2 统一调用响应

```json
{
  "code": "OK",
  "message": "success",
  "requestId": "req-123456",
  "data": {
    "fileId": "file_abc123",
    "fileName": "report.pdf",
    "fileType": "PDF",
    "fileSize": 1024567,
    "previewUrl": "https://..."
  },
  "meta": {
    "actionKey": "storage.file.get:1.0.0",
    "durationMs": 78,
    "provider": "storage-service-01"
  }
}
```

### 7.3 错误响应

```json
{
  "code": "FILE_NOT_FOUND",
  "message": "文件不存在",
  "requestId": "req-123456",
  "error": {
    "type": "NotFoundException",
    "details": {
      "fileId": "file_abc123"
    }
  },
  "meta": {
    "actionKey": "storage.file.get:1.0.0"
  }
}
```

---

## 8. 关键设计决策

### 8.1 为什么选择控制面/数据面分离？

| 优势 | 说明 |
|------|------|
| 集中管理 | 统一的组件/Action 注册中心 |
| 动态发现 | 运行时获取最新能力清单 |
| 版本治理 | 支持多版本共存、灰度发布 |
| 权限管控 | 集中认证授权 |
| 审计分析 | 统一日志和指标采集 |

### 8.2 为什么采用 K8s CRD 风格元数据？

- **标准化**：apiVersion/kind/metadata/spec/status 结构清晰
- **可扩展**：labels 支持任意标签
- **工具生态**：支持现有 YAML 工具和验证
- **版本管理**：apiVersion 支持协议演进

### 8.3 前后端如何联动？

```
设计时：
  1. 开发者在设计器拖拽组件
  2. 配置组件属性
  3. 绑定后端 Action
  4. 配置参数映射
  5. 保存页面配置

运行时：
  1. 页面渲染组件
  2. 组件挂载触发 Action
  3. SDK 查询控制面获取 Action 元数据
  4. 按映射规则组装参数
  5. 调用后端服务
  6. 按映射规则更新组件状态
```

---

## 9. 插件化架构

### 9.1 概述

基于 [PF4J](https://github.com/pf4j/pf4j) 实现 Java Jar 包热注册机制，支持运行时动态加载、卸载和更新 Action 插件。

### 9.2 架构图

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
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 9.3 核心概念

| 概念 | 说明 | PF4J 对应 |
|------|------|-----------|
| **Plugin** | 热加载的 Jar 包 | `Plugin` 接口 |
| **Extension** | 插件暴露的扩展点 | `@Extension` 注解 |
| **ExtensionPoint** | 扩展点接口 | `ExtensionPoint` 接口 |
| **PluginState** | 插件生命周期状态 | `PluginState` 枚举 |

### 9.4 生命周期

```
CREATED → RESOLVED → STARTED → STOPPED → UNLOADED
```

### 9.5 开发示例

**插件接口定义：**
```java
public interface ActionPlugin extends ExtensionPoint {
    String getNamespace();
    List<ActionDefinition> getActions();
    Object execute(String actionName, Map<String, Object> params);
}
```

**插件实现：**
```java
@Extension
@Component
public class FileStoragePlugin implements ActionPlugin {
    @Override
    public String getNamespace() { return "storage.file"; }

    @Override
    public List<ActionDefinition> getActions() {
        return Arrays.asList(
            new ActionDefinition("upload", "上传文件"),
            new ActionDefinition("download", "下载文件")
        );
    }
}
```

### 9.6 API 设计

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/plugins` | 获取所有插件 |
| POST | `/api/plugins` | 上传插件 |
| POST | `/api/plugins/{id}/start` | 启动插件 |
| POST | `/api/plugins/{id}/stop` | 停止插件 |
| DELETE | `/api/plugins/{id}` | 卸载插件 |

---

## 10. 技术选型

| 组件 | 选型 | 说明 |
|------|------|------|
| 控制面服务 | Spring Boot 3.x | 主流 Java 框架 |
| 数据存储 | PostgreSQL + Redis | 关系型 + 缓存 |
| 消息队列 | RabbitMQ / Kafka | 异步 Action 支持 |
| 服务发现 | Nacos | 阿里开源，功能完善 |
| 配置中心 | Nacos / Apollo | 动态配置 |
| 监控 | Prometheus + Grafana | 云原生标准 |
| 链路追踪 | OpenTelemetry | 标准化追踪 |
| **插件框架** | **PF4J 3.12+** | **Java 插件化框架** |
| **Spring 集成** | **pf4j-spring** | **PF4J Spring 集成** |

---

## 10. 实施路线图

### 阶段 1：MVP（4-6 周）

- [ ] 控制面基础 API（组件/Action 注册查询）
- [ ] SDK 基础功能（注解扫描、自动注册）
- [ ] HTTP 协议支持
- [ ] 基础前后端绑定

### 阶段 2：增强（4 周）

- [ ] 生命周期管理（激活/弃用/停用）
- [ ] 版本管理
- [ ] Schema 校验
- [ ] 本地缓存优化

### 阶段 3：生产级（4 周）

- [ ] 权限管控
- [ ] 审计日志
- [ ] 限流熔断
- [ ] 可观测性（Metrics/Tracing）

### 阶段 4：高级特性（持续）

- [ ] **插件化架构（PF4J）**：Jar 包热加载、类隔离、Spring 集成
- [ ] 异步 Action（MQ）
- [ ] 流式响应
- [ ] 多租户
- [ ] 组件市场
