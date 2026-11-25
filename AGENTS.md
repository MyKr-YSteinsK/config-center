# config-center｜开发入口

本仓库以可运行的 Java 17 / Spring Boot 3 配置中心和 Feature Flag 服务展示后端工程能力。现行基线为单实例服务端、Java CLI、H2 本地与测试路径、MySQL + Flyway 持久化路径。

## 恢复工作

- 核对工作区、分支、上游与已有改动。先读 [目标与兼容约束](docs/project/PROJECT_BRIEF.md) 和 [当前状态](docs/project/CURRENT_STATE.md)。按任务读取 [决定](docs/project/DECISIONS.md)、[运行手册](docs/project/RUNBOOK.md) 或 [架构地图](docs/project-map.md)。
- 普通任务可直接执行。正式交接优先读取获授权的目标仓库 Issue 完整正文，核对来源、适用基线、写入与操作权限、必要验收。Issue 读不到时，使用用户提供的可读完整正文或获授权的私有文件。仍缺必要依据时，只暂停依赖该依据的动作。[交接约定](docs/project/DECISIONS.md#交接与恢复) · [GPT Project 同步](docs/项目源文件/00-START_HERE.md)
- 多任务汇合时，按有效来源、编号及适用修订核对依赖、重叠写入和共享资源。可共用的核验做一次，证据分别对应各任务的验收。实质冲突只暂停受影响的动作。
- 在授权内自主实施和验证，实质状态与长期决定写回各自文件。后续升级以 [待审提案](docs/项目源文件/UPGRADE_ROADMAP.md) 为入口，由用户选择后单独实施。`docs/delete/` 是本机待清理区。

## 兼容与数据边界

- 维护已有 Java / Maven 入口、API 路径、JSON 字段与错误语义。配置当前行、历史和命名空间 revision 在同一事务内写入，Watch 在提交后唤醒。两类业务历史均追加写入，回滚创建新版本。
- 名称和写授权按精确字符串匹配。两类历史通过当前对象主键归属，历史里的原拼写用于追溯。升级存量 MySQL 时先做 [V4 预检与归属审查](docs/project/IDENTITY_MIGRATION.md)。演练备份、失败和恢复只用隔离的合成数据。
- 四个写入入口支持可选请求编号。保存、历史、配置 revision 和成功回执同事务提交，重放前重新授权。备份与恢复保持业务和回执一致。[写入回执合同](docs/project/WRITE_RECEIPTS.md)
- 修改 Watch、客户端重试 / 缓存、JPA / Flyway 或 Compose 时，沿完整调用链核对兼容回归。MySQL V1 至 V5 是已交付迁移。后续 schema 变更从 V6 开始追加。
- 运行时保持主机 loopback 边界。真实凭证只存在于私有配置或环境，本地 API key 和诊断接口遵循现有使用范围。保留主 Compose 数据卷，MySQL 测试使用隔离的 `config_center_it`。数据库账户使用专用非 root 用户。
- 涉及新公开接口或网络暴露、破坏性数据操作、变更既有兼容契约、跨项目写入、发布身份或标签，由用户决定后执行。

## 沟通与回执

- 在规划、预览路线、下发 Plan 和开始执行时，先用一段通俗话说清遇到的问题、准备改什么，以及使用上会有什么变化。
- 结束时先说明实际改善、遇到的限制和能证明结果的场景，再给验收、技术细节、提交和复现命令。通俗说明以实际验证结果为准。
- 方向已明确且即将开始下一轮规划、实施或关键审查时，读取上游规则或已确认的同源快照，按需在对话末尾建议一次，用户选择档位。暂时读不到时按已核实的合同继续。建议留在对话里。[模型建议规则](https://github.com/MyKr-YSteinsK/ai-workflow-assets/blob/f50e2b1682af21842a730f20c8533cbc3372a72f/skills/frugal-dev-runner/references/model-recommendation.md)

## 验证与同步

- 使用 JDK 17 与 Maven Wrapper。`./mvnw -q -B clean verify` 运行两个模块的 H2/客户端测试。涉及 MySQL 的改动用独立数据库执行 `./mvnw -q -B -Pmysql-it verify` 或核对对应的远端 CI。Windows 用 `.\mvnw.cmd`。细节见 [运行手册](docs/project/RUNBOOK.md)。
- 正常开发任务按 D-011，在必要验收及 diff、凭证、迁移、版本检查后提交，并快进推送到当前分支已配置上游。核对实际远端 SHA。没有上游、非快进或认证/网络受阻时，报告该交付缺口。创建其他远端分支、改 remote、强推、改写历史、合并、变更版本或标签及部署或发布须单独授权。
- 回执给出关键变更、实际验收、提交 SHA、推送目标与本地/远端同步状态。
