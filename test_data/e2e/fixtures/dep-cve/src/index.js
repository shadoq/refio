const express = require("express");
const _ = require("lodash");

const logger = require("pino")();

const app = express();

app.get("/items", (req, res) => {
  const groups = _.groupBy(req.query.tags ? req.query.tags.split(",") : [], (t) => t[0]);
  logger.info({ groups: Object.keys(groups) }, "items grouped");
  res.json(groups);
});

app.listen(8080);
