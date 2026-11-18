package booking;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** The bookings of a single room. */
final class RoomCalendar {
    private final List<Booking> bookings = new ArrayList<>();

    boolean isFree(TimeSlot slot) {
        for (Booking b : bookings) {
            if (b.getSlot().overlaps(slot)) {
                return false;
            }
        }
        return true;
    }

    void add(Booking booking) {
        bookings.add(booking);
    }

    boolean remove(String id) {
        Iterator<Booking> it = bookings.iterator();
        while (it.hasNext()) {
            if (it.next().getId().equals(id)) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    List<Booking> all() {
        return new ArrayList<>(bookings);
    }
}
