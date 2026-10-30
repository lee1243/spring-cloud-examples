package cn.ianlee.example.biz.bank.common;

import org.springframework.stereotype.Component;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Ian.Lee
 * @Description 交易流水号生成器：前缀 + 时间戳 + 序号。
 * 演示用单机实现，生产环境应替换为号段模式（如 Leaf）等分布式发号器
 */
@Component
public class FlowNoGenerator {

    private final AtomicLong sequence = new AtomicLong(0);

    public String next(String prefix) {
        String time = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        long seq = sequence.incrementAndGet() % 100000;
        return prefix + time + String.format("%05d", seq);
    }

    /**
     * 对账批次号：REC + 交易日 + 当日递增序号
     */
    public String nextBatchNo(String tradeDate) {
        return "REC" + tradeDate + String.format("%04d", sequence.incrementAndGet() % 10000);
    }
}
