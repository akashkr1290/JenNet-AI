import 'package:flutter/material.dart';

/// JanNet AI design tokens - the single source of truth for colour, spacing,
/// radius, elevation and breakpoints. Derived from the approved UI reference
/// ("Master Visual Design System" sheet): sky blue #5C9FD8, navy #203A5F,
/// teal #38A9A2, amber #F3A83F, neutrals #FFFFFF / #F8FAFC / #334155.
///
/// Accessibility: the reference's sky blue, teal and amber fail WCAG AA
/// (4.5:1) as backgrounds for small white text (2.8:1, 2.9:1, 2.0:1). The
/// brand hues are kept for decoration ([sky], [tealBrand], [amber]) while
/// text-bearing surfaces use deeper shades from the same families
/// ([primary], [teal]) or dark text on amber - all measured at >= 4.5:1.
///
/// These are plain constants (not a ThemeExtension) so every widget renders
/// correctly even without the JanNet theme installed - e.g. in widget tests
/// that pump a bare MaterialApp.
class JanColors {
  JanColors._();

  // Brand
  static const Color sky = Color(0xFF5C9FD8); // reference primary (decorative)
  static const Color primary = Color(0xFF2C6FAE); // accessible primary: buttons, links (5.26:1 on white)
  static const Color primaryLight = Color(0xFFDCEAF8);
  static const Color navy = Color(0xFF203A5F);
  static const Color navyDeep = Color(0xFF172C4A);
  static const Color tealBrand = Color(0xFF38A9A2); // reference accent (decorative)
  static const Color teal = Color(0xFF1F7F79); // accessible teal surface (4.81:1 with white)
  static const Color tealLight = Color(0xFFE2F4F2);
  static const Color amber = Color(0xFFF3A83F); // reference accent (use navy text on it)
  static const Color amberDark = Color(0xFF8A5A0B); // amber-family text (5.31:1 on its tint)
  static const Color amberLight = Color(0xFFFDF1DF);
  static const Color orange = Color(0xFFE07B24);

  // Neutrals
  static const Color white = Color(0xFFFFFFFF);
  static const Color background = Color(0xFFF3F7FC); // page
  static const Color surfaceAlt = Color(0xFFF8FAFC);
  static const Color ink = Color(0xFF1E2B3C); // body text
  static const Color slate = Color(0xFF334155);
  static const Color muted = Color(0xFF5B6B80); // secondary text (>= 4.7:1 on white and page)
  static const Color border = Color(0xFFD5E0EC);
  static const Color divider = Color(0xFFE2EAF3);
  static const Color skeleton = Color(0xFFE6EDF5);

  // Semantic
  static const Color success = teal;
  static const Color successLight = tealLight;
  static const Color warning = amberDark;
  static const Color warningLight = amberLight;
  static const Color error = Color(0xFFB3261E);
  static const Color errorLight = Color(0xFFFBE9E7);
  static const Color info = primary;
  static const Color infoLight = Color(0xFFE6F0FA);
}

/// 4-point spacing scale.
class JanSpace {
  JanSpace._();
  static const double xxs = 4;
  static const double xs = 8;
  static const double sm = 12;
  static const double md = 16;
  static const double lg = 20;
  static const double xl = 24;
  static const double xxl = 32;
  static const double xxxl = 48;
}

class JanRadius {
  JanRadius._();
  static const double sm = 10;
  static const double md = 14;
  static const double lg = 18;
  static const double xl = 24;

  static const BorderRadius smAll = BorderRadius.all(Radius.circular(sm));
  static const BorderRadius mdAll = BorderRadius.all(Radius.circular(md));
  static const BorderRadius lgAll = BorderRadius.all(Radius.circular(lg));
  static const BorderRadius xlAll = BorderRadius.all(Radius.circular(xl));
}

class JanShadows {
  JanShadows._();

  /// Soft, cool-toned card shadow used across the reference designs.
  static const List<BoxShadow> card = [
    BoxShadow(color: Color(0x14203A5F), blurRadius: 18, offset: Offset(0, 6)),
    BoxShadow(color: Color(0x0A203A5F), blurRadius: 3, offset: Offset(0, 1)),
  ];

  static const List<BoxShadow> raised = [
    BoxShadow(color: Color(0x22203A5F), blurRadius: 28, offset: Offset(0, 10)),
  ];

  /// Coloured glow under primary call-to-action buttons.
  static const List<BoxShadow> primaryGlow = [
    BoxShadow(color: Color(0x402C6FAE), blurRadius: 16, offset: Offset(0, 6)),
  ];
}

/// Layout breakpoints (logical pixels).
class JanBreakpoints {
  JanBreakpoints._();

  /// Below this: phone layout (bottom navigation, single column).
  static const double medium = 720;

  /// At/above this: desktop/web layout (side navigation rail, multi-column).
  static const double expanded = 1024;

  /// Maximum readable width for page content on large screens.
  static const double contentMaxWidth = 1180;

  /// Maximum width for form-style pages (auth, settings, detail).
  static const double formMaxWidth = 560;

  static bool isExpanded(BuildContext context) => MediaQuery.sizeOf(context).width >= expanded;
  static bool isAtLeastMedium(BuildContext context) => MediaQuery.sizeOf(context).width >= medium;
}
