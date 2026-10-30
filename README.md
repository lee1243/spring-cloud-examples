# SpringCloudExamples

#### 介绍
Spring Cloud微服务示例代码

启动顺序：config -> registry -> service-a -> service-b ··· -> gateway
本地测试路径按config配置中各个服务配置端口请求（例：http://localhost:8010/hello/）

---

## 银行业务场景演示（service-a）

`service-a` 中新增了一套可零外部依赖启动的银行业务典型场景演示，覆盖**防重复提交**、**幂等**、**对账**三类能力，
并额外演示**多线程分片对账**。持久层用 H2 内存库 + `JdbcTemplate`（不引入 MyBatis/JPA，也不依赖 Redis/MQ），
启动即自动建表并灌入对账样本数据。

代码位置：`service-a/src/main/java/cn/ianlee/example/biz/bank/`

| 包 | 职责 |
| --- | --- |
| `common` | 统一返回体 `Result`、银行返回码 `ErrorEnum`、业务异常、流水号生成、H2 列名大小写兼容工具 |
| `dedup` | 防重复提交：一次性令牌 + `@AntiDuplicate` 切面（用户+URI+参数指纹的时间窗口） |
| `idempotent` | 幂等：`@Idempotent` 切面（SpEL 取幂等键）+ 幂等表主键抢占 + 结果回放 + 悬挂接管 |
| `transfer` | 转账交易：账户/流水仓储、借贷同事务记账、状态机 + 乐观锁 CAS、冲正、悬挂处理 |
| `recon` | 对账：取数 → 多线程比对 → 差错登记与调账，含对账线程池、运行锁、批次与差错仓储、日终定时任务 |
| `controller` | 三组演示接口 + `@RestControllerAdvice` 全局异常（HTTP 200 + 业务返回码） |

表结构见 `service-a/src/main/resources/schema.sql`，样本数据见 `data.sql`。
**金额一律以「分」为单位存储（long）**，避免浮点误差。

配置集中在配置中心 `config/src/main/resources/configs/service-a.yml` 的 `bank.*` 下（防重窗口、幂等超时、对账并行度等）。

### 一、防重复提交

三道防线：① 一次性令牌（带条件原子 UPDATE 消费，用即失效）② 注解 + AOP 的时间窗口拦截
③ `t_transfer_order.req_no` 唯一索引兜底。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/bank/dedup/token?userId=CUST001` | 申领一次性令牌，提交时放请求头 `X-Dedup-Token` |
| GET | `/bank/dedup/tokens` | 查看当前有效令牌 |
| POST | `/bank/dedup/submit` | 令牌模式提交（参数走表单/查询串），无令牌或令牌已用返回 `BB0003` |
| POST | `/bank/dedup/window` | 窗口模式提交，窗口期内重复返回 `BB0002`（`reqNo` 已剔除出指纹，换流水号也会被拦） |

### 二、幂等

以 `reqNo` 为幂等键，首次请求写 `PROCESSING` 占位（主键抢占），成功后缓存**业务数据**而非 `Result`，
重复请求直接回放首次结果；`PROCESSING` 超时视为悬挂交易并 CAS 接管，`FAILED` 允许同号重试。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/bank/transfer` | 仅幂等（`@Idempotent(key = "#request.reqNo")`） |
| POST | `/bank/transfer/full` | 令牌防重 + 窗口防重 + 幂等 + 唯一索引，生产交易接口推荐组合 |
| POST | `/bank/transfer/concurrent` | 同 `reqNo` 高并发提交，仅一笔记账，其余 `BB0004`/回放结果 |
| GET | `/bank/transfer/query?reqNo=` | 查询补偿：超时后凭 `reqNo` 取真实结果，避免盲目重发 |
| POST | `/bank/transfer/reverse?reqNo=&operator=` | 冲正，状态机 `SUCCESS → REVERSED` 保证不可重复冲正 |
| POST | `/bank/transfer/hang-demo` | 制造悬挂交易（已扣款但停留 `PROCESSING`） |
| GET | `/bank/transfer/hanging` | 悬挂流水清单 |
| POST | `/bank/transfer/hanging/resolve?reqNo=&toStatus=` | 悬挂处理：`SUCCESS` 补账 / `FAILED` 回补 |
| GET | `/bank/transfer/latest?limit=20` | 最近流水，观察状态机流转 |
| GET | `/bank/account/list`、`/bank/account/{acctNo}` | 账户余额与状态 |
| GET | `/bank/idempotent/records?limit=20`、`/bank/idempotent/record?bizType=&bizKey=` | 幂等记录查询 |
| DELETE | `/bank/idempotent/record?bizType=&bizKey=` | 清理幂等记录（**仅演示用**，便于反复演练同一流水号） |

余额扣减使用 `UPDATE ... WHERE balance >= ?` 的条件原子更新防透支，借贷两笔分录在同一本地事务内完成。

### 三、对账（多线程分片）

日终对账三步法：**取数 → 比对 → 差错处理**，按阶段特征差异化并行：

| 阶段 | 并行策略 |
| --- | --- |
| 取数 | 主线程一次性查询并构造**只读快照** `ReconContext`，子线程不重复查库，避免 N 倍 DB 开销 |
| 比对 | 纯读纯计算，耗时大头。按 `reqNo` **哈希分桶**分片，分片间无交集 → 无需加锁也不会重复处理；用独立线程池 `submit + Future`（而非 `parallelStream`，避免占用全局 `ForkJoinPool` 且无法定制超时与拒绝策略） |
| 调账 | 写 `t_account` 行是典型写热点，**默认串行**。开 `bank.recon.parallel-booking=true` 后用**并查集按账户聚合分组**，涉及同一账户的差错必落同一组，组间账户完全不相交 → 无行锁竞争与交叉死锁 |

并发正确性由四层兜底：

1. **交易日运行锁** `t_recon_lock`：同一交易日只允许一个批次（多实例/重复点击同样拦住，返回 `BB0001`），
   锁超陈旧阈值可被抢占（防实例崩溃后永久死锁），`finally` 中必定释放。
2. **差错唯一索引** `uk_diff_req_type(batch_no, req_no, diff_type)`：插入冲突即「已被其他线程登记」，本线程跳过调账。
3. **对账可重跑、调账不可重跑**：`existsHandledDiff` 判断前次批次是否已处理该差错，重跑只登记为 `IGNORED` 不重复调账。
4. **失败可控可视**：分片超时/异常 → 批次 `PARTIAL_FAILED` + `incompleteShards`/`incompleteBookingGroups`/`failedReqNos` 清单，
   不会让流水「既不算一致也不算差错」地静默丢失。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/bank/recon/run?tradeDate=20261029&parallel=true` | 触发对账，`parallel=false` 走串行，两者结果一致、可对比耗时 |
| POST | `/bank/recon/mock-data?count=2000&diffEvery=20` | 批量造演示流水（含四类差错），用于观察多线程效果 |
| GET | `/bank/recon/report?tradeDate=20261029` | 文本对账报告，纯查询最近批次，不会二次对账调账 |
| GET | `/bank/recon/batches?limit=10`、`/bank/recon/diffs?batchNo=` | 批次清单与差错明细 |
| GET | `/bank/recon/executor` | 对账线程池状态（活跃线程、队列积压、已完成任务） |
| GET/DELETE | `/bank/recon/lock?tradeDate=` | 查看 / 强制释放运行锁 |
| POST | `/bank/recon/channel-flow?...` | 模拟接收渠道对账文件 |
| POST | `/bank/recon/diff/{id}/handle?result=FIXED&operator=` | 人工处理差错（长款退汇、短款核销） |

差错四类与处理策略（**以渠道为准**，且全过程留痕可审计）：

| 差错类型 | 含义 | 处理 |
| --- | --- | --- |
| `AMOUNT_DIFF` | 金额不符 | 按差额方向做**双边分录**补记（单边挂账会破坏账务平衡），自动 `FIXED` |
| `STATUS_DIFF` | 本地悬挂而渠道成功 / 本地成功而渠道失败 | 自动补账或自动冲正，`FIXED` |
| `CHANNEL_ONLY` | 长款（渠道有、本地无） | 转人工，禁止直接入账（防重复付款） |
| `LOCAL_ONLY` | 短款（本地有、渠道无） | 转人工，提请渠道重传对账文件 |

定时任务在 `BankScheduler`：`bank.recon.cron`（默认 `0 0 2 * * ?`）日终对账 + 悬挂流水扫描告警。

### 启动与验证

前置：按 config(8000) → registry(8001) → service-a(8010) → service-b → gateway(8002) 顺序启动；
本机若无 Maven CLI/wrapper，用 IDEA 直接运行各模块 `Application`。
下面 PowerShell 片段只跑 service-a 也可完整验证（`/bank/dedup/submit` 无 `@RequestBody`，参数走表单/查询串）。

```powershell
# 1) 防重：申领令牌 -> 带令牌提交 -> 不带令牌再提交应报 BB0003
$t = (Invoke-RestMethod "http://localhost:8010/bank/dedup/token").data.token
$dedupBody = @{fromAcct='6222000000000001'; toAcct='6222000000000002'; amount=10000; reqNo='REQ-DEMO-001'}
Invoke-RestMethod -Method Post -Uri "http://localhost:8010/bank/dedup/submit" `
    -Headers @{"X-Dedup-Token"=$t} -Body $dedupBody

# 2) 幂等：同一 reqNo 连发两次（JSON 体），第二次回放首次结果，余额只变一次
$trf = @{fromAcct='6222000000000001'; toAcct='6222000000000002'; amount=10000; reqNo='REQ-IDEM-001'} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "http://localhost:8010/bank/transfer" -Body $trf -ContentType 'application/json'
Invoke-RestMethod -Method Post -Uri "http://localhost:8010/bank/transfer" -Body $trf -ContentType 'application/json'
Invoke-RestMethod "http://localhost:8010/bank/account/list"

# 3) 预置样本对账（20261029：5 笔待比对，1 平账 + 2 自动调账 + 2 转人工）
Invoke-RestMethod -Method Post "http://localhost:8010/bank/recon/run?tradeDate=20261029"
Invoke-RestMethod "http://localhost:8010/bank/recon/report?tradeDate=20261029"
# 重跑同一天：差错只登记为 IGNORED，不会重复调账
Invoke-RestMethod -Method Post "http://localhost:8010/bank/recon/run?tradeDate=20261029"

# 4) 多线程效果：样本只有 5 笔（shard-size=500 时分片数=1，看不出并行），需先批量造数
Invoke-RestMethod -Method Post "http://localhost:8010/bank/recon/mock-data?count=2000&diffEvery=20"
Invoke-RestMethod -Method Post "http://localhost:8010/bank/recon/run?tradeDate=20261029&parallel=false"  # 串行基线
Invoke-RestMethod -Method Post "http://localhost:8010/bank/recon/run?tradeDate=20261029&parallel=true"   # 4 分片并行
Invoke-RestMethod "http://localhost:8010/bank/recon/executor"   # 线程池状态
```

并行日志按线程名 `recon-1..recon-4` 输出每个分片的比对笔数与耗时，可直观确认是否真正跑满并行度。
H2 控制台：`http://localhost:8010/h2-console`（JDBC URL `jdbc:h2:mem:bankdemo`，用户 `sa`，密码空）。

### 演示与生产环境的差距（有意保留的说明）

- 防重时间窗口用本地 `ConcurrentHashMap`，多实例部署须替换为 Redis `SETNX` + 过期时间。
- `FlowNoGenerator` 为本地自增，分布式环境需号段/发号服务。
- 对账取数直接读 `t_channel_flow` 表，生产中这一步是下载并解析渠道对账文件。
- 线程池大小、分片阈值、超时预算全部走 `bank.recon.*` 配置，需按实际数据量与 DB 连接池容量调整。
