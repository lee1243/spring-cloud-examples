package cn.ianlee.example.biz.bank.controller;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import cn.ianlee.example.biz.bank.common.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.stream.Collectors;

/**
 * @author Ian.Lee
 * @Description 全局异常处理：银行报文一律返回 200 + 业务返回码，
 * 便于上游按 code 判断「成功/失败/可重试」，不按 HTTP 状态码判定交易结果
 */
@Slf4j
@RestControllerAdvice(basePackages = "cn.ianlee.example.biz.bank")
public class BankExceptionHandler {

    /**
     * 业务异常：防重拦截、幂等冲突、余额不足、状态不允许等
     */
    @ExceptionHandler(BizException.class)
    public Result<Void> handleBiz(BizException e) {
        log.warn("[异常] 业务异常 code={}, msg={}", e.getCode(), e.getMessage());
        return Result.buildFailure(e.getCode(), e.getMessage());
    }

    /**
     * 参数校验失败
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleInvalidArgument(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("；"));
        log.warn("[异常] 参数校验失败: {}", message);
        return Result.fail(ErrorEnum.PARAM_INVALID, message);
    }

    /**
     * 数据库异常：唯一约束冲突等已在业务层转换，此处兜底
     */
    @ExceptionHandler(DataAccessException.class)
    public Result<Void> handleDataAccess(DataAccessException e) {
        log.error("[异常] 数据访问异常", e);
        return Result.fail(ErrorEnum.SYSTEM_BUSY, "数据处理异常，请稍后重试");
    }

    /**
     * 未预期异常：不向前端暴露堆栈细节
     */
    @ExceptionHandler(Exception.class)
    public Result<Void> handleUnexpected(Exception e) {
        log.error("[异常] 系统异常", e);
        return Result.fail(ErrorEnum.SYSTEM_BUSY, null);
    }
}
