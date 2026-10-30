package cn.ianlee.example.biz.bank.recon;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * @author Ian.Lee
 * @Description 单个分片的对账结果。分片内数据只由本线程写入、主线程汇总，
 * 因此无需加锁；跨线程共享的累加统计一律放到主线程完成
 */
@Data
public class ReconShardResult {

    /** 分片序号，从 0 开始 */
    private int shardNo;

    /** 线程名，便于观察并行效果 */
    private String threadName;

    /** 本分片负责的流水号笔数 */
    private int assignedCount;

    /** 本分片一致笔数 */
    private int matchedCount;

    /** 本分片差错笔数 */
    private int diffCount;

    /** 本分片自动调账成功笔数 */
    private int autoFixedCount;

    /** 本分片转人工笔数 */
    private int manualCount;

    /** 本分片处理异常的流水号（不影响其他分片，最终汇总给人工） */
    private List<String> failedReqNos = new ArrayList<>();

    /** 本分片差错明细 */
    private List<ReconDiff> diffs = new ArrayList<>();

    private long costMillis;

    /** 是否发生超时/中断被取消 */
    private boolean cancelled;
}
