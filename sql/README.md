# SQL 初始化与迁移

## 首次初始化

1. 创建 MySQL 库：`agent_platform`。
2. 执行 `init.sql`。
3. 按文件日期从小到大执行所有 `update-*.sql`。
4. 启动 Redis 与 Milvus。
5. 启动 `nuwax-backend`，确认 `/ready` 返回 `success`。

## 当前迁移顺序

1. `init.sql`
2. `update-20260320.sql`
3. `update-20260331.sql`
4. `update-20260418.sql`
5. `update-20260510.sql`
6. `update-20260524.sql`

## 约定

- 增量脚本必须按日期顺序执行。
- 新增迁移脚本命名为 `update-YYYYMMDD.sql`。
- 脚本应尽量幂等，避免重复执行时失败。
- 破坏性变更必须先备份数据，并提供回滚说明。
