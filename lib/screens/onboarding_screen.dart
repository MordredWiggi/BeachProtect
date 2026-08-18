import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/theme.dart';
import 'permissions_screen.dart';

/// First run, and only ever the first run.
///
/// Two steps, and both of them are about *this phone*: who you are, and what
/// Android has to allow before any of it can work. Neither has anything to do
/// with which group you happen to be in, so neither is asked again when that
/// changes — leaving a group takes you to the group screen, not back here.
///
/// The permissions walkthrough is deliberately *inside* the first run rather
/// than something to discover later. Three of the six grants live in Android's
/// own settings app, and an app that never asks is an app whose guard is
/// suspended by the battery manager an hour later, with nothing on screen to
/// say why.
///
/// Completion is recorded natively at the end of the last step and nowhere
/// else, so a first run interrupted half way through resumes rather than
/// counting as done. It used to be inferred from "does a group exist", which
/// became true in the middle of the wizard and swapped the whole thing out for
/// the home screen — the permissions pages were never seen at all.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  late final PageController _pageController;
  final _nameController = TextEditingController();

  int _page = 0;
  String? _error;

  @override
  void initState() {
    super.initState();
    // A first run that was interrupted — the phone rang, Android's own settings
    // app took over for the battery exemption — resumes where it stopped.
    final settings = context.read<GuardController>().settings;
    if (settings.selfName.isNotEmpty) {
      _nameController.text = settings.selfName;
      _page = 1;
    }
    _pageController = PageController(initialPage: _page);
  }

  @override
  void dispose() {
    _pageController.dispose();
    _nameController.dispose();
    super.dispose();
  }

  void _go(int page) {
    setState(() => _page = page);
    _pageController.animateToPage(
      page,
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutCubic,
    );
  }

  @override
  Widget build(BuildContext context) {
    final pages = <Widget>[
      _welcomePage(),
      PermissionsScreen(
        showAppBar: false,
        onFinished: () async {
          final controller = context.read<GuardController>();
          // Recorded last, and only here: this is the one moment we know the
          // whole walkthrough has actually been seen.
          await controller.completeOnboarding();
        },
      ),
    ];

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: Row(
                children: [
                  for (var i = 0; i < pages.length; i++) ...[
                    Expanded(
                      child: AnimatedContainer(
                        duration: const Duration(milliseconds: 250),
                        height: 4,
                        decoration: BoxDecoration(
                          color: i <= _page
                              ? context.colors.primary
                              : context.status.subtle,
                          borderRadius: BorderRadius.circular(2),
                        ),
                      ),
                    ),
                    if (i < pages.length - 1) const SizedBox(width: 6),
                  ],
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 8, 20, 0),
              child: Row(
                mainAxisAlignment: MainAxisAlignment.spaceBetween,
                children: [
                  Text(
                    'Step ${_page + 1} of ${pages.length}',
                    style: TextStyle(
                      fontSize: 12,
                      fontWeight: FontWeight.w700,
                      letterSpacing: 0.3,
                      color: context.colors.onSurface.withValues(alpha: 0.5),
                    ),
                  ),
                  if (_page > 0)
                    TextButton(
                      onPressed: () => _go(_page - 1),
                      style: TextButton.styleFrom(
                        minimumSize: const Size(0, 32),
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                      ),
                      child: const Text('Back'),
                    ),
                ],
              ),
            ),
            Expanded(
              child: PageView(
                controller: _pageController,
                physics: const NeverScrollableScrollPhysics(),
                children: pages,
              ),
            ),
          ],
        ),
      ),
    );
  }

  // =====================================================================

  Widget _welcomePage() {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(22, 24, 22, 12),
            children: [
              const Text(
                'Watch each other\'s things',
                style: TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              Text(
                'Every phone in the group keeps a quiet Bluetooth eye on every '
                'other phone. If one gets carried off, the speaker and the '
                'phones all sound off at once.',
                style: TextStyle(
                  fontSize: 15,
                  height: 1.45,
                  color: context.colors.onSurface.withValues(alpha: 0.68),
                ),
              ),
              const SizedBox(height: 26),
              const _Bullet(
                icon: Icons.sensors_rounded,
                title: 'Signal strength between phones',
                body: 'Each phone tracks how strong the others are, and notices '
                    'when one starts receding.',
              ),
              const _Bullet(
                icon: Icons.vibration_rounded,
                title: 'Cross-checked with movement',
                body: 'A phone only counts as "leaving" if its own accelerometer '
                    'agrees it is moving. That is what stops people walking past '
                    'from setting it off.',
              ),
              const _Bullet(
                icon: Icons.groups_rounded,
                title: 'Several phones must agree',
                body: 'One flaky radio is never enough to start a siren.',
              ),
              const SizedBox(height: 20),
              TextField(
                controller: _nameController,
                textCapitalization: TextCapitalization.words,
                maxLength: 12,
                decoration: const InputDecoration(
                  labelText: 'Your name',
                  helperText: 'Shown to the others. Up to 12 characters.',
                ),
              ),
              const SizedBox(height: 10),
              Text(
                'Then the permissions Android needs before any of it can run. '
                'After that you create or join a group, and you are protected.',
                style: TextStyle(
                  fontSize: 13,
                  height: 1.4,
                  color: context.colors.onSurface.withValues(alpha: 0.6),
                ),
              ),
              if (_error != null) ...[
                const SizedBox(height: 16),
                Text(
                  _error!,
                  style: TextStyle(color: context.status.alarm, fontSize: 14),
                ),
              ],
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 20),
          child: FilledButton(
            onPressed: _submitName,
            child: const Text('Continue'),
          ),
        ),
      ],
    );
  }

  Future<void> _submitName() async {
    final name = _nameController.text.trim();
    if (name.isEmpty) {
      setState(() => _error = 'Please enter a name first.');
      return;
    }
    // Persisted here rather than when a group is created, because creating one
    // is no longer part of this flow — and an unsaved name is a phone that
    // introduces itself to the group as a hexadecimal id.
    await context.read<GuardController>().patch({'selfName': name});
    if (!mounted) return;
    setState(() => _error = null);
    _go(1);
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet({required this.icon, required this.title, required this.body});

  final IconData icon;
  final String title;
  final String body;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 18),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Container(
            width: 38,
            height: 38,
            decoration: BoxDecoration(
              color: context.colors.primary.withValues(alpha: 0.14),
              borderRadius: BorderRadius.circular(12),
            ),
            child: Icon(icon, size: 20, color: context.colors.primary),
          ),
          const SizedBox(width: 14),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  title,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 3),
                Text(
                  body,
                  style: TextStyle(
                    fontSize: 13.5,
                    height: 1.4,
                    color: context.colors.onSurface.withValues(alpha: 0.65),
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
