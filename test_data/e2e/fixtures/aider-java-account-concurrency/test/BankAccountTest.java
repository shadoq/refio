import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

public class BankAccountTest {
    private static int run = 0;
    private static int failures = 0;

    interface Body {
        void run() throws Exception;
    }

    static void test(String name, Body body) {
        run++;
        try {
            body.run();
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": " + t);
        }
    }

    static void eq(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("expected " + expected + " but was " + actual);
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void throwsA(Class<? extends Throwable> type, Body body) {
        try {
            body.run();
        } catch (Throwable t) {
            if (!type.isInstance(t)) throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
            return;
        }
        throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static BankAccount opened() {
        BankAccount account = new BankAccount();
        account.open();
        return account;
    }

    /** Runs the bodies on separate daemon threads and fails if they do not finish in time. */
    static void inParallel(int threads, Body body) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> pool = new ArrayList<>();
        List<Throwable> errors = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            final int index = i;
            Thread t = new Thread(() -> {
                try {
                    start.await();
                    body.run();
                } catch (Throwable e) {
                    synchronized (errors) {
                        errors.add(e);
                    }
                }
            });
            t.setDaemon(true);
            pool.add(t);
            t.start();
        }
        start.countDown();
        for (Thread t : pool) {
            t.join(20000);
            if (t.isAlive()) throw new AssertionError("a thread did not finish within 20 s (deadlock?)");
        }
        if (!errors.isEmpty()) throw new AssertionError("worker failed: " + errors.get(0));
    }

    public static void main(String[] args) {
        test("a new account is closed", () -> {
            BankAccount account = new BankAccount();
            throwsA(IllegalStateException.class, () -> account.balance());
            throwsA(IllegalStateException.class, () -> account.deposit(5));
            throwsA(IllegalStateException.class, () -> account.withdraw(5));
            throwsA(IllegalStateException.class, () -> account.close());
        });

        test("open starts at zero and cannot be repeated", () -> {
            BankAccount account = opened();
            eq(0L, account.balance());
            throwsA(IllegalStateException.class, () -> account.open());
        });

        test("deposit and withdraw change the balance", () -> {
            BankAccount account = opened();
            account.deposit(1000);
            account.deposit(250);
            account.withdraw(400);
            eq(850L, account.balance());
            account.withdraw(850);
            eq(0L, account.balance());
        });

        test("close pays out the balance and closes the account", () -> {
            BankAccount account = opened();
            account.deposit(700);
            eq(700L, account.close());
            throwsA(IllegalStateException.class, () -> account.balance());
            throwsA(IllegalStateException.class, () -> account.deposit(1));
            throwsA(IllegalStateException.class, () -> account.withdraw(1));
            throwsA(IllegalStateException.class, () -> account.close());
        });

        test("reopening starts again from zero", () -> {
            BankAccount account = opened();
            account.deposit(99);
            account.close();
            account.open();
            eq(0L, account.balance());
            account.deposit(5);
            eq(5L, account.balance());
        });

        test("closed account reports the state error before the amount error", () -> {
            BankAccount account = new BankAccount();
            throwsA(IllegalStateException.class, () -> account.deposit(0));
            throwsA(IllegalStateException.class, () -> account.withdraw(-3));
        });

        test("amounts must be positive", () -> {
            BankAccount account = opened();
            throwsA(IllegalArgumentException.class, () -> account.deposit(0));
            throwsA(IllegalArgumentException.class, () -> account.deposit(-1));
            throwsA(IllegalArgumentException.class, () -> account.withdraw(0));
            throwsA(IllegalArgumentException.class, () -> account.withdraw(-1));
            eq(0L, account.balance());
        });

        test("overdraft is rejected and changes nothing", () -> {
            BankAccount account = opened();
            account.deposit(100);
            throwsA(InsufficientFundsException.class, () -> account.withdraw(101));
            eq(100L, account.balance());
            account.withdraw(100);
            throwsA(InsufficientFundsException.class, () -> account.withdraw(1));
            eq(0L, account.balance());
        });

        test("deposit overflow is rejected and changes nothing", () -> {
            BankAccount account = opened();
            account.deposit(Long.MAX_VALUE - 10);
            throwsA(ArithmeticException.class, () -> account.deposit(11));
            eq(Long.MAX_VALUE - 10, account.balance());
            account.deposit(10);
            eq(Long.MAX_VALUE, account.balance());
        });

        test("transfer moves money between accounts", () -> {
            BankAccount a = opened();
            BankAccount b = opened();
            a.deposit(500);
            a.transferTo(b, 200);
            eq(300L, a.balance());
            eq(200L, b.balance());
        });

        test("transfer checks its arguments before the state of the accounts", () -> {
            BankAccount a = opened();
            BankAccount closed = new BankAccount();
            a.deposit(10);
            throwsA(IllegalArgumentException.class, () -> a.transferTo(a, 5));
            throwsA(IllegalArgumentException.class, () -> a.transferTo(closed, 0));
            throwsA(IllegalArgumentException.class, () -> closed.transferTo(closed, 5));
            throwsA(IllegalArgumentException.class, () -> closed.transferTo(a, -5));
        });

        test("transfer needs both accounts open and changes nothing on failure", () -> {
            BankAccount a = opened();
            BankAccount closed = new BankAccount();
            a.deposit(10);
            throwsA(IllegalStateException.class, () -> a.transferTo(closed, 5));
            throwsA(IllegalStateException.class, () -> closed.transferTo(a, 5));
            eq(10L, a.balance());
        });

        test("transfer of more than the balance is rejected", () -> {
            BankAccount a = opened();
            BankAccount b = opened();
            a.deposit(10);
            throwsA(InsufficientFundsException.class, () -> a.transferTo(b, 11));
            eq(10L, a.balance());
            eq(0L, b.balance());
        });

        test("parallel deposits are not lost", () -> {
            BankAccount account = opened();
            inParallel(8, () -> {
                for (int i = 0; i < 50000; i++) account.deposit(1);
            });
            eq(400000L, account.balance());
        });

        test("parallel withdrawals never overdraw", () -> {
            BankAccount account = opened();
            account.deposit(20000);
            AtomicLong succeeded = new AtomicLong();
            inParallel(8, () -> {
                for (int i = 0; i < 5000; i++) {
                    try {
                        account.withdraw(1);
                        succeeded.incrementAndGet();
                    } catch (InsufficientFundsException expected) {
                        // balance ran out
                    }
                }
            });
            eq(20000L, succeeded.get());
            eq(0L, account.balance());
        });

        test("mixed parallel deposits and withdrawals add up", () -> {
            BankAccount account = opened();
            account.deposit(1000);
            AtomicLong withdrawn = new AtomicLong();
            AtomicLong deposited = new AtomicLong();
            inParallel(8, () -> {
                for (int i = 0; i < 20000; i++) {
                    if (i % 2 == 0) {
                        account.deposit(3);
                        deposited.addAndGet(3);
                    } else {
                        try {
                            account.withdraw(2);
                            withdrawn.addAndGet(2);
                        } catch (InsufficientFundsException expected) {
                            // fine
                        }
                    }
                    check(account.balance() >= 0, "negative balance seen");
                }
            });
            eq(1000L + deposited.get() - withdrawn.get(), account.balance());
        });

        test("close is atomic with concurrent deposits", () -> {
            BankAccount account = opened();
            AtomicLong accepted = new AtomicLong();
            AtomicLong payout = new AtomicLong(-1);
            Thread closer = new Thread(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException ignored) {
                    // ignore
                }
                payout.set(account.close());
            });
            closer.setDaemon(true);
            closer.start();
            inParallel(6, () -> {
                while (true) {
                    try {
                        account.deposit(1);
                        accepted.incrementAndGet();
                    } catch (IllegalStateException closed) {
                        return;
                    }
                }
            });
            closer.join(20000);
            eq(accepted.get(), payout.get());
        });

        test("opposite transfers between two accounts neither deadlock nor lose money", () -> {
            BankAccount a = opened();
            BankAccount b = opened();
            a.deposit(1000);
            b.deposit(1000);
            Thread t1 = new Thread(() -> {
                for (int i = 0; i < 30000; i++) {
                    try {
                        a.transferTo(b, 1);
                    } catch (InsufficientFundsException ignored) {
                        // fine
                    }
                }
            });
            Thread t2 = new Thread(() -> {
                for (int i = 0; i < 30000; i++) {
                    try {
                        b.transferTo(a, 1);
                    } catch (InsufficientFundsException ignored) {
                        // fine
                    }
                }
            });
            t1.setDaemon(true);
            t2.setDaemon(true);
            t1.start();
            t2.start();
            t1.join(20000);
            t2.join(20000);
            check(!t1.isAlive() && !t2.isAlive(), "transfers deadlocked");
            eq(2000L, a.balance() + b.balance());
        });

        test("a ring of transfers keeps the total constant", () -> {
            BankAccount[] ring = new BankAccount[4];
            for (int i = 0; i < ring.length; i++) {
                ring[i] = opened();
                ring[i].deposit(500);
            }
            inParallel(8, () -> {
                for (int i = 0; i < 20000; i++) {
                    int from = i % ring.length;
                    int to = (i * 3 + 1) % ring.length;
                    if (from == to) continue;
                    try {
                        ring[from].transferTo(ring[to], 1 + (i % 3));
                    } catch (InsufficientFundsException ignored) {
                        // fine
                    }
                }
            });
            long total = 0;
            for (BankAccount account : ring) {
                check(account.balance() >= 0, "negative balance");
                total += account.balance();
            }
            eq(2000L, total);
        });

        System.out.println(run + " tests, " + failures + " failures");
        System.exit(failures == 0 ? 0 : 1);
    }
}
