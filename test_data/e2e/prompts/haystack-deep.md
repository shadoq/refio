Somewhere in this project the discount calculation is wrong. A 10% discount on a
$5.00 item should give $4.50, but the code currently gives $4.90 - it subtracts the
percent value itself instead of that percentage of the price.

Find the file that contains the buggy discount calculation and fix it so the discount
is applied as a percentage of the price. The project is large, so search for the
relevant code rather than reading every file.

Change only the buggy calculation. Do not touch the similarly named discount formatter
or validator, and do not touch the unrelated price helper.
