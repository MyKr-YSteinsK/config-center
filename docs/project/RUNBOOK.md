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
| 本地配置 | 从 [模板](../../.env.example) 创建缺失的 `.env`，实际密码和 API key 通过私有方式恢复。手工运行 MySQL JAR 另外设置 README 列出的必需环境变量。 |
| 客户端缓存 | `${user.home}/.config-center-client-cache.json`，兼容旧 `.config-center-demo-client-cache.json`。缓存是派生数据且可能含配置明文，正常从服务重新生成。 |
| IDE 和构建输出 | `.idea/`、`target/`、Maven 用户缓存位于本地。接管前废旧工作区材料的去向见 [历史整理](HISTORY.md)。 |

直接运行 JAR 由 `SERVER_ADDRESS` 控制监听，默认 `127.0.0.1`。持久 Compose 的 `0.0.0.0` 只在容器内监听，主机侧由 `SERVER_BIND_ADDRESS` 控制并默认为 loopback。Swagger、H2 Console、Actuator 以及实验用 API key 在这个边界内使用。

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

先确认测试连接指向专用 `config_center_it`；整段测试期间由一个运行独占此库。按 [README 的 MySQL 测试步骤](../../README.md) 准备空测试实例和凭证，再执行以下命令。Failsafe 串行运行 `MysqlPersistenceIT` 和 `MysqlV2UpgradeIT`。后者确认数据库名和认证账户后重建五张业务表及 `flyway_schema_history`，只应用 V1/V2，写入旧数据，再升级和验证；结束时为 V3，保留合成样本。主 Compose 实例不参与。

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

旧包在本机 MySQL 8.0.46 上记录 schema 3 高于内置迁移 2 的警告，Flyway 与 Hibernate 随后完成启动。用前述两个 history GET 均读到 `3,2,1` 及完整原因；用两个 `Write-Demo` rollback 再提交满长原因，均生成 v4。此结论限 `b546f7b` 与当前 MySQL profile；切回该构建也会恢复其条件请求行为，旧 CLI 仍有查询编码缺陷。若必须恢复 V2 数据库，在独立实例还原升级前备份并核对数据。直接缩列可能破坏 V3 已写入的长原因；应用回退无需缩列或更改 Flyway 历史。

## 换机和排障

1. 从 `MyKr-YSteinsK/config-center` 检出 `master`，核对 GitHub 账号与上游，先完成 H2 快测。
2. 按需以密码管理器恢复私有配置，同时保留已有 `.env`。只有需要旧持久数据时才恢复私有数据库备份。代码、迁移与私有运行状态通过各自的渠道恢复。
3. Maven Wrapper 下载失败时先区分用户缓存可写性、网络和仓库依赖解析。历史 Windows 环境发生过 JUnit 临时目录清理失败。遇到同类错误可在当前终端给测试 JVM 设置 `-Djava.io.tmpdir=<已创建的仓库内临时目录>`，并确认该路径被 Git 忽略。
4. Docker 报 `dockerDesktopLinuxEngine` 管道不可用时先核实引擎是否运行。Compose 配置能解析只说明配置语法通过，实际启动、恢复和保卷重启应查看对应运行结果。
5. GitHub 推送使用本机凭证管理器，提交身份与认证账号分别检查。TLS 故障先诊断本机传输与认证设置，保留证书校验。
