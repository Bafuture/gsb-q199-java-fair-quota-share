package com.example.gsb.quota;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 多租户配额公平分配器。
 *
 * <p>核心语义：
 * <ul>
 *   <li>每个租户配置最小保障额度 {@code minGuarantee} 与最大上限 {@code maxLimit}，
 *       所有租户的保障额度之和不得超过全局额度。</li>
 *   <li>租户申请额度时优先使用自己的保障额度；保障额度之外、上限之内的部分
 *       从共享池借用（共享池 = 全局剩余 − 其它租户未闲置的保留保障额度）。</li>
 *   <li>借用是“可收回”的：当某个租户需要使用自己的保障额度而全局剩余不足时，
 *       分配器按需、逐步地从借用方收回借用额度（每次只收回缺口大小，
 *       优先从当前借用最多的租户收回），绝不触碰任何租户的保障内用量。</li>
 *   <li>一段时间（{@code idleTimeout}）没有任何申请/释放活动的租户被视为闲置，
 *       其未使用的保障额度进入共享池供他人借用；未闲置租户的未用保障额度被保留。</li>
 * </ul>
 *
 * <p>不变式：任意时刻所有租户已用额度之和 ≤ 全局额度；任意租户已用 ≤ 其上限。
 * 由于保障额度之和 ≤ 全局额度，且借用额度总是可以被收回，
 * 因此每个租户对其保障额度内的申请永远可以被完全满足（公平性保证）。
 *
 * <p>所有公共方法都是线程安全的（内部使用内置锁串行化状态变更）。
 */
public final class FairQuotaAllocator {

    private final long totalCapacity;
    private final long idleTimeoutMillis;
    private final Clock clock;
    private final Map<String, TenantState> tenants = new LinkedHashMap<>();
    private long totalUsed;
    private long totalGuarantee;

    /**
     * @param totalCapacity 全局总额度，必须为正
     * @param idleTimeout   闲置判定超时；{@link Duration#ZERO} 表示未用保障额度立即可被借用
     * @param clock         时钟（测试中可注入可变时钟以控制闲置判定）
     */
    public FairQuotaAllocator(long totalCapacity, Duration idleTimeout, Clock clock) {
        if (totalCapacity <= 0) {
            throw new IllegalArgumentException("totalCapacity must be positive: " + totalCapacity);
        }
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative: " + idleTimeout);
        }
        this.totalCapacity = totalCapacity;
        this.idleTimeoutMillis = idleTimeout.toMillis();
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 注册租户。
     *
     * @param tenantId     租户标识，不得重复
     * @param minGuarantee 最小保障额度，&ge; 0，且注册后所有租户保障之和不得超过全局额度
     * @param maxLimit     最大上限，&ge; minGuarantee 且 &le; 全局额度
     */
    public synchronized void registerTenant(String tenantId, long minGuarantee, long maxLimit) {
        Objects.requireNonNull(tenantId, "tenantId");
        if (tenants.containsKey(tenantId)) {
            throw new IllegalArgumentException("tenant already registered: " + tenantId);
        }
        if (minGuarantee < 0) {
            throw new IllegalArgumentException("minGuarantee must not be negative: " + minGuarantee);
        }
        if (maxLimit < minGuarantee) {
            throw new IllegalArgumentException(
                    "maxLimit " + maxLimit + " must be >= minGuarantee " + minGuarantee);
        }
        if (maxLimit > totalCapacity) {
            throw new IllegalArgumentException(
                    "maxLimit " + maxLimit + " must be <= totalCapacity " + totalCapacity);
        }
        if (totalGuarantee + minGuarantee > totalCapacity) {
            throw new IllegalArgumentException(
                    "sum of guarantees would exceed totalCapacity: " + (totalGuarantee + minGuarantee)
                            + " > " + totalCapacity);
        }
        tenants.put(tenantId, new TenantState(tenantId, minGuarantee, maxLimit, clock.millis()));
        totalGuarantee += minGuarantee;
    }

    /**
     * 尝试为租户申请 {@code amount} 的额度，返回实际获得的额度（0..amount）。
     *
     * <p>保障额度内的部分永远会被完全满足（必要时触发对借用方的逐步收回）；
     * 超出保障的部分视共享池余量借用，最多到租户上限。
     */
    public synchronized long tryAcquire(String tenantId, long amount) {
        TenantState tenant = requireTenant(tenantId);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        long granted = 0;

        // 1) 保障额度内的部分：必须满足，不足时从借用方逐步收回。
        long ownWant = Math.min(amount, tenant.minGuarantee - tenant.used);
        if (ownWant > 0) {
            long deficit = ownWant - remaining();
            if (deficit > 0) {
                reclaim(deficit);
            }
            long take = Math.min(ownWant, remaining());
            tenant.used += take;
            totalUsed += take;
            granted += take;
        }

        // 2) 超出保障、上限之内的部分：从共享池借用。
        long borrowWant = Math.min(amount - granted, tenant.maxLimit - tenant.used);
        if (borrowWant > 0) {
            long available = remaining() - reservedForOthers(tenant);
            long take = Math.min(borrowWant, Math.max(0, available));
            if (take > 0) {
                tenant.used += take;
                tenant.totalBorrowed += take;
                totalUsed += take;
                granted += take;
            }
        }

        if (granted > 0) {
            tenant.lastActiveMillis = clock.millis();
        }
        return granted;
    }

    /**
     * 释放租户当前已用额度中的 {@code amount}，返回实际释放量。
     *
     * <p>释放借用部分会将其归还共享池。由于借用额度可能已被其它租户收回，
     * 当前实际已用量可能小于 {@code amount}，此时按实际已用量释放（钳制），
     * 调用方可通过返回值获知真实释放了多少。
     */
    public synchronized long release(String tenantId, long amount) {
        TenantState tenant = requireTenant(tenantId);
        if (amount <= 0) {
            throw new IllegalArgumentException("amount must be positive: " + amount);
        }
        long released = Math.min(amount, tenant.used);
        tenant.used -= released;
        totalUsed -= released;
        tenant.lastActiveMillis = clock.millis();
        return released;
    }

    /** 指定租户当前是否闲置（超过 idleTimeout 无任何申请/释放活动）。 */
    public synchronized boolean isIdle(String tenantId) {
        return isIdle(requireTenant(tenantId));
    }

    /** 当前所有处于闲置状态的租户标识（按注册顺序）。 */
    public synchronized List<String> idleTenants() {
        List<String> idle = new ArrayList<>();
        for (TenantState tenant : tenants.values()) {
            if (isIdle(tenant)) {
                idle.add(tenant.tenantId);
            }
        }
        return idle;
    }

    /** 指定租户的统计快照。 */
    public synchronized TenantStats tenantStats(String tenantId) {
        return snapshot(requireTenant(tenantId));
    }

    /** 全局统计快照，包含各租户统计与全局剩余。 */
    public synchronized GlobalStats stats() {
        Map<String, TenantStats> snapshots = new LinkedHashMap<>();
        long reserved = 0;
        for (TenantState tenant : tenants.values()) {
            snapshots.put(tenant.tenantId, snapshot(tenant));
            if (!isIdle(tenant)) {
                reserved += Math.max(0, tenant.minGuarantee - tenant.used);
            }
        }
        return new GlobalStats(
                totalCapacity,
                totalUsed,
                remaining(),
                Math.max(0, remaining() - reserved),
                Map.copyOf(snapshots));
    }

    /**
     * 按需逐步收回借用额度：每次从当前借用最多的租户收回一块，
     * 直到收回总量达到 {@code amount} 或没有可收回的借用额度。
     * 只触碰借用部分，绝不触碰任何租户的保障内用量。
     */
    private void reclaim(long amount) {
        long outstanding = amount;
        while (outstanding > 0) {
            TenantState victim = null;
            long victimBorrowed = 0;
            for (TenantState tenant : tenants.values()) {
                long borrowed = tenant.used - tenant.minGuarantee;
                if (borrowed > victimBorrowed) {
                    victim = tenant;
                    victimBorrowed = borrowed;
                }
            }
            if (victim == null) {
                return;
            }
            long chunk = Math.min(outstanding, victimBorrowed);
            victim.used -= chunk;
            victim.totalReclaimed += chunk;
            totalUsed -= chunk;
            outstanding -= chunk;
        }
    }

    /** 其它租户未闲置的保留保障额度之和（这部分不进入共享池）。 */
    private long reservedForOthers(TenantState borrower) {
        long reserved = 0;
        for (TenantState tenant : tenants.values()) {
            if (tenant != borrower && !isIdle(tenant)) {
                reserved += Math.max(0, tenant.minGuarantee - tenant.used);
            }
        }
        return reserved;
    }

    private boolean isIdle(TenantState tenant) {
        return clock.millis() - tenant.lastActiveMillis >= idleTimeoutMillis;
    }

    private TenantStats snapshot(TenantState tenant) {
        return new TenantStats(
                tenant.tenantId,
                tenant.minGuarantee,
                tenant.maxLimit,
                tenant.used,
                Math.min(tenant.used, tenant.minGuarantee),
                Math.max(0, tenant.used - tenant.minGuarantee),
                tenant.totalBorrowed,
                tenant.totalReclaimed,
                isIdle(tenant));
    }

    private long remaining() {
        return totalCapacity - totalUsed;
    }

    private TenantState requireTenant(String tenantId) {
        TenantState tenant = tenants.get(tenantId);
        if (tenant == null) {
            throw new IllegalArgumentException("unknown tenant: " + tenantId);
        }
        return tenant;
    }

    private static final class TenantState {
        private final String tenantId;
        private final long minGuarantee;
        private final long maxLimit;
        private long used;
        private long totalBorrowed;
        private long totalReclaimed;
        private long lastActiveMillis;

        private TenantState(String tenantId, long minGuarantee, long maxLimit, long lastActiveMillis) {
            this.tenantId = tenantId;
            this.minGuarantee = minGuarantee;
            this.maxLimit = maxLimit;
            this.lastActiveMillis = lastActiveMillis;
        }
    }
}
