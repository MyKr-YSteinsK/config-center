# config-center｜接手快照

日期 2026-11-23。适用仓库 `MyKr-YSteinsK/config-center`，分支 `master`。以接管前的 `0258b9cef02ea74a40989d035c79256c776409c0` 为审查起点。上传版与同一提交中的 `docs/project/CURRENT_STATE.md` 配合使用。

## 目标和阶段

把历史学习项目维护成便于讲解、可复查证据的 Java 后端作品。接管阶段做兼容性小重构、可运行基线和资料整理。用户尚未确认下一阶段的升级路线。 `UPGRADE_ROADMAP.md` 讨论具体决策、后端改造、界面和联动。

## 已实现能力

| 模块 | 职责 |
| --- | --- |
| Spring Boot 服务端 | 按 `app/env` 读写配置与 Feature Flag、业务版本、历史游标和回滚。配置 GET 生成弱 ETag，Watch 以 namespace revision 长轮询。Feature Flag 按关闭、allowlist、稳定分桶顺序给出结果。 |
| Java CLI | HTTP 超时、retry/backoff、断路器、ETag/304、JSON 磁盘缓存、受限瞬时失败回退，以及 Watch 后重新拉取。 |
| 持久化 | H2 快测和本地体验。MySQL 专用 profile 启动验证必需变量，Flyway V1/V2 迁移后 Hibernate validate。MySQL 使用隔离 schema 和 CI service container 做回归。 |
| 运维 | loopback 默认值、写入 API key、进程内有界限流和 Watch 等待队列、traceId、Actuator/Micrometer/Prometheus。 |

接管中的三项局部整理统一 Feature Flag 判定来源、弱 ETag 的十六进制输出和配置响应/缓存校验。业务版本、JPA 乐观锁与命名空间 revision 各有用途。配置写入产生历史和 revision，通知在提交后发生。两类回滚都生成下一业务版本。原有客户端以验证后的缓存支持 304 和瞬时失败回退。

## 保护边界

Java 17、Spring Boot 3 和 Maven 双模块入口。已有 `/api/configs`、`/api/features` 路径、响应 `code/message/data/traceId` 及其测试。MySQL V1/V2 按已应用迁移处理，未来变更追加新迁移。主 Compose 数据卷和 `.env` 继续各自保管，测试 schema 为 `config_center_it`。当前读接口与诊断面向本机，实际密钥和业务数据不用于展示。 `v1.0.0` 历史标签与当前源码构建标识存在差异，等待发布决定。

## 运行、核验和恢复

- 在仓库根用 JDK 17 和 Maven Wrapper，Windows 为 `.\mvnw.cmd -q -B clean verify`，Unix 为 `./mvnw -q -B clean verify`。H2 不依赖 Docker。本地候选已通过服务端 79、客户端 38 项测试。
- H2 启动使用 `java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local`，默认 `127.0.0.1:8080`。详细命令、数据归属和隔离 MySQL 步骤在仓库 README 和 `docs/project/RUNBOOK.md`。
- `-Pmysql-it verify` 是独立 MySQL 证据。接管前提交对应的 GitHub Actions 两个测试任务均通过。本轮本机 Docker 引擎未启动，当前远端 CI 查看相应 GitHub commit。 [接管前 CI](https://github.com/MyKr-YSteinsK/config-center/actions/runs/34239707152)
- 本轮工作入口是用户直接交给 Codex 的请求。重要事实写回 `CURRENT_STATE.md`，历史节点见 `HISTORY.md`。日常开发经验证按 D-011 快进推送已配置上游。GPT 可直接使用这份快照取得上下文，Codex 从仓库根 `AGENTS.md` 恢复。

## 后续进入点

建议先提高可靠变更的可验证性，再选择一个真实业务消费场景。RunProof 已有钉住旧基线的受控适配代码。ACGN History Analyzer 已有 Spring Boot 与 React 界面，适合评估由配置中心控制的一项开关。是否需要单独的配置发布界面，取决于用户希望投递的角色以及现有业务界面能否充分演示发布过程。完整选项和候选验收详见 `UPGRADE_ROADMAP.md`。
