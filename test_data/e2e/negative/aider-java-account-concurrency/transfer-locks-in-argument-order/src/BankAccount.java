import java.util.concurrent.atomic.AtomicLong;

public class BankAccount {
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private final long order = SEQUENCE.incrementAndGet();
    private boolean open;
    private long balance;

    public synchronized void open() {
        if (open) throw new IllegalStateException("account is already open");
        open = true;
        balance = 0;
    }

    public synchronized long close() {
        requireOpen();
        long payout = balance;
        balance = 0;
        open = false;
        return payout;
    }

    public synchronized long balance() {
        requireOpen();
        return balance;
    }

    public synchronized void deposit(long amount) {
        requireOpen();
        requirePositive(amount);
        balance = Math.addExact(balance, amount);
    }

    public synchronized void withdraw(long amount) {
        requireOpen();
        requirePositive(amount);
        requireFunds(amount);
        balance -= amount;
    }

    public void transferTo(BankAccount other, long amount) {
        if (other == this) throw new IllegalArgumentException("cannot transfer to the same account");
        requirePositive(amount);
        BankAccount first = this;
        BankAccount second = other;
        synchronized (first) {
            synchronized (second) {
                requireOpen();
                other.requireOpen();
                requireFunds(amount);
                long target = Math.addExact(other.balance, amount);
                balance -= amount;
                other.balance = target;
            }
        }
    }

    private void requireOpen() {
        if (!open) throw new IllegalStateException("account is closed");
    }

    private static void requirePositive(long amount) {
        if (amount <= 0) throw new IllegalArgumentException("amount must be positive");
    }

    private void requireFunds(long amount) {
        if (amount > balance) throw new InsufficientFundsException("balance " + balance + " is below " + amount);
    }
}
