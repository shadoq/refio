#!/usr/bin/env node
'use strict';

/*
 * Acceptance gate for the Star Dodger lifecycle fix.
 *
 * Drives start()/stop() cycles against a stubbed document and a stubbed
 * requestAnimationFrame, and counts what the game leaves behind: keydown
 * listeners, scheduled frames and ship movement after stop(). Exit 0 only when
 * every check passes.
 *
 * This file is part of the scenario definition - editing it is not a fix.
 *
 * Run: node checks.js
 */

// --- browser stubs --------------------------------------------------------------
const listeners = []; // { type, fn } in registration order

const documentStub = {
  getElementById() {
    return canvasStub;
  },
  addEventListener(type, fn) {
    listeners.push({ type, fn });
  },
  removeEventListener(type, fn) {
    const i = listeners.findIndex((l) => l.type === type && l.fn === fn);
    if (i !== -1) {
      listeners.splice(i, 1);
    }
  },
};

let clearRects = 0;
const ctxStub = {
  clearRect() {
    clearRects += 1;
  },
  fillRect() {},
  fillText() {},
  fillStyle: null,
};
const canvasStub = { getContext: () => ctxStub, width: 480, height: 320 };

let nextFrameId = 1;
const frames = new Map(); // id -> callback
let rafCalls = 0;
global.document = documentStub;
global.requestAnimationFrame = (cb) => {
  rafCalls += 1;
  const id = nextFrameId++;
  frames.set(id, cb);
  return id;
};
global.cancelAnimationFrame = (id) => {
  frames.delete(id);
};

// Run up to `n` pending animation frames (each tick schedules the next).
function pump(n) {
  for (let i = 0; i < n; i += 1) {
    const callbacks = [...frames.values()];
    frames.clear();
    for (const cb of callbacks) {
      cb(Date.now());
    }
  }
}

function keydownListeners() {
  return listeners.filter((l) => l.type === 'keydown');
}

function press(key) {
  for (const l of keydownListeners()) {
    l.fn({ key, preventDefault() {} });
  }
}

const { StarDodger } = require('./game');

const failures = [];

function check(name, ok) {
  console.log((ok ? 'PASS' : 'FAIL') + ' - ' + name);
  if (!ok) {
    failures.push(name);
  }
}

// --- the game animates while a round is running ---------------------------------
const game = new StarDodger();
game.start();
pump(3);
check('the loop draws while the game is running', clearRects >= 3);

// --- keys steer the ship while running ------------------------------------------
const before = game.ship.x;
press('ArrowLeft');
check('arrow keys steer the ship while running', game.ship.x === before - 12);

// --- stopping must tear the round down ------------------------------------------
game.stop();
const rafBefore = rafCalls;
pump(3);
check('stop cancels the animation loop', rafCalls === rafBefore && frames.size === 0);

const shipAfterStop = game.ship.x;
press('ArrowLeft');
check('keys are ignored once the game is stopped', game.ship.x === shipAfterStop);

// --- restarts must not accumulate handlers --------------------------------------
game.start();
const duringRound = keydownListeners().length;
game.stop();
for (let round = 0; round < 2; round += 1) {
  game.start();
  game.stop();
}
check('a running round registers exactly one keydown listener', duringRound === 1);
check('stop tears the keydown listener down again', keydownListeners().length === 0);

// --- a fresh round is still fully playable --------------------------------------
game.start();
pump(2);
press('ArrowRight');
check('a restarted round is playable', game.running === true && clearRects >= 5);
game.stop();

console.log();
if (failures.length > 0) {
  console.log(`checks: ${failures.length} check(s) FAILED: ${failures.join('; ')}`);
  process.exit(1);
}
console.log('checks: all checks passed');
