import 'package:flutter/material.dart';

import '../theme/jan_tokens.dart';
import 'jan_illustrations.dart';
import 'jan_logo.dart';
import 'jan_surfaces.dart';

/// One navigation destination in a role's home shell.
class JanDestination {
  final String label;
  final IconData icon;
  final IconData selectedIcon;
  final WidgetBuilder builder;

  /// Large page heading shown above the page; null hides it (for pages that
  /// render their own greeting/heading).
  final String? heading;
  final String? subheading;

  const JanDestination({
    required this.label,
    required this.icon,
    IconData? selectedIcon,
    required this.builder,
    this.heading,
    this.subheading,
  }) : selectedIcon = selectedIcon ?? icon;
}

/// A top-bar action (notifications, settings, appeals ...).
class JanShellAction {
  final IconData icon;
  final String tooltip;
  final VoidCallback onPressed;

  const JanShellAction({required this.icon, required this.tooltip, required this.onPressed});
}

/// Adaptive home shell shared by every role (citizen, officer, verification,
/// department head, admin):
///  * phones/tablets: JanNet top bar + floating rounded bottom navigation;
///  * web/desktop (>= [JanBreakpoints.expanded]): navy side navigation rail
///    with the logo and role, a slim top bar, and width-capped content -
///    the reference "Citizen Dashboard" web layout.
/// Only the selected page is built (same behaviour as the previous shells).
class JanShell extends StatelessWidget {
  final String roleLabel;
  final List<JanDestination> destinations;
  final int currentIndex;
  final ValueChanged<int> onDestinationSelected;
  final List<JanShellAction> actions;
  final VoidCallback onLogout;

  const JanShell({
    super.key,
    required this.roleLabel,
    required this.destinations,
    required this.currentIndex,
    required this.onDestinationSelected,
    required this.actions,
    required this.onLogout,
  });

  @override
  Widget build(BuildContext context) {
    final destination = destinations[currentIndex];
    final page = KeyedSubtree(key: ValueKey<int>(currentIndex), child: destination.builder(context));
    final content = Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        if (destination.heading != null)
          ResponsiveCenter(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.xs, JanSpace.md, JanSpace.xs),
              child: JanPageHeading(title: destination.heading!, subtitle: destination.subheading),
            ),
          ),
        Expanded(child: ResponsiveCenter(child: page)),
      ],
    );

    if (JanBreakpoints.isExpanded(context)) {
      return Scaffold(
        body: Row(
          children: [
            _SideRail(
              roleLabel: roleLabel,
              destinations: destinations,
              currentIndex: currentIndex,
              onSelect: onDestinationSelected,
              onLogout: onLogout,
            ),
            Expanded(
              child: Column(
                children: [
                  _WideTopBar(title: destination.label, actions: actions),
                  Expanded(child: JanBackdrop(child: content)),
                ],
              ),
            ),
          ],
        ),
      );
    }

    return Scaffold(
      appBar: AppBar(
        // Scales down rather than overflowing when a role has several actions.
        title: const FittedBox(
          fit: BoxFit.scaleDown,
          alignment: Alignment.centerLeft,
          child: JanLogo(markSize: 30, fontSize: 20),
        ),
        actions: [
          for (final a in actions) IconButton(onPressed: a.onPressed, icon: Icon(a.icon), tooltip: a.tooltip),
          IconButton(onPressed: onLogout, icon: const Icon(Icons.logout_rounded), tooltip: 'Sign out'),
          const SizedBox(width: 4),
        ],
      ),
      body: JanBackdrop(child: SafeArea(top: false, bottom: false, child: content)),
      bottomNavigationBar: SafeArea(
        minimum: const EdgeInsets.fromLTRB(JanSpace.sm, 0, JanSpace.sm, JanSpace.sm),
        child: DecoratedBox(
          decoration: BoxDecoration(
            color: JanColors.white,
            borderRadius: JanRadius.xlAll,
            border: Border.all(color: JanColors.divider),
            boxShadow: JanShadows.raised,
          ),
          child: ClipRRect(
            borderRadius: JanRadius.xlAll,
            child: NavigationBar(
              selectedIndex: currentIndex,
              onDestinationSelected: onDestinationSelected,
              labelBehavior: NavigationDestinationLabelBehavior.alwaysShow,
              destinations: [
                for (final d in destinations)
                  NavigationDestination(icon: Icon(d.icon), selectedIcon: Icon(d.selectedIcon), label: d.label),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _WideTopBar extends StatelessWidget {
  final String title;
  final List<JanShellAction> actions;
  const _WideTopBar({required this.title, required this.actions});

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 68,
      padding: const EdgeInsets.symmetric(horizontal: JanSpace.xl),
      decoration: const BoxDecoration(
        color: JanColors.white,
        border: Border(bottom: BorderSide(color: JanColors.divider)),
      ),
      child: Row(
        children: [
          Expanded(
            child: Semantics(
              header: true,
              child: Text(title, style: Theme.of(context).textTheme.titleLarge),
            ),
          ),
          for (final a in actions) IconButton(onPressed: a.onPressed, icon: Icon(a.icon), tooltip: a.tooltip),
        ],
      ),
    );
  }
}

class _SideRail extends StatelessWidget {
  final String roleLabel;
  final List<JanDestination> destinations;
  final int currentIndex;
  final ValueChanged<int> onSelect;
  final VoidCallback onLogout;

  const _SideRail({
    required this.roleLabel,
    required this.destinations,
    required this.currentIndex,
    required this.onSelect,
    required this.onLogout,
  });

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 248,
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [JanColors.navy, JanColors.navyDeep],
        ),
      ),
      child: SafeArea(
        child: Stack(
          children: [
            const Positioned(
              left: 0,
              right: 0,
              bottom: 64,
              height: 120,
              child: ExcludeSemantics(child: CustomPaint(painter: SkylinePainter(color: Color(0x335C9FD8)))),
            ),
            Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const Padding(
                  padding: EdgeInsets.fromLTRB(20, 22, 20, 8),
                  // Scales down instead of overflowing the fixed 248 px rail
                  // (large system text, wide fonts such as the test font).
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: FittedBox(
                      fit: BoxFit.scaleDown,
                      alignment: Alignment.centerLeft,
                      child: JanLogo(markSize: 32, fontSize: 21, onDark: true),
                    ),
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(22, 0, 20, 18),
                  child: Text(
                    roleLabel.toUpperCase(),
                    style: const TextStyle(color: Color(0xFFB9CCE3), fontSize: 11.5, fontWeight: FontWeight.w700, letterSpacing: 1.1),
                  ),
                ),
                Expanded(
                  child: ListView(
                    padding: const EdgeInsets.symmetric(horizontal: 12),
                    children: [
                      for (var i = 0; i < destinations.length; i++)
                        _RailItem(
                          destination: destinations[i],
                          selected: i == currentIndex,
                          onTap: () => onSelect(i),
                        ),
                    ],
                  ),
                ),
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: TextButton.icon(
                    onPressed: onLogout,
                    style: TextButton.styleFrom(
                      foregroundColor: const Color(0xFFDDE7F3),
                      alignment: Alignment.centerLeft,
                      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 14),
                    ),
                    icon: const Icon(Icons.logout_rounded),
                    label: const Text('Sign out'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _RailItem extends StatelessWidget {
  final JanDestination destination;
  final bool selected;
  final VoidCallback onTap;

  const _RailItem({required this.destination, required this.selected, required this.onTap});

  @override
  Widget build(BuildContext context) {
    final fg = selected ? JanColors.white : const Color(0xFFC9D8EA);
    return Padding(
      padding: const EdgeInsets.only(bottom: 4),
      child: Semantics(
        selected: selected,
        button: true,
        label: destination.label,
        excludeSemantics: true,
        child: Material(
          color: selected ? const Color(0x2E9CC5EC) : Colors.transparent,
          borderRadius: JanRadius.mdAll,
          child: InkWell(
            borderRadius: JanRadius.mdAll,
            onTap: onTap,
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 13),
              child: Row(
                children: [
                  Icon(selected ? destination.selectedIcon : destination.icon, color: fg, size: 22),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      destination.label,
                      style: TextStyle(color: fg, fontSize: 15, fontWeight: selected ? FontWeight.w700 : FontWeight.w500),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
