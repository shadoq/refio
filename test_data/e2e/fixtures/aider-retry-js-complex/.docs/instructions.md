# Instructions

Implement a small synchronous event emitter for a UI toolkit.

## API

```js
const { Emitter } = require('./emitter');

const em = new Emitter();
const off = em.on('save', (doc, user) => { /* ... */ }); // returns an unsubscribe function
em.once('save', fn);        // like on, but removed right before its first call
em.off('save', fn);         // removes one registration of fn
em.emit('save', doc, user); // calls the listeners, returns how many were called
em.listenerCount('save');   // number of registrations for the event
```

`on` and `once` return the function that removes exactly that registration.

## Rules

- Listeners run in the order they were registered, receive the emit arguments, and are called with `this` set to the emitter.
- `emit` for an event with no listeners returns `0` and does nothing.
- `emit` works on a snapshot taken when it starts. A listener added during an emit does not run in that emit. A listener removed during an emit (by `off`, by an unsubscribe function, or because it was a `once` listener that already ran) still runs in that emit if it had not run yet, unless it is a `once` listener that has already been called.
- A `once` listener is removed from the emitter before it is called, so if it triggers a nested `emit` of the same event, it does not run again.
- The same function can be registered several times; each registration is called. `off(event, fn)` removes only the **most recently added** registration of `fn` and does nothing when there is none. It also removes a registration made with `once(event, fn)`.
- An unsubscribe function removes only its own registration and is safe to call more than once.
- When a listener throws, the remaining listeners of that emit still run. After the last one, `emit` rethrows the **first** error it saw.
- `emit` returns the number of listener calls that were started, including the ones that threw.
- `listenerCount` counts registrations, not distinct functions. Events are independent of each other.
