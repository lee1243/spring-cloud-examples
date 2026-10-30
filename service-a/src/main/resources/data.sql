-- 初始化账户数据（金额单位：分）
INSERT INTO t_account (acct_no, cust_name, balance, status, version) VALUES
    ('6222000000000001', '张三', 1000000, 'NORMAL', 0),
    ('6222000000000002', '李四', 500000, 'NORMAL', 0),
    ('6222000000000003', '王五', 200000, 'NORMAL', 0);

-- 预置 20261029 本地历史流水（作为对账的本地账）
-- REQ-OK-001 与渠道完全一致，用于验证「平账」
-- AMT-DIFF-001 本地记 66000，渠道记 66600 → 金额不符（自动以渠道为准补账 600 分）
-- REQ-PROC-001 本地停留 PROCESSING（悬挂），渠道成功 → 状态不符（自动补账推进终态）
-- REQ-LOCAL-ONLY-001 本地有、渠道无 → 短款（转人工）
INSERT INTO t_transfer_order (order_no, req_no, from_acct, to_acct, amount, status, version, create_time) VALUES
    ('TRF20261029000001', 'REQ-OK-001', '6222000000000001', '6222000000000002', 12000, 'SUCCESS', 1, '2026-10-29 09:30:00'),
    ('TRF20261029000002', 'AMT-DIFF-001', '6222000000000001', '6222000000000003', 66000, 'SUCCESS', 1, '2026-10-29 10:15:00'),
    ('TRF20261029000003', 'REQ-PROC-001', '6222000000000002', '6222000000000003', 5000, 'PROCESSING', 0, '2026-10-29 12:00:00'),
    ('TRF20261029000004', 'REQ-LOCAL-ONLY-001', '6222000000000003', '6222000000000001', 30000, 'SUCCESS', 1, '2026-10-29 15:40:00');

-- 模拟渠道（对手机构）对账流水：
-- SER...001 渠道有、本地无 → 长款（转人工挂账）
-- SER...002 与本地 AMT-DIFF-001 金额不一致
-- SER...003 与本地悬挂流水 REQ-PROC-001 对应，渠道为成功
-- SER...004 与本地 REQ-OK-001 完全一致
INSERT INTO t_channel_flow (channel_serial, req_no, amount, trade_date, status) VALUES
    ('SER000000000000000001', 'REQ-NOT-EXIST-001', 88800, '20261029', 'SUCCESS'),
    ('SER000000000000000002', 'AMT-DIFF-001', 66600, '20261029', 'SUCCESS'),
    ('SER000000000000000003', 'REQ-PROC-001', 5000, '20261029', 'SUCCESS'),
    ('SER000000000000000004', 'REQ-OK-001', 12000, '20261029', 'SUCCESS');
