# 低代码平台 Action 注册机制设计

借鉴 NocoBase 架构思想设计的面向低代码平台的 Action 注册机制。

## 设计文档

```
├── README.md                               # 本文档
├── backend/                                # 后端设计
│   └── final-design.md                     # 最终设计（控制面/数据面分离）
├── frontend/                               # 前端设计
│   └── component-design.md                 # 前端组件架构设计
├── examples/                               # 组件示例
│   └── file-viewer-component.md            # 文件阅览组件完整示例
└── implementation/                         # 实现代码草稿
    ├── core-implementation.java
    ├── control-plane-implementation.java
    └── spring-boot-starter.java
```

## 核心设计

### 架构

- **控制面**：独立的注册中心服务，管理组件/Action/绑定关系
- **数据面**：轻量 SDK 嵌入业务服务
- **前后端一体化**：Component + Action + Binding 三层联动

### 核心概念

| 概念 | 说明 |
|------|------|
| Component | 前端组件（React/Vue） |
| Action | 后端操作（Java 方法） |
| Binding | 前后端绑定关系 |
| Namespace | 命名空间（领域.上下文） |

### 技术栈

- **后端**：Java 17+, Spring Boot 3.x, PostgreSQL, Redis
- **前端**：React 18+, TypeScript
- **协议**：HTTP/REST, JSON Schema

## 文档导航

| 文档 | 内容 |
|------|------|
| [后端设计](backend/final-design.md) | 控制面/数据面分离架构，前后端联动，完整 API 设计 |
| [前端设计](frontend/component-design.md) | 组件架构、设计时/运行时分离 |
| [文件阅览组件示例](examples/file-viewer-component.md) | 完整的前后端组件实现示例 |

## 许可证

MIT
