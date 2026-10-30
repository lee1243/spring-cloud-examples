package cn.ianlee.example.biz.bank.transfer;

import lombok.Data;

import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/**
 * @author Ian.Lee
 * @Description 转账请求。reqNo 为渠道请求流水号，同时是防重与幂等的业务唯一键
 */
@Data
public class TransferRequest {

    @NotBlank(message = "付款账号不能为空")
    private String fromAcct;

    @NotBlank(message = "收款账号不能为空")
    private String toAcct;

    /** 交易金额，单位：分 */
    @NotNull(message = "交易金额不能为空")
    @Min(value = 1, message = "交易金额必须大于0")
    private Long amount;

    @NotBlank(message = "请求流水号(reqNo)不能为空")
    private String reqNo;

    /** 防重令牌，仅令牌模式下需要（也可放在请求头 X-Dedup-Token） */
    private String dedupToken;
}
