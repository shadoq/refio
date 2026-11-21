# Instructions

Apply a list of JSON-patch-like operations to a JSON document.

## API

`json-patch.js` exports `applyPatch(doc, ops)` (CommonJS, `module.exports = { applyPatch }`). `doc` is any JSON value (object, array, string, number, boolean or `null`). `ops` is an array of operation objects. The function returns the patched document. It never modifies `doc` or `ops`, and the returned document shares no objects or arrays with `doc` or with the `value` of any operation (copy what you insert).

## Pointers

Every `path` and `from` is a JSON pointer: either `""` (the whole document) or `/`-separated tokens that each start with `/`. Inside a token, `~1` stands for `/` and `~0` for `~`. Decode `~1` first and then `~0`, so the token `~01` is the text `~1`. A `~` that is not followed by `0` or `1` is an error, and so is a non-empty pointer that does not start with `/`. The pointer `/` has one token, the empty string, which is a valid object key.

A token on an object is an own key of that object, whatever its text is (`__proto__`, `constructor` and `toString` are ordinary keys, and inherited properties do not exist for the patch). A token on an array is a decimal index without sign, leading zeros or decimals: `0` or `1..9` followed by digits (`01`, `-1`, `1.0` are errors). The token `-` means "the position after the last element" and is accepted only where the operation says so. A token on a string, number, boolean or `null` is an error.

## Operations

Each operation has `op` and `path`.

- `add` (needs `value`): adds `value` at `path`. The parent of `path` must exist. In an object it sets the key, replacing an existing value. In an array the index may be from `0` to the length (inserting and shifting later elements to the right), or `-` to append. Index beyond the length is an error. With `path` `""` the whole document becomes `value`.
- `remove`: removes the value at `path`, which must exist; array elements after it shift left. `-` is an error and the whole document cannot be removed.
- `replace` (needs `value`): the value at `path` must exist and is replaced. `-` is an error. With `""` the whole document becomes `value`.
- `move` (needs `from`): takes the value at `from`, which must exist, removes it, then adds it at `path` the way `add` does (so array indexes of `path` refer to the array after the removal). A `from` that is a proper prefix of `path` (moving something into its own child) is an error. Moving to the same location changes nothing.
- `copy` (needs `from`): adds a deep copy of the value at `from` at `path` the way `add` does.
- `test` (needs `value`): the value at `path` must exist and be deeply equal to `value` (same type, same array order, same keys and values in any key order). Otherwise the operation fails. It never changes the document.

An operation that is not an object, has an unknown `op`, lacks `path` as a string, lacks a required `value` or `from`, or whose `from` is not a string fails.

## Atomicity and errors

Operations run in order, each on the result of the previous. If any operation fails, nothing is applied: `applyPatch` throws an `Error` whose message starts with `op N: ` (where `N` is the 0-based index of the failing operation, followed by a short reason) and which has a numeric `index` property equal to `N`. `doc` is unchanged, as it is after a success.
