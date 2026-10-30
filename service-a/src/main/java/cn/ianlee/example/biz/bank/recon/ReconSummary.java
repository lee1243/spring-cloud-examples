package cn.ianlee.example.biz.bank.recon;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Ian.Lee
 * @Description 对账批次汇总（对账单/轧账结果）
 */
@Data
public class ReconSummary {

    private String batchNo;

    private String tradeDate;

    private int totalLocal;

    private int totalChannel;

    /** 双方一致笔数 */
    private int matchedCount;

    /** 差错笔数 */
    private int diffCount;

    /** 自动调账成功笔数 */
    private int autoFixedCount;

    /** 转人工处理笔数 */
    private int manualCount;

    /** 本地流水金额合计（分，仅成功交易） */
    private long localAmountTotal;

    /** 渠道流水金额合计（分，仅成功交易） */
    private long channelAmountTotal;

    private String status;

    /** 本次实际并行度，1 为串行 */
    private int parallelism;

    /** 分片数 */
    private int shardCount;

    /** 各分片耗时（毫秒），用于观察并行效果 */
    private List<Long> shardCostMillis = new ArrayList<>();

    /** 因超时/异常未完成的分片编号（比对阶段） */
    private List<Integer> incompleteShards = new ArrayList<>();

    /** 差错调账分组数 */
    private int bookingGroups;

    /** 因超时/异常未完成的调账分组编号 */
    private List<Integer> incompleteBookingGroups = new ArrayList<>();

    /** 比对阶段耗时（毫秒） */
    private long compareCostMillis;

    /** 调账阶段耗时（毫秒） */
    private long bookingCostMillis;

    /** 比对处理异常（非差错）的流水号 */
    private List<String> failedReqNos = new ArrayList<>();

    private long costMillis;

    private List<ReconDiff> diffs = new ArrayList<>();

    /** 轧差金额：渠道合计 - 本地合计 */
    public long getNetDiffAmount() {
        return channelAmountTotal - localAmountTotal;
    }
}
