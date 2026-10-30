package cn.ianlee.example.biz.bank.controller;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import cn.ianlee.example.biz.bank.common.Result;
import cn.ianlee.example.biz.bank.recon.ReconDiff;
import cn.ianlee.example.biz.bank.recon.ReconSummary;
import cn.ianlee.example.biz.bank.recon.ReconciliationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 对账演示接口。启动后 data.sql 已预置 20261029 的双方流水（含 3 类差错），
 * 直接 POST /bank/recon/run?tradeDate=20261029 即可看到对账结果与自动处理过程
 */
@Slf4j
@RestController
@RequestMapping("/bank/recon")
public class ReconController {

    private final ReconciliationService reconciliationService;

    public ReconController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    /**
     * 手工触发对账（不传日期默认对昨日）。
     * 多线程只体现在「比对阶段」分片并行；调账阶段默认串行（写账户行是热点），
     * 同一交易日重复调用会被运行锁拦住并提示系统繁忙
     *
     * @param parallel true 多线程分片对账（默认），false 串行对账，两者结果一致、可对比耗时
     */
    @PostMapping("/run")
    public Result<ReconSummary> run(@RequestParam(required = false) String tradeDate,
                                    @RequestParam(defaultValue = "true") boolean parallel) {
        String date = tradeDate == null ? reconciliationService.yesterday() : tradeDate;
        ReconSummary summary = reconciliationService.reconcile(date, parallel);
        return Result.ok(summary, String.format("交易日%s%s对账完成：待比对%d笔, 一致%d笔, 差错%d笔(自动调账%d/转人工%d), "
                        + "并行度%d, 比对分片%d(耗时%s ms), 调账分组%d, 比对%d ms/调账%d ms, 总耗时%d ms",
                date, parallel ? "[多线程]" : "[串行]", summary.getMatchedCount() + summary.getDiffCount(),
                summary.getMatchedCount(), summary.getDiffCount(), summary.getAutoFixedCount(),
                summary.getManualCount(), summary.getParallelism(), summary.getShardCount(),
                summary.getShardCostMillis(), summary.getBookingGroups(), summary.getCompareCostMillis(),
                summary.getBookingCostMillis(), summary.getCostMillis()));
    }

    /**
     * 造批量演示数据（默认 2000 笔，含四类差错），用于观察多线程与串行的耗时差异
     */
    @PostMapping("/mock-data")
    public Result<Map<String, Object>> mockData(@RequestParam(required = false) String tradeDate,
                                                @RequestParam(defaultValue = "2000") int count,
                                                @RequestParam(defaultValue = "20") int diffEvery) {
        String date = tradeDate == null ? reconciliationService.yesterday() : tradeDate;
        return Result.ok(reconciliationService.prepareDemoData(date, count, diffEvery), "演示数据已生成，可执行对账");
    }

    /**
     * 对账线程池状态
     */
    @GetMapping("/executor")
    public Result<Map<String, Object>> executor() {
        return Result.ok(reconciliationService.executorStats(), "对账线程池状态");
    }

    /**
     * 查看交易日对账运行锁（是否正在对账）
     */
    @GetMapping("/lock")
    public Result<Map<String, Object>> lock(@RequestParam(required = false) String tradeDate) {
        String date = tradeDate == null ? reconciliationService.yesterday() : tradeDate;
        Map<String, Object> lock = reconciliationService.runningLock(date);
        return lock == null ? Result.ok(null, "交易日" + date + "无对账任务在跑")
                : Result.ok(lock, "交易日" + date + "正在对账");
    }

    /**
     * 强制释放运行锁（仅演示/故障处理，正常情况由对账结束自动释放）
     */
    @DeleteMapping("/lock")
    public Result<Integer> releaseLock(@RequestParam String tradeDate) {
        return Result.ok(reconciliationService.forceReleaseLock(tradeDate), "运行锁已释放");
    }

    /**
     * 对账报告（文本），纯查询上一次对账结果，不会重复对账调账
     */
    @GetMapping("/report")
    public Result<String> report(@RequestParam(required = false) String tradeDate) {
        String date = tradeDate == null ? reconciliationService.yesterday() : tradeDate;
        return Result.ok(buildReport(reconciliationService.loadLatest(date)), "对账报告");
    }

    /**
     * 对账批次清单
     */
    @GetMapping("/batches")
    public Result<List<Map<String, Object>>> batches(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(reconciliationService.listBatches(limit), "对账批次");
    }

    /**
     * 差错明细查询
     */
    @GetMapping("/diffs")
    public Result<List<ReconDiff>> diffs(@RequestParam String batchNo) {
        return Result.ok(reconciliationService.listDiffs(batchNo), "差错明细");
    }

    /**
     * 模拟接收渠道对账文件（登记渠道流水）
     */
    @PostMapping("/channel-flow")
    public Result<String> importChannelFlow(@RequestParam String channelSerial,
                                            @RequestParam String reqNo,
                                            @RequestParam Long amount,
                                            @RequestParam String tradeDate,
                                            @RequestParam(defaultValue = "SUCCESS") String status) {
        boolean saved = reconciliationService.importChannelFlow(channelSerial, reqNo, amount, tradeDate, status);
        if (!saved) {
            throw new BizException(ErrorEnum.DUPLICATE_SUBMIT, "渠道流水号已存在: " + channelSerial);
        }
        return Result.ok(channelSerial, "渠道流水登记成功");
    }

    /**
     * 人工处理差错：result = FIXED（已调账）/ IGNORED（核销）
     */
    @PostMapping("/diff/{id}/handle")
    public Result<ReconDiff> handleDiff(@PathVariable Long id,
                                        @RequestParam(defaultValue = "FIXED") String result,
                                        @RequestParam(required = false) String remark,
                                        @RequestParam(defaultValue = "OPER001") String operator) {
        return Result.ok(reconciliationService.handleManually(id, result, remark, operator), "差错处理完成");
    }

    /**
     * 根据当日最近批次生成文本报告
     */
    private String buildReport(ReconSummary summary) {
        StringBuilder sb = new StringBuilder();
        sb.append("======== 日终对账报告 ========\n")
                .append("批次号    : ").append(summary.getBatchNo()).append('\n')
                .append("交易日    : ").append(summary.getTradeDate()).append('\n')
                .append("本地笔数  : ").append(summary.getTotalLocal()).append('\n')
                .append("渠道笔数  : ").append(summary.getTotalChannel()).append('\n')
                .append("一致笔数  : ").append(summary.getMatchedCount()).append('\n')
                .append("差错笔数  : ").append(summary.getDiffCount()).append('\n')
                .append("本地合计  : ").append(yuan(summary.getLocalAmountTotal())).append('\n')
                .append("渠道合计  : ").append(yuan(summary.getChannelAmountTotal())).append('\n')
                .append("轧    差  : ").append(yuan(summary.getNetDiffAmount())).append('\n')
                .append("对账结果  : ").append(summary.getStatus()).append('\n')
                .append("并行度    : ").append(summary.getParallelism()).append('\n')
                .append("分片数    : ").append(summary.getShardCount()).append('\n')
                .append("分片耗时  : ").append(summary.getShardCostMillis()).append('\n')
                .append("未完成分片: ").append(summary.getIncompleteShards()).append('\n')
                .append("调账未完组: ").append(summary.getIncompleteBookingGroups()).append('\n')
                .append("异常流水  : ").append(summary.getFailedReqNos()).append('\n')
                .append("总耗时    : ").append(summary.getCostMillis()).append(" ms\n");
        if (summary.getDiffs().isEmpty()) {
            sb.append("无差错，账账相符。\n");
        } else {
            sb.append("-------- 差错明细 --------\n");
            for (ReconDiff diff : summary.getDiffs()) {
                sb.append(String.format("[%d] %s reqNo=%s 本地%s/%d 渠道%s/%d => %s：%s%n",
                        diff.getId(), diff.getDiffTypeName(), diff.getReqNo(),
                        diff.getLocalStatus(), diff.getLocalAmount(), diff.getChannelStatus(), diff.getChannelAmount(),
                        diff.getHandleStatus(), diff.getHandleRemark()));
            }
        }
        return sb.toString();
    }

    private String yuan(long cents) {
        return String.format("%.2f 元（%d 分）", cents / 100.0, cents);
    }
}
