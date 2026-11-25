# MySQL 配置并发写入基线

同样是 8 个 worker 各更新自己的配置 key，分散到 8 个命名空间的那组，在本次四对测量里每次都更快。分散组的成功吞吐为同对集中组的约 3.4–4.1 倍，P95 为 17.51–19.04 ms，集中组为 59.67–69.17 ms。八轮计时共 12,800 次成功，SQL 读回全部一致。差异适用于下列环境和负载。 [Config-Plan11](https://github.com/MyKr-YSteinsK/config-center/issues/10)

## 构建与环境

2026-11-25 从 `124d20f63ed57491af3f9e48878d5451d24e18f2` 的业务源码构建。测量 JAR SHA-256 为 `0952d9b2e46fdb21195fb78c718040f402a5354eb669c41fd9e30fdb389dcb3d`，执行夹具的 SHA-256 为 `b7f65834ef86de8eedabedef7df6fa883fe0350f00bdd7bd7b487e9cf33142db`。硬件为 i7-12700H，14 核、20 逻辑处理器。系统为 Windows 11 x64 build 26200，JDK 17.0.12，Maven Wrapper 3.9.16，Python 3.14.6，原生 MySQL 8.0.46。

全部四对共用同一个 loopback JAR 和独占 `config_center_it`。JAR 使用内置 MySQL profile 和默认连接池、HTTP keep-alive 设置，夹具仅对该进程关闭限流。SQL 连接确认专用非 root 账号，隔离级别 `REPEATABLE-READ`，`innodb_flush_log_at_trx_commit=1`，`innodb_buffer_pool_size=134217728`，`innodb_lock_wait_timeout=50`，`max_connections=151`，`log_bin=0`。测试 JDBC 参数为 `serverTimezone=UTC&useSSL=false&allowPublicKeyRetrieval=true`。夹具为每个 worker 预建一条 HTTP 连接，使用 15 秒 socket 超时。

## 等量负载和种子

- 调用 `POST /api/configs` 更新已有配置。每个 worker 独占一个逻辑 key，同时最多一笔在途。计时轮每个 worker 请求 200 次，值为 256 个 ASCII 字节，JSON 请求体为 317 字节。客户端逐笔提交，无自动重试，并省略写入编号。
- 集中组把 `key-00` 至 `key-07` 放在 `plan11-ns00/bench`。分散组把相同逻辑 key 依次放到 `plan11-ns01/bench` 至 `plan11-ns08/bench`。从构建字节码核对 `NamespaceRevisionLock` 的 64 条带、`Objects.hash(app, env)` 与 `Math.floorMod`，用同版 JDK 独立计算并核对夹具记录。

| 命名空间（env 均为 `bench`） | 条带 |
| --- | ---: |
| `plan11-ns00`（集中组） | 8 |
| `plan11-ns01` | 39 |
| `plan11-ns02` | 6 |
| `plan11-ns03` | 37 |
| `plan11-ns04` | 4 |
| `plan11-ns05` | 35 |
| `plan11-ns06` | 2 |
| `plan11-ns07` | 33 |
| `plan11-ns08` | 0 |

每次进程启动后，经原 HTTP 入口生成 9 个命名空间、每空间 8 个 key。种子共有 72 条当前记录和对应的 72 条历史，初始业务版本均为 1，每个命名空间的 revision 为 8。保留同一份数据导出。正式轮前恢复种子，按固定顺序用集中、分散各预热 20 次/worker，然后再恢复同一份种子并用 SQL 核对。DML 恢复逻辑数据，JVM、数据库缓存和自增计数器随进程运行。正式跑内的两组小跑各 24 次成功，独立 `--smoke-only` 也分别核对了 24 次成功。16 个预热批次各 160 次成功且读回匹配。

## 逐轮结果

用 `perf_counter_ns` 从发起请求到完整读完响应体计算单笔延迟，按成功样本的 nearest-rank 取 P50、P95。吞吐为成功数除以该轮首笔请求开始到末笔请求读完的时间。连接预建、启动、数据恢复、预热和轮后 SQL 核验均在计时之外。

| 对次 | 当轮组别 | worker × 每个请求数 | 尝试 | 成功 | 失败 | 计时 s | 成功/秒 | P50 ms | P95 ms |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 集中 | 8 × 200 | 1600 | 1600 | 0 | 11.822 | 135.3 | 59.16 | 69.17 |
| 1 | 分散 | 8 × 200 | 1600 | 1600 | 0 | 3.067 | 521.7 | 14.81 | 19.01 |
| 2 | 分散 | 8 × 200 | 1600 | 1600 | 0 | 2.962 | 540.1 | 14.38 | 19.04 |
| 2 | 集中 | 8 × 200 | 1600 | 1600 | 0 | 10.145 | 157.7 | 48.75 | 68.96 |
| 3 | 集中 | 8 × 200 | 1600 | 1600 | 0 | 9.631 | 166.1 | 46.60 | 62.04 |
| 3 | 分散 | 8 × 200 | 1600 | 1600 | 0 | 2.364 | 676.9 | 11.05 | 18.00 |
| 4 | 分散 | 8 × 200 | 1600 | 1600 | 0 | 2.437 | 656.5 | 11.37 | 17.51 |
| 4 | 集中 | 8 × 200 | 1600 | 1600 | 0 | 9.216 | 173.6 | 44.68 | 59.67 |

八轮均有效，失败类别为空，429 为零。从保存的单笔时间戳独立复算了计时、吞吐和分位数。每轮在途峰值均为 8，每个 worker 的请求时段无重叠。

| 组别 | 成功/秒范围 | P50 范围（ms） | P95 范围（ms） |
| --- | ---: | ---: | ---: |
| 集中 | 135.3–173.6 | 44.68–59.16 | 59.67–69.17 |
| 分散 | 521.7–676.9 | 11.05–14.81 | 17.51–19.04 |

集中组从首对的 135.3/s 增到末对的 173.6/s，分散组从 521.7/s 增到 656.5/s，组内也有波动。每对分散/集中吞吐比分别约 3.85、3.42、4.07、3.78。实验只覆盖一个并发度和一种负载。端到端计时包含客户端与 HTTP、精确授权、JVM 业务处理和数据库事务。各段耗时、进程内锁等待和 MySQL 行锁等待尚未单独测量，差异的瓶颈归属仍待验证。

## 独立 SQL 读回

每个计时轮的 8 个目标 key 均为 v1→v201，JPA 锁版本随成功次数推进。历史共追加 1,600 条，逐条按 owner、业务版本、值和动作核对。集中组唯一写入命名空间的 revision 为 8→1608，分散组 8 个写入命名空间分别为 8→208。其余当前行、历史、revision 按共同种子核对，回执表为空。所有成功响应的版本序列与 SQL 一致。

原始计时、失败类别、SQL 摘要和构建指纹保存在本机忽略目录 `docs/delete/Config-Plan11/write-baseline-*/rounds.jsonl`，同目录保留启动与关闭日志。两次运行的 JAR 与 MySQL PID 均确认退出。 [构建、启动及复跑命令](RUNBOOK.md#config-plan11-mysql-配置并发写入基线)

## 常规回归

| Windows 命令 | 服务端 H2 | 客户端 | MySQL Failsafe | 失败 / 错误 / 跳过 |
| --- | ---: | ---: | ---: | --- |
| `.\mvnw.cmd -q -B clean verify` | 108 | 82 | — | 0 / 0 / 0 |
| `.\mvnw.cmd -q -B -Pmysql-it verify` | 108 | 82 | 46 | 0 / 0 / 0 |

两条命令退出 0。MySQL profile 在独立于测量的 MySQL 8.0.46 实例完成六套 Failsafe，其中 `MysqlWriteConsistencyIT` 10 项通过。回归用 MySQL 的正常关闭日志与 PID 已核对。原始命令输出和测试计数留在本机忽略目录 `docs/delete/Config-Plan11/`。 [复跑入口](RUNBOOK.md#config-plan11-mysql-配置并发写入基线) · [提交 CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml)
