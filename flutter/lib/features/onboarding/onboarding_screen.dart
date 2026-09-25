import 'package:flutter/material.dart';

import '../../core/theme/jan_tokens.dart';
import '../../core/widgets/jan_illustrations.dart';
import '../../core/widgets/jan_logo.dart';
import '../../core/widgets/jan_surfaces.dart';

/// First-launch onboarding (post-UI gap fix): the three-step introduction
/// from the approved JanNet AI design reference - Report civic issues /
/// Track every update / Improve your community - with Skip, Next and
/// Get Started. Built only from the existing design-system widgets.
///
/// Shown once per device, before sign-in, and only to someone who is not
/// already signed in (see main.dart's startup gate). [onFinished] is called
/// for both Skip and Get Started; the caller records completion and opens
/// the login screen, so authentication itself is untouched.
class OnboardingScreen extends StatefulWidget {
  final VoidCallback onFinished;

  const OnboardingScreen({super.key, required this.onFinished});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingPage {
  final IconData icon;
  final Color color;
  final String title;
  final String body;
  const _OnboardingPage(this.icon, this.color, this.title, this.body);
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  static const _pages = [
    _OnboardingPage(
      Icons.add_a_photo_outlined,
      JanColors.primary,
      'Report Civic Issues',
      'Take a photo of a pothole, garbage, a broken street light or any civic problem, '
          'add a short note and your location. It takes about a minute.',
    ),
    _OnboardingPage(
      Icons.timeline_rounded,
      JanColors.teal,
      'Track Every Update',
      'JanNet AI routes your report to the right department. Follow each step from '
          'submission to resolution and get notified when the status changes.',
    ),
    _OnboardingPage(
      Icons.diversity_3_outlined,
      JanColors.amberDark,
      'Improve Your Community',
      'Your reports show where help is needed most, so your city can fix problems '
          'faster and build a cleaner, safer neighbourhood.',
    ),
  ];

  final _controller = PageController();
  int _index = 0;

  bool get _isLast => _index == _pages.length - 1;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _next() {
    if (_isLast) {
      widget.onFinished();
      return;
    }
    _controller.nextPage(duration: const Duration(milliseconds: 280), curve: Curves.easeOutCubic);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: JanBackdrop(
        child: SafeArea(
          child: Center(
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 560),
              child: Padding(
                padding: const EdgeInsets.symmetric(horizontal: JanSpace.lg, vertical: JanSpace.md),
                child: Column(
                  children: [
                    Row(
                      children: [
                        const JanLogo(markSize: 30, fontSize: 20),
                        const Spacer(),
                        if (!_isLast)
                          TextButton(
                            onPressed: widget.onFinished,
                            child: const Text('Skip'),
                          ),
                      ],
                    ),
                    Expanded(
                      child: PageView.builder(
                        controller: _controller,
                        itemCount: _pages.length,
                        onPageChanged: (i) => setState(() => _index = i),
                        itemBuilder: (context, i) => _PageBody(page: _pages[i]),
                      ),
                    ),
                    Semantics(
                      label: 'Page ${_index + 1} of ${_pages.length}',
                      excludeSemantics: true,
                      child: Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          for (var i = 0; i < _pages.length; i++)
                            AnimatedContainer(
                              duration: const Duration(milliseconds: 200),
                              margin: const EdgeInsets.symmetric(horizontal: 4),
                              width: i == _index ? 24 : 8,
                              height: 8,
                              decoration: BoxDecoration(
                                color: i == _index ? JanColors.primary : JanColors.border,
                                borderRadius: BorderRadius.circular(4),
                              ),
                            ),
                        ],
                      ),
                    ),
                    const SizedBox(height: JanSpace.lg),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton(
                        onPressed: _next,
                        child: Text(_isLast ? 'Get Started' : 'Next'),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}

class _PageBody extends StatelessWidget {
  final _OnboardingPage page;
  const _PageBody({required this.page});

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(builder: (context, constraints) {
      // Short screens (landscape phones, small browser windows) drop the
      // illustration size and scroll instead of overflowing.
      final illustration = constraints.maxHeight < 420 ? 96.0 : 168.0;
      return SingleChildScrollView(
        child: ConstrainedBox(
          constraints: BoxConstraints(minHeight: constraints.maxHeight),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              JanStateIllustration(icon: page.icon, color: page.color, size: illustration),
              const SizedBox(height: JanSpace.xl),
              Semantics(
                header: true,
                child: Text(
                  page.title,
                  textAlign: TextAlign.center,
                  style: Theme.of(context)
                      .textTheme
                      .headlineMedium
                      ?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
                ),
              ),
              const SizedBox(height: JanSpace.sm),
              Text(
                page.body,
                textAlign: TextAlign.center,
                style: const TextStyle(color: JanColors.slate, fontSize: 16, height: 1.5),
              ),
            ],
          ),
        ),
      );
    });
  }
}
