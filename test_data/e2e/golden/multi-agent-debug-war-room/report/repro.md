# Reproduction report (reproducer)

`business_days_between` returns the raw inclusive day count instead of counting
Monday to Friday only, so any range containing a weekend is overcounted and
weekend-only ranges return 2 instead of 0.

failing_input = business_days_between(date(2024,1,6), date(2024,1,7))
failing_input = business_days_between(date(2026,3,6), date(2026,3,9))
failing_input = business_days_between(date(2024,1,1), date(2024,1,14))
