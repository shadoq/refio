package bank

import java.util.Locale

object Statement {
    fun render(account: Account): String {
        val sb = StringBuilder()
        sb.append("Statement for ").append(account.id).append('\n')
        if (account.entries.isEmpty()) {
            sb.append("(no entries)\n")
        }
        for (e in account.entries) {
            val memo = if (e.memo.length > 12) e.memo.substring(0, 12) else e.memo
            sb.append(String.format(Locale.ROOT, "%-12s %10s %10s", memo, e.amount, e.balanceAfter))
            sb.append('\n')
        }
        sb.append("Balance: ").append(account.balance)
        return sb.toString()
    }
}
