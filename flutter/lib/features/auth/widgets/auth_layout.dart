import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_illustrations.dart';
import '../../../core/widgets/jan_logo.dart';
import '../../../core/widgets/jan_surfaces.dart';

/// Shared layout for every authentication screen (login, registration, OTP,
/// MFA, forgot/reset password).
///
/// * Phones/tablets: the reference single-column form on the JanNet backdrop.
/// * Web/desktop (>= [JanBreakpoints.expanded]): a navy brand panel carrying
///   the onboarding messages from the reference (Report civic issues / Track
///   every update / Improve your community) beside the form - not the phone
///   layout stretched across a monitor.
class JanAuthLayout extends StatelessWidget {
  final Widget child;
  final bool showBackButton;

  const JanAuthLayout({super.key, required this.child, this.showBackButton = false});

  @override
  Widget build(BuildContext context) {
    final form = SafeArea(
      child: Center(
        child: SingleChildScrollView(
          padding: const EdgeInsets.symmetric(horizontal: JanSpace.xl, vertical: JanSpace.lg),
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 440),
            child: child,
          ),
        ),
      ),
    );

    final wide = JanBreakpoints.isExpanded(context);
    // On web the back arrow sits over the navy brand panel, so it turns white.
    final appBar = showBackButton
        ? AppBar(
            backgroundColor: Colors.transparent,
            foregroundColor: wide ? JanColors.white : JanColors.navy,
            iconTheme: IconThemeData(color: wide ? JanColors.white : JanColors.navy),
            automaticallyImplyLeading: true,
          )
        : null;

    if (wide) {
      return Scaffold(
        extendBodyBehindAppBar: true,
        appBar: appBar,
        body: Row(
          children: [
            const Expanded(flex: 5, child: _BrandPanel()),
            Expanded(flex: 6, child: JanBackdrop(child: form)),
          ],
        ),
      );
    }
    return Scaffold(
      extendBodyBehindAppBar: true,
      appBar: appBar,
      body: JanBackdrop(child: form),
    );
  }
}

class _BrandPanel extends StatelessWidget {
  const _BrandPanel();

  static const _points = [
    (Icons.add_a_photo_outlined, 'Report civic issues', 'Photo, location and a short note - done in a minute.'),
    (Icons.timeline_rounded, 'Track every update', 'See each step from submission to resolution.'),
    (Icons.diversity_3_outlined, 'Improve your community', 'Your reports help build a cleaner, safer city.'),
  ];

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [JanColors.navy, JanColors.navyDeep],
        ),
      ),
      child: Stack(
        children: [
          const Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            height: 180,
            child: ExcludeSemantics(child: CustomPaint(painter: SkylinePainter(color: Color(0x405C9FD8)))),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(56, 48, 48, 48),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const JanLogo(markSize: 40, fontSize: 26, onDark: true),
                  const Spacer(),
                  const Text(
                    'Report.\nTrack.\nImprove.',
                    style: TextStyle(color: JanColors.white, fontSize: 44, height: 1.1, fontWeight: FontWeight.w800, letterSpacing: -1),
                  ),
                  const SizedBox(height: JanSpace.xl),
                  for (final p in _points)
                    Padding(
                      padding: const EdgeInsets.only(bottom: JanSpace.md),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Container(
                            width: 40,
                            height: 40,
                            decoration: BoxDecoration(
                              color: JanColors.white.withValues(alpha: 0.12),
                              borderRadius: JanRadius.mdAll,
                            ),
                            child: Icon(p.$1, color: const Color(0xFF9CC5EC), size: 22),
                          ),
                          const SizedBox(width: 14),
                          Expanded(
                            child: Column(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                Text(p.$2, style: const TextStyle(color: JanColors.white, fontSize: 16, fontWeight: FontWeight.w700)),
                                const SizedBox(height: 2),
                                Text(p.$3, style: const TextStyle(color: Color(0xFFC9D8EA), fontSize: 14, height: 1.4)),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  const Spacer(flex: 2),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// Logo + optional illustration + title/subtitle block at the top of an
/// auth form. The illustration is only shown when there is vertical room.
class JanAuthHeader extends StatelessWidget {
  final String title;
  final String? subtitle;
  final bool illustration;

  const JanAuthHeader({super.key, required this.title, this.subtitle, this.illustration = false});

  @override
  Widget build(BuildContext context) {
    final roomy = MediaQuery.sizeOf(context).height >= 760 && !JanBreakpoints.isExpanded(context);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Center(child: JanLogo(markSize: 40, fontSize: 26)),
        if (illustration && roomy) ...[
          const SizedBox(height: JanSpace.sm),
          const JanCivicHero(height: 140),
        ],
        const SizedBox(height: JanSpace.lg),
        Semantics(
          header: true,
          child: Text(
            title,
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.headlineMedium?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
          ),
        ),
        if (subtitle != null) ...[
          const SizedBox(height: 6),
          Text(subtitle!, textAlign: TextAlign.center, style: const TextStyle(color: JanColors.muted, fontSize: 15, height: 1.4)),
        ],
        const SizedBox(height: JanSpace.lg),
      ],
    );
  }
}

/// Spinner sized for the inside of a full-width primary button.
class JanButtonSpinner extends StatelessWidget {
  const JanButtonSpinner({super.key});

  @override
  Widget build(BuildContext context) {
    return const SizedBox(
      height: 20,
      width: 20,
      child: CircularProgressIndicator(strokeWidth: 2.4, color: JanColors.white),
    );
  }
}
