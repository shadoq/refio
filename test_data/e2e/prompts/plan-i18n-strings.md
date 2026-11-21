`src/i18n.js` already provides `t(key)` reading `locales/en.json`, but three components still hard-code their texts: `src/components/Header.js`, `src/components/Footer.js` and `src/components/LoginForm.js`.

Plan moving every hard-coded text into `locales/en.json` and using `t(key)` in the components, then adding a Polish translation `locales/pl.json` with the same keys.

New files may be only under `locales/` and `tests/`.

Do not change any file: this is planning only.

Answer with the plan as a numbered list (`1.`, `2.`, ...), one step per item, in the order the work must be done. In every step name the exact files it touches. Name only files that exist in the project, or new files in the places listed above.
