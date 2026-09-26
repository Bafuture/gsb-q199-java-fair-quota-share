package com.example.gsb.quota;

/**
 * 单个租户配额使用的不可变快照。
 *
 * @param tenantId       租户标识
 * @param minGuarantee   最小保障额度（任何情况下都可通过收回借用获得）
 * @param maxLimit       最大上限（保障额度 + 可借用额度的总和上限）
 * @param used           当前已用额度（保障内 + 借用）
 * @param guaranteedUsed 当前已用中属于保障额度的部分
 * @param borrowed       当前已用中属于借用共享池的部分（可被原租户收回）
 * @param totalBorrowed  累计借用量（历史上从共享池借入的总额）
 * @param totalReclaimed 累计被收回量（历史上被其它租户收回的借用额度总额）
 * @param idle           当前是否处于闲置状态（未用保障额度已进入共享池）
 */
public record TenantStats(
        String tenantId,
        long minGuarantee,
        long maxLimit,
        long used,
        long guaranteedUsed,
        long borrowed,
        long totalBorrowed,
        long totalReclaimed,
        boolean idle) {
}
