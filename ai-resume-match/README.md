# 基于 RAG 的智能简历与岗位匹配系统

这是一个后端主导的 AI 应用项目。系统支持上传简历和岗位 JD，通过文档解析、JD 标签提取、本地向量检索、RAG 上下文组装和大模型分析生成岗位匹配报告。

## 技术栈

Java 21, Spring Boot 3, Spring MVC, Spring Data JPA, MySQL, Redis, RabbitMQ, PDFBox, Apache POI, local hashing embedding, cosine similarity, OpenAI-compatible API, Docker Compose, JUnit 5。

## 启动方式

```powershell
docker compose up -d
$env:API_TOKEN="dev-token"
$env:AI_API_KEY="your-api-key"
mvn spring-boot:run
```

## 核心接口

所有接口需要携带请求头：`X-API-Token: dev-token`。

- `POST /api/resumes`：上传 PDF/DOCX 简历。
- `POST /api/jobs`：提交岗位 JD。
- `POST /api/analysis`：创建异步分析任务。
- `GET /api/analysis/{taskId}/report`：查询匹配报告。

## 项目亮点

- 使用 PDFBox 和 Apache POI 提取 PDF/DOCX 简历文本。
- 基于固定技能词表提取 JD 技能标签，形成后续匹配分析输入。
- 使用本地 hashing embedding 和 cosine similarity 实现最小向量检索，召回与岗位最相关的简历片段。
- 使用 RabbitMQ 异步处理分析任务，消息体只传 `taskId`，任务状态与报告结果以 MySQL 为准。
- 使用 Redis 对匹配报告查询做 cache-aside 缓存，降低重复查询数据库的开销。
- 使用 `X-API-Token` 提供本地项目级接口保护。
- 通过 OpenAI-compatible API 生成匹配分数、技能差距、项目优化建议和模拟面试题。

## 简历写法

项目名称：基于 RAG 的智能简历与岗位匹配系统

项目简介：该项目面向求职场景，支持用户上传简历和岗位 JD，基于文档解析、本地向量检索和大模型分析生成岗位匹配报告。系统围绕 JD 能力标签提取、RAG 上下文组装、异步分析任务和报告缓存进行设计，帮助用户获得匹配评分、技能差距、项目优化建议和模拟面试题。

## 计划文档

- `docs/superpowers/plans/2026-05-12-rag-resume-job-match.md`
