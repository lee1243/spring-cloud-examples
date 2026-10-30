package cn.ianlee.example.biz.bank.dedup;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author Ian.Lee
 * @Description 防重切面：同一用户在时间窗口内提交完全相同的业务请求，只放行第一笔。
 * 指纹 = 业务类型 + 用户 + URI + 规范化参数摘要（剔除流水号、令牌等每次必变的字段）
 * Order(0) 保证在幂等切面之前执行，重复请求不进入幂等表
 */
@Slf4j
@Aspect
@Component
@Order(0)
public class AntiDuplicateAspect {

    /** 演示用本地窗口缓存，集群环境应替换为 Redis SETNX + 过期时间 */
    private final Map<String, Long> windowCache = new ConcurrentHashMap<>();

    private final AntiDuplicateTokenService tokenService;

    @Value("${bank.dedup.window-seconds:5}")
    private int defaultWindowSeconds;

    public AntiDuplicateAspect(AntiDuplicateTokenService tokenService) {
        this.tokenService = tokenService;
    }

    @Around("@annotation(antiDuplicate)")
    public Object around(ProceedingJoinPoint pjp, AntiDuplicate antiDuplicate) throws Throwable {
        HttpServletRequest request = currentRequest();
        String userId = request == null ? "anonymous" : headerOrParam(request, "X-User-Id", "userId");
        String uri = request == null ? pjp.getSignature().toShortString() : request.getRequestURI();
        int windowSeconds = antiDuplicate.windowSeconds() > 0
                ? antiDuplicate.windowSeconds() : defaultWindowSeconds;

        // 第一道：令牌一次性消费（前后端配合的页面防重模式）
        if (antiDuplicate.requireToken() && request != null) {
            tokenService.consume(request.getHeader(antiDuplicate.tokenHeader()));
        }

        // 第二道：短时间窗口内相同请求指纹直接拦截
        String fingerKey = buildFingerprint(antiDuplicate.bizType(), userId, uri, pjp.getArgs());
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        windowCache.entrySet().removeIf(entry -> now - entry.getValue() > windowMillis);

        Long first = windowCache.putIfAbsent(fingerKey, now);
        if (first != null && now - first < windowMillis) {
            log.warn("[防重] 命中重复提交拦截, userId={}, uri={}, 窗口剩余{}ms", userId, uri, windowMillis - (now - first));
            throw new BizException(ErrorEnum.DUPLICATE_SUBMIT,
                    String.format("%s：%d 秒内已提交过完全相同的请求", ErrorEnum.DUPLICATE_SUBMIT.getMessage(), windowSeconds));
        }

        try {
            return pjp.proceed();
        } catch (Throwable e) {
            // 业务失败释放窗口，允许用户修正后立即重试
            windowCache.remove(fingerKey);
            throw e;
        }
    }

    private String buildFingerprint(String bizType, String userId, String uri, Object[] args) {
        StringBuilder sb = new StringBuilder(bizType).append('|').append(userId).append('|').append(uri).append('|');
        Arrays.stream(args == null ? new Object[0] : args).map(this::normalize).forEach(sb::append);
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 参与指纹的字段需保持稳定：请求流水号、防重令牌每次都不同，必须剔除，
     * 否则「同一笔业务的重试」会被误判为「不同业务」
     */
    private String normalize(Object arg) {
        if (arg == null) {
            return "";
        }
        return arg.toString()
                .replaceAll("reqNo=[^,)]*", "")
                .replaceAll("dedupToken=[^,)]*", "");
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        return attributes instanceof ServletRequestAttributes
                ? ((ServletRequestAttributes) attributes).getRequest() : null;
    }

    private String headerOrParam(HttpServletRequest request, String header, String param) {
        String value = request.getHeader(header);
        return value == null || value.isEmpty() ? request.getParameter(param) : value;
    }
}
