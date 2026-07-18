const { createLogger } = require("./logger");
const { saveOrder } = require("./db");

const log = createLogger("orders");

function placeOrder(id, total) {
  log.info(`placing order ${id} for ${total}`);
  return saveOrder({ id, total });
}

module.exports = { placeOrder };
