package cn.ianlee.example.biz.bank.recon;

import lombok.Data;

/**
 * @author Ian.Lee
 * @Description 对账差错明细
 */
@Data
public class ReconDiff {

    /** 仅本地有：渠道无此流水，本地多记 → 短款方向，需人工核实 */
    public static final String LOCAL_ONLY = "LOCAL_ONLY";
    /** 仅渠道有：本地无此流水，渠道多记 → 长款，需挂账/退汇 */
    public static final String CHANNEL_ONLY = "CHANNEL_ONLY";
    /** 双方都有但金额不一致 */
    public static final String AMOUNT_DIFF = "AMOUNT_DIFF";
    /** 双方都有且金额一致但状态不一致（如本地悬挂、渠道成功） */
    public static final String STATUS_DIFF = "STATUS_DIFF";

    public static final String PENDING = "PENDING";
    public static final String FIXED = "FIXED";
    public static final String MANUAL = "MANUAL";
    public static final String IGNORED = "IGNORED";

    private Long id;

    private String batchNo;

    private String reqNo;

    private String diffType;

    private Long localAmount;

    private Long channelAmount;

    private String localStatus;

    private String channelStatus;

    private String handleStatus;

    private String handleRemark;

    /** 差额（渠道 - 本地），正数表示本地少记 */
    public long getDiffAmount() {
        long local = localAmount == null ? 0L : localAmount;
        long channel = channelAmount == null ? 0L : channelAmount;
        return channel - local;
    }

    /** 差错类型中文描述，用于报告与日志 */
    public String getDiffTypeName() {
        switch (diffType == null ? "" : diffType) {
            case LOCAL_ONLY:
                return "短款(本地有/渠道无)";
            case CHANNEL_ONLY:
                return "长款(渠道有/本地无)";
            case AMOUNT_DIFF:
                return "金额不符";
            case STATUS_DIFF:
                return "状态不符";
            default:
                return diffType;
        }
    }
}
