# 资料索引

| 内容 | 维护入口 |
| --- | --- |
| 稳定目标、当前功能范围、兼容约束 | [PROJECT_BRIEF.md](PROJECT_BRIEF.md) |
| 已接受的长期决定、D-011 和待定版本 | [DECISIONS.md](DECISIONS.md) |
| 当前基线、证据与维护风险 | [CURRENT_STATE.md](CURRENT_STATE.md) |
| 环境、数据归属、恢复与排障 | [RUNBOOK.md](RUNBOOK.md) |
| 历史里程碑与本地待清理内容 | [HISTORY.md](HISTORY.md) |
| 干净检出的公开使用说明与 API 请求 | [README.md](../../README.md)、[examples.http](../../examples.http) |
| Java 模块、组件、协议和数据路径 | [架构地图](../project-map.md) |
| Codex 入口、GPT Project 手动同步和后续选项 | [AGENTS.md](../../AGENTS.md)、[项目源文件入口](../项目源文件/00-START_HERE.md) |
| 编译、验证和依赖 | [父 POM](../../pom.xml)、[Wrapper 版本](../../.mvn/wrapper/maven-wrapper.properties)、[CI](../../.github/workflows/ci.yml) |
| 持久 schema 与本地运行拓扑 | [Flyway 迁移](../../config-center-server/src/main/resources/db/migration/)、[主 Compose](../../compose.yml)、[MySQL 测试 overlay](../../compose.mysql-it.yml)、[Dockerfile](../../config-center-server/Dockerfile) |

当前实现依据代码、配置和对应版本的运行证据判断。项目附件依据 [同步入口](../项目源文件/00-START_HERE.md) 使用。临时 Plan、旧电脑迁移报告和 `docs/delete/` 中的材料用于回溯。
