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
| MySQL 测试 | 只启动 `compose.mysql-it.yml` 指定的隔离项目/卷和 `config_center_it`，loopback 端口默认 `33306`。验证前核对实际 URL 与项目名，保持主库数据卷完整。 |
| 本地配置 | 从 [模板](../../.env.example) 创建缺失的 `.env`，实际密码和 API key 通过私有方式恢复。手工运行 MySQL JAR 另外设置 README 列出的必需环境变量。 |
| 客户端缓存 | `${user.home}/.config-center-client-cache.json`，兼容旧 `.config-center-demo-client-cache.json`。缓存是派生数据且可能含配置明文，正常从服务重新生成。 |
| IDE 和构建输出 | `.idea/`、`target/`、Maven 用户缓存位于本地。接管前废旧工作区材料的去向见 [历史整理](HISTORY.md)。 |

直接运行 JAR 由 `SERVER_ADDRESS` 控制监听，默认 `127.0.0.1`。持久 Compose 的 `0.0.0.0` 只在容器内监听，主机侧由 `SERVER_BIND_ADDRESS` 控制并默认为 loopback。Swagger、H2 Console、Actuator 以及实验用 API key 在这个边界内使用。

## 换机和排障

1. 从 `MyKr-YSteinsK/config-center` 检出 `master`，核对 GitHub 账号与上游，先完成 H2 快测。
2. 按需以密码管理器恢复私有配置，同时保留已有 `.env`。只有需要旧持久数据时才恢复私有数据库备份。代码、迁移与私有运行状态通过各自的渠道恢复。
3. Maven Wrapper 下载失败时先区分用户缓存可写性、网络和仓库依赖解析。历史 Windows 环境发生过 JUnit 临时目录清理失败。遇到同类错误可在当前终端给测试 JVM 设置 `-Djava.io.tmpdir=<已创建的仓库内临时目录>`，并确认该路径被 Git 忽略。
4. Docker 报 `dockerDesktopLinuxEngine` 管道不可用时先核实引擎是否运行。Compose 配置能解析只说明配置语法通过，实际启动、恢复和保卷重启应查看对应运行结果。
5. GitHub 推送使用本机凭证管理器，提交身份与认证账号分别检查。TLS 故障先诊断本机传输与认证设置，保留证书校验。
