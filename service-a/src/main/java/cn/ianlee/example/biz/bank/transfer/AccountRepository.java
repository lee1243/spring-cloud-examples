package cn.ianlee.example.biz.bank.transfer;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import cn.ianlee.example.biz.bank.common.RowMapUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 账户数据访问。所有余额变动都是「带条件的原子 UPDATE」，
 * 绝不做「先查询再计算再写回」的两步操作，避免并发下透支
 */
@Slf4j
@Repository
public class AccountRepository {

    private final JdbcTemplate jdbcTemplate;

    public AccountRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询账户，不存在直接抛业务异常
     */
    public Map<String, Object> findAccount(String acctNo) {
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT acct_no, cust_name, balance, status, version "
                + "FROM t_account WHERE acct_no = ?", RowMapUtil.INSENSITIVE_MAPPER, acctNo);
        if (rows.isEmpty()) {
            throw new BizException(ErrorEnum.ACCOUNT_NOT_EXIST, "账户不存在: " + acctNo);
        }
        return rows.get(0);
    }

    public long balance(String acctNo) {
        return RowMapUtil.getLong(findAccount(acctNo), "balance");
    }

    /**
     * 原子扣减：靠 balance &gt;= amount 条件保证不透支，影响行数 0 即余额不足或状态异常
     *
     * @return true 扣减成功
     */
    public boolean debit(String acctNo, long amount) {
        int affected = jdbcTemplate.update("UPDATE t_account SET balance = balance - ?, version = version + 1 "
                + "WHERE acct_no = ? AND status = 'NORMAL' AND balance >= ?", amount, acctNo, amount);
        if (affected == 0) {
            log.info("[记账] 扣减失败, acctNo={}, amount={}分", acctNo, amount);
        }
        return affected == 1;
    }

    /**
     * 入账
     */
    public void credit(String acctNo, long amount) {
        int affected = jdbcTemplate.update("UPDATE t_account SET balance = balance + ?, version = version + 1 "
                + "WHERE acct_no = ? AND status = 'NORMAL'", amount, acctNo);
        if (affected == 0) {
            throw new BizException(ErrorEnum.ACCOUNT_STATUS_ABNORMAL, "收款账户状态异常: " + acctNo);
        }
    }

    /**
     * 账户状态调整（演示差错处理时用，如挂账账户置为 FROZEN）
     */
    public void updateStatus(String acctNo, String status) {
        jdbcTemplate.update("UPDATE t_account SET status = ?, version = version + 1 WHERE acct_no = ?", status, acctNo);
    }

    public List<Map<String, Object>> listAccounts() {
        return jdbcTemplate.query("SELECT acct_no, cust_name, balance, status, version FROM t_account ORDER BY acct_no",
                RowMapUtil.INSENSITIVE_MAPPER);
    }
}
