# ai-augmented-employer-toolkit

用 AI 分析"企业引入 AI 后对现有岗位的影响"，目标是帮企业找到一条 **不裁员、转岗培训、人机协作** 的落地路径。

## 为什么做这个

企业引入 AI 的默认叙事是"降本增效 = 减少人员"。但这个等式不是必然的。

格力在引入自动化时，找出老职工原有技能和新岗位的"最大公约数"——让员工觉得"我不是从头再来，是在老经验上添新本事"。结果是转岗后技能等级提升，收入平均增长 8%，数年来转岗成功率 100%，没有一名老职工因为技术替代掉队。

这个项目将这个理念产品化：**用 AI 评估岗位替代风险，同时给出可操作的转岗路径建议。** 让"增强而非替代"成为可落地的方案。

## 功能特性

### v0.1 基础分析

- **岗位自动化分析** — 输入岗位描述，AI 评估自动化替代率（0%~100%），识别哪些任务易被替代、哪些需要人类判断
- **转岗路径建议** — 基于"技能最大公约数"理念，找出可迁移的核心技能，推荐目标岗位，指出需要补充的能力
- **流式输出（SSE）** — 实时打字机效果展示 AI 分析过程，告别白屏等待
- **Structured Output** — 使用 Spring AI `BeanOutputConverter` 确保 LLM 返回结构化数据，附带手动解析兜底
- **会话记忆** — 自定义内存记忆存储，支持多轮对话上下文

### v0.2 RAG 知识库 + Tool Calling

- **RAG 知识库检索** — 应用启动时自动加载 `knowledge/` 目录下的 Markdown 文档，经 Tika 解析 → TokenTextSplitter 分块 → DashScope text-embedding-v3 嵌入 → SimpleVectorStore 存储，通过 `QuestionAnswerAdvisor` 自动注入对话上下文
- **Tool Calling 薪资查询** — 使用 `@Tool` 注解实现模拟薪资查询工具，覆盖 8 个常见转岗方向 × 3 线城市薪资数据，AI 在用户问薪资时自动调用
- **知识库内容** — 内置 4 份知识文档：转岗成功案例、行业自动化报告、薪资基准表、技能迁移指南

### v0.2 UI 重设计

- **紧凑案例横幅** — 格力案例从占满首屏的大横幅改为可折叠的紧凑条幅，点击展开详情
- **仪表盘 + 摘要横向并排** — 环形图和风险标签在左，摘要文字在右，首屏即见核心结论
- **对话气泡式追问** — 追问改为聊天气泡，每轮问答都保留完整上下文，不再是只显示最新一轮
- **响应式布局** — 600px 以下自动纵向堆叠，移动端友好

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.8+
- DashScope API Key（[申请地址](https://dashscope.console.aliyun.com/)）

### 配置

```bash
export AI_DASHSCOPE_API_KEY=your_key_here
```

可选环境变量：

| 变量名 | 默认值 | 说明 |
|--------|--------|------|
| `SERVER_PORT` | `8089` | 服务端口 |
| `ANALYZE_MODEL` | `qwen-plus` | 使用的通义千问模型 |

### 启动

```bash
./mvnw spring-boot:run
```

启动日志中会看到知识库加载信息：

```
开始加载知识库文档...
已入库 N 块（来源: classpath:knowledge/*.md）
知识库加载完成，Markdown 文档 N 块，PDF 文档 0 块
```

服务默认监听 `http://localhost:8089`，浏览器打开即可使用前端页面。

## API 接口

### 同步分析

```bash
curl -X POST http://localhost:8089/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"jobDescription": "负责每日订单录入、发票开具和客户电话回访"}'
```

返回示例：

```json
{
  "automationRatio": 0.72,
  "summary": "该岗位中订单录入和发票开具属于高度重复性任务，AI 可显著提效；客户电话回访涉及情感沟通和异常处理，仍需人工参与。（来源: industry-automation-report.md）",
  "transitionPath": {
    "transferableSkills": ["客户关系维护", "业务流程理解", "异常问题处理"],
    "suggestedRole": "客户关系管理专员",
    "skillGap": "需要学习 CRM 系统操作和数据分析基础能力",
    "encouragement": "你多年的客户服务经验是 AI 无法替代的宝贵资产，转岗是在此之上叠加新能力。"
  }
}
```

### 流式分析（SSE）

```bash
curl -N -X POST "http://localhost:8089/api/analyze/stream?sessionId=test-001" \
  -H "Content-Type: application/json" \
  -d '{"jobDescription": "负责每日订单录入、发票开具和客户电话回访"}'
```

返回 SSE 流式文本，前端通过 `fetch` + `ReadableStream` 消费，实现打字机效果。流结束后解析完整 JSON 渲染结构化卡片。

`sessionId` 参数可选，用于关联多轮对话。同一 sessionId 的后续请求会携带历史上下文。

## 数据流

```
用户输入岗位描述/追问
    │
    ▼
ChatClient.prompt()
    ├── .defaultAdvisors(QuestionAnswerAdvisor)  ← RAG：自动检索知识库相关文档
    ├── .defaultTools(SalaryTool)                ← Tool：AI 按需调用薪资查询
    ├── .messages(history)                       ← Memory：多轮对话上下文
    └── .user(input).stream().content()
    │
    ▼
LLM (qwen-plus)：综合 RAG 上下文 + Tool 结果 + 对话历史生成回答
    │
    ▼
SSE 流式输出 → 前端打字机渲染 + 来源引用标签
```

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.1.1 | 应用框架 |
| Spring AI | 2.0.0-M1 | AI 集成框架 |
| Spring AI Alibaba | 2.0.0-M1.1 | 通义千问适配器 |
| Spring WebFlux | 4.1.1 | SSE 流式响应支持 |
| Tika | 由 Spring AI BOM 管理 | 文档解析（Markdown + PDF） |
| SimpleVectorStore | 由 Spring AI BOM 管理 | 内存向量存储 |
| DashScope Embedding | text-embedding-v3 | 文本向量化 |
| Java | 17 | 运行时 |
| LLM | 通义千问（qwen-plus） | 大语言模型 |

### 使用的 Spring AI 特性

- **ChatClient** — 构建对话请求，注入系统提示词、Advisor、Tool
- **Streaming** — `.stream().content()` 返回 `Flux<String>` 实现流式输出
- **BeanOutputConverter** — 基于 Jackson JSON Schema 的结构化输出，自动注入格式指令
- **Conversation Memory** — 自定义内存记忆存储，通过 `.messages(history)` 注入对话上下文
- **QuestionAnswerAdvisor** — RAG 链式编排，自动从 VectorStore 检索 top-5 相关文档注入上下文
- **@Tool / @ToolParam** — 工具调用注解，LLM 根据用户意图自动决定是否调用
- **TikaDocumentReader** — 统一文档解析器，支持 Markdown 和 PDF
- **TokenTextSplitter** — 文本分块器（chunkSize=400），用于知识库文档切分
- **SimpleVectorStore** — 内存向量存储，零配置即可运行

## 项目结构

```
src/main/java/io/github/aiaugmentedemployertoolkit/
├── AiAugmentedEmployerToolkitApplication.java    # 启动类
├── config/
│   ├── ChatClientConfig.java                     # ChatClient 配置（同步 + 流式，注入 Advisor 和 Tool）
│   └── VectorStoreConfig.java                    # 自适应向量存储（默认 SimpleVectorStore）
├── controller/
│   └── AnalyzeController.java                    # REST 接口（同步 + SSE 流式）
├── dto/
│   ├── AnalyzeRequest.java                       # 请求体
│   ├── AnalyzeResponse.java                      # 响应体（含 @JsonPropertyDescription）
│   └── TransitionPath.java                       # 转岗路径（含 @JsonPropertyDescription）
├── service/
│   ├── AnalyzeService.java                       # 核心业务（BeanOutputConverter + 流式 + 兜底解析）
│   ├── ConversationMemoryStore.java              # 内存会话记忆（ConcurrentHashMap + 滑动窗口）
│   └── KnowledgeBaseService.java                 # 知识库加载（启动时自动运行，Tika + 分块 + 嵌入）
└── tool/
    └── SalaryTool.java                           # 薪资查询工具（@Tool，8 岗位 × 3 城市）

src/main/resources/
├── application.yml                               # 应用配置
├── prompts/analyze-prompt.txt                    # 系统提示词（含 RAG 引用指引 + 工具调用指引）
├── knowledge/                                    # 知识库文档（启动时自动加载）
│   ├── career-transition-cases.md                # 转岗成功案例（6 个）
│   ├── industry-automation-report.md             # 各行业自动化影响数据
│   ├── salary-benchmarks.md                      # 各岗位各城市薪资范围表
│   └── skill-mapping-guide.md                    # 技能迁移映射矩阵
└── static/
    └── index.html                                # 前端页面（响应式布局 + 对话气泡）
```

## 前端页面

浏览器打开 `http://localhost:8089` 即可使用：

- 可折叠的格力案例横幅（点击展开查看详情和数据）
- 输入岗位描述，点击"开始分析"（Ctrl/Cmd + Enter 快捷键）
- 流式打字机效果实时展示 AI 分析过程
- 分析完成后渲染横向仪表盘（环形图 + 摘要）和转岗路径卡片
- 对话气泡式追问区，支持多轮对话（Enter 发送），保留完整上下文
- RAG 知识库来源自动渲染为引用标签

## 踩坑记录

### DashScope 多模态自动配置冲突

`spring-ai-alibaba-starter-dashscope` 会自动注册多模态嵌入和音频的自动配置类，如果不需要这些功能，必须在 `application.yml` 中排除，否则启动报错：

```yaml
spring:
  autoconfigure:
    exclude:
      - com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeMultimodalEmbeddingAutoConfiguration
      - com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioAutoConfiguration
```

### pgvector 依赖导致 DataSource 启动失败

`spring-ai-starter-vector-store-pgvector` 传递依赖了 `spring-boot-starter-jdbc`，Spring Boot 检测到 JDBC 后自动触发 `DataSourceAutoConfiguration`，在没有配置 PostgreSQL 连接的情况下会报 `Failed to determine a suitable driver class`。

Maven 的 `<optional>true</optional>` 仅阻止向下游项目传递，在当前项目中仍会被引入。尝试排除 `DataSourceAutoConfiguration` 和 `PgVectorStoreAutoConfiguration` 均不够彻底。

当前方案：移除 pgvector 依赖，使用 `SimpleVectorStore`（内存向量库）满足 demo 需求。如需持久化向量存储，重新添加依赖并配置数据库连接即可。

### 知识库 PDF 目录不存在导致启动崩溃

`PathMatchingResourcePatternResolver` 扫描不存在的目录时会抛 `FileNotFoundException` 而非返回空数组。如果 `knowledge/pdf/` 目录未创建，`KnowledgeBaseService` 启动加载会失败。

解决方案：对 PDF 目录加载包裹 try-catch，目录不存在时仅打印 warn 日志并继续。

### BeanOutputConverter 与流式输出的冲突

`BeanOutputConverter` 会在系统提示词中注入 JSON Schema 格式指令，这对流式输出会产生干扰（格式指令可能出现在流式文本中）。解决方案是创建两个独立的 `ChatClient` Bean：同步分析用带 `BeanOutputConverter` 的版本，流式输出用纯净的版本。

## License

[Apache License 2.0](LICENSE)
