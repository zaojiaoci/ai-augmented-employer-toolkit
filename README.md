# ai-augmented-employer-toolkit

用 AI 分析"企业引入 AI 后对现有岗位的影响"，目标是帮企业找到一条 **不裁员、转岗培训、人机协作** 的落地路径。

## 为什么做这个

企业引入 AI 的默认叙事是"降本增效 = 减少人员"。但这个等式不是必然的。

格力在引入自动化时，找出老职工原有技能和新岗位的"最大公约数"——让员工觉得"我不是从头再来，是在老经验上添新本事"。结果是转岗后技能等级提升，收入平均增长 8%，数年来转岗成功率 100%，没有一名老职工因为技术替代掉队。

这个项目将这个理念产品化：**用 AI 评估岗位替代风险，同时给出可操作的转岗路径建议。** 让"增强而非替代"成为可落地的方案。

## 功能特性

- **岗位自动化分析** — 输入岗位描述，AI 评估自动化替代率（0%~100%），识别哪些任务易被替代、哪些需要人类判断
- **转岗路径建议** — 基于"技能最大公约数"理念，找出可迁移的核心技能，推荐目标岗位，指出需要补充的能力
- **流式输出（SSE）** — 实时打字机效果展示 AI 分析过程，告别白屏等待
- **多轮对话追问** — 分析完成后可继续追问（如"需要学什么课程？""薪资预期如何？"），AI 基于上下文回答
- **Structured Output** — 使用 Spring AI `BeanOutputConverter` 确保 LLM 返回结构化数据，附带手动解析兜底
- **格力案例融入** — 系统提示词和前端 UI 均融入格力"不裁一人"的成功实践

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
  "summary": "该岗位中订单录入和发票开具属于高度重复性任务，AI 可显著提效；客户电话回访涉及情感沟通和异常处理，仍需人工参与。",
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

## 技术栈

| 组件 | 版本 | 说明 |
|------|------|------|
| Spring Boot | 4.1.1 | 应用框架 |
| Spring AI | 2.0.0-M1 | AI 集成框架 |
| Spring AI Alibaba | 2.0.0-M1.1 | 通义千问适配器 |
| Spring WebFlux | 4.1.1 | SSE 流式响应支持 |
| Java | 17 | 运行时 |
| LLM | 通义千问（qwen-plus） | 大语言模型 |

### 使用的 Spring AI 特性

- **ChatClient** — 构建对话请求，设置系统提示词（`defaultSystem`）
- **Streaming** — `.stream().content()` 返回 `Flux<String>` 实现流式输出
- **BeanOutputConverter** — 基于 Jackson JSON Schema 的结构化输出，自动注入格式指令
- **Conversation Memory** — 自定义内存记忆存储，通过 `.messages(history)` 注入对话上下文

## 项目结构

```
src/main/java/io/github/aiaugmentedemployertoolkit/
├── AiAugmentedEmployerToolkitApplication.java    # 启动类
├── config/
│   └── ChatClientConfig.java                     # ChatClient 配置（同步 + 流式两个 Bean）
├── controller/
│   └── AnalyzeController.java                    # REST 接口（同步 + SSE 流式）
├── dto/
│   ├── AnalyzeRequest.java                       # 请求体
│   ├── AnalyzeResponse.java                      # 响应体（含 @JsonPropertyDescription）
│   └── TransitionPath.java                       # 转岗路径（含 @JsonPropertyDescription）
└── service/
    ├── AnalyzeService.java                       # 核心业务（BeanOutputConverter + 流式 + 兜底解析）
    └── ConversationMemoryStore.java              # 内存会话记忆（ConcurrentHashMap + 滑动窗口）

src/main/resources/
├── application.yml                               # 应用配置
├── prompts/analyze-prompt.txt                    # 系统提示词（融入格力案例）
└── static/
    └── index.html                                # 前端页面（流式打字机 + 追问对话）
```

## 前端页面

浏览器打开 `http://localhost:8089` 即可使用：

- 格力案例 banner（100% 转岗成功率、+8% 收入增长、0 人掉队）
- 输入岗位描述，点击"开始分析"
- 流式打字机效果实时展示 AI 分析过程
- 分析完成后渲染：自动化率仪表盘、风险标签、分析摘要、转岗路径卡片
- 追问输入框支持多轮对话（Enter 发送）

## License

[Apache License 2.0](LICENSE)
