package cn.ianlee.example.biz.bank.idempotent;

import cn.ianlee.example.biz.bank.common.RowMapUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 幂等记录服务：biz_key 为主键，插入冲突即代表该流水已被处理过。
 * 生产环境可放 Redis + DB 双写，DB 保留用于审计与结果回放
 */
@Slf4j
@Service
public class IdempotentRecordService {

    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private final JdbcTemplate jdbcTemplate;

    public IdempotentRecordService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 抢占幂等键
     *
     * @return true 表示本次是首次执行（拿到了执行权）
     */
    public boolean tryAcquire(String bizKey, String bizType) {
        Timestamp now = now();
        try {
            jdbcTemplate.update("INSERT INTO t_idempotent_record (biz_key, biz_type, state, create_time, update_time) "
                    + "VALUES (?, ?, ?, ?, ?)", bizKey, bizType, PROCESSING, now, now);
            return true;
        } catch (DuplicateKeyException e) {
            log.info("[幂等] 幂等键已存在, bizKey={}, 走历史结果判定", bizKey);
            return false;
        }
    }

    public Map<String, Object> find(String bizKey) {
        List<Map<String, Object>> rows = jdbcTemplate.query("SELECT biz_key, biz_type, state, result_json, update_time "
                + "FROM t_idempotent_record WHERE biz_key = ?", rowMapper(), bizKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 回写成功结果，仅处理中状态可回写
     */
    public void markSuccess(String bizKey, String resultJson) {
        jdbcTemplate.update("UPDATE t_idempotent_record SET state = ?, result_json = ?, update_time = ? "
                        + "WHERE biz_key = ? AND state = ?", SUCCESS, resultJson, now(), bizKey, PROCESSING);
    }

    /**
     * 标记失败：保留记录便于排查，同一流水号重试时由切面接管重新执行
     */
    public void markFailed(String bizKey, String resultJson) {
        jdbcTemplate.update("UPDATE t_idempotent_record SET state = ?, result_json = ?, update_time = ? "
                        + "WHERE biz_key = ? AND state = ?", FAILED, resultJson, now(), bizKey, PROCESSING);
    }

    /**
     * 判定 PROCESSING 是否超时（悬挂交易）
     */
    public boolean isProcessingTimeout(Map<String, Object> record, int timeoutSeconds) {
        Timestamp updateTime = RowMapUtil.getTimestamp(record, "update_time");
        if (updateTime == null) {
            return false;
        }
        return System.currentTimeMillis() - updateTime.getTime() > timeoutSeconds * 1000L;
    }

    /**
     * 接管重执行：状态重新置为处理中（CAS，避免多个重试线程同时接管）
     *
     * @return true 表示接管成功
     */
    public boolean takeover(String bizKey, String expectState) {
        int affected = jdbcTemplate.update("UPDATE t_idempotent_record SET state = ?, update_time = ? "
                + "WHERE biz_key = ? AND state = ?", PROCESSING, now(), bizKey, expectState);
        return affected == 1;
    }

    public List<Map<String, Object>> list(int limit) {
        return jdbcTemplate.query("SELECT biz_key, biz_type, state, result_json, update_time FROM t_idempotent_record "
                + "ORDER BY update_time DESC LIMIT ?", rowMapper(), limit);
    }

    public void delete(String bizKey) {
        jdbcTemplate.update("DELETE FROM t_idempotent_record WHERE biz_key = ?", bizKey);
    }

    private RowMapper<Map<String, Object>> rowMapper() {
        return RowMapUtil.INSENSITIVE_MAPPER;
    }

    private Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }
}
