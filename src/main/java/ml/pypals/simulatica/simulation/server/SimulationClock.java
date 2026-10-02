package ml.pypals.simulatica.simulation.server;

/** Wall-clock scheduling only; machine logic always receives complete vanilla ticks. */
public final class SimulationClock {
    private int target = 20;
    private long lastNanos;
    private double credit;
    private long ticks;
    private long sampleNanos;
    private long sampleTicks;
    private double actual;

    public int target() { return target; }
    public long ticks() { return ticks; }
    public double actual() { return actual; }

    public void setTarget(int target) {
        if (target < 1 || target > 1000) throw new IllegalArgumentException("TPS must be 1–1000");
        this.target = target;
        suspend();
    }

    public void suspend() {
        lastNanos = sampleNanos = 0;
        credit = actual = 0;
    }

    public void accrue(long now) {
        if (lastNanos != 0) {
            // ponytail: debt is capped at 100ms (or one tick at low TPS); overload runs slower instead of catching up forever.
            credit = Math.min(credit + (now - lastNanos) * target / 1_000_000_000.0, Math.max(1, target * 0.1));
        }
        lastNanos = now;
        if (sampleNanos == 0) {
            sampleNanos = now;
            sampleTicks = ticks;
        } else if (now - sampleNanos >= 1_000_000_000L) {
            actual = (ticks - sampleTicks) * 1_000_000_000.0 / (now - sampleNanos);
            sampleNanos = now;
            sampleTicks = ticks;
        }
    }

    public boolean due() { return credit >= 1; }
    public void advanced() { credit -= 1; ticks++; }
}
