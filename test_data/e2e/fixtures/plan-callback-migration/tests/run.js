'use strict';
const { weather } = require('../src/weather');
weather('oslo', (err, data) => { if (err || data.url !== '/weather/oslo') process.exit(1); console.log('ok'); });
