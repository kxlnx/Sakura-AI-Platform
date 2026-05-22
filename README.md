# Sakura AI Platform

基于 Spring AI + ReAct 模式的 AI 历史知识库与智能对话平台。以中文维基百科（zhwiki）为核心知识来源，结合短期/长期双层记忆系统，提供精准的历史知识检索与自主联网搜索能力。支持双机负载部署与内网穿透。

## 功能模块

### 1. AI 超级智能体 (YuManus)

基于 ReAct 模式（Reasoning + Acting）的自主规划智能体：

```
用户提问 → think(推理) → act(执行工具) → observe(观察结果) → 循环 → doTerminate(结束)
```

- **知识库检索优先**：先在 Milvus 向量库中检索 Wiki 文档，有结果直接回答
- **联网搜索兜底**：知识库未命中时自动调用 SerpAPI 搜索互联网，必要时爬取完整页面
- **图片搜索**：调用 Pexels API 搜索创作共用图片，前端 Markdown 渲染直接显示
- **增强器管道**：查询重写(-200) → 长期记忆(-100) → 短期记忆(0) → RAG 检索(100)，有序执行
- **最多 8 （可配置）步自主推理**，每步可选工具：searchWeb / searchImage / scrapeWebPage / generatePDF / doTerminate

### 2. AI 历史知识库

- 数据源：中文维基百科最新版 `zhwiki-latest-pages-articles.xml.bz2`
- 通过 [Wikiextractor](https://github.com/attardi/wikiextractor) 将 XML 转为 Markdown
- Milvus 向量库存储，HNSW 索引 + 稠密向量搜索
- 混合检索：稠密向量（DashScope Embedding）+ BM25 关键词，alpha=0.7 加权融合
- 查询重写：结合对话历史补全省略与指代，提升召回准确率

### 3. 双层记忆系统

#### 短期记忆（MySQLChatMemory）

负责维护当前对话的上下文窗口，让 AI 记住"刚才说了什么"。

```
写入: add(conversationId, messages)
  ├─ ① MySQL 持久化（同步）→ 写入 chat_message 表
  ├─ ② 检查是否含 AI 回复 → 有则异步触发摘要压缩
  └─ ③ Redis 增量更新（取出旧列表 → 追加新消息 → 截断到 safeLimit → 写回）

读取: get(conversationId)
  ├─ ① ThreadLocal 请求缓存（同请求内去重，避免同一 chatId 被多个 Advisor 重复查）
  ├─ ② Redis 缓存（热层，30min TTL，JSON 序列化）
  └─ ③ MySQL 兜底（缓存自愈：查到 → 回写 Redis）

摘要压缩: checkAndSummarize()
  └─ 消息数超过 summary-threshold → 取最老 summary-batch-size 条 → LLM 生成摘要
     → 删除原始消息 → 插入 [历史记忆摘要]（role=system）
```

**关键设计**：
- 三层缓存避免了同一 HTTP 请求内对同一 `chatId` 的重复 Redis 查询（`QueryRewritingAdvisor`、`MessageChatMemoryAdvisor` 等都会调用 `get()`）
- 滑动窗口（`safe-limit`）与摘要触发阈值（`summary-threshold`）**解耦**：前端能展示 100 条消息，但只有超过 20 条时才触发 LLM 摘要压缩
- `LoginFilter` 在请求结束时调用 `clearRequestCache()` 清空 ThreadLocal，防止内存泄漏

#### 长期记忆（LongTermMemoryAdvisor → LongTermMemoryReader/Writer）

负责跨对话持久化用户画像，让 AI 记住"这个人是谁、喜欢什么、说过什么"。

```
写入: LongTermMemoryWriter
  ├─ ① 显式记忆：检测 "我叫"、"我是"、"我喜欢" 等关键词 → LLM 提取事实三元组
  ├─ ② 隐式记忆：对话达到一定轮次 → LLM 主动推断用户兴趣偏好
  └─ ③ 向量化 → Milvus 持久化（附带 user_id + category 元数据）

读取: LongTermMemoryReader
  ├─ ① 语义搜索：用户当前问题向量化 → Milvus ANN 搜索（HNSW 索引）
  ├─ ② 关键词匹配：KeywordExtractor 提取中文关键词 → Milvus 精确匹配
  └─ ③ 双路融合 → 去重 → 排序 → 注入 System Prompt
```

**关键设计**：
- `userId` 过滤保证不同用户的记忆完全隔离
- 语义 + 关键词双路召回，降低漏检率
- 记忆片段以 `SystemMessage` 形式注入 Prompt，LLM 无感使用历史信息
- 消息截断（取前 100 字）避免长文本导致 Embedding 语义稀释

### 4. 认证与会话（旁路缓存 + 穿透保护）

```
登录: createSession(userId)
  ├─ ① MySQL 持久化 → INSERT INTO sys_session (token, user_id, expire_time)
  └─ ② Redis 缓存 → SET session:<token> <userId> EX 30m

鉴权: getUserId(token)
  ├─ ① Redis 查缓存 → 命中返回 + 续期 30m
  │     └─ 未命中 ↓
  ├─ ② MySQL 查 sys_session → 命中 → 重建 Redis 缓存 + 返回
  │     └─ 未命中/已过期 ↓
  └─ ③ 返回 null → UserContext.setUserId("anonymous")
```

**穿透保护**：Redis 宕机时，`getUserId()` 自动降级到 MySQL 查询，查到即重建 Redis 缓存（缓存自愈）。不会因为 Redis 不可用导致所有用户掉线变 `anonymous`。

**双写一致性**：登录时先写 MySQL（持久化保证），再写 Redis（设置 TTL）。即使 Redis 写入失败，下次鉴权也会从 MySQL 恢复。

- LoginFilter 统一拦截所有请求，从 Header 或 Query 参数（兼容 SSE）中提取 Token
- 注册 / 登录系统，MySQL `sys_user` 表存储用户信息

### 5. 部署能力

- **Nginx 前后端分离**：静态文件由 Nginx 直接从磁盘提供（毫秒级），API 请求代理到后端集群
- **双机部署**：主节点运行 Redis/MySQL/Milvus/Nginx，从节点仅需 JDK 21 + jar 包，`config/application.yml` 全部指向主节点
- **SakuraFrp 内网穿透**：隧道 `local_port` 配置为 Nginx 的 80 端口，外网通过 `frp-gap.com:53712` 访问
- **Spring Boot 配置收敛**：所有业务参数集中在 `application.yml` 的 `sakura.*` 节点，调参无需改代码，重启生效

## 技术栈

```
后端    Java 21 / Spring Boot 3.4 / Spring AI 1.0 / MyBatis-Plus 3.5
AI      DashScope（千问 Qwen-Plus）/ DashScope Embedding
向量库   Milvus + 混合检索（HNSW 稠密 + BM25 稀疏）
数据库   MySQL 8 + Redis（Lettuce 连接池）
分布式锁  Redisson
前端    Vue 3 / Vite / Vue Router / Marked / Axios
部署    Nginx / SakuraFrp / Docker（Milvus）
工具    Hutool / Jsoup / iText PDF / SerpAPI / Pexels API
```

## 架构

```
                         浏览器
                           │
                     SakuraFrp (外网)
                           │
                      Nginx :80
                       /        \
                      /          \
         前端静态文件 (html/)    /ai/* & /auth/*
                               │
                     upstream 轮询
                    ┌─────────┴─────────┐
                    │                   │
           Spring Boot :8123    Spring Boot :8123
           (你的机器)            (对方机器)
                    │                   │
                    └──────┬────────────┘
                           │
               Redis :6379 / MySQL :3306 / Milvus :19530
                     (全部部署在你的机器上)
```

## 快速开始

### 环境

- JDK 21 / Maven 3.6 / Node.js 18
- MySQL 8.0 / Redis 7 / Milvus (Docker)

### 第一步：基础设施

```bash
# MySQL
CREATE DATABASE sakura_agent DEFAULT CHARACTER SET utf8mb4;

# Milvus
cd E:\tools\milvus && docker compose up -d

# Redis（Windows）
# 安装后编辑 redis.windows.conf：bind 0.0.0.0，重启服务
```

### 第二步：Wiki 数据准备

1. 下载中文维基百科最新版：
   ```
   https://dumps.wikimedia.org/zhwiki/latest/zhwiki-latest-pages-articles.xml.bz2
   ```

2. 安装 Wikiextractor：
   ```bash
   git clone https://github.com/attardi/wikiextractor.git
   cd wikiextractor
   ```

3. 转换为 Markdown（`--json` 输出到指定目录）：
   ```bash
   python -m wikiextractor.WikiExtractor zhwiki-latest-pages-articles.xml.bz2 --json -o E:/tools/milvus/wikimd
   ```

4. 配置 `application.yml`：
   ```yaml
   sakura:
     wiki:
       dir: E:/tools/milvus/wikimd
       chunk-size: 1200        # 切片大小（字数），越小检索越精细但切片越多
       batch-size: 20          # 入库批次大小（DashScope Embedding API 上限 25）
       batch-delay-ms: 100     # 批次间隔，避免触发 API 限流
   ```

5. 首次启动时自动入库。`vectorstore.clear-on-startup: false` 确保已有数据不丢失。

> **切片策略**：默认按 `==` 一级标题切大段（最大 1200 字），大段超长时再按 `===` 二级标题或自然段细分。自动裁切文末参考资料/外部链接等冗余段落。

#### 可选：粗分片 + 语义增强（数据量较小时推荐）

Wiki 文档量较大时（数十万篇），直接按标题粗切入库效率最高。如果你的知识库数据量较小（比如几千篇），可以在 `archived/rag/` 中找到两种增强策略，切换后检索质量更高：

| 策略 | 类 | 说明 |
|------|-----|------|
| **语义分块** | `SemanticTextSplitter` | 用 Embedding 计算相邻句子的语义相似度，在相似度骤降处切分。代价是入库速度慢（每段都要调 Embedding API）|
| **关键词提取** | `MyKeywordEnricher` | 入库时用 LLM 为每段文档自动生成 5 个关键词作为元数据标签，检索时用关键词兜底 |
| **查询重写器** | `QueryRewriter` | 基于 Spring AI 内置 `RewriteQueryTransformer`，将 "这个"、"它" 等指代词展开为完整查询 |

### 第三步：配置密钥

```bash
cp src/main/resources/application-template.yml src/main/resources/application.yml
```

需要填写的密钥：

| 配置项 | 说明 | 获取地址 |
|--------|------|---------|
| `spring.ai.dashscope.api-key` | 阿里云 DashScope（LLM + Embedding） | https://dashscope.aliyun.com |
| `search-api.api-key` | SerpAPI 搜索（百炼引擎） | https://serpapi.com |
| `pexels.api-key` | Pexels 图片搜索 | https://www.pexels.com/api/ |

### 第四步：启动

```bash
# 后端
mvn clean package -DskipTests
java -jar target/sakura-agent-0.0.1-SNAPSHOT.jar

# 前端（开发模式，端口 3000）
cd yu-ai-agent-frontend && npm install && npm run dev
```

访问 `http://localhost:8123`

## 配置参考

所有业务参数集中在 `application.yml` 的 `sakura.*` 节点下，改参数不需要翻 Java 代码，重启生效：

```yaml
sakura:
  memory:
    safe-limit: 100              # 短期记忆窗口大小
    summary-threshold: 20        # 摘要压缩触发阈值
    truncate-length: 100         # 长期记忆提取时的用户消息截断
    recall-top-k: 3              # 长期记忆召回条数
  rag:
    similarity-threshold: 0.5    # 向量相似度最低阈值
    top-k: 3                     # 混合检索返回条数
    alpha: 0.7                   # 稠密向量权重（0=纯BM25, 1=纯向量）
    bm25-k1: 1.5                 # BM25 词频饱和参数
    bm25-b: 0.75                 # BM25 文档长度归一化
    avg-doc-length: 100          # BM25 平均文档长度
  lock:
    wait-seconds: 10             # 分布式锁等待时间
    lease-seconds: 60            # 锁租约时间
  tool:
    result-truncate-length: 600  # 工具返回截断长度
  session:
    ttl-minutes: 30              # Token 有效期
  summary:
    batch-size: 10               # 摘要压缩批处理大小
```

## 数据库设计

项目使用 MySQL 存储业务数据，Milvus 存储向量索引。建表 SQL 在 `src/main/resources/db/init.sql`。

### MySQL 表结构

#### sys_user — 用户表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 自增主键 |
| username | VARCHAR(64) UNIQUE | 用户名 |
| password | VARCHAR(255) | BCrypt 加密密码 |
| nickname | VARCHAR(64) | 昵称 |
| create_time | DATETIME | 创建时间 |
| updated_at | DATETIME | 更新时间 |
| is_delete | TINYINT | 逻辑删除 (0=正常, 1=删除) |

#### sys_session — 会话表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 自增主键 |
| token | VARCHAR(64) | 会话令牌 (索引) |
| user_id | VARCHAR(64) | 用户 ID (索引) |
| create_time | DATETIME | 创建时间 |
| expire_time | DATETIME | 过期时间 |

#### chat_message — 对话消息表

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGINT (PK) | 自增主键 |
| conversation_id | VARCHAR(128) | 会话 ID，格式 `userId:chatId` (索引) |
| user_id | VARCHAR(64) | 用户 ID，从 conversation_id 提取 (索引) |
| role | VARCHAR(32) | 角色：user / assistant / system |
| content | TEXT | 消息内容 |
| tokens | INT | token 消耗统计 (prompt + completion 求和) |
| create_time | DATETIME | 创建时间 (索引) |
| update_time | DATETIME | 更新时间 |
| is_delete | TINYINT | 逻辑删除 (0=正常, 1=删除) |

**消息隔离机制**：`conversation_id` 采用 `userId:chatId` 格式，`user_id` 在写入时从 `conversation_id` 中自动提取。查询对话列表时按 `user_id` 过滤，保证用户只能看到自己的消息。

**摘要压缩**：当某对话的消息数超过 `summary-threshold` 时，异步触发：取最老 N 条 → LLM 生成摘要 → 删除原始消息 → 插入 `role=system` 的摘要实体（内容前缀 `[历史记忆摘要]`）。

### Milvus 向量集合

| 集合名 | 用途 | 维度 | 索引 |
|--------|------|------|------|
| `love_master_knowledge` | Wiki 百科文档切片 | 1536 | HNSW, COSINE |
| `long_term_memory` | 长期记忆用户画像 | 1536 | HNSW, COSINE |

**元数据字段**：`title`（文章标题）、`source`（wiki）、`filename`（源文件名）、`chunk_index`（切片序号）、`user_id`（长期记忆专用）。

**文档切片策略**：支持两种模式，根据数据量选择：

| 策略 | 类 | 原理 | 适用场景 |
|------|-----|------|---------|
| **粗分片**（默认） | `WikiDocumentLoader` | 按 `==` 一级标题切大段（最大 1200 字），超长段再按 `===` 二级标题或自然段细分 | 数十万篇文章，入库速度快 |
| **语义分片**（archived） | `SemanticTextSplitter` | 用 Embedding 计算相邻句子语义相似度，在相似度骤降处切分 | 数据量小，追求检索精度 |

**元数据增强**（archived）：`MyKeywordEnricher` 可在入库时调用 LLM 为每段文档自动提取 5 个关键词写入元数据，检索时用关键词兜底降低漏检率。数据量较小时推荐搭配语义分片一起开启，代价是入库速度显著变慢（每段都需调 LLM + Embedding）。

## 项目结构

```
src/main/java/com/yupi/yuaiagent/
├── agent/               ReAct Agent 核心
│   ├── BaseAgent        SSE 流式执行框架（合并 runStream/runStreamWithMemory）
│   ├── ReActAgent       think/act/step 循环
│   ├── ToolCallAgent    工具调用 + 参数校验 + 结果截断
│   └── YuManus          超级智能体（Prompt + Advisor 管道）
├── app/
│   └── LoveApp          历史知识库问答（RAG + 对话锁）
├── chatmemory/
│   └── MySQLChatMemory  短期记忆（ThreadLocal → Redis → MySQL 三层缓存）
├── memory/
│   ├── LongTermMemoryAdvisor   长期记忆增强器
│   ├── LongTermMemoryReader    长期记忆读（Milvus 语义 + 关键词双路）
│   └── LongTermMemoryWriter    长期记忆写（LLM 用户画像抽取）
├── rag/
│   ├── HybridSearchDocumentRetriever  混合检索器（稠密 + BM25）
│   ├── QueryRewritingAdvisor          查询重写增强器
│   ├── QueryRewritingService          查询重写服务（LLM 补全省略指代）
│   ├── KeywordExtractor              中文关键词提取工具
│   ├── WikiDocumentLoader            Wikipedia Markdown 文档加载器
│   └── MilvusDataLoader              分批入库流水线
├── tools/               工具集（8 个）
│   ├── WebSearchTool        百度搜索（SerpAPI）
│   ├── WebScrapingTool      网页抓取（Jsoup）
│   ├── ImageSearchTool      图片搜索（Pexels）
│   ├── FileOperationTool    文件读写
│   ├── ResourceDownloadTool 资源下载
│   ├── TerminalOperationTool 终端命令
│   ├── PDFGenerationTool    PDF 生成（iText）
│   └── TerminateTool        终止对话
├── auth/                认证
│   ├── SessionManager    Redis + MySQL 旁路缓存
│   └── LoginFilter       请求拦截注入 UserContext
├── advisor/
│   └── BannedWordAdvisor  违禁词拦截（流式/非流式双路径一致）
└── controller/
    └── AiController       所有 REST API 入口
```

## 优化清单（相较于原始项目的新增/重构）

### 记忆系统

- 短期记忆三层缓存：ThreadLocal（请求级去重）→ Redis（30min TTL）→ MySQL（冷存储兜底 + 缓存自愈）
- 长期记忆用户隔离：Milvus 向量搜索 + `user_id` 过滤
- 查询重写排序修复：order=-200 确保在 RAG Advisor 之前执行，不再误读空上下文兜底文本
- 摘要压缩阈值与缓存窗口解耦：`safe-limit`（展示上限）≠ `summary-threshold`（压缩触发）

### 工具链

- 新增 ImageSearchTool（Pexels API），Markdown 返回格式，前端 marked 渲染
- 工具描述全面优化：每个工具增加 "Use this when / Do NOT use for" 约束
- TerminalOperationTool 增加 Process destroy（防止僵尸进程）
- ToolCallAgent 增加工具返回结果截断保护（600 字符，防止 HTML 刷屏）

### 前端

- ChatRoom 支持 Markdown 渲染（marked v18），API 迁移（setOptions 已废弃）
- SuperAgent + LoveMaster 均传递 userId（localStorage），不再依赖 Redis UserContext
- 历史消息正序展示（DESC 查询后 reverse）
- SSE 流结束前发送 `[DONE]` 标记，前端接收并清空缓冲

### 认证与部署

- SessionManager 改为 Redis + MySQL 旁路缓存（Redis 宕机自动从 MySQL 恢复）
- 新增 sys_session 表，token→userId 双写双读
- RedissonConfig 硬编码 → `@Value` 读 YAML（跨机器部署不再卡死）
- 所有 API 端点增加 userId 参数兜底，降低对 ThreadLocal 的依赖
- 新增 KeywordExtractor 工具类（消除三处独立 extractKeywords 重复实现）
- ChatMessage.fromMessage() token 计数 bug 修复（prompt + completion 求和）
- BannedWordAdvisor 非流式路径拦截失效修复（注释掉的 throw 已恢复）

## 致谢

基于 [程序员鱼皮](https://www.codefather.cn) 的 AI 智能体教学项目深度重构。本项目新增了 Wiki 知识库接入、双层记忆系统、图片搜索、旁路认证、三层缓存架构、全链路配置中心化、双机部署等特性。

## License

MIT
