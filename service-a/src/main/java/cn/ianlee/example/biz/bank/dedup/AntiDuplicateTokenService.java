package cn.ianlee.example.biz.bank.dedup;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author Ian.Lee
 * @Description 防重令牌服务：进入交易页面时申领令牌，提交时一次性消费。
 * 消费采用「带条件的原子 UPDATE」，影响行数为 0 即代表令牌已被使用/过期
 */
@Slf4j
@Service
public class AntiDuplicateTokenService {

    private final JdbcTemplate jdbcTemplate;

    @Value("${bank.dedup.token-expire-seconds:60}")
    private int expireSeconds;

    public AntiDuplicateTokenService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 申领令牌（一个用户同一时刻只保留有效令牌，重复申领作废旧令牌）
     *
     * @param userId 客户号/柜员号
     * @return 一次性令牌
     */
    public String apply(String userId) {
        Timestamp now = now();
        Timestamp expire = new Timestamp(System.currentTimeMillis() + expireSeconds * 1000L);
        // 作废旧令牌并清理过期数据
        jdbcTemplate.update("UPDATE t_anti_duplicate_token SET used = 1 WHERE user_id = ? AND used = 0", userId);
        jdbcTemplate.update("DELETE FROM t_anti_duplicate_token WHERE expire_time < ?", now);

        String token = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO t_anti_duplicate_token (token, user_id, used, expire_time, create_time) "
                + "VALUES (?, ?, 0, ?, ?)", token, userId, expire, now);
        log.info("[防重] 令牌申领成功, userId={}, 有效期{}秒", userId, expireSeconds);
        return token;
    }

    /**
     * 消费令牌：只有把 used 从 0 改成 1 的那一次请求能成功
     */
    public void consume(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new BizException(ErrorEnum.TOKEN_INVALID, "缺少防重令牌，请先调用 GET /bank/dedup/token 申领");
        }
        int affected = jdbcTemplate.update("UPDATE t_anti_duplicate_token SET used = 1 "
                        + "WHERE token = ? AND used = 0 AND expire_time > ?", token, now());
        if (affected == 0) {
            log.warn("[防重] 令牌校验失败(已使用/已过期/不存在), token={}", token);
            throw new BizException(ErrorEnum.TOKEN_INVALID);
        }
    }

    /**
     * 当前有效令牌列表，便于演示观察
     */
    public List<Map<String, Object>> listValid() {
        return jdbcTemplate.queryForList("SELECT token, user_id, used, expire_time FROM t_anti_duplicate_token "
                + "WHERE used = 0 AND expire_time > ? ORDER BY create_time DESC", now());
    }

    private Timestamp now() {
        return new Timestamp(System.currentTimeMillis());
    }
}
