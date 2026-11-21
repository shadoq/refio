import booking.Booking;
import booking.BookingService;
import booking.TimeSlot;

import java.util.List;

public final class BookingTests {
    private static int failures = 0;

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body body) {
        try {
            body.run();
            System.out.println("ok   " + name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    static void check(boolean cond, String message) {
        if (!cond) throw new AssertionError(message);
    }

    static void checkEquals(Object expected, Object actual) {
        if (!expected.equals(actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static TimeSlot slot(int h1, int m1, int h2, int m2) {
        return new TimeSlot(h1 * 60 + m1, h2 * 60 + m2);
    }

    static BookingService service() {
        BookingService s = new BookingService();
        s.addRoom("atlas");
        s.addRoom("birch");
        return s;
    }

    public static void main(String[] args) {
        test("back-to-back bookings in one room are allowed", () -> {
            BookingService s = service();
            s.book("atlas", "ann", slot(9, 0, 10, 0));
            s.book("atlas", "bob", slot(10, 0, 11, 0));
            s.book("atlas", "cy", slot(8, 0, 9, 0));
            checkEquals(3, s.bookingsFor("ann").size() + s.bookingsFor("bob").size() + s.bookingsFor("cy").size());
        });

        test("real overlaps are still rejected", () -> {
            BookingService s = service();
            s.book("atlas", "ann", slot(9, 0, 10, 0));
            int rejected = 0;
            TimeSlot[] clashing = {slot(9, 59, 10, 30), slot(9, 0, 10, 0), slot(8, 0, 9, 1), slot(9, 15, 9, 45), slot(8, 0, 11, 0)};
            for (TimeSlot t : clashing) {
                try {
                    s.book("atlas", "bob", t);
                } catch (IllegalStateException expected) {
                    rejected++;
                }
            }
            checkEquals(clashing.length, rejected);
        });

        test("different rooms do not clash", () -> {
            BookingService s = service();
            s.book("atlas", "ann", slot(9, 0, 10, 0));
            s.book("birch", "bob", slot(9, 0, 10, 0));
        });

        test("cancelling by an id typed in by the caller frees the slot", () -> {
            BookingService s = service();
            Booking first = s.book("atlas", "ann", slot(9, 0, 10, 0));
            String typed = new String(first.getId());
            check(s.cancel(typed), "cancel should find the booking");
            s.book("atlas", "bob", slot(9, 0, 10, 0));
            check(!s.cancel(new String("B99")), "unknown id is not cancellable");
            check(!s.cancel(typed), "a booking cannot be cancelled twice");
        });

        test("booking an unknown room is a clear error", () -> {
            BookingService s = service();
            try {
                s.book("lab", "ann", slot(9, 0, 10, 0));
                throw new AssertionError("expected an exception");
            } catch (IllegalArgumentException e) {
                checkEquals("unknown room: lab", e.getMessage());
            }
        });

        test("bookingsFor lists an owner's bookings in time order without cancelled ones", () -> {
            BookingService s = service();
            s.book("birch", "ann", slot(13, 0, 14, 0));
            Booking dropped = s.book("atlas", "ann", slot(11, 0, 12, 0));
            s.book("atlas", "ann", slot(9, 0, 10, 0));
            s.book("atlas", "bob", slot(15, 0, 16, 0));
            s.cancel(dropped.getId());
            List<Booking> mine = s.bookingsFor("ann");
            checkEquals(2, mine.size());
            checkEquals("09:00-10:00", mine.get(0).getSlot().toString());
            checkEquals("13:00-14:00", mine.get(1).getSlot().toString());
        });

        test("slots must be a non-empty range inside one day", () -> {
            int bad = 0;
            int[][] cases = {{600, 600}, {700, 600}, {-1, 10}, {0, 1441}};
            for (int[] c : cases) {
                try {
                    new TimeSlot(c[0], c[1]);
                } catch (IllegalArgumentException e) {
                    bad++;
                }
            }
            checkEquals(cases.length, bad + 1);
            new TimeSlot(0, 1440);
        });

        if (failures > 0) {
            System.out.println(failures + " test(s) failed");
            System.exit(1);
        }
        System.out.println("all tests passed");
    }
}
