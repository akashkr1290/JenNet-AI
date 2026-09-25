import 'package:flutter/material.dart';

import '../api/api_exception.dart';
import '../theme/jan_tokens.dart';
import 'jan_illustrations.dart';
import 'jan_logo.dart';

/// Shared loading / skeleton / empty / error / success states, following
/// the reference state catalogue. All views are scrollable when [scrollable]
/// is true so pull-to-refresh keeps working around them.

/// Wraps a state view so it fills the viewport, stays centred, and can still
/// be dragged (required for RefreshIndicator).
class JanScrollableCenter extends StatelessWidget {
  final Widget child;
  const JanScrollableCenter({super.key, required this.child});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      return SingleChildScrollView(
        physics: const AlwaysScrollableScrollPhysics(),
        padding: const EdgeInsets.symmetric(horizontal: JanSpace.xl, vertical: JanSpace.xl),
        child: ConstrainedBox(
          constraints: BoxConstraints(
            minHeight: constraints.maxHeight.isFinite ? constraints.maxHeight - JanSpace.xl * 2 : 0,
          ),
          child: Center(child: ConstrainedBox(constraints: const BoxConstraints(maxWidth: 420), child: child)),
        ),
      );
    });
  }
}

/// Branded loading indicator: the JanNet mark inside a progress ring.
class JanLoadingView extends StatelessWidget {
  final String message;
  const JanLoadingView({super.key, this.message = 'Loading...'});

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Semantics(
        liveRegion: true,
        label: message,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const SizedBox(
              width: 64,
              height: 64,
              child: Stack(
                alignment: Alignment.center,
                children: [
                  SizedBox.expand(child: CircularProgressIndicator(strokeWidth: 3)),
                  JanLogoMark(size: 32),
                ],
              ),
            ),
            const SizedBox(height: JanSpace.md),
            ExcludeSemantics(
              child: Text(message, style: const TextStyle(color: JanColors.muted, fontWeight: FontWeight.w600)),
            ),
          ],
        ),
      ),
    );
  }
}

/// Static skeleton placeholders shaped like list cards (no animation, so it
/// never blocks widget tests or wastes battery).
class JanSkeletonList extends StatelessWidget {
  final int itemCount;
  final String semanticLabel;
  const JanSkeletonList({super.key, this.itemCount = 4, this.semanticLabel = 'Loading'});

  @override
  Widget build(BuildContext context) {
    Widget bar(double width, double height) => Container(
          width: width,
          height: height,
          decoration: BoxDecoration(color: JanColors.skeleton, borderRadius: BorderRadius.circular(6)),
        );
    return Semantics(
      label: semanticLabel,
      liveRegion: true,
      child: ExcludeSemantics(
        child: ListView.separated(
          physics: const AlwaysScrollableScrollPhysics(),
          padding: const EdgeInsets.all(JanSpace.md),
          itemCount: itemCount,
          separatorBuilder: (_, __) => const SizedBox(height: JanSpace.sm),
          itemBuilder: (_, __) => Container(
            padding: const EdgeInsets.all(JanSpace.md),
            decoration: BoxDecoration(
              color: JanColors.white.withValues(alpha: 0.7),
              borderRadius: JanRadius.lgAll,
              border: Border.all(color: JanColors.divider),
            ),
            child: Row(
              children: [
                Container(
                  width: 64,
                  height: 64,
                  decoration: BoxDecoration(color: JanColors.skeleton, borderRadius: JanRadius.mdAll),
                ),
                const SizedBox(width: JanSpace.md),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      bar(140, 12),
                      const SizedBox(height: 10),
                      bar(double.infinity, 10),
                      const SizedBox(height: 8),
                      bar(90, 10),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Illustrated empty state with an optional call to action.
class JanEmptyState extends StatelessWidget {
  final IconData icon;
  final String title;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;
  final Color color;

  const JanEmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.message,
    this.actionLabel,
    this.onAction,
    this.color = JanColors.teal,
  });

  @override
  Widget build(BuildContext context) {
    return JanScrollableCenter(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          JanStateIllustration(icon: icon, color: color),
          const SizedBox(height: JanSpace.lg),
          Semantics(
            header: true,
            child: Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
            ),
          ),
          const SizedBox(height: JanSpace.xs),
          Text(message, textAlign: TextAlign.center, style: const TextStyle(color: JanColors.muted, height: 1.45)),
          if (actionLabel != null && onAction != null) ...[
            const SizedBox(height: JanSpace.lg),
            FilledButton(onPressed: onAction, child: Text(actionLabel!)),
          ],
        ],
      ),
    );
  }
}

/// Illustrated error state. Picks an appropriate title/icon for offline,
/// access and server errors when built with [JanErrorState.fromError].
class JanErrorState extends StatelessWidget {
  final String title;
  final String message;
  final IconData icon;
  final VoidCallback? onRetry;
  final String retryLabel;

  const JanErrorState({
    super.key,
    this.title = 'Something went wrong',
    required this.message,
    this.icon = Icons.error_outline,
    this.onRetry,
    this.retryLabel = 'Try Again',
  });

  /// Builds a state from any error object thrown by the API layer.
  factory JanErrorState.fromError(Object? error, {required String fallback, VoidCallback? onRetry}) {
    if (error is ApiException) {
      final status = error.status; // 0 = no server response (ApiException.network)
      if (status == 0) {
        return JanErrorState(
          title: "You're offline",
          message: error.message,
          icon: Icons.wifi_off_rounded,
          onRetry: onRetry,
          retryLabel: 'Check Connectivity',
        );
      }
      if (status == 401 || status == 403) {
        return JanErrorState(title: 'Access required', message: error.message, icon: Icons.lock_outline, onRetry: onRetry);
      }
      if (status >= 500) {
        return JanErrorState(
          title: 'Service temporarily unavailable',
          message: error.message,
          icon: Icons.dns_outlined,
          onRetry: onRetry,
          retryLabel: 'Retry Connection',
        );
      }
      return JanErrorState(message: error.message, onRetry: onRetry);
    }
    return JanErrorState(message: fallback, onRetry: onRetry);
  }

  @override
  Widget build(BuildContext context) {
    return JanScrollableCenter(
      child: Semantics(
        liveRegion: true,
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            JanStateIllustration(icon: icon, color: JanColors.error),
            const SizedBox(height: JanSpace.lg),
            Text(
              title,
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.titleLarge?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
            ),
            const SizedBox(height: JanSpace.xs),
            Text(message, textAlign: TextAlign.center, style: const TextStyle(color: JanColors.muted, height: 1.45)),
            if (onRetry != null) ...[
              const SizedBox(height: JanSpace.lg),
              FilledButton.icon(
                onPressed: onRetry,
                icon: const Icon(Icons.refresh_rounded),
                label: Text(retryLabel),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

/// Inline status banner (info / success / warning / error), icon + text so
/// meaning never relies on colour alone.
enum JanBannerTone { info, success, warning, error }

class JanBanner extends StatelessWidget {
  final String message;
  final JanBannerTone tone;
  final IconData? icon;
  final Widget? action;

  const JanBanner({super.key, required this.message, this.tone = JanBannerTone.info, this.icon, this.action});

  @override
  Widget build(BuildContext context) {
    final (Color fg, Color bg, IconData defaultIcon) = switch (tone) {
      JanBannerTone.info => (JanColors.primary, JanColors.infoLight, Icons.info_outline),
      JanBannerTone.success => (JanColors.teal, JanColors.successLight, Icons.check_circle_outline),
      JanBannerTone.warning => (JanColors.amberDark, JanColors.warningLight, Icons.warning_amber_rounded),
      JanBannerTone.error => (JanColors.error, JanColors.errorLight, Icons.error_outline),
    };
    return Semantics(
      liveRegion: tone == JanBannerTone.error || tone == JanBannerTone.warning,
      container: true,
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: JanSpace.md, vertical: JanSpace.sm),
        decoration: BoxDecoration(
          color: bg,
          borderRadius: JanRadius.mdAll,
          border: Border.all(color: fg.withValues(alpha: 0.35)),
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Padding(
              padding: const EdgeInsets.only(top: 1),
              child: Icon(icon ?? defaultIcon, color: fg, size: 20),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: Text(message, style: TextStyle(color: fg, fontWeight: FontWeight.w600, height: 1.4)),
            ),
            if (action != null) action!,
          ],
        ),
      ),
    );
  }
}

/// Success dialog ("Report Submitted") with a primary and optional secondary action.
Future<void> showJanSuccessDialog(
  BuildContext context, {
  required String title,
  required String message,
  String primaryLabel = 'OK',
  VoidCallback? onPrimary,
  String? secondaryLabel,
  VoidCallback? onSecondary,
}) {
  return showDialog<void>(
    context: context,
    builder: (ctx) => Dialog(
      insetPadding: const EdgeInsets.all(JanSpace.xl),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 400),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(JanSpace.xl, JanSpace.xl, JanSpace.xl, JanSpace.lg),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Container(
                width: 64,
                height: 64,
                decoration: const BoxDecoration(color: JanColors.teal, shape: BoxShape.circle),
                child: const Icon(Icons.check_rounded, color: JanColors.white, size: 38),
              ),
              const SizedBox(height: JanSpace.md),
              Semantics(
                header: true,
                liveRegion: true,
                child: Text(
                  title,
                  textAlign: TextAlign.center,
                  style: Theme.of(ctx).textTheme.titleLarge?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
                ),
              ),
              const SizedBox(height: JanSpace.xs),
              Text(message, textAlign: TextAlign.center, style: const TextStyle(color: JanColors.muted, height: 1.45)),
              const SizedBox(height: JanSpace.lg),
              SizedBox(
                width: double.infinity,
                child: FilledButton(
                  onPressed: () {
                    Navigator.of(ctx).pop();
                    onPrimary?.call();
                  },
                  child: Text(primaryLabel),
                ),
              ),
              if (secondaryLabel != null)
                TextButton(
                  onPressed: () {
                    Navigator.of(ctx).pop();
                    onSecondary?.call();
                  },
                  child: Text(secondaryLabel),
                ),
            ],
          ),
        ),
      ),
    ),
  );
}
