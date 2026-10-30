package cn.ianlee.example.biz.bank.idempotent;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import cn.ianlee.example.biz.bank.common.Result;
import cn.ianlee.example.biz.bank.common.RowMapUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 幂等切面：
 * 1) 首次请求：抢占幂等键 → 执行业务 → 回写结果
 * 2) 重复请求：SUCCESS 回放首次结果；PROCESSING 未超时提示「处理中」；PROCESSING 已超时视为悬挂，接管重试；
 * FAILED 允许用同一流水号重试
 */
@Slf4j
@Aspect
@Component
@Order(1)
public class IdempotentAspect {

    private final ExpressionParser parser = new SpelExpressionParser();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final IdempotentRecordService recordService;

    @Value("${bank.idempotent.processing-timeout-seconds:30}")
    private int defaultProcessingTimeoutSeconds;

    public IdempotentAspect(IdempotentRecordService recordService) {
        this.recordService = recordService;
    }

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint pjp, Idempotent idempotent) throws Throwable {
        MethodSignature signature = (MethodSignature) pjp.getSignature();
        String bizType = idempotent.bizType();
        String bizKey = bizType + ":" + resolveKey(pjp, idempotent.key());
        int timeoutSeconds = idempotent.processingTimeoutSeconds() > 0
                ? idempotent.processingTimeoutSeconds() : defaultProcessingTimeoutSeconds;

        // 首次请求：拿到执行权
        if (recordService.tryAcquire(bizKey, bizType)) {
            return invokeAndRecord(pjp, signature, bizKey);
        }

        Map<String, Object> record = recordService.find(bizKey);
        if (record == null) {
            // 极端情况：记录刚被清理，按首次执行处理
            return invokeAndRecord(pjp, signature, bizKey);
        }
        String state = RowMapUtil.getString(record, "state");

        if (IdempotentRecordService.SUCCESS.equals(state)) {
            Object payload = deserialize(RowMapUtil.getString(record, "result_json"), signature);
            log.info("[幂等] 回放首次执行结果, bizKey={}", bizKey);
            return replay(signature, payload, "幂等回放：该流水号已处理完成，返回首次结果");
        }

        if (IdempotentRecordService.PROCESSING.equals(state)) {
            if (recordService.isProcessingTimeout(record, timeoutSeconds) && recordService.takeover(bizKey, state)) {
                log.warn("[幂等] 检测到悬挂交易(超过{}秒未终态), 接管重试, bizKey={}", timeoutSeconds, bizKey);
                return invokeAndRecord(pjp, signature, bizKey);
            }
            throw new BizException(ErrorEnum.IDEMPOTENT_PROCESSING);
        }

        // FAILED：允许同流水号重试，重试同样纳入幂等管控
        if (recordService.takeover(bizKey, state)) {
            log.info("[幂等] 上次执行失败, 同流水号重试, bizKey={}", bizKey);
            return invokeAndRecord(pjp, signature, bizKey);
        }
        throw new BizException(ErrorEnum.IDEMPOTENT_PROCESSING);
    }

    /**
     * 执行并登记结果。业务异常回写失败状态后继续抛出，由全局异常处理器转统一返回体
     */
    private Object invokeAndRecord(ProceedingJoinPoint pjp, MethodSignature signature, String bizKey) throws Throwable {
        try {
            Object result = pjp.proceed();
            recordService.markSuccess(bizKey, serialize(extractData(signature, result)));
            return result;
        } catch (BizException e) {
            recordService.markFailed(bizKey, "{\"code\":\"" + e.getCode() + "\",\"message\":\"" + e.getMessage() + "\"}");
            throw e;
        } catch (Throwable t) {
            recordService.markFailed(bizKey, "{\"message\":\"" + t.getClass().getName() + "\"}");
            throw t;
        }
    }

    /**
     * 幂等键：由 SpEL 从入参取业务流水号
     */
    private String resolveKey(ProceedingJoinPoint pjp, String expression) {
        Object[] args = pjp.getArgs();
        String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
        StandardEvaluationContext context = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            context.setVariable(names[i], args[i]);
        }
        Object value = parser.parseExpression(expression).getValue(context);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new BizException(ErrorEnum.PARAM_INVALID, "幂等键(" + expression + ")不能为空");
        }
        return value.toString();
    }

    /**
     * 只缓存业务数据体，避免 Result 套 Result
     */
    private Object extractData(MethodSignature signature, Object result) {
        if (result instanceof Result) {
            return ((Result<?>) result).getData();
        }
        return result;
    }

    private Object replay(MethodSignature signature, Object payload, String message) {
        if (Result.class.isAssignableFrom(signature.getReturnType())) {
            return Result.replay(payload, message);
        }
        return payload;
    }

    private String serialize(Object value) {
        try {
            return value == null ? "{}" : objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("[幂等] 结果序列化失败: {}", e.getMessage());
            return "{}";
        }
    }

    /**
     * 依据方法返回值的泛型参数还原数据类型，如 Result&lt;TransferResponse&gt; → TransferResponse
     */
    private Object deserialize(String json, MethodSignature signature) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        Class<?> dataType = resolveDataType(signature.getMethod());
        try {
            if (dataType == null || dataType == Object.class) {
                return json;
            }
            return objectMapper.readValue(json, dataType);
        } catch (Exception e) {
            log.warn("[幂等] 历史结果反序列化失败, 返回原始报文: {}", e.getMessage());
            return json;
        }
    }

    private Class<?> resolveDataType(Method method) {
        Type returnType = method.getGenericReturnType();
        if (returnType instanceof ParameterizedType) {
            Type[] typeArgs = ((ParameterizedType) returnType).getActualTypeArguments();
            if (typeArgs.length == 1 && typeArgs[0] instanceof Class) {
                return (Class<?>) typeArgs[0];
            }
        }
        return null;
    }
}
