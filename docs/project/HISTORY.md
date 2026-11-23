# 历史开发与归档

保留形成当前能力、限制或证据的节点。详细旧说明可以通过 [接管前的仓库快照](https://github.com/MyKr-YSteinsK/config-center/tree/0258b9cef02ea74a40989d035c79256c776409c0) 回看。

| 演化 | 有效结论 | Git 线索 |
| --- | --- | --- |
| Spring Boot + Maven 学习原型 | 服务端、Java CLI、统一响应、traceId 和配置读写成为主入口。 | `35153ed`、`2460308` |
| 版本化和可靠客户端 | 业务版本、历史和回滚、ETag/304、网络重试和磁盘缓存成为可演示链路。 | `05332b3`、`cef7da2`、`e845566`、`52e98d3` |
| Watch 和并发加固 | 持久命名空间 revision，事务提交后通知，进程内首行同步和等待容量。未来游标须等到更大的 revision。 | `fb8a103`、`f2e40cd`、`0716bd5`、`67f39fe`、`3eb3ca4` |
| 可运行的持久化基线 | 显式 MySQL profile、Flyway、隔离的 MySQL 回归和 Compose。历史演练验证过空库与本地保卷重启。 | `8cae914`、`ee72ffb`、`a38d5e8`、`da0b674`、`3ff9531`、`e8114a1` |
| 有界 API 和迁移接管 | 历史分页、allowlist 复制、loopback 绑定，以及新版 Windows Maven Wrapper。D-011 保存日常快进交付方式。 | `c75c0f6`、`57abe7d`、`14c2514`、`987ea20`、`0258b9c` |

早期 MySQL 8.0.46 手工演练记录了空库迁移、JPA 校验、API 写入/历史/回滚和重复启动。Phase 9E 的 Compose 记录使用 MySQL 8.4.10，覆盖从空卷启动、保卷重启和隔离的删卷重建。 [旧架构记录](https://github.com/MyKr-YSteinsK/config-center/blob/0258b9cef02ea74a40989d035c79256c776409c0/docs/project-map.md#8-runtime-configuration-baseline)

2026-09-08 的迁移记录保留了 Windows H2 测试、克隆和 Wrapper 探测的历史证据。该会话的 Docker 引擎不可用，仓库同提交的 Actions 另有独立 H2、MySQL 8.4 结果。接管后证据从 [当前状态](CURRENT_STATE.md) 查。 [旧状态记录](https://github.com/MyKr-YSteinsK/config-center/blob/0258b9cef02ea74a40989d035c79256c776409c0/docs/project/CURRENT_STATE.md) · [历史 CI](https://github.com/MyKr-YSteinsK/config-center/actions/runs/34239707152)

## 2026-11-23 本机待清理

本机 `docs/delete/2026-11-23/` 供用户核对后手动清理，已加入 Git 忽略规则。

| 位置 | 内容和处理 |
| --- | --- |
| `old-docs/` | 旧换机指南和接管前的根指令、项目目标、决定、状态、资料索引原件。运行与恢复说明已汇总到 [运行手册](RUNBOOK.md)，长期决定和历史证据有各自的有效入口。 |
| `maven/` | 接管前的 server/client `target/`，包含旧 JAR、测试与覆盖率报告。可由对应源码重新构建；两个模块新的 `target/` 来自本次验证。 |
| `repository-root/` | 已移出的 IDE 工程元数据、空 `.agents` 目录和空电脑迁移检出残留目录。 |
| `verification/` | 本次验证日志和临时文件。保留的结论在 [当前状态](CURRENT_STATE.md)。 |

已在历史提交移除的 `dev-plan.md`、`config-center-dev-plan-v2.md`、`config-center-persistent-deployment-plan.md`、`patch-log.md` 由 Git 保管，可用 `git log --all -- <原路径>` 找到。实际 `.env`、外置数据库/卷以及用户目录里的缓存继续由本机或对应工具管理。
