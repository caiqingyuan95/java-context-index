# Java Context Index - 完整技术与代码设计文档

## 文档版本
- **版本**: v2.0 (合并版)
- **更新日期**: 2026-04-04
- **文档来源**: 
  - 技术方案文档.md (v1.0)
  - 零Git污染与项目识别优化.md (v1.0)
  - 项目识别与索引复用机制.md (v1.0)

---

## 目录

1. [项目概述](#一项目概述)
2. [核心设计理念](#二核心设计理念)
3. [系统架构](#三系统架构)
4. [技术栈选型](#四技术栈选型)
5. [核心模块设计与代码实现](#五核心模块设计与代码实现)
6. [项目识别与索引复用机制](#六项目识别与索引复用机制)
7. [零Git污染方案](#七零git污染方案)
8. [团队共享索引机制](#八团队共享索引机制)
9. [数据存储设计](#九数据存储设计)
10. [配置管理](#十配置管理)
11. [MCP协议集成](#十一mcp协议集成)
12. [性能优化策略](#十二性能优化策略)
13. [部署方案](#十三部署方案)
14. [安全与权限](#十四安全与权限)
15. [故障排查](#十五故障排查)
16. [最佳实践](#十六最佳实践)
17. [总结](#十七总结)

---

## 一、项目概述

### 1.1 项目目标

构建一个智能化的 Java 代码语义索引系统,将大型 Java 应用的代码结构与自然语言业务能力深度关联,实现通过自然语言直接搜索到对应代码的能力。

### 1.2 核心价值

- **语义化搜索**: 用自然语言搜索代码,如"用户注册时的密码加密逻辑"
- **团队共享**: 一次索引,团队共享,多人协作零重复成本
- **智能增量**: 基于 Git 的智能增量更新,秒级同步代码变更
- **零Git污染**: 所有生成文件存储在全局目录,不影响目标项目版本控制
- **精准识别**: 通过Maven坐标/pom.xml识别项目,不依赖Git远程仓库
- **多引擎支持**: 灵活的 Embedding 模型和向量数据库选择

### 1.3 适用场景

✅ **强烈推荐**:
- 大型 Java 项目 (500+ 文件)
- 团队协作开发 (3+ 人)
- 新成员快速熟悉代码
- 复杂业务逻辑检索
- 代码重构影响分析

⚠️ **不推荐**:
- 小型项目 (<100文件,搜索意义不大)
- 非 Java 项目 (当前仅支持Java)
- 完全离线环境 (需本地Embedding模型)

---

## 二、核心设计理念

### 2.1 主干为王 (Mainline-First)

- **仅监控主干分支** (main/master),确保语义空间的唯一性和稳定性
- 避免多分支索引导致的语义混乱和维护成本
- 团队成员在 feature 分支搜索时,明确提示"结果基于主干索引"

### 2.2 手动触发增量

- **放弃复杂的自动化触发** (WatchService/定时任务)
- 用户在 `git pull` 后手动发起索引刷新 (如: `refresh_index`)
- 简单可靠,避免后台进程占用资源

### 2.3 Git 差分追踪

- 利用 Git Commit Hash 精准定位变更
- `git diff --name-status` 秒级获取变更清单
- 仅处理差异文件,实现增量更新

### 2.4 零Git污染 ⭐

- 所有生成文件存储在全局目录 `~/.java-context-index/`
- 不影响目标Java项目的版本控制
- `git status` 输出完全干净

### 2.5 项目识别策略 (不依赖Git)

- 通过pom.xml的Maven坐标或手动指定projectKey来识别项目
- 团队项目pom.xml天然一致,确保索引复用
- 不强制依赖Git远程仓库配置

### 2.6 团队共享索引

- 一次索引,团队共享,多人协作零重复成本
- 中心化向量数据库,统一语义空间
- 基于Maven坐标的项目识别,确保团队一致性

---

## 三、系统架构

### 3.1 整体架构

```
┌─────────────────────────────────────────────────────┐
│                   接口层                               │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ MCP      │  │ REST API │  │ CLI 命令行        │   │
│  │ Server   │  │          │  │                  │   │
│  └──────────┘  └──────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   业务服务层                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ Index    │  │ Search   │  │ Sync/增量更新     │   │
│  │ Service  │  │ Service  │  │ Service          │   │
│  └──────────┘  └──────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   数据处理层                           │
│  ┌──────────┐  ┌──────────┐  ┌──────────────────┐   │
│  │ Java代码  │  │ 语义文本  │  │ 变更检测          │   │
│  │ AST解析  │  │ 构建器   │  │ (Git/文件指纹)    │   │
│  └──────────┘  └──────────┘  └──────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   AI 向量层                           │
│  ┌──────────────────────────────────────────────┐   │
│  │         Embedding 模型 (可插拔)                │   │
│  │  OpenAI │ 豆包Doubao │ Ollama本地 │ (扩展)    │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   存储层                              │
│  ┌──────────────────────────────────────────────┐   │
│  │       向量数据库 (策略模式,可切换)              │   │
│  │  Milvus │ ChromaDB │ TypeSense │ (扩展)       │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   全局存储层 (零Git污染)               │
│  ┌──────────────────────────────────────────────┐   │
│  │  ~/.java-context-index/                       │   │
│  │  ├── indices/{projectKey}/                    │   │
│  │  │   └── index-metadata.json                  │   │
│  │  ├── cache/                                   │   │
│  │  └── logs/                                    │   │
│  └──────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────┘
```

### 3.2 团队共享架构 (手动同步模式)

```
┌──────────────────────────────────────────────────────┐
│              主干为王 + 手动同步模式                    │
│                                                       │
│  场景: 团队协作,共享向量数据库                           │
│                                                       │
│  1. 代码对齐阶段                                       │
│  ┌────────────────────────────────────────┐           │
│  │ 开发者A (索引构建者/维护者)              │           │
│  │ 1. 切换到主干分支: git checkout main    │           │
│  │ 2. 拉取最新代码: git pull               │           │
│  │ 3. 确认当前commit: git rev-parse HEAD   │           │
│  └────────────────────────────────────────┘           │
│                     ↓                                 │
│  2. 手动触发刷新                                       │
│  ┌────────────────────────────────────────┐           │
│  │ 向Agent发送指令: refresh_index           │           │
│  │ 或执行CLI: java -jar ... --refresh      │           │
│  └────────────────────────────────────────┘           │
│                     ↓                                 │
│  3. 差分计算阶段                                       │
│  ┌────────────────────────────────────────┐           │
│  │ 1. 读取全局元数据                       │           │
│  │    ~/.java-context-index/               │           │
│  │    └── indices/{projectKey}/            │           │
│  │        └── index-metadata.json          │           │
│  │    lastIndexedCommit: "abc123"          │           │
│  │ 2. 获取当前HEAD: "xyz789"               │           │
│  │ 3. 执行: git diff --name-status         │           │
│  │           abc123 xyz789                │           │
│  │ 4. 解析变更清单:                         │           │
│  │    M src/UserService.java               │           │
│  │    A src/NewController.java             │           │
│  │    D src/OldService.java                │           │
│  └────────────────────────────────────────┘           │
│                     ↓                                 │
│  4. 按需同步阶段                                       │
│  ┌────────────────────────────────────────┐           │
│  │ 仅处理变更文件 (3个文件,耗时3-5秒)       │           │
│  │ ├─ M: 删除旧chunks + 索引新内容         │           │
│  │ ├─ A: 全量解析并插入                    │           │
│  │ └─ D: 从向量库删除                      │           │
│  └────────────────────────────────────────┘           │
│                     ↓                                 │
│  ┌────────────────────────────────────────┐           │
│  │      共享向量数据库 (团队共用)           │           │
│  │  - Milvus 集群 / ChromaDB 服务         │           │
│  │  - 集合: java_code_index (主干索引)     │           │
│  │  - 更新成功,所有成员立即可用             │           │
│  └────────────────────────────────────────┘           │
│                     ↑                                 │
│  5. 团队使用阶段                                       │
│  ┌────────────────────────────────────────┐           │
│  │ 开发者B/C/D (索引使用者)                 │           │
│  │ 1. 配置相同向量数据库地址                │           │
│  │ 2. 直接搜索主干索引                      │           │
│  │ 3. 如在feature分支,提示:                 │           │
│  │    ⚠️ "搜索结果基于主干索引"             │           │
│  │ 4. 无需本地索引,零成本使用               │           │
│  └────────────────────────────────────────┘           │
└──────────────────────────────────────────────────────┘
```

---

## 四、技术栈选型

### 4.1 核心框架

| 组件 | 技术选型 | 版本 | 说明 |
|------|---------|------|------|
| **开发语言** | Java | 17 LTS | 现代语法,长期支持 |
| **构建工具** | Maven | 3.9+ | 标准项目管理 |
| **Web框架** | Spring Boot | 3.2.x | REST API + 依赖注入 |
| **AST解析** | JavaParser | 3.26.x | Java生态最成熟AST库 |

### 4.2 Embedding 模型 (多提供商)

| 提供商 | 模型名称 | 维度 | 成本 | 中文能力 | 适用场景 |
|--------|---------|------|------|---------|---------|
| **豆包Doubao** | text-embedding-large-3 | 4096 | 低 | ⭐⭐⭐⭐⭐ | 国内项目首选 ✅ |
| **OpenAI** | text-embedding-3-small | 1536 | 中 | ⭐⭐⭐ | 国际化项目 |
| **Ollama** | nomic-embed-text | 768 | 免费 | ⭐⭐⭐ | 本地/离线部署 |

**选择建议**:
- 🇨🇳 国内团队/中文代码混合 → **豆包** (性价比高,中文理解强,无需代理)
- 🌍 国际化团队/纯英文代码 → **OpenAI** (代码理解最强)
- 🔒 隐私敏感/离线环境 → **Ollama** (本地运行,完全可控)

### 4.3 向量数据库 (多引擎)

| 数据库 | 类型 | 部署 | 性能 | 混合搜索 | 适用场景 |
|--------|------|------|------|---------|---------|
| **ChromaDB** | 轻量级嵌入式 | 单机/Docker | 百万级 | ❌ 仅向量 | 个人/小团队 ✅ |
| **Milvus** | 企业级分布式 | 独立服务/集群 | 亿级 | ⚠️ 基础 | 大型企业项目 |
| **TypeSense** | 混合搜索引擎 | 独立服务 | 千万级 | ✅ 原生 | 需要全文+向量 |

**选择建议**:
- 🚀 快速启动/小团队 → **ChromaDB** (零配置,5分钟上手)
- 🏢 大型企业/海量代码 → **Milvus** (高性能,分布式)
- 🔍 精准搜索需求 → **TypeSense** (原生混合搜索)

### 4.4 变更检测策略

| 策略 | 优先级 | 识别精度 | 性能 | 适用场景 |
|------|--------|---------|------|---------|
| **Git Diff** | ⭐ 唯一方案 | 文件级 | 毫秒 | 主干分支变更检测 |

**核心原则**: 简化设计,仅使用 Git Diff 方案

---

## 五、核心模块设计与代码实现

### 5.1 项目检测器 (ProjectDetector)

**职责**: 检测Java项目,提取项目元数据,生成projectKey

**核心功能**:
1. 检测Java项目根目录
2. 提取项目元数据 (Maven/Gradle坐标)
3. 生成项目唯一标识 (projectKey)
4. 计算全局存储路径 (零Git污染)

**代码实现**: 详见 `src/main/java/com/javacontext/index/parser/ProjectDetector.java`

**关键方法**:
- `detectProject(workingDir, manualKey)`: 检测项目并生成项目信息
- `generateProjectKey(projectPath, manualKey)`: 生成projectKey (优先级: 手动 > Maven > Gradle > Git > 路径Hash)
- `extractMavenCoordinate(projectPath)`: 从pom.xml提取Maven坐标
- `normalizeProjectKey(key)`: 规范化projectKey,确保团队一致性
- `normalizeProjectKeyToDir(projectKey)`: 将projectKey转换为安全的目录名

---

### 5.2 代码解析器 (JavaCodeParser)

**职责**: 使用 JavaParser 对 Java 源码进行 AST 级别解析

**解析能力**:
- ✅ 类、接口、枚举、注解类型
- ✅ 方法、构造函数、字段
- ✅ Javadoc 注释提取
- ✅ 方法签名 (参数、返回值、异常)
- ✅ 注解信息 (@Service, @Controller等)
- ✅ 包结构和导入依赖
- ✅ 访问修饰符

**输出**: `List<CodeChunk>` - 语义化的代码块列表

**代码实现**: 详见 `src/main/java/com/javacontext/index/parser/JavaCodeParser.java`

---

### 5.3 语义文本构建器 (SemanticTextBuilder)

**目标**: 为每个代码块构建适合 Embedding 的增强语义文本

**构建策略**:

```
语义文本 = 上下文信息 + 自然语言描述 + 代码特征 + 逻辑摘要(可选)

示例 - UserService.createUser方法:

"Package: com.example.service. 
Class: UserService. 
Method: createUser. 
Parameters: username, email, password. 
Return: User. 
Description: Creates a new user account with validation. 
Validates input parameters, checks for duplicate email, 
encrypts password, and persists to database. 
Annotations: @Transactional, @Service. 
Throws: DuplicateEmailException, ValidationException."
```

**包含要素** (按权重排序):
1. Javadoc 描述 (核心语义)
2. 包名 + 类名 + 方法名 (上下文)
3. 方法签名 (参数、返回值类型)
4. 注解信息 (框架语义)
5. 异常声明
6. 关键依赖调用 (可选)
7. 逻辑摘要 (针对无注释代码,可选)

**代码实现**: 详见 `src/main/java/com/javacontext/index/embedding/SemanticTextBuilder.java`

---

### 5.4 Embedding 服务

**架构**: 策略模式,支持多模型热切换

**统一接口**:
- `embed(text)` → 单个文本向量化
- `embedAll(texts)` → 批量向量化 (推荐50个/批)
- `getDimension()` → 获取向量维度

**提供商适配**:
- 通过 LangChain4j 统一抽象层
- 配置化切换 (`embedding.provider=doubao/openai/ollama`)
- 自动加载对应模型的配置参数

**性能优化**:
- 批量处理 (减少API调用次数60%)
- 向量缓存 (相同内容复用向量)
- 异步处理 (索引构建时并行)
- 重试机制 (网络异常自动重试)

**代码实现**: 详见 `src/main/java/com/javacontext/index/embedding/EmbeddingService.java`

---

### 5.5 向量数据库服务

**架构**: 策略模式 + 工厂模式,支持运行时切换

**统一接口**:
```
VectorDatabaseStrategy
├─ initialize()                  // 初始化连接和集合
├─ insertCodeChunk()             // 插入单个代码块
├─ insertCodeChunks()            // 批量插入
├─ search()                      // 向量相似度搜索
├─ hybridSearch()                // 混合搜索 (向量+全文)
├─ deleteByChunkIds()            // 根据ID删除
├─ deleteByFilePath()            // 根据文件路径删除
├─ deleteByProjectKey()          // 根据项目Key删除
├─ updateChunk()                 // 更新代码块
├─ existsIndex()                 // 检查项目索引是否存在
└─ getStats()                    // 获取统计信息
```

**代码实现**: 详见 `src/main/java/com/javacontext/index/embedding/VectorDatabaseService.java`

---

### 5.6 元数据管理器 (MetadataManager)

**职责**: 管理索引元数据的保存和加载 (全局存储)

**核心功能**:
1. 保存索引元数据到全局目录 `~/.java-context-index/indices/{projectKey}/`
2. 加载索引元数据
3. 管理增量更新状态

**代码实现**: 详见 `src/main/java/com/javacontext/index/parser/MetadataManager.java`

---

### 5.7 增量索引服务 (IncrementalIndexService)

**职责**: 基于Git Diff的增量索引更新

**核心流程**:
```
1. 用户操作:
   ├─ 切换到主干分支: git checkout main
   ├─ 拉取最新代码: git pull
   └─ 手动触发刷新: refresh_index (Agent指令/CLI命令)
   ↓
2. 读取元数据 (~/.java-context-index/indices/{projectKey}/index-metadata.json):
   ├─ lastIndexedCommit: "abc123def456"
   ├─ baseBranch: "main"
   └─ indexedAt: "2026-04-04T10:30:00"
   ↓
3. Git 差分计算:
   ├─ 获取当前HEAD: git rev-parse HEAD → "xyz789uvw012"
   ├─ 执行差分: git diff --name-status abc123def456 xyz789uvw012
   └─ 解析变更清单:
       M  src/main/java/UserService.java
       A  src/main/java/NewController.java
       D  src/main/java/OldService.java
       R  src/main/java/RenamedService.java
   ↓
4. 按需同步 (仅处理变更文件):
   ├─ 新增 (A): 全量解析 + 插入
   ├─ 修改 (M): 删除旧 + 重新插入
   ├─ 删除 (D): 删除关联chunks
   └─ 重命名 (R): 更新file_path字段 (无需重新向量化)
   ↓
5. 更新元数据:
   ├─ lastIndexedCommit = "xyz789uvw012"
   ├─ indexedAt = 当前时间
   └─ 保存到 index-metadata.json
   ↓
6. 返回统计信息:
   "增量更新完成: 新增1个文件,修改1个文件,删除1个文件,重命名1个文件
    处理chunks: 45个, 耗时: 4.2秒"
```

**代码实现**: 详见 `src/main/java/com/javacontext/index/embedding/IncrementalIndexService.java`

---

## 六、项目识别与索引复用机制

### 6.1 项目Key生成策略

| 优先级 | 策略 | 示例 | 适用场景 | 团队共享 |
|--------|------|------|---------|---------|
| **1** | 手动指定 | `--project-key=myorg:order-service` | 任何场景 ✅ | ✅ 完美支持 |
| **2** | Maven坐标 | `com.myorg:order-service:1.0.0` | Maven项目 ✅ | ✅ 完美支持 |
| **3** | Gradle坐标 | `com.myorg:order-service:1.0.0` | Gradle项目 ✅ | ✅ 完美支持 |
| **4** | Git远程仓库 | `github.com:myorg/order-service` | Git项目 | ✅ 支持 |
| **5** | 项目路径Hash | `local:a1b2c3d4e5f6` | 本地项目 | ❌ 仅本机 |

### 6.2 索引复用检测完整流程

```
用户触发Agent (在任何目录下)
  ↓
第1步: 检测Java项目
  ├─ 扫描项目标识 (pom.xml / build.gradle等)
  ├─ 找到? → 提取项目元数据
  └─ 未找到? → 提示"未检测到Java项目"
  ↓
第2步: 计算项目指纹
  ├─ projectKey = generateProjectKey(projectPath)
  └─ 示例: "com.myorg:order-service:1.0.0-SNAPSHOT"
  ↓
第3步: 查询向量数据库 (关键优化) ⭐
  ├─ 执行查询:
  │   SELECT DISTINCT project_key, project_name, indexed_by, 
  │          lastIndexedCommit, indexedAt, shared
  │   FROM java_code_index
  │   WHERE project_key = "com.myorg:order-service:1.0.0-SNAPSHOT"
  │   LIMIT 1
  │
  ├─ 存在? → ✅ 进入第4步 (复用已有索引)
  └─ 不存在? → 🆕 进入第5步 (需要新建索引)
  ↓
第4步: 复用已有索引 (零成本) ✅
  ├─ 从向量数据库加载索引元数据
  ├─ 检查本地Git状态
  │   ├─ 当前分支: main? ✅
  │   ├─ 本地commit与索引commit相同?
  │   │   ├─ 相同? → 索引已是最新,直接使用 ✅
  │   │   └─ 不同? → 有新commit,提示增量更新 ⚠️
  │   └─ 有未推送变更? → 提示先推送到主干 ⚠️
  │
  ├─ 显示给用户:
  │   ✅ 发现团队索引!
  │   📦 索引信息:
  │      - 构建者: zhangsan@company.com
  │      - 更新时间: 2026-04-04T10:30:00
  │      - 文件数: 1250个
  │      - 代码块: 18,750个
  │   💡 你无需重新索引,可直接搜索
  │
  └─ 用户确认后即可搜索
  ↓
第5步: 新建索引 (首次使用) 🆕
  ├─ 显示项目信息
  ├─ 提示用户: 需要建立索引
  ├─ 用户确认 → 开始全量索引
  └─ 索引完成 → 显示统计信息
```

### 6.3 典型交互场景

#### 场景1: 团队成员克隆项目 (复用索引)

```bash
# 开发者B克隆项目
$ git clone git@github.com:myorg/order-service.git
$ cd order-service

# 触发搜索
$ search_code "用户订单支付逻辑"

Agent响应:
✅ 检测到项目: order-service (Maven)
🔍 计算项目指纹: com.myorg:order-service:1.0.0-SNAPSHOT
🔎 查询向量数据库...
✅ 发现团队索引!

📦 索引信息:
   - 构建者: zhangsan@company.com
   - 最后更新: 2026-04-04T10:30:00
   - 索引commit: abc123def456
   - 总文件: 1250个
   - 总代码块: 18,750个

🔄 检查本地状态...
   - 当前分支: main ✅
   - 本地commit: abc123def456 ✅ (与索引同步)
   - 状态: 索引已是最新

💡 提示: 索引由 zhangsan 构建,你无需重新索引,直接使用即可!

🔍 正在搜索: "用户订单支付逻辑"
找到5个匹配的代码块:
1. OrderService.processPayment (score: 0.94)
   File: src/main/java/OrderService.java:120-156
   ...
```

#### 场景2: Git pull后有新的commit (提示增量更新)

```bash
$ git pull
Updating abc123..xyz789
Fast-forward
 src/main/java/OrderService.java | 15 +++++++++++++++
 1 file changed, 15 insertions(+)

# 触发搜索
$ search_code "订单退款逻辑"

Agent响应:
✅ 检测到项目: order-service
🔍 查询向量数据库... ✅ 已有索引

⚠️ 检测到索引不是最新!

📊 状态对比:
   - 索引commit: abc123def456 (2026-04-04T10:30:00)
   - 当前commit: xyz789uvw012 (2026-04-05T09:15:00)
   - 差异: 1个文件变更

💡 建议执行增量更新 (约2秒):
   refresh_index

或者: 继续使用旧索引搜索 (可能缺少最新代码)
```

### 6.4 成本对比

| 场景 | 无索引复用 | 有索引复用 | 节省 |
|------|-----------|-----------|------|
| **10人团队** | 每人索引1次 = 10次 | 1人索引,9人复用 | **90%** |
| **首次索引 (1000文件)** | 10次 × 8分钟 = 80分钟 | 1次 × 8分钟 = 8分钟 | **72分钟** |
| **API成本 (豆包)** | 10次 × ¥50 = ¥500 | 1次 × ¥50 = ¥50 | **¥450** |
| **增量更新** | 每人独立更新 | 1人更新,9人复用 | **90%** |

---

## 七、零Git污染方案

### 7.1 核心原则

**核心要求**: 本项目生成的所有文件绝对不能提交到Git,不影响目标Java项目的版本控制。

### 7.2 存储位置对比

```
❌ 错误方案 (会污染Git):
  /path/to/java-project/
  ├── .java-context-index/     ← 会在git status中显示
  ├── pom.xml
  └── src/

✅ 正确方案 (零污染):
  全局存储: ~/.java-context-index/
  └── indices/
      ├── com.myorg-order-service-1.0.0/
      │   ├── index-metadata.json
      │   └── project-config.json
      └── com.myorg-user-service-2.0.0/
          └── ...

  目标Java项目 (完全干净):
  /path/to/order-service/
  ├── pom.xml
  ├── src/
  └── target/
  
  ✅ git status输出: 干净,无任何java-context-index文件
```

### 7.3 全局存储目录结构

```
~/.java-context-index/
├── config.json                    # 全局配置
├── indices/                       # 索引元数据
│   ├── com.myorg-order-service-1.0.0-snapshot/
│   │   ├── index-metadata.json    # Git commit等元数据
│   │   └── project-config.json    # 项目配置
│   ├── com.myorg-user-service-2.0.0/
│   │   └── index-metadata.json
│   └── myorg-payment-service/
│       └── index-metadata.json
├── cache/                         # 向量缓存
└── logs/                          # 日志
    └── java-context-index.log
```

### 7.4 安全检查清单

```bash
# 1. 检查Java项目目录
$ cd /path/to/java-project
$ git status
# ✅ 输出应该是干净的

# 2. 确认全局存储
$ ls -la ~/.java-context-index/
# ✅ 所有生成文件都在这里

# 3. 检查是否有文件被误提交
$ git log --all --full-history -- ".java-context-index"
# ✅ 应该无任何输出
```

---

## 八、团队共享索引机制

### 8.1 核心设计理念

**问题**: 团队中每个人都重新索引代码库会浪费大量时间和API成本,多分支索引导致语义空间混乱

**解决方案**: 主干为王 + 一次索引 + 团队共享 + 手动同步

### 8.2 分支感知限制

**核心原则**: 仅索引主干分支,其他分支使用主干索引

```
用户在 feature 分支搜索代码:

1. 用户操作:
   $ git checkout feature-user-auth
   $ # 向Agent发送搜索请求
   $ "查找用户认证的代码"

2. 系统检测:
   ├─ 当前分支: feature-user-auth
   ├─ 索引分支: main (固定)
   └─ 分支不匹配!

3. 系统响应:
   ⚠️ 搜索结果基于主干索引 (main分支)
   📌 当前您在 feature-user-auth 分支
   💡 搜索结果为 latest main 分支的代码
   
   找到3个匹配的代码块:
   1. AuthService.authenticate (score: 0.95)
      File: src/main/java/AuthService.java:45-78
      ...

4. 用户选择:
   ├─ 接受结果: 继续使用主干索引搜索
   └─ 需要feature分支索引:
       1. 切换到主干: git checkout main
       2. 拉取最新: git pull
       3. 更新索引: refresh_index
       4. 切回分支: git checkout feature-user-auth
```

### 8.3 协作模式

#### 模式1: 中心化共享 (推荐)

**适用**: 团队协作,有共享服务器或云服务

**角色分工**:

| 角色 | 职责 | 权限 | 人数 |
|------|------|------|------|
| **索引构建者** | 在主干合并PR后执行刷新 | 主干写权限 | 1-2人 |
| **索引使用者** | 直接搜索,零成本使用 | 只读 | 不限 |

**工作流程**:

```
1. 索引构建者配置:
   vector-db:
     type: milvus
     milvus:
       host: 192.168.1.100  # 共享服务器
       port: 19530
       collection-name: java_code_index

2. 索引构建者执行刷新 (在主干):
   $ git checkout main
   $ git pull
   $ refresh_index
   ✅ 增量更新完成: 处理15个文件,耗时4.2秒

3. 索引使用者配置 (完全相同):
   vector-db:
     type: milvus
     milvus:
       host: 192.168.1.100  # 相同的共享服务器
       port: 19530
       collection-name: java_code_index

4. 索引使用者直接搜索:
   $ search_code "用户订单支付逻辑"
   ✅ 找到5个匹配的代码块
```

---

## 九、数据存储设计

### 9.1 向量数据库集合结构

| 字段名 | 类型 | 说明 | 索引类型 | 示例值 |
|--------|------|------|---------|--------|
| id | VARCHAR(100) | 主键 (UUID) | 主键索引 | "uuid-abc123" |
| **project_key** | VARCHAR(500) | **项目唯一标识** ⭐ | 标量索引 | "com.myorg:order-service:1.0.0" |
| **project_name** | VARCHAR(200) | **项目名称** | 标量索引 | "order-service" |
| **git_remote_url** | VARCHAR(500) | **Git远程仓库地址** | 标量索引 | "git@github.com:myorg/order-service.git" |
| **indexed_by** | VARCHAR(100) | **索引构建者** | - | "zhangsan@company.com" |
| **shared** | BOOLEAN | **是否共享索引** | 标量索引 | true |
| file_path | VARCHAR(500) | 文件相对路径 | 标量索引 | "src/main/java/UserService.java" |
| chunk_type | VARCHAR(50) | 代码块类型 | 标量索引 | "METHOD" |
| name | VARCHAR(200) | 名称 | 标量索引 | "createUser" |
| fully_qualified_name | VARCHAR(500) | 完全限定名 | - | "com.example.UserService.createUser" |
| code | TEXT | 完整代码内容 | TypeSense全文索引 | "public User createUser(...) {...}" |
| semantic_text | TEXT | 增强语义文本 | TypeSense全文索引 | "Package: com.example..." |
| description | TEXT | Javadoc描述 | TypeSense全文索引 | "创建新用户账户..." |
| package_name | VARCHAR(300) | 包名 | 标量索引 | "com.example.service" |
| modifier | VARCHAR(50) | 访问修饰符 | 标量索引 | "public" |
| start_line | INT | 起始行号 | - | 45 |
| end_line | INT | 结束行号 | - | 78 |
| vector | FLOAT_VECTOR | 向量表示 | 向量索引 | [0.123, 0.456, ...] |

**新增字段说明** (⭐ 标记):

| 字段 | 作用 | 使用场景 |
|------|------|---------|
| **project_key** | 项目唯一标识,用于索引复用检测 | 团队共享,避免重复索引 |
| **project_name** | 人类可读的项目名称 | 显示给用户,项目列表 |
| **git_remote_url** | Git远程仓库完整地址 | 生成project_key,项目溯源 |
| **indexed_by** | 索引构建者标识 | 团队协作用户感知 |
| **shared** | 是否可被团队复用 | 区分本地索引和共享索引 |

### 9.2 全局元数据存储

**路径**: `~/.java-context-index/indices/{projectKey}/index-metadata.json`

**作用**:
1. 记录上次索引状态,支持增量更新
2. 追踪代码版本与索引的对应关系
3. 存储在全局目录,不污染Git

**示例**:

```json
{
  "version": 1,
  "projectKey": "com.myorg:order-service:1.0.0-snapshot",
  "projectName": "order-service",
  "projectPath": "/Users/dev/projects/order-service",
  "projectType": "maven",
  
  "lastIndexedCommit": "abc123def456",
  "baseBranch": "main",
  "indexedAt": "2026-04-04T10:30:00",
  "indexedBy": "zhangsan@company.com",
  
  "embeddingProvider": "doubao",
  "vectorDbType": "milvus",
  "vectorCollection": "java_code_index",
  
  "totalFiles": 1250,
  "totalChunks": 18750,
  
  "files": {
    "src/main/java/com/myorg/OrderService.java": {
      "commitHash": "abc123def456",
      "chunkCount": 25,
      "chunkIds": ["uuid-001", "uuid-002", "..."]
    }
  }
}
```

---

## 十、配置管理

### 10.1 配置文件结构

**文件**: `application.yml`

```yaml
# ==========================================
# 项目扫描配置
# ==========================================
project:
  scan-paths:
    - src/main/java
    - src/test/java
  exclude-patterns:
    - "**/target/**"
    - "**/generated/**"
    - "**/test/**"  # 可选:排除测试代码

# ==========================================
# 全局存储配置 (零Git污染)
# ==========================================
storage:
  base-dir: ~/.java-context-index
  indices-dir: ~/.java-context-index/indices
  cache-dir: ~/.java-context-index/cache
  log-dir: ~/.java-context-index/logs

# ==========================================
# Embedding 模型配置
# ==========================================
embedding:
  provider: doubao  # doubao | openai | ollama
  dimension: 4096
  batch-size: 50  # 批量处理大小
  max-retries: 3  # API调用重试次数
  
  # 豆包配置 (推荐国内使用)
  doubao:
    api-key: ${DOUBAO_API_KEY}
    model-name: text-embedding-large-3  # large-3 | medium-3 | small-3
  
  # OpenAI配置
  openai:
    api-key: ${OPENAI_API_KEY}
    model-name: text-embedding-3-small  # 3-small | 3-large
  
  # Ollama本地配置
  ollama:
    base-url: http://localhost:11434
    model-name: nomic-embed-text

# ==========================================
# 向量数据库配置
# ==========================================
vector-db:
  type: milvus  # milvus | chromadb | typesense
  
  # Milvus配置 (企业级,团队共享)
  milvus:
    host: 192.168.1.100
    port: 19530
    collection-name: java_code_index
    index-type: IVF_FLAT
    metric-type: COSINE
  
  # ChromaDB配置 (轻量级,个人使用)
  chromadb:
    base-url: http://localhost:8000
    collection-name: java_code_index
  
  # TypeSense配置 (混合搜索)
  typesense:
    host: localhost
    port: 8108
    api-key: ${TYPESENSE_API_KEY}
    collection-name: java_code_index

# ==========================================
# 搜索配置
# ==========================================
search:
  default-top-k: 10  # 默认返回结果数
  max-top-k: 50  # 最大返回结果数
  semantic-weight: 0.7  # 语义搜索权重 (混合搜索时)
  keyword-weight: 0.3  # 关键词权重 (混合搜索时)
  min-score: 0.5  # 最低相似度阈值

# ==========================================
# 语义文本构建配置
# ==========================================
semantic-text:
  logic-summary:
    enabled: true  # 是否启用逻辑摘要生成
    model: qwen-1.5b-chat
    provider: ollama  # ollama | vllm | openai-api
    ollama:
      base-url: http://localhost:11434
      model-name: qwen:1.5b-chat
    trigger-condition:
      min-comment-length: 20  # 注释长度<20字时生成摘要
      skip-if-has-javadoc: true  # 有完整Javadoc时跳过
    generation:
      max-summary-length: 50  # 摘要最大长度
      max-code-lines: 500  # 最大处理代码行数
      timeout: 5000  # 生成超时(ms)

# ==========================================
# 变更检测配置
# ==========================================
change-detection:
  strategy: git  # git (唯一方案)
  enable-method-level: false  # 是否启用方法级精准更新
  auto-sync: false  # 是否自动检测并更新索引

# ==========================================
# 团队共享配置
# ==========================================
sharing:
  mode: centralized  # centralized | distributed | local
  read-only: false  # 是否只读模式 (索引使用者)
  allow-concurrent-update: true  # 是否允许并发更新

# ==========================================
# MCP Server 配置
# ==========================================
mcp:
  enabled: true
  port: 8080
  cors-enabled: true

# ==========================================
# 日志配置
# ==========================================
logging:
  level:
    com.javacontext.index: INFO
  file:
    name: ~/.java-context-index/logs/java-context-index.log
```

### 10.2 环境变量配置

**推荐**: 敏感信息通过环境变量注入

```bash
# Embedding API Keys
export DOUBAO_API_KEY="your-doubao-api-key"
export OPENAI_API_KEY="your-openai-api-key"

# TypeSense API Key
export TYPESENSE_API_KEY="your-typesense-key"

# 向量数据库类型
export VECTOR_DB_TYPE=milvus
export VECTOR_DB_HOST=192.168.1.100
```

---

## 十一、MCP协议集成

### 11.1 实现的 Tools

| Tool 名称 | 功能描述 | 主要参数 | 返回结果 |
|-----------|---------|---------|---------|
| `code_index_build` | 构建/更新代码索引 | projectPath, projectKey, force | 索引统计信息 |
| `code_search` | 语义搜索代码 | query, topK, filters | 匹配的代码块列表 |
| `code_context` | 获取代码上下文 | chunkId, contextLines | 代码及上下文 |
| `index_status` | 查询索引状态 | - | 索引元数据、新鲜度 |
| `index_diff` | 查看代码变更差异 | fromCommit, toCommit | 变更文件列表 |
| `index_progress` | **查询索引建立进度** | taskId | **进度百分比、当前阶段、预估剩余时间** |

### 11.2 AI 助手调用示例

**示例1: 搜索代码**

```json
{
  "method": "tools/call",
  "params": {
    "name": "code_search",
    "arguments": {
      "query": "用户订单支付的业务逻辑和异常处理",
      "topK": 5,
      "filters": {
        "chunkType": "METHOD",
        "packageName": "com.example.order"
      }
    }
  }
}
```

**示例2: 检查索引状态**

```json
{
  "method": "tools/call",
  "params": {
    "name": "index_status",
    "arguments": {}
  }
}
```

**示例3: 查询索引建立进度**

```json
{
  "method": "tools/call",
  "params": {
    "name": "index_progress",
    "arguments": {
      "taskId": "task-20260404-abc123"
    }
  }
}
```

**返回结果**:

```json
{
  "content": [
    {
      "type": "text",
      "text": "📊 索引建立进度:\n\n✅ 任务ID: task-20260404-abc123\n📦 项目: com.myorg:order-service:1.0.0-SNAPSHOT\n🔄 状态: 进行中\n\n📈 进度详情:\n   - 总体进度: 65% (812/1250 文件)\n   - 当前阶段: Embedding向量化\n   - 已处理: 812个文件\n   - 待处理: 438个文件\n   - 已生成代码块: 12,180个\n   - 已插入向量: 11,950个\n\n⏱️ 时间统计:\n   - 已耗时: 5分12秒\n   - 预估剩余: 2分48秒\n   - 预计完成: 2026-04-04T10:38:00\n\n📊 阶段进度:\n   ✅ AST解析: 100% (1250/1250)\n   ✅ 语义文本构建: 100% (1250/1250)\n   🔄 Embedding向量化: 65% (812/1250)\n   ⏳ 向量数据库插入: 63% (788/1250)\n\n💡 提示: 索引建立中,请稍后再查询或等待完成通知"
    }
  ]
}
```

---

### 11.3 REST API 接口

#### 11.3.1 查询索引进度

**接口**: `GET /api/index/progress/{taskId}`

**请求示例**:

```bash
curl http://localhost:8080/api/index/progress/task-20260404-abc123
```

**响应示例**:

```json
{
  "success": true,
  "data": {
    "taskId": "task-20260404-abc123",
    "projectKey": "com.myorg:order-service:1.0.0-SNAPSHOT",
    "status": "IN_PROGRESS",
    "progress": {
      "totalFiles": 1250,
      "processedFiles": 812,
      "pendingFiles": 438,
      "percentage": 65.0,
      "generatedChunks": 12180,
      "insertedVectors": 11950
    },
    "currentStage": "EMBEDDING",
    "stages": {
      "AST_PARSING": {
        "status": "COMPLETED",
        "total": 1250,
        "processed": 1250,
        "percentage": 100.0
      },
      "SEMANTIC_TEXT_BUILDING": {
        "status": "COMPLETED",
        "total": 1250,
        "processed": 1250,
        "percentage": 100.0
      },
      "EMBEDDING": {
        "status": "IN_PROGRESS",
        "total": 1250,
        "processed": 812,
        "percentage": 65.0
      },
      "VECTOR_INSERT": {
        "status": "IN_PROGRESS",
        "total": 1250,
        "processed": 788,
        "percentage": 63.0
      }
    },
    "timeStats": {
      "startTime": "2026-04-04T10:30:00",
      "elapsedSeconds": 312,
      "estimatedRemainingSeconds": 168,
      "estimatedCompletionTime": "2026-04-04T10:38:00"
    },
    "message": "索引建立中,已完成65%"
  }
}
```

#### 11.3.2 查询当前活跃任务

**接口**: `GET /api/index/progress/active`

**请求示例**:

```bash
curl http://localhost:8080/api/index/progress/active
```

**响应示例**:

```json
{
  "success": true,
  "data": {
    "activeTasks": [
      {
        "taskId": "task-20260404-abc123",
        "projectKey": "com.myorg:order-service:1.0.0-SNAPSHOT",
        "status": "IN_PROGRESS",
        "percentage": 65.0,
        "currentStage": "EMBEDDING",
        "startedAt": "2026-04-04T10:30:00",
        "elapsedSeconds": 312
      }
    ],
    "totalActiveTasks": 1
  }
}
```

---

### 11.4 代码实现

#### 11.4.1 进度跟踪器 (IndexProgressTracker)

```java
package com.javacontext.index.embedding;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;

/**
 * 索引建立进度跟踪器
 * 
 * 核心功能:
 * 1. 创建索引任务并跟踪进度
 * 2. 更新各阶段进度
 * 3. 计算预估剩余时间
 * 4. 查询任务进度
 */
@Slf4j
@Service
public class IndexProgressTracker {
    
    private final Map<String, IndexTask> tasks = new ConcurrentHashMap<>();
    
    /**
     * 创建新的索引任务
     */
    public String createTask(String projectKey, int totalFiles) {
        String taskId = "task-" + LocalDateTime.now().toString().replaceAll("[:-]", "") 
                       + "-" + UUID.randomUUID().toString().substring(0, 6);
        
        IndexTask task = new IndexTask();
        task.setTaskId(taskId);
        task.setProjectKey(projectKey);
        task.setStatus(TaskStatus.IN_PROGRESS);
        task.setTotalFiles(totalFiles);
        task.setStartTime(LocalDateTime.now());
        
        // 初始化各阶段
        task.getStages().put("AST_PARSING", new StageProgress(totalFiles));
        task.getStages().put("SEMANTIC_TEXT_BUILDING", new StageProgress(totalFiles));
        task.getStages().put("EMBEDDING", new StageProgress(totalFiles));
        task.getStages().put("VECTOR_INSERT", new StageProgress(totalFiles));
        
        tasks.put(taskId, task);
        
        log.info("创建索引任务: taskId={}, projectKey={}, totalFiles={}", 
                taskId, projectKey, totalFiles);
        
        return taskId;
    }
    
    /**
     * 更新阶段进度
     */
    public void updateStageProgress(String taskId, String stageName, int processed) {
        IndexTask task = tasks.get(taskId);
        if (task == null) {
            log.warn("任务不存在: {}", taskId);
            return;
        }
        
        StageProgress stage = task.getStages().get(stageName);
        if (stage != null) {
            stage.setProcessed(processed);
            stage.setPercentage((double) processed / stage.getTotal() * 100);
            
            if (processed >= stage.getTotal()) {
                stage.setStatus(StageStatus.COMPLETED);
            } else {
                stage.setStatus(StageStatus.IN_PROGRESS);
                task.setCurrentStage(stageName);
            }
            
            // 更新总体进度
            task.setProcessedFiles(processed);
            task.setPercentage((double) processed / task.getTotalFiles() * 100);
            
            // 计算预估时间
            calculateEstimatedTime(task);
        }
    }
    
    /**
     * 更新代码块统计
     */
    public void updateChunkStats(String taskId, int generatedChunks, int insertedVectors) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setGeneratedChunks(generatedChunks);
            task.setInsertedVectors(insertedVectors);
        }
    }
    
    /**
     * 标记任务完成
     */
    public void completeTask(String taskId) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(TaskStatus.COMPLETED);
            task.setEndTime(LocalDateTime.now());
            
            Duration duration = Duration.between(task.getStartTime(), task.getEndTime());
            log.info("索引任务完成: taskId={}, duration={}秒", 
                    taskId, duration.getSeconds());
        }
    }
    
    /**
     * 标记任务失败
     */
    public void failTask(String taskId, String errorMessage) {
        IndexTask task = tasks.get(taskId);
        if (task != null) {
            task.setStatus(TaskStatus.FAILED);
            task.setErrorMessage(errorMessage);
            task.setEndTime(LocalDateTime.now());
            
            log.error("索引任务失败: taskId={}, error={}", taskId, errorMessage);
        }
    }
    
    /**
     * 查询任务进度
     */
    public IndexTask getProgress(String taskId) {
        return tasks.get(taskId);
    }
    
    /**
     * 查询所有活跃任务
     */
    public Map<String, IndexTask> getActiveTasks() {
        return tasks.entrySet().stream()
                .filter(e -> e.getValue().getStatus() == TaskStatus.IN_PROGRESS)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
    
    /**
     * 计算预估剩余时间
     */
    private void calculateEstimatedTime(IndexTask task) {
        Duration elapsed = Duration.between(task.getStartTime(), LocalDateTime.now());
        double percentage = task.getPercentage() / 100.0;
        
        if (percentage > 0.05) { // 至少完成5%才开始估算
            long totalEstimatedSeconds = (long) (elapsed.getSeconds() / percentage);
            long remainingSeconds = totalEstimatedSeconds - elapsed.getSeconds();
            
            task.setElapsedSeconds(elapsed.getSeconds());
            task.setEstimatedRemainingSeconds(remainingSeconds);
            task.setEstimatedCompletionTime(
                    LocalDateTime.now().plusSeconds(remainingSeconds));
        }
    }
    
    /**
     * 索引任务
     */
    @Data
    public static class IndexTask {
        private String taskId;
        private String projectKey;
        private TaskStatus status;
        private String currentStage;
        
        private int totalFiles;
        private int processedFiles;
        private int pendingFiles;
        private double percentage;
        
        private int generatedChunks;
        private int insertedVectors;
        
        private Map<String, StageProgress> stages = new ConcurrentHashMap<>();
        
        private LocalDateTime startTime;
        private LocalDateTime endTime;
        private long elapsedSeconds;
        private long estimatedRemainingSeconds;
        private LocalDateTime estimatedCompletionTime;
        
        private String errorMessage;
        
        public int getPendingFiles() {
            return totalFiles - processedFiles;
        }
    }
    
    /**
     * 阶段进度
     */
    @Data
    public static class StageProgress {
        private StageStatus status;
        private int total;
        private int processed;
        private double percentage;
        
        public StageProgress(int total) {
            this.total = total;
            this.status = StageStatus.PENDING;
        }
    }
    
    /**
     * 任务状态枚举
     */
    public enum TaskStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED, CANCELLED
    }
    
    /**
     * 阶段状态枚举
     */
    public enum StageStatus {
        PENDING, IN_PROGRESS, COMPLETED, FAILED
    }
}
```

---

### 11.5 使用场景

#### 场景1: 长时间全量索引

```bash
# 1. 启动全量索引
$ refresh_index --force

Agent响应:
⏳ 开始全量索引...
📊 任务ID: task-20260404-abc123
📦 项目: com.myorg:order-service:1.0.0-SNAPSHOT
📁 总文件: 1250个

💡 提示: 索引建立需要约8分钟,您可以通过以下方式查询进度:
   - index_progress task-20260404-abc123
   - GET /api/index/progress/task-20260404-abc123

# 2. 查询进度
$ index_progress task-20260404-abc123

Agent响应:
📊 索引建立进度:

✅ 任务ID: task-20260404-abc123
📦 项目: com.myorg:order-service:1.0.0-SNAPSHOT
🔄 状态: 进行中

📈 进度详情:
   - 总体进度: 65% (812/1250 文件)
   - 当前阶段: Embedding向量化
   - 已处理: 812个文件
   - 待处理: 438个文件
   - 已生成代码块: 12,180个
   - 已插入向量: 11,950个

⏱️ 时间统计:
   - 已耗时: 5分12秒
   - 预估剩余: 2分48秒
   - 预计完成: 2026-04-04T10:38:00

📊 阶段进度:
   ✅ AST解析: 100% (1250/1250)
   ✅ 语义文本构建: 100% (1250/1250)
   🔄 Embedding向量化: 65% (812/1250)
   ⏳ 向量数据库插入: 63% (788/1250)

# 3. 索引完成通知
✅ 索引建立完成!
   - 任务ID: task-20260404-abc123
   - 总耗时: 8分15秒
   - 处理文件: 1250个
   - 生成代码块: 18,750个
   - 插入向量: 18,750个
```

#### 场景2: 增量索引 (快速)

```bash
# 增量索引通常很快,进度查询可能直接返回完成
$ refresh_index

Agent响应:
⏳ 开始增量索引...
📊 任务ID: task-20260404-def456
📝 变更文件: 15个

✅ 增量索引完成!
   - 任务ID: task-20260404-def456
   - 总耗时: 4.2秒
   - 新增: 1个文件
   - 修改: 12个文件
   - 删除: 2个文件
```

#### 场景3: 查询活跃任务列表

```bash
# 查询当前所有正在进行的索引任务
$ GET /api/index/progress/active

响应:
{
  "activeTasks": [
    {
      "taskId": "task-20260404-abc123",
      "projectKey": "com.myorg:order-service:1.0.0-SNAPSHOT",
      "status": "IN_PROGRESS",
      "percentage": 65.0,
      "currentStage": "EMBEDDING",
      "elapsedSeconds": 312
    }
  ],
  "totalActiveTasks": 1
}
```

---

### 11.6 进度推送机制 (可选)

**WebSocket 实时推送**:

```javascript
// 前端连接WebSocket
const ws = new WebSocket('ws://localhost:8080/ws/index-progress');

ws.onmessage = (event) => {
  const progress = JSON.parse(event.data);
  console.log(`进度更新: ${progress.percentage}%`);
  updateProgressBar(progress);
};

// 服务端推送频率: 每2-5秒推送一次
```

**Server-Sent Events (SSE)**:

```java
@GetMapping("/api/index/progress/{taskId}/stream")
public SseEmitter streamProgress(@PathVariable String taskId) {
    SseEmitter emitter = new SseEmitter(0L); // 无超时
    
    // 定期推送进度更新
    scheduler.scheduleAtFixedRate(() -> {
        try {
            IndexTask task = progressTracker.getProgress(taskId);
            emitter.send(SseEmitter.event().data(task));
            
            if (task.getStatus() == TaskStatus.COMPLETED || 
                task.getStatus() == TaskStatus.FAILED) {
                emitter.complete();
            }
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }, 0, 2, TimeUnit.SECONDS);
    
    return emitter;
}
```

---

## 十二、性能优化策略

### 12.1 索引性能

| 优化点 | 策略 | 预期效果 | 适用场景 |
|--------|------|---------|---------|
| **批量Embedding** | 50个代码块一批调用API | 减少网络开销60% | 所有场景 |
| **异步处理** | 线程池并行解析文件 | 索引速度提升3-5倍 | 全量索引 |
| **向量缓存** | 相同内容复用向量 | 避免重复API调用 | 增量更新 |
| **增量索引** | Git检测,仅处理变更 | 10分钟→5秒 ⚡ | 日常更新 |
| **方法级更新** | 只更新变更的方法 | 大型文件提速80% | 大文件修改 |
| **连接池** | 向量数据库长连接复用 | 减少连接开销 | 所有场景 |

### 12.2 搜索性能

| 优化点 | 策略 | 预期效果 |
|--------|------|---------|
| **向量索引** | IVF_FLAT + 1024个聚类 | 搜索速度提升10倍 |
| **预过滤** | 先标量过滤,再向量搜索 | 减少搜索空间 |
| **结果缓存** | 相同查询缓存结果 | 重复查询毫秒级响应 |
| **分页加载** | 按需加载Top-K结果 | 减少网络传输 |

### 12.3 性能基准测试

**测试环境**: 1000个Java文件, 15000个代码块

| 操作 | Milvus | ChromaDB | TypeSense |
|------|--------|----------|-----------|
| **首次全量索引** | 8分钟 | 10分钟 | 9分钟 |
| **增量更新 (10文件)** | 3秒 | 5秒 | 4秒 |
| **向量搜索 (Top-10)** | 50ms | 80ms | 60ms |
| **混合搜索 (Top-10)** | 120ms | N/A | 70ms |
| **内存占用** | 2GB | 500MB | 1GB |

---

## 十三、部署方案

### 13.1 开发环境 (个人)

**方案**: ChromaDB + 豆包 Embedding

```bash
# 1. 启动 ChromaDB (零配置)
docker run -d -p 8000:8000 chromadb/chroma

# 2. 配置环境变量
export DOUBAO_API_KEY="your-api-key"

# 3. 配置 application.yml
# vector-db.type: chromadb
# embedding.provider: doubao

# 4. 启动服务
java -jar java-context-index.jar

# 5. 首次索引
curl -X POST http://localhost:8080/api/index/build \
  -d '{"projectPath": "/path/to/your/java-project"}'
```

**成本**:
- ChromaDB: 免费
- 豆包 Embedding: ~¥50/月 (1000文件首次索引)
- 总成本: **¥50/月**

### 13.2 团队环境 (推荐)

**方案**: Milvus 集群 + 豆包 Embedding

```bash
# 1. 部署 Milvus (Docker Compose)
git clone https://github.com/milvus-io/milvus/releases/download/v2.5.0/milvus-standalone-docker-compose.yml
docker-compose up -d

# 2. 配置环境变量
export DOUBAO_API_KEY="your-api-key"
export VECTOR_DB_TYPE=milvus
export VECTOR_DB_HOST=localhost

# 3. 开发者A执行首次索引
java -jar java-context-index.jar --action=index --project=/path/to/project

# 4. 共享数据库连接信息
# 创建 shared-config.yml:
# vector-db:
#   type: milvus
#   milvus:
#     host: 192.168.1.100
#     port: 19530

# 5. 团队其他成员使用共享配置
java -jar java-context-index.jar --config=shared-config.yml
# 直接搜索,无需重新索引!
```

**成本** (10人团队):
- Milvus: 免费 (自建) / ¥200/月 (云服务)
- 豆包 Embedding: ¥50 (仅首次索引,团队共享)
- 增量更新: ¥5/月 (日常变更)
- 总成本: **¥55-250/月 (团队共享)**
- 人均成本: **¥5.5-25/月** ✅

### 13.3 生产环境

**方案**: Milvus 集群 + TypeSense + 豆包 Embedding

**高可用配置**:
- Milvus 3节点集群 (自动故障转移)
- 负载均衡 (Nginx/HAProxy)
- 数据备份 (定时快照)
- 监控告警 (Prometheus + Grafana)

---

## 十四、安全与权限

### 14.1 数据安全

| 数据类型 | 存储位置 | 加密 | 访问控制 |
|---------|---------|------|---------|
| Embedding API Key | 环境变量 | ✅ | 仅服务端 |
| 向量数据 | 向量数据库 | 可选 | 数据库权限 |
| 代码内容 | 向量数据库 | 可选 | 数据库权限 |
| 元数据 | 全局文件 | ❌ | 文件系统权限 |

### 14.2 访问控制

**向量数据库层面**:
- Milvus: RBAC (基于角色的访问控制)
- TypeSense: API Key 认证
- ChromaDB: 网络隔离 (推荐内网访问)

**应用层面**:
- API Key 认证
- IP 白名单
- 读写权限分离 (只读用户/索引管理员)

---

## 十五、故障排查

### 15.1 常见问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| **索引构建慢** | Embedding API限流 | 降低batch-size,增加重试 |
| **搜索结果为空** | 未执行索引/向量库连接失败 | 检查索引状态,确认数据库连接 |
| **增量更新失败** | Git命令执行失败 | 检查Git仓库 |
| **内存溢出** | 批量处理过大 | 减小batch-size至20-30 |
| **团队索引不同步** | 多人并发更新 | 启用乐观锁,基于commit检测 |

### 15.2 日志分析

**关键日志路径**: `~/.java-context-index/logs/java-context-index.log`

**日志级别**:
- `ERROR`: 系统错误,需立即处理
- `WARN`: 警告信息,建议关注
- `INFO`: 正常操作流程
- `DEBUG`: 详细调试信息 (开发时启用)

---

## 十六、最佳实践

### 16.1 索引构建

1. **首次索引选择低峰期**: 全量索引可能耗时5-10分钟
2. **监控索引进度**: 使用 `index_progress` 查询实时进度
3. **排除不必要文件**: 配置 `exclude-patterns` 减少索引量
4. **选择合适的Embedding模型**: 中文代码混合用豆包,纯英文用OpenAI
5. **团队共享索引**: 一次构建,多人使用,降低成本

### 16.2 搜索优化

1. **使用自然语言查询**: "用户登录验证逻辑" 优于 "login password"
2. **添加过滤条件**: 缩小搜索范围,提高准确性
3. **调整Top-K**: 默认10个结果,需要更多上下文可增加至20-30
4. **利用混合搜索**: TypeSense 提供最佳混合搜索体验

### 16.3 团队协作

1. **元数据全局存储**: `~/.java-context-index/` 不加入Git
2. **建立索引更新规范**: 指定专人负责或Git Hook自动更新
3. **定期检查索引新鲜度**: 确保索引与代码同步
4. **文档化数据库连接**: 团队共享配置文档

### 16.4 零Git污染

1. ✅ **确认全局存储**: 所有生成文件在 `~/.java-context-index/`
2. ✅ **定期检查Git状态**: `git status` 应该干净
3. ❌ **不要**在项目目录内生成任何文件
4. ❌ **不要**将 `.java-context-index/` 加入版本控制

### 16.5 进度监控

1. **全量索引时监控进度**: 使用 `index_progress {taskId}` 查询
2. **关注预估时间**: 根据预估剩余时间安排工作
3. **查看阶段进度**: 了解当前处于哪个处理阶段
4. **异常及时处理**: 如任务失败,查看错误信息并排查
5. **避免重复提交**: 查询活跃任务,避免重复建立索引

---

## 十七、总结

### 17.1 核心优势

| 优势 | 说明 | 价值 |
|------|------|------|
| **语义搜索** | 自然语言搜索代码 | 提升开发效率50%+ |
| **团队共享** | 一次索引,多人使用 | 降低成本90% |
| **增量更新** | Git驱动,秒级同步 | 无感知更新 |
| **零Git污染** | 全局存储,不污染项目 | 版本控制干净 |
| **精准识别** | Maven坐标识别项目 | 不依赖Git远程仓库 |
| **多引擎支持** | 灵活选择,按需切换 | 适应不同场景 |
| **智能分块** | AST级别代码理解 | 精准语义提取 |

### 17.2 技术决策清单

| 决策点 | 推荐选择 | 备选方案 |
|--------|---------|---------|
| Embedding模型 | 豆包 text-embedding-large-3 | OpenAI text-embedding-3-small |
| 向量数据库 (个人) | ChromaDB | Ollama本地 |
| 向量数据库 (团队) | Milvus | TypeSense |
| 变更检测 | Git Diff | 文件指纹 |
| 索引粒度 | 方法级 | 文件级 |
| 搜索模式 | 混合搜索 | 纯向量搜索 |
| 存储位置 | 全局目录 ~/.java-context-index | 项目目录内 |
| 项目识别 | Maven坐标 (pom.xml) | Git远程仓库 |

### 17.3 典型工作流

```
开发者A (首次索引):
1. cd /path/to/project
2. refresh_index
   → 自动从pom.xml提取Maven坐标
   → 查询向量数据库
   → 全量索引
   → 元数据保存到~/.java-context-index/
   → 向量存储到Milvus

开发者B (克隆后使用):
1. git clone ... && cd project
2. search_code "xxx"
   → 自动从pom.xml提取Maven坐标 (与A相同)
   → 查询向量数据库 (发现A的索引)
   → 直接使用,零成本
   → 元数据保存到~/.java-context-index/ (本地)

✅ 整个过程无任何文件提交到Git!
✅ 团队共享索引,零重复成本!
```

---

**文档版本**: v2.0 (合并版)  
**更新日期**: 2026-04-04  
**文档来源**: 
- 技术方案文档.md (v1.0)
- 零Git污染与项目识别优化.md (v1.0)
- 项目识别与索引复用机制.md (v1.0)

**维护团队**: Java Context Index Team
