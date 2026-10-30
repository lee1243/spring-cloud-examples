package cn.ianlee.example.biz.bank.recon;

import cn.ianlee.example.biz.bank.transfer.TransferOrder;
import cn.ianlee.example.biz.bank.transfer.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * @author Ian.Lee
 * @Description 银行业务定时任务：日终对账 + 悬挂交易守护
 */
@Slf4j
@Component
public class BankScheduler {

    /** 单次告警最多输出的悬挂流水条数（批量造数后可能成百上千条，不能全部打日志） */
    private static final int MAX_ALERT_LOG = 20;

    private final ReconciliationService reconciliationService;
    private final TransferService transferService;

    public BankScheduler(ReconciliationService reconciliationService, TransferService transferService) {
        this.reconciliationService = reconciliationService;
        this.transferService = transferService;
    }

    /**
     * 日终对账：默认每日 02:00 对上一交易日轧账，cron 由配置 bank.recon.cron 控制
     */
    @Scheduled(cron = "${bank.recon.cron:0 0 2 * * ?}")
    public void dailyReconciliation() {
        String tradeDate = reconciliationService.yesterday();
        log.info("[定时任务] 日终对账启动, 交易日={}", tradeDate);
        try {
            ReconSummary summary = reconciliationService.reconcile(tradeDate);
            log.info("[定时任务] 日终对账完成, 批次={}, 差错={}笔, 状态={}, 并行度={}, 分片={}, 耗时{}ms",
                    summary.getBatchNo(), summary.getDiffCount(), summary.getStatus(),
                    summary.getParallelism(), summary.getShardCount(), summary.getCostMillis());
        } catch (RuntimeException e) {
            // 定时任务必须吞掉异常，否则后续调度会被取消
            log.error("[定时任务] 日终对账失败, 交易日={}", tradeDate, e);
        }
    }

    /**
     * 悬挂交易守护：周期性捞取长时间 PROCESSING 的流水并告警，
     * 生产环境此处会主动查询渠道/上游结果做「冲正或补账」
     */
    @Scheduled(fixedDelayString = "${bank.idempotent.hanging-scan-interval-ms:300000}")
    public void scanHangingOrders() {
        List<TransferOrder> hangingOrders = transferService.listHanging();
        if (hangingOrders.isEmpty()) {
            return;
        }
        log.warn("[定时任务] 共发现{}笔悬挂流水，详情如下(最多输出{}条)", hangingOrders.size(), MAX_ALERT_LOG);
        for (int i = 0; i < Math.min(hangingOrders.size(), MAX_ALERT_LOG); i++) {
            TransferOrder order = hangingOrders.get(i);
            log.warn("[定时任务] 发现悬挂流水 orderNo={}, reqNo={}, 金额{}分, 登记于{}",
                    order.getOrderNo(), order.getReqNo(), order.getAmount(), order.getCreateTime());
        }
    }
}
