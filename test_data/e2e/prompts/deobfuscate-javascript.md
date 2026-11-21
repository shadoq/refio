A third-party "metrics beacon" script was bundled into this project and the vendor is no
longer reachable. You have the minified, obfuscated source only:

- `sample/tracker.js` - the obfuscated beacon. Strings live in a hex-escaped array literal;
  a self-invoking function rotates that array at load time, and an accessor function maps
  small hex indices onto (post-rotation) array slots.
- `README.md` - bundle notes.

The bundler needs to know what this script actually does before it can be allowed into the
next release. Your task: deobfuscate the script **statically** (do not execute it against any
network; node may be used offline if helpful) and write `report/deobfuscated-config.md` with
the recovered runtime configuration:

- `c2` - the full beacon URL the script reports to,
- `key` - the beacon key it sends,
- `every` - the reporting interval **in milliseconds**,
- `rounds` - how many reporting rounds it performs.

Show the mapping you used from accessor indices to array values (the rotation matters: the
array literal is not in its load-time order). Do not modify `sample/tracker.js`, the README or
any other project file - the deliverable is the report only.
