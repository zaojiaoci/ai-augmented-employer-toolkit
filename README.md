# ai-augmented-employer-toolkit

用 AI 分析"企业引入 AI 后对现有岗位的影响"，目标是帮企业找到一条 **不裁员、转岗培训、人机协作** 的落地路径。

## 为什么做这个

企业引入 AI 的默认叙事是"降本增效 = 减少人员"。但这个等式不是必然的。

这个项目想提供一个可操作的反例：**用 AI 提升效率的同时，把受影响员工转岗到新岗位，而不是直接替代。** 它开源"什么算 AI 增强型雇主"的标准和工具，让企业可以自评、自证、被看见。

## 当前进度

**v0.1** — 最小可用原型

- `POST /api/analyze`：输入一段岗位描述，返回 AI 对该岗位的替代率分析和简要摘要
- 基于 Spring AI Alibaba（DashScope / 通义千问）构建

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
| `ANALYZE_MODEL` | `qwen-plus` | 使用的通义千问模型 |

### 启动

```bash
./mvnw spring-boot:run
```

服务默认监听 `http://localhost:8089`。

### 调用示例

```bash
curl -X POST http://localhost:8080/api/analyze \
  -H "Content-Type: application/json" \
  -d '{"jobDescription": "负责每日订单录入、发票开具和客户电话回访"}'
```

返回示例：

```json
{
  "automationRatio": 0.72,
  "summary": "该岗位中订单录入和发票开具属于高度重复性任务，AI 可显著提效；客户电话回访涉及情感沟通和异常处理，仍需人工参与。建议将录入和开票环节交由 AI 处理，员工转向客户关系维护和复杂问题解决。"
}
```

## 技术栈

| 组件 | 版本 |
|------|------|
| Spring Boot | 4.1.1 |
| Spring AI | 2.0.0-M1 |
| Spring AI Alibaba | 2.0.0-M1.1 |
| Java | 17 |
| LLM | 通义千问（qwen-plus） |

## 项目结构

```
src/main/java/io/github/aiaugmentedemployertoolkit/
├── AiAugmentedEmployerToolkitApplication.java   # 启动类
├── config/ChatClientConfig.java                 # ChatClient 配置（加载系统提示词）
├── controller/AnalyzeController.java            # REST 接口
├── dto/
│   ├── AnalyzeRequest.java                      # 请求体
│   └── AnalyzeResponse.java                     # 响应体
└── service/AnalyzeService.java                  # 业务逻辑（调用 LLM + 解析结果）

src/main/resources/
├── application.yml                              # 应用配置
└── prompts/analyze-prompt.txt                   # 系统提示词
```

## License

[Apache License 2.0](LICENSE)
