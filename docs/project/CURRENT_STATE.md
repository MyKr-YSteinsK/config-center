# 当前状态

## 2026-11-23 维护接管

仓库 `MyKr-YSteinsK/config-center`，分支 `master`。接管前本地和远端均为 `0258b9cef02ea74a40989d035c79256c776409c0`。本轮按用户在对话中授权的范围整理现行项目入口、审查、归档和实施三处局部重构。项目孵化器使用 `AI-Workflow-Assets` 的 `750c696`，GPT Project 设置见 [项目交接](../项目源文件/00-START_HERE.md)。

- 聚合 Feature Flag 判定与解释，保留关闭、allowlist、灰度优先级和原分桶输出。测试覆盖服务入口的三类解释。
- 使用 Java 17 `HexFormat` 生成同格式弱 ETag，并锁定已知序列的原始 ETag 值。
- HTTP 配置响应和磁盘缓存复用结构校验，保留缓存命中、304 和瞬时失败回退条件。

## 当轮验证

| 环境与命令 | 结果 |
| --- | --- |
| Windows、JDK 17.0.12、Maven Wrapper 3.9.16，`.\mvnw.cmd -q -B clean verify` | 服务端 79 项、客户端 38 项通过，无失败或跳过。两个模块生成 JaCoCo 报告。测试 JVM 的临时目录指向忽略区。 |
| 本机 loopback，运行本次打包的 JAR，`local` H2 profile | 用合成数据验证写入、空 body 的 304、已经注册的 Watch 唤醒及 traceId、expectedVersion 冲突、回滚到新版本、历史顺序和 allowlist。 |
| 本机实际 Java CLI，`spring-boot:run` + 隔离用户缓存 | 首次 HTTP 读取及 Feature Flag 评估通过，第二次读取命中 304。带加号的 userId 另见审查结论。 |
| GitHub Actions H2、MySQL 8.4 | 接管前基线 `0258b9c` 的 `build-test` 与 `mysql-integration` 已通过。接管后的结果按实际提交核对 [工作流](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)。 [基线运行](https://github.com/MyKr-YSteinsK/config-center/actions/runs/34239707152) |
| 本机 MySQL/Compose | Docker Desktop Linux 引擎管道不可用，本轮未在本机运行或重置 MySQL/Compose。远端 MySQL 作业验证隔离的数据库与应用集成。 |

## 风险与待决

- 已复现 CLI 会二次编码 userId 中的加号。对有表示的配置列表，实际 `If-None-Match: *` 返回 200。H2 上配置回滚用 500 字符的合法输入会因写入历史时拼接前缀而返回 409，读回确认仍是原版本和原历史。三者列入 [升级提案](../项目源文件/UPGRADE_ROADMAP.md)。
- MySQL 排序规则与进程中的精确字符串键可能对命名空间别名产生不同分组，仍需独立复核。Watch 通知、等待容量、timeout、traceId 和事务可见性须联动回归。单实例限流和 Watch，以及 CLI 明文缓存，限定了当前适用范围。
- 构建路径仍用 `1.0.0`，旧标签 `v1.0.0` 的目标为 `0bce90ce8ccfe512edc04355ded2789f5ff49524`。版本与发布决策待用户确认。
- 前端、SDK、跨项目联动及可靠变更的新契约处在待审阶段。RunProof 中的既有适配有旧提交与构建 pin，测试新候选需要新的证据。用户可从 [升级提案](../项目源文件/UPGRADE_ROADMAP.md) 选择下一条主线。

恢复与数据操作见 [运行手册](RUNBOOK.md)。历史证据和本机手动清理位置见 [历史整理](HISTORY.md)。
