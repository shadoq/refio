'use strict';

/**
 * Star Dodger - the mini canvas game on the arcade page.
 *
 * The page creates one instance and reuses it: start() runs a round, stop()
 * returns the player to the menu, start() begins the next round.
 */

const SHIP_STEP = 12;
const STAR_SPEED = 3;
const CANVAS_W = 480;
const CANVAS_H = 320;
const STAR_COUNT = 6;

class StarDodger {
  constructor() {
    this.canvas = document.getElementById('game');
    this.ctx = this.canvas.getContext('2d');
    this.running = false;
    this.frameId = null;
    this.score = 0;
    this.ship = { x: CANVAS_W / 2, y: CANVAS_H - 40 };
    this.stars = [];
  }

  /** Begin a round: reset the board, hook up input, start the loop. */
  start() {
    if (this.running) {
      return;
    }
    this.running = true;
    this.score = 0;
    this.ship = { x: CANVAS_W / 2, y: CANVAS_H - 40 };
    this.stars = [];
    for (let i = 0; i < STAR_COUNT; i += 1) {
      this.stars.push(this.spawnStar());
    }
    // Steering: arrow keys move the ship while the round is running.
    document.addEventListener('keydown', (e) => this.onKey(e));
    this.frameId = requestAnimationFrame(() => this.tick());
  }

  /** End the round and return to the menu. */
  stop() {
    if (!this.running) {
      return;
    }
    this.running = false;
    // The loop checks `running` before drawing, so it winds down by itself.
    this.frameId = null;
  }

  /** Map the arrow keys to ship movement. */
  onKey(event) {
    if (event.key === 'ArrowLeft') {
      this.ship.x = Math.max(0, this.ship.x - SHIP_STEP);
    } else if (event.key === 'ArrowRight') {
      this.ship.x = Math.min(CANVAS_W, this.ship.x + SHIP_STEP);
    } else if (event.key === 'ArrowUp') {
      this.ship.y = Math.max(0, this.ship.y - SHIP_STEP);
    } else if (event.key === 'ArrowDown') {
      this.ship.y = Math.min(CANVAS_H, this.ship.y + SHIP_STEP);
    }
  }

  spawnStar() {
    return {
      x: Math.random() * CANVAS_W,
      y: -10,
      size: 4 + Math.random() * 6,
    };
  }

  /** One animation frame: advance the world, then draw it. */
  tick() {
    this.update();
    this.draw();
    this.frameId = requestAnimationFrame(() => this.tick());
  }

  update() {
    this.score += 1;
    for (const star of this.stars) {
      star.y += STAR_SPEED;
    }
    this.stars = this.stars.filter((star) => star.y < CANVAS_H + 10);
    while (this.stars.length < STAR_COUNT) {
      this.stars.push(this.spawnStar());
    }
  }

  draw() {
    const { ctx } = this;
    ctx.clearRect(0, 0, CANVAS_W, CANVAS_H);
    ctx.fillStyle = '#101020';
    ctx.fillRect(0, 0, CANVAS_W, CANVAS_H);
    ctx.fillStyle = '#ffe066';
    ctx.fillRect(this.ship.x - 10, this.ship.y - 8, 20, 16);
    ctx.fillStyle = '#ffffff';
    for (const star of this.stars) {
      ctx.fillRect(star.x, star.y, star.size, star.size);
    }
    ctx.fillStyle = '#9be3ff';
    ctx.fillText(String(this.score), 8, 16);
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = { StarDodger };
}
