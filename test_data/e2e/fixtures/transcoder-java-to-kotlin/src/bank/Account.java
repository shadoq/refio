package bank;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Account {
    public static final class Entry {
        private final String memo;
        private final Money amount;
        private final Money balanceAfter;

        Entry(String memo, Money amount, Money balanceAfter) {
            this.memo = memo;
            this.amount = amount;
            this.balanceAfter = balanceAfter;
        }

        public String getMemo() {
            return memo;
        }

        public Money getAmount() {
            return amount;
        }

        public Money getBalanceAfter() {
            return balanceAfter;
        }
    }

    private final String id;
    private final Money overdraftLimit;
    private Money balance = Money.ZERO;
    private final List<Entry> entries = new ArrayList<>();

    public Account(String id, Money overdraftLimit) {
        if (overdraftLimit.isNegative()) {
            throw new IllegalArgumentException("overdraft limit must not be negative");
        }
        this.id = id;
        this.overdraftLimit = overdraftLimit;
    }

    public String getId() {
        return id;
    }

    public Money getBalance() {
        return balance;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    public void post(String memo, Money amount) {
        Money next = balance.plus(amount);
        if (next.compareTo(overdraftLimit.negate()) < 0) {
            throw new IllegalStateException("overdraft limit exceeded for " + id);
        }
        balance = next;
        entries.add(new Entry(memo, amount, next));
    }
}
