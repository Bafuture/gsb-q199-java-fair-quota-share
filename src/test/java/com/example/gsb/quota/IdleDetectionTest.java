package com.example.gsb.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 闲置检测：超时未活动的租户，其未用保障额度进入共享池。 */
class IdleDetectionTest {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private final MutableClock clock = new MutableClock();

    @Test
    void tenantBecomesIdleAfterTimeout() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, IDLE_TIMEOUT, clock);
        allocator.registerTenant("a", 40, 100);
        allocator.registerTenant("b", 40, 100);

        assertThat(allocator.isIdle("a")).isFalse();
        assertThat(allocator.idleTenants()).isEmpty();

        clock.advance(IDLE_TIMEOUT.minusMillis(1));
        assertThat(allocator.isIdle("a")).isFalse();

        clock.advance(Duration.ofMillis(1));
        assertThat(allocator.isIdle("a")).isTrue();
        assertThat(allocator.idleTenants()).containsExactlyInAnyOrder("a", "b");

        // a 重新活动后不再闲置。
        allocator.tryAcquire("a", 1);
        assertThat(allocator.isIdle("a")).isFalse();
        assertThat(allocator.idleTenants()).containsExactly("b");
    }

    @Test
    void idleQuotaEntersSharedPoolAndCanBeBorrowed() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, IDLE_TIMEOUT, clock);
        allocator.registerTenant("a", 40, 100);
        allocator.registerTenant("b", 40, 100);

        // 双方都未闲置：b 借用时，a 的 40 未用保障额度被保留，b 只能借到未分配的 20。
        assertThat(allocator.tryAcquire("b", 100)).isEqualTo(60);
        assertThat(allocator.tenantStats("b").borrowed()).isEqualTo(20);
        assertThat(allocator.stats().sharedPoolAvailable()).isZero();

        // a 闲置后，其 40 未用保障额度进入共享池，b 可以继续借。
        clock.advance(IDLE_TIMEOUT);
        assertThat(allocator.stats().sharedPoolAvailable()).isEqualTo(40);
        assertThat(allocator.tryAcquire("b", 40)).isEqualTo(40);
        assertThat(allocator.tenantStats("b").used()).isEqualTo(100);
    }

    @Test
    void activeTenantKeepsItsReservedGuarantee() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, IDLE_TIMEOUT, clock);
        allocator.registerTenant("a", 40, 100);
        allocator.registerTenant("b", 40, 100);

        // a 保持活动（在超时前释放一次），b 闲置。
        clock.advance(IDLE_TIMEOUT.minusMillis(1));
        allocator.tryAcquire("a", 1);
        clock.advance(Duration.ofMillis(1));

        assertThat(allocator.isIdle("a")).isFalse();
        assertThat(allocator.isIdle("b")).isTrue();

        // 共享池 = 全局剩余 99 − a 保留的 39 = 60（含 b 闲置的 40）。
        GlobalStats stats = allocator.stats();
        assertThat(stats.remaining()).isEqualTo(99);
        assertThat(stats.sharedPoolAvailable()).isEqualTo(60);
    }
}
