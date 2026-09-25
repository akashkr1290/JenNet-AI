import 'package:flutter/material.dart';

import 'jan_tokens.dart';

/// The JanNet AI Material 3 theme. Every Material component (buttons, inputs,
/// cards, navigation, dialogs, sheets, chips, snack bars, tabs, switches)
/// takes its look from here, so screens stay consistent without per-screen
/// styling.
///
/// [highContrast] keeps the same shapes and hierarchy but deepens text,
/// borders and interactive colours (Gap-backlog Patch 46: the user's saved
/// high-contrast setting is applied app-wide from main.dart).
class JanTheme {
  JanTheme._();

  static ThemeData light({bool highContrast = false}) {
    final primary = highContrast ? const Color(0xFF184F86) : JanColors.primary;
    final ink = highContrast ? const Color(0xFF0B1320) : JanColors.ink;
    final muted = highContrast ? const Color(0xFF2E3B4C) : JanColors.muted;
    final border = highContrast ? const Color(0xFF4A5B70) : JanColors.border;
    final focus = highContrast ? const Color(0xFF0B1320) : JanColors.tealBrand;

    final scheme = ColorScheme(
      brightness: Brightness.light,
      primary: primary,
      onPrimary: JanColors.white,
      primaryContainer: JanColors.primaryLight,
      onPrimaryContainer: const Color(0xFF0F2E4F),
      secondary: JanColors.teal,
      onSecondary: JanColors.white,
      secondaryContainer: JanColors.tealLight,
      onSecondaryContainer: const Color(0xFF0B3B38),
      tertiary: JanColors.amber,
      onTertiary: JanColors.navy,
      tertiaryContainer: JanColors.amberLight,
      onTertiaryContainer: const Color(0xFF5A3A06),
      error: JanColors.error,
      onError: JanColors.white,
      errorContainer: JanColors.errorLight,
      onErrorContainer: const Color(0xFF5F1410),
      surface: JanColors.white,
      onSurface: ink,
      onSurfaceVariant: muted,
      surfaceContainerLowest: JanColors.white,
      surfaceContainerLow: const Color(0xFFF7FAFD),
      surfaceContainer: const Color(0xFFF1F5FA),
      surfaceContainerHigh: const Color(0xFFEAF0F7),
      surfaceContainerHighest: const Color(0xFFE3EAF3),
      outline: border,
      outlineVariant: JanColors.divider,
      inverseSurface: JanColors.navy,
      onInverseSurface: JanColors.white,
      inversePrimary: const Color(0xFF9CC5EC),
      shadow: Colors.black,
      scrim: Colors.black,
      surfaceTint: Colors.transparent,
    );

    final textTheme = TextTheme(
      displaySmall: TextStyle(fontSize: 34, height: 1.15, fontWeight: FontWeight.w800, letterSpacing: -0.6, color: JanColors.navy),
      headlineLarge: TextStyle(fontSize: 30, height: 1.2, fontWeight: FontWeight.w800, letterSpacing: -0.5, color: JanColors.navy),
      headlineMedium: TextStyle(fontSize: 26, height: 1.2, fontWeight: FontWeight.w800, letterSpacing: -0.4, color: JanColors.navy),
      headlineSmall: TextStyle(fontSize: 22, height: 1.25, fontWeight: FontWeight.w700, letterSpacing: -0.2, color: JanColors.navy),
      titleLarge: TextStyle(fontSize: 19, height: 1.3, fontWeight: FontWeight.w700, color: JanColors.navy),
      titleMedium: TextStyle(fontSize: 16, height: 1.35, fontWeight: FontWeight.w700, color: ink),
      titleSmall: TextStyle(fontSize: 14, height: 1.35, fontWeight: FontWeight.w700, color: ink),
      bodyLarge: TextStyle(fontSize: 16, height: 1.45, fontWeight: FontWeight.w400, color: ink),
      bodyMedium: TextStyle(fontSize: 14, height: 1.45, fontWeight: FontWeight.w400, color: ink),
      bodySmall: TextStyle(fontSize: 12.5, height: 1.4, fontWeight: FontWeight.w400, color: muted),
      labelLarge: const TextStyle(fontSize: 15, height: 1.2, fontWeight: FontWeight.w600, letterSpacing: 0.1),
      labelMedium: TextStyle(fontSize: 12.5, height: 1.2, fontWeight: FontWeight.w600, letterSpacing: 0.2, color: muted),
      labelSmall: TextStyle(fontSize: 11, height: 1.2, fontWeight: FontWeight.w700, letterSpacing: 0.6, color: muted),
    );

    OutlineInputBorder inputBorder(Color color, [double width = 1.2]) => OutlineInputBorder(
          borderRadius: JanRadius.mdAll,
          borderSide: BorderSide(color: color, width: width),
        );

    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      scaffoldBackgroundColor: JanColors.background,
      canvasColor: JanColors.white,
      textTheme: textTheme,
      visualDensity: VisualDensity.standard,
      splashFactory: InkRipple.splashFactory, // InkSparkle needs shader support not guaranteed on web
      iconTheme: IconThemeData(color: highContrast ? ink : JanColors.navy, size: 24),
      dividerTheme: const DividerThemeData(color: JanColors.divider, thickness: 1, space: 24),
      appBarTheme: AppBarThemeData(
        backgroundColor: JanColors.background,
        foregroundColor: JanColors.navy,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleSpacing: 16,
        toolbarHeight: 64,
        titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: JanColors.navy, letterSpacing: -0.2),
        iconTheme: const IconThemeData(color: JanColors.navy),
        actionsIconTheme: const IconThemeData(color: JanColors.navy),
      ),
      cardTheme: CardThemeData(
        color: JanColors.white,
        surfaceTintColor: Colors.transparent,
        shadowColor: const Color(0x33203A5F),
        elevation: 1.5,
        clipBehavior: Clip.antiAlias,
        shape: RoundedRectangleBorder(
          borderRadius: JanRadius.lgAll,
          side: BorderSide(color: highContrast ? border : JanColors.divider),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: primary,
          foregroundColor: JanColors.white,
          disabledBackgroundColor: const Color(0xFFB9CCE0),
          disabledForegroundColor: JanColors.white,
          minimumSize: const Size(64, 50),
          padding: const EdgeInsets.symmetric(horizontal: 22, vertical: 14),
          shape: const RoundedRectangleBorder(borderRadius: JanRadius.mdAll),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700, letterSpacing: 0.1),
          elevation: 2,
          shadowColor: const Color(0x662C6FAE),
        ),
      ),
      elevatedButtonTheme: ElevatedButtonThemeData(
        style: ElevatedButton.styleFrom(
          backgroundColor: JanColors.white,
          foregroundColor: primary,
          minimumSize: const Size(64, 48),
          shape: const RoundedRectangleBorder(borderRadius: JanRadius.mdAll),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: JanColors.navy,
          minimumSize: const Size(64, 48),
          padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
          side: BorderSide(color: highContrast ? ink : JanColors.navy, width: 1.4),
          shape: const RoundedRectangleBorder(borderRadius: JanRadius.mdAll),
          textStyle: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
        ),
      ),
      textButtonTheme: TextButtonThemeData(
        style: TextButton.styleFrom(
          foregroundColor: primary,
          minimumSize: const Size(48, 44),
          textStyle: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700),
          shape: const RoundedRectangleBorder(borderRadius: JanRadius.smAll),
        ),
      ),
      iconButtonTheme: IconButtonThemeData(
        style: IconButton.styleFrom(
          foregroundColor: JanColors.navy,
          minimumSize: const Size(44, 44),
        ),
      ),
      floatingActionButtonTheme: FloatingActionButtonThemeData(
        backgroundColor: primary,
        foregroundColor: JanColors.white,
        shape: const RoundedRectangleBorder(borderRadius: JanRadius.lgAll),
      ),
      inputDecorationTheme: InputDecorationThemeData(
        filled: true,
        fillColor: JanColors.white,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        border: inputBorder(border),
        enabledBorder: inputBorder(border),
        focusedBorder: inputBorder(focus, 2),
        errorBorder: inputBorder(JanColors.amber, 1.6),
        focusedErrorBorder: inputBorder(JanColors.orange, 2),
        disabledBorder: inputBorder(JanColors.divider),
        labelStyle: TextStyle(color: muted, fontSize: 15),
        floatingLabelStyle: TextStyle(color: primary, fontWeight: FontWeight.w600),
        hintStyle: TextStyle(color: muted.withValues(alpha: 0.85), fontSize: 15),
        helperStyle: TextStyle(color: muted, fontSize: 12.5),
        errorStyle: const TextStyle(color: JanColors.amberDark, fontSize: 12.5, fontWeight: FontWeight.w600),
        prefixIconColor: muted,
        suffixIconColor: muted,
        counterStyle: TextStyle(color: muted, fontSize: 12),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: Colors.transparent,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        height: 68,
        indicatorColor: JanColors.primaryLight,
        indicatorShape: const RoundedRectangleBorder(borderRadius: JanRadius.mdAll),
        labelTextStyle: WidgetStateProperty.resolveWith((states) => TextStyle(
              fontSize: 12,
              fontWeight: states.contains(WidgetState.selected) ? FontWeight.w700 : FontWeight.w500,
              color: states.contains(WidgetState.selected) ? primary : muted,
            )),
        iconTheme: WidgetStateProperty.resolveWith((states) => IconThemeData(
              size: 24,
              color: states.contains(WidgetState.selected) ? primary : muted,
            )),
      ),
      tabBarTheme: TabBarThemeData(
        labelColor: primary,
        unselectedLabelColor: muted,
        indicatorColor: primary,
        indicatorSize: TabBarIndicatorSize.label,
        dividerColor: Colors.transparent,
        labelStyle: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w700),
        unselectedLabelStyle: const TextStyle(fontSize: 14.5, fontWeight: FontWeight.w500),
      ),
      chipTheme: ChipThemeData(
        backgroundColor: JanColors.white,
        selectedColor: JanColors.primaryLight,
        disabledColor: JanColors.surfaceAlt,
        side: BorderSide(color: border),
        shape: const StadiumBorder(),
        labelStyle: TextStyle(color: ink, fontSize: 13, fontWeight: FontWeight.w600),
        secondaryLabelStyle: TextStyle(color: primary, fontSize: 13, fontWeight: FontWeight.w700),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        checkmarkColor: primary,
      ),
      dialogTheme: DialogThemeData(
        backgroundColor: JanColors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 6,
        shape: const RoundedRectangleBorder(borderRadius: JanRadius.xlAll),
        titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w700, color: JanColors.navy),
        contentTextStyle: TextStyle(fontSize: 15, height: 1.45, color: ink),
      ),
      bottomSheetTheme: const BottomSheetThemeData(
        backgroundColor: JanColors.white,
        surfaceTintColor: Colors.transparent,
        showDragHandle: true,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.vertical(top: Radius.circular(JanRadius.xl))),
      ),
      snackBarTheme: SnackBarThemeData(
        behavior: SnackBarBehavior.floating,
        backgroundColor: JanColors.navy,
        contentTextStyle: const TextStyle(color: JanColors.white, fontSize: 14.5),
        actionTextColor: const Color(0xFF9CC5EC),
        shape: const RoundedRectangleBorder(borderRadius: JanRadius.mdAll),
      ),
      listTileTheme: ListTileThemeData(
        iconColor: JanColors.navy,
        textColor: ink,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 2),
        shape: const RoundedRectangleBorder(borderRadius: JanRadius.mdAll),
        titleTextStyle: TextStyle(fontSize: 15.5, fontWeight: FontWeight.w600, color: ink),
        subtitleTextStyle: TextStyle(fontSize: 13, height: 1.35, color: muted),
      ),
      switchTheme: SwitchThemeData(
        thumbColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? JanColors.white : muted),
        trackColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? JanColors.teal : JanColors.divider),
        trackOutlineColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? JanColors.teal : border),
      ),
      checkboxTheme: CheckboxThemeData(
        fillColor: WidgetStateProperty.resolveWith(
            (states) => states.contains(WidgetState.selected) ? primary : Colors.transparent),
        side: BorderSide(color: border, width: 1.6),
        shape: const RoundedRectangleBorder(borderRadius: BorderRadius.all(Radius.circular(5))),
      ),
      progressIndicatorTheme: const ProgressIndicatorThemeData(
        color: JanColors.primary,
        linearTrackColor: JanColors.divider,
        circularTrackColor: Colors.transparent,
      ),
      tooltipTheme: const TooltipThemeData(
        decoration: BoxDecoration(color: JanColors.navy, borderRadius: JanRadius.smAll),
        textStyle: TextStyle(color: JanColors.white, fontSize: 12.5),
      ),
      dropdownMenuTheme: DropdownMenuThemeData(
        menuStyle: MenuStyle(
          backgroundColor: const WidgetStatePropertyAll(JanColors.white),
          surfaceTintColor: const WidgetStatePropertyAll(Colors.transparent),
          shape: const WidgetStatePropertyAll(RoundedRectangleBorder(borderRadius: JanRadius.mdAll)),
        ),
      ),
    );
  }
}
