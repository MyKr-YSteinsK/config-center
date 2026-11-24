# config-center｜开发入口

本仓库以可运行的 Java 17 / Spring Boot 3 配置中心和 Feature Flag 服务展示后端工程能力。现行基线为单实例服务端、Java CLI、H2 本地与测试路径、MySQL + Flyway 持久化路径。

## 恢复工作

- 先读 [目标与兼容约束](docs/project/PROJECT_BRIEF.md) 和 [当前状态](docs/project/CURRENT_STATE.md)。按任务读取 [决定](docs/project/DECISIONS.md)、[运行手册](docs/project/RUNBOOK.md) 或 [架构地图](docs/project-map.md)。
- 普通任务可直接在 Codex 执行。正式 GPT Project 交接优先使用已授权的目标仓库 Issue，取得完整正文后核对范围和验收。公开 Issue 只包含可公开信息。项目资料及人工同步入口在 [GPT Project 交接](docs/项目源文件/00-START_HERE.md)。
- 在授权内自主实施和验证，实质状态与长期决定写回各自文件。后续升级以 [待审提案](docs/项目源文件/UPGRADE_ROADMAP.md) 为入口，由用户选择后单独实施。`docs/delete/` 是本机待清理区。

## 兼容与数据边界

- 维护已有 Java / Maven 入口、API 路径、JSON 字段与错误语义。配置当前行、历史和命名空间 revision 在同一事务内写入，Watch 在提交后唤醒。两类业务历史均追加写入，回滚创建新版本。
- 修改 Watch、客户端重试 / 缓存、JPA / Flyway 或 Compose 时，沿完整调用链核对兼容回归。MySQL V1 至 V3 是已应用迁移。后续 schema 变更从 V4 开始追加。
- 运行时保持主机 loopback 边界。真实凭证只存在于私有配置或环境，本地 API key 和诊断接口遵循现有使用范围。保留主 Compose 数据卷，MySQL 测试使用隔离的 `config_center_it`。数据库账户使用专用非 root 用户。
- 涉及新公开接口或网络暴露、破坏性数据操作、变更既有兼容契约、跨项目写入、发布身份或标签，由用户决定后执行。

## 验证与同步

- 使用 JDK 17 与 Maven Wrapper。`./mvnw -q -B clean verify` 运行两个模块的 H2/客户端测试。涉及 MySQL 的改动用独立数据库执行 `./mvnw -q -B -Pmysql-it verify` 或核对对应的远端 CI。Windows 用 `.\mvnw.cmd`。细节见 [运行手册](docs/project/RUNBOOK.md)。
- 正常开发任务按 D-011，在必要验收及 diff、凭证、迁移、版本检查后提交，并快进推送到当前分支已配置上游。核对实际远端 SHA。没有上游、非快进或认证/网络受阻时，报告该交付缺口。创建其他远端分支、改 remote、强推、改写历史、移标签及部署/发布须单独授权。
- 回执给出关键变更、实际验收、提交 SHA、推送目标与本地/远端同步状态。
