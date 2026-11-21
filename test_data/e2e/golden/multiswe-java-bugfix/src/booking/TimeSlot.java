package booking;

/** A half-open time range within one day, in minutes since midnight: [start, end). */
public final class TimeSlot {
    private final int start;
    private final int end;

    public TimeSlot(int start, int end) {
        if (start < 0 || end > 24 * 60 || start >= end) {
            throw new IllegalArgumentException("invalid slot: " + start + "-" + end);
        }
        this.start = start;
        this.end = end;
    }

    public int getStart() {
        return start;
    }

    public int getEnd() {
        return end;
    }

    public boolean overlaps(TimeSlot other) {
        return start < other.end && other.start < end;
    }

    @Override
    public String toString() {
        return String.format("%02d:%02d-%02d:%02d", start / 60, start % 60, end / 60, end % 60);
    }
}
