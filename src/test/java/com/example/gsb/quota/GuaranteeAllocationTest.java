package com.example.gsb.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 保底分配：每个租户先拿到自己的保障额度；注册参数校验。 */
class GuaranteeAllocationTest {

    private final MutableClock clock = new MutableClock();

    @Test
    void eachTenantGetsItsGuaranteeFirst() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("a", 30, 80);
        allocator.registerTenant("b", 20, 60);
        allocator.registerTenant("c", 50, 100);

        assertThat(allocator.tryAcquire("a", 30)).isEqualTo(30);
        assertThat(allocator.tryAcquire("b", 20)).isEqualTo(20);
        assertThat(allocator.tryAcquire("c", 50)).isEqualTo(50);

        GlobalStats stats = allocator.stats();
        assertThat(stats.totalUsed()).isEqualTo(100);
        assertThat(stats.remaining()).isZero();
        assertThat(stats.tenants().get("a").guaranteedUsed()).isEqualTo(30);
        assertThat(stats.tenants().get("b").guaranteedUsed()).isEqualTo(20);
        assertThat(stats.tenants().get("c").guaranteedUsed()).isEqualTo(50);
    }

    @Test
    void guaranteeIsAlwaysSatisfiableEvenWhenPoolIsExhausted() {
        // 保障额度之和等于全局额度：池子里没有多余额度可借。
        FairQuotaAllocator allocator = new FairQuotaAllocator(60, Duration.ZERO, clock);
        allocator.registerTenant("a", 20, 60);
        allocator.registerTenant("b", 40, 60);

        // a 借光了全部额度（20 保障 + 40 借用）。
        assertThat(allocator.tryAcquire("a", 60)).isEqualTo(60);
        // b 的保障额度依然必须被完整满足（触发对 a 的收回）。
        assertThat(allocator.tryAcquire("b", 40)).isEqualTo(40);

        assertThat(allocator.tenantStats("a").used()).isEqualTo(20);
        assertThat(allocator.tenantStats("b").used()).isEqualTo(40);
    }

    @Test
    void borrowIsCappedByMaxLimit() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("a", 10, 50);

        assertThat(allocator.tryAcquire("a", 1000)).isEqualTo(50);
        assertThat(allocator.tenantStats("a").used()).isEqualTo(50);
    }

    @Test
    void rejectsInvalidRegistrations() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("a", 60, 100);

        // 保障额度之和超过全局额度。
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allocator.registerTenant("b", 41, 50));
        // 上限小于保障额度。
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allocator.registerTenant("b", 20, 10));
        // 上限超过全局额度。
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allocator.registerTenant("b", 10, 101));
        // 负的保障额度。
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allocator.registerTenant("b", -1, 10));
        // 重复注册。
        assertThatIllegalArgumentException()
                .isThrownBy(() -> allocator.registerTenant("a", 1, 1));
        // 恰好注册满保障额度之和是允许的。
        allocator.registerTenant("b", 40, 100);
        assertThat(allocator.stats().tenants()).containsOnlyKeys("a", "b");
    }

    @Test
    void rejectsInvalidOperations() {
        FairQuotaAllocator allocator = new FairQuotaAllocator(100, Duration.ZERO, clock);
        allocator.registerTenant("a", 10, 50);

        assertThatIllegalArgumentException().isThrownBy(() -> allocator.tryAcquire("ghost", 1));
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.tryAcquire("a", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.release("a", 0));
        assertThatIllegalArgumentException().isThrownBy(() -> allocator.release("ghost", 1));

        // 释放量超过当前已用量时按实际已用量钳制（借用额度可能已被收回）。
        allocator.tryAcquire("a", 10);
        assertThat(allocator.release("a", 11)).isEqualTo(10);
        assertThat(allocator.tenantStats("a").used()).isZero();
    }
}
