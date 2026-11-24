# 写入一致性与结果核对

两个人同时保存时，可以用最后读到的值、历史和 revision 核对实际成功的写入。连接中断时，也可以查到想要的效果是否曾经出现。现有历史描述每次保存的内容，精确归因需要服务端持久化的请求身份。

## V4 的精确名称与并发

Config-Plan06 统一了数据库与 Java 的精确名称。不同 app/env 拼写各有当前行、历史和 revision。并发首次写入仍可能遇到 InnoDB 锁冲突。V4 受控交错检查两笔成功或被回滚的一笔返回 409/4091，随后用原精确权限补写，最终各有一条 v1、revision 1，且等待者只接到自身 scope 的通知。原同版本、同键首写、多键、upsert/rollback 和 flush 之后的通知回归继续执行。下面保留 V3 的实测对照。[V4 迁移合同](IDENTITY_MIGRATION.md) · [本轮验证](CURRENT_STATE.md)

## V3 真实 MySQL 交错

Config-Plan05 的基线为 `eb84b51`。使用本机 MySQL 8.0.46、InnoDB `REPEATABLE-READ`、专用非 root 账户与独占 `config_center_it`，客户端直连 Spring Boot 随机 loopback 端口。仅演练关闭限流。测试屏障保留真实 JPA 查询、事务和 MySQL 约束。每对请求记录不同的 `CONNECTION_ID()`，无自动重试。测试会话的 `innodb_lock_wait_timeout` 为 6 秒，屏障与 HTTP 等待均有上限。[任务与验收](https://github.com/MyKr-YSteinsK/config-center/issues/4)

| 场景与交错 | 持久结果与 HTTP |
| --- | --- |
| 配置和开关从同一个 v1 修改 | 两个事务各自读到 v1 后放行。收到一个 200 和一个 409/4091，败方触发历史版本唯一约束（1062）。当前和新增 v2 快照等于胜者内容，v1 完整保留。配置 revision 加一，已挂起 Watch 只收到一次提交后的通知和自己的 trace。开关保持配置 revision。败方读回 v2，以它为前置版本成功提交 v3。 |
| 两类同名首次创建 | 两个事务各自读到缺行后放行。收到一个 200 和一个 409/4091，败方触发当前业务键唯一约束（1062）。只存在一条 v1 当前及历史。配置 revision 为 1。单次缺行携带正 `expectedVersion` 仍可创建，存量省略前置条件仍可更新。 |
| 同精确命名空间，不同配置键 | 两笔各自完成业务写入准备，在进程内 revision 条带锁前相遇。空 namespace 中两个请求均 200，两条当前和历史各为 v1，只有一条 revision 行，值为 2。两键再次并行更新均 200，历史为各自的 v2/v1，revision 到 4。四次通知逐次对应提交。 |
| 修改与回滚 | 两类业务的 upsert 和 rollback 各自读到 v2 后相遇。一个 200 和一个 409/4091，败方触发历史唯一约束。v3 内容按胜者请求或回滚目标快照读回，v2、v1 完全保留。后续写入可生成 v4。 |
| flush、提交与回滚 | 测试在事务提交前 flush，事务内已看见当前 v2、两条历史和 revision 2。独立 SQL 连接和 HTTP 仍看见旧状态，Watch 在等候，通知次数为 0。允许提交后，独立读回 v2，Watch 返回 `changed=true`、revision 2 及请求 trace。另一轮在同一点中止，五张表与失败前一致，Watch 继续等待。下一条合法写入提交后仅通知一次，等待槽与命名空间计数回零。 |
| MySQL 等价拼写的首条 revision | 两个不同条带同时完成 `SELECT … FOR UPDATE` 并看到缺行，再插入相同数据库身份。InnoDB 检测到 1213 / SQLSTATE 40001，Spring 翻译为 `CannotAcquireLockException`。基线返回 HTTP 200 与 500/5000，修复后返回 200 与 409/4091。败方无当前行、历史、revision 或通知，胜方留下一个 v1 及 revision 1。败方随后可用同一权限再创建自己的键，revision 到 2。 |

普通同版本、同键首写、同 namespace 多键和 upsert/rollback 场景均未触发死锁或锁等待超时。同名竞争的败方产生 1062，别名首行场景单独复现了 1213。修复位于 HTTP 异常映射，事务由原边界整体回滚。结论适用于测试中实际发生的交错。

## V3 名称比较的实测矩阵

MySQL 五张业务表的 `app`、`env`、`config_key` 和 `name` 使用 `utf8mb4_unicode_ci`。逐行对照实际表列的 SQL 等值查询、HTTP 当前记录或评估、历史和 revision。以下以 `plan05-cafe/dev` 下的 `cafe` 为基准，H2 为 2.3.232 的项目测试 profile。

| 变体 | Java 精确比较 | MySQL 实测 | H2 实测 |
| --- | --- | --- | --- |
| 相同拼写 | 相等 | 同一行和历史，HTTP 200，namespace revision 可读 | 同样匹配 |
| 仅 app 或业务名大小写不同 | 不等 | 当前、历史和对应 revision 查询均命中，HTTP 200 | 该变体没有匹配的记录，当前读/评估返回 404 |
| `café` 与 `cafe`，或 app 对应重音变体 | 不等 | 等值查询命中，HTTP 200 | 该变体没有匹配的记录，当前读/评估返回 404 |
| `cafe␠` 或 app 尾部 ASCII 空格 | 不等 | 查询仍命中，HTTP 200 | 该变体没有匹配的记录，当前读/评估返回 404 |
| `plan05-other`、`tea`、`计划五`、`中文旗标` | 不等 | 未造数时为空或 404。独立 namespace 写入 `中文旗标` 后可读，原 scope revision 保持。 | 同样区分并可读回中文 |

- **授权和字段。** 两个合成 API key 分别授权 `plan05-cafe/dev` 和 `Plan05-cafe/dev`。交叉使用返回 403/4031，且持久状态未变化。分别使用各自授权的拼写可写入。MySQL 将它们定位到同一配置与开关，upsert 响应与当前行反映本次请求拼写，旧历史保留原拼写。原 namespace revision 行仍存首次拼写。H2 两个 scope 各自拥有当前、历史和 revision。
- **等待与下一次读。** 精确拼写的控制组收到 `changed=true, latestVersion=2`，独立 scope 正常超时于 0。原拼写 Watch 已在服务器挂起时，以获授权的另一拼写写入。MySQL 的 revision 到 3，该 Watch 带原 trace 超时返回 `changed=false, latestVersion=2`，下一次轮询立即返回 3，列表读回 `alias-write`。H2 上别名写入自己的 revision 1，原拼写两次都保持 2。复现覆盖大小写变体的实际等待路径，重音与尾空格的结果来自身份查询和评估矩阵。
- **灰度取样。** 同一个 MySQL 开关上，`plan05-user-6` 在 50% 灰度时查询 `cafe`、`Cafe`、`café`、`cafe␠`，结果依次为 bucket 3/开启、68/关闭、4/开启、58/关闭。仅改变 app 拼写且保持请求 name，会得到原 bucket。总开关关闭和 allowlist 命中的优先级在真实请求中均验证。H2 上未写入的别名为 404。

### Plan05 审查过的身份路线

| 路线 | 取舍与需要验证的条件 |
| --- | --- |
| 保持数据库等值规则，使用数据库解析出的稳定身份 | 已有等价读写可继续定位原记录。先决定精确授权与共用数据的关系，再把 revision 首行、事务锁、Watch 和灰度输入绑到持久稳定的 scope/flag 身份。沿唯一约束原子建立首行，处理两个请求首次解析都缺行的情况。需测试存量拼写、两个授权键、分桶影响与热点查询/锁开销。 |
| 采用精确标识并迁移 | JVM 与持久层可共享精确比较。需要明确 Unicode、尾空格和历史保留策略，选择相应的排序规则或精确索引。先盘点客户端别名与混合拼写历史，按原业务对象迁移关联，避免当前行、历史和 revision 迁成彼此独立的对象。需要索引重建、兼容期、授权和灰度迁移及旧库演练。 |

小写化、去空格无法复现当时实测的 MySQL 等值关系。所有者在 Config-Plan06 选择第二条路线。迁移预检仍用原数据库的 SQL 等值关系盘点旧组，由所有者决定混合 scope 的归属。[Config-Plan06](https://github.com/MyKr-YSteinsK/config-center/issues/5) · [V4 迁移步骤](IDENTITY_MIGRATION.md)

## 丢失写入响应时如何核对

调用方的两个断连场景都会收到 `RemoteDisconnected`。受控代理统计转发次数并记录上游状态与 trace。独立读者用新 HTTP 连接分别取当前、历史和 Watch revision。MySQL 实包同时用独立命令行连接逐项比对数据库。演练使用合成值和单独的 app。

| 执行场景 | 注入器知道的事实 | 调用方实际可确认的结果 |
| --- | --- | --- |
| 提交后丢响应 | 只转发一笔带 `expectedVersion=1` 的 v2，收到上游 200 与 trace，随后关闭调用方连接 | 当前 v2 为 `target`，历史 v2/v1，revision 比基线多 1。目标效果已出现，停止写入。 |
| 请求未到达 | 转发 0 次，同样关闭调用方连接 | 当前、历史与 revision 停在基线。此刻未观察到目标效果，无法用这次读回判断仍在途的请求。 |
| 提交后有另一笔写入 | 目标提交后，另一请求正常写入 v3 | 当前显示后来的值。历史 v3/v2/v1 可证明目标效果曾在 v2 出现，revision 多 2。 |
| 重复内容的归因 | 调用方请求转发 0 次，另一请求以相同业务体、operator、reason 提交 | 读回与「原请求提交」的场景在所比较的历史字段完全一样。现有历史无法证明是谁发起了这次提交。 |
| 人工重复写入对照 | 先成功写 v2，再分别发出旧前置版本和无条件的相同内容 | 仍带 `expectedVersion=1` 的重放返回 409/4091，状态保持。去掉条件再次提交，新增内容相同的 v3 并推进 revision。 |

本机使用同一个 JAR 在 MySQL 8.0.46 和 H2 上跑完五个案例。以下为 MySQL 实包的首个样本，独立读回在协议层和数据库得到相同结果。

```text
Caller: trace=committed-attempt RemoteDisconnected
Injector: forwarded=1 upstream_status=200 upstream_trace=committed-attempt
Reader: current=(2, 'target'), history v2/v1, revision 1 -> 2
Independent SQL: one current row, versions 2/1 in history, one namespace row at revision 2
```

遇到连接丢失，先记录原 scope、业务键、期望旧版本、目标内容及自己持有的 trace，再另起连接读当前和历史。目标值已被后写覆盖时，用版本游标查看更早的历史。根据读回区分效果存在、当前未观察到和归因不明。当前接口没有持久请求回执，trace 可辅助日志关联，operator/reason 是客户端自报字段。

后续如要唯一归因和安全重放，可评估在写入事务内持久化幂等键和结果，约定范围、请求摘要、不同内容复用时的冲突、重放返回与保留期。缺行时是否只允许创建、回滚是否带当前版本前置条件，也需单独定义与兼容。实包命令与预期输出见 [运行手册](RUNBOOK.md#config-plan05-并发与结果读回)。
