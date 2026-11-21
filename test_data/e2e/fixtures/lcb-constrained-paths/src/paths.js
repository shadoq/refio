'use strict';

/**
 * Cheapest route in a directed road network when at most k toll roads may be used.
 *
 * @param {number} n          number of junctions, numbered 0..n-1
 * @param {Array<Array>} edges  roads as [from, to, cost, toll]; toll is 1/true for a toll road
 * @param {number} src        start junction
 * @param {number} dst        destination junction
 * @param {number} k          maximum number of toll roads on the route
 * @returns {number} the minimum total cost, or -1 when no route exists
 */
function shortestPath(n, edges, src, dst, k) {
  throw new Error('not implemented');
}

module.exports = { shortestPath };
