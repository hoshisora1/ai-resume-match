# AI Resume Match 前端产品体验设计

## 1. 背景与目标

`ai-resume-match` 已完成后端工程化重构，具备简历上传、岗位创建、异步分析、任务状态、失败重试、报告查询、可靠消息、缓存、可观测性和端到端验证。目前缺少面向真实用户的操作界面。

本阶段增加一个作品级前端，使项目能够在简历和面试演示中直观体现：

- AI 能力被包装成可完成的产品流程，而不是孤立的模型调用。
- 异步任务、失败恢复、数据持久化和安全边界在界面中有明确表达。
- 前后端、代理、容器和测试形成完整交付链路。
- 首屏是可直接使用的产品界面，不增加营销落地页。

产品定位为单用户、本地或内部演示环境。界面使用中文，目标视口为桌面和移动端。

## 2. 已确认决策

- 前端技术栈：React + TypeScript + Vite。
- 部署方式：独立前端容器，Nginx 托管静态资源并代理 Spring Boot API。
- 首页结构：数据总览首页，而不是引导式首屏或工程控制台。
- 视觉风格：精确工程感。
- 功能深度：总览、分析历史、新建分析、任务进度、报告查看和失败重试。
- API Token 由 Nginx 或 Vite 开发代理注入，不进入浏览器代码或存储。
- 为前端补充岗位名称、组合提交、历史分页和总览统计接口。

## 3. 范围

### 3.1 包含

- 分析总览与近期记录。
- 按状态筛选、分页浏览分析历史。
- 上传 PDF/DOCX 简历并填写岗位名称和 JD。
- 原子创建简历、岗位、分析任务和 outbox 事件。
- 展示排队、运行、自动重试、成功和失败状态。
- 对可重试失败执行手动重试。
- 展示匹配分数、任务元数据和 Markdown 报告。
- 桌面与移动端响应式布局。
- 前端单元、接口模拟、浏览器和全栈冒烟测试。
- Docker Compose 一键启动前端及现有后端依赖。

### 3.2 不包含

- 账号、登录、RBAC、多租户或计费。
- 面向公网用户的认证体系。
- 简历原文预览、在线编辑或长期浏览器存储。
- AI 对话、人工修改报告或报告导出。
- RabbitMQ、Redis、outbox 等运维控制台。
- 在产品内加入技术栈或工程架构宣传页；工程说明保留在仓库文档中。

## 4. 信息架构与路由

前端使用稳定的应用外壳：桌面端为左侧导航和顶部操作栏，移动端将导航变为紧凑的横向入口。顶部始终保留服务状态和“新建分析”主操作。

路由：

```text
/                         分析总览
/analyses                 分析历史
/analyses/new             新建分析
/analyses/:taskId         分析详情
```

`/analyses/:taskId` 是任务状态驱动的详情页：

- `PENDING`、`RUNNING`：显示进度与轮询状态。
- `FAILED_RETRYABLE`：显示失败摘要、尝试次数和重试操作。
- `FAILED_FINAL`、`CANCELLED`、兼容状态 `FAILED`：显示终态说明。
- `SUCCESS`：显示匹配分数、报告和任务元数据。

这样保留独立的进度、失败和报告视图，同时避免为同一个任务创建多条不必要的 URL。

## 5. 页面设计

### 5.1 分析总览

首屏包括：

- 全部分析数量。
- 已完成数量。
- 成功任务的平均匹配度。
- 最近五条分析记录。
- “新建分析”主操作。

统计块和记录表格使用稳定尺寸，避免加载或状态变化造成布局跳动。移动端将统计块改为单列，并隐藏历史表格中的简历文件名等次要列。

### 5.2 分析历史

历史页支持：

- 状态筛选。
- 页码和每页数量。
- 空历史与无筛选结果两种空状态。
- 点击记录进入分析详情。

筛选和分页状态写入 URL 查询参数，刷新、返回和分享本地链接时保持当前视图。

### 5.3 新建分析

新建页在一个专注页面内完成：

1. 选择 PDF 或 DOCX 简历。
2. 填写岗位名称。
3. 粘贴岗位 JD。
4. 提交前确认文件名和岗位名称。

浏览器只在当前组件内存中保留文件和 JD。提交成功后立即清空表单并跳转任务详情；提交失败时保留输入，允许用户修正或重试。

### 5.4 分析详情

任务进行中时展示当前状态、已尝试次数和创建时间。前端以 2 秒开始轮询，并逐步增加到最多 8 秒；页面进入后台后停止积极轮询，回到前台再刷新。

成功后展示：

- 匹配分数。
- 岗位名称和简历文件名。
- 创建和完成时间。
- Markdown 匹配报告。
- 返回历史和再次分析操作。

报告渲染禁止原始 HTML。外部链接使用安全属性，不把 AI 输出视为可信页面内容。

## 6. 视觉系统

视觉方向为安静、可信、适合重复操作的工程产品界面。

核心颜色：

```text
Ink        #18252e  深墨侧栏与主要文字
Teal       #176c62  主操作、分数与成功状态
Amber      #edb755  需要注意的状态与小面积强调
Surface    #f4f6f7  页面背景
White      #ffffff  表格、输入区和内容表面
Danger     #b44735  终态失败和破坏性反馈
```

设计约束：

- 不使用渐变、装饰性光斑或营销式大标题。
- 卡片和面板圆角不超过 8px，不嵌套装饰卡片。
- 使用 Lucide 图标，陌生图标提供 tooltip 和可访问名称。
- 字号不随视口宽度缩放，字距为 0。
- 使用本地可用的系统字体栈，避免运行时依赖外部字体服务。
- 状态不能只靠颜色表达，必须同时包含文字或图标。
- 表格、计数、按钮和上传区域使用稳定尺寸约束。

## 7. 前端架构

仓库新增独立 `frontend/` 工程：

```text
frontend/
  src/
    app/                 路由、应用外壳、Query Client、错误边界
    features/
      dashboard/         总览查询与视图
      analyses/          历史、创建、详情、轮询、重试
      reports/           分数与 Markdown 报告
    shared/
      api/               fetch 客户端、DTO、运行时响应校验
      components/        通用状态、表单和布局组件
      lib/               日期、状态和错误映射
      styles/            设计令牌与全局样式
  e2e/                   Playwright 场景
  nginx/                 Nginx 模板
```

技术职责：

- React Router 管理页面路由。
- TanStack Query 管理服务端状态、轮询和缓存失效。
- React Hook Form + Zod 管理表单和客户端校验。
- `fetch` 封装统一处理 JSON、multipart、错误体、request ID 和取消请求。
- React Testing Library + Vitest 覆盖组件与业务交互。
- MSW 提供确定性的接口状态模拟。
- Playwright 覆盖浏览器流程和响应式行为。

不引入额外全局状态库。服务器数据归 TanStack Query，短期表单数据归页面组件，路由筛选归 URL。

## 8. 运行拓扑与 Token 边界

生产或 Docker Compose 链路：

```text
Browser
  -> frontend:8080 (Nginx)
      -> React static assets
      -> /api/* -> app:8080/api/*
      -> /backend-health -> app:8080/actuator/health/readiness
```

Nginx 启动时从模板生成配置，并在代理请求中设置 `X-API-Token`。浏览器只访问同源 `/api`，不读取 Token，也不需要 CORS。顶部健康指示器只表示前端可以访问 Spring Boot readiness endpoint，不把它解释为所有外部依赖均正常。

本地开发链路：

```text
Browser -> Vite dev server -> proxy -> localhost Spring Boot
```

Vite 配置读取不带 `VITE_` 前缀的服务端环境变量 `API_TOKEN`，只在开发代理中注入请求头。任何进入客户端 bundle 的变量都不得包含 Token。

Docker Compose 新增 `frontend` 服务，默认通过 `${FRONTEND_PORT:-3000}:8080` 暴露。Spring Boot 端口继续保留，便于 API 调试和现有测试兼容。

## 9. 后端契约扩展

### 9.1 岗位名称

`job_description` 新增非空 `title varchar(120)`，通过 Flyway V2 迁移：

1. 以 nullable 形式增加字段。
2. 为历史记录填充 `岗位 {id}`。
3. 修改为非空字段。

`CreateJobRequest` 增加可选 `title`，旧客户端未提供时从 JD 第一条非空文本生成安全的截断标题，无法生成时使用 `未命名岗位`。新前端始终显式提交岗位名称。

### 9.2 组合提交

新增：

```http
POST /api/analysis-submissions
Content-Type: multipart/form-data

file=<PDF|DOCX>
jobTitle=<1..120 characters>
jobContent=<non-blank text>
```

处理流程：

1. 在数据库事务外校验和解析文件，避免长事务。
2. 在同一事务内保存简历、岗位、分析任务和 outbox 事件。
3. 返回完整 `AnalysisTaskResponse`。

现有 `POST /api/resumes`、`POST /api/jobs` 和 `POST /api/analysis` 保留。

### 9.3 历史分页

新增：

```http
GET /api/analysis?status=SUCCESS&page=0&size=20
```

响应：

```json
{
  "items": [
    {
      "taskId": 1,
      "jobTitle": "高级后端工程师",
      "resumeFileName": "resume.pdf",
      "status": "SUCCESS",
      "matchScore": 88,
      "attemptCount": 1,
      "maxAttempts": 3,
      "failureCode": null,
      "createdAt": "...",
      "updatedAt": "...",
      "completedAt": "..."
    }
  ],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

默认按 `createdAt desc, taskId desc` 排序。`page` 默认 `0` 且不得为负数，`size` 默认 `20`、最大 `100`；`status` 为空时查询全部，非空时必须是服务端支持的任务状态。

### 9.4 总览统计

新增：

```http
GET /api/analysis/summary
```

响应字段：

- `totalCount`
- `successCount`
- `inProgressCount`
- `retryableFailureCount`
- `averageMatchScore`，成功报告分数的算术平均值，四舍五入到一位小数；没有成功报告时为 `null`

现有任务详情响应增加 `jobTitle`、`resumeFileName` 和 nullable `matchScore`。这些均为新增字段，不删除或改名现有字段。

## 10. 数据流

### 10.1 总览与历史

总览并行查询 summary 和最近五条记录。历史列表的 query key 包含状态、页码和每页数量。创建任务或重试成功后，使 summary、历史和对应任务缓存失效。

### 10.2 新建分析

```text
Select file + enter title/JD
  -> client validation
  -> POST /api/analysis-submissions
  -> receive taskId
  -> clear sensitive form state
  -> navigate /analyses/{taskId}
```

### 10.3 任务轮询

```text
PENDING/RUNNING
  -> GET task at 2s, 4s, then max 8s
  -> SUCCESS: stop polling and fetch report
  -> FAILED_RETRYABLE: stop and expose retry
  -> terminal failure: stop and show diagnostic state
```

网络查询失败不会改变任务业务状态。前端保留最后一次有效状态、降低查询频率并提供手动刷新。

## 11. 错误与状态处理

前端统一解析 `ApiErrorResponse(code, message, requestId)`，按位置展示：

- 字段错误：对应字段下方。
- 文件错误：上传区域内。
- 操作错误：靠近触发操作的 inline alert 或短时通知。
- 页面加载失败：页面级恢复状态。
- 未捕获渲染异常：Error Boundary。

状态规则：

- `401` 表示代理或环境配置错误，不提示用户在浏览器输入 Token。
- `FAILED_RETRYABLE` 显示重试按钮、失败摘要和尝试次数。
- 最终失败不显示无效重试，提供安全说明及可复制的 request ID。
- 后端堆栈、原始 prompt、AI 原始请求和文档正文不得出现在错误界面或前端日志。
- 加载、空列表、无筛选结果、排队、运行、自动重试、成功和失败均有独立状态。

## 12. 安全与隐私

- 简历文件和 JD 不写入 `localStorage`、`sessionStorage`、IndexedDB 或前端日志。
- API Token 不进入浏览器 bundle、网络响应或浏览器存储。
- Markdown 不启用原始 HTML。
- Nginx 设置内容类型保护、referrer policy 和适合静态 SPA 的 Content Security Policy。
- 前端依赖不从运行时 CDN 加载。
- 示例和测试只使用合成简历与 JD。
- 任何前端分析或错误上报都不得包含简历、JD 或报告正文；首版不接入第三方前端监控。

## 13. 可访问性与响应式

- 所有表单控件具有可见 label、错误关联和键盘焦点。
- 图标按钮具有 tooltip 和 accessible name。
- 状态同时使用文字和视觉标记。
- 颜色对比满足常规文本可读性要求。
- 主流程仅使用键盘即可完成。
- 长岗位名称、长文件名和长错误消息必须换行或截断并提供完整信息。
- 重点验证 `1440x900` 桌面和 `390x844` 移动视口。
- 固定格式元素使用 grid、minmax、aspect-ratio 或稳定高度，避免内容变化导致位移。

## 14. 测试策略

### 14.1 前端快速测试

Vitest + React Testing Library 覆盖：

- 总览统计、空状态和历史筛选。
- 新建表单校验、文件限制和提交状态。
- 任务状态到 UI 的映射。
- 轮询启动、退避和终止。
- 可重试与最终失败。
- API 错误和 request ID。
- Markdown 安全渲染。

MSW 模拟 PENDING、RUNNING、SUCCESS、FAILED_RETRYABLE、FAILED_FINAL、401、网络中断和非法响应。

### 14.2 浏览器测试

Playwright 覆盖：

- 总览到新建分析的完整导航。
- 上传、提交、等待和报告查看。
- 历史筛选、分页和结果回看。
- 失败重试。
- 键盘操作和焦点。
- 桌面、移动视口及关键截图。

### 14.3 全栈验收

使用 React/Nginx、Spring Boot、MySQL、Redis、RabbitMQ 和 mock AI 启动完整环境，Playwright 通过浏览器提交合成 DOCX/PDF 并读取真实报告。

验收命令：

```powershell
cd frontend
npm run lint
npm run typecheck
npm run test
npm run build
npm run test:e2e

cd ..
mvn test
mvn verify
docker compose --env-file .env.example config --quiet
```

## 15. 部署与文档

- 新增前端多阶段 Dockerfile，构建产物由非特权 Nginx 运行。
- Docker Compose 新增 frontend service、healthcheck 和依赖关系。
- README 默认入口改为前端 URL，同时保留 API 和 RabbitMQ 管理入口说明。
- `docs/architecture.md` 增加浏览器、Nginx 和前端模块边界。
- `docs/development.md` 增加 Node 开发、测试和构建命令。
- `docs/operations/runbook.md` 增加前端健康检查、代理 Token 和静态资源故障排查。

完整 Compose 验收前，需要修复已观察到的 MySQL Connector/J URL 问题：配置不得使用 `characterEncoding=utf8mb4` 作为 Java charset，应保留数据库 `utf8mb4` collation 并使用受支持的连接参数。该修复属于前端全栈启动的必要前置条件。

## 16. 验收标准

设计完成的实现必须满足：

- 用户可以从浏览器完成上传、岗位填写、创建任务、等待、查看报告和重试。
- 首页展示真实统计和最近分析，历史页支持筛选与分页。
- 组合提交不会因中途失败留下部分业务记录。
- 浏览器无法读取 API Token，网络请求保持同源。
- AI 报告不能注入原始 HTML。
- 所有关键业务状态具有明确且稳定的界面。
- 桌面和移动端无重叠、不可读文本或横向页面溢出。
- 前端 lint、typecheck、unit、browser tests 和 production build 通过。
- 后端 `mvn test`、Docker-backed `mvn verify` 和 Compose 配置验证继续通过。
- README、架构、开发和运维文档与实际运行方式一致。

## 17. 设计结论

采用独立 React 前端和 Nginx 同源代理，在不改变现有后端可靠性边界的前提下，将系统升级为可直接演示和使用的作品级 AI 应用。前端以数据总览为首页，以异步分析任务为核心交互对象，通过组合提交、历史查询、状态轮询、失败恢复和安全报告渲染，把现有工程能力转化为清晰的用户体验。
