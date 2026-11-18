Bug report from the facilities team (room booking library in `src/booking/`):

1. Booking a room for 10:00-11:00 fails with "slot taken" when the same room is already booked 09:00-10:00. Meetings that end exactly when the next one starts must be allowed. Real overlaps must still be rejected.
2. `cancel(id)` returns false and leaves the slot blocked when the id comes from a request (a string parsed from the URL) instead of being the very object `book()` returned. It must work for any equal id string.
3. Booking a room name that does not exist crashes with a bare `NullPointerException`. It should fail with an `IllegalArgumentException` whose message is `unknown room: <name>`.

`python run_tests.py` compiles the project with the plain-Java test runner in `test/BookingTests.java` and runs it; it must exit 0. Do not edit the tests.
