// Prints the cart total. Right now it prints a raw number of cents; we want it money-formatted.
function cartTotal(items) {
  return items.reduce((sum, it) => sum + it.price * it.qty, 0);
}

const items = [
  { name: "pen", price: 250, qty: 2 }, // prices are in cents
  { name: "pad", price: 500, qty: 1 },
];

console.log("Total: " + cartTotal(items));
