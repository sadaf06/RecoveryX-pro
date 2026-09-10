package com.example.ui.theme

import androidx.compose.ui.graphics.Color

// Premium Liquid Glass UI Color Palette (VisionOS Dark-First Theme)
val GlassPrimary = Color(0xFF4F7CFF)       // Electric Blue
val GlassSecondary = Color(0xFF7B61FF)     // Purple Accent
val GlassAccent = Color(0xFF4FD1FF)        // Radiant Cyan
val GlassViolet = Color(0xFFA855F7)        // Intense Violet

val GlassBgDeep = Color(0xFF0A0B10)        // Deep Charcoal/Slate Background (Apple & Linear Dark Mode)
val GlassSurfaceDark = Color(0xFF12131A)   // Translucent Glass Base Panel
val GlassSurfaceCard = Color(0xFF171922)   // Translucent Frosted Card Background

// Success, Warning, Error
val GlassSuccess = Color(0xFF00C896)
val GlassWarning = Color(0xFFFFB547)
val GlassError = Color(0xFFFF5D73)

// Text Colors
val GlassTextPrimary = Color(0xFFFFFFFF)
val GlassTextSecondary = Color(0xFFA1A8B8)

// Translucent borders and highlight outlines
val GlassOutlineActive = Color(0x4D4F7CFF)  // Glowing Blue Outline
val GlassOutlineBorder = Color(0x1AFFFFFF)  // Very subtle translucent white border

// Dark Color Scheme Mapping
val md_theme_dark_primary = GlassPrimary
val md_theme_dark_onPrimary = Color(0xFFFFFFFF)
val md_theme_dark_primaryContainer = Color(0xFF14203F)
val md_theme_dark_onPrimaryContainer = Color(0xFFE0E6FF)

val md_theme_dark_secondary = GlassSecondary
val md_theme_dark_onSecondary = Color(0xFFFFFFFF)
val md_theme_dark_secondaryContainer = Color(0xFF211742)
val md_theme_dark_onSecondaryContainer = Color(0xFFECE6FF)

val md_theme_dark_background = GlassBgDeep
val md_theme_dark_onBackground = GlassTextPrimary

val md_theme_dark_surface = GlassSurfaceDark
val md_theme_dark_onSurface = GlassTextPrimary

val md_theme_dark_surfaceVariant = GlassSurfaceCard
val md_theme_dark_onSurfaceVariant = GlassTextSecondary

val md_theme_dark_outline = GlassOutlineActive
val md_theme_dark_outlineVariant = GlassOutlineBorder

// Light Theme (Mapped closely or beautifully so that the app maintains high-fidelity aesthetics under both preferences)
val md_theme_light_primary = GlassPrimary
val md_theme_light_onPrimary = Color(0xFFFFFFFF)
val md_theme_light_primaryContainer = Color(0xFFE8EFFF)
val md_theme_light_onPrimaryContainer = Color(0xFF001740)

val md_theme_light_secondary = GlassSecondary
val md_theme_light_onSecondary = Color(0xFFFFFFFF)
val md_theme_light_secondaryContainer = Color(0xFFF0ECFF)
val md_theme_light_onSecondaryContainer = Color(0xFF18004D)

val md_theme_light_background = Color(0xFFF5F7FB) // Soft frosted light background
val md_theme_light_onBackground = Color(0xFF101524)

val md_theme_light_surface = Color(0xFFFFFFFF)
val md_theme_light_onSurface = Color(0xFF101524)

val md_theme_light_surfaceVariant = Color(0xFFEAEFF8)
val md_theme_light_onSurfaceVariant = Color(0xFF6B7587)

val md_theme_light_outline = Color(0x334F7CFF)
val md_theme_light_outlineVariant = Color(0x1A000000)

