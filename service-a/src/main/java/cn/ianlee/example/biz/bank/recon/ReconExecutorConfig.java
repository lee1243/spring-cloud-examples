package cn.ianlee.example.biz.bank.recon;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * @author Ian.Lee
 * @Description 对账专用线程池。银行日终对账属于「批量跑批」场景，
 * 与在线交易线程池物理隔离，避免对账高峰拖垮联机交易。关键取舍：
 * 1）有界队列 + CallerRunsPolicy：队列满时由提交线程自己跑，宁可降速也不丢对账分片；
 * 2）waitForTasksToCompleteOnShutdown：停机时等待调账动作做完，避免「调账到一半」造成账不平；
 * 3）线程命名 recon-：便于日志与线程栈定位
 */
@Slf4j
@Configuration
public class ReconExecutorConfig {

    public static final String RECON_EXECUTOR = "reconExecutor";

    @Value("${bank.recon.parallelism:4}")
    private int parallelism;

    @Value("${bank.recon.queue-capacity:200}")
    private int queueCapacity;

    @Value("${bank.recon.await-termination-seconds:60}")
    private int awaitTerminationSeconds;

    @Bean(name = RECON_EXECUTOR)
    public ThreadPoolTaskExecutor reconExecutor() {
        int poolSize = Math.max(1, parallelism);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolSize);
        executor.setMaxPoolSize(poolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(60);
        executor.setThreadNamePrefix("recon-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        log.info("[对账] 线程池初始化完成, 并行度={}, 队列容量={}", poolSize, queueCapacity);
        return executor;
    }
}
