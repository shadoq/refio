package pl.jclab.refio.core.services.turn

import pl.jclab.refio.core.services.TurnLoopConfig as CoreTurnLoopConfig

/**
 * Type alias for TurnLoopConfig from core services package.
 * This allows turn/ package classes to use TurnLoopConfig without circular dependencies.
 */
typealias TurnLoopConfig = CoreTurnLoopConfig
