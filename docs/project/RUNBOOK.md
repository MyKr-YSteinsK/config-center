# 本地运行与恢复

## 环境与入口

在实际仓库根目录执行下列命令。JDK 17 是构建和运行基线，Maven Wrapper 固定 3.9.16，首次获取 Maven 和依赖需要网络。Windows 运行 `.\mvnw.cmd`，Unix 和 CI 运行 `./mvnw`。默认使用 Maven 的用户缓存。Docker Desktop 和 Compose 只用于容器持久化或本地隔离 MySQL 测试。

```powershell
git status --short --branch
java -version
.\mvnw.cmd -version
.\mvnw.cmd -q -B clean verify
java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local
```

[README](../../README.md) 提供 H2 写入、条件读取、灰度、Watch、客户端命令，以及直接 MySQL 和 Compose 的参数。两模块的 Surefire、JaCoCo 输出位于各自的 `target/`。默认 H2 快测和 CLI 单测均不依赖 Docker。

| 目的 | 命令或证据 |
| --- | --- |
| 只验证服务端 | `.\mvnw.cmd -q -B -pl config-center-server test` |
| 只验证客户端 | `.\mvnw.cmd -q -B -pl config-center-client test` |
| 验证持久层 | 启动独立 `config_center_it` 后运行 `.\mvnw.cmd -q -B -Pmysql-it verify`，结果在服务端 `target/failsafe-reports/` |
| 验证候选提交的远端环境 | [Actions CI](https://github.com/MyKr-YSteinsK/config-center/actions/workflows/ci.yml) 以 SHA 查询 `build-test` 和 `mysql-integration` |

## 数据地图

| 数据 | 位置与恢复 |
| --- | --- |
| H2 运行及测试 | `local` 使用内存 `configdb`，重启即重新开始。`test` 使用随机内存库和 `create-drop`，只服务测试。两者均禁用 Flyway。 |
| 持久 MySQL | 默认 Compose 项目把数据放在 `config-center_mysql-data`，手工 MySQL 则使用用户指定的实例。应用使用专用非 root 账户，Flyway 迁移后由 Hibernate validate。需要跨机保留时，通过私有备份渠道导出和恢复数据库。 |
| MySQL 测试 | 使用专用 `config_center_it`。Compose 路径用 `compose.mysql-it.yml` 隔离项目和卷，loopback 端口默认 `33306`；独立 MySQL 可使用专用数据目录、loopback 端口和非 root 测试账户。验证前核对实际 URL 与项目名，保持主库数据卷完整。 |
| 写入回执 | MySQL 的 `write_receipt` 与五张业务表同库，保存编号、摘要与原成功结果。与业务及 Flyway 元数据一并备份和恢复，按配置历史的保密要求保管。 |
| 本地配置 | 从 [模板](../../.env.example) 创建缺失的 `.env`，实际密码和 API key 通过私有方式恢复。手工运行 MySQL JAR 另外设置 README 列出的必需环境变量。 |
| 客户端缓存 | `${user.home}/.config-center-client-cache-exact-v1.json`。旧两代文件独立保留，详情见本页客户端切换。缓存可能含配置明文。 |
| IDE 和构建输出 | `.idea/`、`target/`、Maven 用户缓存位于本地。接管前废旧工作区材料的去向见 [历史整理](HISTORY.md)。 |

直接运行 JAR 由 `SERVER_ADDRESS` 控制监听，默认 `127.0.0.1`。持久 Compose 的 `0.0.0.0` 只在容器内监听，主机侧由 `SERVER_BIND_ADDRESS` 控制并默认为 loopback。Swagger、H2 Console、Actuator 以及实验用 API key 在这个边界内使用。

## Config-Plan09 实包进程与失败诊断

在仓库根用 Python 3.11+ 执行直接回归。测试用可控 Python 子进程写日志，既不启动 JAR 也不占用端口。检查已退出的子进程、就绪超时、正常停止、场景异常、中断和一次性命令。终止超时后的强制清理使用替身验证调用与两次有界等待。

```powershell
python -B config-center-server/src/test/fixtures/test_jar_process.py
```

Watch、写入断连和迁移脚本共用 `jar_process.py`。它通过 `java.home` 找到实际的 JVM，读取新子进程的 Tomcat 端口日志，在交出端口前检查该子进程仍存活。没有额外的 HTTP 就绪请求。启动诊断包含阶段、绝对日志路径及可用退出码。原 `server-1.log`、`server-2.log`、`server.log` 和迁移阶段日志继续记录原始 stdout/stderr，可据此查 PID。Watch 等待监听最多 75 秒，写入断连及迁移监听等待 80 秒。一次性预检仍等待最多 85 秒并返回退出码与完整日志。

停止时先终止并等待。Watch 和写入断连等 15 秒，迁移等 18 秒。到期后强制终止，再等同样的时限。先前的错误会保留，清理结果附在错误后或写到 stderr。Windows 的两种终止方法使用同一个系统调用，替身检查的是超时处理和再次等待的顺序。端口检查只代表当次观察，子进程仍可能随后停止。MySQL 仍以实际 SQL 认证判定就绪，并用 `mysqladmin shutdown` 收尾。

### 基线对照

从审查提交 `44b4bb6` 取旧夹具，替换其 `Jars.launch` 的进程来源。子进程打印日志并主动退出后才开始判定。以下脚本会断言旧夹具错误接受了端口，候选的直接回归断言相同条件下拒绝并报告退出码 23。

```powershell
$reviewFixture = (New-Item -ItemType Directory "docs/delete/plan09-$([guid]::NewGuid())").FullName
git archive --format=zip -o "$reviewFixture/fixtures.zip" 44b4bb6 -- config-center-server/src/test/fixtures
if ($LASTEXITCODE -ne 0) { throw 'Baseline fixture archive failed' }
Expand-Archive -LiteralPath "$reviewFixture/fixtures.zip" -DestinationPath $reviewFixture
@'
from contextlib import contextmanager
from pathlib import Path
import subprocess
import sys

root = Path(sys.argv[1])
sys.path.insert(0, str(root / "config-center-server/src/test/fixtures"))
from identity_upgrade import Jars

log = root / "exited.log"
with log.open("wb") as sink:
    child = subprocess.Popen([sys.executable, "-c",
        "print('Tomcat started on port 43123', flush=True); raise SystemExit(23)"],
        stdout=sink, stderr=subprocess.STDOUT)
    assert child.wait(10) == 23

@contextmanager
def launch(*args):
    yield child, log

jars = Jars.__new__(Jars)
jars.launch = launch
with jars.serving(None, None, "baseline-exited") as port:
    assert port == 43123 and child.poll() == 23
    print(f"Baseline accepted port {port} after exit {child.returncode}. Log: {log}")
'@ | python -B - $reviewFixture
```

### 原命令回归

在 JDK 17 下先用 `.\mvnw.cmd -q -B clean verify` 构建当前 JAR。按下列各节的 `git archive` 步骤取得三个旧包。将 `$v3`、`$v4`、`$v5` 设为相应 JAR 的完整路径，用 `Get-FileHash -Algorithm SHA256` 记录四个包的身份。 [V3 · `52e887d`](#合成演练和失败恢复) · [V4 · `5e13538`](#测试与实包断连演练) · [V5 · `0e65ac8`](#config-plan08-写入调用链对照)

```powershell
python -B config-center-client/src/test/fixtures/watch_recovery.py all
python -B config-center-server/src/test/fixtures/write_outcome.py --profile local
# 以下第一条需要事先配置独占、loopback 的 config_center_it 和专用非 root 账号
python -B config-center-server/src/test/fixtures/write_outcome.py --profile mysql
# 以下三条将各自启动隔离的原生 MySQL
python -B config-center-server/src/test/fixtures/identity_upgrade.py --baseline-jar $v3
python -B config-center-server/src/test/fixtures/idempotency_replay.py --baseline-jar $v4
python -B config-center-server/src/test/fixtures/write_chain_comparison.py --baseline-jar $v5 --legacy-jar $v3
```

写入断连的 MySQL profile 需要隔离 MySQL 的环境变量。后面三条需要 `mysqld`、`mysql`、`mysqladmin` 和 `mysqldump`。使用 Maven 启动 CLI 的场景顺序运行。日志、合成库、备份和缓存按场景写在 `docs/delete/` 下。 [隔离 MySQL 环境变量](#快测与真实-mysql-http) · [实测记录](CURRENT_STATE.md#config-plan09--2026-11-25)

本机执行 `.\mvnw.cmd -q -B -Pmysql-it verify` 时，等完整 Maven 命令结束再关闭这次的隔离 MySQL。Windows 外层命令提早超时可能先关闭数据库，让仍在运行的 Failsafe JVM 报连接失败。复跑时使用新的隔离库。

## Config-Plan08 写入调用链对照

同一组合成操作分别走旧、新 JAR 的 HTTP 入口。脚本比较实际响应和数据库，并让新 JAR 直接连接旧 JAR 刚写过的 V5 库，重放四类旧回执。其余迁移、并发、故障和 CLI 场景仍可按各节独立复演。

在仓库根准备 JDK 17、Python 3.11+、`mysqld`、`mysql`、`mysqladmin` 和 `mysqldump`。以下命令把两个旧构建放进 Git 忽略区，并用当前工作树的 Maven 产物作候选。合成 V3 样本用于重建含混合拼写历史的 V5 输入。

```powershell
.\mvnw.cmd -q -B clean verify
if ($LASTEXITCODE -ne 0) { throw 'Candidate build failed' }
$bundle = (New-Item -ItemType Directory -Force "docs/delete/plan08-$([guid]::NewGuid())").FullName
$refs = [ordered]@{
    baseline = '0e65ac86c51e1f05edec5ab52fc85805114636b1'
    legacy = '52e887daab5881974ec7d9bf06a707d2110fb378'
}
foreach ($entry in $refs.GetEnumerator()) {
    $archive = Join-Path $bundle "$($entry.Key).zip"
    $source = Join-Path $bundle $entry.Key
    git archive --format=zip -o $archive $entry.Value -- .mvn mvnw mvnw.cmd pom.xml compose.yml config-center-server config-center-client
    if ($LASTEXITCODE -ne 0) { throw 'git archive failed' }
    Expand-Archive -LiteralPath $archive -DestinationPath $source
    Push-Location $source
    try {
        .\mvnw.cmd -q -B -DskipTests package
        if ($LASTEXITCODE -ne 0) { throw 'Baseline package failed' }
    } finally { Pop-Location }
}
python -B config-center-server/src/test/fixtures/write_chain_comparison.py `
  --baseline-jar "$bundle/baseline/config-center-server/target/config-center-server-1.0.0.jar" `
  --legacy-jar "$bundle/legacy/config-center-server/target/config-center-server-1.0.0.jar"
```

对照脚本自建独占 `config_center_it` 和专用非 root 账号，使用随机 loopback 端口。它在基线 V5 上造数并保存相同起点的副本，再分别跑前后 HTTP 序列。两次执行中新生成的时间使用同一检查与占位表示，历史时间顺序及 ETag 与快照关系单独校验。行 ID、owner、版本、顺序和摘要直接比较。切换到同一库的重放严格比较原 `data` 字节（含时间）以及六张表和索引。日志、两份 JSON 对照记录和合成备份位于 `docs/delete/Config-Plan08/`。

单独配置 [README 的 MySQL 测试环境](../../README.md#persistent-start-mysql--docker-compose)，可跑 `.\mvnw.cmd -q -B -Pmysql-it verify` 核对锁、回滚和提交后 Watch。真实 CLI 的三次 503、健康读取和 304 用 `python -B config-center-client/src/test/fixtures/watch_recovery.py recover`，精确名称和旧缓存切换已包含在上面的实包对照。

## Config-Plan07 幂等写入与回执

重试同一笔保存时发送原编号和原请求。原操作成功时，客户端会拿到当时的结果和本次 trace。另发一笔新操作时使用新编号。若响应显示 409 是占位竞争，用原请求再试。编号复用冲突应先核对请求。字段、权限和存储合同见 [写入回执](WRITE_RECEIPTS.md)。

### 测试与实包断连演练

在仓库根使用 JDK 17。准备 `mysqld`、`mysql`、`mysqladmin` 与 Python 3。实包脚本自建一次性 MySQL、合成账号和数据。先构建当前 JAR，再从审查基线重建旧 V4 JAR，输出放在本机 Git 忽略区。

```powershell
.\mvnw.cmd -q -B clean verify
$old = (New-Item -ItemType Directory -Force "docs/delete/v4-$([guid]::NewGuid())").FullName
git archive --format=zip -o "$old/src.zip" 5e1353811cceb76d5effb164342ccf5b9e822f8b -- .mvn mvnw mvnw.cmd pom.xml config-center-server config-center-client
Expand-Archive -LiteralPath "$old/src.zip" -DestinationPath "$old/src"
Push-Location "$old/src"
try { .\mvnw.cmd -q -B -DskipTests package } finally { Pop-Location }
python -B config-center-server/src/test/fixtures/idempotency_replay.py --baseline-jar "$old/src/config-center-server/target/config-center-server-1.0.0.jar"
```

日志位于 `docs/delete/Config-Plan07/`。脚本启动旧 JAR 写入真实 V4 样本，比较 V5 升级前后的旧表和迁移记录。代理在收到配置保存和旗标回滚的 HTTP 200 后断开调用方连接。独立 HTTP 与 SQL 读回原提交。另有后写覆盖、相同内容不同编号和零转发对照。重启同一持久库的应用后，旧 key 验证 403，当前获授权 key 取回原结果。已有历史与 revision 保持，已挂起 Watch 走无变化超时。零转发场景的第一次重试才写入。

在另一个独占的 `config_center_it` 设置 [README 的 MySQL 验证环境](../../README.md#persistent-start-mysql--docker-compose)，确保实际认证账号为专用非 root 账户。Failsafe 会清理和重建隔离测试表。

```powershell
.\mvnw.cmd -q -B -Pmysql-it verify
Get-Content config-center-server/target/failsafe-reports/*Mysql*IT.txt
```

用 [examples.http](../../examples.http) 也可手工验证首次写入、同编号重放、冲突和新编号。开始时使用空库与合成 key，按顺序发送请求。运行重启后的示例时保持同一隔离的 MySQL，先停止旧进程再启动当前 JAR。H2 内存库适合即时重放演示。

### V5 切换与恢复

1. 在隔离复本确认实际 catalog、账号、当前 Flyway 版本及备份可恢复。对存量 V3 保持 V4 预检和映射步骤。V4 完整后，V5 执行单条 `CREATE TABLE`，既有五张业务表保持。
2. 停掉早期构建和其他写入者后，用批准的配置启动当前构建，核对 Flyway 成功记录及 Hibernate validate。V4 首次升级后，预期回执表为空。先用合成编号跑一次写入与重放，再启用依赖该协议的调用方。
3. 如果升级失败，先在隔离环境读取 `flyway_schema_history` 并核对 `write_receipt` 的实际结构。保留故障现场。使用升级前的一致性备份恢复到独立实例并校验，再选择重试。V5 表变更由 Flyway 管理。上线后回执进入相同的一致性备份。只恢复业务表会丢失重放依据。

```sql
SELECT version, script, checksum, success FROM flyway_schema_history ORDER BY installed_rank;
SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='write_receipt';
```

旧 JAR 会忽略编号。依赖编号的客户端只向支持 V5 协议的服务写入。恢复到旧备份后先核对哪些请求与回执已保全，再恢复这些调用方。

## Config-Plan06 精确名称与迁移

升级后，客户端应使用为对象选定的精确名字。旧历史里的拼写继续可见。先在隔离副本确定归属，再用匹配的构建切换。完整规则、映射格式、DDL 与恢复边界见 [精确名称与 V4 迁移](IDENTITY_MIGRATION.md)。

### 检查 V3 副本及正常启动

使用 JDK 17、MySQL 8.0.17+、utf8mb4 连接。核对隔离实例与 `config_center_it`，使用专用非 root 帐户。沿用 README 的 MySQL 连接环境变量与授权配置。关停连接该库的旧实例及其他写入者。先保存升级前备份，并验证能恢复到另一个独立实例。

```powershell
.\mvnw.cmd -q -B clean verify
$jar = 'config-center-server/target/config-center-server-1.0.0.jar'
java -jar $jar --spring.profiles.active=mysql --check-identity-migration
```

有多个可能归属时，输出 `IDENTITY_V4_GROUP` 和明确拒绝信息。检查命令不修改表或 Flyway 历史。如果需要映射，按报告填写合成或私下批准的映射文件。使用相同的有效授权配置复查，然后在这个隔离副本启动。

```powershell
$mapping = 'D:\path\to\approved-private-map.json'
java -jar $jar --spring.profiles.active=mysql --check-identity-migration "--config-center.identity-mapping-file=$mapping"
java -jar $jar --spring.profiles.active=mysql "--config-center.identity-mapping-file=$mapping"
```

`IDENTITY_V4_READY (read only)` 表示映射与检查快照一致。无歧义旧库正常启动可自动迁移，空库会顺序应用 V1–V5。成功后确认 Flyway V4/V5、Hibernate validate、精确读取、历史和 Watch。映射文件只在执行 V4 时读取，归档检查和审批证据后常规重启可省略映射。正常启动保持既有 loopback 和 API 设置。

### 合成演练和失败恢复

先在仓库根构建本轮包。旧包从本次审查基线重建，产物放本机忽略区。Windows 使用以下命令。在 PATH 准备 `mysqld`、`mysql`、`mysqladmin`、`mysqldump` 与 Python 3。

```powershell
.\mvnw.cmd -q -B clean verify
$baseline = (New-Item -ItemType Directory -Force "docs/delete/v3-$([guid]::NewGuid())").FullName
git archive --format=zip -o "$baseline/src.zip" 52e887daab5881974ec7d9bf06a707d2110fb378 -- .mvn mvnw mvnw.cmd pom.xml config-center-server config-center-client
Expand-Archive -LiteralPath "$baseline/src.zip" -DestinationPath "$baseline/src"
Push-Location "$baseline/src"
try { .\mvnw.cmd -q -B -DskipTests package } finally { Pop-Location }
python -B config-center-server/src/test/fixtures/identity_upgrade.py --baseline-jar "$baseline/src/config-center-server/target/config-center-server-1.0.0.jar"
```

脚本使用不同随机 loopback 端口、各自的数据目录和一次性非 root 账户。它会生成合成 V3、逐项读回、导出逻辑备份、验证缺映射拒绝及部分 DDL 后启动失败，然后将备份恢复到另一隔离实例，运行旧 JAR 和本轮 JAR。预期看到旧表未改时的预检拒绝、第一条 DDL 已提交的权限故障、V3 恢复可用、V4 后独立的 v1 与旧历史、各自的 Watch 和真实 CLI 200/304。证据位于 `docs/delete/Config-Plan06/`。Failsafe 中的空库、V2→V3→V4 和异常归属检查可使用另一个独占 `config_center_it` 重现。

```powershell
.\mvnw.cmd -q -B -Pmysql-it verify
Get-Content config-center-server/target/failsafe-reports/*Mysql*IT.txt
Get-FileHash -Algorithm SHA256 config-center-server/target/config-center-server-1.0.0.jar
```

恢复时保留现有失败实例和日志供比对。检查失败库实际的 Flyway 状态和 owner 列，该命令用失败库单独的客户端配置，只做查询。

```powershell
mysql.exe "--defaults-extra-file=$failedOptions" --database=config_center_it --execute="SELECT version, success FROM flyway_schema_history ORDER BY installed_rank; SELECT TABLE_NAME, COLUMN_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND COLUMN_NAME='owner_id';"
```

从升级前备份导入一个空的独立 V3 副本。手工演练可用下列命令，`$sourceOptions` 与 `$restoreOptions` 分别指向私有 MySQL 客户端配置文件（`[client]` 节）。先核对两份配置指向不同的 loopback 端口，两个实例中的授权、catalog 以及恢复目标为空。备份的数据库名为 `config_center_it`。

```powershell
$dumpDir = (New-Item -ItemType Directory -Force 'docs/delete').FullName
$dump = Join-Path $dumpDir "v3-backup-$([guid]::NewGuid()).sql"
mysqldump.exe "--defaults-extra-file=$sourceOptions" --single-transaction --no-tablespaces --set-gtid-purged=OFF --skip-lock-tables --skip-add-drop-table "--result-file=$dump" config_center_it
if ($LASTEXITCODE -ne 0) { throw '备份失败' }
Get-FileHash -Algorithm SHA256 -LiteralPath $dump
mysql.exe "--defaults-extra-file=$restoreOptions" --database=config_center_it "--execute=source $($dump.Replace('\','/'))"
if ($LASTEXITCODE -ne 0) { throw '恢复失败' }
```

先核对恢复库的五张表、约束及 V1–V3 的 Flyway 记录，再用旧 JAR 读回历史。复跑 V4 时只在这个已验证副本重做预检。以上 Python 演练会自动核对并执行整套流程。

### 客户端切换

1. 稳定 V4 后再启用新 CLI。先核对目标精确 `app/env` 和要评估的 flag name，把仍用旧别名的调用方切换到已批准的名字。
2. 保留或私下归档用户目录中的 `.config-center-client-cache.json` 与 `.config-center-demo-client-cache.json`。新 CLI 使用独立的 `.config-center-client-cache-exact-v1.json`，会忽略这两个旧文件，首次有效 200 写入新文件，后续才使用新 ETag 和回退缓存。
3. 从新 CLI 输出确认初始读取的 `source=http-200`、实际配置与 scope，并在第二次读取观察合法 `etag-304`。新 CLI 连到旧服务端时仍会得到旧比较结果。若误用旧服务端填充了新文件，切换前把这份新文件也隔离。旧 CLI 的离线缓存只能在客户端停止或归档。

## Config-Plan05 并发与结果读回

同时保存时，用返回的版本和独立读回区分哪个请求成功。保存后连接中断时，先核对当前、历史和 revision，再判断目标效果是否出现。测试故障由夹具引入，使用测试账号与数据。

### 快测与真实 MySQL HTTP

在仓库根使用 JDK 17。MySQL 命令开始前，依照 [README 的隔离 MySQL 步骤](../../README.md) 配置 `CONFIG_CENTER_DB_URL`、`CONFIG_CENTER_DB_USERNAME`、`CONFIG_CENTER_DB_PASSWORD` 和测试 API key，确认 URL 实际指向 loopback 上独占的 `config_center_it` 及专用非 root 账户。该 profile 包含重建测试表的 V2 升级 fixture，须使用可丢弃测试库。

```powershell
.\mvnw.cmd -q -B clean verify
.\mvnw.cmd -q -B -Pmysql-it verify
Get-Content config-center-server/target/failsafe-reports/com.example.configcenter.Mysql*IT.txt
```

两类写入在 `MysqlWriteConsistencyIT` 中经真实 HTTP 和不同数据库连接交错。测试输出连接号、隔离级别及 HTTP 状态。现行 `MysqlNameIdentityIT` 与 `H2NameIdentityTest` 检查 V4 精确合同。要重复 V3 等值对照，按上文的 `git archive` 方式从 `52e887d` 提取旧源码，在另一份隔离测试库运行该版本的测试。关键结果见 [写入证据](WRITE_CONSISTENCY.md)。

### 实包响应丢失

先执行上述构建。若运行 MySQL 实包，保留独立测试库及同一套环境变量，将 MySQL 命令行客户端加入 PATH。脚本核验实际 catalog 和认证账户，再从随机 loopback 端口启动 JAR。使用合成 app 和 key，限流只在该进程关闭。日志保存在 Git 忽略的 `docs/delete/Config-Plan05/`。

```powershell
python config-center-server/src/test/fixtures/write_outcome.py --profile local
python config-center-server/src/test/fixtures/write_outcome.py --profile mysql
Get-FileHash -Algorithm SHA256 config-center-server/target/config-center-server-1.0.0.jar
```

- `Caller` 输出调用方看到的断连。`Injector` 分别输出转发 1 次且上游 HTTP 200 的 trace，或转发 0 次。
- `Reader` 经独立 HTTP 请求读回版本、历史和 revision。MySQL 场景另从新 SQL 连接打印并校验同一结果。已提交场景显示 v2/v1 和 revision 加一，未到达场景维持旧状态。
- 后写覆盖时，当前 v3 和历史中的目标 v2 同时存在。另一客户端提交相同内容时，快照可证明效果存在，当前历史无法唯一归因。只在单独的重复写入对照中测试发送旧前置版本和无条件重复，预期分别为 409 和新增版本。

## Config-Plan04 Watch 演练

客户端会先记下服务器观察到的变更。配置读取失败时，旧值可以继续显示并标为待同步。连接恢复后客户端直接补取，成功应用后才用确认的游标订阅下一轮。持续失败时，它给出最后进度并以失败退出。

### 正常运行

使用 JDK 17，在仓库根验证并启动新的本机 H2 进程。

```powershell
.\mvnw.cmd -q -B clean verify
java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local --server.address=127.0.0.1
```

在第二个终端运行 [README 的演练写入](../../README.md#local-walkthrough) 后，使用一个隔离的缓存目录运行两轮。首次 Watch 后有一次读取，可以用有效 304 确认。接下来没有新的写入时，无变化响应直接计一轮。

```powershell
$watchCache = (New-Item -ItemType Directory -Force "docs/delete/watch-normal-$([guid]::NewGuid())").FullName
.\mvnw.cmd -q -B -pl config-center-client spring-boot:run "-Dspring-boot.run.jvmArguments=-Duser.home=`"$watchCache`"" '-Dspring-boot.run.arguments=--demo.baseUrl=http://127.0.0.1:8080 --demo.watch.rounds=2 --demo.watch.timeoutSeconds=1'
```

预期两轮结束均为 `pendingSync=false`，最终 `reason=completed`，然后运行功能评估。用 `--demo.watch.enabled=false` 或 `--demo.watch.rounds=0` 可单次读取和评估。

### 故障、重启和预算耗尽

用上面构建的 JAR，在仓库根执行下列命令。演练脚本使用 Python 3 标准库，在随机 loopback 端口启动自己的 H2 JAR 和受控 HTTP 服务，从 Maven 正常入口启动 Java CLI。每次使用隔离缓存和合成数据，日志写入 `docs/delete/Config-Plan04/`。

```powershell
python config-center-client/src/test/fixtures/watch_recovery.py recover
python config-center-client/src/test/fixtures/watch_recovery.py restart
python config-center-client/src/test/fixtures/watch_recovery.py 503
python config-center-client/src/test/fixtures/watch_recovery.py connection
python config-center-client/src/test/fixtures/watch_recovery.py timeout
# 用 all 一次运行上述场景
python config-center-client/src/test/fixtures/watch_recovery.py all
```

| 场景 | 预期请求与输出 |
| --- | --- |
| `recover` | 先确认旧值和 revision 1，再写入一次新值。配置 GET 连续 3 次收到 503，CLI 显示 `source=stale-cache`、`confirmedCursor=1`、`pendingSync=true`。断路器等待后，补取 200 并将新值写入缓存，确认游标 2。下一次为 `WATCH since=2`。默认 5 轮正常完成。 |
| `restart` | 确认 revision 3 后重启 H2。下一次 Watch 返回 `changed=false, latestVersion=0`，补取空列表后确认 0。随后一条写入使 `WATCH since=0` 观察到 revision 1，新值应用成功。 |
| `503`、`connection`、`timeout` | 一次更新通知之后持续拒绝配置读取、拒绝连接或不返回响应。缩短为 6 秒调度窗口和 250 ms 重试间隔，普通读取超时设为 900 ms。CLI 输出 `reason=recovery-budget-exhausted`、`confirmedCursor=1`、`pendingSync=true` 和失败原因，缓存仍为旧值，进程失败退出。脚本断言这些结果并打印实测恢复时长。 |

### 如何读懂进度和时间

- `observedRevision` 是 Watch 的观察值。长轮询超时时返回的是注册时的 revision。`confirmedCursor` 是在该观察之后成功读取所覆盖的下界。列表可能已经包含更晚的写入，下轮仍从确认的观察值追赶。
- `source=http-200` 表示读取成功。`source=etag-304` 表示服务端验证了这次发送的具体 ETag，客户端可采用对应缓存。`source=stale-cache` 表示瞬时失败后暂用旧值，待同步的观察仍保留。`cacheHit` 包括两类缓存使用，`etag304` 只计验证成功。
- 初次订阅一律从 0 开始。首个有效 Watch 观察后，再读取或验证一次列表，包括 revision 0 的空库。已同步后的较小观察值也会先读取，再采用较小的游标。数据库重建后若 revision 已追平或超过旧值，现有协议无法可靠辨认重建。
- `demo.watch.rounds` 默认 5。有效观察和所需读取都成功后才完成一轮。临时故障共享同一个恢复窗口，窗口自第一次可恢复失败起按单调时间计。`recoveryTimeoutSeconds` 默认为 30，`recoveryRetryIntervalMs` 默认为 1000。临近截止时，最后一次等待缩短为剩余时间。已观察待同步的更新会直接重试列表。Watch 暂不可达时会用已确认游标继续观察。
- 连接失败、读超时、5xx 和半开前的断路器拒绝可在窗口内恢复。4xx（含 429）、不合规的 304 和无效响应是终止错误。调度到期后，已进入 `getWithRetry` 的请求可完成最多三次内部尝试。有效读取可以确认，线程取消时保留中断并停止后续调用及确认。连接与读取超时沿用各自的传输设置，持续输送字节的响应可能超过本次测得的时长。

## Config-Plan03 实包复现

在第一个终端运行 `clean verify`，用 JDK 17 启动本轮 JAR。H2 适合从空库快速复现。已准备独占测试库时，按 README 设置 MySQL 连接、非 root 账户和 `CONFIG_CENTER_API_KEY`，把 `local` 改为 `mysql`。本轮实测使用后者。

```powershell
.\mvnw.cmd -q -B clean verify
Get-FileHash config-center-server/target/config-center-server-1.0.0.jar -Algorithm SHA256
java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local --server.address=127.0.0.1 --rate-limit.enabled=false
```

第二个终端从同一仓库执行，使用新启动的合成演示库。`local` 默认为 `kr-dev-key`；MySQL 使用启动服务时传入的 key。以下两个 rollback 的返回版本都是 3。读回的历史最新项为 v1 的快照，原因含完整的 500 个「文」。再次针对同一数据执行会继续追加新版本。

```powershell
$base = 'http://127.0.0.1:8080'
$key = if ($env:CONFIG_CENTER_API_KEY) { $env:CONFIG_CENTER_API_KEY } else { 'kr-dev-key' }
function Write-Demo($path, $fields) {
    $body = @{ app='demo-app'; env='dev' } + $fields
    Invoke-RestMethod -Method Post -Uri "$base/api/$path" -Headers @{'X-API-Key'=$key} -ContentType 'application/json; charset=utf-8' -Body ($body | ConvertTo-Json -Compress)
}
foreach ($v in 1,2) {
    (Write-Demo 'configs' @{key='sample'; value="$v"}).data.version
    (Write-Demo 'features' @{name='new-checkout'; enabled=($v -eq 1); rolloutPercentage=0; allowlist=@('a+b')}).data.version
}
$reason = '文' * 500
(Write-Demo 'configs/rollback' @{key='sample'; targetVersion=1; reason=$reason}).data.version
(Write-Demo 'features/rollback' @{name='new-checkout'; targetVersion=1; reason=$reason}).data.version
foreach ($path in 'configs/history?app=demo-app&env=dev&key=sample','features/history?app=demo-app&env=dev&name=new-checkout') {
    $rows = (Invoke-RestMethod "$base/api/$path").data
    ($rows.version -join ',')                  # 3,2,1
    $rows[0].reason -ceq "rollback-to=1, $reason"  # True
}
curl.exe -si -H 'If-None-Match: *' -H 'X-Trace-Id: plan03' "$base/api/configs?app=demo-app&env=dev"
curl.exe -si -H 'If-None-Match: *' -H 'X-Trace-Id: plan03' "$base/api/configs?app=empty&env=test"
(Invoke-RestMethod "$base/api/features/evaluate?app=demo-app&env=dev&name=new-checkout&userId=a%2Bb").data
$demoCache = (New-Item -ItemType Directory -Force "docs/delete/cli-$([guid]::NewGuid())").FullName
1..2 | ForEach-Object {
    .\mvnw.cmd -q -B -pl config-center-client spring-boot:run "-Dspring-boot.run.jvmArguments=-Duser.home=`"$demoCache`"" '-Dspring-boot.run.arguments=--demo.baseUrl=http://127.0.0.1:8080 --demo.watch.enabled=false --demo.userId=a+b'
}
```

两次 curl 应看到 304、ETag、`X-Trace-Id: plan03` 和空响应体。直接请求及两次 CLI 均应返回 `userId=a+b`、`enabled=true`、`bucket=-1`；CLI 第二次配置读取应显示 `cacheHit=1, etag304=1`。

## V3 升级与构建回退

本节记录 Plan03 在 V3 上验证过的旧构建回退。要重现该历史结论，从 `1060563` 的源码运行下面的测试，并使用独占的 `config_center_it`。现行测试会在验证 V3 后继续迁移到 V4。V4 构建回退见 [V4 恢复步骤](#合成演练和失败恢复)。主 Compose 实例不参与。

```powershell
.\mvnw.cmd -q -B -Pmysql-it verify
Get-Content config-center-server/target/failsafe-reports/com.example.configcenter.Mysql*IT.txt
```

新库从 V1 顺序迁移至 V3。旧 V2 库只新增 V3，将两列历史原因扩为 `VARCHAR(600) NULL`。测试在迁移前后对照全部既有业务行、索引键及唯一性、V1/V2 的脚本名与校验和，检查再次 migrate 为 0，随后 Hibernate 启动校验并执行满长回滚。在需保留数据的环境升级前，先保存可恢复的私有备份。

**只回退应用构建。** 在同一专用 MySQL 上，用新 JAR 跑过上一节冒烟并停止新服务后，保留 V3 结构、连接、授权和数据。以下命令从实际审查基线重建旧 JAR，输出位于本机待清理区。

```powershell
$old = (New-Item -ItemType Directory -Force "docs/delete/base-$([guid]::NewGuid())").FullName
git archive --format=zip -o "$old/src.zip" b546f7b6e402c2de4c3ad82323a592f509770505 -- .mvn mvnw.cmd pom.xml config-center-server config-center-client
Expand-Archive -LiteralPath "$old/src.zip" -DestinationPath "$old/src"
Push-Location "$old/src"
try { .\mvnw.cmd -q -B -DskipTests package } finally { Pop-Location }
java -jar "$old/src/config-center-server/target/config-center-server-1.0.0.jar" --spring.profiles.active=mysql --server.address=127.0.0.1
```

旧包在本机 MySQL 8.0.46 上记录 schema 3 高于内置迁移 2 的警告，Flyway 与 Hibernate 随后完成启动。两个 history GET 均读到 `3,2,1` 及完整原因。再用两个 `Write-Demo` rollback 提交满长原因，均生成业务版本 v4。此结论限 `b546f7b` 与 V3 schema。切回该构建也会恢复旧条件请求行为，旧 CLI 仍有查询编码缺陷。若必须恢复 V2 数据库，在独立实例还原升级前备份并核对数据。直接缩列可能破坏 V3 已写入的长原因。

## 换机和排障

1. 从 `MyKr-YSteinsK/config-center` 检出 `master`，核对 GitHub 账号与上游，先完成 H2 快测。
2. 按需以密码管理器恢复私有配置，同时保留已有 `.env`。只有需要旧持久数据时才恢复私有数据库备份。代码、迁移与私有运行状态通过各自的渠道恢复。
3. Maven Wrapper 下载失败时先区分用户缓存可写性、网络和仓库依赖解析。历史 Windows 环境发生过 JUnit 临时目录清理失败。遇到同类错误可在当前终端给测试 JVM 设置 `-Djava.io.tmpdir=<已创建的仓库内临时目录>`，并确认该路径被 Git 忽略。
4. Docker 报 `dockerDesktopLinuxEngine` 管道不可用时先核实引擎是否运行。Compose 配置能解析只说明配置语法通过，实际启动、恢复和保卷重启应查看对应运行结果。
5. GitHub 推送使用本机凭证管理器，提交身份与认证账号分别检查。TLS 故障先诊断本机传输与认证设置，保留证书校验。
6. Windows 上 Maven 清理报 JAR 被占用时，确认 PID 对应本次演练启动的进程并正常停止。脚本可从 `java.home` 定位实际 JDK 的 `bin/java.exe`，便于退出时关闭启动的 JVM。
