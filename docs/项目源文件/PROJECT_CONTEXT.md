# config-center｜接手快照

日期 2026-11-25。适用仓库 `MyKr-YSteinsK/config-center`，分支 `master`。Config-Plan05 审查基线 `eb84b51f67f103373b7999ec601cc708ab205591`，本轮源码见同一交付的 `docs/project/CURRENT_STATE.md`。上传版与同一提交中的 `docs/project/CURRENT_STATE.md` 配合使用。

## 目标和阶段

把项目维护成可以凭请求、历史和测试证据讲解的 Java 后端作品。所有者已选择先推进单仓。Config-Plan03 修复 CLI 编码、回滚历史扩列和条件请求。Config-Plan04 使客户端在收到变更却暂时取不到新配置时，先保留待办，等连接恢复后补取。持续失败会明确退出。Config-Plan05 用真实数据库证明同版本竞争只留一笔成功，两个不同键可同时写入。已验证保存成功后断连时可独立核对目标效果，历史的唯一归因仍待决定。[Config-Plan03](https://github.com/MyKr-YSteinsK/config-center/issues/2) · [Config-Plan04](https://github.com/MyKr-YSteinsK/config-center/issues/3) · [Config-Plan05](https://github.com/MyKr-YSteinsK/config-center/issues/4)

## 已实现能力

| 模块 | 职责 |
| --- | --- |
| Spring Boot 服务端 | 按 `app/env` 读写配置与 Feature Flag、业务版本、历史游标和回滚。配置 GET 生成弱 ETag，Watch 以 namespace revision 长轮询。Feature Flag 按关闭、allowlist、稳定分桶顺序给出结果。 |
| Java CLI | HTTP 超时、retry/backoff、断路器、ETag/304、JSON 磁盘缓存、受限瞬时失败回退，以及在有限 Watch 轮次内确认同步、补取失败的刷新。 |
| 持久化 | H2 快测和本地体验。MySQL 专用 profile 启动验证必需变量，Flyway V1–V3 迁移后 Hibernate validate。MySQL 使用隔离 schema 和 CI service container 做回归。 |
| 运维 | loopback 默认值、写入 API key、进程内有界限流和 Watch 等待队列、traceId、Actuator/Micrometer/Prometheus。 |

接管中的三项局部整理统一 Feature Flag 判定来源、弱 ETag 的十六进制输出和配置响应/缓存校验。业务版本、JPA 乐观锁与命名空间 revision 各有用途。配置写入产生历史和 revision，通知在提交后发生。两类回滚都生成下一业务版本。CLI 从 0 开始订阅，首个观察后也需读取。有效 200 或用具体 ETag 验证的 304 才能确认先前观察到的 revision。旧缓存回退保留待同步，默认 30 秒恢复调度窗口、至少 1 秒间隔。观察到 revision 变小会重同步。若重建后 revision 已追平或超过旧游标，现有协议无法区分。

## 保护边界

Java 17、Spring Boot 3 和 Maven 双模块入口。已有 `/api/configs`、`/api/features` 路径、响应 `code/message/data/traceId` 及其测试。MySQL V1 至 V3 按已应用迁移处理，未来变更追加新迁移。主 Compose 数据卷和 `.env` 继续各自保管，测试 schema 为 `config_center_it`。当前读接口与诊断面向本机，实际密钥和业务数据不用于展示。 `v1.0.0` 历史标签与当前源码构建标识存在差异，等待发布决定。

## 运行、核验和恢复

- 在仓库根用 JDK 17 和 Maven Wrapper，Windows 为 `.\mvnw.cmd -q -B clean verify`，Unix 为 `./mvnw -q -B clean verify`。H2 不依赖 Docker。Config-Plan05 使用 JDK 17.0.12、Maven Wrapper 3.9.16 与独立的 MySQL 8.0.46。
- H2 启动使用 `java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local`，默认 `127.0.0.1:8080`。详细命令、数据归属和隔离 MySQL 步骤在仓库 README 和 `docs/project/RUNBOOK.md`。
- Config-Plan03 已在本机独立 MySQL 8.0.46 使用 `config_center_it` 与非 root 账户。`-Pmysql-it verify` 跑过 6 项常规持久层及 1 项真实 V2 升级测试，旧行、唯一性、revision 和校验和保全，两列 `VARCHAR(600) NULL` 可容纳两种回滚的 533 长度历史表示。旧 `b546f7b` 构建也能在 V3 上启动并读写两类历史。
- Config-Plan03 的 loopback 实包验证满长回滚、空/非空列表的 `*` 以及加号白名单。CLI 与直接 HTTP 同样返回 `userId=a+b`、`enabled=true`、`bucket=-1`，二次读取命中 304。本机 JAR SHA-256 为 `47bb894d97177602c48ad68b78217fb15a4152f262d6b9ae71d6f582c51781bd`。
- Config-Plan04 用本机 H2 实包与真实 Java CLI 验证一次写入、503、断路器开放、自动补取并从新游标订阅；验证 H2 重启后读取空列表再追上写入，以及持续 503、拒绝连接和无响应在有界调度后失败退出。复现见 `docs/project/RUNBOOK.md`。
- Config-Plan05 用不同 MySQL 连接和测试屏障验证同版本、同键首次创建、同 namespace 多键、回滚竞争、flush 与通知。别名首条 revision 死锁的 HTTP 500 已修正为 409/4091，数据与通知仍以提交为界。
- MySQL `utf8mb4_unicode_ci` 在样本上匹配大小写、重音和尾部空格变体，H2 分开。两把精确授权的合成 key 可写 MySQL 的相同数据库身份。已挂起 Watch 可能等到超时，下一次看到新 revision。按查询旗标名分桶使变体得到不同结果。待决定是否建立稳定身份或迁移为精确标识。
- 实包断连演练分别记录代理转发真相、独立 HTTP 当前/历史/revision、MySQL 命令行读回。目标效果存在时停止写入。历史显示曾经保存的值，trace、operator 和 reason 缺乏唯一的持久请求归因。详见同一提交的 `docs/project/WRITE_CONSISTENCY.md` 与 `docs/project/RUNBOOK.md`。
- [对应 GitHub Actions](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml) 按实际交付 SHA 查看 `build-test` 与 MySQL 8.4 的 `mysql-integration`。`CURRENT_STATE.md` 记录源码与验证环境，历史接管记录在 `HISTORY.md`。按 D-011 快进推送并核对远端。

## 后续进入点

下一份任务先根据 Plan05 的实测决定名称身份和授权边界。若实际消费方需要安全重放或唯一归因，再定义事务内请求回执及写入前置条件。明确合同后按调用链重构、补诊断并串起单仓演示。跨项目消费、SDK 和前端继续作为候选。详细验收和依赖见 `UPGRADE_ROADMAP.md`。
