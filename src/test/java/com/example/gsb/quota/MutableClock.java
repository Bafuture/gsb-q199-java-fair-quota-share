package com.example.gsb.quota;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** 测试用可变时钟，可手动推进时间以触发闲置判定。 */
final class MutableClock extends Clock {

    private long millis;

    @Override
    public synchronized long millis() {
        return millis;
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis());
    }

    @Override
    public ZoneId getZone() {
        return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    synchronized void advance(Duration duration) {
        millis += duration.toMillis();
    }
}
