package com.example.gsb.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 公平性验证：高并发抢占场景下，各租户实际获得量不得低于其保障额度。
 */
class ConcurrencyFairnessTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void bigTenantCannotStarveSmallTenants() throws Exception {
        // 场景还原：大租户一上来抢光全部额度，小租户随后入场。
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("big", 20, 100);
        List<String> smalls = List.of("s1", "s2", "s3", "s4");
        for (String small : smalls) {
            allocator.registerTenant(small, 20, 40);
        }

        // 大租户抢光全部 100 额度（20 保障 + 80 借用）。
        assertThat(allocator.tryAcquire("big", 100)).isEqualTo(100);
        assertThat(allocator.stats().remaining()).isZero();

        // 4 个小租户并发申请各自的保障额度：必须全部足额获得。
        ExecutorService pool = Executors.newFixedThreadPool(smalls.size());
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Long>> futures = new ArrayList<>();
            for (String small : smalls) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return allocator.tryAcquire(small, 20);
                }));
            }
            start.countDown();
            for (Future<Long> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(20);
            }
        } finally {
            pool.shutdownNow();
        }

        // 大租户被逐步收回到只剩自己的保障额度，小租户全部拿到保障额度。
        assertThat(allocator.tenantStats("big").used()).isEqualTo(20);
        assertThat(allocator.tenantStats("big").totalReclaimed()).isEqualTo(80);
        for (String small : smalls) {
            assertThat(allocator.tenantStats(small).used()).isEqualTo(20);
        }
        assertThat(allocator.stats().remaining()).isZero();
    }

    @Test
    void guaranteesHoldUnderHighContentionAcquireReleaseLoops() throws Exception {
        // 保障额度之和等于全局额度：任何借用都必须可被收回，
        // 每个租户在任意时刻申请保障额度都必须足额获得。
        int tenants = 8;
        long guarantee = 20;
        long capacity = tenants * guarantee;
        int iterations = 200;

        FairQuotaAllocator allocator = new FairQuotaAllocator(capacity, Duration.ZERO, clock);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < tenants; i++) {
            String id = "t" + i;
            // 上限高于保障额度，制造持续的借用与收回竞争。
            allocator.registerTenant(id, guarantee, capacity);
            ids.add(id);
        }

        ExecutorService pool = Executors.newFixedThreadPool(tenants);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<Void>> futures = new ArrayList<>();
            for (String id : ids) {
                futures.add(pool.submit(new Callable<Void>() {
                    @Override
                    public Void call() {
                        ThreadLocalRandom random = ThreadLocalRandom.current();
                        for (int i = 0; i < iterations; i++) {
                            // 1) 保障额度：必须永远足额获得，否则公平性被破坏。
                            long granted = allocator.tryAcquire(id, guarantee);
                            assertThat(granted)
                                    .as("tenant %s must always get its guarantee", id)
                                    .isEqualTo(guarantee);
                            // 2) 尝试借用额外额度（可能成功也可能失败，取决于竞争）。
                            long borrowed = allocator.tryAcquire(id, guarantee);
                            // 3) 全部释放，进入下一轮。
                            allocator.release(id, guarantee + borrowed);
                            if (random.nextInt(10) == 0) {
                                Thread.yield();
                            }
                        }
                        return null;
                    }
                }));
            }
            start.countDown();
            for (Future<Void> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        // 全部释放后：全局剩余恢复满额，各租户已用归零。
        GlobalStats stats = allocator.stats();
        assertThat(stats.totalUsed()).isZero();
        assertThat(stats.remaining()).isEqualTo(capacity);
        for (String id : ids) {
            assertThat(allocator.tenantStats(id).used()).isZero();
        }
    }
}
