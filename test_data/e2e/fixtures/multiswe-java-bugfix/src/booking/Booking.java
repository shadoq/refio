package booking;

public final class Booking {
    private final String id;
    private final String room;
    private final String owner;
    private final TimeSlot slot;

    public Booking(String id, String room, String owner, TimeSlot slot) {
        this.id = id;
        this.room = room;
        this.owner = owner;
        this.slot = slot;
    }

    public String getId() {
        return id;
    }

    public String getRoom() {
        return room;
    }

    public String getOwner() {
        return owner;
    }

    public TimeSlot getSlot() {
        return slot;
    }
}
