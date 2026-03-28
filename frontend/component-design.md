# 低代码平台前端组件设计文档

## 1. 前端架构概述

### 1.1 设计目标

- **组件化开发**：支持自定义前端组件的动态注册和加载
- **前后端联动**：前端组件与后端 Action 自动绑定
- **可视化配置**：通过 JSON Schema 定义组件属性，支持低代码配置
- **动态加载**：支持运行时加载远程组件（UMD/ESM 格式）

### 1.2 架构图

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         低代码平台前端                                   │
├─────────────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────────────┐ │
│  │  页面设计器      │  │   运行时引擎     │  │     组件市场            │ │
│  │  (Design Time)  │  │  (Runtime)      │  │  (Component Registry)   │ │
│  └────────┬────────┘  └────────┬────────┘  └─────────────────────────┘ │
│           │                    │                                        │
│           ▼                    ▼                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                    Component SDK (核心)                           │  │
│  │  ┌────────────┐ ┌────────────┐ ┌────────────┐ ┌────────────┐     │  │
│  │  │ Component  │ │  Action    │ │  Data      │ │  Event     │     │  │
│  │  │ Registry   │ │  Binding   │ │  Source    │ │  System    │     │  │
│  │  └────────────┘ └────────────┘ └────────────┘ └────────────┘     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                              │                                          │
│                              ▼                                          │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │                     组件层 (Components)                           │  │
│  │  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌────────┐  │  │
│  │  │ 基础组件  │ │ 表单组件  │ │ 列表组件  │ │ 图表组件  │ │ 自定义 │  │  │
│  │  │ Input    │ │ Form     │ │ Table    │ │ Chart    │ │ 业务   │  │  │
│  │  │ Button   │ │ Field    │ │ List     │ │ Graph    │ │ 组件   │  │  │
│  │  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └────────┘  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
                                    │
                                    │ HTTP / WebSocket
                                    ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                         后端服务层                                      │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  Action Registry Service    │   Action Execution Engine          │  │
│  │  (组件元数据注册)            │   (组件关联的后端 Action 执行)      │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## 2. 核心概念

### 2.1 组件定义 (ComponentDefinition)

```typescript
interface ComponentDefinition {
  // 基础信息
  id: string;                    // 唯一标识
  namespace: string;             // 命名空间
  name: string;                  // 组件名称
  version: string;               // 版本号 (SemVer)
  title: string;                 // 显示标题
  description: string;           // 描述
  icon?: string;                 // 图标

  // 分类
  category: ComponentCategory;   // 分类: BASIC | FORM | DATA | CHART | CUSTOM
  tags: string[];                // 标签

  // 组件实现
  entry: {
    type: 'umd' | 'esm' | 'internal';  // 加载方式
    url?: string;                      // 远程组件地址
    moduleName?: string;               // UMD 模块名
    cssUrl?: string;                   // 样式文件
  };

  // Schema 定义
  propsSchema: JSONSchema7;      // 属性配置 Schema
  eventsSchema: EventSchema[];   // 事件配置 Schema

  // 关联后端 Action
  actions?: ComponentAction[];

  // 依赖
  dependencies?: ComponentDependency[];

  // 设计时配置
  designTime?: {
    resizable: boolean;          // 是否可调整大小
    draggable: boolean;          // 是否可拖拽
    defaultWidth: number;        // 默认宽度
    defaultHeight: number;       // 默认高度
    minimizable: boolean;        // 是否可最小化
    maximizable: boolean;        // 是否可最大化
  };
}

// 组件关联的 Action
type ComponentAction = {
  name: string;                  // Action 名称
  displayName: string;           // 显示名称
  description?: string;          // 描述
  actionKey: string;             // 后端 Action 标识
  trigger: 'auto' | 'manual' | 'event';  // 触发方式
  // 参数映射（使用完整的映射类型）
  inputMapping?: Record<string, ParamMapping>;
  outputMapping?: Record<string, ResultMapping>;
  loadingTarget?: string;        // loading 状态绑定到哪个属性
  errorTarget?: string;          // error 状态绑定到哪个属性
};

// 参数映射类型
type ParamMapping =
  | { type: 'static'; value: any }
  | { type: 'prop'; propName: string }
  | { type: 'context'; path: string }
  | { type: 'expression'; expr: string };

// 结果映射类型
type ResultMapping =
  | { type: 'direct'; resultPath: string }
  | { type: 'transform'; expr: string };
```

### 2.2 属性配置 Schema

```typescript
// 组件属性 Schema 示例
const fileViewerPropsSchema: JSONSchema7 = {
  type: 'object',
  title: '文件阅览器配置',
  properties: {
    // 数据源配置
    fileUrl: {
      type: 'string',
      title: '文件URL',
      description: '文件的完整URL地址',
      'x-component': 'Input',
      'x-component-props': {
        placeholder: '请输入文件URL',
        allowClear: true
      }
    },
    fileId: {
      type: 'string',
      title: '文件ID',
      description: '通过文件ID获取文件',
      'x-component': 'Input',
      'x-reactions': [
        {
          target: 'fileUrl',
          fulfill: {
            state: {
              visible: '{{!$self.value}}'
            }
          }
        }
      ]
    },

    // 显示配置
    viewerType: {
      type: 'string',
      title: '阅览器类型',
      enum: ['auto', 'image', 'pdf', 'doc', 'video', 'audio'],
      default: 'auto',
      'x-component': 'Select',
      'x-component-props': {
        options: [
          { label: '自动检测', value: 'auto' },
          { label: '图片', value: 'image' },
          { label: 'PDF', value: 'pdf' },
          { label: '文档', value: 'doc' },
          { label: '视频', value: 'video' },
          { label: '音频', value: 'audio' }
        ]
      }
    },

    // 外观配置
    width: {
      type: 'string',
      title: '宽度',
      default: '100%',
      'x-component': 'Input'
    },
    height: {
      type: 'string',
      title: '高度',
      default: '500px',
      'x-component': 'Input'
    },

    // 功能配置
    showToolbar: {
      type: 'boolean',
      title: '显示工具栏',
      default: true,
      'x-component': 'Switch'
    },
    showDownload: {
      type: 'boolean',
      title: '允许下载',
      default: true,
      'x-component': 'Switch'
    },
    showPrint: {
      type: 'boolean',
      title: '允许打印',
      default: false,
      'x-component': 'Switch'
    },

    // 高级配置
    watermark: {
      type: 'object',
      title: '水印配置',
      properties: {
        enabled: {
          type: 'boolean',
          title: '启用水印',
          default: false,
          'x-component': 'Switch'
        },
        text: {
          type: 'string',
          title: '水印文字',
          'x-component': 'Input',
          'x-reactions': [
            {
              dependencies: ['watermark.enabled'],
              fulfill: {
                state: {
                  visible: '{{$deps[0]}}'
                }
              }
            }
          ]
        },
        opacity: {
          type: 'number',
          title: '透明度',
          default: 0.3,
          minimum: 0,
          maximum: 1,
          'x-component': 'Slider',
          'x-component-props': {
            step: 0.1
          },
          'x-reactions': [
            {
              dependencies: ['watermark.enabled'],
              fulfill: {
                state: {
                  visible: '{{$deps[0]}}'
                }
              }
            }
          ]
        }
      }
    },

    // 权限配置
    permission: {
      type: 'object',
      title: '权限配置',
      properties: {
        viewPermission: {
          type: 'string',
          title: '查看权限',
          'x-component': 'PermissionSelector'
        },
        downloadPermission: {
          type: 'string',
          title: '下载权限',
          'x-component': 'PermissionSelector'
        }
      }
    }
  }
};
```

### 2.3 事件系统

```typescript
interface EventSchema {
  name: string;                  // 事件名称
  title: string;                 // 显示标题
  description?: string;          // 描述
  parameters?: {                 // 事件参数
    name: string;
    type: string;
    description?: string;
  }[];
}

// 文件阅览器事件定义
const fileViewerEvents: EventSchema[] = [
  {
    name: 'onLoad',
    title: '文件加载完成',
    description: '文件内容加载完成后触发',
    parameters: [
      { name: 'fileInfo', type: 'FileInfo', description: '文件信息' }
    ]
  },
  {
    name: 'onError',
    title: '加载失败',
    description: '文件加载失败时触发',
    parameters: [
      { name: 'error', type: 'Error', description: '错误信息' }
    ]
  },
  {
    name: 'onDownload',
    title: '下载文件',
    description: '用户点击下载时触发',
    parameters: [
      { name: 'fileInfo', type: 'FileInfo', description: '文件信息' }
    ]
  },
  {
    name: 'onPageChange',
    title: '页码变更',
    description: 'PDF/文档切换页码时触发',
    parameters: [
      { name: 'currentPage', type: 'number', description: '当前页码' },
      { name: 'totalPages', type: 'number', description: '总页数' }
    ]
  }
];
```

---

## 3. 前后端联动机制

### 3.1 联动架构

```
┌─────────────────────────────────────────────────────────────────┐
│                          前端页面                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  文件阅览组件 (FileViewer)                                │   │
│  │  ┌─────────────────────────────────────────────────┐   │   │
│  │  │  Props:                                         │   │   │
│  │  │   - fileId: "file_123"                          │   │   │
│  │  │   - showDownload: true                          │   │   │
│  │  │                                                 │   │   │
│  │  │  Actions:                                       │   │   │
│  │  │   ┌─────────────┐      ┌─────────────┐         │   │   │
│  │  │   │  loadFile   │─────▶│  download   │         │   │   │
│  │  │   │  (自动触发)  │      │  (手动触发)  │         │   │   │
│  │  │   └─────────────┘      └─────────────┘         │   │   │
│  │  └─────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              │ Component SDK                    │
│                              │  - Action Binding                │
│                              │  - Data Mapping                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  HTTP Request                                           │   │
│  │  POST /api/invoke/storage.file.get                        │   │
│  │  X-Action-Version: 1.0.0                                  │   │
│  │  {                                                      │   │
│  │    "input": { "fileId": "file_123" }                    │   │
│  │  }                                                      │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                                    │
                                    ▼
┌─────────────────────────────────────────────────────────────────┐
│                          后端服务                                │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  Action Invoker                                         │   │
│  │  ┌─────────────┐    ┌─────────────┐    ┌─────────────┐ │   │
│  │  │   Router    │───▶│  Validator  │───▶│  Handler    │ │   │
│  │  └─────────────┘    └─────────────┘    └─────────────┘ │   │
│  └─────────────────────────────────────────────────────────┘   │
│                              │                                  │
│                              ▼                                  │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │  FileResource                                           │   │
│  │  @Action(value = "get", title = "获取文件")              │   │
│  │  public FileInfo getFile(@ActionParam("fileId") ...)    │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

### 3.2 Action 绑定配置

```typescript
// 组件 Action 绑定配置
interface ActionBinding {
  // Action 标识
  actionKey: string;             // 如: "storage.file.get:1.0.0"

  // 触发配置
  trigger: {
    type: 'mount' | 'watch' | 'event' | 'manual';
    // mount: 组件挂载时自动触发
    // watch: 监听某个属性变化时触发
    // event: 绑定到组件事件
    // manual: 手动触发（如点击按钮）

    watch?: string;              // trigger=watch 时，监听的属性名
    event?: string;              // trigger=event 时，绑定的事件名
    debounce?: number;           // 防抖时间(ms)
    throttle?: number;           // 节流时间(ms)
  };

  // 参数映射
  inputMapping: {
    // 支持多种映射方式
    [paramName: string]:
      | { type: 'static'; value: any }           // 静态值
      | { type: 'prop'; propName: string }       // 组件属性
      | { type: 'context'; path: string }        // 上下文数据
      | { type: 'expression'; expr: string };    // 表达式
  };

  // 结果映射
  outputMapping: {
    [componentProp: string]:
      | { type: 'direct'; resultPath: string }   // 直接映射
      | { type: 'transform'; expr: string };    // 转换表达式
  };

  // 状态映射
  stateMapping?: {
    loading?: string;            // loading 状态映射到的属性
    error?: string;              // error 状态映射到的属性
    data?: string;               // data 状态映射到的属性
  };

  // 错误处理
  errorHandling?: {
    strategy: 'throw' | 'silent' | 'fallback' | 'notify';
    fallbackValue?: any;
    notifyUser?: boolean;
  };
}

// 文件阅览器 Action 绑定示例
const fileViewerActionBindings: ActionBinding[] = [
  {
    actionKey: 'storage.file.get:1.0.0',
    trigger: { type: 'mount' },
    inputMapping: {
      fileId: { type: 'prop', propName: 'fileId' },
      url: { type: 'prop', propName: 'fileUrl' }
    },
    outputMapping: {
      fileInfo: { type: 'direct', resultPath: 'data' },
      loading: { type: 'static', value: false }
    },
    stateMapping: {
      loading: 'loading',
      error: 'error',
      data: 'fileInfo'
    }
  },
  {
    actionKey: 'storage.file.download:1.0.0',
    trigger: { type: 'event', event: 'onDownload' },
    inputMapping: {
      fileId: { type: 'prop', propName: 'fileId' },
      fileName: { type: 'context', path: 'fileInfo.name' }
    },
    errorHandling: {
      strategy: 'silent',
      notifyUser: true
    }
  },
  {
    actionKey: 'storage.file.preview-url:1.0.0',
    trigger: { type: 'watch', watch: 'fileId', debounce: 300 },
    inputMapping: {
      fileId: { type: 'prop', propName: 'fileId' }
    },
    outputMapping: {
      previewUrl: { type: 'direct', resultPath: 'data.url' }
    }
  }
];
```

---

## 4. 组件运行时 SDK

### 4.1 核心接口

```typescript
// 组件基类
abstract class LowCodeComponent<P = {}, S = {}> {
  // 组件定义
  static definition: ComponentDefinition;

  // 属性
  props: P;

  // 状态
  state: S;

  // 上下文
  context: ComponentContext;

  // Action 执行器
  actionExecutor: ActionExecutor;

  // 生命周期
  abstract mount(container: HTMLElement): void;
  abstract unmount(): void;
  abstract updateProps(props: Partial<P>): void;

  // 事件触发
  emit(eventName: string, ...args: any[]): void;

  // 调用 Action
  invokeAction(actionName: string, params?: Record<string, any>): Promise<any>;
}

// 组件上下文
interface ComponentContext {
  // 应用级上下文
  app: {
    name: string;
    version: string;
    theme: ThemeConfig;
    locale: string;
  };

  // 页面级上下文
  page: {
    id: string;
    name: string;
    params: Record<string, any>;
    query: Record<string, any>;
  };

  // 用户上下文
  user: {
    id: string;
    name: string;
    permissions: string[];
    roles: string[];
  };

  // 数据上下文
  data: {
    // 页面共享数据
    [key: string]: any;
  };

  // 获取上下文值
  get(path: string): any;
  set(path: string, value: any): void;
}

// Action 执行器
interface ActionExecutor {
  invoke(
    actionKey: string,
    input: any,
    options?: InvokeOptions
  ): Promise<ActionResponse>;

  invokeComponentAction(
    componentId: string,
    actionName: string,
    params?: Record<string, any>
  ): Promise<any>;
}
```

### 4.2 组件注册与加载

```typescript
// 组件注册中心
class ComponentRegistry {
  private components: Map<string, ComponentDefinition> = new Map();
  private instances: Map<string, LowCodeComponent> = new Map();

  // 注册组件
  register(definition: ComponentDefinition): void {
    this.components.set(definition.id, definition);
  }

  // 批量注册
  registerBatch(definitions: ComponentDefinition[]): void {
    definitions.forEach(d => this.register(d));
  }

  // 从远程加载组件
  async loadFromRemote(url: string): Promise<ComponentDefinition> {
    const response = await fetch(url);
    const module = await response.text();

    // 执行 UMD 模块
    const definition = this.executeUMD(module);
    this.register(definition);
    return definition;
  }

  // 获取组件定义
  getDefinition(id: string): ComponentDefinition | undefined {
    return this.components.get(id);
  }

  // 创建组件实例
  async createInstance(
    id: string,
    props: Record<string, any>,
    context: ComponentContext
  ): Promise<LowCodeComponent> {
    const definition = this.getDefinition(id);
    if (!definition) {
      throw new Error(`Component ${id} not found`);
    }

    // 加载组件代码
    const ComponentClass = await this.loadComponentCode(definition);

    // 创建实例
    const instance = new ComponentClass();
    instance.props = props;
    instance.context = context;

    // 初始化 Action 绑定
    this.initializeActionBindings(instance, definition.actions);

    return instance;
  }

  // 初始化 Action 绑定
  private initializeActionBindings(
    instance: LowCodeComponent,
    actions?: ComponentAction[]
  ): void {
    if (!actions) return;

    actions.forEach(action => {
      // 根据 trigger 类型设置监听
      switch (action.trigger) {
        case 'auto':
          // 组件挂载后自动触发
          this.bindAutoTrigger(instance, action);
          break;
        case 'manual':
          // 注册到 instance 的方法上
          this.bindManualTrigger(instance, action);
          break;
        case 'event':
          // 绑定到组件事件
          this.bindEventTrigger(instance, action);
          break;
      }
    });
  }
}
```

---

## 5. 组件市场与版本管理

### 5.1 组件市场架构

```
┌─────────────────────────────────────────────────────────────────┐
│                        组件市场 (Component Market)               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐          │
│  │   官方组件    │  │   团队组件    │  │   个人组件    │          │
│  │  (Official)  │  │   (Team)     │  │ (Personal)   │          │
│  │              │  │              │  │              │          │
│  │ • 基础组件   │  │ • 业务组件   │  │ • 共享组件   │          │
│  │ • 表单组件   │  │ • 模板组件   │  │ • 实验组件   │          │
│  │ • 图表组件   │  │ • 定制组件   │  │              │          │
│  └──────────────┘  └──────────────┘  └──────────────┘          │
│                                                                 │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │                    组件仓库 (Repository)                 │   │
│  │  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌─────────┐       │   │
│  │  │版本管理 │ │依赖分析 │ │质量检测 │ │安全扫描 │       │   │
│  │  └─────────┘ └─────────┘ └─────────┘ └─────────┘       │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 版本管理策略

```typescript
interface ComponentVersionPolicy {
  // 版本号规则 (SemVer)
  version: string;

  // 兼容性等级
  compatibility: 'major' | 'minor' | 'patch';

  // 升级策略
  upgradePolicy: {
    autoUpgrade: boolean;          // 是否自动升级
    allowedVersions: string[];     // 允许升级的版本范围
    blockedVersions: string[];     // 禁止升级的版本
  };

  // 迁移指南
  migration?: {
    fromVersion: string;
    toVersion: string;
    guide: string;                 // 迁移文档
    codemod?: string;              // 自动迁移脚本
  };
}

// 组件发布流程
interface ComponentPublishFlow {
  // 1. 代码提交
  submit: {
    sourceCode: string;            // 源代码
    compiledCode: string;          // 编译后代码
    sourceMap: string;             // Source map
  };

  // 2. 质量检查
  qualityCheck: {
    lint: boolean;                 // 代码规范检查
    test: TestResult;              // 单元测试
    coverage: number;              // 测试覆盖率
    bundleSize: number;            // 打包大小
  };

  // 3. 安全扫描
  securityScan: {
    vulnerabilities: SecurityVuln[];
    license: string;               // 许可证检查
    dependencies: DependencyVuln[];
  };

  // 4. 文档生成
  documentation: {
    readme: string;
    changelog: string;
    apiDoc: string;
    examples: ExampleCase[];
  };

  // 5. 发布
  publish: {
    registry: string;              // 发布目标
    tags: string[];                // 标签 (latest, beta, etc.)
    visibility: 'public' | 'private' | 'org';
  };
}
```

---

## 6. 设计时与运行时分离

### 6.1 设计时 (Design Time)

```typescript
// 设计器配置
interface DesignerConfig {
  // 画布配置
  canvas: {
    width: number;
    height: number;
    gridSize: number;
    showGrid: boolean;
    snapToGrid: boolean;
  };

  // 组件面板
  componentPanel: {
    groups: ComponentGroup[];
    searchEnabled: boolean;
    dragEnabled: boolean;
  };

  // 属性面板
  propertyPanel: {
    tabs: PropertyTab[];
    jsonEdit: boolean;
    expressionEdit: boolean;
  };

  // 数据源面板
  dataSourcePanel: {
    supportActions: boolean;
    supportVariables: boolean;
    supportApis: boolean;
  };
}

// 设计器状态
interface DesignerState {
  // 当前选中的组件
  selectedComponentId: string | null;

  // 页面结构树
  componentTree: ComponentNode[];

  // 历史记录
  history: HistoryRecord[];

  // 数据源
  dataSources: DataSource[];

  // 变量
  variables: Variable[];
}

// 组件节点
interface ComponentNode {
  id: string;
  componentId: string;
  props: Record<string, any>;
  style: CSSProperties;
  events: EventBinding[];
  actions: ActionBinding[];
  children?: ComponentNode[];
  parentId?: string;
}
```

### 6.2 运行时 (Runtime)

```typescript
// 运行时配置
interface RuntimeConfig {
  // 模式
  mode: 'preview' | 'production' | 'debug';

  // 组件加载配置
  componentLoading: {
    strategy: 'eager' | 'lazy' | 'onDemand';
    cdnBase?: string;
    registryUrl?: string;
  };

  // Action 调用配置
  actionInvocation: {
    baseUrl: string;
    timeout: number;
    retryPolicy: RetryPolicy;
    batchEnabled: boolean;         // 是否启用批量调用
  };

  // 缓存配置
  cache: {
    componentCache: boolean;
    dataCache: boolean;
    actionResultCache: boolean;
  };

  // 监控配置
  monitoring: {
    performance: boolean;
    errorTracking: boolean;
    userBehavior: boolean;
  };
}

// 运行时引擎
class RuntimeEngine {
  private registry: ComponentRegistry;
  private actionExecutor: ActionExecutor;
  private context: ComponentContext;

  constructor(config: RuntimeConfig) {
    this.registry = new ComponentRegistry();
    this.actionExecutor = new ActionExecutor(config.actionInvocation);
    this.context = this.initializeContext();
  }

  // 渲染页面
  async renderPage(pageSchema: PageSchema, container: HTMLElement): Promise<void> {
    // 1. 加载页面所需组件
    await this.loadPageComponents(pageSchema);

    // 2. 构建组件树
    const rootNode = this.buildComponentTree(pageSchema.components);

    // 3. 渲染到容器
    this.renderNode(rootNode, container);
  }

  // 加载页面组件
  private async loadPageComponents(pageSchema: PageSchema): Promise<void> {
    const componentIds = this.extractComponentIds(pageSchema);

    for (const id of componentIds) {
      if (!this.registry.has(id)) {
        await this.registry.loadFromRemote(`${this.config.componentLoading.registryUrl}/${id}`);
      }
    }
  }

  // 执行 Action（支持批量）
  async invokeActions(
    invocations: ActionInvocation[]
  ): Promise<ActionResponse[]> {
    if (this.config.actionInvocation.batchEnabled && invocations.length > 1) {
      return this.actionExecutor.invokeBatch(invocations);
    }

    return Promise.all(
      invocations.map(inv => this.actionExecutor.invoke(inv))
    );
  }
}
```

---

## 7. 与后端设计的一致性

### 7.1 元数据映射

| 前端概念 | 后端概念 | 映射关系 |
|---------|---------|---------|
| ComponentDefinition | ActionDefinition | 组件元数据 ↔ Action 元数据 |
| ComponentAction | ActionDefinition | 组件 Action ↔ 后端 Action |
| EventSchema | ActionMetadata | 事件定义 ↔ 输入参数 |
| PropsSchema | ParameterSchema | 属性 Schema ↔ 参数 Schema |
| ComponentContext | ActionContext | 组件上下文 ↔ Action 上下文 |

### 7.2 统一标识

```
# 前端组件标识
{category}.{componentName}:{version}
# 示例: data.file-viewer:1.2.0

# 后端 Action 标识
{domain}.{resource}.{action}:{version}
# 示例: storage.file.get:1.0.0

# 关联映射
组件 Action: file-viewer.loadFile ──────▶ 后端 Action: storage.file.get:1.0.0
```

### 7.3 统一调用协议

```typescript
// 前端调用 Action
interface ActionInvocation {
  actionKey: string;             // 如: "storage.file.get:1.0.0"
  input: any;                    // 输入参数
  context: {
    requestId: string;
    tenantId: string;
    userId: string;
    traceId: string;
  };
  options: {
    timeoutMs: number;
    retryCount: number;
    cacheEnabled: boolean;
  };
}

// 与后端 Invoke API 保持一致
// POST /api/invoke/{namespace}.{action}
```

---

## 8. 总结

本前端设计文档与后端 Action 注册机制形成完整闭环：

1. **组件即插件**：前端组件与后端 Action 统一注册到平台
2. **配置驱动**：通过 JSON Schema 实现低代码配置
3. **前后联动**：组件属性 ↔ Action 参数自动映射
4. **运行时动态加载**：支持 UMD/ESM 格式的远程组件
5. **版本一致性**：前后端均采用 SemVer 版本管理
6. **统一调用协议**：通过 Action SDK 实现前后端通信标准化

