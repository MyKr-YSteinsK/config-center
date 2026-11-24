# 精确名称与 V4 迁移

V4 让数据库、权限和 Watch 对名字使用同一套规则。大小写、重音、空格或 Unicode 表示有差异时，可以各自保存独立对象。旧历史仍显示当时记录的名字，升级后由对应的当前对象读出。

## 精确名称合同

- `app`、`env`、`key`、`name` 用精确字符串比较。H2 使用当前 profile 的精确 `VARCHAR`。MySQL 只将当前配置、当前旗标和 revision 的身份列改为 `utf8mb4_0900_bin`，使用 `NO PAD`。已有长度、非空和类型约束继续执行。HTTP 名称包含未配对代理项时返回 400/4001，配置的授权 scope 含此类字符时启动失败。合法补充字符可无损保存。不同引擎的排序可能不同，每个列表仍用同一有序快照给出 ETag 和数据。[MySQL Unicode 与 NO PAD 说明](https://dev.mysql.com/doc/refman/8.0/en/charset-unicode-sets.html#collation-pad-attributes)
- 查询另一种拼写时，空 namespace 返回空列表和 revision 0。缺少精确当前行或开关时返回原有 404。历史按精确当前对象查询，找不到该对象时历史列表为空。写 key 仍按配置中的精确 app/env 授权，读接口继续使用既有本地边界。
- Watch 的 revision、注册、条带锁与通知都使用精确 scope。配置当前行、历史与 revision 在一个事务提交，成功提交后唤醒对应等待者。两个独立的新 scope 同时写仍可能遇到数据库锁争用，失败方继续按 409 刷新和核对。
- 保留 `userId + ":" + featureName`、SHA-256 分桶以及关闭、allowlist、灰度的优先级。迁移保留当前旗标的精确 name，所以原精确名称的同一用户保持原 bucket。曾经借用旧等值规则查询的名字在未创建时返回 404，新建后使用自身名称分桶。

## 预检与授权归属

`--check-identity-migration` 启动只读 JDBC 检查。使用 V3 库、相同 MySQL profile 和与实际 V4 启动相同的有效授权配置。检查会验证旧列及索引、MySQL 目标 collation 与 UTF-8 连接，按旧表 SQL 等值关系对五张表分组，并用旧等值连接查找每条历史的当前对象。报告行 ID、版本、lock_version、历史关联的当前 ID、scope、名称 UTF-8 HEX、同组授权 scope、归属问题和指纹。授权值不进入报告。报告和映射由所有者私下保管。

| 预检发现 | 处理 |
| --- | --- |
| 单一持久 scope，授权与之相符，或同组无授权 | 保持原 scope。报告的 `authorized` 为空时，该旧组仍没有写授权。 |
| 旧等值组涉及多个持久 scope，或同组授权存在精确拼写竞争 | 默认停止。所有者从实际持久 scope 中选择一个精确目标，且该目标须有对应精确授权。 |
| 孤立历史、非法版本、配置无有效 revision 等异常 | 停止。先核对原始备份和数据来源。版本缺口与历史截取按原样保留。 |
| 审查指纹过期、目标来自另一组、存在多余或重复映射 | 停止。用最新同库检查重新审查。 |
| 授权 scope 没有旧数据组 | 保留其原精确配置。 |

指纹覆盖组内行的内容摘要、原始精确拼写、ID、版本、历史 owner 关系及生效授权 scope。map 是一次性审批输入。为报告中每个 `needsMapping=true` 的可处理组填写一条，指纹与大小写须逐字匹配。以下值为替换用占位符。

```json
{
  "mappings": [
    {
      "fingerprint": "<report fingerprint>",
      "target": {"app": "Plan06-alpha", "env": "Dev"}
    }
  ]
}
```

再次预检时报 `IDENTITY_V4_READY (read only)` 才表示报告与映射一致，返回码 0。失败先看详情与原始数据。升级前关闭所有旧实例和写入者，并保持目标库独占，直至 V4 验证完成。预检与执行之间发生的任何变更都需要重看报告。V4 启动再次扫描当前库并校验映射。

## 物理改动与读取

1. Flyway 验证 V1–V3 后，在 Java V4 中读取并校验完整计划。成功前不执行业务表 DML 或 DDL。
2. 两张历史表各添加 `owner_id`，按旧列 SQL 比较解析到相应的当前主键。历史行的原始字段保留，包括旧拼写、值、说明、灰度、版本、时间、operator、reason 和 V3 增加空间后的原因。
3. 以主键更新已审批的当前 scope/revision scope，保留当前 key/name、主键、业务版本和 lock_version。共享 revision 整体归到选定 scope，数值与版本保持。迁移日志用指纹、表和主键记录源/目标 scope。其它业务字段类型和 collation 保持原定义。
4. 将当前与 revision 的身份列改为 `utf8mb4_0900_bin`，旧复合唯一索引继续按这些列保证唯一性。每张历史表在 `owner_id` 非空后，用 `UNIQUE(owner_id, version)` 替代以原始拼写建立的唯一和查找索引。相同前缀索引支持按 owner 和版本分页。新写入同时记录当前 owner ID 和当时的原始字段。历史/回滚通过当前表的精确复合键找到 owner，历史 JSON 的拼写只表示当时的名称。

## 回退与验证边界

MySQL 每条 ALTER 会隐式提交，多条 DDL 之间可以留下部分结构。预检失败时业务表完全未变，Flyway 在实际启动 V4 时仍可能记录 `success=0`。进入 DDL 后同样必须先确认实际列和索引及 Flyway 状态，停止该实例。从升级前备份恢复到独立实例，核对数据、索引、版本及校验和，用旧 JAR 检查 V3，再重新执行本节的检查和升级。正常路径由 Flyway 校验拦住失败的版本。本项目的恢复步骤使用完整 V3 备份，V3 和 V4 构建分别连接匹配的 schema；不同精确身份在新库可共存，切回旧比较规则可能冲突。

只读检查、备份/恢复演练、普通启动和客户端切换命令见 [运行手册](RUNBOOK.md#config-plan06-精确名称与迁移)。V3 的实际对照见 [Plan05 写入证据](WRITE_CONSISTENCY.md)。
