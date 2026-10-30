package cn.ianlee.example.biz.bank.controller;

import cn.ianlee.example.biz.bank.common.Result;
import cn.ianlee.example.biz.bank.dedup.AntiDuplicate;
import cn.ianlee.example.biz.bank.idempotent.Idempotent;
import cn.ianlee.example.biz.bank.idempotent.IdempotentRecordService;
import cn.ianlee.example.biz.bank.transfer.TransferOrder;
import cn.ianlee.example.biz.bank.transfer.TransferRequest;
import cn.ianlee.example.biz.bank.transfer.TransferResponse;
import cn.ianlee.example.biz.bank.transfer.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 幂等演示接口（转账为银行业务主干场景）。典型验证顺序：
 * 1）POST /bank/transfer 用 reqNo=REQ001 提交 → 正常记账，executed=true
 * 2）再次用相同 reqNo=REQ001 提交 → 返回首次结果，executed=false，余额不再变化
 * 3）GET /bank/idempotent/records 观察幂等表状态流转
 * 4）POST /bank/transfer/hang-demo 制造悬挂流水 → GET /bank/transfer/hanging 查看 →
 * POST /bank/transfer/hanging/resolve 补账或回补
 */
@Slf4j
@RestController
@RequestMapping("/bank")
public class TransferController {

    private final TransferService transferService;
    private final IdempotentRecordService idempotentRecordService;

    public TransferController(TransferService transferService, IdempotentRecordService idempotentRecordService) {
        this.transferService = transferService;
        this.idempotentRecordService = idempotentRecordService;
    }

    /**
     * 幂等转账：以 reqNo 为幂等键，重复请求回放首次结果，绝不重复记账
     */
    @PostMapping("/transfer")
    @Idempotent(bizType = "transfer", key = "#request.reqNo")
    public Result<TransferResponse> transfer(@Valid @RequestBody TransferRequest request) {
        return Result.ok(transferService.transfer(request));
    }

    /**
     * 全防线转账：令牌防重 + 窗口防重 + 幂等 + req_no 唯一索引，生产交易接口推荐此组合
     */
    @PostMapping("/transfer/full")
    @AntiDuplicate(bizType = "transfer", windowSeconds = 2, requireToken = true)
    @Idempotent(bizType = "transfer", key = "#request.reqNo")
    public Result<TransferResponse> transferWithAllGuards(@Valid @RequestBody TransferRequest request) {
        return Result.ok(transferService.transfer(request));
    }

    /**
     * 并发压测演示：同一 reqNo 高并发提交，只有一笔记账成功，其余为「处理中」或「回放结果」
     */
    @PostMapping("/transfer/concurrent")
    @Idempotent(bizType = "transfer", key = "#request.reqNo", processingTimeoutSeconds = 5)
    public Result<TransferResponse> transferConcurrent(@Valid @RequestBody TransferRequest request) throws Exception {
        // 模拟银行核心链路耗时（渠道报文、风控校验等），放大并发窗口
        Thread.sleep(300L);
        return Result.ok(transferService.transfer(request));
    }

    /**
     * 制造悬挂交易：已扣款但流水停留 PROCESSING
     */
    @PostMapping("/transfer/hang-demo")
    @Idempotent(bizType = "transfer-hang", key = "#request.reqNo")
    public Result<TransferResponse> hangDemo(@Valid @RequestBody TransferRequest request) {
        return Result.ok(transferService.simulateHanging(request));
    }

    /**
     * 查询补偿：上游超时后凭 reqNo 获取真实结果，避免盲目重发
     */
    @GetMapping("/transfer/query")
    public Result<TransferResponse> query(@RequestParam String reqNo) {
        return Result.ok(transferService.query(reqNo), "查询成功");
    }

    /**
     * 冲正
     */
    @PostMapping("/transfer/reverse")
    public Result<TransferResponse> reverse(@RequestParam String reqNo,
                                            @RequestParam(defaultValue = "OPER001") String operator) {
        return Result.ok(transferService.reverse(reqNo, operator), "冲正成功");
    }

    /**
     * 悬挂流水清单
     */
    @GetMapping("/transfer/hanging")
    public Result<List<TransferOrder>> hanging() {
        return Result.ok(transferService.listHanging(), "当前悬挂流水");
    }

    /**
     * 悬挂流水处理：toStatus=SUCCESS 补账入账，FAILED 回补付款方
     */
    @PostMapping("/transfer/hanging/resolve")
    public Result<TransferResponse> resolveHanging(@RequestParam String reqNo, @RequestParam String toStatus) {
        return Result.ok(transferService.resolveHanging(reqNo, toStatus), "悬挂交易已处理为 " + toStatus);
    }

    /**
     * 最近流水，便于观察状态机流转
     */
    @GetMapping("/transfer/latest")
    public Result<List<TransferOrder>> latest(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(transferService.listLatest(limit), "最近" + limit + "笔流水");
    }

    @GetMapping("/account/list")
    public Result<List<Map<String, Object>>> accounts() {
        return Result.ok(transferService.accounts(), "账户清单");
    }

    @GetMapping("/account/{acctNo}")
    public Result<Map<String, Object>> account(@PathVariable String acctNo) {
        return Result.ok(transferService.account(acctNo), "账户信息");
    }

    /**
     * 幂等记录清单
     */
    @GetMapping("/idempotent/records")
    public Result<List<Map<String, Object>>> idempotentRecords(@RequestParam(defaultValue = "20") int limit) {
        return Result.ok(idempotentRecordService.list(limit), "幂等记录");
    }

    /**
     * 查询单笔幂等状态，直观看到 PROCESSING/SUCCESS/FAILED
     */
    @GetMapping("/idempotent/record")
    public Result<Map<String, Object>> idempotentRecord(@RequestParam String bizType, @RequestParam String bizKey) {
        return Result.ok(idempotentRecordService.find(bizType + ":" + bizKey), "幂等记录");
    }

    /**
     * 清理幂等记录（仅演示用，生产严禁）。便于反复演练同一 reqNo
     */
    @DeleteMapping("/idempotent/record")
    public Result<String> cleanIdempotent(@RequestParam String bizType, @RequestParam String bizKey) {
        String fullKey = bizType + ":" + bizKey;
        idempotentRecordService.delete(fullKey);
        log.info("[幂等] 已清理幂等记录 {}", fullKey);
        return Result.ok(fullKey, "幂等记录已清理，同一流水号可重新执行");
    }
}
