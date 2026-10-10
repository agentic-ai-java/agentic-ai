# ARGI 2.1.0-RC1

ARGI 2.1.0-RC1 是预发布版本，Maven 坐标采用 `io.github.agentic-ai-java:argi-*`，运行环境要求 Java 17 或更高版本。本次发布仅包含 Core；Extensions、Examples 和 Benchmark 分别由独立仓库维护。

## 主要变化

- 采用 ARGI 产品名，提供图工作流、ReAct Agent、多智能体编排、上下文工程和人工介入能力；Java 包名为 `io.github.agentic.ai`，配置前缀为 `argi.*`。
- 增加可选的检查点 revision/CAS 与执行租约契约，在启用对应能力时约束过期执行的检查点写入和工具派发。
- 修复状态克隆和快照覆盖用户注册的 `input` 策略、整数排序跨数值类型比较及 Map 序列化标记处理等问题。
- 改进工具选择预算、调用次数限制、调度失败处理和 Studio 执行与恢复时的状态更新。
- Studio 提供内嵌静态页面，升级前端依赖以修复已报告的依赖漏洞。
- Core 与可选集成拆分；JDBC、Redis、MongoDB 持久化及相关图节点等集成由 [Extensions](https://github.com/agentic-ai-java/argi-extensions) 维护。

## Maven 使用方式

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.github.agentic-ai-java</groupId>
      <artifactId>argi-bom</artifactId>
      <version>2.1.0-RC1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <dependency>
    <groupId>io.github.agentic-ai-java</groupId>
    <artifactId>argi-agent-framework</artifactId>
  </dependency>
</dependencies>
```

## 迁移说明

已有 ARGI Core 消费方需将 `io.github.agentic-ai` 改为 `io.github.agentic-ai-java`，同时更新 BOM 或显式版本至 `2.1.0-RC1`。Java 包名及 `argi.*` 配置前缀不因本次 Maven 坐标变更而变化。可使用 `tools/scripts/migrate-maven-coordinates.py` 迁移 Core 的父 POM 和依赖坐标；脚本默认预览，`--write` 会生成不覆盖旧文件的备份，版本号需另行更新。

历史标签早于 ARGI 命名迁移，升级旧产品时还需核对 Java 包名、公开类名和配置前缀。外部 Extensions 尚需适配新的 Core 坐标，本次不承诺旧 Extensions 制品与此 RC 兼容；不要在同一应用中同时引入新旧坐标下的 Core 制品。

Studio API 需要配置 `argi.agent.studio.execution.auth-token`，并在界面设置 Execution Token。旧版仅按 threadId 保存的线程应按 Studio 模块 README 中的 `migrateLegacyThread` 方法迁移至 app/user/thread 范围。

## 验证范围

候选验收包括 JDK 17 上的 Java 测试、格式、Checkstyle、前端依赖审计、格式、lint、TypeScript 检查、前端测试和静态构建，以及发布 POM、BOM、源码与 Javadoc 制品和 Studio 页面入包检查；通过结果以发布标签对应提交的 GitHub Actions 为准。签名和 Central 端验证在实际发布 workflow 中执行。未验证 JDK 21、外部 Extensions 兼容性、性能、压力、容量、混沌及全项目覆盖率和 Java 依赖漏洞指标。

[完整变更](https://github.com/agentic-ai-java/argi/compare/v2.0.0.0...v2.1.0-RC1)
