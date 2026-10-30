-- 银行业务演示表结构（H2 内存库，Boot 启动时自动执行）
-- 金额统一以「分」为单位存储，避免浮点误差

-- 账户表：version 为乐观锁版本号
CREATE TABLE IF NOT EXISTS t_account (
    acct_no    VARCHAR(32) PRIMARY KEY,
    cust_name  VARCHAR(64),
    balance    BIGINT      NOT NULL,
    status     VARCHAR(16) NOT NULL,
    version    INT         NOT NULL
);

-- 交易流水表：req_no 为渠道请求流水号，唯一索引作为最后一道防重
CREATE TABLE IF NOT EXISTS t_transfer_order (
    order_no     VARCHAR(32) PRIMARY KEY,
    req_no       VARCHAR(64) NOT NULL,
    from_acct    VARCHAR(32) NOT NULL,
    to_acct      VARCHAR(32) NOT NULL,
    amount       BIGINT      NOT NULL,
    status       VARCHAR(16) NOT NULL,
    fail_reason  VARCHAR(128),
    version      INT         NOT NULL,
    create_time  TIMESTAMP   NOT NULL,
    update_time  TIMESTAMP,
    CONSTRAINT uk_req_no UNIQUE (req_no)
);

CREATE INDEX IF NOT EXISTS idx_order_status ON t_transfer_order (status);

-- 幂等记录表：biz_key 即幂等键，主键冲突说明该流水已处理过
CREATE TABLE IF NOT EXISTS t_idempotent_record (
    biz_key     VARCHAR(128) PRIMARY KEY,
    biz_type    VARCHAR(32)  NOT NULL,
    state       VARCHAR(16)  NOT NULL,
    result_json VARCHAR(4000),
    create_time TIMESTAMP    NOT NULL,
    update_time TIMESTAMP
);

-- 防重令牌表：提交时一次性消费（DELETE 影响行数判定）
CREATE TABLE IF NOT EXISTS t_anti_duplicate_token (
    token       VARCHAR(64) PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    used        TINYINT     NOT NULL,
    expire_time TIMESTAMP   NOT NULL,
    create_time TIMESTAMP   NOT NULL
);

-- 渠道方（对手机构）流水，模拟对方提供的对账文件
CREATE TABLE IF NOT EXISTS t_channel_flow (
    channel_serial VARCHAR(64) PRIMARY KEY,
    req_no         VARCHAR(64) NOT NULL,
    amount         BIGINT      NOT NULL,
    trade_date     VARCHAR(8)  NOT NULL,
    status         VARCHAR(16) NOT NULL
);

-- 对账批次
CREATE TABLE IF NOT EXISTS t_recon_batch (
    batch_no      VARCHAR(32) PRIMARY KEY,
    trade_date    VARCHAR(8)  NOT NULL,
    total_local   INT,
    total_channel INT,
    success_count INT,
    diff_count    INT,
    local_amount  BIGINT,
    channel_amount BIGINT,
    parallelism   INT,
    shard_count   INT,
    cost_millis   BIGINT,
    status        VARCHAR(16),
    create_time   TIMESTAMP
);

-- 对账差错明细
CREATE TABLE IF NOT EXISTS t_recon_diff (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    batch_no       VARCHAR(32) NOT NULL,
    req_no         VARCHAR(64),
    diff_type      VARCHAR(24) NOT NULL,
    local_amount   BIGINT,
    channel_amount BIGINT,
    local_status   VARCHAR(16),
    channel_status VARCHAR(16),
    handle_status  VARCHAR(16) NOT NULL,
    handle_remark  VARCHAR(200),
    create_time    TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_diff_batch ON t_recon_diff (batch_no);

-- 差错登记唯一约束（batch_no + req_no + diff_type）：多线程分片对账下即使分片重叠也不会重复登记同一差错
CREATE UNIQUE INDEX IF NOT EXISTS uk_diff_req_type ON t_recon_diff (batch_no, req_no, diff_type);

CREATE INDEX IF NOT EXISTS idx_batch_date ON t_recon_batch (trade_date);

-- 对账运行锁：同一交易日串行化，避免两个批次并发对同一笔差错重复调账
CREATE TABLE IF NOT EXISTS t_recon_lock (
    trade_date VARCHAR(8) PRIMARY KEY,
    batch_no   VARCHAR(32) NOT NULL,
    lock_time  TIMESTAMP   NOT NULL
);
