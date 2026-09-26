package com.example.gsb.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 借用与归还：借用方使用闲置额度，原租户需要时逐步收回。 */
class BorrowAndReclaimTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void borrowerUsesIdleQuotaAndOwnerReclaimsItStepByStep() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("big", 20, 100);
        allocator.registerTenant("small", 40, 60);

        // big 借满：20 保障 + 80 借用（其中 40 是 small 闲置的保障额度，40 是未分配余量）。
        assertThat(allocator.tryAcquire("big", 100)).isEqualTo(100);
        TenantStats bigAfterBorrow = allocator.tenantStats("big");
        assertThat(bigAfterBorrow.borrowed()).isEqualTo(80);
        assertThat(bigAfterBorrow.totalBorrowed()).isEqualTo(80);
        assertThat(allocator.stats().remaining()).isZero();

        // small 分两次要回自己的保障额度，借用额度被逐步收回。
        assertThat(allocator.tryAcquire("small", 10)).isEqualTo(10);
        TenantStats bigAfterStep1 = allocator.tenantStats("big");
        assertThat(bigAfterStep1.used()).isEqualTo(90);
        assertThat(bigAfterStep1.totalReclaimed()).isEqualTo(10);

        assertThat(allocator.tryAcquire("small", 30)).isEqualTo(30);
        TenantStats bigAfterStep2 = allocator.tenantStats("big");
        assertThat(bigAfterStep2.used()).isEqualTo(60);
        assertThat(bigAfterStep2.totalReclaimed()).isEqualTo(40);

        // small 拿回了全部 40 保障额度；big 仍保有 20 保障 + 40 借用（未分配余量部分）。
        TenantStats small = allocator.tenantStats("small");
        assertThat(small.used()).isEqualTo(40);
        assertThat(small.guaranteedUsed()).isEqualTo(40);
        assertThat(small.borrowed()).isZero();
        assertThat(allocator.stats().remaining()).isZero();
        assertThat(allocator.stats().totalUsed()).isEqualTo(100);
    }

    @Test
    void reclaimNeverTouchesGuaranteedUsage() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(50, Duration.ZERO, clock);
        allocator.registerTenant("a", 10, 50);
        allocator.registerTenant("b", 40, 50);

        // a 借满全局额度，b 随后要回全部保障额度。
        allocator.tryAcquire("a", 50);
        assertThat(allocator.tryAcquire("b", 40)).isEqualTo(40);

        TenantStats a = allocator.tenantStats("a");
        // a 被收回 40，但自己的 10 保障额度分毫不动。
        assertThat(a.used()).isEqualTo(10);
        assertThat(a.guaranteedUsed()).isEqualTo(10);
        assertThat(a.borrowed()).isZero();
        assertThat(a.totalReclaimed()).isEqualTo(40);
    }

    @Test
    void reclaimPrefersLargestBorrower() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("x", 10, 100);
        allocator.registerTenant("y", 10, 100);
        allocator.registerTenant("owner", 80, 80);

        // x 借 50，y 借 20（各自另有 10 保障）。
        allocator.tryAcquire("x", 60);
        allocator.tryAcquire("y", 30);
        assertThat(allocator.stats().remaining()).isEqualTo(10);

        // owner 要回 80 保障额度，缺口 70：先收 x 的 50，再收 y 的 20。
        assertThat(allocator.tryAcquire("owner", 80)).isEqualTo(80);
        assertThat(allocator.tenantStats("x").totalReclaimed()).isEqualTo(50);
        assertThat(allocator.tenantStats("y").totalReclaimed()).isEqualTo(20);
        assertThat(allocator.tenantStats("x").used()).isEqualTo(10);
        assertThat(allocator.tenantStats("y").used()).isEqualTo(10);
    }

    @Test
    void releasedBorrowedQuotaReturnsToSharedPool() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("a", 20, 100);
        allocator.registerTenant("b", 20, 100);

        allocator.tryAcquire("a", 80); // 20 保障 + 60 借用
        assertThat(allocator.stats().remaining()).isEqualTo(20);

        // a 释放 60（视为归还借用部分），b 可以借到。
        allocator.release("a", 60);
        assertThat(allocator.stats().remaining()).isEqualTo(80);
        assertThat(allocator.tryAcquire("b", 80)).isEqualTo(80);
        assertThat(allocator.tenantStats("b").borrowed()).isEqualTo(60);
    }
}
