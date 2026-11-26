# headers-app

The support team's status portal: a knowledge base search, a sign-in page, a dashboard and an
account page, plus a small echo API that the front-end uses. Node 20 with `node:http` only, no
npm packages, no database.

## Run

    node server.js            # PORT environment variable, default 3000
    PORT=0 node server.js     # prints `listening <port>` with the port the OS chose

The smoke scripts wait for the `listening <port>` line on stdout, so keep it.

## Demo account

    user      dana
    password  welcome2020

## Pages and endpoints

- `/` - knowledge base search (`?q=` is the search term)
- `/login` - sign-in form; `?next=` is where to go after signing in (default `/dashboard`)
- `/dashboard`, `/account` - only with the `sid` cookie that the sign-in sets
- `/api/echo` - `GET ?message=...` or `POST` with JSON `{"message": "..."}`; answers JSON
- `/static/app.js`, `/static/style.css` - the front-end files from `public/`

## Files

- `server.js` - the whole app
- `public/` - front-end files served under `/static/`
- `check.js` - the acceptance check of the current security review. It starts `server.js` on a
  free port, sends real requests and compares the answers with the review's requirements. Run it
  with `node check.js`; it does not need anything else and stops the server it started.

Known issues are tracked in the security review; please do not edit `check.js`, it is the
reference for what "fixed" means.
