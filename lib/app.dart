import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'core/guard_controller.dart';
import 'core/theme.dart';
import 'screens/group_gate_screen.dart';
import 'screens/home_screen.dart';
import 'screens/onboarding_screen.dart';

class BeachProtectApp extends StatelessWidget {
  const BeachProtectApp({super.key});

  @override
  Widget build(BuildContext context) {
    return ChangeNotifierProvider(
      create: (_) => GuardController()..initialise(),
      child: MaterialApp(
        title: 'BeachProtect',
        debugShowCheckedModeBanner: false,
        theme: BpTheme.light(),
        darkTheme: BpTheme.dark(),
        themeMode: ThemeMode.system,
        home: const _Root(),
      ),
    );
  }
}

/// Decides which of the app's three top-level surfaces is showing.
///
/// The order is deliberate, and the split between the first two is the point.
/// Setting the phone up happens once. Having a group is a state the app moves
/// in and out of — so leaving one drops the user on the group screen, not back
/// into a welcome wizard asking for a name they set weeks ago.
class _Root extends StatelessWidget {
  const _Root();

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();

    if (controller.loading) {
      return const Scaffold(
        body: Center(child: CircularProgressIndicator()),
      );
    }

    if (controller.needsOnboarding) return const OnboardingScreen();
    if (controller.needsGroup) return const GroupGateScreen();

    return const HomeScreen();
  }
}
