package booking;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BookingService {
    private final Map<String, RoomCalendar> rooms = new LinkedHashMap<>();
    private int counter = 0;

    public void addRoom(String name) {
        rooms.put(name, new RoomCalendar());
    }

    public Booking book(String room, String owner, TimeSlot slot) {
        RoomCalendar calendar = rooms.get(room);
        if (!calendar.isFree(slot)) {
            throw new IllegalStateException("slot taken: " + room + " " + slot);
        }
        Booking booking = new Booking("B" + (++counter), room, owner, slot);
        calendar.add(booking);
        return booking;
    }

    public boolean cancel(String id) {
        for (RoomCalendar calendar : rooms.values()) {
            if (calendar.remove(id)) {
                return true;
            }
        }
        return false;
    }

    public List<Booking> bookingsFor(String owner) {
        List<Booking> result = new ArrayList<>();
        for (RoomCalendar calendar : rooms.values()) {
            for (Booking b : calendar.all()) {
                if (b.getOwner().equals(owner)) {
                    result.add(b);
                }
            }
        }
        result.sort(Comparator.comparingInt((Booking b) -> b.getSlot().getStart()).thenComparing(Booking::getId));
        return result;
    }
}
