Create a complete single self-contained file `wordle_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a Wordle clone.

Embed the word data in the file: an answer list of at least 200 common five-letter words from which the
random answer is drawn, plus a larger valid-guess dictionary of several thousand words, or a permissive
validator that accepts any plausible five-letter input; the lists may overlap but the answer list must
stay common words. The player gets exactly six guesses, typed on the physical keyboard or an on-screen
keyboard and submitted with Enter, Backspace deletes a letter, and guessing is only possible while the
game is active. A guess that is not five letters or is not in the dictionary is rejected with a visible
message and a row shake, and it never consumes a turn.

Coloring must follow the real rules: green for a correct letter in the correct position, yellow for a
letter present elsewhere in the answer, gray otherwise, with duplicated letters handled correctly by
matching greens first and then consuming yellow matches left to right, so a second occurrence only turns
yellow if the answer truly contains another copy of that letter. The on-screen and physical keyboards
reflect the best known state per letter, green over yellow over gray, and never regress. A hard mode
toggle enforces the revealed hints: green letters must stay in place, yellow letters must be reused
somewhere, and violations are rejected with an explanation.

After the sixth failed guess, reveal the answer in a banner before the game-over state; on a win, show a
congratulation banner. Persist statistics in localStorage across reloads: games played, win rate, current
streak and best streak, shown in a small stats panel. Add a share button that copies a spoiler-free emoji
grid of the guesses with a header line to the clipboard and confirms the copy.

Every visible control must work; no fake simulation states. Deliver the one file `wordle_model_01.html`.
