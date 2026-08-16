import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'core/guard_controller.dart';
import 'core/theme.dart';
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

    if (controller.needsSetup) return const OnboardingScreen();

    return const HomeScreen();
  }
}
