# Instructions

Pick the winning hand(s) in a game of short-deck poker. Implement `ShortDeckPoker.bestHands` in `src/ShortDeckPoker.java`.

## The deck

The deck has 36 cards: ranks `6 7 8 9 T J Q K A` in suits `C D H S`. There are no 2s, 3s, 4s or 5s. A card is written as rank then suit, for example `TH` or `6S`. A hand is five cards separated by whitespace, for example `"6H 7D 8C 9S TH"`.

## Hand ranking, lowest to highest

1. high card
2. one pair
3. two pair
4. three of a kind
5. straight
6. full house
7. flush
8. four of a kind
9. straight flush

Note that a **flush beats a full house** in this variant. A straight flush that ends in the ace is not treated specially: it is just the highest straight flush.

### Straights

A straight is five cards of consecutive ranks in the order `6 7 8 9 T J Q K A`. The ace can also be low: `A 6 7 8 9` is a straight, the **lowest** one, and its high card is the 9, so `6 7 8 9 T` beats it. There is no other wrap-around: `Q K A 6 7` is not a straight.

### Breaking ties inside a category

- Pairs, two pair, three and four of a kind, full house: compare the rank of the biggest group first (the triple of a full house, the higher pair of two pair), then the next group, and so on; single cards left over (kickers) are compared from high to low.
- High card and flush: compare all five ranks from high to low.
- Straights and straight flushes: compare the high card (9 for `A 6 7 8 9`).
- Suits never break ties.

## The function

`bestHands(List<String> hands)` returns the winning hands, exactly as they were given (same strings, no trimming or re-ordering), in their original order. When several hands are exactly equal in strength all of them are returned. An empty list gives an empty list.

It throws `IllegalArgumentException` when any hand does not have exactly five cards, when a card is not in the 36-card deck (a rank such as `5` or `10`, an unknown suit, lower case letters), or when the same card appears twice inside one hand. Different hands may share cards. The input list is not modified.
