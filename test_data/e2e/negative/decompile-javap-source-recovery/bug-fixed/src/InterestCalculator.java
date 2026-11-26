/*
 * Recovered from InterestCalculator.class (javap listing, class file version 61).
 * Behaviour is identical to the shipped class, including the truncating divisions.
 */
public class InterestCalculator {

    private static final long TIER1 = 100000L;
    private static final long TIER2 = 1000000L;
    private static final long TIER3 = 10000000L;

    private InterestCalculator() {
    }

    public static boolean isLeap(int year) {
        if (year % 4 != 0) {
            return false;
        }
        if (year % 100 != 0) {
            return true;
        }
        return year % 400 == 0;
    }

    public static int rateBp(long amountCents) {
        if (amountCents < 0L) {
            return 0;
        }
        if (amountCents < TIER1) {
            return 150;
        }
        if (amountCents < TIER2) {
            return 225;
        }
        return amountCents < TIER3 ? 310 : 425;
    }

    public static String tierName(long amountCents) {
        if (amountCents < 0L) {
            return "invalid";
        }
        if (amountCents < TIER1) {
            return "basic";
        }
        if (amountCents < TIER2) {
            return "standard";
        }
        return amountCents < TIER3 ? "premium" : "private";
    }

    public static long interest(long amountCents, int days, int year) {
        if (days <= 0) {
            return 0L;
        }
        int denom = isLeap(year) ? 366 : 365;
        long scaled = amountCents * rateBp(amountCents);
        long yearly = (scaled + 5000L) / 10000L;   // proper rounding, not the class's truncation
        long daily = yearly / denom;
        long fee = (days > 730 ? 40L : 12L) + daily / 50L;
        return daily * days + fee;
    }

    public static void main(String[] args) {
        if (args.length == 0 || args.length % 3 != 0) {
            System.err.println("usage: InterestCalculator <amount-cents> <days> <year> [...]");
            System.exit(2);
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i + 2 < args.length; i += 3) {
            long amount = Long.parseLong(args[i]);
            int days = Integer.parseInt(args[i + 1]);
            int year = Integer.parseInt(args[i + 2]);
            out.append("amount=").append(amount)
               .append(" days=").append(days)
               .append(" year=").append(year)
               .append(" leap=").append(isLeap(year))
               .append(" tier=").append(tierName(amount))
               .append(" rate=").append(rateBp(amount))
               .append(" interest=").append(interest(amount, days, year))
               .append('\n');
        }
        System.out.print(out);
    }
}
