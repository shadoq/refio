Build a tiny client-side single-page application in `index.html` (a single self-contained file is
fine; vanilla JS only - no frameworks, no network requests, no ES modules).

It must provide two hash-based routes and a navigation menu:
- a link `<a href="#/home">Home</a>` and a link `<a href="#/about">About</a>`,
- a content container with `id="view"`.

Use hash-based routing: listen for the `hashchange` event and read `location.hash`. When the route
is `#/home`, render Home content into `#view`; when it is `#/about`, render About content into
`#view`. Clicking a nav link must switch the `#view` content WITHOUT a full page reload, and the two
views must show clearly different text. Default to the Home view on load. No JavaScript errors.
