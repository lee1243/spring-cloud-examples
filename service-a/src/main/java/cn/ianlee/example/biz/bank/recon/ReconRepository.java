package cn.ianlee.example.biz.bank.recon;

import cn.ianlee.example.biz.bank.common.RowMapUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 对账数据访问：渠道流水（对账文件）、对账批次、差错明细
 */
@Slf4j
@Repository
public class ReconRepository {

    private final JdbcTemplate jdbcTemplate;

    public ReconRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 读取指定交易日的渠道流水（生产中此处为下载并解析对账文件）
     */
    public List<Map<String, Object>> listChannelFlows(String tradeDate) {
        return jdbcTemplate.query("SELECT channel_serial, req_no, amount, trade_date, status FROM t_channel_flow "
                + "WHERE trade_date = ? ORDER BY req_no", RowMapUtil.INSENSITIVE_MAPPER, tradeDate);
    }

    /**
     * 登记渠道流水，模拟收到对账文件。重复登记由主键冲突拦截
     */
    public boolean saveChannelFlow(String channelSerial, String reqNo, long amount, String tradeDate, String status) {
        try {
            jdbcTemplate.update("INSERT INTO t_channel_flow (channel_serial, req_no, amount, trade_date, status) "
                    + "VALUES (?, ?, ?, ?, ?)", channelSerial, reqNo, amount, tradeDate, status);
            return true;
        } catch (Exception e) {
            log.warn("[对账] 渠道流水重复登记, channelSerial={}", channelSerial);
            return false;
        }
    }

    public void saveBatch(ReconSummary summary) {
        jdbcTemplate.update("INSERT INTO t_recon_batch (batch_no, trade_date, total_local, total_channel, success_count, "
                        + "diff_count, local_amount, channel_amount, parallelism, shard_count, cost_millis, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                summary.getBatchNo(), summary.getTradeDate(), summary.getTotalLocal(), summary.getTotalChannel(),
                summary.getMatchedCount(), summary.getDiffCount(), summary.getLocalAmountTotal(),
                summary.getChannelAmountTotal(), summary.getParallelism(), summary.getShardCount(),
                summary.getCostMillis(), summary.getStatus(), new Timestamp(System.currentTimeMillis()));
    }

    public void updateBatchStatus(String batchNo, String status) {
        jdbcTemplate.update("UPDATE t_recon_batch SET status = ? WHERE batch_no = ?", status, batchNo);
    }

    /**
     * 批次结果回写：并行对账时一致/差错笔数在分片完成后才可知，结束时一并更新统计与状态
     */
    public void updateBatchResult(ReconSummary summary) {
        jdbcTemplate.update("UPDATE t_recon_batch SET total_local = ?, total_channel = ?, success_count = ?, "
                        + "diff_count = ?, local_amount = ?, channel_amount = ?, shard_count = ?, cost_millis = ?, status = ? "
                        + "WHERE batch_no = ?",
                summary.getTotalLocal(), summary.getTotalChannel(), summary.getMatchedCount(), summary.getDiffCount(),
                summary.getLocalAmountTotal(), summary.getChannelAmountTotal(), summary.getShardCount(),
                summary.getCostMillis(), summary.getStatus(), summary.getBatchNo());
    }

    /**
     * 登记差错明细并回填自增主键。
     * 多线程下即使不同分片算出同一差错，也会被 (batch_no, req_no, diff_type) 唯一索引拦住
     *
     * @return true 登记成功；false 已被其他线程登记，应跳过后续调账
     */
    public boolean saveDiff(ReconDiff diff) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbcTemplate.update(connection -> {
                PreparedStatement ps = connection.prepareStatement(
                        "INSERT INTO t_recon_diff (batch_no, req_no, diff_type, local_amount, channel_amount, local_status, "
                                + "channel_status, handle_status, handle_remark, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, diff.getBatchNo());
                ps.setString(2, diff.getReqNo());
                ps.setString(3, diff.getDiffType());
                ps.setLong(4, diff.getLocalAmount() == null ? 0L : diff.getLocalAmount());
                ps.setLong(5, diff.getChannelAmount() == null ? 0L : diff.getChannelAmount());
                ps.setString(6, diff.getLocalStatus());
                ps.setString(7, diff.getChannelStatus());
                ps.setString(8, diff.getHandleStatus());
                ps.setString(9, diff.getHandleRemark());
                ps.setTimestamp(10, new Timestamp(System.currentTimeMillis()));
                return ps;
            }, keyHolder);
        } catch (DuplicateKeyException e) {
            log.warn("[对账] 差错重复登记已忽略, batchNo={}, reqNo={}, type={}",
                    diff.getBatchNo(), diff.getReqNo(), diff.getDiffType());
            return false;
        }
        if (keyHolder.getKey() != null) {
            diff.setId(keyHolder.getKey().longValue());
        }
        return true;
    }

    public List<ReconDiff> listDiffs(String batchNo) {
        return jdbcTemplate.query("SELECT * FROM t_recon_diff WHERE batch_no = ? ORDER BY id", this::mapDiff, batchNo);
    }

    /**
     * 判断该差错在本交易日的前次批次中是否已处理完成（FIXED/IGNORED）。
     * 对账任务可重跑，但调账绝不能因重跑而重复执行
     *
     * @param excludeBatchNo 当前批次号，排除自身
     */
    public boolean existsHandledDiff(String tradeDate, String excludeBatchNo, String reqNo, String diffType) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(1) FROM t_recon_diff d "
                + "INNER JOIN t_recon_batch b ON d.batch_no = b.batch_no "
                + "WHERE b.trade_date = ? AND d.req_no = ? AND d.diff_type = ? "
                + "AND d.handle_status IN ('FIXED', 'IGNORED') AND d.batch_no <> ?",
                Integer.class, tradeDate, reqNo, diffType, excludeBatchNo);
        return count != null && count > 0;
    }

    /**
     * 指定交易日的最近一个对账批次
     */
    public Map<String, Object> latestBatch(String tradeDate) {
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT batch_no, trade_date, total_local, total_channel, "
                + "success_count, diff_count, local_amount, channel_amount, parallelism, shard_count, cost_millis, status, "
                + "create_time FROM t_recon_batch WHERE trade_date = ? ORDER BY create_time DESC LIMIT 1",
                RowMapUtil.INSENSITIVE_MAPPER, tradeDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<Map<String, Object>> listBatches(int limit) {
        return jdbcTemplate.query("SELECT batch_no, trade_date, total_local, total_channel, success_count, diff_count, "
                        + "local_amount, channel_amount, parallelism, shard_count, cost_millis, status, create_time "
                        + "FROM t_recon_batch ORDER BY create_time DESC LIMIT ?",
                RowMapUtil.INSENSITIVE_MAPPER, limit);
    }

    public ReconDiff findDiff(Long id) {
        List<ReconDiff> list = jdbcTemplate.query("SELECT * FROM t_recon_diff WHERE id = ?", this::mapDiff, id);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 差错处理结果回写：仅待处理状态可回写，避免重复调账
     */
    public boolean markDiffHandled(Long id, String handleStatus, String remark) {
        int affected = jdbcTemplate.update("UPDATE t_recon_diff SET handle_status = ?, handle_remark = ? "
                + "WHERE id = ? AND handle_status = ?", handleStatus, remark, id, ReconDiff.PENDING);
        return affected == 1;
    }

    /**
     * 对账运行锁：同一交易日只允许一个批次在跑。
     * 锁已存在且未超过陈旧阈值时获锁失败；超时（如上次实例崩溃）则允许抢占，避免死锁
     *
     * @return true 获锁成功
     */
    public boolean tryLock(String tradeDate, String batchNo, int staleMinutes) {
        Timestamp now = new Timestamp(System.currentTimeMillis());
        try {
            jdbcTemplate.update("INSERT INTO t_recon_lock (trade_date, batch_no, lock_time) VALUES (?, ?, ?)",
                    tradeDate, batchNo, now);
            return true;
        } catch (DuplicateKeyException e) {
            Timestamp deadline = new Timestamp(System.currentTimeMillis() - staleMinutes * 60 * 1000L);
            int affected = jdbcTemplate.update("UPDATE t_recon_lock SET batch_no = ?, lock_time = ? "
                    + "WHERE trade_date = ? AND lock_time < ?", batchNo, now, tradeDate, deadline);
            if (affected == 1) {
                log.warn("[对账] 发现陈旧锁已超时({}分钟), 强行抢占, tradeDate={}", staleMinutes, tradeDate);
                return true;
            }
            return false;
        }
    }

    /**
     * 释放运行锁：只能释放自己持有的锁，防止误删其他批次锁
     */
    public void unlock(String tradeDate, String batchNo) {
        jdbcTemplate.update("DELETE FROM t_recon_lock WHERE trade_date = ? AND batch_no = ?", tradeDate, batchNo);
    }

    /**
     * 查看当前持锁情况（运维用）
     */
    public Map<String, Object> findLock(String tradeDate) {
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT trade_date, batch_no, lock_time FROM t_recon_lock "
                + "WHERE trade_date = ?", RowMapUtil.INSENSITIVE_MAPPER, tradeDate);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 强制解锁（仅演示/运维故障处理用）
     */
    public int forceReleaseLock(String tradeDate) {
        return jdbcTemplate.update("DELETE FROM t_recon_lock WHERE trade_date = ?", tradeDate);
    }

    /**
     * 批量写入本地流水（造演示数据用，让多线程分片对账有可观测的耗时差异）
     */
    public int batchInsertLocalOrders(final List<TransferOrderSeed> seeds) {
        int[] affected = jdbcTemplate.batchUpdate("INSERT INTO t_transfer_order (order_no, req_no, from_acct, to_acct, "
                        + "amount, status, version, create_time) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        TransferOrderSeed seed = seeds.get(i);
                        ps.setString(1, seed.orderNo);
                        ps.setString(2, seed.reqNo);
                        ps.setString(3, seed.fromAcct);
                        ps.setString(4, seed.toAcct);
                        ps.setLong(5, seed.amount);
                        ps.setString(6, seed.status);
                        ps.setTimestamp(7, seed.createTime);
                    }

                    @Override
                    public int getBatchSize() {
                        return seeds.size();
                    }
                });
        return affected.length;
    }

    /**
     * 批量写入渠道流水
     */
    public int batchInsertChannelFlows(final List<ChannelFlowSeed> seeds) {
        int[] affected = jdbcTemplate.batchUpdate("INSERT INTO t_channel_flow (channel_serial, req_no, amount, "
                        + "trade_date, status) VALUES (?, ?, ?, ?, ?)",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ChannelFlowSeed seed = seeds.get(i);
                        ps.setString(1, seed.channelSerial);
                        ps.setString(2, seed.reqNo);
                        ps.setLong(3, seed.amount);
                        ps.setString(4, seed.tradeDate);
                        ps.setString(5, seed.status);
                    }

                    @Override
                    public int getBatchSize() {
                        return seeds.size();
                    }
                });
        return affected.length;
    }

    /**
     * 本地流水批量插入用的轻量载体（避免为造数而扩展业务实体）
     */
    public static class TransferOrderSeed {
        public String orderNo;
        public String reqNo;
        public String fromAcct;
        public String toAcct;
        public long amount;
        public String status;
        public Timestamp createTime;
    }

    /**
     * 渠道流水批量插入载体
     */
    public static class ChannelFlowSeed {
        public String channelSerial;
        public String reqNo;
        public long amount;
        public String tradeDate;
        public String status;
    }

    private ReconDiff mapDiff(ResultSet rs, int rowNum) throws SQLException {
        ReconDiff diff = new ReconDiff();
        diff.setId(rs.getLong("id"));
        diff.setBatchNo(rs.getString("batch_no"));
        diff.setReqNo(rs.getString("req_no"));
        diff.setDiffType(rs.getString("diff_type"));
        diff.setLocalAmount(rs.getLong("local_amount"));
        diff.setChannelAmount(rs.getLong("channel_amount"));
        diff.setLocalStatus(rs.getString("local_status"));
        diff.setChannelStatus(rs.getString("channel_status"));
        diff.setHandleStatus(rs.getString("handle_status"));
        diff.setHandleRemark(rs.getString("handle_remark"));
        return diff;
    }
}
