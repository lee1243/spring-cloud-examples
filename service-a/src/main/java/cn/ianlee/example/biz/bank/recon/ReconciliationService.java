package cn.ianlee.example.biz.bank.recon;

import cn.ianlee.example.biz.bank.common.BizException;
import cn.ianlee.example.biz.bank.common.ErrorEnum;
import cn.ianlee.example.biz.bank.common.FlowNoGenerator;
import cn.ianlee.example.biz.bank.common.RowMapUtil;
import cn.ianlee.example.biz.bank.transfer.AccountRepository;
import cn.ianlee.example.biz.bank.transfer.BookingService;
import cn.ianlee.example.biz.bank.transfer.TransferOrder;
import cn.ianlee.example.biz.bank.transfer.TransferOrderRepository;
import cn.ianlee.example.biz.bank.transfer.TransferService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * @author Ian.Lee
 * @Description 多线程分片对账服务，银行日终对账三步法：
 * 1）取数：加载本地流水 + 渠道（对手机构）流水，生产中第一步是下载并解析对账文件；
 * 2）比对：以渠道请求流水号 req_no 为主键逐条比对金额、状态，产出四类差错；
 * 3）差错处理：能自动化的按「以渠道为准」原则补账/冲正，其余转人工，全过程留痕可审计。
 *
 * 并行化要点（跑批类任务的标准拆法，分「比对」与「调账」两个阶段差异化处理）：
 * - 主线程一次性取数并构造只读快照，子线程不再读全量数据，避免 N 倍 DB 开销；
 * - 比对阶段纯读纯计算，是日终对账的耗时大头：按 reqNo 哈希分桶，同一笔流水只落一个分片，
 *   分片间无交集，因此无需加锁也不会重复调账，可放心按并行度扩线程；
 * - 调账阶段是写账务（锁定借贷账户行），属于典型写热点：默认串行执行，
 *   开启 bank.recon.parallel-booking 后用「并查集按账户聚合分组」，同组账户互不相交才并行，
 *   避免并行下同一行锁竞争甚至死锁（差错笔数通常仅占总流水千分之几，串行不是瓶颈）；
 * - 写入均依赖 DB 条件更新（CAS）与唯一索引兜底：差错表 (batch_no, req_no, diff_type) 唯一索引，
 *   即使分片策略变更或重跑也不会重复登记、重复记账；
 * - @Transactional 不跨线程传播，故每个子线程内的记账各自独立事务（单笔借贷仍是原子的）；
 * - 交易日运行锁 t_recon_lock 保证同一交易日只有一个批次在跑（多实例/重复点击同样拦住）；
 * - 单个分片异常或超时不影响其他分片，批次汇总为 PARTIAL_FAILED 并保留未完成清单，失败可控；
 * - 并行度为 1 时自动降级为串行，结果与并行完全一致，便于回归比对
 */
@Slf4j
@Service
public class ReconciliationService {

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 演示造数上限，防止误传大数把内存库填爆 */
    private static final int MAX_DEMO_ROWS = 20000;

    private final ReconRepository reconRepository;
    private final TransferOrderRepository orderRepository;
    private final AccountRepository accountRepository;
    private final BookingService bookingService;
    private final TransferService transferService;
    private final FlowNoGenerator flowNoGenerator;
    private final ThreadPoolTaskExecutor reconExecutor;

    @Value("${bank.recon.auto-fix-amount-diff:true}")
    private boolean autoFixAmountDiff;

    /** 并行度，1 为串行 */
    @Value("${bank.recon.parallelism:4}")
    private int parallelism;

    /** 单个分片最大流水数，决定实际分片个数 */
    @Value("${bank.recon.shard-size:500}")
    private int shardSize;

    /** 等待全部分片完成的最大秒数 */
    @Value("${bank.recon.await-timeout-seconds:120}")
    private int awaitTimeoutSeconds;

    /** 运行锁陈旧阈值（分钟），超过则可抢占，防止实例崩溃后永不能对账 */
    @Value("${bank.recon.lock-stale-minutes:30}")
    private int lockStaleMinutes;

    /**
     * 是否并行处理差错调账。默认 false：
     * 一笔调账同时锁定借贷两个账户，并行时极易产行锁竞争甚至死锁，
     * 而差错笔数通常只占总流水的千分之几，串行调账并不是瓶颈。
     * 仅当生产环境已按账户分库路由（同一账户必定落同一线程/同一库）时才建议开启
     */
    @Value("${bank.recon.parallel-booking:false}")
    private boolean parallelBooking;

    public ReconciliationService(ReconRepository reconRepository,
                                TransferOrderRepository orderRepository,
                                AccountRepository accountRepository,
                                BookingService bookingService,
                                TransferService transferService,
                                FlowNoGenerator flowNoGenerator,
                                @Qualifier(ReconExecutorConfig.RECON_EXECUTOR) ThreadPoolTaskExecutor reconExecutor) {
        this.reconRepository = reconRepository;
        this.orderRepository = orderRepository;
        this.accountRepository = accountRepository;
        this.bookingService = bookingService;
        this.transferService = transferService;
        this.flowNoGenerator = flowNoGenerator;
        this.reconExecutor = reconExecutor;
    }

    /**
     * 执行指定交易日对账（默认按配置并行度多线程执行）
     *
     * @param tradeDate yyyyMMdd
     */
    public ReconSummary reconcile(String tradeDate) {
        return reconcile(tradeDate, parallelism > 1);
    }

    /**
     * @param tradeDate 交易日 yyyyMMdd
     * @param parallel  true 多线程分片对账；false 串行对账。两者结果完全一致，
     *                  保留串行入口用于单元测试与数据量很小时避免线程开销
     */
    public ReconSummary reconcile(String tradeDate, boolean parallel) {
        String batchNo = flowNoGenerator.nextBatchNo(tradeDate);
        // 运行锁：同一交易日多线程/多实例下只允许一个批次在跑，从根本上排除并发调账
        if (!reconRepository.tryLock(tradeDate, batchNo, lockStaleMinutes)) {
            Map<String, Object> lock = reconRepository.findLock(tradeDate);
            throw new BizException(ErrorEnum.SYSTEM_BUSY, String.format("交易日%s已有对账批次执行中(批次%s)，请稍后重试",
                    tradeDate, lock == null ? "未知" : RowMapUtil.getString(lock, "batch_no")));
        }

        long begin = System.currentTimeMillis();
        try {
            // 步骤一：取数，形成只读快照（主线程一次查完，子线程不重复查库）
            ReconContext context = loadContext(tradeDate, batchNo);
            ReconSummary summary = initSummary(context);

            // 步骤二：按 reqNo 哈希分桶，分片间无交集
            int shardCount = resolveShardCount(context.getAllReqNos().size(), parallel);
            List<List<String>> shards = splitShards(context.getAllReqNos(), shardCount);
            summary.setParallelism(parallel ? Math.min(Math.max(1, parallelism), shardCount) : 1);
            summary.setShardCount(shards.size());
            log.info("[对账] 批次{} 开始, 交易日={}, 待比对{}笔, 并行度={}, 分片={}",
                    batchNo, tradeDate, context.getAllReqNos().size(), summary.getParallelism(), shards.size());

            // 先落批次表(RUNNING)，使子线程登记差错时能关联到批次，并让 existsHandledDiff 可跨批次判断
            summary.setStatus("RUNNING");
            reconRepository.saveBatch(summary);

            long deadline = System.currentTimeMillis() + awaitTimeoutSeconds * 1000L;

            // 阶段一：比对。纯读 + 纯计算，是日终对账的耗时大头，按 reqNo 分片并行处理
            long compareBegin = System.currentTimeMillis();
            List<ReconDiff> allDiffs = new ArrayList<>();
            for (ReconShardResult result : executeShards(shards, context, parallel, deadline)) {
                accumulateCompare(summary, result);
                allDiffs.addAll(result.getDiffs());
            }
            summary.setCompareCostMillis(System.currentTimeMillis() - compareBegin);

            // 阶段二：差错登记与调账（写账务）。默认串行，parallel-booking=true 时按账户分组并行
            // 调账单独给一个超时窗口，否则比对跑满超时后调账会因零预算被全部列为未完成
            long bookingBegin = System.currentTimeMillis();
            executeBooking(allDiffs, context, summary,
                    bookingBegin + awaitTimeoutSeconds * 1000L);
            summary.setBookingCostMillis(System.currentTimeMillis() - bookingBegin);

            finishSummary(summary, begin);
            return summary;
        } finally {
            // 无论成功、超时还是异常，必须释放运行锁，否则后续批次永远无法对账
            reconRepository.unlock(tradeDate, batchNo);
        }
    }

    /**
     * 步骤一：取数并构建快照。本地流水 + 渠道流水，生产中此处还需下载/解析/入库对账文件
     */
    private ReconContext loadContext(String tradeDate, String batchNo) {
        List<TransferOrder> localOrders = orderRepository.listByTimeRange(dayStart(tradeDate), dayEnd(tradeDate));
        List<Map<String, Object>> channelFlows = reconRepository.listChannelFlows(tradeDate);

        Map<String, TransferOrder> localMap = new LinkedHashMap<>();
        long localAmountTotal = 0L;
        for (TransferOrder order : localOrders) {
            localMap.put(order.getReqNo(), order);
            if (TransferOrder.SUCCESS.equals(order.getStatus())) {
                localAmountTotal += order.getAmount();
            }
        }

        Map<String, Map<String, Object>> channelMap = new LinkedHashMap<>();
        long channelAmountTotal = 0L;
        for (Map<String, Object> flow : channelFlows) {
            channelMap.put(RowMapUtil.getString(flow, "req_no"), flow);
            if ("SUCCESS".equals(RowMapUtil.getString(flow, "status"))) {
                channelAmountTotal += RowMapUtil.getLong(flow, "amount");
            }
        }

        Set<String> allReqNos = new LinkedHashSet<>();
        allReqNos.addAll(localMap.keySet());
        allReqNos.addAll(channelMap.keySet());

        return new ReconContext(batchNo, tradeDate,
                Collections.unmodifiableMap(localMap),
                Collections.unmodifiableMap(channelMap),
                Collections.unmodifiableList(new ArrayList<>(allReqNos)),
                localAmountTotal, channelAmountTotal);
    }

    private ReconSummary initSummary(ReconContext context) {
        ReconSummary summary = new ReconSummary();
        summary.setBatchNo(context.getBatchNo());
        summary.setTradeDate(context.getTradeDate());
        summary.setTotalLocal(context.getLocalMap().size());
        summary.setTotalChannel(context.getChannelMap().size());
        summary.setLocalAmountTotal(context.getLocalAmountTotal());
        summary.setChannelAmountTotal(context.getChannelAmountTotal());
        return summary;
    }

    /**
     * 分片数：取「并行度」与「流水数/分片大小」中的较小值，避免少量数据创建无用线程
     */
    private int resolveShardCount(int total, boolean parallel) {
        if (!parallel || total <= 1) {
            return 1;
        }
        int bySize = (int) Math.ceil(total / (double) Math.max(1, shardSize));
        return Math.max(1, Math.min(Math.max(1, parallelism), Math.min(bySize, total)));
    }

    /**
     * 哈希分桶：同一 reqNo 恒定落入同一分片，因此不会两个线程同时处理同一笔业务
     */
    private List<List<String>> splitShards(List<String> allReqNos, int shardCount) {
        List<List<String>> shards = new ArrayList<>(shardCount);
        for (int i = 0; i < shardCount; i++) {
            shards.add(new ArrayList<>());
        }
        for (String reqNo : allReqNos) {
            shards.get(Math.floorMod(reqNo.hashCode(), shardCount)).add(reqNo);
        }
        return shards;
    }

    /**
     * 并行执行各分片，带总体超时保护；单分片超时/异常不影响其他分片结果
     */
    private List<ReconShardResult> executeShards(List<List<String>> shards, ReconContext context,
                                                 boolean parallel, long deadline) {
        List<ReconShardResult> results = new ArrayList<>(shards.size());
        if (!parallel || shards.size() <= 1) {
            for (int i = 0; i < shards.size(); i++) {
                results.add(processShard(i, shards.get(i), context));
            }
            return results;
        }

        List<Future<ReconShardResult>> futures = new ArrayList<>(shards.size());
        for (int i = 0; i < shards.size(); i++) {
            final int shardNo = i;
            final List<String> reqNos = shards.get(i);
            // 无返回值依赖，用 submit + Future 聚合，而不是 parallelStream：
            // parallelStream 共用全局 ForkJoinPool，跑批长时间占用会影响其他业务，且无法定制超时与拒绝策略
            futures.add(reconExecutor.submit(() -> processShard(shardNo, reqNos, context)));
        }

        for (int i = 0; i < futures.size(); i++) {
            Future<ReconShardResult> future = futures.get(i);
            ReconShardResult result = new ReconShardResult();
            result.setShardNo(i);
            result.setAssignedCount(shards.get(i).size());
            try {
                long remainMillis = Math.max(1L, deadline - System.currentTimeMillis());
                results.add(future.get(remainMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                result.setCancelled(true);
                result.getFailedReqNos().addAll(shards.get(i));
                results.add(result);
                log.error("[对账] 分片{} 执行超过{}秒已中断, 本分片{}笔转入未完成清单",
                        i, awaitTimeoutSeconds, shards.get(i).size());
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                result.setCancelled(true);
                results.add(result);
                // 比对阶段只读不写，未完分片计入未完成清单即可，不会造成账务不一致
                for (int j = i + 1; j < shards.size(); j++) {
                    ReconShardResult pending = new ReconShardResult();
                    pending.setShardNo(j);
                    pending.setAssignedCount(shards.get(j).size());
                    pending.setCancelled(true);
                    pending.getFailedReqNos().addAll(shards.get(j));
                    results.add(pending);
                }
                log.error("[对账] 等待分片{} 结果时被中断, 其余分片计入未完成清单", i, e);
                break;
            } catch (ExecutionException e) {
                // 分片整体抛异常：本分片流水全部列入待人工清单，否则会出现「既不算一致也不算差错」的静默丢数据
                future.cancel(true);
                result.setCancelled(true);
                result.getFailedReqNos().addAll(shards.get(i));
                results.add(result);
                log.error("[对账] 分片{} 执行异常, 本分片{}笔转入未完成清单", i, shards.get(i).size(),
                        e.getCause() == null ? e : e.getCause());
            }
        }
        return results;
    }

    /**
     * 单分片作业（阶段一）：只比对、只产出差错清单，不写库不调账。
     * 无副作用因此可安全并行；单笔异常不中断分片，失败仅影响本分片
     */
    private ReconShardResult processShard(int shardNo, List<String> reqNos, ReconContext context) {
        long start = System.currentTimeMillis();
        ReconShardResult result = new ReconShardResult();
        result.setShardNo(shardNo);
        result.setThreadName(Thread.currentThread().getName());
        result.setAssignedCount(reqNos.size());

        for (String reqNo : reqNos) {
            try {
                ReconDiff diff = compare(context.getBatchNo(), context.getTradeDate(), reqNo,
                        context.getLocalMap().get(reqNo), context.getChannelMap().get(reqNo));
                if (diff == null) {
                    result.setMatchedCount(result.getMatchedCount() + 1);
                    continue;
                }
                result.setDiffCount(result.getDiffCount() + 1);
                result.getDiffs().add(diff);
            } catch (RuntimeException e) {
                // 单笔异常不中断分片，转入人工清单，保证其余流水照常对完
                log.error("[对账] 比对分片{} 处理流水异常, reqNo={}", shardNo, reqNo, e);
                result.getFailedReqNos().add(reqNo);
            }
        }

        result.setCostMillis(System.currentTimeMillis() - start);
        log.info("[对账] 比对分片{} 完成, 线程={}, 比对{}笔, 一致{}笔, 发现差错{}笔, 异常{}笔, 耗时{}ms",
                shardNo, result.getThreadName(), result.getAssignedCount(), result.getMatchedCount(),
                result.getDiffCount(), result.getFailedReqNos().size(), result.getCostMillis());
        return result;
    }

    /**
     * 差错登记与处理。多线程下的并发安全由两道机制保证：
     * 1) 前次批次已调账的差错不再重复处理（对账可重跑，调账不可重跑）；
     * 2) (batch_no, req_no, diff_type) 唯一索引插入冲突代表同一差错已由其他线程登记，本线程直接跳过调账
     */
    private void registerAndHandle(ReconDiff diff, ReconContext context) {
        String tradeDate = context.getTradeDate();
        String batchNo = context.getBatchNo();
        if (reconRepository.existsHandledDiff(tradeDate, batchNo, diff.getReqNo(), diff.getDiffType())) {
            diff.setHandleStatus(ReconDiff.IGNORED);
            diff.setHandleRemark("前次批次已处理该差错，本批次仅登记不重复调账（对账幂等）");
            reconRepository.saveDiff(diff);
            return;
        }
        diff.setHandleStatus(ReconDiff.PENDING);
        if (!reconRepository.saveDiff(diff)) {
            diff.setHandleStatus(ReconDiff.IGNORED);
            diff.setHandleRemark("同一差错已由其他分片登记，本线程不重复调账");
            return;
        }
        handleDiff(diff, context);
    }

    /**
     * 汇总比对阶段各分片结果。统计累加全部放在主线程，因此无需并发容器
     */
    private void accumulateCompare(ReconSummary summary, ReconShardResult result) {
        summary.setMatchedCount(summary.getMatchedCount() + result.getMatchedCount());
        summary.setDiffCount(summary.getDiffCount() + result.getDiffCount());
        summary.getFailedReqNos().addAll(result.getFailedReqNos());
        summary.getShardCostMillis().add(result.getCostMillis());
        if (result.isCancelled()) {
            summary.getIncompleteShards().add(result.getShardNo());
        }
    }

    /**
     * 阶段二：差错登记与调账（写账务）。默认单线程串行；
     * 开启并行时先按账户做并查集聚合，只有账户互不相交的差错才分到不同组并行处理
     */
    private void executeBooking(List<ReconDiff> allDiffs, ReconContext context, ReconSummary summary, long deadline) {
        if (allDiffs.isEmpty()) {
            return;
        }
        if (!parallelBooking || allDiffs.size() <= 1) {
            summary.setBookingGroups(1);
            accumulateBooking(summary, handleBookingGroup(0, allDiffs, context));
            summary.getDiffs().addAll(allDiffs);
            return;
        }

        List<List<ReconDiff>> groups = groupByAccount(allDiffs, context);
        summary.setBookingGroups(groups.size());
        log.info("[对账] 差错调账并行执行, 差错{}笔按账户聚合为{}组", allDiffs.size(), groups.size());

        List<Future<ReconShardResult>> futures = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            final int groupNo = i;
            final List<ReconDiff> group = groups.get(i);
            futures.add(reconExecutor.submit(() -> handleBookingGroup(groupNo, group, context)));
        }
        for (int i = 0; i < futures.size(); i++) {
            Future<ReconShardResult> future = futures.get(i);
            try {
                long remainMillis = Math.max(1L, deadline - System.currentTimeMillis());
                accumulateBooking(summary, future.get(remainMillis, TimeUnit.MILLISECONDS));
            } catch (TimeoutException e) {
                future.cancel(true);
                summary.getIncompleteBookingGroups().add(i);
                addAllReqNos(summary.getFailedReqNos(), groups.get(i));
                log.error("[对账] 调账分组{} 超过总超时未完成, 本组{}笔差错列入待人工清单", i, groups.get(i).size());
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                // 剩余分组一律记为未完成：其线程可能仍在跑，批次结果必须提示人工复核而不能当作已完成
                for (int j = i; j < groups.size(); j++) {
                    summary.getIncompleteBookingGroups().add(j);
                    addAllReqNos(summary.getFailedReqNos(), groups.get(j));
                }
                log.error("[对账] 等待调账分组结果时被中断, 分组{}及之后仍在后台执行, 必须人工复核账务", i, e);
                break;
            } catch (ExecutionException e) {
                summary.getIncompleteBookingGroups().add(i);
                addAllReqNos(summary.getFailedReqNos(), groups.get(i));
                log.error("[对账] 调账分组{} 执行异常", i, e.getCause() == null ? e : e.getCause());
            }
        }
        summary.getDiffs().addAll(allDiffs);
    }

    /**
     * 调账分组作业（阶段二）：登记差错 + 按策略自动调账，逐笔独立事务，单笔失败不拖垮整组
     */
    private ReconShardResult handleBookingGroup(int groupNo, List<ReconDiff> group, ReconContext context) {
        long start = System.currentTimeMillis();
        ReconShardResult result = new ReconShardResult();
        result.setShardNo(groupNo);
        result.setThreadName(Thread.currentThread().getName());
        result.setAssignedCount(group.size());
        for (ReconDiff diff : group) {
            try {
                registerAndHandle(diff, context);
            } catch (RuntimeException e) {
                log.error("[对账] 调账分组{} 处理差错异常, reqNo={}", groupNo, diff.getReqNo(), e);
                result.getFailedReqNos().add(diff.getReqNo());
            }
            // IGNORED（前次已处理/其他线程已登记）既非调账也非人工，不计入两个统计
            if (ReconDiff.FIXED.equals(diff.getHandleStatus())) {
                result.setAutoFixedCount(result.getAutoFixedCount() + 1);
            } else if (ReconDiff.MANUAL.equals(diff.getHandleStatus())) {
                result.setManualCount(result.getManualCount() + 1);
            }
        }
        result.setCostMillis(System.currentTimeMillis() - start);
        log.info("[对账] 调账分组{} 完成, 线程={}, 差错{}笔, 自动调账{}笔, 转人工{}笔, 耗时{}ms", groupNo,
                result.getThreadName(), group.size(), result.getAutoFixedCount(), result.getManualCount(),
                result.getCostMillis());
        return result;
    }

    private void accumulateBooking(ReconSummary summary, ReconShardResult result) {
        summary.setAutoFixedCount(summary.getAutoFixedCount() + result.getAutoFixedCount());
        summary.setManualCount(summary.getManualCount() + result.getManualCount());
        summary.getFailedReqNos().addAll(result.getFailedReqNos());
    }

    /**
     * 并查集按账户聚合差错：只要两笔差错涉及同一账户就并入一组，组间账户完全不相交，
     * 因此并行调账不会争抢同一行锁，也不会出现交叉持锁死锁
     */
    private List<List<ReconDiff>> groupByAccount(List<ReconDiff> allDiffs, ReconContext context) {
        int size = allDiffs.size();
        int[] parent = new int[size];
        for (int i = 0; i < size; i++) {
            parent[i] = i;
        }
        Map<String, Integer> firstTouch = new HashMap<>();
        for (int i = 0; i < size; i++) {
            for (String acct : bookingAccounts(allDiffs.get(i), context)) {
                Integer prev = firstTouch.putIfAbsent(acct, i);
                if (prev != null) {
                    parent[find(parent, i)] = find(parent, prev);
                }
            }
        }
        Map<Integer, List<ReconDiff>> grouped = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            grouped.computeIfAbsent(find(parent, i), key -> new ArrayList<>()).add(allDiffs.get(i));
        }
        return new ArrayList<>(grouped.values());
    }

    /**
     * 本笔差错调账可能触碰的账户。长款（本地无流水）等不触碰账户的差错用 reqNo 占位，
     * 表示与其他差错无账户冲突，可独立并行
     */
    private List<String> bookingAccounts(ReconDiff diff, ReconContext context) {
        TransferOrder local = context.getLocalMap().get(diff.getReqNo());
        if (local == null) {
            return Collections.singletonList("REQ:" + diff.getReqNo());
        }
        return Arrays.asList(local.getFromAcct(), local.getToAcct());
    }

    /**
     * 并查集查找（带路径折半压缩）
     */
    private int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private void addAllReqNos(List<String> target, List<ReconDiff> diffs) {
        for (ReconDiff diff : diffs) {
            target.add(diff.getReqNo());
        }
    }

    /**
     * 确定批次最终状态并回写统计
     */
    private void finishSummary(ReconSummary summary, long begin) {
        boolean incomplete = !summary.getIncompleteShards().isEmpty() || !summary.getIncompleteBookingGroups().isEmpty()
                || !summary.getFailedReqNos().isEmpty();
        if (incomplete) {
            summary.setStatus("PARTIAL_FAILED");
        } else if (summary.getDiffCount() == 0) {
            summary.setStatus("BALANCED");
        } else if (summary.getManualCount() > 0) {
            summary.setStatus("PENDING_MANUAL");
        } else {
            summary.setStatus("AUTO_PROCESSED");
        }
        summary.setCostMillis(System.currentTimeMillis() - begin);
        reconRepository.updateBatchResult(summary);
        log.info("[对账] 批次{} 完成: 本地{}笔/渠道{}笔, 一致{}笔, 差错{}笔(自动调账{}笔/转人工{}笔), 轧差{}分, "
                        + "比对{}ms/调账{}ms, 并行度{}, 分片{}, 结果{}, 总耗时{}ms",
                summary.getBatchNo(), summary.getTotalLocal(), summary.getTotalChannel(), summary.getMatchedCount(),
                summary.getDiffCount(), summary.getAutoFixedCount(), summary.getManualCount(),
                summary.getNetDiffAmount(), summary.getCompareCostMillis(), summary.getBookingCostMillis(),
                summary.getParallelism(), summary.getShardCount(), summary.getStatus(), summary.getCostMillis());
    }

    /**
     * 单笔比对：一致返回 null，否则返回差错对象
     */
    private ReconDiff compare(String batchNo, String tradeDate, String reqNo,
                              TransferOrder local, Map<String, Object> channel) {
        ReconDiff diff = new ReconDiff();
        diff.setBatchNo(batchNo);
        diff.setReqNo(reqNo);

        // 单日多次对账需区分批次，日志带交易日便于定位
        log.debug("[对账] 比对 reqNo={}, tradeDate={}", reqNo, tradeDate);

        if (local == null) {
            // 渠道有、本地无 → 长款
            diff.setDiffType(ReconDiff.CHANNEL_ONLY);
            diff.setChannelAmount(RowMapUtil.getLong(channel, "amount"));
            diff.setChannelStatus(RowMapUtil.getString(channel, "status"));
            diff.setLocalAmount(0L);
            diff.setLocalStatus(null);
            return diff;
        }
        if (channel == null) {
            // 本地有、渠道无 → 短款（本地记了渠道没有，通常是对账文件缺漏或本地误记）
            diff.setDiffType(ReconDiff.LOCAL_ONLY);
            diff.setLocalAmount(local.getAmount());
            diff.setLocalStatus(local.getStatus());
            diff.setChannelAmount(0L);
            return diff;
        }

        long localAmount = local.getAmount();
        long channelAmount = RowMapUtil.getLong(channel, "amount");
        String channelStatus = RowMapUtil.getString(channel, "status");
        diff.setLocalAmount(localAmount);
        diff.setChannelAmount(channelAmount);
        diff.setLocalStatus(local.getStatus());
        diff.setChannelStatus(channelStatus);

        if (localAmount != channelAmount) {
            diff.setDiffType(ReconDiff.AMOUNT_DIFF);
            return diff;
        }
        boolean localSuccess = TransferOrder.SUCCESS.equals(local.getStatus());
        boolean channelSuccess = "SUCCESS".equals(channelStatus);
        if (localSuccess != channelSuccess) {
            diff.setDiffType(ReconDiff.STATUS_DIFF);
            return diff;
        }
        // 金额、状态一致（含双方均失败的匹配失败）视为平账
        return null;
    }

    /**
     * 差错处理策略：以渠道为准的自动调账 + 必须人工核实的情形
     */
    private void handleDiff(ReconDiff diff, ReconContext context) {
        String reqNo = diff.getReqNo();
        try {
            switch (diff.getDiffType()) {
                case ReconDiff.AMOUNT_DIFF:
                    handleAmountDiff(diff, context);
                    break;
                case ReconDiff.STATUS_DIFF:
                    handleStatusDiff(diff);
                    break;
                case ReconDiff.CHANNEL_ONLY:
                    // 长款：渠道成功本地无流水，先挂账等待渠道确认后退汇，禁止直接入账（防止重复付款）
                    finish(diff, ReconDiff.MANUAL, "长款挂账待人工确认，核对渠道报文后走退汇或补账");
                    break;
                case ReconDiff.LOCAL_ONLY:
                    // 短款：本地成功渠道无，多为文件缺漏，以本地为准并提请渠道重传文件
                    finish(diff, ReconDiff.MANUAL, "短款待渠道重传对账文件，本地流水保留");
                    break;
                default:
                    finish(diff, ReconDiff.MANUAL, "未识别差错类型，转人工");
            }
        } catch (BizException e) {
            finish(diff, ReconDiff.MANUAL, "自动处理失败转人工: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("[对账] 差错自动处理异常, batchNo={}, reqNo={}", diff.getBatchNo(), reqNo, e);
            finish(diff, ReconDiff.MANUAL, "自动处理异常转人工: " + e.getClass().getSimpleName());
        }
    }

    /**
     * 金额不符：以渠道金额为准，补记差额。
     * 差额调整必须走「双边分录」，否则本地账会不平：
     * 渠道 > 本地 → 本地少记，按差额再做一笔转账；渠道 < 本地 → 本地多记，反向转账回补
     */
    private void handleAmountDiff(ReconDiff diff, ReconContext context) {
        if (!autoFixAmountDiff) {
            finish(diff, ReconDiff.MANUAL, "金额不符，自动调账已关闭，转人工");
            return;
        }
        // 优先用主线程取数快照，避免调账阶段逐笔回查 DB（并行时这会放大为 N 倍查询开销）
        TransferOrder local = context.getLocalMap().get(diff.getReqNo());
        if (local == null) {
            finish(diff, ReconDiff.MANUAL, "本地流水缺失，无法自动调账");
            return;
        }
        long delta = diff.getDiffAmount();
        if (delta > 0) {
            bookingService.bookTransfer(local.getFromAcct(), local.getToAcct(), delta);
        } else {
            bookingService.bookTransfer(local.getToAcct(), local.getFromAcct(), -delta);
        }
        finish(diff, ReconDiff.FIXED, String.format("以渠道为准补记差额%d分，%s→%s",
                Math.abs(delta), delta > 0 ? local.getFromAcct() : local.getToAcct(),
                delta > 0 ? local.getToAcct() : local.getFromAcct()));
    }

    /**
     * 状态不符：本地悬挂但渠道成功 → 补账；本地成功但渠道失败 → 冲正；其余转人工
     */
    private void handleStatusDiff(ReconDiff diff) {
        String localStatus = diff.getLocalStatus();
        String channelStatus = diff.getChannelStatus();
        if (TransferOrder.PROCESSING.equals(localStatus) && "SUCCESS".equals(channelStatus)) {
            transferService.resolveHanging(diff.getReqNo(), TransferOrder.SUCCESS);
            finish(diff, ReconDiff.FIXED, "本地悬挂而渠道成功，已补账入账并推进终态");
            return;
        }
        if (TransferOrder.SUCCESS.equals(localStatus) && "FAILED".equals(channelStatus)) {
            transferService.reverse(diff.getReqNo(), "RECON-AUTO");
            finish(diff, ReconDiff.FIXED, "本地成功而渠道失败，已自动冲正回补");
            return;
        }
        if (TransferOrder.FAILED.equals(localStatus) && "SUCCESS".equals(channelStatus)) {
            finish(diff, ReconDiff.MANUAL, "渠道成功但本地失败，需人工确认后重新发起或调账");
            return;
        }
        finish(diff, ReconDiff.MANUAL, String.format("状态组合(%s/%s)需人工核实", localStatus, channelStatus));
    }

    private void finish(ReconDiff diff, String handleStatus, String remark) {
        diff.setHandleStatus(handleStatus);
        diff.setHandleRemark(remark);
        reconRepository.markDiffHandled(diff.getId(), handleStatus, remark);
        log.info("[对账] 差错处理 batchNo={}, reqNo={}, 类型={}, 结果={}",
                diff.getBatchNo(), diff.getReqNo(), diff.getDiffTypeName(), handleStatus);
    }

    /**
     * 人工差错处理入口（长款退汇、短款核销等），带处理人留痕
     */
    public ReconDiff handleManually(Long diffId, String result, String remark, String operator) {
        ReconDiff diff = reconRepository.findDiff(diffId);
        if (diff == null) {
            throw new BizException(ErrorEnum.ORDER_NOT_EXIST, "差错记录不存在: " + diffId);
        }
        if (!ReconDiff.PENDING.equals(diff.getHandleStatus())) {
            throw new BizException(ErrorEnum.ORDER_STATUS_ILLEGAL, "该差错已处理: " + diff.getHandleStatus());
        }
        String handleStatus = ReconDiff.IGNORED.equals(result) ? ReconDiff.IGNORED : ReconDiff.FIXED;
        if (!reconRepository.markDiffHandled(diffId, handleStatus,
                String.format("[人工]%s 处理人:%s", remark == null ? "" : remark, operator))) {
            throw new BizException(ErrorEnum.SYSTEM_BUSY, "并发处理，请刷新后重试");
        }
        diff.setHandleStatus(handleStatus);
        diff.setHandleRemark(String.format("[人工]%s 处理人:%s", remark, operator));
        return diff;
    }

    public List<ReconDiff> listDiffs(String batchNo) {
        return reconRepository.listDiffs(batchNo);
    }

    /**
     * 读取指定交易日最近一次对账结果（不对账、不调账，纯查询）
     */
    public ReconSummary loadLatest(String tradeDate) {
        Map<String, Object> batch = reconRepository.latestBatch(tradeDate);
        if (batch == null) {
            throw new BizException(ErrorEnum.ORDER_NOT_EXIST, "交易日" + tradeDate + "尚未产生对账批次，请先执行对账");
        }
        ReconSummary summary = new ReconSummary();
        summary.setBatchNo(RowMapUtil.getString(batch, "batch_no"));
        summary.setTradeDate(tradeDate);
        summary.setTotalLocal(RowMapUtil.getInt(batch, "total_local"));
        summary.setTotalChannel(RowMapUtil.getInt(batch, "total_channel"));
        summary.setMatchedCount(RowMapUtil.getInt(batch, "success_count"));
        summary.setDiffCount(RowMapUtil.getInt(batch, "diff_count"));
        summary.setStatus(RowMapUtil.getString(batch, "status"));
        summary.setLocalAmountTotal(RowMapUtil.getLong(batch, "local_amount"));
        summary.setChannelAmountTotal(RowMapUtil.getLong(batch, "channel_amount"));
        summary.setParallelism(RowMapUtil.getInt(batch, "parallelism"));
        summary.setShardCount(RowMapUtil.getInt(batch, "shard_count"));
        summary.setCostMillis(RowMapUtil.getLong(batch, "cost_millis"));
        List<ReconDiff> diffs = reconRepository.listDiffs(summary.getBatchNo());
        for (ReconDiff diff : diffs) {
            if (ReconDiff.FIXED.equals(diff.getHandleStatus())) {
                summary.setAutoFixedCount(summary.getAutoFixedCount() + 1);
            } else if (ReconDiff.MANUAL.equals(diff.getHandleStatus())) {
                summary.setManualCount(summary.getManualCount() + 1);
            }
        }
        summary.getDiffs().addAll(diffs);
        return summary;
    }

    /**
     * 线程池运行状态，用于观察并行效果（活跃线程/队列积压/已完成任务）
     */
    public Map<String, Object> executorStats() {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("parallelismConfig", parallelism);
        stats.put("shardSize", shardSize);
        stats.put("awaitTimeoutSeconds", awaitTimeoutSeconds);
        stats.put("corePoolSize", reconExecutor.getCorePoolSize());
        stats.put("activeCount", reconExecutor.getActiveCount());
        stats.put("poolSize", reconExecutor.getPoolSize());
        stats.put("queueSize", reconExecutor.getThreadPoolExecutor().getQueue().size());
        stats.put("completedTaskCount", reconExecutor.getThreadPoolExecutor().getCompletedTaskCount());
        return stats;
    }

    /**
     * 当前交易日是否在对账中
     */
    public Map<String, Object> runningLock(String tradeDate) {
        return reconRepository.findLock(tradeDate);
    }

    /**
     * 强制释放运行锁（仅演示/运维故障处理）
     */
    public int forceReleaseLock(String tradeDate) {
        return reconRepository.forceReleaseLock(tradeDate);
    }

    public List<Map<String, Object>> listBatches(int limit) {
        return reconRepository.listBatches(limit);
    }

    /**
     * 模拟收到渠道对账文件
     */
    public boolean importChannelFlow(String channelSerial, String reqNo, Long amount, String tradeDate, String status) {
        return reconRepository.saveChannelFlow(channelSerial, reqNo, amount == null ? 0L : amount, tradeDate,
                status == null ? "SUCCESS" : status);
    }

    /**
     * 造批量对账演示数据（只批量插流水，造数本身不动余额），专用于观察多线程与串行的耗时差异。
     * 注意：造完后执行对账，金额不符与悬挂差错会触发真实调账（改变账户余额），
     * 可用 GET /bank/account/list 对比调账前后余额
     *
     * <p>差错分布固定：每13笔一笔长款、每17笔一笔短款、每7笔一笔本地悬挂、
     * 每 diffEvery 笔一笔金额不符，其余完全平账
     *
     * @param count     生成笔数（上限 {@value #MAX_DEMO_ROWS}）
     * @param diffEvery 金额不符间隔
     */
    public Map<String, Object> prepareDemoData(String tradeDate, int count, int diffEvery) {
        int total = Math.min(Math.max(1, count), MAX_DEMO_ROWS);
        // 随机后缀避开主键冲突：同一交易日可反复造数对比多线程耗时
        String tag = String.format("%03d", ThreadLocalRandom.current().nextInt(1000));
        Timestamp createTime = Timestamp.valueOf(LocalDate.parse(tradeDate, DATE_FORMATTER).atTime(10, 30));
        String[] accts = {"6222000000000001", "6222000000000002", "6222000000000003"};
        List<ReconRepository.TransferOrderSeed> localSeeds = new ArrayList<>(total);
        List<ReconRepository.ChannelFlowSeed> channelSeeds = new ArrayList<>(total);
        int hanging = 0;
        int amountDiff = 0;
        int channelOnly = 0;
        int localOnly = 0;

        for (int i = 0; i < total; i++) {
            String reqNo = "MOCK-" + tag + "-" + tradeDate + "-" + String.format("%07d", i);
            long amount = 10000L + (i % 5) * 137L;
            String fromAcct = accts[i % 3];
            String toAcct = accts[(i + 1) % 3];
            boolean skipLocal = i % 13 == 0;
            boolean skipChannel = i % 17 == 0;
            boolean isHanging = i % 7 == 0 && !skipLocal;
            boolean isAmountDiff = i % Math.max(2, diffEvery) == 0 && !skipChannel && !skipLocal;

            if (!skipLocal) {
                ReconRepository.TransferOrderSeed seed = new ReconRepository.TransferOrderSeed();
                seed.orderNo = "TRF" + tag + tradeDate + String.format("%07d", i);
                seed.reqNo = reqNo;
                seed.fromAcct = fromAcct;
                seed.toAcct = toAcct;
                seed.amount = amount;
                seed.status = isHanging ? TransferOrder.PROCESSING : TransferOrder.SUCCESS;
                seed.createTime = createTime;
                localSeeds.add(seed);
            } else {
                channelOnly++;
            }
            if (!skipChannel) {
                ReconRepository.ChannelFlowSeed seed = new ReconRepository.ChannelFlowSeed();
                seed.channelSerial = "SER" + tag + tradeDate + String.format("%07d", i);
                seed.reqNo = reqNo;
                seed.amount = isAmountDiff ? amount + 500L : amount;
                seed.tradeDate = tradeDate;
                seed.status = "SUCCESS";
                channelSeeds.add(seed);
            } else {
                localOnly++;
            }
            if (isHanging) {
                hanging++;
            }
            if (isAmountDiff) {
                amountDiff++;
            }
        }

        reconRepository.batchInsertLocalOrders(localSeeds);
        reconRepository.batchInsertChannelFlows(channelSeeds);

        // 悬挂流水必须「已实际扣款」：否则对账补账时只有入账没有扣款，会凭空搅乱余额
        int hungDebits = 0;
        for (ReconRepository.TransferOrderSeed seed : localSeeds) {
            if (TransferOrder.PROCESSING.equals(seed.status) && accountRepository.debit(seed.fromAcct, seed.amount)) {
                hungDebits++;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tradeDate", tradeDate);
        result.put("流水号后缀", tag);
        result.put("localInserted", localSeeds.size());
        result.put("channelInserted", channelSeeds.size());
        result.put("悬挂已实际扣款", hungDebits);
        result.put("预计长款", channelOnly);
        result.put("预计短款", localOnly);
        result.put("预计悬挂(状态不符)", hanging);
        result.put("预计金额不符", amountDiff);
        log.info("[对账] 演示数据就绪, 交易日={}, 本地{}笔, 渠道{}笔", tradeDate, localSeeds.size(), channelSeeds.size());
        return result;
    }

    /**
     * 昨日交易日（日终对账默认对前一天轧账）
     */
    public String yesterday() {
        return LocalDate.now().minusDays(1).format(DATE_FORMATTER);
    }

    public String today() {
        return LocalDate.now().format(DATE_FORMATTER);
    }

    private Timestamp dayStart(String tradeDate) {
        return Timestamp.valueOf(LocalDate.parse(tradeDate, DATE_FORMATTER).atStartOfDay());
    }

    private Timestamp dayEnd(String tradeDate) {
        return Timestamp.valueOf(LocalDate.parse(tradeDate, DATE_FORMATTER).plusDays(1).atStartOfDay());
    }
}
