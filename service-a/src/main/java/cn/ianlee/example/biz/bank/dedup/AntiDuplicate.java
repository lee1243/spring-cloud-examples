package cn.ianlee.example.biz.bank.dedup;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author Ian.Lee
 * @Description 接口防重提交注解，标注在 Controller 方法上。
 * 与幂等的区别：防重是「短时间内不允许重复点击」，幂等是「同一流水号重复请求结果一致」
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AntiDuplicate {

    /** 业务类型，参与指纹计算 */
    String bizType() default "default";

    /** 防重时间窗口（秒），窗口内相同指纹直接拦截；小于等于 0 时取配置 bank.dedup.window-seconds */
    int windowSeconds() default -1;

    /** 是否要求携带一次性防重令牌（先申领后消费模式） */
    boolean requireToken() default false;

    /** 令牌所在的请求头名称 */
    String tokenHeader() default "X-Dedup-Token";
}
