# config-center｜持久化与本地部署计划

更新时间：2026-07-16

## 1. 决策与目标

Phase 0–8 已完成后，项目进入新的独立演进计划。本计划不覆盖旧计划，也不继续堆叠 Feature Flag 规则或客户端 SDK 能力；当前唯一方向是补齐一个正常 Java 后端项目应具备的持久化、数据库迁移、容器化和可复现部署能力。

最终目标：

- 保留 H2 作为快速学习和单元测试基线；
- 新增 MySQL 持久化运行模式；
- 使用 Flyway 管理 MySQL 表结构，禁止依赖 Hibernate 自动建表；
- 使用 Docker Compose 一条命令启动 MySQL 与服务端；
- 自动验证首次建库、重复启动、服务重启后数据保留、历史/回滚/revision 持久化；
- 保持单体、单实例、轻量，不引入 Redis、消息队列、Kubernetes 或复杂权限系统。

## 2. 当前基线

当前服务端仍使用 H2 内存数据库，`spring.jpa.hibernate.ddl-auto=update`，服务停止后数据消失。服务端模块尚未加入 MySQL Driver 和 Flyway。现有配置、历史、回滚、namespace revision、Feature Flag、ETag、Watch、鉴权、限流、指标和客户端可靠性已经完成并有回归测试。

本计划必须保护这些已验证行为，不借数据库改造重写业务层。

## 3. 全局约束

- Java 17、Spring Boot 3、Maven 多模块保持不变。
- H2 快速模式继续可用，现有测试不能被 MySQL 环境绑死。
- MySQL 模式使用 Flyway 作为唯一 schema 来源。
- MySQL 模式使用 `ddl-auto=validate`，禁止 `update/create/create-drop`。
- 不提交数据库密码、API Key、`.env` 或任何真实凭据。
- 应用不得使用 MySQL `root` 账号。
- Docker Compose 只包含 `mysql` 与 `config-center-server`，不加入 Redis、Prometheus Server、Grafana、Nginx 或前端。
- 每个子阶段单独提交，先跑聚焦测试，再跑完整 `clean verify`。
- 每次代码修改同步更新 `docs/project-map.md`、`docs/dev-plan.md` 与 `docs/patch-log.md`；公开运行方式变化时更新 README。
- 每个 Codex handoff 最后必须包含：`Use frugal-dev-runner. Do not expand scope.`

## 4. 推荐的运行模式

### 4.1 H2 快速模式

用途：学习、快速启动、普通单元/集成测试。

建议命令：

```powershell
java -jar config-center-server/target/config-center-server-1.0.0.jar --spring.profiles.active=local
```

特点：

- 不需要 Docker 或外部数据库；
- 数据随进程退出而消失；
- 保持最快反馈；
- 不作为持久化能力证明。

### 4.2 MySQL 持久化模式

用途：完整本地演示、数据库迁移验证、服务重启后数据保留。

建议命令：

```powershell
docker compose up -d --build
```

特点：

- MySQL 数据存储在命名 volume；
- Flyway 自动执行版本化 migration；
- Hibernate 只验证实体与 schema；
- 服务重启或容器重建后，未删除 volume 时数据仍存在。

## 5. 凭据与 Codex 输入策略

### 5.1 首选方案：不提供你个人 MySQL 凭据

Codex 应使用 Docker Compose 创建项目专用数据库与项目专用账号。开发凭据只存在于本地 `.env`，并由 `.env.example` 提供字段模板。

建议环境变量：

```dotenv
MYSQL_DATABASE=config_center
MYSQL_USER=config_center_app
MYSQL_PASSWORD=replace-with-local-dev-password
MYSQL_ROOT_PASSWORD=replace-with-local-root-password
CONFIG_CENTER_API_KEY=replace-with-local-api-key
```

要求：

- `.env` 必须加入 `.gitignore`；
- `.env.example` 只放占位值，不放真实密码；
- 应用连接使用 `MYSQL_USER`，不使用 root；
- root 密码只用于 MySQL 容器初始化；
- Codex 报告中不得回显密码。

### 5.2 可选方案：使用你已安装的本地 MySQL

只有当 Docker 不可用或你明确要求连接现有 MySQL 时，才需要提供以下信息：

```text
host
port
数据库名
专用用户名
专用密码
MySQL 主版本
```

仍然不要把密码直接写进 Codex handoff。把它放进本机环境变量或未提交的 `.env`：

```dotenv
CONFIG_CENTER_DB_URL=jdbc:mysql://localhost:3306/config_center?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai
CONFIG_CENTER_DB_USERNAME=config_center_app
CONFIG_CENTER_DB_PASSWORD=your-local-password
```

Codex 只需要知道变量名，不需要知道变量值。

### 5.3 Codex 开工前需要确认的信息

- Docker Desktop 是否已安装并正在运行；
- `docker version` 与 `docker compose version` 是否成功；
- 本机 `3306`、`8080` 是否被占用；
- 当前 Git 工作区是否干净；
- Phase 8 最新 commit SHA；
- 是否接受 Docker Compose 使用项目专用开发密码；
- 是否保留 H2 作为默认 profile，或要求必须显式选择 `local`。

默认决策：保留 H2 作为 `local` profile；MySQL 必须显式选择 `mysql`，Docker 容器自动选择 `mysql`。

---

# Phase 9A｜数据库策略与 Profile 基线 `[x]`

## Goal

先建立清晰的配置边界，确保后续 MySQL 和 Docker 改造不会污染现有 H2 测试链路。

## Scope

- 将当前公共配置与数据源配置拆开；
- 新增 `local`、`mysql`、`test` Profile；
- 保证默认测试仍能使用 H2；
- 定义统一的数据库环境变量；
- 新增配置绑定测试或 context 启动测试。

## Required behavior

### `application.yml`

仅保留公共配置：

- `server.port`
- application name
- JPA 公共设置，例如 `open-in-view=false`
- rate limit
- management
- logging
- API Key

### `application-local.yml`

- H2 内存数据源；
- H2 Console；
- `ddl-auto=update` 或保留当前本地行为；
- Flyway 关闭；
- 只用于快速本地运行。

### `application-test.yml`

- 独立 H2 内存库；
- 测试间隔离；
- Flyway 关闭；
- 不依赖 Docker 或本地 MySQL。

### `application-mysql.yml`

- 数据源只通过环境变量配置；
- `ddl-auto=validate`；
- Flyway 开启；
- H2 Console 关闭；
- JDBC URL 应包含明确字符集、时区和连接参数；
- 不提供真实密码默认值。

## Non-goals

- 不添加 MySQL 表结构；
- 不添加 Dockerfile；
- 不修改实体或业务逻辑；
- 不在此阶段启动真实 MySQL。

## Likely files

- `config-center-server/src/main/resources/application.yml`
- `application-local.yml`
- `application-test.yml`
- `application-mysql.yml`
- server configuration tests
- `.gitignore`
- `.env.example`
- project docs

## Verification

```powershell
.\mvnw.cmd -q -B -pl config-center-server test
.\mvnw.cmd -q -B clean verify
git diff --check
```

补充验证：

- `local` profile 能启动；
- `test` profile 不访问外部数据库；
- `mysql` profile 缺少凭据时快速失败，并显示缺少变量而非使用错误默认密码。

## Acceptance criteria

- H2 测试不需要 Docker；
- MySQL 配置不含明文密码；
- Profile 职责清晰；
- 现有回归测试全部通过。

## Completion evidence

- 2026-07-21 完成：公共、`local`、`test`、`mysql` 配置边界已拆分。
- `test` 使用随机 H2 且不依赖 Docker；`local` 可启动并通过健康检查。
- `mysql` 缺少数据库变量时快速失败，仅报告缺失变量名。
- 服务端测试、全量 Maven 验证与 `git diff --check` 均通过；命令与结果记录于 `docs/patch-log.md`。

## Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

# Phase 9B｜MySQL 与 Flyway schema `[x]`

## Goal

引入真实 MySQL 持久化和可版本化 schema，确保业务实体、索引、唯一约束、历史表与 namespace revision 能被可靠创建和验证。

## Scope

- 引入 MySQL Connector/J；
- 引入 Flyway MySQL 支持；
- 创建 `V1__init_schema.sql`；
- MySQL 模式改为 `ddl-auto=validate`；
- 使用专用 MySQL 数据库账号；
- 针对 MySQL 执行 migration 和 Spring Context 验证。

## Schema requirements

V1 migration 必须准确创建：

- `config_item`
- `config_item_history`
- `config_namespace_revision`
- `feature_flag`
- `feature_flag_history`
- `flyway_schema_history` 由 Flyway 管理

必须覆盖：

- 主键与自增策略；
- `(app, env, config_key)` 唯一约束；
- `(app, env, name)` 唯一约束；
- `(app, env)` namespace revision 唯一约束；
- history 查询索引；
- `lock_version` 乐观锁字段；
- 所有长度、nullability 和类型与 JPA Entity 一致；
- `allowlist_json` 可容纳当前上限；
- 时间字段使用适合 MySQL 的高精度时间类型；
- 数据库字符集使用 `utf8mb4`。

## Technical notes

- 不根据 Hibernate 自动生成 SQL 后不加审查地提交；
- migration 文件一旦合入，不允许直接修改已发布版本，应通过 `V2__...` 演进；
- 禁止在 application 启动时执行自定义 `schema.sql`；
- Flyway 和 JPA 的启动顺序必须由 Spring Boot 标准机制管理；
- 不为 H2 强行复用 MySQL migration；H2 测试链路可继续关闭 Flyway。

## Non-goals

- 不导入旧 H2 数据；
- 不做数据库备份恢复；
- 不做读写分离；
- 不做连接池专项调优；
- 不做多数据库兼容抽象。

## Verification

至少完成：

```powershell
.\mvnw.cmd -q -B -pl config-center-server test
.\mvnw.cmd -q -B clean verify
```

真实 MySQL 验证：

1. 对空数据库启动 mysql profile；
2. Flyway 执行 V1；
3. Spring JPA validate 成功；
4. 再次启动不重复建表；
5. `flyway_schema_history` 记录成功版本；
6. 使用 API 写入配置和 Feature Flag；
7. 历史、回滚、revision 正常。

## Acceptance criteria

- 空 MySQL 数据库可由 Flyway 自动初始化；
- 重复启动幂等；
- Entity 与 schema 完全匹配；
- H2 测试不回归；
- 不需要 root 账号运行应用。

## Completion evidence

- 2026-07-21 完成：Connector/J、Flyway Core/MySQL 与 `V1__init_schema.sql` 已加入。
- MySQL 8.0.46 空库成功执行 V1，Hibernate `ddl-auto=validate` 成功；第二次启动确认无重复 migration。
- 应用使用专用非 root 账号完成配置与 Feature Flag 的写入、历史、回滚和重启持久化验证。
- H2 服务端测试、全量 Maven 验证与 `git diff --check` 均通过；命令与结果记录于 `docs/patch-log.md`。

## Codex configuration

- Model: GPT-5.6 Sol
- Reasoning: High

---

# Phase 9C｜Dockerfile 与 Docker Compose `[x]`

## Goal

实现一条命令启动可持久化的完整后端环境，并保持容器结构最小。

## Scope

- 新增服务端 Dockerfile；
- 新增根目录 `compose.yml`；
- 新增 `.dockerignore`；
- 使用 MySQL named volume；
- 增加 MySQL healthcheck；
- 服务端等待 MySQL healthy 后启动；
- 通过环境变量注入数据源和 API Key；
- 增加服务端健康检查。

## Dockerfile requirements

建议采用多阶段构建：

```text
builder: JDK 17 + Maven Wrapper
runtime: JRE 17
```

要求：

- 从仓库根目录构建；
- 利用 Maven 依赖缓存层；
- 只将 server 可执行 jar 放入运行镜像；
- 非 root 用户运行应用；
- 暴露 8080；
- 不在镜像中放 `.env`、本地缓存、target 全量目录或 Git 元数据；
- 固定 Java 17，不使用 `latest` 标签作为唯一依据。

## Compose requirements

仅包含：

```text
mysql
config-center-server
```

MySQL：

- 明确 MySQL 主版本；
- 使用 named volume；
- 读取 `.env`；
- healthcheck 使用 `mysqladmin ping`；
- 默认不要求对宿主机暴露 3306；如为了调试开放，应可配置。

Server：

- 使用 `spring.profiles.active=mysql`；
- 连接 Compose 内部服务名 `mysql`；
- `depends_on` 使用 health condition；
- 暴露宿主机 8080；
- healthcheck 调用 `/actuator/health`；
- 不把密码写进镜像或 compose 文件。

## Non-goals

- 不加入 client 容器；
- 不加入 Prometheus/Grafana；
- 不加入 Nginx；
- 不加入多 server 副本；
- 不加入容器编排脚本框架。

## Verification

```powershell
docker compose config
docker compose build --no-cache config-center-server
docker compose up -d
docker compose ps
docker compose logs --no-color config-center-server
```

验证：

- MySQL healthy；
- server healthy；
- Flyway migration 成功；
- `/actuator/health` 返回 UP；
- Swagger 和 API 可访问；
- `docker compose down` 后重新 `up`，数据仍保留；
- `docker compose down -v` 后重新 `up`，Flyway 从空库初始化。

## Acceptance criteria

- 新用户只需 JDK（用于普通构建）和 Docker Desktop 即可运行持久化模式；
- 一条 Compose 命令能启动完整后端；
- 密码不进入 Git；
- 数据 volume 行为明确；
- 镜像仅包含运行所需内容。

## Completion evidence

- 2026-07-22 完成：新增 Java 17 多阶段 server 镜像、双服务 `compose.yml`、构建上下文过滤与 MySQL named volume。
- MySQL 8.4.10 与 server healthcheck 均通过；Flyway 从空库应用 V1，Actuator、Swagger、ping 与授权配置写入通过。
- `docker compose down` 后数据保留；`docker compose down -v` 后旧数据消失且 Flyway 从空库重新应用 V1。
- 无缓存镜像构建、全量 Maven 验证与 `git diff --check` 的命令和结果记录于 `docs/patch-log.md`。

## Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: High

---

# Phase 9D｜自动化 MySQL 验证与 CI `[ ]`

## Goal

避免“本地看起来能启动”成为唯一证据，让 Flyway 与 MySQL 持久化链路能够自动回归。

## Recommended strategy

保留两层测试：

### 快速层

- 现有 H2 测试；
- 每次 `clean verify` 执行；
- 不要求 Docker。

### MySQL 集成层

推荐优先使用 GitHub Actions MySQL service container，或一个明确的 Maven integration-test profile。不要同时引入 Testcontainers 和 CI service 两套重复体系，除非有明确收益。

默认选择：

- GitHub Actions 启动 MySQL service；
- Maven 使用 `mysql-it` profile；
- 运行少量关键 MySQL 集成测试；
- 本地可通过 Docker Compose 执行同一 profile。

## Required MySQL integration coverage

至少验证：

- Flyway 对空库执行；
- Spring Context + JPA validate；
- 配置 upsert/history/rollback；
- Feature Flag upsert/history/rollback；
- namespace revision 在重启或新连接后保留；
- 唯一约束真实生效；
- 乐观锁在 MySQL 上生效；
- UTF-8 中文与 emoji 可以保存读取；
- migration 重复执行不报错。

## CI requirements

- 普通 H2 build-test job 继续保留；
- 新增独立 `mysql-integration` job；
- 使用 CI 专用一次性密码；
- 不读取开发者本地 `.env`；
- MySQL job 失败时上传 surefire 和 Flyway/应用日志；
- 不把 Docker Compose 作为 GitHub Actions 唯一启动方式，优先 service container 保持简单。

## Non-goals

- 不追求所有测试都跑 MySQL；
- 不在 CI 做性能压测；
- 不加入多个 MySQL 版本矩阵；
- 不引入云数据库。

## Verification

```powershell
.\mvnw.cmd -q -B clean verify
.\mvnw.cmd -q -B -Pmysql-it verify
```

以及：

- GitHub Actions 两个 job 均通过；
- MySQL job 使用独立 schema；
- 测试结束后无凭据输出。

## Acceptance criteria

- 日常测试快速且不依赖 MySQL；
- MySQL 特有行为有自动回归；
- Flyway migration 进入 CI 门禁；
- 本地和 CI 使用同一套环境变量契约。

## Codex configuration

- Model: GPT-5.6 Sol
- Reasoning: High

---

# Phase 9E｜持久化端到端验收与文档 `[ ]`

## Goal

把 MySQL/Flyway/Docker 能力整理成一个可复现、可讲述、可交给他人运行的稳定版本。

## End-to-end scenario

1. 复制 `.env.example` 为 `.env`；
2. 填写项目专用开发密码；
3. 执行 `docker compose up -d --build`；
4. 等待 MySQL 和 server healthy；
5. 写入配置版本 1、版本 2；
6. 写入 Feature Flag；
7. 验证历史与回滚；
8. 记录 namespace revision；
9. 重启 server 容器；
10. 验证当前值、历史、Feature Flag、revision 全部保留；
11. 完全停止并重新启动 Compose；
12. 数据仍保留；
13. 执行 `docker compose down -v`；
14. 重新启动，确认空库重新由 Flyway 初始化。

## Documentation updates

README 需要提供两条清晰路径：

### Quick start：H2

适合快速学习和调接口。

### Persistent start：MySQL + Docker Compose

适合完整演示和持久化验证。

同时说明：

- `.env` 创建方法；
- 不要使用真实生产密码；
- `down` 与 `down -v` 的区别；
- Flyway migration 位置和规则；
- 数据 volume 名称；
- 常见端口冲突；
- 如何查看 server/MySQL 日志；
- 如何确认 migration 版本；
- 已知边界仍为单实例、进程内 Watch 和 rate limit。

## Final verification

```powershell
.\mvnw.cmd -q -B clean verify
.\mvnw.cmd -q -B -Pmysql-it verify
docker compose config
docker compose up -d --build
docker compose ps
git diff --check
```

必须记录：

- 测试数量和结果；
- MySQL/Flyway 版本；
- migration 版本；
- 首次启动结果；
- 重启持久化结果；
- volume 删除后的空库重建结果；
- CI 链接或 run 状态；
- 剩余风险。

## Acceptance criteria

- README 命令可复制执行；
- H2 与 MySQL 两种模式均可运行；
- MySQL 数据在正常重启后保留；
- Flyway 能从空库稳定重建；
- 本地与 CI 验证一致；
- 文档不夸大分布式或生产能力。

## Codex configuration

- Model: GPT-5.6 Terra
- Reasoning: Medium

---

# 6. 实施顺序与提交策略

严格顺序：

```text
Phase 9A -> Phase 9B -> Phase 9C -> Phase 9D -> Phase 9E
```

建议 commit：

```text
chore(config): split local mysql and test profiles
feat(db): add mysql persistence and flyway baseline
feat(docker): add persistent mysql compose runtime
test(mysql): add mysql integration verification
 docs: document persistent local deployment
```

每个 commit 必须：

- 工作区改动范围单一；
- 有对应验证证据；
- 文档同步；
- 不提前实现下一 Phase；
- 不混入 package rename、Feature Flag 新规则或 SDK 重构。

# 7. Codex 应获得的材料

开工前提供：

- 仓库地址与本地路径：`D:\CS\config-center`；
- 最新 branch 与 commit SHA；
- 本计划文件；
- `AGENTS.md`；
- `docs/project-map.md`；
- `docs/patch-log.md`；
- Docker Desktop 可用性检查结果；
- 是否存在端口冲突；
- 本地 `.env` 已创建的确认，不提供具体密码文本。

不需要提供：

- 你个人 MySQL root 密码；
- 生产数据库账号；
- 云服务器信息；
- GitHub Secret 明文；
- 真实 API Key。

# 8. 每个 Codex handoff 的固定要求

每次只执行一个 Phase，并使用以下固定章节：

- Goal
- Current context
- Scope
- Non-goals
- Likely files
- UX / API / behavior requirements
- Technical notes
- Verification
- Documentation updates
- Patch-log suggestion
- Required report

最后一行必须是：

```text
Use frugal-dev-runner. Do not expand scope.
```

# 9. 计划完成后的决策门

完成 Phase 9A–9E 后，再重新审计项目。下一步优先级暂定：

1. 精简版 Client SDK extraction；
2. 有真实需求时再扩展 Feature Flag 规则；
3. 暂不进入分布式、集群和复杂管理平台。
