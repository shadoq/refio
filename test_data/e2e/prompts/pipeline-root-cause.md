Do not modify any files.

This pipeline runs ingest -> transform -> load. A bug report says the reported dollar
totals are consistently too low: a $10.50 sale shows up as $10.00, and the grand total
is short by the dropped cents.

Investigate the code and point at the root-cause file and the specific function
responsible. Explain briefly why it produces the wrong number.

This is a diagnosis task only. Do NOT fix or change anything.
