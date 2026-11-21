package bank;

import java.util.Locale;

public final class Statement {
    private Statement() {
    }

    public static String render(Account account) {
        StringBuilder sb = new StringBuilder();
        sb.append("Statement for ").append(account.getId()).append('\n');
        if (account.getEntries().isEmpty()) {
            sb.append("(no entries)\n");
        }
        for (Account.Entry e : account.getEntries()) {
            String memo = e.getMemo();
            if (memo.length() > 12) {
                memo = memo.substring(0, 12);
            }
            sb.append(String.format(Locale.ROOT, "%-12s %10s %10s", memo, e.getAmount(), e.getBalanceAfter()));
            sb.append('\n');
        }
        sb.append("Balance: ").append(account.getBalance());
        return sb.toString();
    }
}
