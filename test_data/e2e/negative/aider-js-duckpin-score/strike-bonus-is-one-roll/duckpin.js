'use strict';

const PINS = 10;
const FRAMES = 10;

class DuckpinGame {
  constructor() {
    this._rolls = [];
    this._frames = []; // { start, length, kind } of every finished frame
    this._frameStart = 0;
    this._frameDown = 0;
    this._bonusLeft = 0;
    this._bonusDown = 0; // pins down on the current rack of the bonus phase
    this._over = false;
  }

  roll(pins) {
    if (this._over) throw new Error('Cannot roll after game is over');
    if (!Number.isInteger(pins) || pins < 0 || pins > PINS) {
      throw new Error('Pins must be an integer between 0 and 10');
    }

    if (this._bonusLeft > 0) {
      if (pins > PINS - this._bonusDown) throw new Error('Pin count exceeds pins on the lane');
      this._rolls.push(pins);
      this._bonusLeft -= 1;
      this._bonusDown = this._bonusDown + pins === PINS ? 0 : this._bonusDown + pins;
      if (this._bonusLeft === 0) this._over = true;
      return;
    }

    if (pins > PINS - this._frameDown) throw new Error('Pin count exceeds pins on the lane');
    this._rolls.push(pins);
    this._frameDown += pins;
    const length = this._rolls.length - this._frameStart;

    let kind = null;
    if (this._frameDown === PINS) kind = length === 1 ? 'strike' : 'spare';
    else if (length === 3) kind = 'open';
    if (kind === null) return;

    this._frames.push({ start: this._frameStart, length, kind });
    this._frameStart = this._rolls.length;
    this._frameDown = 0;
    if (this._frames.length === FRAMES) {
      this._bonusLeft = { strike: 2, spare: 1, open: 0 }[kind];
      this._bonusDown = 0;
      if (this._bonusLeft === 0) this._over = true;
    }
  }

  score() {
    if (!this._over) throw new Error('Score cannot be taken until the end of the game');
    let total = 0;
    for (const { start, length, kind } of this._frames) {
      const end = start + length;
      for (let i = start; i < end; i += 1) total += this._rolls[i];
      if (kind === 'strike') total += this._rolls[end];
      if (kind === 'spare') total += this._rolls[end];
    }
    return total;
  }
}

module.exports = { DuckpinGame };
