# Instructions

Simulate one lift (elevator) car and report where and when it stops. The simulation is fully deterministic and uses whole seconds.

## Input

`simulate(calls, buttons=(), start=1, travel=2, door=5)` returns the list of stops as `(time, floor)` tuples, in the order they happen. `time` is the second at which the car arrives and its doors start to open.

- `calls` are hall calls: tuples `(time, floor, direction)`, where `direction` is `"U"` (the passenger wants to go up) or `"D"` (down). Any other direction raises `ValueError`.
- `buttons` are car buttons: tuples `(time, floor)`.
- `start` is the floor where the car waits at time 0 with its doors closed.
- `travel` is the number of seconds to move one floor. `door` is the number of seconds a stop lasts (the car cannot move until it is over).

The input lists may be in any order. A hall call or button becomes a *request* at its `time`; it is *available* from then on until it is served. Floors are integers.

## State

The car has a current floor, a clock `now` (starts at 0) and a direction: `None` (idle), `"U"` or `"D"`. It repeats the following steps until no request is left.

1. **Nothing available.** If no request is available at `now`, the car becomes idle (direction `None`) and `now` jumps to the time of the earliest request that is not yet available.
2. **Stop check.** Look at the available requests for the car's current floor. A request is served now when it is a car button, or a hall call whose direction equals the car's direction, or when the car has no available request strictly ahead of it in its direction (an idle car has none ahead, so it serves everything at its floor). If at least one request is served: the car stops, `(now, floor)` is recorded, all the served requests are removed, `now` grows by `door`, and the loop starts again. Requests for this floor that were not served stay pending. A request for the current floor that is made while the doors are open is not part of that stop; it is handled by the next pass of the loop and, if it is served, produces another stop at the same floor.
3. **Choose the direction.** If the car is idle it heads towards the nearest floor that has an available request (the lower floor on a tie). If it has a direction and there is an available request strictly ahead in that direction (any kind of request, whatever its own direction), it keeps going. Otherwise it reverses.
4. **Move.** The car moves one floor in its direction and `now` grows by `travel`. Back to step 2.

This is the collective-control rule: the car keeps its direction while requests remain ahead, picks up on the way only the hall calls that go the same way as it does, and turns round only when nothing is left ahead. A request is considered at the moment the car arrives on a floor, so a request whose time is later than the arrival is not served by passing that floor.

## Example

With the defaults, a car starting at floor 1 with the single button `(0, 4)` moves to floors 2 and 3 and arrives at floor 4 at second 6, so the result is `[(6, 4)]`.
