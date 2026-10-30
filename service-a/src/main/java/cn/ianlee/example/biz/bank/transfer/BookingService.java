package cn.ianlee.example.biz.bank.transfer;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author Ian.Lee
 * @Description 记账服务：借贷必在同一个本地事务内完成，要么同时成功，要么同时回滚，
 * 保证「账账相符、账务平衡」。流水登记与状态推进刻意放在事务外，
 * 使失败流水也能留痕可查、可冲正
 */
@Slf4j
@Service
public class BookingService {

    private final AccountRepository accountRepository;

    public BookingService(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * 转账记账：先扣付款方，再入收款方
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void bookTransfer(String fromAcct, String toAcct, long amount) {
        if (!accountRepository.debit(fromAcct, amount)) {
            // 抛出运行时异常触发回滚，扣减结果一并撤销
            throw new BizException(ErrorEnum.BALANCE_NOT_ENOUGH,
                    String.format("付款账户可用余额 %d 分，本次交易 %d 分",
                            accountRepository.balance(fromAcct), amount));
        }
        accountRepository.credit(toAcct, amount);
        log.info("[记账] 转账完成 {} -> {} 金额{}分", fromAcct, toAcct, amount);
    }

    /**
     * 冲正记账：方向与原交易相反
     */
    @Transactional(rollbackFor = Exception.class, isolation = Isolation.READ_COMMITTED)
    public void bookReverse(String fromAcct, String toAcct, long amount) {
        accountRepository.credit(fromAcct, amount);
        accountRepository.debit(toAcct, amount);
        log.info("[记账] 冲正完成 回补{} 扣回{} 金额{}分", fromAcct, toAcct, amount);
    }

    /**
     * 单边记账（入内部挂账户/待退汇账户），用于长款、短款等单方向挂账。
     * 注意：对外的金额不符调账不能用它，必须走双边分录（bookTransfer），否则账不平
     *
     * @param positive true 入账，false 扣减
     */
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public void bookAdjustment(String acctNo, long amount, boolean positive) {
        if (positive) {
            accountRepository.credit(acctNo, amount);
        } else if (!accountRepository.debit(acctNo, amount)) {
            throw new BizException(ErrorEnum.BALANCE_NOT_ENOUGH, "调账扣减失败: " + acctNo);
        }
        log.info("[记账] 差错调整 账户{} 金额{}分 方向{}", acctNo, amount, positive ? "入" : "出");
    }

    /**
     * 单边入账：悬挂交易补账、失败回补均走该方法
     */
    @Transactional(rollbackFor = Exception.class)
    public void creditOnly(String acctNo, long amount) {
        accountRepository.credit(acctNo, amount);
        log.info("[记账] 单边入账 账户{} 金额{}分", acctNo, amount);
    }
}
