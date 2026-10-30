package cn.ianlee.example.biz.bank.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Ian.Lee
 * @Description 幂等注解：以业务流水号为幂等键，重复请求回放首次执行结果，绝不重复记账
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 业务类型，与幂等键共同构成记录主键前缀 */
    String bizType() default "transfer";

    /** 幂等键的 SpEL 表达式，例如 #req.reqNo */
    String key();

    /** 处理中(state=PROCESSING)允许的挂起秒数，超过视为悬挂交易并可被接管重试；小于等于 0 取配置值 */
    int processingTimeoutSeconds() default -1;
}
