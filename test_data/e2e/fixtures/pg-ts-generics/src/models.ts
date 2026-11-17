// models.ts - label helpers for the orders report.
//
// The parameters are untyped on purpose: the shapes come straight from the
// API and used to be "whatever arrives". If a field is renamed upstream,
// this file finds out at runtime.

export function userLabel(user): string {
  return user.name + " <" + user.email + ">";
}

export function orderTotalLabel(order): string {
  const total = order.lines.reduce(
    (sum, line) => sum + line.unitPrice * line.qty,
    0
  );
  return order.id + ": " + total.toFixed(2) + " " + order.currency;
}

export const CURRENCY_SYMBOLS: { [code: string]: string } = {
  EUR: "\u20ac",
  USD: "$",
  PLN: "z\u0142",
};
