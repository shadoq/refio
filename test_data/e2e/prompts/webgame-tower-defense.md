Create a complete single self-contained file `tower_defense_model_01.html` (HTML + CSS + JavaScript inline;
no external libraries, CDNs, assets or backend). Build a tower defense game.

The map is a grid, for example 16 by 10 cells, with a fixed enemy path from entrance to exit rendered
visually distinct from the buildable grass cells. Enemies walk cell by cell along the path with visible
health bars, and every kill pays a bounty. Include at least two enemy classes, fast runners and tanky
brutes, mixed by the waves.

Offer at least three tower types with clearly different stats and projectiles: a fast single-target gun, a
long-range slow-firing cannon whose shells deal splash damage in a radius, and a frost tower that slows
enemies for a duration. Each tower can be upgraded two or three levels, every upgrade meaningfully
improving damage, range and fire rate at a rising cost, and a built tower can be sold for a partial
refund. Money comes from kill bounties plus a wave-clear bonus and is shown in the HUD together with
lives and the wave number.

Script the waves as data that scales in enemy count and health, mixing the classes, and announce each
wave. Clicking a tower type in the build bar shows its cost and a range circle preview on the map before
placement, and selecting a built tower shows its range circle plus its upgrade and sell options. Targeting
must prioritize the enemy furthest along the path. An enemy that leaks costs a life, zero lives ends the
game with a game-over screen, and clearing the final wave shows a victory screen.

Provide Start Wave, Pause and a 1x or 2x speed toggle, and persist the best wave reached in localStorage,
displayed on the start and game-over screens.

Every visible control must work; no fake simulation states. Deliver the one file `tower_defense_model_01.html`.
