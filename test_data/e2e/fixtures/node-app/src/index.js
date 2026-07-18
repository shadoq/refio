const { createLogger } = require("./logger");
const { placeOrder } = require("./orders");

const log = createLogger("main");

log.info("starting");
placeOrder(1, 42);
