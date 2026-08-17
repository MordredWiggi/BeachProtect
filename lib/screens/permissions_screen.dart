import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/permissions.dart';
import '../core/theme.dart';

/// Explains every permission before asking for any of them, then walks through
/// granting them one at a time.
///
/// The list itself lives in `core/permissions.dart`, because the home screen
/// keeps reminding the user about whatever is still outstanding and the two
/// must never disagree about what "outstanding" means.
///
/// Deliberately explain-first. An app that fires four system dialogs at a new
/// user within ten seconds of launch gets refused, and once a permission is
/// permanently denied it can only be fixed by digging through Android settings.
class PermissionsScreen extends StatefulWidget {
  const PermissionsScreen({
    super.key,
    this.onFinished,
    this.showAppBar = true,
  });

  /// Called when the required permissions are all in place.
  final VoidCallback? onFinished;
  final bool showAppBar;

  @override
  State<PermissionsScreen> createState() => _PermissionsScreenState();
}

class _PermissionsScreenState extends State<PermissionsScreen>
    with WidgetsBindingObserver {
  PermissionState _granted = const PermissionState.unknown();
  bool _checking = true;
  bool _working = false;
  bool _explained = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Several of these are granted in Android's own settings screens, so the
    // state is re-read whenever we come back to the foreground.
    if (state == AppLifecycleState.resumed) _refresh();
  }

  // =====================================================================

  Future<void> _refresh() async {
    final controller = context.read<GuardController>();
    // The controller owns the answer, so that the home screen's reminder and
    // this screen can never disagree about what is still outstanding.
    await controller.refreshPermissions();
    if (!mounted) return;
    setState(() {
      _granted = controller.permissions;
      _checking = false;
    });
  }

  bool get _requiredDone => _granted.allRequiredGranted;

  int get _outstanding => _granted.missing.length;

  Future<void> _grant(Need need) async {
    final controller = context.read<GuardController>();
    setState(() => _working = true);
    try {
      await requestNeed(need, controller.bridge);
    } finally {
      if (mounted) setState(() => _working = false);
      await _refresh();
    }
  }

  /// Walks through everything still outstanding, one at a time.
  Future<void> _grantAll() async {
    for (final need in Need.values) {
      if (!mounted) return;
      if (_granted[need]) continue;
      await _grant(need);
    }
  }

  // =====================================================================

  @override
  Widget build(BuildContext context) {
    final body = _checking
        ? const Center(child: CircularProgressIndicator())
        : _explained
            ? _checklist()
            : _explanation();

    if (!widget.showAppBar) return body;
    return Scaffold(
      appBar: AppBar(title: const Text('Permissions')),
      body: body,
    );
  }

  // ---- step one: what and why ------------------------------------------

  Widget _explanation() {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(22, 20, 22, 12),
            children: [
              const Text(
                'What BeachProtect needs',
                style: TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 10),
              Text(
                'Android will ask you a few times in a row. Here is exactly '
                'what each one is for, so none of it is a surprise.',
                style: TextStyle(
                  fontSize: 15,
                  height: 1.45,
                  color: context.colors.onSurface.withValues(alpha: 0.68),
                ),
              ),
              const SizedBox(height: 22),
              Container(
                padding: const EdgeInsets.all(14),
                decoration: BoxDecoration(
                  color: context.status.armed.withValues(alpha: 0.1),
                  borderRadius: BorderRadius.circular(14),
                  border: Border.all(
                    color: context.status.armed.withValues(alpha: 0.3),
                  ),
                ),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Icon(Icons.lock_rounded,
                        size: 18, color: context.status.armed),
                    const SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'No internet permission is requested, because the app '
                        'never uses one. Everything happens directly between '
                        'the phones over Bluetooth.',
                        style: TextStyle(
                          fontSize: 13,
                          height: 1.4,
                          color: context.colors.onSurface.withValues(alpha: 0.8),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 22),
              for (final entry in needs.entries)
                _WhyCard(need: entry.value, granted: _granted[entry.key]),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 20),
          child: FilledButton(
            onPressed: () => setState(() => _explained = true),
            child: Text(
              _requiredDone ? 'Review permissions' : 'Set these up',
            ),
          ),
        ),
      ],
    );
  }

  // ---- step two: grant them --------------------------------------------

  Widget _checklist() {
    return Column(
      children: [
        Expanded(
          child: ListView(
            padding: const EdgeInsets.fromLTRB(22, 20, 22, 12),
            children: [
              Text(
                _requiredDone ? 'You are all set' : 'Let it do its job',
                style: const TextStyle(fontSize: 27, fontWeight: FontWeight.w800),
              ),
              const SizedBox(height: 10),
              Text(
                _requiredDone
                    ? _outstanding == 0
                        ? 'Everything is granted.'
                        : 'The essentials are in place. The remaining items are '
                            'optional, but the guard is more reliable with them.'
                    : 'Tap Allow on each one. Android shows its own dialog, or '
                        'opens its settings app for the last two.',
                style: TextStyle(
                  fontSize: 15,
                  height: 1.45,
                  color: context.colors.onSurface.withValues(alpha: 0.68),
                ),
              ),
              const SizedBox(height: 22),
              for (final entry in needs.entries)
                _GrantRow(
                  info: entry.value,
                  granted: _granted[entry.key],
                  busy: _working,
                  onGrant: () => _grant(entry.key),
                ),
              const SizedBox(height: 10),
              TextButton.icon(
                onPressed: () => setState(() => _explained = false),
                icon: const Icon(Icons.help_outline_rounded, size: 18),
                label: const Text('Remind me what these are for'),
              ),
            ],
          ),
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(22, 8, 22, 20),
          child: Column(
            children: [
              if (_outstanding > 0)
                FilledButton.icon(
                  onPressed: _working ? null : _grantAll,
                  icon: _working
                      ? const SizedBox(
                          width: 18,
                          height: 18,
                          child: CircularProgressIndicator(strokeWidth: 2.2),
                        )
                      : const Icon(Icons.check_rounded),
                  label: Text(
                    _working
                        ? 'Waiting for Android...'
                        : 'Grant the remaining $_outstanding',
                  ),
                ),
              if (_outstanding > 0) const SizedBox(height: 8),
              if (widget.onFinished != null)
                (_requiredDone
                    ? FilledButton(
                        onPressed: widget.onFinished,
                        child: const Text('Done'),
                      )
                    : OutlinedButton(
                        onPressed: null,
                        child: const Text('Grant the required ones to continue'),
                      )),
            ],
          ),
        ),
      ],
    );
  }
}

class _WhyCard extends StatelessWidget {
  const _WhyCard({required this.need, required this.granted});

  final NeedInfo need;
  final bool granted;

  @override
  Widget build(BuildContext context) {
    final tint = need.required ? context.colors.primary : context.status.disarmed;
    return Container(
      margin: const EdgeInsets.only(bottom: 14),
      padding: const EdgeInsets.all(15),
      decoration: BoxDecoration(
        color: context.colors.surfaceContainer,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: context.status.hairline),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(need.icon, size: 20, color: tint),
              const SizedBox(width: 10),
              Expanded(
                child: Text(
                  need.title,
                  style: const TextStyle(
                    fontSize: 15.5,
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              if (granted)
                Icon(Icons.check_circle_rounded,
                    size: 18, color: context.status.armed)
              else
                Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: tint.withValues(alpha: 0.14),
                    borderRadius: BorderRadius.circular(7),
                  ),
                  child: Text(
                    need.required ? 'required' : 'optional',
                    style: TextStyle(
                      fontSize: 11,
                      fontWeight: FontWeight.w700,
                      color: tint,
                    ),
                  ),
                ),
            ],
          ),
          const SizedBox(height: 8),
          Text(
            need.why,
            style: TextStyle(
              fontSize: 13.5,
              height: 1.45,
              color: context.colors.onSurface.withValues(alpha: 0.72),
            ),
          ),
          const SizedBox(height: 7),
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(Icons.subdirectory_arrow_right_rounded,
                  size: 15,
                  color: context.colors.onSurface.withValues(alpha: 0.45)),
              const SizedBox(width: 6),
              Expanded(
                child: Text(
                  need.consequence,
                  style: TextStyle(
                    fontSize: 12.5,
                    height: 1.4,
                    fontStyle: FontStyle.italic,
                    color: context.colors.onSurface.withValues(alpha: 0.55),
                  ),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _GrantRow extends StatelessWidget {
  const _GrantRow({
    required this.info,
    required this.granted,
    required this.busy,
    required this.onGrant,
  });

  final NeedInfo info;
  final bool granted;
  final bool busy;
  final VoidCallback onGrant;

  @override
  Widget build(BuildContext context) {
    final tint = granted
        ? context.status.armed
        : info.required
            ? context.status.alarm
            : context.status.suspicious;

    return Container(
      margin: const EdgeInsets.only(bottom: 12),
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: context.colors.surfaceContainer,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: granted ? context.status.hairline : tint.withValues(alpha: 0.4),
        ),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.center,
        children: [
          Icon(granted ? Icons.check_circle_rounded : info.icon,
              color: tint, size: 22),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  info.title,
                  style: const TextStyle(
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  granted
                      ? 'Granted'
                      : info.required
                          ? 'Required'
                          : 'Recommended',
                  style: TextStyle(
                    fontSize: 12.5,
                    fontWeight: FontWeight.w600,
                    color: tint,
                  ),
                ),
              ],
            ),
          ),
          if (!granted)
            FilledButton(
              onPressed: busy ? null : onGrant,
              style: FilledButton.styleFrom(
                minimumSize: const Size(0, 38),
                padding: const EdgeInsets.symmetric(horizontal: 18),
                backgroundColor: tint,
              ),
              child: const Text('Allow'),
            ),
        ],
      ),
    );
  }
}
