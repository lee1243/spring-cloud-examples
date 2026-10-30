package cn.ianlee.example.biz.bank.transfer;

import lombok.Data;

/**
 * @author Ian.Lee
 * @Description 转账结果
 */
@Data
public class TransferResponse {

    private String orderNo;

    private String reqNo;

    private String status;

    /** 交易金额，单位：分 */
    private Long amount;

    private String fromAcct;

    private String toAcct;

    /** 付款账户交易后余额，单位：分 */
    private Long fromBalanceAfter;

    private String message;
}
