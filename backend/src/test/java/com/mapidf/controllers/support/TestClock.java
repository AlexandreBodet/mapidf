package com.mapidf.controllers.support;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Java n'a pas d'horloge mutable : sans elle, éprouver une frontière de fenêtre imposerait de
 * dormir, donc un test lent et instable.
 */
final class TestClock extends Clock {

    private Instant now;

    TestClock(Instant now) {
        this.now = now;
    }

    void set(Instant next) {
        this.now = next;
    }

    @Override
    public Instant instant() {
        return now;
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }
}
