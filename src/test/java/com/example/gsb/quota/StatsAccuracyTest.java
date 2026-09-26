package com.example.gsb.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 统计准确性：已用、保障、借用、收回与全局剩余的精确对账。 */
class StatsAccuracyTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void tenantAndGlobalStatsAreAccurateStepByStep() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(90, Duration.ZERO, clock);
        allocator.registerTenant("a", 30, 80);
        allocator.registerTenant("b", 20, 60);

        // a 用 50：30 保障 + 20 借用。
        assertThat(allocator.tryAcquire("a", 50)).isEqualTo(50);
        TenantStats a1 = allocator.tenantStats("a");
        assertThat(a1.used()).isEqualTo(50);
        assertThat(a1.guaranteedUsed()).isEqualTo(30);
        assertThat(a1.borrowed()).isEqualTo(20);
        assertThat(a1.totalBorrowed()).isEqualTo(20);
        assertThat(a1.totalReclaimed()).isZero();
        assertThat(allocator.stats().remaining()).isEqualTo(40);

        // b 用 20：全部在保障内，不触发收回（剩余 40 足够）。
        assertThat(allocator.tryAcquire("b", 20)).isEqualTo(20);
        TenantStats b1 = allocator.tenantStats("b");
        assertThat(b1.used()).isEqualTo(20);
        assertThat(b1.guaranteedUsed()).isEqualTo(20);
        assertThat(b1.borrowed()).isZero();
        assertThat(b1.totalBorrowed()).isZero();
        assertThat(allocator.stats().remaining()).isEqualTo(20);

        // a 再申请 30：全局剩余只有 20，只能借到 20，已用 70（30 保障 + 40 借用）。
        assertThat(allocator.tryAcquire("a", 30)).isEqualTo(20);
        TenantStats a2 = allocator.tenantStats("a");
        assertThat(a2.used()).isEqualTo(70);
        assertThat(a2.borrowed()).isEqualTo(40);
        assertThat(a2.totalBorrowed()).isEqualTo(40);
        assertThat(allocator.stats().remaining()).isZero();

        // b 的保障额度已用满（20/20），再申请属于借用；共享池已空，借不到。
        assertThat(allocator.tryAcquire("b", 20)).isZero();

        // a 释放 70：全部归还，全局剩余回到 70。
        assertThat(allocator.release("a", 70)).isEqualTo(70);
        assertThat(allocator.tenantStats("a").used()).isZero();
        assertThat(allocator.stats().remaining()).isEqualTo(70);

        // b 借用 40：20 保障 + 40 借用 = 60（到达上限）。
        assertThat(allocator.tryAcquire("b", 40)).isEqualTo(40);
        TenantStats b2 = allocator.tenantStats("b");
        assertThat(b2.used()).isEqualTo(60);
        assertThat(b2.borrowed()).isEqualTo(40);
        assertThat(b2.totalBorrowed()).isEqualTo(40);
        assertThat(allocator.stats().remaining()).isEqualTo(30);

        // b 释放 60：全局剩余回到 90，累计统计保持不变。
        assertThat(allocator.release("b", 60)).isEqualTo(60);
        GlobalStats global = allocator.stats();
        assertThat(global.totalCapacity()).isEqualTo(90);
        assertThat(global.totalUsed()).isZero();
        assertThat(global.remaining()).isEqualTo(90);
        assertThat(global.sharedPoolAvailable()).isEqualTo(90);
        assertThat(global.tenants()).containsOnlyKeys("a", "b");
        // 累计借用量不因释放而减少。
        assertThat(allocator.tenantStats("a").totalBorrowed()).isEqualTo(40);
        assertThat(allocator.tenantStats("b").totalBorrowed()).isEqualTo(40);
    }

    @Test
    void reclaimedAmountsAreAccountedToBothSides() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(90, Duration.ZERO, clock);
        allocator.registerTenant("a", 30, 80);
        allocator.registerTenant("b", 20, 60);

        // a 借到 80（30 保障 + 50 借用），全局剩余 10。
        allocator.tryAcquire("a", 80);
        // b 要 20 保障额度：缺口 10，从 a 收回 10。
        assertThat(allocator.tryAcquire("b", 20)).isEqualTo(20);

        TenantStats a = allocator.tenantStats("a");
        assertThat(a.used()).isEqualTo(70);
        assertThat(a.borrowed()).isEqualTo(40);
        assertThat(a.totalBorrowed()).isEqualTo(50);
        assertThat(a.totalReclaimed()).isEqualTo(10);

        TenantStats b = allocator.tenantStats("b");
        assertThat(b.used()).isEqualTo(20);
        assertThat(b.guaranteedUsed()).isEqualTo(20);
        assertThat(b.totalReclaimed()).isZero();

        GlobalStats global = allocator.stats();
        assertThat(global.totalUsed()).isEqualTo(90);
        assertThat(global.remaining()).isZero();
    }
}
