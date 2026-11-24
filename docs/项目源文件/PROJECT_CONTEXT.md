# config-center｜接手快照

日期 2026-11-24。适用仓库 `MyKr-YSteinsK/config-center`，分支 `master`。Config-Plan03 审查基线 `b546f7b`，实现提交 `928f39e25c40a8bec3e2932a3c123116876eac5f`。上传版与同一提交中的 `docs/project/CURRENT_STATE.md` 配合使用。

## 目标和阶段

把项目维护成可以凭请求、历史和测试证据讲解的 Java 后端作品。所有者已选择先推进单仓。首轮已按 Issue #2 实施 CLI 编码、回滚历史扩列和条件请求三项修复；下一轮优先处理 Watch 故障恢复。[Config-Plan03](https://github.com/MyKr-YSteinsK/config-center/issues/2)

## 已实现能力

| 模块 | 职责 |
| --- | --- |
| Spring Boot 服务端 | 按 `app/env` 读写配置与 Feature Flag、业务版本、历史游标和回滚。配置 GET 生成弱 ETag，Watch 以 namespace revision 长轮询。Feature Flag 按关闭、allowlist、稳定分桶顺序给出结果。 |
| Java CLI | HTTP 超时、retry/backoff、断路器、ETag/304、JSON 磁盘缓存、受限瞬时失败回退，以及 Watch 后重新拉取。 |
| 持久化 | H2 快测和本地体验。MySQL 专用 profile 启动验证必需变量，Flyway V1–V3 迁移后 Hibernate validate。MySQL 使用隔离 schema 和 CI service container 做回归。 |
| 运维 | loopback 默认值、写入 API key、进程内有界限流和 Watch 等待队列、traceId、Actuator/Micrometer/Prometheus。 |

接管中的三项局部整理统一 Feature Flag 判定来源、弱 ETag 的十六进制输出和配置响应/缓存校验。业务版本、JPA 乐观锁与命名空间 revision 各有用途。配置写入产生历史和 revision，通知在提交后发生。两类回滚都生成下一业务版本。原有客户端以验证后的缓存支持 304 和瞬时失败回退。

## 保护边界

Java 17、Spring Boot 3 和 Maven 双模块入口。已有 `/api/configs`、`/api/features` 路径、响应 `code/message/data/traceId` 及其测试。MySQL V1 至 V3 按已应用迁移处理，未来变更追加新迁移。主 Compose 数据卷和 `.env` 继续各自保管，测试 schema 为 `config_center_it`。当前读接口与诊断面向本机，实际密钥和业务数据不用于展示。 `v1.0.0` 历史标签与当前源码构建标识存在差异，等待发布决定。

## 运行、核验和恢复

- 在仓库根用 JDK 17 和 Maven Wrapper，Windows 为 `.\mvnw.cmd -q -B clean verify`，Unix 为 `./mvnw -q -B clean verify`。H2 不依赖 Docker。本轮在 JDK 17.0.12 下通过服务端 85、客户端 44 项测试。
- H2 启动使用 `java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local`，默认 `127.0.0.1:8080`。详细命令、数据归属和隔离 MySQL 步骤在仓库 README 和 `docs/project/RUNBOOK.md`。
- 本机独立 MySQL 8.0.46 使用 `config_center_it` 与非 root 账户。`-Pmysql-it verify` 跑过 6 项常规持久层及 1 项真实 V2 升级测试，旧行、唯一性、revision 和校验和保全，两列 `VARCHAR(600) NULL` 可容纳两种回滚的 533 长度历史表示。旧 `b546f7b` 构建也能在 V3 上启动并读写两类历史。
- 当前 JAR 的 loopback 冒烟验证满长回滚、空/非空列表的 `*` 以及加号白名单。CLI 与直接 HTTP 同样返回 `userId=a+b`、`enabled=true`、`bucket=-1`，二次读取命中 304。本机 JAR SHA-256 为 `47bb894d97177602c48ad68b78217fb15a4152f262d6b9ae71d6f582c51781bd`。
- [对应 GitHub Actions](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml) 按实际交付 SHA 查看 `build-test` 与 MySQL 8.4 的 `mysql-integration`。`CURRENT_STATE.md` 记录源码与验证环境，历史接管记录在 `HISTORY.md`。按 D-011 快进推送并核对远端。

## 后续进入点

下一份任务先区分 Watch 观察到的 revision 和实际应用的配置；刷新失败并用陈旧缓存后，服务端无新写入也要能够补取。继而验证并发与 MySQL 一致性、按调用链重构及可解释性，最后串起单仓演示。跨项目消费、SDK 和前端继续作为候选。详细验收和依赖见 `UPGRADE_ROADMAP.md`。
