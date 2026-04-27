package pl.jclab.refio.ui.settings

import com.intellij.ui.components.*
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pl.jclab.refio.ui.theme.LCATheme
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Theme Settings Panel
 *
 * Displays visual preview of LCATheme elements and standard UI components.
 * Helps developers understand available theme constants and verify
 * dark/light theme compatibility.
 */
class ThemeSettingsPanel : JBPanel<ThemeSettingsPanel>(BorderLayout()) {

    /**
     * Custom JPanel with rounded corners for theme preview bubbles
     */
    private class RoundedBubblePanel(private val backgroundColor: Color) : JBPanel<RoundedBubblePanel>() {
        
        init {
            background = backgroundColor
            isOpaque = false // Let paintComponent handle background
            border = JBUI.Borders.empty(LCATheme.gap)
        }
        
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            
            val radius = LCATheme.bubbleRadius.toDouble()
            val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), radius, radius)
            
            g2.color = backgroundColor
            g2.fill(shape)
            
            g2.dispose()
            super.paintComponent(g)
        }
    }

    init {
        border = JBUI.Borders.empty()

        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        // LCATheme Colors Section
        mainPanel.add(createLCAThemeColorsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // Chat Bubble Colors Section
        mainPanel.add(createChatBubbleColorsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // Mode Badge Colors Section
        mainPanel.add(createModeBadgeColorsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // Step Status Colors Section
        mainPanel.add(createStepStatusColorsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // LCATheme Spacing Section
        mainPanel.add(createLCAThemeSpacingSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // LCATheme Fonts Section
        mainPanel.add(createLCAThemeFontsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // LCATheme Utilities Section
        mainPanel.add(createLCAThemeUtilitiesSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // Borders Section
        mainPanel.add(createBordersSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // Insets Section
        mainPanel.add(createInsetsSection())
        mainPanel.add(Box.createVerticalStrut(LCATheme.spacingXl))

        // Standard Components Preview
        mainPanel.add(createComponentsPreviewSection())

        add(JBScrollPane(mainPanel).apply {
            border = JBUI.Borders.empty()
        }, BorderLayout.CENTER)
    }

    // ==================== LCATheme COLORS ====================

    private fun createLCAThemeColorsSection(): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = createTitledBorder("LCATheme Colors")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4)
                fill = GridBagConstraints.HORIZONTAL
            }

            val colors = listOf(
                Triple("backgroundColor", LCATheme.backgroundColor, "UIUtil.getPanelBackground()"),
                Triple("editorBackground", LCATheme.editorBackground, "EditorColorsManager.getInstance().globalScheme.defaultBackground"),
                Triple("listBackground", LCATheme.listBackground, "UIUtil.getListBackground()"),
                Triple("headerBackground", LCATheme.headerBackground, "UIUtil.getListBackground()"),
                Triple("descriptionForeground", LCATheme.descriptionForeground, "ContextHelp.FOREGROUND"),
                Triple("disabledForeground", LCATheme.disabledForeground, "Label.disabledForeground()"),
                Triple("labelForeground", LCATheme.labelForeground, "UIUtil.getLabelForeground()"),
                Triple("labelDisabledForeground", LCATheme.labelDisabledForeground, "UIUtil.getLabelDisabledForeground()"),
                Triple("headerInactiveColor", LCATheme.headerInactiveColor, "UIUtil.getHeaderInactiveColor()"),
                Triple("inactiveTextFieldBackground", LCATheme.inactiveTextFieldBackground, "UIUtil.getInactiveTextFieldBackgroundColor()"),
                Triple("borderColor", LCATheme.borderColor, "separatorForeground()"),
                Triple("accentColor", LCATheme.accentColor, "Link.Foreground.ENABLED"),
                Triple("successColor", LCATheme.successColor, "JBColor(0x59A869, 0x499C54)"),
                Triple("errorColor", LCATheme.errorColor, "JBColor(0xDB5860, 0xC75450)"),
                Triple("warningColor", LCATheme.warningColor, "JBColor(0xE5A840, 0xBE9117)"),
                Triple("infoColor", LCATheme.infoColor, "JBColor(0x2196F3, 0x6AB7FF)"),
                Triple("neutralColor", LCATheme.neutralColor, "JBColor(0x757575, 0x9E9E9E)"),
                Triple("progressColor", LCATheme.progressColor, "JBColor(0x4CAF50, 0x81C784)"),
                Triple("grayColor", LCATheme.grayColor, "JBColor.GRAY"),
                Triple("whiteColor", LCATheme.whiteColor, "JBColor.WHITE"),
                Triple("redColor", LCATheme.redColor, "JBColor.RED"),
                Triple("lightGrayColor", LCATheme.lightGrayColor, "JBColor.LIGHT_GRAY"),
                Triple("monoTextColor", LCATheme.monoTextColor, "JBColor(0x555555, 0xBBBBBB)"),
                Triple("darkenedBackground", LCATheme.darkenedBackground, "backgroundColor.darker()")
            )

            colors.forEach { (name, color, source) ->
                // Property name
                gbc.gridx = 0
                gbc.weightx = 0.3
                add(JBLabel("LCATheme.$name").apply {
                    font = LCATheme.headerFont
                }, gbc)

                // Color sample
                gbc.gridx = 1
                gbc.weightx = 0.3
                add(createColorSample(color), gbc)

                // Source info
                gbc.gridx = 2
                gbc.weightx = 0.4
                add(JBLabel(source).apply {
                    foreground = LCATheme.descriptionForeground
                    font = LCATheme.smallFont
                }, gbc)

                gbc.gridy++
            }
        }
    }

    private fun createColorSample(color: Color): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            val swatch = JPanel().apply {
                preferredSize = Dimension(60, 24)
                background = color
                border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
                isOpaque = true
            }

            val hexLabel = JBLabel("#${Integer.toHexString(color.rgb).substring(2).uppercase()}").apply {
                font = LCATheme.monoFont
            }

            add(swatch)
            add(hexLabel)
        }
    }

    // ==================== CHAT BUBBLE COLORS ====================

    private fun createChatBubbleColorsSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = createTitledBorder("Chat Bubble Colors")

            // User bubble
            add(createBubblePreview(
                "User Message",
                LCATheme.userBubbleBackground,
                LCATheme.userBubbleForeground,
                "userBubbleBackground / userBubbleForeground"
            ))

            // Assistant bubble
            add(createBubblePreview(
                "Assistant Message",
                LCATheme.assistantBubbleBackground,
                LCATheme.assistantBubbleForeground,
                "assistantBubbleBackground / assistantBubbleForeground"
            ))

            // System bubble
            add(createBubblePreview(
                "System Message",
                LCATheme.systemBubbleBackground,
                LCATheme.systemBubbleForeground,
                "systemBubbleBackground / systemBubbleForeground"
            ))

            // Approval bubble
            add(createBubblePreview(
                "Approval Required",
                LCATheme.approvalBubbleBackground,
                LCATheme.assistantBubbleForeground,
                "approvalBubbleBackground"
            ))

            // Question bubble
            add(createBubblePreview(
                "Question from Agent",
                LCATheme.questionBubbleBackground,
                LCATheme.assistantBubbleForeground,
                "questionBubbleBackground"
            ))
        }
    }

    private fun createBubblePreview(text: String, bgColor: Color, fgColor: Color, propertyName: String): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
            // Rounded bubble preview
            add(RoundedBubblePanel(bgColor).apply {
                preferredSize = Dimension(200, 36)
                layout = BorderLayout()

                add(JBLabel(text).apply {
                    foreground = fgColor
                    horizontalAlignment = SwingConstants.CENTER
                }, BorderLayout.CENTER)
            })

            // Property info
            add(JBLabel(propertyName).apply {
                foreground = LCATheme.descriptionForeground
                font = LCATheme.smallFont
            })
        }
    }

    // ==================== MODE BADGE COLORS ====================

    private fun createModeBadgeColorsSection(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.spacingLg, LCATheme.gap)).apply {
            border = createTitledBorder("Mode Badge Colors")

            // Chat mode badge
            add(createModeBadgePreview("CHAT", LCATheme.chatModeBadgeBackground, LCATheme.chatModeBadgeForeground))

            // Plan mode badge
            add(createModeBadgePreview("PLAN", LCATheme.planModeBadgeBackground, LCATheme.planModeBadgeForeground))

            // Agent mode badge
            add(createModeBadgePreview("AGENT", LCATheme.agentModeBadgeBackground, LCATheme.agentModeBadgeForeground))
        }
    }

    private fun createModeBadgePreview(mode: String, bgColor: Color, fgColor: Color): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(JBLabel(mode).apply {
                font = LCATheme.smallFont
                foreground = fgColor
                background = bgColor
                isOpaque = true
                border = JBUI.Borders.empty(2, 8)
            })

            add(JBLabel("${mode.lowercase()}ModeBadge...").apply {
                foreground = LCATheme.descriptionForeground
                font = LCATheme.smallFont
            })
        }
    }

    // ==================== STEP STATUS COLORS ====================

    private fun createStepStatusColorsSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = createTitledBorder("Step Status Colors")

            // Step status colors
            val statusColors = listOf(
                Triple("stepPendingBackground", LCATheme.stepPendingBackground, "Pending/Planned steps"),
                Triple("stepRunningBackground", LCATheme.stepRunningBackground, "Currently running steps"),
                Triple("stepSuccessBackground", LCATheme.stepSuccessBackground, "Successfully completed steps"),
                Triple("stepFailedBackground", LCATheme.stepFailedBackground, "Failed steps"),
                Triple("stepSkippedBackground", LCATheme.stepSkippedBackground, "Skipped steps"),
                Triple("stepCanceledBackground", LCATheme.stepCanceledBackground, "Canceled steps"),
                Triple("stepNewBackground", LCATheme.stepNewBackground, "New steps")
            )

            statusColors.forEach { (name, bgColor, description) ->
                add(createStatusColorPreview(name, bgColor, description))
                add(Box.createVerticalStrut(LCATheme.gap))
            }
        }
    }

    private fun createStatusColorPreview(name: String, bgColor: Color, description: String): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
            // Status preview
            add(JPanel(BorderLayout()).apply {
                preferredSize = Dimension(120, 32)
                background = bgColor
                border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
                isOpaque = true

                add(JBLabel("SAMPLE").apply {
                    horizontalAlignment = SwingConstants.CENTER
                    foreground = LCATheme.whiteColor
                    font = LCATheme.headerFont.deriveFont(10f)
                }, BorderLayout.CENTER)
            })

            // Property info
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JBLabel("LCATheme.$name").apply {
                    font = LCATheme.headerFont
                })
                add(JBLabel(description).apply {
                    foreground = LCATheme.descriptionForeground
                    font = LCATheme.smallFont
                })
            })
        }
    }

    // ==================== LCATheme SPACING ====================

    private fun createLCAThemeSpacingSection(): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = createTitledBorder("LCATheme Spacing (scaled for HiDPI)")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4)
                fill = GridBagConstraints.HORIZONTAL
            }

            val spacings = listOf(
                Triple("spacingXs", LCATheme.spacingXs, "2px scaled"),
                Triple("spacingSm / gap", LCATheme.spacingSm, "4px scaled"),
                Triple("spacing / padding", LCATheme.spacing, "8px scaled"),
                Triple("spacingLg", LCATheme.spacingLg, "12px scaled"),
                Triple("spacingXl / margin", LCATheme.spacingXl, "16px scaled"),
                Triple("bubbleRadius", LCATheme.bubbleRadius, "8px scaled - bubble corner radius")
            )

            spacings.forEach { (name, value, description) ->
                // Property name
                gbc.gridx = 0
                gbc.weightx = 0.3
                add(JBLabel("LCATheme.$name").apply {
                    font = LCATheme.headerFont
                }, gbc)

                // Visual representation
                gbc.gridx = 1
                gbc.weightx = 0.4
                add(createSpacingSample(value), gbc)

                // Value + description
                gbc.gridx = 2
                gbc.weightx = 0.3
                add(JBLabel("${value}px ($description)").apply {
                    foreground = LCATheme.descriptionForeground
                    font = LCATheme.smallFont
                }, gbc)

                gbc.gridy++
            }
        }
    }

    private fun createSpacingSample(size: Int): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            // Visual bar showing the spacing
            add(JPanel().apply {
                preferredSize = Dimension(size, 16)
                background = LCATheme.accentColor
                border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
            })

            // Reference bar (always 16px for comparison)
            add(JBLabel("|").apply {
                foreground = LCATheme.descriptionForeground
            })
        }
    }

    // ==================== LCATheme UTILITIES ====================

    private fun createLCAThemeUtilitiesSection(): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = createTitledBorder("LCATheme Utilities")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4)
                fill = GridBagConstraints.HORIZONTAL
            }

            // isDark utility
            gbc.gridx = 0
            gbc.weightx = 0.3
            add(JBLabel("LCATheme.isDark").apply {
                font = LCATheme.headerFont
            }, gbc)

            gbc.gridx = 1
            gbc.weightx = 0.3
            add(JBLabel(LCATheme.isDark.toString()).apply {
                font = LCATheme.monoFont
                foreground = if (LCATheme.isDark) LCATheme.successColor else LCATheme.infoColor
            }, gbc)

            gbc.gridx = 2
            gbc.weightx = 0.4
            add(JBLabel("JBColor.isBright().not()").apply {
                foreground = LCATheme.descriptionForeground
                font = LCATheme.smallFont
            }, gbc)
        }
    }

    // ==================== LCATheme FONTS ====================

    private fun createLCAThemeFontsSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = createTitledBorder("LCATheme Fonts")

            val fonts = listOf(
                "headerFont" to LCATheme.headerFont,
                "bodyFont" to LCATheme.bodyFont,
                "smallFont" to LCATheme.smallFont,
                "monoFont" to LCATheme.monoFont,
                "editorFont" to LCATheme.editorFont,
                "boldFont" to LCATheme.boldFont,
                "italicFont" to LCATheme.italicFont,
                "largeBoldFont" to LCATheme.largeBoldFont,
                "smallBoldFont" to LCATheme.smallBoldFont
            )

            fonts.forEach { (name, font) ->
                add(JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
                    add(JBLabel("LCATheme.$name:").apply {
                        this.font = LCATheme.headerFont
                        preferredSize = Dimension(150, preferredSize.height)
                    })
                    add(JBLabel("The quick brown fox jumps over the lazy dog").apply {
                        this.font = font
                    })
                    add(JBLabel("(${font.name}, ${font.size}pt)").apply {
                        foreground = LCATheme.descriptionForeground
                        this.font = LCATheme.smallFont
                    })
                })
            }
        }
    }

    // ==================== COMPONENTS PREVIEW ====================

    private fun createComponentsPreviewSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = createTitledBorder("Component Preview (using LCATheme)")

            // Buttons row
            add(createSubSection("Buttons", listOf(
                JButton("Normal"),
                JButton("Disabled").apply { isEnabled = false }
            )))

            // Text inputs row
            add(createSubSection("Text Inputs", listOf(
                JBTextField("Normal text field"),
                JBTextField("Disabled").apply { isEnabled = false }
            )))

            // Labels with LCATheme colors
            add(createSubSection("Labels", listOf(
                JBLabel("Normal label"),
                JBLabel("Description text").apply { foreground = LCATheme.descriptionForeground },
                JBLabel("Disabled text").apply { foreground = LCATheme.disabledForeground },
                JBLabel("Accent/Link").apply { foreground = LCATheme.accentColor },
                JBLabel("Success").apply { foreground = LCATheme.successColor },
                JBLabel("Error").apply { foreground = LCATheme.errorColor },
                JBLabel("Warning").apply { foreground = LCATheme.warningColor }
            )))

            // Borders preview
            add(createSubSection("Borders with LCATheme.borderColor", listOf(
                JPanel().apply {
                    preferredSize = Dimension(100, 40)
                    border = JBUI.Borders.customLine(LCATheme.borderColor, 1)
                    add(JBLabel("1px border"))
                },
                JPanel().apply {
                    preferredSize = Dimension(100, 40)
                    border = JBUI.Borders.customLine(LCATheme.borderColor, 2)
                    add(JBLabel("2px border"))
                }
            )))

            // Checkboxes
            add(createSubSection("Checkboxes", listOf(
                JBCheckBox("Checked", true),
                JBCheckBox("Unchecked", false),
                JBCheckBox("Disabled", false).apply { isEnabled = false }
            )))

            // Progress bars
            add(createSubSection("Progress", listOf(
                JProgressBar(0, 100).apply {
                    value = 65
                    isStringPainted = true
                },
                JProgressBar().apply { isIndeterminate = true }
            )))
        }
    }

    private fun createSubSection(title: String, components: List<JComponent>): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(title),
                JBUI.Borders.empty(LCATheme.gap)
            )
            alignmentX = Component.LEFT_ALIGNMENT

            components.forEach { add(it) }
        }
    }

    // ==================== BORDERS ====================

    private fun createBordersSection(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = createTitledBorder("LCATheme Borders")

            val borders = listOf(
                Triple("sectionBorder", LCATheme.sectionBorder, "Line + ${LCATheme.spacingLg}px padding"),
                Triple("cardBorder", LCATheme.cardBorder, "Line + ${LCATheme.padding}px padding"),
                Triple("inputBorder", LCATheme.inputBorder, "Line border (1px)")
            )

            borders.forEach { (name, border, description) ->
                add(JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
                    // Border preview
                    add(JPanel().apply {
                        preferredSize = Dimension(150, 40)
                        this.border = border
                        add(JBLabel("Sample"))
                    })

                    // Property info
                    add(JPanel().apply {
                        layout = BoxLayout(this, BoxLayout.Y_AXIS)
                        add(JBLabel("LCATheme.$name").apply {
                            font = LCATheme.headerFont
                        })
                        add(JBLabel(description).apply {
                            foreground = LCATheme.descriptionForeground
                            font = LCATheme.smallFont
                        })
                    })
                })
                add(Box.createVerticalStrut(LCATheme.gap))
            }

            // Custom border methods
            add(JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
                add(JBLabel("Methods:").apply {
                    font = LCATheme.headerFont
                })
            })

            val methods = listOf(
                "emptyBorder()" to "Empty border (no padding)",
                "paddedBorder(padding)" to "Uniform padding on all sides",
                "customLineBorder(color, thickness)" to "Custom line border",
                "compoundBorder(outer, inner)" to "Composite border (outer + inner)"
            )

            methods.forEach { (signature, description) ->
                add(JPanel(FlowLayout(FlowLayout.LEFT, LCATheme.padding, LCATheme.gap)).apply {
                    add(JBLabel(signature).apply {
                        font = LCATheme.monoFont
                        foreground = LCATheme.accentColor
                    })
                    add(JBLabel(" - $description").apply {
                        font = LCATheme.smallFont
                        foreground = LCATheme.descriptionForeground
                    })
                })
            }
        }
    }

    // ==================== INSETS ====================

    private fun createInsetsSection(): JPanel {
        return JPanel(GridBagLayout()).apply {
            border = createTitledBorder("LCATheme Insets (for GridBagConstraints)")

            val gbc = GridBagConstraints().apply {
                gridx = 0
                gridy = 0
                anchor = GridBagConstraints.WEST
                insets = JBUI.insets(4)
                fill = GridBagConstraints.HORIZONTAL
            }

            val insets = listOf(
                Triple("insetsNone", LCATheme.insetsNone, "0px all sides"),
                Triple("insetsSmall", LCATheme.insetsSmall, "${LCATheme.spacingSm}px all sides"),
                Triple("insetsMedium", LCATheme.insetsMedium, "${LCATheme.padding}px all sides"),
                Triple("insetsLarge", LCATheme.insetsLarge, "${LCATheme.margin}px all sides"),
                Triple("insetsXLarge", LCATheme.insetsXLarge, "24px all sides")
            )

            insets.forEach { (name, inset, description) ->
                // Property name
                gbc.gridx = 0
                gbc.weightx = 0.3
                add(JBLabel("LCATheme.$name").apply {
                    font = LCATheme.headerFont
                }, gbc)

                // Visual representation
                gbc.gridx = 1
                gbc.weightx = 0.3
                add(JBLabel("Insets($inset)").apply {
                    font = LCATheme.monoFont
                }, gbc)

                // Description
                gbc.gridx = 2
                gbc.weightx = 0.4
                add(JBLabel(description).apply {
                    foreground = LCATheme.descriptionForeground
                    font = LCATheme.smallFont
                }, gbc)

                gbc.gridy++
            }

            // Add separator
            gbc.gridx = 0
            gbc.gridwidth = 3
            gbc.fill = GridBagConstraints.HORIZONTAL
            gbc.insets = JBUI.insets(8, 0)
            add(JSeparator(), gbc)
            gbc.gridy++
            gbc.gridwidth = 1
            gbc.insets = JBUI.insets(4)

            // Methods header
            gbc.gridx = 0
            gbc.weightx = 1.0
            gbc.gridwidth = 3
            add(JBLabel("Helper Methods:").apply {
                font = LCATheme.headerFont
            }, gbc)
            gbc.gridy++
            gbc.gridwidth = 1

            val methods = listOf(
                "insetsTop(value)" to "Top inset only",
                "insetsBottom(value)" to "Bottom inset only",
                "insetsLeft(value)" to "Left inset only",
                "insetsRight(value)" to "Right inset only",
                "insetsVertical(value)" to "Top + bottom insets",
                "insetsHorizontal(value)" to "Left + right insets",
                "insets(top, left, bottom, right)" to "Custom insets for all sides"
            )

            methods.forEach { (signature, description) ->
                gbc.gridx = 0
                gbc.weightx = 0.4
                add(JBLabel(signature).apply {
                    font = LCATheme.monoFont
                    foreground = LCATheme.accentColor
                }, gbc)

                gbc.gridx = 1
                gbc.weightx = 0.6
                gbc.gridwidth = 2
                add(JBLabel(description).apply {
                    foreground = LCATheme.descriptionForeground
                    font = LCATheme.smallFont
                }, gbc)

                gbc.gridy++
                gbc.gridwidth = 1
            }
        }
    }

    private fun createTitledBorder(title: String) = BorderFactory.createTitledBorder(
        JBUI.Borders.customLine(LCATheme.borderColor), title
    ).apply {
        titleColor = UIUtil.getLabelForeground()
    }

    fun reload() {
        // Repaint to reflect any theme changes
        revalidate()
        repaint()
    }
}
