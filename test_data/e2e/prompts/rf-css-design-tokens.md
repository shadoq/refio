`styles.css` is the marketing site's stylesheet: the same handful of colors
(#4a5568 body text, #2d3748 headings, #3182ce primary blue and friends) and
the same 4/8/12/16/24/32 px spacing values are spelled out inline in rule
after rule, so every palette tweak means a find-and-replace across the whole
file.

Your task:

1. Introduce a `:root` block of custom properties covering the full color
   palette and a spacing scale, plus whatever type, line-height and radius
   tokens earn their keep.
2. Replace every repeated literal in the rules below `:root` with
   `var(--...)` references - colors, paddings, margins, font sizes, radii.
   No rule may keep spelling out the old hex or px literals.
3. Keep the rendered result identical: the same values, the same selectors,
   the same cascade and the same media queries. The breakpoint inside the
   media query can stay a literal (custom properties do not resolve there).
4. Keep it a single stylesheet - no extra files, no preprocessor.

Do not modify the README: the change belongs in `styles.css`.
