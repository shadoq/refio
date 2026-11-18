# Instructions

Score a game of *three-ball* bowling. The lane has 10 pins, a game has 10 frames, and a player gets up to **three** rolls in each frame instead of two.

## API

`duckpin.js` exports `DuckpinGame` (CommonJS, `module.exports = { DuckpinGame }`). A new game has no rolls.

- `roll(pins)` records one roll.
- `score()` returns the total score of a finished game.

## Frames

In a frame the pins that are already down stay down until the frame ends. A roll can knock down at most the pins still standing in the frame. A frame ends when:

- **Strike**: the first roll of the frame knocks down all 10 pins. The frame has one roll.
- **Spare**: the pins knocked down in the frame add up to 10 with the second or the third roll. The frame has two or three rolls.
- **Open frame**: three rolls were made and fewer than 10 pins are down.

A new frame starts with all 10 pins standing.

## Scoring

The score of a frame is the pins knocked down in it plus a bonus:

- strike: the next **two** rolls after the frame;
- spare: the next **one** roll after the frame;
- open frame: no bonus.

The "next rolls" are simply the following rolls of the game, even if they belong to later frames. The score of the game is the sum of the ten frame scores.

## The tenth frame

The tenth frame is played like any other frame. If it ends with a strike the player gets two bonus rolls, if it ends with a spare (on the second or on the third roll) one bonus roll, and no bonus rolls after an open frame. A bonus roll is played on a fresh lane of 10 pins and counts only as the bonus of the tenth frame, it is not a frame of its own. After a strike in the tenth frame, if the first bonus roll is not a strike the second bonus roll can knock down at most the pins left standing after the first one; if the first bonus roll is a strike, the second bonus roll is played on a fresh lane again. The game is over when the tenth frame and its bonus rolls are complete. The highest possible score is 300 (twelve strikes).

## Errors

All errors are `Error` instances with exactly these messages. A rejected roll is not recorded and the game can go on.

| situation | message |
|-----------|---------|
| `pins` is not an integer from 0 to 10 | `Pins must be an integer between 0 and 10` |
| the roll knocks down more pins than are standing | `Pin count exceeds pins on the lane` |
| a roll after the game is over | `Cannot roll after game is over` |
| `score()` before the game is over | `Score cannot be taken until the end of the game` |

When a roll is invalid for two reasons the first row of the table that applies wins, except that a roll after the end of the game always gives the third message.
