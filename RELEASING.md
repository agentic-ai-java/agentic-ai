# 发布 ARGI 2.1.0-RC1

发布范围为 `io.github.agentic-ai-java` 下的父 POM、BOM、Graph Core、Agent Framework、Studio 和两个 Starter，共七个 Maven 模块。GitHub 标签为 `v2.1.0-RC1`，Release 标记为预发布。

## 准备与验证

本任务按用户授权通过 GitHub CI 构建和验证，不执行本地构建或测试。Build and Test 使用 `release` profile 生成未签名候选制品，校验 flatten 后的坐标、版本、BOM 管理项、源码与 Javadoc jar、Java 17 字节码及 Studio 页面入包，并保存为 `release-candidate` artifact。

发布说明使用 [RELEASE_NOTES.md](RELEASE_NOTES.md)。普通 push 不触发发布。按用户最新授权，发布流程不再等待全量 CI；发布 workflow 自行执行快速发布工具回归、构建、签名和 ZIP 校验，标签和草稿必须指向同一提交。Java 17 全量测试最近通过于 `f8c51b35d4d5b6bc0c674835b212d3545eac2f27`，后续修复仅调整发布脚本与流程。

## 发布凭据

先在 Sonatype Central Portal 核实账号拥有 `io.github.agentic-ai-java` namespace 的发布权限。GitHub 组织名本身不能替代 Central 的 namespace 授权。

在仓库 Actions secrets 中配置以下名称，或使用对本仓库开放的组织级 secrets：

| 名称 | 内容 |
| --- | --- |
| `CENTRAL_USERNAME` | Central Portal 用户令牌的用户名部分 |
| `CENTRAL_PASSWORD` | 同一用户令牌的密码部分 |
| `GPG_PRIVATE_KEY` | 用于制品签名的 ASCII armored 私钥 |
| `GPG_PASSPHRASE` | 私钥口令；无口令密钥可留空 |

签名公钥需能按 [Central 要求](https://central.sonatype.org/publish/requirements/gpg/) 查询。凭据仅在 GitHub 的 secrets 界面配置，不提交到仓库或粘贴到聊天中。

## 发布顺序与窗口

割接窗口从经确认后手动启动 Release workflow 开始，到 Central 发布成功且 GitHub 预发布公开为止；发布期间不移动标签、不替换同版本制品。候选提交、CI 链接和签名发布记录归档在 GitHub Actions 与 Release 中。

1. 确认标签与 GitHub 预发布草稿指向同一候选提交。发布期间不重复运行全量 CI。
2. 配置凭据并确认 namespace 授权。
3. 手动运行 `.github/workflows/release.yml`，输入 `v2.1.0-RC1`，保持默认 `publish=false`，使用 Maven `verify` 生成签名制品，再由 `verify-release-artifacts.py --signed --create-bundle` 按 Maven 仓库布局生成 ZIP，校验制品路径、内容和校验和，不上传 Central，并归档签名制品。失败时也尝试归档发布包。
4. 签名验证通过并获得发布确认后，以同一标签再次运行 workflow，设置 `publish=true`。
5. workflow 复核标签、版本和草稿目标，运行快速发布工具回归，以 JDK 17 重建 UI，生成并签名 Maven 制品，校验 ZIP 后由 `publish-central-bundle.py` 使用 Central 官方 API 上传同一发布包，设置 `publishingType=AUTOMATIC`。
6. 脚本归档 deployment ID 和状态到 `target/central-publishing/deployment.json`，等待 Central 返回 `PUBLISHED`，再将对应 GitHub Release 草稿公开为预发布。校验失败、网络异常或等待超时会终止流程；记录存在时仅继续查询状态，不重复上传。
7. 从 Maven Central 检查七个模块的 POM、jar、源码、Javadoc 和签名，并以消费项目验证新坐标可解析。

根 POM 与独立 BOM 的 `release` profile 保留 Central 插件配置；手动执行 `deploy` 将实际上传并公开 Maven 制品。GitHub 发布流程使用官方 ZIP API，默认 `publish=false` 不执行上传或公开发布。

发布使用 wrapper 固定的 Maven 3.9.16。RC1 首次上传使用 Maven 3.10.0，Central 返回七个模块目录存在无配套 POM 的内容；Central 插件 0.11.0 只清理特定文件名的 staging metadata，且 `skipPublishing=true` 实际跳过制品收集，无法按文档描述生成 ZIP。发布流程改用 Python 标准库打包已验证制品和签名，明确限定文件清单，禁止多余的仓库 metadata；上传使用官方 API，凭据仅在进程内存中处理。普通 CI 校验未签名 ZIP，发布 CI 校验签名 ZIP。该修复无迁移，直接替换构建配置；公开后的 Maven 版本不能覆盖或撤回。

## 迁移与回滚

消费方先预览 Core 坐标迁移，再写入；随后更新版本并审核 POM 差异：

```shell
python3 tools/scripts/migrate-maven-coordinates.py /path/to/application/pom.xml
python3 tools/scripts/migrate-maven-coordinates.py --write /path/to/application/pom.xml
```

脚本只迁移七个 Core 制品的父 POM 和依赖，外部 Extensions 坐标保持原样。应用需恢复时，用 `pom.xml.before-argi-rc1` 还原 POM，并恢复原版本及相应业务数据备份。检查点迁移须使用实际 saver 对应的迁移能力，不提供跨存储的统一改写脚本。

发布前可以撤销发布准备提交、移除草稿并恢复本地备份。Central 公开发布后，版本不能覆盖或撤回：停止推荐该 RC，保留原制品和标签，通过新 RC 版本修复。若 Central 已成功而 GitHub 发布失败，仅补发布 GitHub Release，不重复上传同版本制品。Central 状态不明确时，先核查 Portal 中的 deployment 与公开制品，再决定是否重试。

## 配置依据

2026-10-08 查阅并采用以下官方文档：

- [Sonatype Central Maven 插件](https://central.sonatype.org/publish/publish-portal-maven/)：签名制品、自动发布及等待 `published` 状态。
- [Sonatype Central 发布 API](https://central.sonatype.org/publish/publish-portal-api/)：Maven 布局 ZIP、Bearer 用户令牌、multipart 上传、自动发布与 deployment 状态查询。
- [Maven GPG 插件](https://maven.apache.org/plugins/maven-gpg-plugin/usage.html)：使用环境变量传递口令。
- [actions/setup-java](https://github.com/actions/setup-java/tree/v4)：JDK 17、`central` server 凭据与 GPG key 导入。
