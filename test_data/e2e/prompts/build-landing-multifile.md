Build a small product landing page as THREE separate files in the project root:
- `index.html` - the markup. It must reference the stylesheet and the script as EXTERNAL files:
  `<link rel="stylesheet" href="styles.css">` and a CLASSIC `<script src="app.js"></script>`.
  Do NOT use ES modules (`type="module"`), and do NOT inline the CSS or the JS.
- `styles.css` - the styling.
- `app.js` - the behaviour.

The page must contain a signup form with:
- an email input with `id="email"`,
- a submit button with `id="submit"`,
- an initially-empty message element with `id="msg"`.

In `app.js`, when the submit button is clicked, validate the email. If it is empty or invalid,
write an error message into `#msg`; if it is valid, write a success message into `#msg`. Always
show the result inside `#msg` (do NOT use `alert()`). The page must load with no JavaScript errors.

Use only vanilla HTML/CSS/JS - no frameworks and no network requests.
