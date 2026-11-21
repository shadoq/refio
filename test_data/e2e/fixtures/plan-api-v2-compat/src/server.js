'use strict';
const http = require('node:http');
const users = require('./users');

function handle(req, res) {
    if (req.url === '/api/v1/users') {
        res.setHeader('content-type', 'application/json');
        return res.end(JSON.stringify(users));
    }
    res.statusCode = 404;
    res.end();
}

if (require.main === module) http.createServer(handle).listen(3000);
module.exports = { handle };
