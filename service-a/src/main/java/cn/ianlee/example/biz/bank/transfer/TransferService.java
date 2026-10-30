package cn.ianlee.example.biz.bank.transfer;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import cn.ianlee.example.biz.bank.common.FlowNoGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * @author Ian.Lee
 * @Description 转账服务（银行业务主干场景）。流程：
 * 1) 登记流水 PROCESSING —— req_no 唯一索引兜底防重
 * 2) 并发/重复请求命中同一流水时按状态机决策：回放 / 拒绝 / 重新记账
 * 3) 记账（借贷同事务）
 * 4) CAS 推进终态 SUCCESS / FAILED
 * 全程只允许 PROCESSING → 终态 的单次流转，因此重复请求不会重复记账
 */
@Slf4j
@Service
public class TransferService {

    private final AccountRepository accountRepository;
    private final TransferOrderRepository orderRepository;
    private final BookingService bookingService;
    private final FlowNoGenerator flowNoGenerator;

    @Value("${bank.idempotent.processing-timeout-seconds:30}")
    private int hangingTimeoutSeconds;

    public TransferService(AccountRepository accountRepository,
                           TransferOrderRepository orderRepository,
                           BookingService bookingService,
                           FlowNoGenerator flowNoGenerator) {
        this.accountRepository = accountRepository;
        this.orderRepository = orderRepository;
        this.bookingService = bookingService;
        this.flowNoGenerator = flowNoGenerator;
    }

    public TransferResponse transfer(TransferRequest request) {
        validate(request);

        // 步骤一：登记流水
        TransferOrder order = new TransferOrder();
        order.setOrderNo(flowNoGenerator.next("TRF"));
        order.setReqNo(request.getReqNo());
        order.setFromAcct(request.getFromAcct());
        order.setToAcct(request.getToAcct());
        order.setAmount(request.getAmount());
        order.setStatus(TransferOrder.PROCESSING);

        if (!orderRepository.insert(order)) {
            // 步骤二：流水号已存在，说明是重复/重试请求，按状态机决策
            return handleExisting(orderRepository.requireByReqNo(request.getReqNo()), request);
        }

        // 步骤三：记账；步骤四：推进终态
        return bookAndFinish(order.getOrderNo(), request.getFromAcct(), request.getToAcct(), request.getAmount());
    }

    /**
     * 已存在流水的处理策略：这是幂等语义的核心
     */
    private TransferResponse handleExisting(TransferOrder exist, TransferRequest request) {
        switch (exist.getStatus()) {
            case TransferOrder.PROCESSING:
                // 另一线程/另一次重试正在处理，结果未知，交由「查询补偿」获取最终结果
                log.warn("[幂等] 流水处理中, reqNo={}, orderNo={}", exist.getReqNo(), exist.getOrderNo());
                throw new BizException(ErrorEnum.IDEMPOTENT_PROCESSING);
            case TransferOrder.SUCCESS:
                log.info("[幂等] 流水已成功, 直接回放结果, reqNo={}", exist.getReqNo());
                return toResponse(exist, "该流水号已成功入账，本次为重复请求，未重复记账");
            default:
                // FAILED / REVERSED：允许同流水号重新发起，状态回置 PROCESSING 后重记账（CAS 保证单线程接管）
                if (!orderRepository.casUpdateStatus(exist.getOrderNo(), exist.getStatus(),
                        TransferOrder.PROCESSING, null)) {
                    throw new BizException(ErrorEnum.IDEMPOTENT_PROCESSING);
                }
                log.info("[幂等] 流水状态{}→PROCESSING, 重新记账, reqNo={}", exist.getStatus(), exist.getReqNo());
                return bookAndFinish(exist.getOrderNo(), request.getFromAcct(), request.getToAcct(), request.getAmount());
        }
    }

    /**
     * 记账 + 终态推进
     */
    private TransferResponse bookAndFinish(String orderNo, String fromAcct, String toAcct, long amount) {
        try {
            bookingService.bookTransfer(fromAcct, toAcct, amount);
        } catch (BizException e) {
            orderRepository.casUpdateStatus(orderNo, TransferOrder.PROCESSING, TransferOrder.FAILED, e.getMessage());
            throw e;
        } catch (RuntimeException e) {
            orderRepository.casUpdateStatus(orderNo, TransferOrder.PROCESSING, TransferOrder.FAILED, "系统异常:" + e.getMessage());
            throw e;
        }

        boolean finished = orderRepository.casUpdateStatus(orderNo, TransferOrder.PROCESSING, TransferOrder.SUCCESS, null);
        if (!finished) {
            log.warn("[幂等] 终态推进失败(已被其他线程推进), orderNo={}", orderNo);
        }
        TransferOrder latest = orderRepository.findByOrderNo(orderNo);
        TransferResponse response = toResponse(latest, "转账成功");
        response.setFromBalanceAfter(accountRepository.balance(fromAcct));
        return response;
    }

    /**
     * 冲正：仅 SUCCESS 流水可冲正，状态机保证不可重复冲正
     */
    public TransferResponse reverse(String reqNo, String operator) {
        TransferOrder order = orderRepository.requireByReqNo(reqNo);
        if (!TransferOrder.SUCCESS.equals(order.getStatus())) {
            throw new BizException(ErrorEnum.ORDER_STATUS_ILLEGAL,
                    "当前状态[" + order.getStatus() + "]不允许冲正，仅成功交易可冲正");
        }
        // 先锁状态再动账：CAS 失败说明已被他人冲正
        if (!orderRepository.casUpdateStatus(order.getOrderNo(), TransferOrder.SUCCESS, TransferOrder.REVERSED,
                "冲正操作人:" + operator)) {
            throw new BizException(ErrorEnum.ORDER_STATUS_ILLEGAL, "并发冲突，冲正失败，请稍后重试");
        }
        try {
            bookingService.bookReverse(order.getFromAcct(), order.getToAcct(), order.getAmount());
        } catch (RuntimeException e) {
            // 记账失败则回退状态，避免状态与账务不一致
            orderRepository.casUpdateStatus(order.getOrderNo(), TransferOrder.REVERSED, TransferOrder.SUCCESS, null);
            throw e;
        }
        log.info("[冲正] orderNo={}, reqNo={}, 金额{}分, 操作人={}", order.getOrderNo(), reqNo, order.getAmount(), operator);

        TransferResponse response = toResponse(orderRepository.findByOrderNo(order.getOrderNo()),
                String.format("冲正成功，已回补付款账户 %d 分", order.getAmount()));
        response.setFromBalanceAfter(accountRepository.balance(order.getFromAcct()));
        return response;
    }

    /**
     * 查询补偿：上游超时后凭 reqNo 查询真实结果，避免盲目重发
     */
    public TransferResponse query(String reqNo) {
        return toResponse(orderRepository.requireByReqNo(reqNo), "查询成功");
    }

    /**
     * 演示用：模拟「已扣款但进程崩溃」的悬挂交易，流水停留在 PROCESSING
     */
    public TransferResponse simulateHanging(TransferRequest request) {
        validate(request);
        TransferOrder order = new TransferOrder();
        order.setOrderNo(flowNoGenerator.next("TRF"));
        order.setReqNo(request.getReqNo());
        order.setFromAcct(request.getFromAcct());
        order.setToAcct(request.getToAcct());
        order.setAmount(request.getAmount());
        order.setStatus(TransferOrder.PROCESSING);
        if (!orderRepository.insert(order)) {
            return toResponse(orderRepository.requireByReqNo(request.getReqNo()), "该流水号已存在");
        }
        // 只做了扣款，未推进终态，模拟宕机
        accountRepository.debit(request.getFromAcct(), request.getAmount());
        log.warn("[悬挂] 已构造悬挂流水 orderNo={}, 可用 GET /bank/transfer/hanging 查看", order.getOrderNo());

        TransferResponse response = toResponse(order, "已模拟记账中断，流水停留在 PROCESSING（悬挂）");
        response.setFromBalanceAfter(accountRepository.balance(request.getFromAcct()));
        return response;
    }

    /**
     * 悬挂流水处理：按渠道/上游确认结果决定补账或回补
     *
     * @param toStatus SUCCESS（补账入账）或 FAILED（回补扣款）
     */
    public TransferResponse resolveHanging(String reqNo, String toStatus) {
        TransferOrder order = orderRepository.requireByReqNo(reqNo);
        if (!TransferOrder.PROCESSING.equals(order.getStatus())) {
            throw new BizException(ErrorEnum.ORDER_STATUS_ILLEGAL, "流水已是终态: " + order.getStatus());
        }
        if (TransferOrder.SUCCESS.equals(toStatus)) {
            // 上游/渠道确认交易实际成功：补记入账
            bookingService.creditOnly(order.getToAcct(), order.getAmount());
            orderRepository.casUpdateStatus(order.getOrderNo(), TransferOrder.PROCESSING, TransferOrder.SUCCESS, "悬挂补账");
        } else if (TransferOrder.FAILED.equals(toStatus)) {
            // 确认交易失败：将已扣金额回补付款账户
            bookingService.creditOnly(order.getFromAcct(), order.getAmount());
            orderRepository.casUpdateStatus(order.getOrderNo(), TransferOrder.PROCESSING, TransferOrder.FAILED, "悬挂回补");
        } else {
            throw new BizException(ErrorEnum.PARAM_INVALID, "目标状态只能为 SUCCESS 或 FAILED");
        }
        log.info("[悬挂处理] reqNo={}, 处理为{}", reqNo, toStatus);
        return toResponse(orderRepository.findByOrderNo(order.getOrderNo()), "悬挂交易已处理为 " + toStatus);
    }

    /**
     * 悬挂流水清单（守护任务/日终检查用）
     */
    public List<TransferOrder> listHanging() {
        return orderRepository.listHanging(hangingTimeoutSeconds);
    }

    public List<TransferOrder> listLatest(int limit) {
        return orderRepository.listLatest(limit);
    }

    public Map<String, Object> account(String acctNo) {
        return accountRepository.findAccount(acctNo);
    }

    public List<Map<String, Object>> accounts() {
        return accountRepository.listAccounts();
    }

    private void validate(TransferRequest request) {
        if (request.getFromAcct().equals(request.getToAcct())) {
            throw new BizException(ErrorEnum.PARAM_INVALID, "付款账户与收款账户不能相同");
        }
        if (request.getAmount() == null || request.getAmount() <= 0) {
            throw new BizException(ErrorEnum.AMOUNT_ILLEGAL);
        }
        // 账户不存在/状态异常在此抛出
        accountRepository.findAccount(request.getFromAcct());
        accountRepository.findAccount(request.getToAcct());
    }

    private TransferResponse toResponse(TransferOrder order, String message) {
        TransferResponse response = new TransferResponse();
        response.setOrderNo(order.getOrderNo());
        response.setReqNo(order.getReqNo());
        response.setStatus(order.getStatus());
        response.setAmount(order.getAmount());
        response.setFromAcct(order.getFromAcct());
        response.setToAcct(order.getToAcct());
        response.setMessage(message);
        return response;
    }
}
