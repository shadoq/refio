Create a complete single self-contained file `chess_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a chess game against a simple AI opponent.

Represent the board internally and generate pseudo-legal moves for every piece, then filter out any move
that would leave the own king in check so that only legal moves can be played. Implement castling with
rights tracking for both sides, legal only when the king is not in check, does not pass through an
attacked square, and the squares between king and rook are empty; en passant capture is available only on
the move immediately following the double pawn push; promotion opens a dialog offering queen, rook,
bishop and knight. Clicking a piece highlights its legal target squares and clicking a target plays the
move.

Detect check, checkmate and stalemate by testing whether any legal move exists, and announce the result
properly; when a position repeats for the third time, announce the repetition. Keep a move list in
standard algebraic notation with disambiguation such as Nbd2, capture marks, O-O and O-O-O, the promotion
equal sign, and the plus and hash suffixes, rendered in a scrollable panel beside the board. Undo reverts
the AI reply and the player move together, and a button flips the board orientation.

The opponent plays black using a minimax or negamax search with alpha-beta pruning at a fixed depth of
two to three plies, evaluating material, piece-square tables and mobility; run it asynchronously with a
thinking indicator so the page never freezes and it answers within a couple of seconds. The AI must always
choose among its generated legal moves, never hardcoded ones.

Persist the board, the side to move, castling rights and the move history in localStorage so a reload
resumes the game, with a New Game button that resets everything. Show whose turn it is at all times.

Every visible control must work; no fake simulation states. Deliver the one file `chess_model_01.html`.
