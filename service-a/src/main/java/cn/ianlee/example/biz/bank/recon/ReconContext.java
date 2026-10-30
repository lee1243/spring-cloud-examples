package cn.ianlee.example.biz.bank.recon;

import cn.ianlee.example.biz.bank.transfer.TransferOrder;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 对账上下文：主线程一次性取数形成的只读快照。
 * 快照在任务提交前构造完毕、之后不再修改，因此可安全地被多个分片线程读取
 * （提交动作本身建立了 happens-before，无需额外同步）
 */
@Data
public class ReconContext {

    private final String batchNo;

    private final String tradeDate;

    /** 本地流水快照，key = req_no */
    private final Map<String, TransferOrder> localMap;

    /** 渠道流水快照，key = req_no */
    private final Map<String, Map<String, Object>> channelMap;

    /** 待比对的全部请求流水号（两侧并集） */
    private final List<String> allReqNos;

    private final long localAmountTotal;

    private final long channelAmountTotal;
}
