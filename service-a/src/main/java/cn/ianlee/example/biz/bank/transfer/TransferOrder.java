package cn.ianlee.example.biz.bank.transfer;

import lombok.Data;

import java.sql.Timestamp;

/**
 * @author Ian.Lee
 * @Description 交易流水，状态机：PROCESSING → SUCCESS / FAILED，SUCCESS → REVERSED
 */
@Data
public class TransferOrder {

    /** 处理中（记账中或结果未知，即可能的悬挂交易） */
    public static final String PROCESSING = "PROCESSING";
    /** 记账成功 */
    public static final String SUCCESS = "SUCCESS";
    /** 记账失败（余额不足、入账异常等，已保证资金平衡） */
    public static final String FAILED = "FAILED";
    /** 已冲正 */
    public static final String REVERSED = "REVERSED";

    private String orderNo;

    private String reqNo;

    private String fromAcct;

    private String toAcct;

    private Long amount;

    private String status;

    private String failReason;

    private Integer version;

    private Timestamp createTime;

    private Timestamp updateTime;
}
