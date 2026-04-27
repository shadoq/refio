package pl.jclab.refio.ui.theme

import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Font

/**
 * Centralized theme constants for Refio UI.
 *
 * All colors are dynamic and respond to IDE theme changes (light/dark).
 * All spacing values are scaled for HiDPI displays.
 *
 * Usage:
 * ```kotlin
 * label.foreground = LCATheme.descriptionForeground
 * panel.border = JBUI.Borders.empty(LCATheme.padding)
 * ```
 */
object LCATheme {

    // ==================== COLORS ====================

    /** Background color for panels */
    val backgroundColor: Color get() = UIUtil.getPanelBackground()

    /** Background color for list items */
    val listBackground: Color get() = UIUtil.getListBackground()

    /** Background color for headers */
    val headerBackground: Color get() = UIUtil.getListBackground()

    /** Background color for editor (code editing areas) */
    val editorBackground: Color get() = EditorColorsManager.getInstance().globalScheme.defaultBackground

    val editorForeground: Color get() = EditorColorsManager.getInstance().globalScheme.defaultForeground

    /** Foreground color for description/helper text */
    val descriptionForeground: Color get() = JBUI.CurrentTheme.ContextHelp.FOREGROUND

    /** Foreground color for disabled elements */
    val disabledForeground: Color get() = JBUI.CurrentTheme.Label.disabledForeground()

    /** Foreground color for labels */
    val labelForeground: Color get() = UIUtil.getLabelForeground()

    /** Foreground color for disabled labels */
    val labelDisabledForeground: Color get() = UIUtil.getLabelDisabledForeground()

    /** Color for header inactive state */
    val headerInactiveColor: Color get() = UIUtil.getHeaderInactiveColor()

    /** Background color for inactive text fields */
    val inactiveTextFieldBackground: Color get() = UIUtil.getInactiveTextFieldBackgroundColor()

    /** Border color for separators and borders */
    val borderColor: Color get() = JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground()

    /** Accent color for links and highlights */
    val accentColor: Color get() = JBUI.CurrentTheme.Link.Foreground.ENABLED

    /** Color for success status */
    val successColor: Color get() = JBColor(Color(0x59A869), Color(0x499C54))

    /** Color for error status */
    val errorColor: Color get() = JBColor(Color(0xDB5860), Color(0xC75450))

    /** Color for warning status */
    val warningColor: Color get() = JBColor(Color(0xE5A840), Color(0xBE9117))

    /** Color for info status (blue) */
    val infoColor: Color get() = JBColor(Color(0x2196F3), Color(0x6AB7FF))

    /** Color for neutral/idle status (gray) */
    val neutralColor: Color get() = JBColor(Color(0x757575), Color(0x9E9E9E))

    /** Color for progress bar foreground */
    val progressColor: Color get() = JBColor(Color(0x4CAF50), Color(0x81C784))

    /** Gray color for disabled/secondary elements */
    val grayColor: Color get() = JBColor.GRAY

    // ==================== STEP STATUS COLORS ====================

    /** Pending/Planned step background */
    val stepPendingBackground: Color get() = JBColor(Color(0xFF, 0xF3, 0xE0), Color(0x5D, 0x4E, 0x37))

    /** Running step background */
    val stepRunningBackground: Color get() = JBColor(Color(0xE8, 0xF5, 0xE9), Color(0x2E, 0x4E, 0x3A))

    /** Success step background */
    val stepSuccessBackground: Color get() = JBColor(Color(0xC8, 0xE6, 0xC9), Color(0x1B, 0x5E, 0x20))

    /** Failed step background */
    val stepFailedBackground: Color get() = JBColor(Color(0xFF, 0xEB, 0xEE), Color(0x5F, 0x1E, 0x1E))

    /** Skipped step background */
    val stepSkippedBackground: Color get() = JBColor(Gray._240, Gray._80)

    /** Canceled step background */
    val stepCanceledBackground: Color get() = JBColor(Gray._224, Gray._64)

    /** New step background */
    val stepNewBackground: Color get() = JBColor(Color(0xE3, 0xF2, 0xFD), Color(0x1E, 0x3A, 0x5F))

    /** White color for text on dark backgrounds */
    val whiteColor: Color get() = JBColor.WHITE

    /** Red color for errors */
    val redColor: Color get() = JBColor.RED

    /** Light gray color for secondary elements */
    val lightGrayColor: Color get() = JBColor.LIGHT_GRAY

    /** Mono text color (for code blocks and monospace text) */
    val monoTextColor: Color get() = JBColor(Gray._85, Gray._187)

    /** Darkened background for panels */
    val darkenedBackground: Color get() = backgroundColor.darker()

    /** Info highlight background (light blue) */
    val infoHighlightBackground: Color get() = JBColor(Color(0xE7, 0xF3, 0xFF), Color(0x2D, 0x3F, 0x5F))

    /** Info highlight border (blue with alpha) */
    val infoHighlightBorder: Color get() = JBColor(Color(0x00, 0x7A, 0xCC, 60), Color(0x58, 0x9D, 0xF6, 60))

    /** Info highlight foreground (darker blue) */
    val infoHighlightForeground: Color get() = JBColor(Color(0x00, 0x5A, 0x9C), Color(0x58, 0x9D, 0xF6))

    /** Error highlight background (light red) */
    val errorHighlightBackground: Color get() = JBColor(Color(0xFF, 0xEB, 0xEE), Color(0x5F, 0x2D, 0x2D))

    /** Error highlight foreground (darker red) */
    val errorHighlightForeground: Color get() = JBColor(Color(0xC6, 0x28, 0x28), Color(0xEF, 0x9A, 0x9A))

    /** Subtle separator color (very light gray) */
    val subtleSeparatorColor: Color get() = JBColor(Gray._220, Gray._60)

    // ==================== CODE BLOCK COLORS ====================

    /** Code block background (dark gray) */
    val codeBlockBackground: Color get() = JBColor(Gray._43, Gray._43)

    /** Code block foreground text */
    val codeBlockForeground: Color get() = JBColor(Color(0xA9, 0xB7, 0xC6), Color(0xA9, 0xB7, 0xC6))

    /** Code block metadata panel background (subtle tint) */
    val codeMetadataBackground: Color get() = JBColor(Color(0xE8, 0xF0, 0xFE), Color(0x1E, 0x26, 0x30))

    /** Code block highlight color 1 (blue) */
    val codeBlockHighlight1: Color get() = JBColor(Color(0x88, 0xAA, 0xFF), Color(0x88, 0xAA, 0xFF))

    /** Code block highlight color 2 (lighter blue) */
    val codeBlockHighlight2: Color get() = JBColor(Color(0xAA, 0xCC, 0xFF), Color(0xAA, 0xCC, 0xFF))

    /** Code block highlight color 3 (purple-blue) */
    val codeBlockHighlight3: Color get() = JBColor(Color(0x88, 0x88, 0xFF), Color(0x88, 0x88, 0xFF))

    /** Code block comment color (gray) */
    val codeBlockComment: Color get() = JBColor(Gray._187, Gray._187)

    // ==================== STATUS COLORS ====================

    /** Status color: success/running (green) */
    val statusGreen: Color get() = JBColor.GREEN

    /** Status color: error/failed (red) */
    val statusRed: Color get() = JBColor.RED

    /** Status color: info/chat (blue) */
    val statusBlue: Color get() = JBColor.BLUE

    /** Status color: warning/agent (orange) */
    val statusOrange: Color get() = JBColor.ORANGE

    // ==================== CHAT BUBBLE COLORS ====================
    // Updated to match landing page design (.tool-card and .tool-message)

    /** User message bubble background */
    // Continue-like: subtle tint, not flat gray
    val userBubbleBackground: Color get() = JBColor(Color(0xF1, 0xF6, 0xFF), Color(0x23, 0x2D, 0x3A))

    /** User message bubble foreground */
    val userBubbleForeground: Color get() = JBColor(Color(0x1565C0), Color(0xBBDEFB))

    /** Assistant message bubble background */
    val assistantBubbleBackground: Color get() = JBColor(Color(0xFF, 0xF7, 0xF0), Color(0x2B, 0x26, 0x24))

    /** Assistant message bubble foreground */
    val assistantBubbleForeground: Color get() = UIUtil.getLabelForeground()

    /** System message bubble background */
    val systemBubbleBackground: Color get() = JBColor(Color(0xF6, 0xF7, 0xFA), Color(0x1F, 0x22, 0x26))

    /** System message bubble foreground */
    val systemBubbleForeground: Color get() = JBUI.CurrentTheme.ContextHelp.FOREGROUND

    /** Tool call bubble background */
    val toolBubbleBackground: Color get() = JBColor(Color(0xF0, 0xF4, 0xF8), Color(0x24, 0x28, 0x2E))

    /** Tool call header background */
    val toolHeaderBackground: Color get() = JBColor(Color(0xE8, 0xEC, 0xF0), Color(0x2A, 0x2E, 0x34))

    /** Tool call name foreground */
    val toolNameForeground: Color get() = JBColor(Color(0x19, 0x76, 0xD2), Color(0x64, 0xB5, 0xF6))

    /** Muted text foreground */
    val mutedForeground: Color get() = JBUI.CurrentTheme.ContextHelp.FOREGROUND

    /** Tool inline compact background */
    val toolInlineBackground: Color get() = JBColor(Color(0xF5, 0xF7, 0xF9), Color(0x22, 0x26, 0x2A))

    /** Tool result bubble background (lighter, subtle) */
    val toolResultBackground: Color get() = JBColor(Color(0xFA, 0xFC, 0xFE), Color(0x1E, 0x22, 0x26))

    /** Tool result bubble foreground */
    val toolResultForeground: Color get() = JBColor(Color(0x3D, 0x3D, 0x3D), Color(0xC0, 0xC0, 0xC0))

    /** Conversation summary bubble background */
    val summaryBubbleBackground: Color get() = JBColor(Color(0xFF, 0xF4, 0xE6), Color(0x2F, 0x27, 0x22))

    /** Conversation summary bubble foreground */
    val summaryBubbleForeground: Color get() = JBColor(Color(0x5D4037), Color(0xFFE0B2))

    /** Approval bubble background (yellow/orange) */
    val approvalBubbleBackground: Color get() = JBColor(Color(0xFF, 0xF3, 0xD1), Color(0x2E, 0x2B, 0x20))

    /** Question bubble background (purple) */
    val questionBubbleBackground: Color get() = JBColor(Color(0xF5, 0xEE, 0xFF), Color(0x2B, 0x24, 0x33))

    // ==================== MODE BADGE COLORS ====================

    /** Chat mode badge background */
    val chatModeBadgeBackground: Color get() = JBColor(Color(0xE3F2FD), Color(0x2D4A5E))

    /** Chat mode badge foreground */
    val chatModeBadgeForeground: Color get() = JBColor(Color(0x1565C0), Color(0x90CAF9))

    /** Plan mode badge background */
    val planModeBadgeBackground: Color get() = JBColor(Color(0xFFF8E1), Color(0x4A4033))

    /** Plan mode badge foreground */
    val planModeBadgeForeground: Color get() = JBColor(Color(0xF57C00), Color(0xFFCC80))

    /** Agent mode badge background */
    val agentModeBadgeBackground: Color get() = JBColor(Color(0xFFEBEE), Color(0x4A3333))

    /** Agent mode badge foreground */
    val agentModeBadgeForeground: Color get() = JBColor(Color(0xC62828), Color(0xEF9A9A))

    // ==================== TOOLBAR BUTTON COLORS ====================
    // Matching landing page .tool-button style

    /** New Session button background (blue with transparency) */
    val newSessionButtonBackground: Color get() = JBColor(
        Color(0x49, 0xC7, 0xFF, 30),  // Light mode: rgba(73, 199, 255, 0.12)
        Color(0x49, 0xC7, 0xFF, 30)   // Dark mode: rgba(73, 199, 255, 0.12)
    )

    /** New Session button border (blue) */
    val newSessionButtonBorder: Color get() = JBColor(
        Color(0x49, 0xC7, 0xFF, 102),  // Light mode: rgba(73, 199, 255, 0.4)
        Color(0x49, 0xC7, 0xFF, 102)   // Dark mode: rgba(73, 199, 255, 0.4)
    )

    /** New Session button foreground (light blue) */
    val newSessionButtonForeground: Color get() = JBColor(
        Color(0x19, 0x76, 0xD2),       // Light mode: darker blue
        Color(0xBF, 0xE6, 0xFF)        // Dark mode: #bfe6ff
    )


    // ==================== SPACING ====================

    /** Extra small spacing (2px scaled) */
    val spacingXs: Int get() = JBUI.scale(2)

    /** Small spacing (4px scaled) */
    val spacingSm: Int get() = JBUI.scale(4)

    /** Medium spacing (8px scaled) - default padding */
    val spacing: Int get() = JBUI.scale(8)

    /** Large spacing (12px scaled) */
    val spacingLg: Int get() = JBUI.scale(12)

    /** Extra large spacing (16px scaled) - section margins */
    val spacingXl: Int get() = JBUI.scale(16)

    /** Standard padding (8px scaled) */
    val padding: Int get() = spacing

    /** Standard margin (16px scaled) */
    val margin: Int get() = spacingXl

    /** Standard inner padding for settings panels and titled sections */
    val settingsPanelPadding: Int get() = padding

    /** Standard gap between elements (4px scaled) */
    val gap: Int get() = spacingSm

    /** Border radius for chat bubbles and rounded elements (14px scaled - updated to match landing page) */
    val bubbleRadius: Int get() = JBUI.scale(14)

    /** Button border radius for toolbar buttons (6px scaled - matching landing page .tool-button) */
    val buttonRadius: Int get() = JBUI.scale(6)

    // ==================== FONTS ====================

    /** Bold label font */
    val headerFont: Font get() = JBUI.Fonts.label().asBold()

    /** Regular label font */
    val bodyFont: Font get() = JBUI.Fonts.label()

    /** Small font for captions */
    val smallFont: Font get() = JBUI.Fonts.smallFont()

    /** Monospace font for code */
    val monoFont: Font get() = Font(Font.MONOSPACED, Font.PLAIN, JBUI.Fonts.label().size)

    /** Editor font from IntelliJ settings */
    val editorFont: Font get() = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)

    /** Bold font (alias for headerFont) */
    val boldFont: Font get() = headerFont

    /** Italic font for emphasis */
    val italicFont: Font get() = bodyFont.deriveFont(Font.ITALIC)

    /** Large bold font for prominent headers */
    val largeBoldFont: Font get() = headerFont.deriveFont(headerFont.size + 2f)

    /** Small bold font for compact headers */
    val smallBoldFont: Font get() = smallFont.deriveFont(Font.BOLD)

    // ==================== BORDERS ====================

    /** Empty border with no padding */
    fun emptyBorder() = JBUI.Borders.empty()

    /** Empty border with uniform padding on all sides */
    fun paddedBorder(padding: Int) = JBUI.Borders.empty(padding)

    /** Empty border with vertical and horizontal padding */
    fun paddedBorder(vertical: Int, horizontal: Int) = JBUI.Borders.empty(vertical, horizontal)

    /** Empty border with specific padding for each side */
    fun paddedBorder(top: Int, left: Int, bottom: Int, right: Int) = JBUI.Borders.empty(top, left, bottom, right)

    /** Custom line border with specified color and thickness */
    fun customLineBorder(color: Color, thickness: Int = 1) = JBUI.Borders.customLine(color, thickness)

    /** Custom line border on specific sides */
    fun customLineBorder(color: Color, top: Int = 0, left: Int = 0, bottom: Int = 0, right: Int = 0) =
        JBUI.Borders.customLine(color, top, left, bottom, right)

    /** Compound border (outer + inner) */
    fun compoundBorder(outer: javax.swing.border.Border, inner: javax.swing.border.Border) =
        JBUI.Borders.compound(outer, inner)

    /** Titled border with theme-aware styling */
    fun createTitledBorder(title: String) = javax.swing.BorderFactory.createTitledBorder(
        JBUI.Borders.customLine(borderColor), title
    ).apply {
        titleColor = com.intellij.util.ui.UIUtil.getLabelForeground()
    }

    /** Standard titled border used by settings panels */
    fun createSettingsBorder(title: String, innerPadding: Int = settingsPanelPadding) = compoundBorder(
        createTitledBorder(title),
        paddedBorder(innerPadding)
    )

    /** Standard section border (line + padding) */
    val sectionBorder get() = compoundBorder(
        customLineBorder(borderColor, 1),
        paddedBorder(spacingLg)
    )

    /** Standard card border (line + padding) */
    val cardBorder get() = compoundBorder(
        customLineBorder(borderColor, 1),
        paddedBorder(padding)
    )

    /** Standard input border */
    val inputBorder get() = customLineBorder(borderColor, 1)

    // ==================== INSETS (for GridBagConstraints) ====================

    /** Zero insets (no spacing) */
    val insetsNone get() = JBUI.emptyInsets()

    /** Small insets (4px all sides) */
    val insetsSmall get() = JBUI.insets(spacingSm)

    /** Medium insets (8px all sides) */
    val insetsMedium get() = JBUI.insets(padding)

    /** Large insets (16px all sides) */
    val insetsLarge get() = JBUI.insets(margin)

    /** Extra large insets (24px all sides) */
    val insetsXLarge get() = JBUI.insets(24)

    /** Standard GridBag insets (12, 4, 4, 4) - most common in forms */
    val insetsGridBagDefault get() = JBUI.insets(12, 4, 4, 4)

    /** Large GridBag insets (16, 8, 8, 8) */
    val insetsGridBagLarge get() = JBUI.insets(16, 8, 8, 8)

    /** Indented details insets (0, 32, 8, 8) - for nested content */
    val insetsDetailsIndented get() = JBUI.insets(0, 32, 8, 8)

    /** Top medium insets (8, 0, 0, 0) */
    val insetsTopMedium get() = JBUI.insetsTop(8)

    /** Top small insets (4, 0, 0, 0) */
    val insetsTopSmall get() = JBUI.insetsTop(4)

    /** Bottom medium insets (0, 0, 8, 0) */
    val insetsBottomMedium get() = JBUI.insetsBottom(8)

    /** Form field insets (8, 4, 8, 4) */
    val insetsFormField get() = JBUI.insets(8, 4)

    /** Dialog insets (16, 4, 4, 4) */
    val insetsDialogField get() = JBUI.insets(16, 4, 4, 4)

    /** Top large bottom none (16, 0, 8, 0) */
    val insetsTopLargeBottomMedium get() = JBUI.insets(16, 0, 8, 0)

    // ==================== PARAMETERIZED INSETS FUNCTIONS ====================

    /** Custom insets with all four values */
    fun insets(top: Int, left: Int, bottom: Int, right: Int) = JBUI.insets(top, left, bottom, right)

    /** Top-only insets */
    fun insetsTop(top: Int) = JBUI.insetsTop(top)

    /** Bottom-only insets */
    fun insetsBottom(bottom: Int) = JBUI.insetsBottom(bottom)

    /** Right-only insets */
    fun insetsRight(right: Int) = JBUI.insetsRight(right)

    /** Left-only insets */
    fun insetsLeft(left: Int) = JBUI.insetsLeft(left)

    // ==================== UTILITIES ====================

    /** Check if current theme is dark */
    val isDark: Boolean get() = JBColor.isBright().not()
}
