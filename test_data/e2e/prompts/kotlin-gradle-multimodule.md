This is a small Gradle project with three modules: `money`, `pricing` and `app`. Our checkout is producing totals that are a few cents off, and orders that list the same product on several lines do not get the bulk discount they should.

Find the cause and fix it so the whole build is green. Keep the public API of each module as it is. Run the tests with `node verify.cjs` (it calls the Gradle wrapper offline and checks every module); the existing tests describe the intended behaviour, so do not change them.
