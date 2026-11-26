# Source recovery: InterestCalculator

The decompiled file is close, but javap.txt shows three differences. The rate method uses
strict `ifge` jumps at every tier boundary, so the second tier test is a strict less-than,
not less-or-equal. The yearly step is an `ldiv` (long division truncates), with no rounding
sequence anywhere near it. The fee line loads the ternary result and `daily / 50` together
before the single `ladd`, so the division belongs to the sum, not to one arm.

mistake = 31 | the tier test must be a strict less-than: the bytecode compares against 1000000l with lcmp/ifge (jump when greater or equal), so the 225 branch is taken only below the tier 2 boundary, not at it
mistake = 56 | the bytecode divides scaled by 10000 with ldiv (truncating long division); there is no rounding, so yearly is scaled / 10000L
mistake = 58 | the bytecode evaluates the ternary (days > 730 ? 40L : 12L) first and then adds daily / 50L (both constants jump to the same ladd), so the parentheses go around the ternary: (days > 730 ? 40L : 12L) + daily / 50L
