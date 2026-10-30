package cn.ianlee.example.biz.bank.common;

import lombok.Getter;

/**
 * @author Ian.Lee
 * @Description 银行交易返回码
 */
@Getter
public enum ErrorEnum {

    SYSTEM_BUSY("BB0001", "系统繁忙，请稍后重试"),
    DUPLICATE_SUBMIT("BB0002", "请勿重复提交"),
    TOKEN_INVALID("BB0003", "防重令牌无效或已过期，请重新发起"),
    IDEMPOTENT_PROCESSING("BB0004", "上一笔请求仍在处理中，请勿重复提交"),
    BALANCE_NOT_ENOUGH("BB0005", "付款账户余额不足"),
    ACCOUNT_NOT_EXIST("BB0006", "账户不存在"),
    ACCOUNT_STATUS_ABNORMAL("BB0007", "账户状态异常"),
    ORDER_NOT_EXIST("BB0008", "交易不存在"),
    ORDER_STATUS_ILLEGAL("BB0009", "交易状态不允许该操作"),
    PARAM_INVALID("BB0010", "请求参数不合法"),
    AMOUNT_ILLEGAL("BB0011", "交易金额不合法");

    private final String code;

    private final String message;

    ErrorEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
