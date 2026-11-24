# 当前状态

## Config-Plan03 · 2026-11-24

审查基线为 `master@b546f7b6e402c2de4c3ad82323a592f509770505`。本轮生产代码、测试和 CI 位于 `928f39e25c40a8bec3e2932a3c123116876eac5f`。交付包括后续文档提交。查询最终提交的 Actions 可取得对应的 H2 和 MySQL 8.4 两项作业。[工作范围](https://github.com/MyKr-YSteinsK/config-center/issues/2) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/928f39e25c40a8bec3e2932a3c123116876eac5f) · [CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

| 验收面 | 实际读回 |
| --- | --- |
| CLI 传输 | 六组参数逐组经过配置、Watch、开关评估的 builder 和真实 loopback transport。接收端解码一次得到原值，涵盖加号、空格、中文、百分号和字面量 `%2B`。MySQL 实包上，CLI 的 `a+b` 与 HTTP 的 `a%2Bb` 返回完全相同的评估数据，`enabled=true`、`bucket=-1`。同一缓存第二次运行 `cacheHit=1, etag304=1`。运行手册的 PowerShell 冒烟另在新 H2 进程复演通过。 |
| 回滚与事务 | 两类 HTTP 回滚保留缺省、空串、空白、中文、满长 ASCII/中文及 250 个补充字符的原表示。501 返回 400/4001，五张业务表内容保持一致。两类写入在同一测试事务内 flush 后注入异常，退出事务独立查询，当前行、完整历史和 revision 回到原值，未调用 Watch 通知器。 |
| 条件读取 | 空列表与非空列表都有表示。独立 `*`、同一弱标签、等价强标签和包含匹配项的列表均返回空 body 的 304，并携带 ETag 和 trace 响应头。不匹配返回当前 200 envelope，非法参数返回 400。按单一有序快照计签，响应字段变化或重建后内容变化均使旧标签失效。 |
| 全量快测 | Windows、JDK 17.0.12、Maven Wrapper 3.9.16。`clean verify` 服务端 85 项、客户端 44 项，零失败、零错误、零跳过。修复前的回归在同一环境复现了三项问题。 |
| MySQL | 本机 Docker 引擎不可用。使用独立数据目录、loopback 端口、MySQL Community 8.0.46 和专用非 root 账户，在 `config_center_it` 跑完 `-Pmysql-it verify`。`MysqlPersistenceIT` 6 项、`MysqlV2UpgradeIT` 1 项均通过。真实 V2 数据升至 V3 后，两列可空且宽 600，五张业务表旧行、索引定义、原迁移校验和与 revision 保全。再 migrate 为 0，Hibernate validate 通过。两类回滚均读回 19 位目标版本加 500 长度原因的 533 长度表示。 |
| 新旧实包 | 本轮服务端 JAR SHA-256 为 `47bb894d97177602c48ad68b78217fb15a4152f262d6b9ae71d6f582c51781bd`。新 JAR 经 MySQL HTTP 从 v2 回滚到 v1 快照并生成 v3，配置与开关的 500 字中文原因逐字相符。用 `b546f7b` 旧 JAR 接同一 V3，Flyway 提示数据库版本较新，应用成功启动，两类历史 v3/v2/v1 可读，旧 API 的 500 字回滚均生成 v4。 |

复现命令、数据库边界与构建回退见 [运行手册](RUNBOOK.md)。构建文件名沿用 `1.0.0`，上述哈希标识本机实际运行的 JAR。历史标签 `v1.0.0` 指向 `0bce90c`，版本与发布另行决定。

## 后续工作

下一轮先处理 Java CLI 的 Watch 故障恢复。收到更新后，刷新可能回退陈旧缓存；需要区分已观察 revision 与已应用配置，在服务端没有新写入时也能等到断路器恢复并补取。随后验证 MySQL 排序规则与进程内身份匹配、写入竞争及结果核对，再推进按调用链重构、诊断与单仓演示。前端、SDK 和跨项目消费保留在 [升级路线](../项目源文件/UPGRADE_ROADMAP.md)。接管基线的历史整理及本地待清理区见 [历史整理](HISTORY.md)。
