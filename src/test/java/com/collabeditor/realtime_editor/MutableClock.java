package com.collabeditor.realtime_editor;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A {@link Clock} whose time only moves when a test says so. */
public final class MutableClock extends Clock {

    private volatile Instant now;

    public MutableClock() {
        this(Instant.parse("2026-01-01T00:00:00Z"));
    }

    public MutableClock(Instant start) {
        this.now = start;
    }

    public void advance(Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}