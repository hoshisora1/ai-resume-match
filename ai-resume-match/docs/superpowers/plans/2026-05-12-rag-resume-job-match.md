# RAG Resume Job Match Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 构建一个后端主导的“基于 RAG 的智能简历与岗位匹配系统”，用于替换简历中的课设级订餐系统项目。

**Architecture:** 使用 Spring Boot 提供 REST API，MySQL 持久化简历、岗位和分析报告，Redis 缓存任务状态，RabbitMQ 承接异步分析任务。文档解析、JD 标签提取、本地最小向量检索、RAG 上下文组装和大模型调用拆成独立服务；RabbitMQ 消息只传 `taskId`，数据库作为任务状态与报告结果的唯一事实来源。

**Tech Stack:** Java 21, Spring Boot 3, Spring MVC, Spring Data JPA, MySQL, Redis, RabbitMQ, Apache POI, Apache PDFBox, local hashing embedding, cosine similarity, OpenAI-compatible API, Docker Compose, JUnit 5, Testcontainers.

---

## 文件结构

- Create: `pom.xml`：Maven 依赖与 Java 版本管理。
- Create: `docker-compose.yml`：本地启动 MySQL、Redis、RabbitMQ。
- Create: `src/main/resources/application.yml`：应用配置。
- Create: `src/main/java/com/zhulikang/aimatch/AiMatchApplication.java`：启动类。
- Create: `src/main/java/com/zhulikang/aimatch/resume/Resume.java`：简历实体。
- Create: `src/main/java/com/zhulikang/aimatch/job/JobDescription.java`：岗位实体。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java`：分析任务实体。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/MatchReport.java`：分析报告实体。
- Create: `src/main/java/com/zhulikang/aimatch/resume/ResumeRepository.java`：简历数据访问。
- Create: `src/main/java/com/zhulikang/aimatch/job/JobDescriptionRepository.java`：岗位数据访问。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTaskRepository.java`：任务数据访问。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/MatchReportRepository.java`：报告数据访问。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/RabbitConfig.java`：声明异步分析队列。
- Create: `src/main/java/com/zhulikang/aimatch/document/DocumentTextExtractor.java`：PDF/DOCX 文本解析入口。
- Create: `src/main/java/com/zhulikang/aimatch/job/JdTagExtractor.java`：JD 技能标签提取。
- Create: `src/main/java/com/zhulikang/aimatch/rag/TextChunker.java`：简历文本分块。
- Create: `src/main/java/com/zhulikang/aimatch/rag/EmbeddingClient.java`：向量化接口。
- Create: `src/main/java/com/zhulikang/aimatch/rag/HashingEmbeddingClient.java`：本地 hashing embedding 实现。
- Create: `src/main/java/com/zhulikang/aimatch/rag/InMemoryVectorStore.java`：内存向量检索实现。
- Create: `src/main/java/com/zhulikang/aimatch/rag/VectorSearchResult.java`：向量检索结果。
- Create: `src/main/java/com/zhulikang/aimatch/rag/RagContextBuilder.java`：RAG 上下文组装。
- Create: `src/main/java/com/zhulikang/aimatch/ai/AiClient.java`：AI 调用接口。
- Create: `src/main/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClient.java`：OpenAI-compatible API 实现。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java`：任务创建与查询。
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`：异步消费与报告生成。
- Create: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`：REST API。
- Create: `src/test/java/com/zhulikang/aimatch/document/DocumentTextExtractorTest.java`：文档解析测试。
- Create: `src/test/java/com/zhulikang/aimatch/job/JdTagExtractorTest.java`：JD 标签提取测试。
- Create: `src/test/java/com/zhulikang/aimatch/rag/InMemoryVectorStoreTest.java`：向量检索测试。
- Create: `src/test/java/com/zhulikang/aimatch/rag/RagContextBuilderTest.java`：RAG 上下文测试。
- Create: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisServiceTest.java`：任务创建测试。
- Create: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`：接口测试。
- Create: `README.md`：项目说明、启动方式、接口示例、简历写法。

---

### Task 1: 项目骨架与依赖

**Files:**
- Create: `pom.xml`
- Create: `src/main/java/com/zhulikang/aimatch/AiMatchApplication.java`
- Create: `src/main/resources/application.yml`

- [ ] **Step 1: 创建 Maven 配置**

写入 `pom.xml`：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.zhulikang</groupId>
  <artifactId>ai-resume-match</artifactId>
  <version>0.1.0</version>
  <properties>
    <java.version>21</java.version>
    <spring.boot.version>3.3.5</spring.boot.version>
  </properties>
  <dependencyManagement>
    <dependencies>
      <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>${spring.boot.version}</version>
        <type>pom</type>
        <scope>import</scope>
      </dependency>
    </dependencies>
  </dependencyManagement>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-amqp</artifactId></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.apache.pdfbox</groupId><artifactId>pdfbox</artifactId><version>3.0.3</version></dependency>
    <dependency><groupId>org.apache.poi</groupId><artifactId>poi-ooxml</artifactId><version>5.3.0</version></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
      </plugin>
    </plugins>
  </build>
</project>
```

- [ ] **Step 2: 创建启动类**

写入 `src/main/java/com/zhulikang/aimatch/AiMatchApplication.java`：

```java
package com.zhulikang.aimatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiMatchApplication {
    public static void main(String[] args) {
        SpringApplication.run(AiMatchApplication.class, args);
    }
}
```

- [ ] **Step 3: 创建应用配置**

写入 `src/main/resources/application.yml`：

```yaml
spring:
  application:
    name: ai-resume-match
  datasource:
    url: jdbc:mysql://localhost:3306/ai_resume_match?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
    username: root
    password: root
  jpa:
    hibernate:
      ddl-auto: update
    open-in-view: false
  data:
    redis:
      host: localhost
      port: 6379
  rabbitmq:
    host: localhost
    port: 5672
    username: guest
    password: guest

ai:
  endpoint: https://api.openai.com/v1/chat/completions
  api-key: ${AI_API_KEY:demo-key}
  model: gpt-4o-mini
```

- [ ] **Step 4: 验证项目可编译**

Run: `mvn -q test`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/com/zhulikang/aimatch/AiMatchApplication.java src/main/resources/application.yml
git commit -m "chore: scaffold ai resume match backend"
```

---

### Task 2: 领域模型与持久化

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/resume/Resume.java`
- Create: `src/main/java/com/zhulikang/aimatch/job/JobDescription.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisTask.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/MatchReport.java`
- Create: repository files under `resume`, `job`, `analysis`

- [ ] **Step 1: 创建实体类**

核心字段如下，四个实体都使用 `GenerationType.IDENTITY`：

```java
package com.zhulikang.aimatch.resume;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class Resume {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private String fileName;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String rawText;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String structuredSummary;
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected Resume() {}

    public Resume(String fileName, String rawText, String structuredSummary) {
        this.fileName = fileName;
        this.rawText = rawText;
        this.structuredSummary = structuredSummary;
    }

    public Long getId() { return id; }
    public String getFileName() { return fileName; }
    public String getRawText() { return rawText; }
    public String getStructuredSummary() { return structuredSummary; }
}
```

```java
package com.zhulikang.aimatch.job;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class JobDescription {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String content;
    @Column(nullable = false)
    private String skillTags;
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected JobDescription() {}

    public JobDescription(String content, String skillTags) {
        this.content = content;
        this.skillTags = skillTags;
    }

    public Long getId() { return id; }
    public String getContent() { return content; }
    public String getSkillTags() { return skillTags; }
}
```

```java
package com.zhulikang.aimatch.analysis;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class AnalysisTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false)
    private Long resumeId;
    @Column(nullable = false)
    private Long jobDescriptionId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING;
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status { PENDING, RUNNING, SUCCESS, FAILED }

    protected AnalysisTask() {}

    public AnalysisTask(Long resumeId, Long jobDescriptionId) {
        this.resumeId = resumeId;
        this.jobDescriptionId = jobDescriptionId;
    }

    public void markRunning() { this.status = Status.RUNNING; }
    public void markSuccess() { this.status = Status.SUCCESS; }
    public void markFailed() { this.status = Status.FAILED; }
    public Long getId() { return id; }
    public Long getResumeId() { return resumeId; }
    public Long getJobDescriptionId() { return jobDescriptionId; }
    public Status getStatus() { return status; }
}
```

```java
package com.zhulikang.aimatch.analysis;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
public class MatchReport {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true)
    private Long taskId;
    @Column(nullable = false)
    private int matchScore;
    @Lob @Column(nullable = false, columnDefinition = "LONGTEXT")
    private String reportContent;
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    protected MatchReport() {}

    public MatchReport(Long taskId, int matchScore, String reportContent) {
        this.taskId = taskId;
        this.matchScore = matchScore;
        this.reportContent = reportContent;
    }

    public Long getId() { return id; }
    public Long getTaskId() { return taskId; }
    public int getMatchScore() { return matchScore; }
    public String getReportContent() { return reportContent; }
}
```

- [ ] **Step 2: 创建 Repository**

```java
package com.zhulikang.aimatch.resume;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ResumeRepository extends JpaRepository<Resume, Long> {}
```

```java
package com.zhulikang.aimatch.job;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JobDescriptionRepository extends JpaRepository<JobDescription, Long> {}
```

```java
package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisTaskRepository extends JpaRepository<AnalysisTask, Long> {}
```

```java
package com.zhulikang.aimatch.analysis;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface MatchReportRepository extends JpaRepository<MatchReport, Long> {
    Optional<MatchReport> findByTaskId(Long taskId);
}
```

- [ ] **Step 3: 运行测试**

Run: `mvn -q test`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch
git commit -m "feat: add resume match domain model"
```

---

### Task 3: 文档解析服务

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/document/DocumentTextExtractor.java`
- Create: `src/test/java/com/zhulikang/aimatch/document/DocumentTextExtractorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.zhulikang.aimatch.document;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentTextExtractorTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void rejectsUnsupportedFileType() {
        MockMultipartFile file = new MockMultipartFile("file", "resume.txt", "text/plain", "hello".getBytes());
        assertThatThrownBy(() -> extractor.extract(file))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Only PDF and DOCX are supported");
    }

    @Test
    void normalizesBlankCharacters() {
        String normalized = extractor.normalize("Java\\n\\n Spring   Boot\\tRedis");
        assertThat(normalized).isEqualTo("Java Spring Boot Redis");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=DocumentTextExtractorTest test`

Expected: FAIL，原因是 `DocumentTextExtractor` 不存在。

- [ ] **Step 3: 实现解析服务**

```java
package com.zhulikang.aimatch.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.stream.Collectors;

@Service
public class DocumentTextExtractor {
    public String extract(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        try {
            if (name.endsWith(".pdf")) {
                return normalize(extractPdf(file));
            }
            if (name.endsWith(".docx")) {
                return normalize(extractDocx(file));
            }
            throw new IllegalArgumentException("Only PDF and DOCX are supported");
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to extract document text", e);
        }
    }

    String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private String extractPdf(MultipartFile file) throws IOException {
        try (PDDocument document = Loader.loadPDF(file.getBytes())) {
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(MultipartFile file) throws IOException {
        try (XWPFDocument document = new XWPFDocument(file.getInputStream())) {
            return document.getParagraphs().stream()
                .map(p -> p.getText())
                .collect(Collectors.joining(" "));
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=DocumentTextExtractorTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/document src/test/java/com/zhulikang/aimatch/document
git commit -m "feat: extract resume document text"
```

---

### Task 4: JD 标签提取与简历摘要

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/job/JdTagExtractor.java`
- Create: `src/test/java/com/zhulikang/aimatch/job/JdTagExtractorTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.zhulikang.aimatch.job;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdTagExtractorTest {
    private final JdTagExtractor extractor = new JdTagExtractor();

    @Test
    void extractsBackendSkillTagsInStableOrder() {
        String jd = "熟悉 Java、Spring Boot、MySQL、Redis，有 Kafka 项目经验优先";
        assertThat(extractor.extractTags(jd))
            .containsExactly("Java", "Spring Boot", "MySQL", "Redis", "Kafka");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=JdTagExtractorTest test`

Expected: FAIL，原因是 `JdTagExtractor` 不存在。

- [ ] **Step 3: 实现标签提取**

```java
package com.zhulikang.aimatch.job;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class JdTagExtractor {
    private static final List<String> KNOWN_TAGS = List.of(
        "Java", "Spring Boot", "Spring MVC", "MyBatis", "JPA",
        "MySQL", "Redis", "RabbitMQ", "Kafka", "Docker",
        "RAG", "向量检索", "大模型"
    );

    public List<String> extractTags(String jdText) {
        String lower = jdText.toLowerCase();
        List<String> result = new ArrayList<>();
        for (String tag : KNOWN_TAGS) {
            if (lower.contains(tag.toLowerCase())) {
                result.add(tag);
            }
        }
        return result;
    }

    public String toStorageValue(List<String> tags) {
        return String.join(",", tags);
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q -Dtest=JdTagExtractorTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/job/JdTagExtractor.java src/test/java/com/zhulikang/aimatch/job/JdTagExtractorTest.java
git commit -m "feat: extract job skill tags"
```

---

### Task 5: 最小向量检索与 RAG 上下文组装

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/rag/TextChunker.java`
- Create: `src/main/java/com/zhulikang/aimatch/rag/EmbeddingClient.java`
- Create: `src/main/java/com/zhulikang/aimatch/rag/HashingEmbeddingClient.java`
- Create: `src/main/java/com/zhulikang/aimatch/rag/InMemoryVectorStore.java`
- Create: `src/main/java/com/zhulikang/aimatch/rag/VectorSearchResult.java`
- Create: `src/main/java/com/zhulikang/aimatch/rag/RagContextBuilder.java`
- Create: `src/test/java/com/zhulikang/aimatch/rag/InMemoryVectorStoreTest.java`
- Create: `src/test/java/com/zhulikang/aimatch/rag/RagContextBuilderTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.zhulikang.aimatch.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryVectorStoreTest {
    private final EmbeddingClient embeddingClient = new HashingEmbeddingClient();

    @Test
    void returnsMostRelevantChunksBySimilarity() {
        InMemoryVectorStore store = new InMemoryVectorStore(embeddingClient);
        store.addAll(List.of(
            "项目：高性能秒杀系统，使用 Redis Kafka MySQL 解决库存扣减和异步下单",
            "教育背景：中国地质大学软件工程专业，学习数据结构和算法",
            "工具：熟悉 Git Maven IntelliJ IDEA"
        ));

        List<VectorSearchResult> results = store.search("高并发 Redis Kafka", 2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).text()).contains("Redis Kafka");
        assertThat(results.get(0).score()).isGreaterThanOrEqualTo(results.get(1).score());
    }
}
```

```java
package com.zhulikang.aimatch.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RagContextBuilderTest {
    private final RagContextBuilder builder = new RagContextBuilder();

    @Test
    void buildsPromptWithRetrievedChunksJobAndTags() {
        String prompt = builder.build(
            List.of("召回片段：高性能秒杀系统，使用 Redis Kafka MySQL"),
            "要求：Java 后端，熟悉 Redis 和 Kafka",
            List.of("Java", "Redis", "Kafka")
        );

        assertThat(prompt).contains("你是资深 Java 后端面试官");
        assertThat(prompt).contains("召回片段");
        assertThat(prompt).contains("Redis,Kafka");
        assertThat(prompt).contains("匹配分数");
        assertThat(prompt).doesNotContain("完整简历全文");
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=InMemoryVectorStoreTest,RagContextBuilderTest test`

Expected: FAIL，原因是 `TextChunker`、`EmbeddingClient`、`InMemoryVectorStore`、`RagContextBuilder` 不存在或方法签名不匹配。

- [ ] **Step 3: 实现文本分块**

```java
package com.zhulikang.aimatch.rag;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class TextChunker {
    private static final int CHUNK_SIZE = 350;
    private static final int OVERLAP = 80;

    public List<String> chunk(String text) {
        String normalized = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(start + CHUNK_SIZE, normalized.length());
            chunks.add(normalized.substring(start, end));
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(0, end - OVERLAP);
        }
        return chunks;
    }
}
```

- [ ] **Step 4: 实现本地 hashing embedding**

```java
package com.zhulikang.aimatch.rag;

public interface EmbeddingClient {
    double[] embed(String text);
}
```

```java
package com.zhulikang.aimatch.rag;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class HashingEmbeddingClient implements EmbeddingClient {
    private static final int DIMENSION = 128;

    @Override
    public double[] embed(String text) {
        double[] vector = new double[DIMENSION];
        String normalized = text == null ? "" : text.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}\\u4e00-\\u9fa5]+", " ")
            .trim();
        if (normalized.isEmpty()) {
            return vector;
        }
        for (String token : normalized.split("\\s+")) {
            int index = Math.floorMod(token.hashCode(), DIMENSION);
            vector[index] += 1.0;
        }
        normalize(vector);
        return vector;
    }

    private void normalize(double[] vector) {
        double sum = 0.0;
        for (double value : vector) {
            sum += value * value;
        }
        if (sum == 0.0) {
            return;
        }
        double norm = Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = vector[i] / norm;
        }
    }
}
```

- [ ] **Step 5: 实现内存向量检索**

```java
package com.zhulikang.aimatch.rag;

public record VectorSearchResult(String text, double score) {}
```

```java
package com.zhulikang.aimatch.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class InMemoryVectorStore {
    private final EmbeddingClient embeddingClient;
    private final List<Entry> entries = new ArrayList<>();

    public InMemoryVectorStore(EmbeddingClient embeddingClient) {
        this.embeddingClient = embeddingClient;
    }

    public void addAll(List<String> chunks) {
        for (String chunk : chunks) {
            if (chunk != null && !chunk.isBlank()) {
                entries.add(new Entry(chunk, embeddingClient.embed(chunk)));
            }
        }
    }

    public List<VectorSearchResult> search(String query, int topK) {
        double[] queryVector = embeddingClient.embed(query);
        return entries.stream()
            .map(entry -> new VectorSearchResult(entry.text(), cosine(queryVector, entry.vector())))
            .sorted(Comparator.comparingDouble(VectorSearchResult::score).reversed())
            .limit(topK)
            .toList();
    }

    private double cosine(double[] left, double[] right) {
        double dot = 0.0;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
        }
        return dot;
    }

    private record Entry(String text, double[] vector) {}
}
```

- [ ] **Step 6: 实现 RAG 上下文组装**

```java
package com.zhulikang.aimatch.rag;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RagContextBuilder {
    public String build(List<String> retrievedResumeChunks, String jdText, List<String> tags) {
        return """
            你是资深 Java 后端面试官，请根据简历内容和岗位 JD 生成匹配报告。

            输出格式：
            1. 匹配分数：0-100 的整数
            2. 技能匹配：列出已匹配技能
            3. 技能差距：列出缺失或薄弱技能
            4. 项目优化建议：给出 3 条可落地建议
            5. 模拟面试题：给出 5 个后端相关问题

            JD 技能标签：%s

            召回的简历片段：
            %s

            岗位 JD：
            %s
            """.formatted(String.join(",", tags), String.join("\n---\n", retrievedResumeChunks), jdText);
    }
}
```

- [ ] **Step 7: 运行测试确认通过**

Run: `mvn -q -Dtest=InMemoryVectorStoreTest,RagContextBuilderTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/rag src/test/java/com/zhulikang/aimatch/rag
git commit -m "feat: add local vector retrieval for rag"
```

---

### Task 6: AI 客户端抽象

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/ai/AiClient.java`
- Create: `src/main/java/com/zhulikang/aimatch/ai/OpenAiCompatibleClient.java`

- [ ] **Step 1: 创建 AI 接口**

```java
package com.zhulikang.aimatch.ai;

public interface AiClient {
    String complete(String prompt);
}
```

- [ ] **Step 2: 创建 OpenAI-compatible 实现**

```java
package com.zhulikang.aimatch.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleClient implements AiClient {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String endpoint;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleClient(
        @Value("${ai.endpoint}") String endpoint,
        @Value("${ai.api-key}") String apiKey,
        @Value("${ai.model}") String model
    ) {
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String prompt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        Map<String, Object> body = Map.of(
            "model", model,
            "messages", List.of(Map.of("role", "user", "content", prompt)),
            "temperature", 0.2
        );
        Map response = restTemplate.postForObject(endpoint, new HttpEntity<>(body, headers), Map.class);
        List choices = (List) response.get("choices");
        Map first = (Map) choices.getFirst();
        Map message = (Map) first.get("message");
        return String.valueOf(message.get("content"));
    }
}
```

- [ ] **Step 3: 运行测试**

Run: `mvn -q test`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/ai
git commit -m "feat: add ai client abstraction"
```

---

### Task 7: 分析任务服务

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/analysis/RabbitConfig.java`
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java`
- Create: `src/test/java/com/zhulikang/aimatch/analysis/AnalysisServiceTest.java`

- [ ] **Step 1: 写失败测试**

```java
package com.zhulikang.aimatch.analysis;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.assertThat;

class AnalysisServiceTest {
    @Test
    void createsTaskAndPublishesMessage() {
        AnalysisTaskRepository repository = mock(AnalysisTaskRepository.class);
        MatchReportRepository reportRepository = mock(MatchReportRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        when(repository.save(any())).thenAnswer(invocation -> {
            AnalysisTask task = invocation.getArgument(0);
            ReflectionTestUtils.setField(task, "id", 99L);
            return task;
        });

        AnalysisService service = new AnalysisService(repository, reportRepository, rabbitTemplate);
        AnalysisTask task = service.createTask(1L, 2L);

        assertThat(task.getResumeId()).isEqualTo(1L);
        assertThat(task.getJobDescriptionId()).isEqualTo(2L);
        verify(rabbitTemplate).convertAndSend("analysis.queue", 99L);
        verify(rabbitTemplate, never()).convertAndSend("analysis.queue", task);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q -Dtest=AnalysisServiceTest test`

Expected: FAIL，原因是 `AnalysisService` 不存在。

- [ ] **Step 3: 声明分析队列**

```java
package com.zhulikang.aimatch.analysis;

import org.springframework.amqp.core.Queue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String ANALYSIS_QUEUE = "analysis.queue";

    @Bean
    public Queue analysisQueue() {
        return new Queue(ANALYSIS_QUEUE, true);
    }
}
```

- [ ] **Step 4: 实现任务服务**

```java
package com.zhulikang.aimatch.analysis;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class AnalysisService {
    private final AnalysisTaskRepository taskRepository;
    private final MatchReportRepository reportRepository;
    private final RabbitTemplate rabbitTemplate;

    public AnalysisService(
        AnalysisTaskRepository taskRepository,
        MatchReportRepository reportRepository,
        RabbitTemplate rabbitTemplate
    ) {
        this.taskRepository = taskRepository;
        this.reportRepository = reportRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Transactional
    public AnalysisTask createTask(Long resumeId, Long jobDescriptionId) {
        AnalysisTask task = taskRepository.save(new AnalysisTask(resumeId, jobDescriptionId));
        rabbitTemplate.convertAndSend(RabbitConfig.ANALYSIS_QUEUE, task.getId());
        return task;
    }

    public Optional<MatchReport> findReport(Long taskId) {
        return reportRepository.findByTaskId(taskId);
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `mvn -q -Dtest=AnalysisServiceTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/analysis/RabbitConfig.java src/main/java/com/zhulikang/aimatch/analysis/AnalysisService.java src/test/java/com/zhulikang/aimatch/analysis/AnalysisServiceTest.java
git commit -m "feat: create async analysis tasks"
```

---

### Task 8: 异步分析 Worker

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java`

- [ ] **Step 1: 实现 Worker**

```java
package com.zhulikang.aimatch.analysis;

import com.zhulikang.aimatch.ai.AiClient;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.rag.EmbeddingClient;
import com.zhulikang.aimatch.rag.InMemoryVectorStore;
import com.zhulikang.aimatch.rag.RagContextBuilder;
import com.zhulikang.aimatch.rag.TextChunker;
import com.zhulikang.aimatch.rag.VectorSearchResult;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Component
public class AnalysisWorker {
    private final AnalysisTaskRepository taskRepository;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final MatchReportRepository reportRepository;
    private final TextChunker textChunker;
    private final EmbeddingClient embeddingClient;
    private final RagContextBuilder ragContextBuilder;
    private final AiClient aiClient;

    public AnalysisWorker(
        AnalysisTaskRepository taskRepository,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        MatchReportRepository reportRepository,
        TextChunker textChunker,
        EmbeddingClient embeddingClient,
        RagContextBuilder ragContextBuilder,
        AiClient aiClient
    ) {
        this.taskRepository = taskRepository;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.reportRepository = reportRepository;
        this.textChunker = textChunker;
        this.embeddingClient = embeddingClient;
        this.ragContextBuilder = ragContextBuilder;
        this.aiClient = aiClient;
    }

    @Transactional
    @RabbitListener(queues = RabbitConfig.ANALYSIS_QUEUE)
    public void handle(Long taskId) {
        AnalysisTask task = taskRepository.findById(taskId).orElseThrow();
        try {
            task.markRunning();
            Resume resume = resumeRepository.findById(task.getResumeId()).orElseThrow();
            JobDescription job = jobRepository.findById(task.getJobDescriptionId()).orElseThrow();
            InMemoryVectorStore vectorStore = new InMemoryVectorStore(embeddingClient);
            vectorStore.addAll(textChunker.chunk(resume.getRawText()));
            List<String> retrievedChunks = vectorStore.search(job.getContent(), 3).stream()
                .map(VectorSearchResult::text)
                .toList();
            String prompt = ragContextBuilder.build(
                retrievedChunks,
                job.getContent(),
                Arrays.stream(job.getSkillTags().split(",")).filter(s -> !s.isBlank()).toList()
            );
            String report = aiClient.complete(prompt);
            reportRepository.save(new MatchReport(task.getId(), extractScore(report), report));
            task.markSuccess();
        } catch (RuntimeException ex) {
            task.markFailed();
            throw ex;
        }
    }

    int extractScore(String report) {
        String digits = report.replaceAll("(?s).*?(\\d{1,3}).*", "$1");
        int score = Integer.parseInt(digits);
        return Math.max(0, Math.min(100, score));
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `mvn -q test`

Expected: `BUILD SUCCESS`

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/analysis/AnalysisWorker.java
git commit -m "feat: consume analysis tasks asynchronously"
```

---

### Task 9: REST API

**Files:**
- Create: `src/main/java/com/zhulikang/aimatch/api/ResumeMatchController.java`
- Create: `src/test/java/com/zhulikang/aimatch/api/ResumeMatchControllerTest.java`

- [ ] **Step 1: 写接口测试**

```java
package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ResumeMatchController.class)
class ResumeMatchControllerTest {
    @Autowired MockMvc mockMvc;
    @MockBean DocumentTextExtractor extractor;
    @MockBean ResumeRepository resumeRepository;
    @MockBean JobDescriptionRepository jobRepository;
    @MockBean JdTagExtractor jdTagExtractor;
    @MockBean AnalysisService analysisService;

    @Test
    void returnsNotFoundWhenReportMissing() throws Exception {
        when(analysisService.findReport(1L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/analysis/1/report"))
            .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 2: 实现 Controller**

```java
package com.zhulikang.aimatch.api;

import com.zhulikang.aimatch.analysis.AnalysisService;
import com.zhulikang.aimatch.analysis.AnalysisTask;
import com.zhulikang.aimatch.analysis.MatchReport;
import com.zhulikang.aimatch.document.DocumentTextExtractor;
import com.zhulikang.aimatch.job.JdTagExtractor;
import com.zhulikang.aimatch.job.JobDescription;
import com.zhulikang.aimatch.job.JobDescriptionRepository;
import com.zhulikang.aimatch.resume.Resume;
import com.zhulikang.aimatch.resume.ResumeRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class ResumeMatchController {
    private final DocumentTextExtractor extractor;
    private final ResumeRepository resumeRepository;
    private final JobDescriptionRepository jobRepository;
    private final JdTagExtractor jdTagExtractor;
    private final AnalysisService analysisService;

    public ResumeMatchController(
        DocumentTextExtractor extractor,
        ResumeRepository resumeRepository,
        JobDescriptionRepository jobRepository,
        JdTagExtractor jdTagExtractor,
        AnalysisService analysisService
    ) {
        this.extractor = extractor;
        this.resumeRepository = resumeRepository;
        this.jobRepository = jobRepository;
        this.jdTagExtractor = jdTagExtractor;
        this.analysisService = analysisService;
    }

    @PostMapping("/resumes")
    public Map<String, Long> uploadResume(@RequestParam("file") MultipartFile file) {
        String rawText = extractor.extract(file);
        Resume resume = resumeRepository.save(new Resume(file.getOriginalFilename(), rawText, rawText));
        return Map.of("resumeId", resume.getId());
    }

    @PostMapping("/jobs")
    public Map<String, Long> createJob(@RequestBody Map<String, String> request) {
        String content = request.getOrDefault("content", "");
        String tags = jdTagExtractor.toStorageValue(jdTagExtractor.extractTags(content));
        JobDescription job = jobRepository.save(new JobDescription(content, tags));
        return Map.of("jobDescriptionId", job.getId());
    }

    @PostMapping("/analysis")
    public Map<String, Long> createAnalysis(@RequestBody Map<String, Long> request) {
        AnalysisTask task = analysisService.createTask(request.get("resumeId"), request.get("jobDescriptionId"));
        return Map.of("taskId", task.getId());
    }

    @GetMapping("/analysis/{taskId}/report")
    public ResponseEntity<MatchReport> report(@PathVariable Long taskId) {
        return analysisService.findReport(taskId)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 3: 运行接口测试**

Run: `mvn -q -Dtest=ResumeMatchControllerTest test`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/zhulikang/aimatch/api src/test/java/com/zhulikang/aimatch/api
git commit -m "feat: expose resume match api"
```

---

### Task 10: 本地环境与项目说明

**Files:**
- Create: `docker-compose.yml`
- Create: `README.md`

- [ ] **Step 1: 创建 Docker Compose**

```yaml
services:
  mysql:
    image: mysql:8.4
    environment:
      MYSQL_ROOT_PASSWORD: root
      MYSQL_DATABASE: ai_resume_match
    ports:
      - "3306:3306"
  redis:
    image: redis:7
    ports:
      - "6379:6379"
  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"
```

- [ ] **Step 2: 创建 README**

```markdown
# 基于 RAG 的智能简历与岗位匹配系统

这是一个后端主导的 AI 应用项目。系统支持上传简历和岗位 JD，通过文档解析、JD 标签提取、本地向量检索、RAG 上下文组装和大模型分析生成岗位匹配报告。

## 技术栈

Java 21, Spring Boot 3, MySQL, Redis, RabbitMQ, PDFBox, Apache POI, local hashing embedding, cosine similarity, OpenAI-compatible API, Docker Compose。

## 启动

```bash
docker compose up -d
$env:AI_API_KEY="your-api-key"
mvn spring-boot:run
```

## 核心接口

- `POST /api/resumes` 上传 PDF/DOCX 简历。
- `POST /api/jobs` 提交岗位 JD。
- `POST /api/analysis` 创建异步分析任务。
- `GET /api/analysis/{taskId}/report` 查询匹配报告。

## 简历写法

项目名称：基于 RAG 的智能简历与岗位匹配系统

项目简介：该项目面向求职场景，支持用户上传简历和岗位 JD，基于文档解析、本地向量检索和大模型分析生成岗位匹配报告。系统围绕简历结构化、JD 能力标签提取、异步分析任务和结果缓存进行设计，帮助用户获得匹配评分、技能差距、项目优化建议和模拟面试题。
```

- [ ] **Step 3: 运行全部测试**

Run: `mvn test`

Expected: `BUILD SUCCESS`

- [ ] **Step 4: Commit**

```bash
git add docker-compose.yml README.md
git commit -m "docs: add local setup and project summary"
```

---

## 自检

- Spec coverage: 计划覆盖后端骨架、领域模型、文档解析、JD 分析、本地向量检索、RAG prompt、AI 客户端、异步任务、REST API、本地环境和 README。
- Placeholder scan: 本计划不包含未决占位内容或空泛实现指令。
- Type consistency: `Resume`、`JobDescription`、`AnalysisTask`、`MatchReport`、`AnalysisService`、`RabbitConfig`、`EmbeddingClient`、`InMemoryVectorStore`、`RagContextBuilder` 在各任务中的包名和方法名保持一致。
