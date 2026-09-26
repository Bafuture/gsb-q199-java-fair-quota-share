package com.example.gsb.quota;

import java.util.Map;

/**
 * 全局配额使用的不可变快照。
 *
 * @param totalCapacity       全局总额度
 * @param totalUsed           所有租户当前已用额度之和
 * @param remaining           全局剩余额度（totalCapacity - totalUsed）
 * @param sharedPoolAvailable 共享池当前可借用额度（全局剩余减去各租户未闲置的保留保障额度）
 * @param tenants             各租户统计快照（按注册顺序）
 */
public record GlobalStats(
        long totalCapacity,
        long totalUsed,
        long remaining,
        long sharedPoolAvailable,
        Map<String, TenantStats> tenants) {
}
