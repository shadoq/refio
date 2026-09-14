Long amounts on the receipt are hard to read: `123456789` prints as `1234567.89 PLN`.

Group the thousands with a single space, so that amount prints as `1 234 567.89 PLN`. Short amounts must keep printing exactly as they do now, and the currency stays `PLN`.

The test suite in `test/` already covers both cases. Do not change the tests. Run them yourself before you finish.
