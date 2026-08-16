import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/theme.dart';
import 'qr_scan_screen.dart';

/// First-run flow.
///
/// Four steps, in the order that lets someone actually get protected: who you
/// are, which group, how you prove it is you, and what Android needs to let the
/// guard run. Nothing here can be skipped in a way that leaves a guard that
/// silently does not work.
class OnboardingScreen extends StatefulWidget {
  const OnboardingScreen({super.key});

  @override
  State<OnboardingScreen> createState() => _OnboardingScreenState();
}

class _OnboardingScreenState extends State<OnboardingScreen> {
  final _pageController = PageController();
  final _nameController = TextEditingController();
  final _groupNameController = TextEditingController(text: 'Beach day');
  final _codeController = TextEditingController();
  final _pinController = TextEditingController();

  int _page = 0;
  bool _joining = false;
  bool _busy = false;
  String? _error;

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
    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 16, 20, 8),
              child: Row(
                children: [
                  for (var i = 0; i < 4; i++) ...[
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
                    if (i < 3) const SizedBox(width: 6),
                  ],
                ],
              ),
            ),
            Expanded(
              child: PageView(
                controller: _pageController,
                physics: const NeverScrollableScrollPhysics(),
                children: [
                  _welcomePage(),
                  _groupPage(),
                  _pinPage(),
                  const _PermissionsPage(),
                ],
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

/// Final onboarding step, and also reachable later from Settings.
class _PermissionsPage extends StatefulWidget {
  const _PermissionsPage();

  @override
  State<_PermissionsPage> createState() => _PermissionsPageState();
}

class _PermissionsPageState extends State<_PermissionsPage> {
  bool _bluetoothGranted = false;
  bool _notificationsGranted = false;
  bool _batteryExempt = false;
  bool _fullScreenAllowed = true;
  bool _bluetoothOn = false;
  bool _checking = true;

  @override
  void initState() {
    super.initState();
    _refresh();
  }

  Future<void> _refresh() async {
    final controller = context.read<GuardController>();
    final scan = await Permission.bluetoothScan.status;
    final advertise = await Permission.bluetoothAdvertise.status;
    final connect = await Permission.bluetoothConnect.status;
    final location = await Permission.locationWhenInUse.status;
    final notifications = await Permission.notification.status;

    // Android 12 split Bluetooth out of the location permission. On anything
    // older, a BLE scan is still legally a location request.
    final bluetoothOk = (scan.isGranted && advertise.isGranted && connect.isGranted) ||
        location.isGranted;

    final exempt = await controller.bridge.isIgnoringBatteryOptimizations();
    final fullScreen = await controller.bridge.canUseFullScreenIntent();
    final btOn = await controller.bridge.bluetoothEnabled();

    if (!mounted) return;
    setState(() {
      _bluetoothGranted = bluetoothOk;
      _notificationsGranted = notifications.isGranted;
      _batteryExempt = exempt;
      _fullScreenAllowed = fullScreen;
      _bluetoothOn = btOn;
      _checking = false;
    });
  }

  Future<void> _requestBluetooth() async {
    await [
      Permission.bluetoothScan,
      Permission.bluetoothAdvertise,
      Permission.bluetoothConnect,
      Permission.locationWhenInUse,
    ].request();
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final ready = _bluetoothGranted && _notificationsGranted && _bluetoothOn;
    final controller = context.read<GuardController>();

    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(22, 24, 22, 12),
            children: [
              const Text(
                'Let it do its job',
                style: TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 8),
              Text(
                'The first two are required. The last two are what stop Android '
                'from quietly killing the guard while you swim.',
                style: TextStyle(
                  fontSize: 15,
                  height: 1.45,
                  color: context.colors.onSurface.withValues(alpha: 0.68),
                ),
              ),
              const SizedBox(height: 24),
              if (_checking)
                const Center(child: Padding(
                  padding: EdgeInsets.all(24),
                  child: CircularProgressIndicator(),
                ))
              else ...[
                _PermissionRow(
                  icon: Icons.bluetooth_rounded,
                  title: 'Bluetooth',
                  body: 'Required. The entire protocol runs on Bluetooth LE '
                      'advertising - no internet, no accounts.',
                  granted: _bluetoothGranted,
                  onFix: _requestBluetooth,
                ),
                _PermissionRow(
                  icon: Icons.power_settings_new_rounded,
                  title: 'Bluetooth switched on',
                  body: 'Nothing works while the radio is off.',
                  granted: _bluetoothOn,
                  onFix: () async {
                    await controller.bridge.requestEnableBluetooth();
                    await Future<void>.delayed(const Duration(seconds: 2));
                    await _refresh();
                  },
                ),
                _PermissionRow(
                  icon: Icons.notifications_rounded,
                  title: 'Notifications',
                  body: 'Required. Android only lets an app run in the '
                      'background if it shows an ongoing notification.',
                  granted: _notificationsGranted,
                  onFix: () async {
                    await Permission.notification.request();
                    await _refresh();
                  },
                ),
                _PermissionRow(
                  icon: Icons.battery_charging_full_rounded,
                  title: 'Ignore battery optimisation',
                  body: 'Strongly recommended. Without it Android may put the '
                      'guard to sleep after a while, which is exactly when you '
                      'are furthest from your towel.',
                  granted: _batteryExempt,
                  optional: true,
                  onFix: () async {
                    await controller.bridge.requestIgnoreBatteryOptimizations();
                    await Future<void>.delayed(const Duration(seconds: 1));
                    await _refresh();
                  },
                ),
                _PermissionRow(
                  icon: Icons.screen_lock_portrait_rounded,
                  title: 'Full screen alarms',
                  body: 'Lets the disarm screen appear over the lock screen. '
                      'Without it you still get a heads-up notification and the '
                      'siren still sounds.',
                  granted: _fullScreenAllowed,
                  optional: true,
                  onFix: () async {
                    await controller.bridge.openFullScreenIntentSettings();
                    await Future<void>.delayed(const Duration(seconds: 1));
                    await _refresh();
                  },
                ),
              ],
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 20),
          child: Column(
            children: [
              FilledButton(
                onPressed: ready
                    ? () async {
                        await controller.bridge.startService();
                        await controller.refreshSettings();
                      }
                    : null,
                child: const Text('Done'),
              ),
              if (!ready)
                Padding(
                  padding: const EdgeInsets.only(top: 8),
                  child: Text(
                    'Grant Bluetooth, notifications and switch Bluetooth on to finish.',
                    textAlign: TextAlign.center,
                    style: TextStyle(
                      fontSize: 12.5,
                      color: context.colors.onSurface.withValues(alpha: 0.55),
                    ),
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }
}

class _PermissionRow extends StatelessWidget {
  const _PermissionRow({
    required this.icon,
    required this.title,
    required this.body,
    required this.granted,
    required this.onFix,
    this.optional = false,
  });

  final IconData icon;
  final String title;
  final String body;
  final bool granted;
  final bool optional;
  final Future<void> Function() onFix;

  @override
  Widget build(BuildContext context) {
    final tint = granted
        ? context.status.armed
        : optional
            ? context.status.suspicious
            : context.status.alarm;
    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: context.colors.surfaceContainer,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: context.status.hairline),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(granted ? Icons.check_circle_rounded : icon, color: tint, size: 22),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(title,
                    style: const TextStyle(
                        fontSize: 15, fontWeight: FontWeight.w700)),
                const SizedBox(height: 3),
                Text(
                  body,
                  style: TextStyle(
                    fontSize: 13,
                    height: 1.38,
                    color: context.colors.onSurface.withValues(alpha: 0.62),
                  ),
                ),
              ],
            ),
          ),
          if (!granted)
            TextButton(
              onPressed: onFix,
              style: TextButton.styleFrom(foregroundColor: tint),
              child: const Text('Allow'),
            ),
        ],
      ),
    );
  }
}
