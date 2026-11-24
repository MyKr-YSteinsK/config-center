# 当前状态

## Config-Plan04 · 2026-11-24

服务端通知有更新时，如果客户端一时取不到新值，会继续显示旧值并标明待同步。恢复后客户端自己补取，一直失败则提示最后一次确认进度并以失败结束。用真实 H2 JAR 和 Java CLI 演练时，服务端只在旧值确认后写了一次新值，客户端仍成功跟上。

审查基线 `master@1060563b3bc94e658a0c07efe4be23c4dbd1d85e`。实现和回归脚本位于 `0017d7de7c9b6f766ba00b5db5e1ba71a36c9f21`，后续文档提交一并交付。按最终 SHA 查看两项 CI。[Issue #3](https://github.com/MyKr-YSteinsK/config-center/issues/3) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/0017d7de7c9b6f766ba00b5db5e1ba71a36c9f21) · [CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

| 验收面 | 结果 |
| --- | --- |
| 观察、读取与确认 | 首个 Watch 从 0 开始，取得观察后重新读取，包括观察到空库的 revision 0。`confirmedCursor` 最多确认列表读取前的 `observedRevision`。有效 200 和发送了具体缓存验证器的有效 304 可确认。旧缓存、非法 304、协议错误和 4xx（含 429）不会误确认。受控读写交错验证了下一轮能追赶后来写入。 |
| 单次写入后的恢复 | 先用 304 确认旧值和游标 1，写入一次后收到 revision 2。配置 GET 连续三次 503，旧缓存待同步，断路器实际开放。最后一次 503 后约 5.08 秒发送探测，HTTP 200 应用 `recovered` 并更新磁盘缓存，游标 2 随后实际用于订阅。第 2/5 轮在原轮内恢复，五轮结束 `cacheHit=2, etag304=1`，退出 0。 |
| H2 实包重启 | 已确认 3 的 CLI 收到较小观察 0，返回 `changed=false`。读取 `[]` 后确认 0，随后一次写入从该游标观察并应用 revision 1。退出 0。超时的 `latestVersion` 是注册时捕获的值。重建后 revision 已追平或超过旧值时，现有协议无法辨认。 |
| 预算、断路器和取消 | 默认 30 秒恢复调度窗口，失败后至少等 1000 ms。受控 6 秒窗口、250 ms 间隔、900 ms 配置读取超时下，持续 503、拒绝连接和无响应分别于 6001、6001、7197 ms 结束恢复，均退出 1，仍确认 1、待同步 2。计时从首次可恢复失败返回起，包含最后在途调用。确定性测试验证同一窗口、到期不再发起外层调用、在途成功可确认、取消优先。真实 socket 读期间取消的回归验证 I/O 返回后无额外请求，并验证了半开槽释放和等待中断。 |
| 全量回归 | Windows、JDK 17.0.12、Maven Wrapper 3.9.16。`clean verify` 服务端 85 项、客户端 83 项，零失败或跳过。修复前两条 CLI 回归分别复现缺少补取、持续故障成功退出。真实 CLI 也验证了健康两轮、Watch 关闭和零轮三种入口。MySQL 沿用最终提交的隔离 CI。 |

本机运行的服务端 JAR SHA-256 为 `39c3fbc84bb8e07c059301a125246c6800424c2c303e9e8d1b76a6b255a0f420`，CLI 通过同一源码的 `spring-boot:run` 启动。对应客户端打包文件 SHA-256 为 `acbf92c390dca4b1baaeb7361252b15db982d210157bc269e41fe94f87d606ce`。对持续送达部分字节的慢响应，请求周期仍取决于已有传输行为。可用 [运行手册](RUNBOOK.md#config-plan04-watch-演练) 复演正常、重启、恢复失败和仅一次写入的场景。

## 后续工作

下一轮核对同时写入时的版本冲突、同一命名空间多 key 首写、MySQL 排序规则与进程内键的匹配，以及提交后响应丢失时可独立核对的效果。调用链重构、诊断和单仓展示随后推进。前端、SDK 和跨项目消费见 [升级路线](../项目源文件/UPGRADE_ROADMAP.md)。接管基线和本地待清理区见 [历史整理](HISTORY.md)。

## Config-Plan03 · 2026-11-24

审查基线为 `master@b546f7b6e402c2de4c3ad82323a592f509770505`。该轮生产代码、测试和 CI 位于 `928f39e25c40a8bec3e2932a3c123116876eac5f`。交付包括后续文档提交。最终交付 `1060563` 的 H2 和 MySQL 8.4.13 两项 CI 成功。[工作范围](https://github.com/MyKr-YSteinsK/config-center/issues/2) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/928f39e25c40a8bec3e2932a3c123116876eac5f) · [Config-Plan03 CI](https://github.com/MyKr-YSteinsK/config-center/actions/runs/45386328123)

| 验收面 | 实际读回 |
| --- | --- |
| CLI 传输 | 六组参数逐组经过配置、Watch、开关评估的 builder 和真实 loopback transport。接收端解码一次得到原值，涵盖加号、空格、中文、百分号和字面量 `%2B`。MySQL 实包上，CLI 的 `a+b` 与 HTTP 的 `a%2Bb` 返回完全相同的评估数据，`enabled=true`、`bucket=-1`。同一缓存第二次运行 `cacheHit=1, etag304=1`。运行手册的 PowerShell 冒烟另在新 H2 进程复演通过。 |
| 回滚与事务 | 两类 HTTP 回滚保留缺省、空串、空白、中文、满长 ASCII/中文及 250 个补充字符的原表示。501 返回 400/4001，五张业务表内容保持一致。两类写入在同一测试事务内 flush 后注入异常，退出事务独立查询，当前行、完整历史和 revision 回到原值，未调用 Watch 通知器。 |
| 条件读取 | 空列表与非空列表都有表示。独立 `*`、同一弱标签、等价强标签和包含匹配项的列表均返回空 body 的 304，并携带 ETag 和 trace 响应头。不匹配返回当前 200 envelope，非法参数返回 400。按单一有序快照计签，响应字段变化或重建后内容变化均使旧标签失效。 |
| 全量快测 | Windows、JDK 17.0.12、Maven Wrapper 3.9.16。`clean verify` 服务端 85 项、客户端 44 项，零失败、零错误、零跳过。修复前的回归在同一环境复现了三项问题。 |
| MySQL | 本机 Docker 引擎不可用。使用独立数据目录、loopback 端口、MySQL Community 8.0.46 和专用非 root 账户，在 `config_center_it` 跑完 `-Pmysql-it verify`。`MysqlPersistenceIT` 6 项、`MysqlV2UpgradeIT` 1 项均通过。真实 V2 数据升至 V3 后，两列可空且宽 600，五张业务表旧行、索引定义、原迁移校验和与 revision 保全。再 migrate 为 0，Hibernate validate 通过。两类回滚均读回 19 位目标版本加 500 长度原因的 533 长度表示。 |
| 新旧实包 | 本轮服务端 JAR SHA-256 为 `47bb894d97177602c48ad68b78217fb15a4152f262d6b9ae71d6f582c51781bd`。新 JAR 经 MySQL HTTP 从 v2 回滚到 v1 快照并生成 v3，配置与开关的 500 字中文原因逐字相符。用 `b546f7b` 旧 JAR 接同一 V3，Flyway 提示数据库版本较新，应用成功启动，两类历史 v3/v2/v1 可读，旧 API 的 500 字回滚均生成 v4。 |

复现命令、数据库边界与构建回退见 [运行手册](RUNBOOK.md)。构建文件名沿用 `1.0.0`，上述哈希标识本机实际运行的 JAR。历史标签 `v1.0.0` 指向 `0bce90c`，版本与发布另行决定。
