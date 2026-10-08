# CI 依赖与许可证检查修复

## 失败证据

2026-10-08 查阅主分支提交 `6324f53d7b6337cc9238a8cc5314dfef75f3548b` 的 GitHub Actions 记录，定位到以下失败：

- [Build and Test](https://github.com/agentic-ai-java/argi/actions/runs/37613208744)：兼容性配置校验脚本导入 `yaml` 时失败；其余前置构建、测试及兼容性矩阵通过，最终打包因前置检查失败而跳过。
- [License Check](https://github.com/agentic-ai-java/argi/actions/runs/37613208799)：`CloneStateKeyStrategyPollutionTest.java` 缺少 Apache 2.0 许可证头。

后续 [PR 检查日志](https://github.com/agentic-ai-java/argi/actions/runs/37654777430) 进一步确认：runner 已提供 `yamllint` 命令，`make tools` 因此跳过安装；但 `verify-compatibility-wiring.sh` 使用的 `python3` 环境缺少 PyYAML。命令存在不能证明另一个 Python 环境具备所需模块。

## 修复与验收

- `tools/make/tools.mk` 使用 `python3 -m pip install PyYAML==6.0.2` 显式安装固定版本，使安装和校验使用同一解释器；保留原有 `yamllint` 安装逻辑。
- `argi-graph-core/src/test/java/io/github/agentic/ai/graph/CloneStateKeyStrategyPollutionTest.java` 补齐许可证头。
- 无迁移，直接替换；不改变 Java 运行时行为或公开 API。

用户明确要求不运行本地构建或测试，直接推送 GitHub 并观察 CI。该授权覆盖本任务的全局远端 CI 禁用约定及本地验收要求。验收依据为修复提交对应的 GitHub Actions 实际结果，不能用已有提交的成功结果替代。需检查 Build and Test、Linter、License Check、Secrets Check 四个 workflow，并继续处理任何新暴露的失败。

本次未增加依赖漏洞、覆盖率或性能验收机制，不将现有 CI 结果表述为这些指标达标。变更限于开发工具依赖和测试文件头；回滚可撤销修复提交。配置修改前的恢复副本保存在本地忽略目录 `target/ci-fix-backups.rtK8ne/`。
