'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { DuckpinGame } = require('./duckpin');

function play(rolls) {
  const game = new DuckpinGame();
  for (const pins of rolls) game.roll(pins);
  return game;
}

const repeat = (value, times) => Array(times).fill(value);

test('a game of 30 gutter balls scores 0', () => {
  assert.equal(play(repeat(0, 30)).score(), 0);
});

test('three rolls of one pin in every frame score 30', () => {
  assert.equal(play(repeat(1, 30)).score(), 30);
});

test('twelve strikes make the perfect game of 300', () => {
  assert.equal(play(repeat(10, 12)).score(), 300);
});

test('a spare made with the second roll scores the next single roll as a bonus', () => {
  assert.equal(play([3, 7, 5, ...repeat(0, 2), ...repeat(0, 24)]).score(), 20);
});

test('a spare made with the third roll is a spare too', () => {
  // frame 1: 3 + 3 + 4 = 10 on the third roll, bonus is the next roll (6); frame 2: 6
  assert.equal(play([3, 3, 4, 6, 0, 0, ...repeat(0, 24)]).score(), 22);
});

test('a strike scores the next two rolls as a bonus', () => {
  // frame 1: 10 + 2 + 3; frame 2: 2 + 3 + 4
  assert.equal(play([10, 2, 3, 4, ...repeat(0, 24)]).score(), 24);
});

test('the strike bonus can come from two different frames', () => {
  // frame 1: 10 + 10 + 3 = 23; frame 2: 10 + 3 + 2 = 15; frame 3: 3 + 2 + 0 = 5
  assert.equal(play([10, 10, 3, 2, 0, ...repeat(0, 21)]).score(), 43);
});

test('a strike followed by a spare', () => {
  // frame 1: 10 + 4 + 6 = 20; frame 2: 10 + next roll 5 = 15; frame 3: 5
  assert.equal(play([10, 4, 6, 5, 0, 0, ...repeat(0, 21)]).score(), 40);
});

test('an open frame has three rolls and no bonus', () => {
  // frame 1: 1 + 2 + 3 = 6 (open), frame 2: 9 (open)
  assert.equal(play([1, 2, 3, 4, 4, 1, ...repeat(0, 24)]).score(), 15);
});

test('spares of two rolls in every frame with a bonus roll', () => {
  assert.equal(play(repeat(5, 21)).score(), 150);
});

test('spares of three rolls in every frame with a bonus roll', () => {
  assert.equal(play([...repeat([5, 0, 5], 10).flat(), 5]).score(), 150);
});

test('a complete mixed game', () => {
  const rolls = [10, 3, 7, 4, 2, 4, 0, 0, 0, 9, 1, 10, 10, 5, 2, 1, 6, 4, 10, 7, 3];
  assert.equal(play(rolls).score(), 154);
});

test('tenth frame strike with two bonus strikes', () => {
  assert.equal(play([...repeat(0, 27), 10, 10, 10]).score(), 30);
});

test('tenth frame strike, then a bonus that limits the next bonus roll', () => {
  const game = play([...repeat(0, 27), 10, 3]);
  assert.throws(() => game.roll(8), { message: 'Pin count exceeds pins on the lane' });
  game.roll(7);
  assert.equal(game.score(), 20);
});

test('tenth frame strike, first bonus strike gives a fresh lane for the second', () => {
  const game = play([...repeat(0, 27), 10, 10]);
  game.roll(6);
  assert.equal(game.score(), 26);
});

test('tenth frame spare on the second roll gets one fresh-lane bonus roll', () => {
  const game = play([...repeat(0, 27), 4, 6]);
  assert.throws(() => game.score(), { message: 'Score cannot be taken until the end of the game' });
  game.roll(10);
  assert.equal(game.score(), 20);
});

test('tenth frame spare on the third roll gets one bonus roll', () => {
  const game = play([...repeat(0, 27), 2, 3, 5]);
  assert.throws(() => game.score(), { message: 'Score cannot be taken until the end of the game' });
  game.roll(7);
  assert.equal(game.score(), 17);
});

test('tenth frame open: three rolls end the game with no bonus', () => {
  const game = play([...repeat(0, 27), 1, 1, 1]);
  assert.equal(game.score(), 3);
  assert.throws(() => game.roll(0), { message: 'Cannot roll after game is over' });
});

test('after the last bonus roll the game is over', () => {
  const game = play([...repeat(0, 27), 10, 10, 10]);
  assert.throws(() => game.roll(0), { message: 'Cannot roll after game is over' });
  const spare = play([...repeat(0, 27), 5, 5, 1]);
  assert.throws(() => spare.roll(0), { message: 'Cannot roll after game is over' });
});

test('a roll after the end gets the game-over message even when it is also invalid', () => {
  const game = play(repeat(0, 30));
  assert.throws(() => game.roll(11), { message: 'Cannot roll after game is over' });
});

test('invalid pin counts are rejected', () => {
  for (const bad of [-1, 11, 1.5, NaN, '3', null, undefined]) {
    const game = new DuckpinGame();
    assert.throws(() => game.roll(bad), { message: 'Pins must be an integer between 0 and 10' });
  }
});

test('more pins than are standing in the frame are rejected', () => {
  const game = new DuckpinGame();
  game.roll(6);
  assert.throws(() => game.roll(5), { message: 'Pin count exceeds pins on the lane' });
  game.roll(2);
  assert.throws(() => game.roll(3), { message: 'Pin count exceeds pins on the lane' });
});

test('a rejected roll is not recorded', () => {
  const game = new DuckpinGame();
  game.roll(6);
  assert.throws(() => game.roll(5));
  game.roll(4); // spare on the second roll
  for (const pins of [2, ...repeat(0, 2), ...repeat(0, 24)]) game.roll(pins);
  // frame 1: 10 + 2, frame 2: 2
  assert.equal(game.score(), 14);
});

test('the lane is fresh at the start of every frame', () => {
  const game = play([9, 1]);
  assert.doesNotThrow(() => game.roll(10));
});

test('score cannot be taken before the game is over', () => {
  assert.throws(() => new DuckpinGame().score(), {
    message: 'Score cannot be taken until the end of the game',
  });
  assert.throws(() => play(repeat(0, 29)).score(), {
    message: 'Score cannot be taken until the end of the game',
  });
});

test('an unfinished tenth frame with a strike and one bonus roll is not scoreable', () => {
  assert.throws(() => play([...repeat(0, 27), 10, 10]).score(), {
    message: 'Score cannot be taken until the end of the game',
  });
});

test('score can be read repeatedly', () => {
  const game = play(repeat(10, 12));
  assert.equal(game.score(), 300);
  assert.equal(game.score(), 300);
});
