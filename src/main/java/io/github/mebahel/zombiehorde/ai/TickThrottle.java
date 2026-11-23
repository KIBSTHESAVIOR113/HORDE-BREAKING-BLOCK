package io.github.mebahel.zombiehorde.ai;

/** REFERENCE: per-instance cooldown measured in ticks (use Entity#getTicksLived as the clock). Zero dependencies. */
public final class TickThrottle {
    private long last = Long.MIN_VALUE / 2;

    /** True at most once per intervalTicks; records the call when it returns true. */
    public boolean tryAcquire(long nowTick, int intervalTicks) {
        if (nowTick - last >= intervalTicks) {
            last = nowTick;
            return true;
        }
        return false;
    }

    /** Lets a caller force the next tryAcquire to succeed (e.g. after the target died). */
    public void reset() {
        last = Long.MIN_VALUE / 2;
    }
}
