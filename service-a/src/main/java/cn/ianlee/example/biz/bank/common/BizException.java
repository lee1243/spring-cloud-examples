package cn.ianlee.example.biz.bank.common;

import lombok.Getter;

/**
 * @author Ian.Lee
 * @Description 业务异常，由全局异常处理器转换为统一返回体
 */
@Getter
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String code;

    public BizException(ErrorEnum error) {
        this(error, error.getMessage());
    }

    public BizException(ErrorEnum error, String message) {
        super(message);
        this.code = error.getCode();
    }
}
