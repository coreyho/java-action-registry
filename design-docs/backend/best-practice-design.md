# Action 注册机制最佳设计（V1 + V2 融合版）

## 设计哲学

融合 V1 的**简洁轻量**与 V2 的**企业级治理**，采用"**渐进式架构**"理念：
- 小型项目：内嵌模式，零依赖启动
- 中大型项目：独立控制面，完整治理能力
- 平滑演进：两者 API 兼容，可随时切换模式

---

## 1. 总体架构

### 1.1 融合架构图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         🎛️ 控制面（可选独立部署）                             │
│                                                                             │
│  ┌─────────────────────────────────────────────────────────────────────┐   │
│  │              Action Registry Service（可选）                          │   │
│  │                                                                      │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │  Metadata    │  │   Lifecycle  │  │   Version    │              │   │
│  │  │   Store      │  │   Manager    │  │   Manager    │              │   │
│  │  │  (Database)  │  │              │  │   (SemVer)   │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  │                                                                      │   │
│  │  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐              │   │
│  │  │   Schema     │  │   Multi-     │  │   Audit &    │              │   │
│  │  │  Validator   │  │   Tenant     │  │   Analytics  │              │   │
│  │  └──────────────┘  └──────────────┘  └──────────────┘              │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              │                       │                       │
              ▼                       ▼                       ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                           📦 数据面（业务服务）                               │
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────┐          │
│  │   Service A      │  │   Service B      │  │   Service C      │          │
│  │                  │  │                  │  │                  │          │
│  │ ┌──────────────┐ │  │ ┌──────────────┐ │  │ ┌──────────────┐ │          │
│  │ │ Action SDK   │ │  │ │ Action SDK   │ │  │ │ Action SDK   │ │          │
│  │ │              │ │  │ │              │ │  │ │              │ │          │
│  │ │ • Scanner    │ │  │ │ • Scanner    │ │  │ │ • Scanner    │ │          │
│  │ │ • Registry   │ │  │ │ • Registry   │ │  │ │ • Registry   │ │          │
│  │ │ • Engine     │ │  │ │ • Engine     │ │  │ │ • Engine     │ │          │
│  │ │ • Catalog    │ │  │ │ • Catalog    │ │  │ │ • Catalog    │ │          │
│  │ └──────────────┘ │  │ └──────────────┘ │  │ └──────────────┘ │          │
│  │                  │  │                  │  │                  │          │
│  │ ┌──────────────┐ │  │ ┌──────────────┐ │  │ ┌──────────────┐ │          │
│  │ │ Global       │ │  │ │ Global       │ │  │ │ Global       │ │          │
│  │ │ Registry     │ │  │ │ Registry     │ │  │ │ Registry     │ │          │
│  │ │ (内存/Redis) │ │  │ │ (内存/Redis) │ │  │ │ (内存/Redis) │ │          │
│  │ └──────────────┘ │  │ └──────────────┘ │  │ └──────────────┘ │          │
│  │                  │  │                  │  │                  │          │
│  │ ┌──────────────┐ │  │ ┌──────────────┐ │  │ ┌──────────────┐ │          │
│  │ │ Service      │ │  │ │ Service      │ │  │ │ Service      │ │          │
│  │ │ Registry     │ │  │ │ Registry     │ │  │ │ Registry     │ │          │
│  │ │ (本地Action) │ │  │ │ (本地Action) │ │  │ │ (本地Action) │ │          │
│  │ └──────────────┘ │  │ └──────────────┘ │  │ └──────────────┘ │          │
│  │                  │  │                  │  │                  │          │
│  │ ┌──────────────┐ │  │ ┌──────────────┐ │  │ ┌──────────────┐ │          │
│  │ │ Resource     │ │  │ │ Resource     │ │  │ │ Resource     │ │          │
│  │ │ Registry     │ │  │ │ Registry     │ │  │ │ Registry     │ │          │
│  │ │ (资源级)     │ │  │ │ (资源级)     │ │  │ │ (资源级)     │          │
│  │ └──────────────┘ │  │ └──────────────┘ │  │ └──────────────┘ │          │
│  └──────────────────┘  └──────────────────┘  └──────────────────┘          │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### 1.2 部署模式对比

| 模式 | 控制面 | 存储 | 适用场景 | 运维复杂度 |
|------|--------|------|----------|-----------|
| **嵌入式** | 内嵌 Library | 内存 / 本地文件 | 单体应用/快速原型 | ⭐ |
| **增强嵌入式** | 内嵌 + 可选外接 | Redis + MySQL | 中小型微服务 | ⭐⭐ |
| **独立模式** | 独立服务集群 | 分布式数据库 | 大型企业/多租户 | ⭐⭐⭐ |
| **混合模式** | 多级控制面 | 联邦存储 | 超大规模/集团型 | ⭐⭐⭐⭐ |

---

## 2. 核心概念（融合设计）

### 2.1 Action 标识符（兼容双模式）

```
V1 风格（简洁）:          {service}:{resource}:{action}
示例:                     user-service:user:create

V2 风格（领域驱动）:       {domain}.{context}.{action}:{version}
示例:                     crm.customer.create:1.0.0

融合风格（推荐）:         {domain}.{context}.{action}:{version}@{service}
示例:                     crm.customer.create:1.0.0@user-service
```

**标识符解析规则**:
```java
public class ActionKey {
    private String domain;        // 领域 (crm/order/payment)
    private String context;       // 限界上下文 (customer/fulfillment)
    private String name;          // Action 名称
    private String version;       // 版本号 (SemVer)
    private String service;       // 服务名（可选）

    // V1 兼容构造函数
    public static ActionKey fromV1(String service, String resource, String action) {
        return new ActionKey(resource, "default", action, "1.0.0", service);
    }

    // V2 风格构造函数
    public static ActionKey fromV2(String namespace, String name, String version) {
        String[] parts = namespace.split("\\.");
        return new ActionKey(parts[0], parts[1], name, version, null);
    }

    // 获取完整 Key
    public String toFullKey() {
        return String.format("%s.%s.%s:%s@%s",
            domain, context, name, version, service);
    }
}
```

### 2.2 Action 生命周期（完整版）

```
┌─────────────────────────────────────────────────────────────────┐
│                         Action 生命周期                          │
├─────────────────────────────────────────────────────────────────┤
│                                                                  │
│   ┌─────────┐    ┌─────────┐    ┌─────────┐    ┌─────────┐     │
│   │  DRAFT  │───▶│ ACTIVE  │───▶│DEPRECATED│───▶│DISABLED │     │
│   │  (草稿) │    │ (活跃)  │    │ (已弃用) │    │ (已停用) │     │
│   └─────────┘    └────┬────┘    └────┬────┘    └────┬────┘     │
│        ▲              │              │              │           │
│        │              │              │              │           │
│        └──────────────┴──────────────┴──────────────┘           │
│                   (可重新激活)                                   │
│                                                                  │
│   ┌─────────┐                                                    │
│   │ REMOVED │◄─────────────────────────────────────────────     │
│   │ (已移除)│  (仅逻辑删除，保留审计记录)                         │
│   └─────────┘                                                    │
│                                                                  │
└─────────────────────────────────────────────────────────────────┘
```

**状态说明**:

| 状态 | 可调用 | 可见性 | 说明 |
|------|--------|--------|------|
| DRAFT | ❌ | 开发者 | 开发中，仅本地测试 |
| ACTIVE | ✅ | 所有人 | 可正常调用 |
| DEPRECATED | ✅ (告警) | 所有人 | 不建议新接入，返回 Deprecation 头 |
| DISABLED | ❌ | 管理员 | 已停用，返回明确错误 |
| REMOVED | ❌ | 审计记录 | 仅保留历史 |

### 2.3 Action 类型

| 类型 | 协议 | 适用场景 | 响应模式 |
|------|------|----------|----------|
| SYNC | HTTP/gRPC/Dubbo | 常规业务操作 | 即时响应 |
| ASYNC | MQ/事件总线 | 耗时操作、削峰填谷 | 异步回调/轮询 |
| STREAM | WebSocket/gRPC Stream | 实时推送、大数据导出 | 流式响应 |

---

## 3. 元数据 Schema（标准化）

### 3.1 融合元数据结构

```yaml
# Action 元数据（YAML 格式，K8s CRD 风格）
apiVersion: action.example.io/v1
kind: Action
metadata:
  # 基础信息
  name: create
  namespace: crm.customer
  version: 1.0.0
  title: 创建客户
  description: 创建新客户记录

  # 标签与归属
  labels:
    env: prod
    team: crm
    feature: customer-management
  owner: crm-team@example.com

  # 时间戳
  createdAt: "2024-01-15T08:00:00Z"
  updatedAt: "2024-01-15T08:00:00Z"

spec:
  # 执行类型与协议
  type: SYNC
  protocol: HTTP

  # 服务端点（V1 兼容）
  endpoint:
    service: crm-service
    path: /api/v1/customers
    method: POST
    timeoutMs: 5000

  # 输入参数 Schema（JSON Schema 2020-12）
  inputSchema:
    type: object
    required: [customerName, contact]
    properties:
      customerName:
        type: string
        description: 客户名称
        minLength: 2
        maxLength: 100
      customerType:
        type: string
        enum: [ENTERPRISE, INDIVIDUAL]
        default: INDIVIDUAL
      contact:
        type: object
        required: [phone]
        properties:
          phone:
            type: string
            pattern: '^1[3-9]\d{9}$'
          email:
            type: string
            format: email

  # 输出结果 Schema
  outputSchema:
    type: object
    required: [customerId]
    properties:
      customerId:
        type: string
      customerName:
        type: string
      status:
        type: string
        enum: [ACTIVE, PENDING]

  # 错误响应 Schema
  errorSchema:
    type: object
    properties:
      code:
        type: string
        enum: [VALIDATION_ERROR, DUPLICATE_CUSTOMER, SYSTEM_ERROR]
      message:
        type: string
      details:
        type: object

  # 重试策略
  retryPolicy:
    maxAttempts: 3
    backoffMs: 1000
    retryableCodes: [TIMEOUT, SERVICE_UNAVAILABLE]

  # 认证授权
  auth:
    mode: JWT
    scopes: [crm:write, customer:manage]
    publicAccess: false

  # 幂等控制
  idempotency:
    enabled: true
    keyHeader: Idempotency-Key
    ttlSeconds: 86400

  # 多租户
  tenantScope: TENANT_SPECIFIC  # GLOBAL / TENANT_SPECIFIC

  # 资源限制
  rateLimit:
    requestsPerSecond: 100
    burstSize: 150

  # 兼容性声明
  compatibility:
    minClientVersion: "1.0.0"
    deprecatedFields: []
    breakingChanges: false

status:
  # 生命周期状态
  state: ACTIVE

  # 统计信息
  usageStats:
    totalCalls: 15000
    successRate: 99.8
    avgLatencyMs: 45
    p99LatencyMs: 120

  # 版本关系
  versions:
    latest: "1.2.0"
    recommended: "1.0.0"
    deprecated: ["0.9.0"]
```

### 3.2 核心类设计（融合版）

```java
/**
 * Action 定义（融合 V1 简洁 + V2 完整）
 */
@Data
@Builder
public class ActionDefinition {

    // ==================== V1 风格基础字段 ====================

    /** Action 唯一标识符（V1 风格） */
    private String name;

    /** 显示标题 */
    private String title;

    /** 描述 */
    private String description;

    /** 所属资源名称 */
    private String resourceName;

    /** 所属服务名称 */
    private String serviceName;

    /** 执行处理器 */
    private ActionHandler handler;

    /** 是否是远程 Action */
    private boolean remote;

    /** 远程服务地址 */
    private String remoteEndpoint;

    // ==================== V2 风格扩展字段 ====================

    /** Action Key（V2 风格） */
    private ActionKey actionKey;

    /** 领域 */
    private String domain;

    /** 限界上下文 */
    private String boundedContext;

    /** 版本号（SemVer） */
    private String version;

    /** 生命周期状态 */
    private ActionState state;

    /** 完整元数据 */
    private ActionMetadata metadata;

    // ==================== 兼容性方法 ====================

    /**
     * 获取 V1 风格完整名称
     */
    public String getQualifiedName() {
        return String.format("%s:%s:%s", serviceName, resourceName, name);
    }

    /**
     * 获取 V2 风格完整 Key
     */
    public String getFullKey() {
        if (actionKey != null) {
            return actionKey.toFullKey();
        }
        return String.format("%s.%s.%s:%s@%s",
            domain != null ? domain : "default",
            boundedContext != null ? boundedContext : resourceName,
            name,
            version != null ? version : "1.0.0",
            serviceName);
    }

    /**
     * 判断是否可调用
     */
    public boolean isInvocable() {
        return state == ActionState.ACTIVE || state == ActionState.DEPRECATED;
    }
}

/**
 * Action 执行上下文（融合版）
 */
@Data
@Builder
public class ActionContext {

    // V1 兼容字段
    private ActionDefinition action;
    private Map<String, Object> params;
    private Object result;
    private Map<String, Object> attributes;

    // V2 扩展字段
    private String requestId;
    private String tenantId;
    private String userId;
    private String traceId;
    private String actionKey;

    // 用户信息（V1）
    private UserInfo currentUser;

    // 请求头（V1）
    private Map<String, String> headers;

    // 调用选项（V2）
    private InvokeOptions options;

    // 子上下文（V1）
    public ActionContext createSubContext() {
        return ActionContext.builder()
            .requestId(UUID.randomUUID().toString())
            .tenantId(this.tenantId)
            .userId(this.userId)
            .traceId(this.traceId)
            .parentContext(this)
            .build();
    }

    private ActionContext parentContext;
}
```

---

## 4. 注册机制（三层 + 控制面）

### 4.1 三层注册架构（V1 核心保留）

```java
/**
 * 全局 Action 注册表（融合版）
 * 支持内存/Redis/远程服务多种存储后端
 */
public interface GlobalActionRegistry {

    // ==================== V1 基础 API ====================

    void registerAction(ActionDefinition action);
    void unregisterAction(String actionName);
    ActionDefinition getAction(String actionName);
    ActionDefinition getAction(String serviceName, String resourceName, String actionName);
    List<ActionDefinition> getAllActions();
    List<ActionDefinition> getActionsByService(String serviceName);
    List<ActionDefinition> getActionsByResource(String serviceName, String resourceName);
    boolean hasAction(String actionName);

    // ==================== V2 扩展 API ====================

    void registerAction(ActionMetadata metadata);
    void activate(String actionKey);
    void deprecate(String actionKey, DeprecateRequest request);
    void disable(String actionKey, String reason);

    ActionDefinition getActionByKey(String actionKey);
    ActionDefinition getActionByKey(String namespace, String name, String version);

    List<ActionDefinition> queryActions(ActionQuery query);

    // 版本管理
    List<String> getVersions(String namespace, String name);
    ActionDefinition getLatestVersion(String namespace, String name);
    ActionDefinition getRecommendedVersion(String namespace, String name);

    // 监听变更
    void addRegistryListener(ActionRegistryListener listener);
    void removeRegistryListener(ActionRegistryListener listener);

    // ==================== 存储后端切换 ====================

    void setStorageBackend(StorageBackend backend);
}

/**
 * 服务级注册表（V1 核心）
 */
public interface ServiceActionRegistry {

    String getServiceName();

    // 本地 Action 注册
    void registerLocalAction(String resourceName, ActionDefinition action);
    void registerLocalAction(ActionMetadata metadata);

    // 远程 Action 代理注册
    void registerRemoteAction(String resourceName, RemoteActionProxy proxy);

    // 查询
    ActionDefinition getAction(String resourceName, String actionName);
    Map<String, ActionDefinition> getResourceActions(String resourceName);
    Set<String> getResources();

    // 资源定义
    void defineResource(ResourceDefinition resource);
    ResourceDefinition getResource(String resourceName);

    // 同步到全局注册表
    void syncToGlobal(GlobalActionRegistry globalRegistry);
}

/**
 * 资源级注册表（V1 核心）
 */
public interface ResourceActionRegistry {

    String getResourceName();

    void registerAction(ActionDefinition action);
    void unregisterAction(String actionName);

    ActionDefinition getAction(String actionName);
    List<ActionDefinition> getAllActions();

    // V2 扩展：资源级元数据
    ResourceMetadata getMetadata();
}
```

### 4.2 存储后端抽象（支持多模式）

```java
/**
 * 存储后端接口
 */
public interface StorageBackend {

    void save(ActionDefinition action);
    void delete(String actionKey);
    ActionDefinition findByKey(String actionKey);
    List<ActionDefinition> findAll();
    List<ActionDefinition> findByQuery(ActionQuery query);

    // 监听变更（用于分布式场景）
    void watch(Consumer<RegistryEvent> callback);
}

/**
 * 内存存储（V1 嵌入式模式）
 */
@Component
public class InMemoryStorageBackend implements StorageBackend {
    private final ConcurrentHashMap<String, ActionDefinition> store = new ConcurrentHashMap<>();

    @Override
    public void save(ActionDefinition action) {
        store.put(action.getFullKey(), action);
    }

    @Override
    public ActionDefinition findByKey(String actionKey) {
        return store.get(actionKey);
    }

    // ...
}

/**
 * Redis 存储（增强嵌入式模式）
 */
@Component
public class RedisStorageBackend implements StorageBackend {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Override
    public void save(ActionDefinition action) {
        String json = JsonUtils.toJson(action);
        redisTemplate.opsForHash().put("action:registry", action.getFullKey(), json);
    }

    @Override
    public ActionDefinition findByKey(String actionKey) {
        String json = (String) redisTemplate.opsForHash().get("action:registry", actionKey);
        return JsonUtils.fromJson(json, ActionDefinition.class);
    }

    // ...
}

/**
 * 远程控制面存储（V2 独立模式）
 */
@Component
public class RemoteRegistryBackend implements StorageBackend {
    @Autowired
    private ActionRegistryClient registryClient;

    @Override
    public void save(ActionDefinition action) {
        registryClient.register(action.getMetadata());
    }

    @Override
    public ActionDefinition findByKey(String actionKey) {
        return registryClient.getAction(actionKey);
    }

    // ...
}
```

---

## 5. 统一调用机制

### 5.1 Action 执行引擎（融合版）

```java
/**
 * Action 执行引擎（融合 V1 + V2）
 */
public interface ActionEngine {

    // ==================== V1 风格 API ====================

    ActionResult execute(String actionName, Map<String, Object> params);
    ActionResult execute(String actionName, ActionContext context);
    ActionResult execute(String serviceName, String resourceName, String actionName,
                         Map<String, Object> params);

    CompletableFuture<ActionResult> executeAsync(String actionName, Map<String, Object> params);
    List<ActionResult> executeBatch(List<ActionRequest> requests);
    ActionResult executePipeline(ActionPipeline pipeline);

    // ==================== V2 风格 API ====================

    <I, O> ActionResponse<O> invoke(String actionKey, I input);
    <I, O> ActionResponse<O> invoke(String actionKey, String version, I input);
    <I, O> ActionResponse<O> invoke(String actionKey, I input, InvokeOptions options);

    <I, O> CompletableFuture<ActionResponse<O>> invokeAsync(String actionKey, I input);

    // 批量调用（V2 增强）
    <I, O> List<ActionResponse<O>> invokeBatch(List<InvocationRequest<I>> requests);

    // 流式调用（V2 新增）
    <I, O> Flux<O> invokeStream(String actionKey, I input);
}

/**
 * 执行引擎实现
 */
@Component
public class DefaultActionEngine implements ActionEngine {

    @Autowired
    private GlobalActionRegistry registry;

    @Autowired
    private ActionContextFactory contextFactory;

    @Autowired
    private List<ActionInterceptor> interceptors;

    @Autowired
    private ActionInvoker invoker;

    // V1 兼容实现
    @Override
    public ActionResult execute(String actionName, Map<String, Object> params) {
        ActionDefinition action = registry.getAction(actionName);
        if (action == null) {
            throw new ActionNotFoundException(actionName);
        }

        ActionContext context = contextFactory.create(action, params);
        return executeWithContext(action, context);
    }

    // V2 风格实现
    @Override
    public <I, O> ActionResponse<O> invoke(String actionKey, I input) {
        ActionDefinition action = registry.getActionByKey(actionKey);
        if (action == null) {
            throw new ActionNotFoundException(actionKey);
        }

        // 状态检查
        if (!action.isInvocable()) {
            throw new ActionStateException(action.getState());
        }

        // 构建调用选项
        InvokeOptions options = InvokeOptions.builder()
            .timeoutMs(action.getMetadata().getSpec().getEndpoint().getTimeoutMs())
            .retryPolicy(action.getMetadata().getSpec().getRetryPolicy())
            .build();

        return invoker.invoke(action, input, options);
    }

    private ActionResult executeWithContext(ActionDefinition action, ActionContext context) {
        // 执行拦截器链
        ActionInvocation invocation = new ActionInvocation(action, context, interceptors);
        Object result = invocation.proceed();
        return ActionResult.success(result);
    }
}
```

### 5.2 调用器（带治理能力）

```java
/**
 * Action 调用器（融合治理能力）
 */
@Component
public class ActionInvoker {

    @Autowired
    private ActionCatalog catalog;

    @Autowired
    private LoadBalancer loadBalancer;

    @Autowired
    private CircuitBreakerManager circuitBreakerManager;

    @Autowired
    private RateLimiterManager rateLimiterManager;

    @Autowired
    private List<ProtocolAdapter> protocolAdapters;

    public <I, O> ActionResponse<O> invoke(ActionDefinition action, I input, InvokeOptions options) {
        String actionKey = action.getFullKey();

        // 1. 限流检查
        if (!rateLimiterManager.tryAcquire(actionKey)) {
            throw new RateLimitExceededException(actionKey);
        }

        // 2. 熔断检查
        CircuitBreaker cb = circuitBreakerManager.getCircuitBreaker(actionKey);
        if (cb.isOpen()) {
            throw new CircuitBreakerOpenException(actionKey);
        }

        // 3. 选择协议适配器
        ProtocolAdapter adapter = selectProtocolAdapter(action);

        // 4. 执行调用
        long startTime = System.currentTimeMillis();
        try {
            O result = adapter.invoke(action, input, options);

            // 记录成功
            cb.recordSuccess();

            long duration = System.currentTimeMillis() - startTime;
            return ActionResponse.<O>builder()
                .code("OK")
                .data(result)
                .meta(ResponseMeta.builder()
                    .actionKey(actionKey)
                    .durationMs(duration)
                    .build())
                .build();

        } catch (Exception e) {
            // 记录失败
            cb.recordFailure();

            throw new ActionInvocationException(actionKey, e);
        }
    }

    private ProtocolAdapter selectProtocolAdapter(ActionDefinition action) {
        String protocol = action.getMetadata().getSpec().getProtocol();
        return protocolAdapters.stream()
            .filter(a -> a.supports(protocol))
            .findFirst()
            .orElseThrow(() -> new UnsupportedProtocolException(protocol));
    }
}
```

---

## 6. 注解驱动（V1 核心保留）

```java
/**
 * 资源定义注解（V1 风格）
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface ActionResource {
    String value();              // 资源名称
    String service() default ""; // 服务名（可选）
    String description() default "";

    // V2 扩展
    String domain() default "";
    String boundedContext() default "";
}

/**
 * Action 定义注解（V1 风格）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Action {
    String value();              // Action 名称
    String title() default "";
    String description() default "";
    boolean publicAccess() default false;
    boolean async() default false;
    long timeout() default 30000L;

    // V2 扩展
    String version() default "1.0.0";
    ActionType type() default ActionType.SYNC;
    String protocol() default "HTTP";
}

/**
 * 参数注解（V1 风格）
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ActionParam {
    String value();
    boolean required() default true;
    String defaultValue() default "";
    String description() default "";

    // V2 扩展：验证规则
    String pattern() default "";
    long min() default Long.MIN_VALUE;
    long max() default Long.MAX_VALUE;
}

/**
 * 使用示例（融合风格）
 */
@ActionResource(
    value = "customer",
    service = "crm-service",
    domain = "crm",
    boundedContext = "customer"
)
@Component
public class CustomerResource {

    @Autowired
    private CustomerService customerService;

    // V1 简洁风格
    @Action(value = "get", title = "查询客户")
    public Customer getCustomer(
            @ActionParam("id") String customerId) {
        return customerService.findById(customerId);
    }

    // V2 完整风格
    @Action(
        value = "create",
        title = "创建客户",
        version = "2.0.0",
        type = ActionType.SYNC,
        timeout = 5000
    )
    public Customer createCustomer(
            @ActionParam(value = "customerName", required = true, pattern = "^.{2,100}$")
            String customerName,
            @ActionParam(value = "contact", required = true)
            ContactInfo contact) {
        return customerService.create(customerName, contact);
    }
}
```

---

## 7. 配置体系

### 7.1 渐进式配置

```yaml
# application.yml

# ========== 嵌入式模式（V1 风格，最小配置） ==========
action:
  service-name: my-service
  gateway:
    enabled: true
    path: /api

# ========== 增强嵌入式模式（推荐） ==========
action:
  service-name: my-service

  # 注册表配置
  registry:
    mode: embedded        # embedded / remote
    storage: redis        # memory / redis / mysql

  # 缓存配置
  cache:
    type: caffeine        # caffeine / redis
    ttl: 5m

  # 调用配置
  invocation:
    timeout: 5000
    retry:
      enabled: true
      max-attempts: 3
    circuit-breaker:
      enabled: true
      failure-rate-threshold: 50

# ========== 独立控制面模式（V2 风格） ==========
action:
  service-name: my-service

  registry:
    mode: remote
    server-url: http://action-registry:8080
    api-key: ${REGISTRY_API_KEY}
    heartbeat-interval: 30s

  catalog:
    cache-ttl: 5m
    preload-on-startup: true

  # 多租户
  tenant:
    enabled: true
    header-name: X-Tenant-ID
    resolver: header  # header / jwt / custom

  # 可观测性
  observability:
    metrics:
      enabled: true
      export-to: prometheus
    tracing:
      enabled: true
      sampler: 0.1
    audit:
      enabled: true
      storage: mysql
```

---

## 8. 迁移路径

```
阶段 1: 嵌入式模式（V1）
    │
    │ 业务增长，需要治理能力
    ▼

阶段 2: 增强嵌入式（V1 + Redis/MySQL）
    │
    │ 多团队协作，需要集中管理
    ▼

阶段 3: 独立控制面（V2）
    │
    │ 企业级需求：多租户、审计、版本治理
    ▼

阶段 4: 联邦模式（多级控制面）
```

---

## 9. 最佳实践总结

| 场景 | 推荐方案 | 关键配置 |
|------|----------|----------|
| 单体应用/快速原型 | 嵌入式 + 内存 | `mode: embedded, storage: memory` |
| 中小型微服务 | 嵌入式 + Redis | `mode: embedded, storage: redis` |
| 中大型微服务 | 独立控制面 | `mode: remote` |
| 多租户 SaaS | 独立控制面 + 多租户 | `tenant.enabled: true` |
| 金融级高可用 | 独立控制面集群 + 联邦 | 多级控制面部署 |

---

## 10. 设计取舍说明

### 保留 V1 的核心设计
1. **三层注册架构**：Global/Service/Resource 清晰分层
2. **注解驱动开发**：简洁直观，开发效率高
3. **统一调用方式**：ActionEngine 屏蔽本地/远程差异
4. **拦截器机制**：AOP 扩展能力强

### 引入 V2 的关键能力
1. **控制面/数据面分离**：支持独立部署和治理
2. **生命周期管理**：DRAFT → ACTIVE → DEPRECATED → DISABLED
3. **SemVer 版本管理**：规范化版本控制
4. **多租户支持**：数据隔离和权限控制
5. **标准化 Schema**：JSON Schema 2020-12
6. **可观测性**：Metrics + Tracing + Audit

### 兼容性保证
1. **双模式 API**：V1 和 V2 风格 API 并存
2. **标识符兼容**：支持 service:resource:action 和 domain.context.action:version 两种格式
3. **配置兼容**：V1 配置可在 V2 中直接使用
4. **平滑升级**：无需修改业务代码即可切换部署模式
