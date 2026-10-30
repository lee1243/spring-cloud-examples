package cn.ianlee.example.biz.bank.common;

import lombok.Data;

/**
 * @author Ian.Lee
 * @Description 银行接口统一返回体
 */
@Data
public class Result<T> {

    private String code;

    private String message;

    private T data;

    /** 本次请求是否真正执行业务，false 表示被防重拦截或幂等回放历史结果 */
    private boolean executed;

    public static <T> Result<T> ok(T data) {
        return build("000000", "交易成功", data, true);
    }

    public static <T> Result<T> ok(T data, String message) {
        return build("000000", message, data, true);
    }

    public static <T> Result<T> replay(T data, String message) {
        return build("000000", message, data, false);
    }

    public static <T> Result<T> fail(ErrorEnum error, String message) {
        return build(error.getCode(), message == null ? error.getMessage() : message, null, false);
    }

    /**
     * 按已格式化的返回码构造失败应答（异常处理器使用）
     */
    public static <T> Result<T> buildFailure(String code, String message) {
        return build(code, message, null, false);
    }

    private static <T> Result<T> build(String code, String message, T data, boolean executed) {
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        result.setData(data);
        result.setExecuted(executed);
        return result;
    }
}
