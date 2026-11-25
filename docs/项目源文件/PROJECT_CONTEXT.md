# config-center｜接手快照

日期 2026-11-25。适用仓库 `MyKr-YSteinsK/config-center`，分支 `master`。Config-Plan07 审查基线 `5e1353811cceb76d5effb164342ccf5b9e822f8b`。源码和验证证据见同一交付的 `docs/project/CURRENT_STATE.md`。

## 目标和阶段

把项目维护成可以凭请求、历史和测试证据讲解的 Java 后端作品。所有者已选择先推进单仓。Config-Plan03 修复 CLI 编码、回滚历史扩列和条件请求。Config-Plan04 使客户端在收到变更却暂时取不到新配置时，先保留待办，等连接恢复后补取。持续失败会明确退出。Config-Plan05 用真实数据库证明同版本竞争只留一笔成功，两个不同键可同时写入。已验证保存成功后断连时可独立核对目标效果，历史的唯一归因仍待决定。[Config-Plan03](https://github.com/MyKr-YSteinsK/config-center/issues/2) · [Config-Plan04](https://github.com/MyKr-YSteinsK/config-center/issues/3) · [Config-Plan05](https://github.com/MyKr-YSteinsK/config-center/issues/4)

Config-Plan06 统一为精确名称。以前共用同一条数据的不同拼写现在可以分别创建。V4 把旧历史关联到当前行，保留原来的名字和内容，混合 scope 默认停止等待审查。已在隔离 MySQL 验证旧库恢复、再升级与 CLI 新缓存切换。[Config-Plan06](https://github.com/MyKr-YSteinsK/config-center/issues/5)

Config-Plan07 为四种保存和回滚提供可选请求编号。保存、历史、配置 revision 和成功回执一次提交。即使回信丢失或服务重启，获授权的调用方可用原编号和原请求取回当时的结果。无编号调用继续用原流程。[Config-Plan07](https://github.com/MyKr-YSteinsK/config-center/issues/6)

## 已实现能力

| 模块 | 职责 |
| --- | --- |
| Spring Boot 服务端 | 按精确 `app/env/key` 或 `app/env/name` 读写。精确写授权、历史归属与回滚、弱 ETag 和按精确 scope 的 Watch。Feature Flag 按关闭、allowlist、稳定分桶顺序给出结果。 |
| Java CLI | HTTP 超时、retry/backoff、断路器、ETag/304、JSON 磁盘缓存、受限瞬时失败回退和有限 Watch 恢复。新缓存名为 `.config-center-client-cache-exact-v1.json`，需要新服务端有效 200 才建立条目。 |
| 持久化 | H2 快测和本地体验。MySQL 使用 Flyway V1–V5、Hibernate validate 及 `utf8mb4_0900_bin` 精确身份列。两类历史以 `owner_id` 归属。V5 回执按精确 scope 和二进制编号唯一，保留版本化意图摘要和原业务响应。 |
| 运维 | loopback 默认值、写入 API key、进程内有界限流和 Watch 等待队列、traceId、Actuator/Micrometer/Prometheus。 |

接管中的三项局部整理统一 Feature Flag 判定来源、弱 ETag 的十六进制输出和配置响应/缓存校验。业务版本、JPA 乐观锁与命名空间 revision 各有用途。配置写入产生历史和 revision，通知在提交后发生。两类回滚都生成下一业务版本。CLI 从 0 开始订阅，首个观察后也需读取。有效 200 或用具体 ETag 验证的 304 才能确认先前观察到的 revision。旧缓存回退保留待同步，默认 30 秒恢复调度窗口、至少 1 秒间隔。观察到 revision 变小会重同步。若重建后 revision 已追平或超过旧游标，现有协议无法区分。

## 保护边界

Java 17、Spring Boot 3 和 Maven 双模块入口。保留已有 API 与 envelope、精确授权、owner 历史、回滚新版本以及提交后 Watch。MySQL V1 至 V5 固定，后续从 V6 追加。真实授权、回执、归属映射与业务配置留在私有位置。故障恢复只用隔离的 `config_center_it` 与合成数据。备份和恢复须覆盖业务与回执。当前读接口与诊断面向本机。 `v1.0.0` 历史标签与当前源码构建标识存在差异，等待发布决定。

## 运行、核验和恢复

- 在仓库根用 JDK 17 和 Maven Wrapper，Windows 为 `.\mvnw.cmd -q -B clean verify`，Unix 为 `./mvnw -q -B clean verify`。H2 不依赖 Docker。Config-Plan06 使用 JDK 17.0.12、Maven Wrapper 3.9.16 与独立的 MySQL 8.0.46。
- H2 启动使用 `java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local`，默认 `127.0.0.1:8080`。详细命令、数据归属和隔离 MySQL 步骤在仓库 README 和 `docs/project/RUNBOOK.md`。
- Config-Plan03 已在本机独立 MySQL 8.0.46 使用 `config_center_it` 与非 root 账户。`-Pmysql-it verify` 跑过 6 项常规持久层及 1 项真实 V2 升级测试，旧行、唯一性、revision 和校验和保全，两列 `VARCHAR(600) NULL` 可容纳两种回滚的 533 长度历史表示。旧 `b546f7b` 构建也能在 V3 上启动并读写两类历史。
- Config-Plan03 的 loopback 实包验证满长回滚、空/非空列表的 `*` 以及加号白名单。CLI 与直接 HTTP 同样返回 `userId=a+b`、`enabled=true`、`bucket=-1`，二次读取命中 304。本机 JAR SHA-256 为 `47bb894d97177602c48ad68b78217fb15a4152f262d6b9ae71d6f582c51781bd`。
- Config-Plan04 用本机 H2 实包与真实 Java CLI 验证一次写入、503、断路器开放、自动补取并从新游标订阅；验证 H2 重启后读取空列表再追上写入，以及持续 503、拒绝连接和无响应在有界调度后失败退出。复现见 `docs/project/RUNBOOK.md`。
- Config-Plan05 用不同 MySQL 连接和测试屏障验证同版本、同键首次创建、同 namespace 多键、回滚竞争、flush 与通知。别名首条 revision 死锁的 HTTP 500 已修正为 409/4091，数据与通知仍以提交为界。
- V3 的 `utf8mb4_unicode_ci` 在样本上折叠大小写、重音和尾空格。V4 用 `utf8mb4_0900_bin` 与 Java 对齐，当前身份、写授权和 Watch 精确匹配。历史查找按当前 ID，原历史拼写仅用于追溯。保留原当前旗标名和稳定分桶算法。
- 旧库先停止写入、备份并验证恢复，再用实际授权配置运行 `--check-identity-migration`。如需决策，只能在对应旧组中按最新指纹选定已观察且有授权的目标 scope。实测 MySQL 的中途 DDL 失败可留下部分列，按完整 V3 备份恢复到另一库后重新升级。旧 CLI 离线数据仍存在，新的 CLI 应先连接 V4 做有效 200。详见 `docs/project/IDENTITY_MIGRATION.md` 和 `docs/project/RUNBOOK.md`。
- 实包断连演练分别记录代理转发真相、独立 HTTP 当前/历史/revision、MySQL 命令行读回。目标效果存在时停止写入。历史显示曾经保存的值，trace、operator 和 reason 缺乏唯一的持久请求归因。详见同一提交的 `docs/project/WRITE_CONSISTENCY.md` 与 `docs/project/RUNBOOK.md`。
- 对编号请求，原意图重放返回已存 data 和本次 trace。换操作或正文使用同一编号为 409。每次都重新授权，写入失败可用原编号再试。V5 实包在真实 V4 样本库升级，验证保存成功后断连、零转发、后写覆盖和换进程重放。成功回执持续保留，旧服务会忽略编号，升级需停旧写入者。见 `docs/project/WRITE_RECEIPTS.md`。
- [对应 GitHub Actions](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml) 按实际交付 SHA 查看 `build-test` 与 MySQL 8.4 的 `mysql-integration`。`CURRENT_STATE.md` 记录源码与验证环境，历史接管记录在 `HISTORY.md`。按 D-011 快进推送并核对远端。

## 后续进入点

下一份任务优先审查架构重构、代码精简与工程交付。沿四条写入链、Watch 与 CLI 梳理职责，以现有兼容与实包场景作为保护网。新增前置条件、回执清理、跨项目消费、SDK 和前端均是候选。详细验收和依赖见 `UPGRADE_ROADMAP.md`。
