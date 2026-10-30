package cn.ianlee.example.biz.bank.transfer;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

/**
 * @author Ian.Lee
 * @Description 交易流水数据访问：req_no 唯一索引是防重的最后一道防线
 */
@Slf4j
@Repository
public class TransferOrderRepository {

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<TransferOrder> rowMapper = this::mapOrder;

    public TransferOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 登记流水。唯一索引冲突代表该流水号已受理过
     */
    public boolean insert(TransferOrder order) {
        try {
            jdbcTemplate.update("INSERT INTO t_transfer_order (order_no, req_no, from_acct, to_acct, amount, status, "
                            + "version, create_time) VALUES (?, ?, ?, ?, ?, ?, 0, ?)",
                    order.getOrderNo(), order.getReqNo(), order.getFromAcct(), order.getToAcct(),
                    order.getAmount(), order.getStatus(), new Timestamp(System.currentTimeMillis()));
            return true;
        } catch (DuplicateKeyException e) {
            log.warn("[防重] req_no 唯一索引拦截重复请求, reqNo={}", order.getReqNo());
            return false;
        }
    }

    public TransferOrder findByReqNo(String reqNo) {
        return queryOne("SELECT * FROM t_transfer_order WHERE req_no = ?", reqNo);
    }

    public TransferOrder findByOrderNo(String orderNo) {
        return queryOne("SELECT * FROM t_transfer_order WHERE order_no = ?", orderNo);
    }

    /**
     * 状态机 + 乐观锁 CAS：只允许从 fromStatus 推进到 toStatus，
     * 并发/重复请求下只有一个是成功的那次
     */
    public boolean casUpdateStatus(String orderNo, String fromStatus, String toStatus, String failReason) {
        int affected = jdbcTemplate.update("UPDATE t_transfer_order SET status = ?, fail_reason = ?, "
                        + "version = version + 1, update_time = ? WHERE order_no = ? AND status = ?",
                toStatus, failReason, new Timestamp(System.currentTimeMillis()), orderNo, fromStatus);
        return affected == 1;
    }

    /**
     * 捞取悬挂流水：长时间停留在 PROCESSING 的交易
     */
    public List<TransferOrder> listHanging(int timeoutSeconds) {
        return jdbcTemplate.query("SELECT * FROM t_transfer_order WHERE status = 'PROCESSING' AND create_time < ? "
                + "ORDER BY create_time", rowMapper, new Timestamp(System.currentTimeMillis() - timeoutSeconds * 1000L));
    }

    /**
     * 按时间区间取本地流水（对账用，左闭右开）
     */
    public List<TransferOrder> listByTimeRange(Timestamp start, Timestamp end) {
        return jdbcTemplate.query("SELECT * FROM t_transfer_order WHERE create_time >= ? AND create_time < ? "
                + "ORDER BY req_no", rowMapper, start, end);
    }

    public List<TransferOrder> listLatest(int limit) {
        return jdbcTemplate.query("SELECT * FROM t_transfer_order ORDER BY create_time DESC LIMIT ?", rowMapper, limit);
    }

    public TransferOrder requireByReqNo(String reqNo) {
        TransferOrder order = findByReqNo(reqNo);
        if (order == null) {
            throw new BizException(ErrorEnum.ORDER_NOT_EXIST, "未查询到该请求流水号的交易: " + reqNo);
        }
        return order;
    }

    private TransferOrder queryOne(String sql, String key) {
        List<TransferOrder> list = jdbcTemplate.query(sql, rowMapper, key);
        return list.isEmpty() ? null : list.get(0);
    }

    private TransferOrder mapOrder(ResultSet rs, int rowNum) throws SQLException {
        TransferOrder order = new TransferOrder();
        order.setOrderNo(rs.getString("order_no"));
        order.setReqNo(rs.getString("req_no"));
        order.setFromAcct(rs.getString("from_acct"));
        order.setToAcct(rs.getString("to_acct"));
        order.setAmount(rs.getLong("amount"));
        order.setStatus(rs.getString("status"));
        order.setFailReason(rs.getString("fail_reason"));
        order.setVersion(rs.getInt("version"));
        order.setCreateTime(rs.getTimestamp("create_time"));
        order.setUpdateTime(rs.getTimestamp("update_time"));
        return order;
    }
}
