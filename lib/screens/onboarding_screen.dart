import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/theme.dart';
import 'permissions_screen.dart';
import 'qr_scan_screen.dart';

/// First-run flow.
///
/// Four steps, in the order that lets someone actually get protected: who you
/// are, which group, how you prove it is you, and what Android needs to let the
/// guard run. Nothing here can be skipped in a way that leaves a guard that
/// silently does not work.
///
/// The permissions walkthrough is deliberately *inside* the first run rather
/// than something to discover later. Three of the six grants live in Android's
/// own settings app, and an app that never asks is an app whose guard is
/// suspended by the battery manager an hour later, with nothing on screen to
/// say why.
///
/// Completion is recorded natively, not inferred. It used to be inferred from
/// "does a group exist", which becomes true at step two - so the root widget
/// swapped the whole flow out for the home screen the instant the group was
/// created, and the user got two steps of a four step progress bar and never
/// saw the PIN or permissions pages at all.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  late final PageController _pageController;
  final _nameController = TextEditingController();
  final _groupNameController = TextEditingController(text: 'Beach day');
  final _codeController = TextEditingController();
  final _pinController = TextEditingController();

  int _page = 0;
  bool _joining = false;
  bool _busy = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    // A first run that was interrupted - the phone rang, Android's own
    // settings app took over for the battery exemption - resumes where it
    // stopped, rather than asking again for a name and a group that already
    // exist.
    final settings = context.read<GuardController>().settings;
    if (settings.selfName.isNotEmpty) _nameController.text = settings.selfName;
    if (settings.groupName.isNotEmpty) {
      _groupNameController.text = settings.groupName;
    }
    if (settings.hasGroup) _page = settings.hasPin ? 3 : 2;
    _pageController = PageController(initialPage: _page);
  }

  @override
  void dispose() {
    _pageController.dispose();
    _nameController.dispose();
    _groupNameController.dispose();
    _codeController.dispose();
    _pinController.dispose();
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
      _groupPage(),
      _pinPage(),
      PermissionsScreen(
        showAppBar: false,
        onFinished: () async {
          final controller = context.read<GuardController>();
          await controller.bridge.startService();
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
                  // Only from the group page: every later step has already
                  // written something (a group, a PIN) that going back would
                  // offer to create a second time.
                  if (_page == 1)
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

  Widget _shell({
    required String title,
    required String subtitle,
    required List<Widget> children,
    required Widget action,
  }) {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(22, 24, 22, 12),
            children: [
              Text(
                title,
                style: const TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              Text(
                subtitle,
                style: TextStyle(
                  fontSize: 15,
                  height: 1.45,
                  color: context.colors.onSurface.withValues(alpha: 0.68),
                ),
              ),
              const SizedBox(height: 26),
              ...children,
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
          child: action,
        ),
      ],
    );
  }

  Widget _welcomePage() {
    return _shell(
      title: 'Watch each other\'s things',
      subtitle:
          'Every phone in the group keeps a quiet Bluetooth eye on every other '
          'phone. If one gets carried off, the speaker and the phones all sound '
          'off at once.',
      children: [
        const _Bullet(
          icon: Icons.sensors_rounded,
          title: 'Signal strength between phones',
          body: 'Each phone tracks how strong the others are, and notices when '
              'one starts receding.',
        ),
        const _Bullet(
          icon: Icons.vibration_rounded,
          title: 'Cross-checked with movement',
          body: 'A phone only counts as "leaving" if its own accelerometer '
              'agrees it is moving. That is what stops people walking past from '
              'setting it off.',
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
          'Then: your group, a PIN for switching the guard off, and the '
          'permissions Android needs before any of it can run. About a minute '
          'in total, and it is all explained as you go.',
          style: TextStyle(
            fontSize: 13,
            height: 1.4,
            color: context.colors.onSurface.withValues(alpha: 0.6),
          ),
        ),
      ],
      action: FilledButton(
        onPressed: () {
          if (_nameController.text.trim().isEmpty) {
            setState(() => _error = 'Please enter a name first.');
            return;
          }
          setState(() => _error = null);
          _go(1);
        },
        child: const Text('Continue'),
      ),
    );
  }

  Widget _groupPage() {
    return _shell(
      title: _joining ? 'Join a group' : 'Start a group',
      subtitle: _joining
          ? 'Scan the QR from a phone that already has the group, or type its '
              'code.'
          : 'One person creates the group and shares the code. Everyone else '
              'joins it.',
      children: [
        SegmentedButton<bool>(
          segments: const [
            ButtonSegment(value: false, label: Text('Create'), icon: Icon(Icons.add_rounded)),
            ButtonSegment(value: true, label: Text('Join'), icon: Icon(Icons.qr_code_scanner_rounded)),
          ],
          selected: {_joining},
          onSelectionChanged: (value) => setState(() {
            _joining = value.first;
            _error = null;
          }),
        ),
        const SizedBox(height: 22),
        if (_joining) ...[
          FilledButton.icon(
            onPressed: _scanQr,
            icon: const Icon(Icons.qr_code_scanner_rounded),
            label: const Text('Scan the group QR'),
          ),
          const SizedBox(height: 16),
          Row(
            children: [
              const Expanded(child: Divider()),
              Padding(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                child: Text(
                  'or type it',
                  style: TextStyle(
                    fontSize: 12.5,
                    color: context.colors.onSurface.withValues(alpha: 0.5),
                  ),
                ),
              ),
              const Expanded(child: Divider()),
            ],
          ),
          const SizedBox(height: 16),
          TextField(
            controller: _codeController,
            textCapitalization: TextCapitalization.characters,
            style: const TextStyle(
              fontFamily: 'monospace',
              fontSize: 19,
              letterSpacing: 2,
            ),
            decoration: const InputDecoration(
              labelText: 'Group code',
              hintText: 'ABCD-EFGH-JKMN-PQRS',
            ),
          ),
        ] else
          TextField(
            controller: _groupNameController,
            textCapitalization: TextCapitalization.sentences,
            decoration: const InputDecoration(
              labelText: 'Group name',
              helperText: 'Just so you can tell groups apart.',
            ),
          ),
      ],
      action: FilledButton(
        onPressed: _busy ? null : _submitGroup,
        child: _busy
            ? const SizedBox(
                height: 20,
                width: 20,
                child: CircularProgressIndicator(strokeWidth: 2.4),
              )
            : Text(_joining ? 'Join group' : 'Create group'),
      ),
    );
  }

  Widget _pinPage() {
    return _shell(
      title: 'Set a group PIN',
      subtitle:
          'Needed to switch the guard off. Share it with the group so anyone '
          'can silence a false alarm - and nobody else can.',
      children: [
        TextField(
          controller: _pinController,
          keyboardType: TextInputType.number,
          obscureText: true,
          maxLength: 8,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          style: const TextStyle(fontSize: 22, letterSpacing: 6),
          decoration: const InputDecoration(
            labelText: 'PIN',
            helperText: '4 to 8 digits.',
          ),
        ),
        const SizedBox(height: 8),
        Text(
          'You can switch to a fingerprint later in Settings; the PIN stays as '
          'the fallback.',
          style: TextStyle(
            fontSize: 13,
            height: 1.4,
            color: context.colors.onSurface.withValues(alpha: 0.6),
          ),
        ),
      ],
      action: FilledButton(
        onPressed: () async {
          final pin = _pinController.text.trim();
          if (pin.length < 4) {
            setState(() => _error = 'Use at least 4 digits.');
            return;
          }
          final controller = context.read<GuardController>();
          await controller.bridge.setPin(pin);
          await controller.refreshSettings();
          if (!mounted) return;
          setState(() => _error = null);
          _go(3);
        },
        child: const Text('Continue'),
      ),
    );
  }

  // =====================================================================

  Future<void> _scanQr() async {
    final code = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const QrScanScreen()),
    );
    if (code != null && mounted) {
      _codeController.text = code;
      await _submitGroup();
    }
  }

  Future<void> _submitGroup() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    final controller = context.read<GuardController>();
    try {
      if (_joining) {
        final ok = await controller.joinGroup(
          code: _codeController.text.trim(),
          groupName: _groupNameController.text.trim(),
          selfName: _nameController.text.trim(),
        );
        if (!ok) {
          setState(() => _error = 'That code is not valid. Check it and try again.');
          return;
        }
      } else {
        await controller.createGroup(
          groupName: _groupNameController.text.trim().isEmpty
              ? 'Beach day'
              : _groupNameController.text.trim(),
          selfName: _nameController.text.trim(),
        );
      }
      if (!mounted) return;
      _go(2);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
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
                  style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
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
