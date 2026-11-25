# 当前状态

## Config-Plan09 · 2026-11-25

复演时，已经退出的服务有时会在日志里留下端口。旧夹具会拿着这个端口继续运行。现在会先确认这次启动的进程仍存活，失败时给出阶段、日志路径和可用退出码。场景出错后也会收尾，并保留原来的错误。

审查基线为 `master@44b4bb60a13d90bfb2cb4967336bdfde96a52382`，开始时与配置的 `origin/master` 一致。三份本地 `Popen`、Tomcat 端口判定和终止等待收敛到 `jar_process.py`，两份 JDK 发现合为一处。Watch 保有重启和编号日志，断连夹具保有 profile、隔离环境及 `server.log`，迁移夹具保有各阶段参数、日志和一次性命令结果。MySQL 用自己的 SQL 就绪检查和优雅停止。现行 Java/HTTP、V1–V5、构建、Compose 和测试工作流均以审查提交为准。 [Issue #8](https://github.com/MyKr-YSteinsK/config-center/issues/8) · [复现和诊断](RUNBOOK.md#config-plan09-实包进程与失败诊断)

| 验收 | 实测 |
| --- | --- |
| 失败与进程清理 | 旧夹具接受了已经退出的进程日志中的端口 43123。相同条件下候选报告 `dead-with-port: child exited before port handoff (exit code 23)` 与绝对日志路径，无端口行的退出对照也返回 23。存活无端口 1.5 秒后超时，真实子进程已结束。正常结束、场景异常和中断均确认回收，场景异常保留原错误。实际缺失可执行文件有独立诊断。一次性命令保留退出码 7 和完整输出。直接回归 7 项通过，强制终止的两次有界等待及失败诊断由替身检验。 |
| Watch CLI | 原 `watch_recovery.py all` 退出 0。五个场景均验证 200→304。三次 503 后约 5.08 秒发出探测并确认游标 2。H2 重启后为 3→0→1。持续 503、拒连、读超时分别在 6009、6005、7308 ms 结束恢复，三个 CLI 均按预期退出 1。 |
| 写入断连 | `write_outcome.py` 的 `local` 与 `mysql` 均退出 0。转发 1 次时读到 v2、新历史及推进后的 revision。转发 0 次时保留原状态，另有后写覆盖、相同内容与刻意重放对照。MySQL 用独立 SQL 核对当前、历史和 revision。 |
| V3 升级和恢复 | `identity_upgrade.py` 验证无映射预检非零、匹配映射预检退出 0 且保持原库。实际部分 DDL 留下首张历史表的 `owner_id`，常规启动拒绝失败的 Flyway 标记。在另一隔离库恢复备份，旧 JAR 读到原数据。候选用真实 CLI 跳过旧缓存，读到 200 和新验证器的 304。精确历史、回滚、Watch、分桶及无额外迁移的重启均通过。 |
| V5 断连重放 | `idempotency_replay.py` 从 V4 样本升级并退出 0。用新的服务进程取回配置保存和旗标回滚的原结果。JAR 日志的 PID 为 7428→13032，重放无新增历史与回执，Watch revision 保持 7。零转发样本的首次重试写入一次。 |
| V5 写入链对照 | `write_chain_comparison.py` 退出 0。两份结果 JSON 的 37 个 HTTP/OpenAPI 项及六张表 29 行一致。在同一旧库切换时，四份旧回执的 `data` 逐字节相同，Watch revision 保持 3。随后新编号写入及两类旧历史回滚通过。 |
| Maven | `clean verify` 执行服务端 H2 108 项、客户端 82 项。本机独立源码副本和隔离 MySQL 重跑 `-Pmysql-it verify`，执行同样的 108、82 项及 MySQL Failsafe 46 项。最终两套均为零失败、零错误、零跳过。首轮本机 MySQL 验证由外层 480 秒时限提前关闭数据库，重跑取消该包装时限并使用新隔离实例。 |

本机实包环境为 Windows x64（26200）、JDK 17.0.12、Maven Wrapper 3.9.16、Python 3.14.6 与原生 MySQL 8.0.46。MySQL 使用各自独占的 loopback `config_center_it` 和专用非 root 账号。直接回归输出、命令总日志和实包指纹保存在本机忽略目录 `docs/delete/Config-Plan09/`，各场景的原始服务日志随各自运行目录保留。

| 实包 | 源码来源 | JAR SHA-256 |
| --- | --- | --- |
| V3 | `52e887daab5881974ec7d9bf06a707d2110fb378` | `1076e4f4cd4dd8c9c26308031bfea7b09eea6a767c0a0e640e0a1f22d63bb9d7` |
| V4 | `5e1353811cceb76d5effb164342ccf5b9e822f8b` | `f4e5891f3a77dcce81c225e812eebf0e8efe98bc1d5c480de77b917b95afd064` |
| V5 对照 | `0e65ac86c51e1f05edec5ab52fc85805114636b1` | `039927a7d8e28d4dab6577cf6b8c6c04f5e355c49bc97abbfce244a103bd88ac` |
| 本轮候选 | 本轮工作树，Java 源与 `44b4bb6` 相同 | `be58dd762301f53e242c460ff3308962e93203dfcb094557c2b59842c4a6e1e0` |

候选与 Plan08 已核对的 JAR 中，`BOOT-INF/classes` 与 `BOOT-INF/lib` 共 174 个文件内容逐一相同。复演收尾后，原始日志中记录的 36 个 JVM PID 与 9 个独立 MySQL PID 均已退出。启动检查依赖 Tomcat 日志与进程存活观察，没有新增 HTTP 探测。按最终交付 SHA 查看两个 CI 作业。[GitHub Actions](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

## Config-Plan08 · 2026-11-25

保存和回滚原来各写一遍收尾。现在配置和旗标各自共用保存与记录历史的路径，四个接口共用权限和请求头处理。实包对照中，两个版本返回相同的业务结果，新版本也逐字取回了旧版本保存的四份回执。

审查基线 `master@0e65ac86c51e1f05edec5ab52fc85805114636b1`，重构、测试与复演脚本位于 `3cecf15a95302998251e223df1af83b9c964c515`。按最终交付 SHA 查看两项 CI。[Issue #7](https://github.com/MyKr-YSteinsK/config-center/issues/7) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/3cecf15a95302998251e223df1af83b9c964c515) · [CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

| 验收 | 前后对照与实测 |
| --- | --- |
| 结构收益 | 两域各用一个私有 `persistChange`，配置专有的 revision 和提交后通知留在配置服务。共同的版本校验、回滚原因进入 `WriteRules`。四条路由交给一个 HTTP 适配器，编号执行器只管事务、回执和争用后的新事务读回。[前后职责](../project-map.md#numbered-writes) |
| HTTP 合同 | 在相同 V5 输入的两个隔离库，37 个实际 HTTP/OpenAPI 对照项一致。覆盖校验先于授权、授权先于非法编号、精确名称、403/400/404/409、编号首写及重放、页游标、灰度、ETag、请求 trace 和 Watch。完整 OpenAPI 只归一化随机端口。 |
| 事务与历史 | 六张表共 29 行，行 ID、owner、业务及锁版本、历史顺序、revision 与回执摘要一致。归一化仅用于各自新生成的时间和由它派生的 ETag，历史顺序及 ETag 对实际快照另行验证。保留原测试的独立 SQL、并发屏障、flush 后中止、争用、成功只通知一次及失败零副作用。 |
| 旧 V5 原库 | 先用基线 V5 写出四类回执，再停旧进程。候选连接同一库，四份 `data`（含 Instant）逐字节一致。原行、索引、迁移记录和 Watch revision 3 保持。Flyway 验证 5 个版本并报告无需迁移，Hibernate 完成 schema 校验。随后一次新编号写入成功，含旧拼写的两域历史均可正常回滚。 |
| 真实 CLI 和断连 | Plan04 三次配置 GET 503 后约 5.05 秒发出探测，随后确认游标 2，CLI 退出 0。Plan06 真实 CLI 忽略旧别名缓存，首次精确 GET 200，随后用新验证器取 304，`a+b` 传输正确。Plan07 配置保存及旗标回滚在实际响应丢失、重启后仍返回原结果，零转发对照只写一次。 |
| 本机全量 | 基线源码与候选均实际执行 `clean verify` 和隔离 `-Pmysql-it verify`，服务端 108、客户端 82、MySQL 46 项，零失败、错误和跳过。候选 MySQL 的编号唯一约束与等待超时分别验证，实际占位等待约 5.01 秒。 |

生产统计覆盖服务端 `src/main/java` 的物理行。相对同一基线，生产 Java 58→60 个文件、3907→3931 行，7 个生产文件涉及改动（+127/−103）。测试及复演单列，3 个文件（+366/−13），保留既有 Java 用例和断言。文档在 7 个现行文件中更新。构建依赖和迁移文件保持原值。以后调整保存收尾、写授权或编号处理时，各有一个共用入口。

Watch、CLI 和读取路径本来已有各自的状态与职责，本轮沿用其实现并用实包回归保护。V5 的意图、编码和 SQL 已有独立文件，保留原实现与序列化合同。

实包 SHA-256 分别为基线 V5 `039927a7d8e28d4dab6577cf6b8c6c04f5e355c49bc97abbfce244a103bd88ac`、候选 V5 `22ec0cf6e1948ec7c0fec51b52e2a08dfb70fd4b08c6840f17122dda1526a805`。混合历史样本来自 V3 实包 `1076e4f4cd4dd8c9c26308031bfea7b09eea6a767c0a0e640e0a1f22d63bb9d7`，其原始拼写和 owner 归属均保留。环境为 Windows、JDK 17.0.12、Maven Wrapper 3.9.16、独立 MySQL 8.0.46，专用非 root `config_center_it`。同一库的两个构建顺序启动。[运行手册与复现](RUNBOOK.md#config-plan08-写入调用链对照)

## 后续工作

评估低基数的写入和 Watch 诊断。Watch、CLI 各段已有验证过的等待和恢复职责，按实际故障定位价值决定局部整理。性能先固定数据、并发和引擎，再测历史查询、编号写入与重放以及 Watch 完成时间。[后续优先级与验收](../项目源文件/UPGRADE_ROADMAP.md#待审优先级) · [历史整理](HISTORY.md)

## Config-Plan07 · 2026-11-25

保存成功但回信丢失后，用原编号和原请求可以取回第一次的结果。旧 V4 JAR 对同一编号重复保存，产生 v1 和 v2。本轮在真实 MySQL 上让代理等到服务端返回成功再断开，随后换进程连接同一库。重试拿到了原结果，历史和 Watch 进度保持。

审查基线 `master@5e1353811cceb76d5effb164342ccf5b9e822f8b`。实现、测试、实包脚本与 CI 位于 `1f499059b00e6677830cd112dd78f780caaf936d`。根据最终交付提交 SHA 查看两项 CI。[Issue #6](https://github.com/MyKr-YSteinsK/config-center/issues/6) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/1f499059b00e6677830cd112dd78f780caaf936d) · [CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

| 验收 | 本机实测 |
| --- | --- |
| 四个入口与原结果 | 保存和回滚均经真实 HTTP 核对 200、首次 `false`、重放 `true`、本次 trace 和完整 data。每个编号只有一次提交。后来有新版本时，重放仍返回原版本、时间、值或灰度规则。无编号写入保留原来的版本条件和重复行为。 |
| 意图与授权 | 四条固定 SHA-256 向量与逐字段变体验证通过。JSON 顺序和排版可换，DTO 等价的缺省/null 可重放。换操作、值、版本、审计字段或 allowlist 顺序冲突。无效或重复编号返回 400。当前无权、跨精确名称或撤销的 key 均为 403，并在读回执前拦截。另一把现有授权 key 可取回原结果。 |
| 并发 | H2 和 MySQL 通过受控屏障、独立请求与连接验证。同意图为 200/200，异意图为 200/409，败方实际命中回执唯一约束。首占位者失败为 500/200。保持首占位不放行时，H2 约 2.00 秒、MySQL 约 5.01 秒结束竞争，败方 409 后按原编号重放成功。不同编号争同一业务版本只提交一笔，同编号可在两个精确 scope 独立绑定。 |
| 原子性 | 配置和旗标分别覆盖旧版本、找不到回滚目标、回执编码故障和完全 flush 后的提交前中止。暂停的事务能看到本次变更，独立连接的六张表仍为旧状态。失败后无回执或通知，同编号可再次成功。损坏、未完成或不受支持的已存回执为 500/5000。 |
| 实包断连与重启 | 配置保存和旗标回滚各转发一次并在上游 200 后断开，独立 HTTP/SQL 核对 v2 与 v3。后续写入分别推进到 v3 与 v4。更换进程（PID 6952→4168）和授权 key 后仍重放原 v2/v3，Watch 超时于 revision 7，历史和回执数保持。零转发对照重试才首次写入。相同内容使用不同编号各有一个回执。 |
| V5 升级 | 空库安装、V2→V3→V4→V5 分段升级、Flyway/Hibernate 校验和重复 migrate=0 通过。真实 V4 JAR 写入的配置、旗标、历史、revision、索引和 V1–V4 记录在升级后逐项相符，回执初始为空。V1–V4 源文件与审查基线一致。 |
| 回归 | JDK 17.0.12 和 Maven 3.9.16 的 `clean verify` 通过服务端 108 项、客户端 82 项。MySQL 8.0.46、`REPEATABLE-READ`、非 root `config_center_it` 的 Failsafe 通过 46 项，其中回执套件 15 项。全部零失败、错误和跳过。真实 CLI 在三次 503 后约 5.04 秒发出探测并恢复到游标 2。无编号断连与 V4 混合旧库的 CLI、Watch、回退恢复演练也通过。 |

回执 SQL 测得普通配置、最大字段配置和最大字段旗标的快照分别为 140、14,988、4,854 字节。计入 scope、编号、操作名和摘要后的变长数据为 237、15,454、5,319 字节，固定列、页和索引另占空间。每笔成功编号写入保留一条回执，与业务表使用一致性备份。

首轮远端 MySQL 的异意图竞争返回约定的 409，测试却要求底层必须是唯一约束冲突。调整后的唯一约束用例先让第二个事务读到空，再等首笔提交才发出第二次 INSERT。另一个用例继续持有首个占位，验证真实锁等待、409 和原编号重放。[首轮 MySQL CI](https://github.com/MyKr-YSteinsK/config-center/actions/runs/45535439211/job/138940777679)

实包基线 V4 JAR SHA-256 为 `f4e5891f3a77dcce81c225e812eebf0e8efe98bc1d5c480de77b917b95afd064`，本轮 V5 JAR SHA-256 为 `ca97fd5d196969dbc3c1b378e744cd332ce8a6fc32e498d2eb579c85c2149cdb`。本机用独立 mysqld、随机 loopback 端口和合成数据。安全重放依赖已提交回执留在与业务一致的持久库，以及所有写入者都支持协议。[写入回执合同](WRITE_RECEIPTS.md) · [复现与切换](RUNBOOK.md#config-plan07-幂等写入与回执)

## Config-Plan06 · 2026-11-25

以前两个不同拼写可能共用一条 MySQL 数据。升级后，它们各自拥有配置、写权限和更新进度。旧历史仍显示当时的名字，只能通过经确认的当前对象读取和回滚。遇到归属不清的旧库，升级会先停下来。隔离演练也证实，中途改表失败可以通过恢复备份重新开始。

审查基线 `master@52e887daab5881974ec7d9bf06a707d2110fb378`，服务端、客户端、夹具与 CI 实现位于 `bf39bded3d1438652c5032feefbcc284af8a725e`。按交付提交 SHA 查看远端两项 CI。[Issue #5](https://github.com/MyKr-YSteinsK/config-center/issues/5) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/bf39bded3d1438652c5032feefbcc284af8a725e) · [CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

| 验收 | 本机实测 |
| --- | --- |
| 精确身份 | H2 和 MySQL 经真实 HTTP 与参数化 SQL 按 app、env、key、name 分别验证大小写、重音、前后空格、组合字符、中文和补充字符。未写入的拼写为空或 404，分别创建后各为 v1。MySQL 当前身份列使用 `utf8mb4_0900_bin` / `NO PAD`。实际旧 JAR 接受孤立代理项并存为 HEX `3F`，现行两类写入和回滚拒绝该名称，返回 400/4001。 |
| 授权、Watch 和灰度 | 交叉写为 403/4031，五张表无变化。两个已挂起 Watch 按各自精确 scope 唤醒，保持各自 trace 和容量清理。固定向量 `cafe/Cafe/café/cafe␠` 得到 3/68/4/58，关闭和 allowlist 优先级通过。 |
| 并发和事务 | 保留同版本、同键首次创建、同 scope 多键、回滚竞争和 flush 通知回归。受控异名首次交错本机两笔均成功，各有 v1 和 revision 1。若 InnoDB 回滚一笔，测试核对 409/4091、没有部分提交，再用同一权限补写。V4 实包断连实验仍可用独立 HTTP/SQL 核对历史中的效果。 |
| 空库及 V2/V3 升级 | V1–V4 空库、分步 V2→V3→V4、再次 migrate 为 0 和校验通过。V1–V3 文件原字节及已存校验和一致。V3 扩列前后保留原字段；V4 新增 owner 约束、保留无关列及两类 533 长度回滚表示。 |
| 混合旧数据和映射 | 旧 JAR 与真实 V3 SQL 两套样本都验证归属。单 scope 的混合 key/name 可自动迁移。混合 scope、孤立历史、异常 revision 或错误/过期映射阻止升级。正确映射后，逐行对照原始历史与业务字段、ID、lock_version、版本和共享 revision。 |
| 备份、故障与实包 | 缺映射时五张表的行、列和索引不变，实际启动记下 V4 `success=0`。DDL 权限故障留下第一张表的 `owner_id`，正常重启拒绝。导入备份到另一隔离实例后，V3 JAR 读回原数据。该副本再升 V4，两类历史可分页，回滚生成 v4；旧拼写独立创建为 v1，Watch 分别到 5 和 1，旗标 bucket 分别为 68 和 3。 |
| 缓存与全量 | CLI 忽略两代旧别名缓存，实际离线请求无回退，联网先拿 200，再用新 ETag 拿 304。JDK 17.0.12、Maven 3.9.16 上 `clean verify` 通过服务端 88 和客户端 82 项。隔离 MySQL 8.0.46、`REPEATABLE-READ`、非 root 账户及独占 `config_center_it` 下 Failsafe 通过 31 项，五个套件分别为 3、6、1、10、11 项。 |

实包演练使用 V3 JAR SHA-256 `1076e4f4cd4dd8c9c26308031bfea7b09eea6a767c0a0e640e0a1f22d63bb9d7` 与 V4 JAR SHA-256 `f4e5891f3a77dcce81c225e812eebf0e8efe98bc1d5c480de77b917b95afd064`。合成逻辑备份为 13,161 字节，SHA-256 为 `b8254c1585978a0c300b5ecebdbfb2ff36f0a3ce7f27cbf0787a3ff461aaca4a`。三套演练实例各用独立数据目录和 loopback 端口。主库、Compose 卷及真实配置未参与。本机 Docker 引擎不可用，使用独立 mysqld。退回旧服务使用匹配的 V3 备份副本。本轮只验证了单版本切换和既有写授权边界。[迁移合同](IDENTITY_MIGRATION.md) · [复现与恢复](RUNBOOK.md#config-plan06-精确名称与迁移)

用本轮源码构建的 H2 JAR 和真实 CLI 重跑 Plan04 的五种 Watch 演练。一次写入后在最后一次 503 约 5.03 秒后补取并确认，H2 重启后从 0 重新订阅。持续 503、拒绝连接和无响应分别在约 6.01、6.00、7.25 秒结束，进程退出 1，保留已确认游标 1。[Watch 演练](RUNBOOK.md#config-plan04-watch-演练)

## Config-Plan05 · 2026-11-25（V3 基线）

真实 MySQL 验证同时保存时写进去的是哪一笔，并用断连演练证明可以查到想要的效果是否曾出现。修复前，等值拼写争用首条 revision 时，被数据库回滚的一笔返回系统异常。现在它返回明确的写入冲突，成功方仍有完整的记录与通知。

基线 `master@eb84b51f67f103373b7999ec601cc708ab205591`，实现与验证位于 `5c259f47df1899f44e8a55adea274c93660a401d`。按最终交付 SHA 查询两项 CI。[Issue #4](https://github.com/MyKr-YSteinsK/config-center/issues/4) · [实现提交](https://github.com/MyKr-YSteinsK/config-center/commit/5c259f47df1899f44e8a55adea274c93660a401d) · [CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)

| 验收面 | 实际结果 |
| --- | --- |
| 同版本、首次创建及回滚竞争 | 两类资源从同一旧版本、缺行以及 upsert/rollback 的受控交错均为一个 200、一个 409/4091。胜者和完整历史均可读回。不同键在同 namespace 首写均 200，revision 到 2，已存在时并行更新到 4。 |
| 原子性与通知 | 写入三部分 flush 后、提交前，独立连接仍读到旧值，Watch 等待中。提交后可读到新值且只通知一次。注入事务失败时五张表和通知计数保持，随后合法写入可用，Watch 资源释放。 |
| 实际修复 | MySQL 的两个等价 app 拼写走不同条带，首条 revision 同时缺行时触发 1213/40001。基线返回 HTTP 500/code 5000，当前返回 409/4091。保留成功方唯一 v1 与 revision 1，败方提交无效且不通知。随后合法补写产生 revision 2。 |
| 名称与授权 | MySQL 在已测案例折叠大小写、重音和尾空格，H2 独立。授权交叉用错拼写返回 403/4031。各用获授权拼写时，MySQL 可写同一行。已挂起的原拼写 Watch 带正确 trace 超时，下一轮看到 3。固定用户在 50% 灰度下，`cafe/Cafe` 得到 bucket 3/68。 |
| 提交后断连 | H2 和 MySQL 实包均覆盖：只转发一次且上游 200 后断连、转发 0 次、后写覆盖、另一请求提交相同内容及重复写入对照。MySQL 独立 SQL 与 HTTP 当前、历史、revision 一致。目标效果可核对，完全相同的效果不能唯一归因。 |
| 回归与环境 | Windows、JDK 17.0.12、Maven Wrapper 3.9.16。`clean verify` 服务端 86、客户端 83 项。独立 MySQL 8.0.46、`REPEATABLE-READ`、专用非 root `config_center_it` 下，Failsafe 执行 18 项，其中并发 10、名称 1、原持久化 6、V2 升级 1。全部无失败或跳过。Docker 引擎不可用，本机使用独立 mysqld。 |

本机 H2 与 MySQL 演练使用同一服务端 JAR，SHA-256 为 `1076e4f4cd4dd8c9c26308031bfea7b09eea6a767c0a0e640e0a1f22d63bb9d7`。实际矩阵、读回和候选方案见 [写入证据](WRITE_CONSISTENCY.md)，命令见 [运行手册](RUNBOOK.md#config-plan05-并发与结果读回)。

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
