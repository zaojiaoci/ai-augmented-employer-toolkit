# 参与贡献 · ai-augmented-employer-toolkit

感谢你愿意为这个项目添砖加瓦。这是一份给贡献者的快速上手指南。

## 这个项目在做什么（一句话）

用 Spring AI 把“AI 对企业岗位的影响”做成可落地的分析工具，核心理念是 **增强而非替代**——先拆任务、再谈替代比例，并给出转岗路径。

## 环境要求

| 工具 | 版本 | 说明 |
|------|------|------|
| JDK | 17+ | 项目基于 Spring Boot 4.1.1 |
| Maven | 自带（用 `./mvnw`） | 不需要本机单独安装 Maven |
| DashScope API Key | 可选 | 仅运行“真实调用模型”的 live 测试时需要；普通单测与启动都不需要 |

## 一键跑起来

```bash
git clone https://github.com/zaojiaoci/ai-augmented-employer-toolkit.git
cd ai-augmented-employer-toolkit

# 可选：想要真实调模型才需要；不配也能启动、能跑全部单测
export AI_DASHSCOPE_API_KEY=your_key_here

./mvnw spring-boot:run
```

启动后浏览器打开 `http://localhost:8089` 即可使用。

## 跑测试

```bash
./mvnw test
```

- **全部单元测试都是离线执行的**：用 mock / 假 `ChatModel`，不需要 API Key，不发任何网络请求，可直接进 CI。
- 有两个“真实调模型”的测试默认跳过，需要显式开启：
  - `FollowUpBehaviorLiveTest`：环境里有 `AI_DASHSCOPE_API_KEY` 时自动跑（约 45 秒）。
  - `AnalysisEvaluationLiveTest`：需要 `AI_DASHSCOPE_API_KEY` **且** `RUN_LIVE_EVAL=true`（约 9 分钟）。
  - 想让 CI 完全离线：不设置 key 或不开启 `RUN_LIVE_EVAL` 即可。

## 开发约定

1. **全应用只有一个 `ChatClient` 实例**（在 `ChatClientConfig` 装配）。不要再为“同步/流式”拆第二个 Bean —— 那是被验证过的无效方案。
2. **提示词注入规则写在 system prompt（`prompts/analyze-prompt.txt`），user message 里只放被 `<<<USER_INPUT>>>` 包裹的纯数据**。规则和数据混进同一条消息会诱导模型“讨论结构本身”（踩坑记录 D1）。
3. **涉及提示词 / 投递格式的改动，必须实际调一次模型看输出**，不能只看单测变绿。模型行为没人用单测兜底（参考 `FollowUpBehaviorLiveTest`）。
4. **可选能力都要有降级路径**：rerank、evaluation 关闭时不能启动失败（见 `RagAdvisorFactory` 能力分级）。新增可选能力请保持这一约定。
5. **知识库文档必须有可信度 front-matter**：`knowledge/*.md` 文件头用 `--- credibility: reported|structural|directional|illustrative ---` 声明等级，未声明一律按 `illustrative`（示意数据，不可用于决策）处理。
6. **Lombok 已配好注解处理器**，新增 DTO 用 `@Data` / 记录类即可，不要手写样板 getter/setter。

## 提交规范

- 分支：从 `main` 切 `feature/xxx` 或 `fix/xxx`。
- Commit：使用 Conventional Commits 风格（`feat:`、`fix:`、`docs:`、`test:` 等），一行说明清楚“为什么”。
- PR：描述里说明改动点 + 如何验证（尤其 LLM 行为类改动，附一次真实模型输出或日志）。
- 许可证：本项目为 **Apache License 2.0**，提交即表示你同意以该许可证授权你的贡献。

## 适合入手的方向（good first contributions）

如果不确定从哪开始，下面这些边界清晰、风险低：

- **补充知识库文档**：写一份新行业的自动化影响报告，带 `credibility` front-matter（注意：示意数据必须标 `illustrative`，不得写不可核查的具体数字）。
- **前端增强**：分析报告导出（PDF/Markdown）、移动端细节打磨、`index.html` 的引用标签样式。
- **更多模型适配**：当前默认 `qwen-plus`，尝试 DeepSeek / 其他 OpenAI 兼容模型，验证只需改配置（验证“切换成本”本身也是贡献）。
- **单测补强**：给 `AnalyzeService` / `ResultSplitter` 的边缘情况补用例（标记跨多个 token、空流等）。
- **新增 eval-case**：往 `src/test/resources/eval-cases.json` 里加一条行业用例，丰富评测基线。
- **文档与国际化**：README 英文版、踩坑记录提炼成独立博客。

欢迎在 Issue 里认领或提出你自己的方向。

## 讨论与求助

- Bug / 功能建议：开 Issue。
- 想聊设计取舍、踩坑经验：开 Discussion 或直接联系维护者。
- 不确定改动是否合适？先开一个 Draft PR 或 Issue 讨论，比闷头写一大坨再被驳回更省事。
