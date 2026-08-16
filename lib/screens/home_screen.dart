import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/models.dart';
import '../core/theme.dart';
import '../widgets/common.dart';
import '../widgets/peer_card.dart';
import '../widgets/shield_button.dart';
import 'box_setup_screen.dart';
import 'group_screen.dart';
import 'settings_screen.dart';
import 'simulator_screen.dart';

class HomeScreen extends StatelessWidget {
  const HomeScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();
    final snapshot = controller.snapshot;
    final settings = controller.settings;
    final status = context.status;

    return Scaffold(
      appBar: AppBar(
        titleSpacing: 20,
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            const Text('BeachProtect'),
            if (settings.groupName.isNotEmpty)
              Text(
                settings.groupName,
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w600,
                  color: context.colors.onSurface.withValues(alpha: 0.55),
                ),
              ),
          ],
        ),
        actions: [
          if (settings.simulationEnabled)
            IconButton(
              tooltip: 'Test scenarios',
              icon: const Icon(Icons.science_rounded),
              onPressed: () => Navigator.of(context).push(
                MaterialPageRoute(builder: (_) => const SimulatorScreen()),
              ),
            ),
          IconButton(
            tooltip: 'Group',
            icon: const Icon(Icons.groups_rounded),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const GroupScreen()),
            ),
          ),
          IconButton(
            tooltip: 'Settings',
            icon: const Icon(Icons.settings_rounded),
            onPressed: () => Navigator.of(context).push(
              MaterialPageRoute(builder: (_) => const SettingsScreen()),
            ),
          ),
          const SizedBox(width: 4),
        ],
      ),
      body: SafeArea(
        top: false,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 4, 16, 40),
          children: [
            const SizedBox(height: 4),
            Center(
              child: ShieldButton(
                state: snapshot.state,
                pendingRemainingMs: snapshot.pendingRemainingMs,
                onTap: () => _toggle(context, controller),
              ),
            ),
            const SizedBox(height: 18),
            _StatusText(snapshot: snapshot),
            const SizedBox(height: 20),

            if (snapshot.state == GuardState.alarm) ...[
              _AlarmPanel(controller: controller, snapshot: snapshot),
              const SizedBox(height: 18),
            ],

            ..._warnings(context, controller, snapshot),

            _QuickActions(controller: controller, snapshot: snapshot),
            const SizedBox(height: 22),

            SectionHeader(
              title: 'Group',
              trailing: Text(
                snapshot.peers.isEmpty
                    ? 'nobody yet'
                    : '${snapshot.armedPeerCount} of ${snapshot.peers.length} guarding',
                style: TextStyle(
                  fontSize: 12,
                  fontWeight: FontWeight.w600,
                  color: context.colors.onSurface.withValues(alpha: 0.55),
                ),
              ),
            ),
            if (snapshot.peers.isEmpty)
              _EmptyPeers(bluetoothOn: snapshot.diagnostics.bluetoothOn)
            else
              for (final peer in snapshot.peers) ...[
                PeerCard(
                  peer: peer,
                  onRename: () => _rename(context, controller, peer),
                ),
                const SizedBox(height: 10),
              ],

            const SizedBox(height: 12),
            SectionHeader(title: 'Speaker'),
            if (snapshot.box.configured)
              BoxCard(
                box: snapshot.box,
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const BoxSetupScreen()),
                ),
              )
            else
              Card(
                child: SettingTile(
                  leading: Icon(Icons.speaker_rounded, color: status.disarmed),
                  title: 'No speaker guarded',
                  subtitle:
                      'Pick your Bluetooth speaker so the group is warned if '
                      'someone walks off with it.',
                  trailing: const Icon(Icons.chevron_right_rounded),
                  onTap: () => Navigator.of(context).push(
                    MaterialPageRoute(builder: (_) => const BoxSetupScreen()),
                  ),
                ),
              ),

            const SizedBox(height: 22),
            _DiagnosticsStrip(snapshot: snapshot),
          ],
        ),
      ),
    );
  }

  List<Widget> _warnings(
    BuildContext context,
    GuardController controller,
    GuardSnapshot snapshot,
  ) {
    final widgets = <Widget>[];

    // Android 14+ withholds this by default, and without it the disarm screen
    // silently degrades to a notification instead of covering the lock screen.
    // That is exactly the moment the user needs it, so it gets top billing.
    if (!controller.fullScreenAlarmAllowed) {
      widgets.add(
        _CustomWarning(
          severe: true,
          icon: Icons.screen_lock_portrait_rounded,
          message: 'Android is blocking the lock screen disarm prompt. Without '
              'it you cannot stop an alarm without unlocking first.',
          actionLabel: 'Allow',
          onAction: () async {
            await controller.bridge.openFullScreenIntentSettings();
            await Future<void>.delayed(const Duration(seconds: 1));
            await controller.refreshCapabilities();
          },
        ),
      );
    }

    for (final warning in snapshot.warnings) {
      // "No peers" is already communicated by the empty group list; repeating
      // it as a banner is just noise.
      if (warning == GuardWarning.noPeers) continue;
      widgets.add(
        WarningBanner(
          warning: warning,
          actionLabel: warning == GuardWarning.bluetoothOff ? 'Turn on' : null,
          onAction: warning == GuardWarning.bluetoothOff
              ? () => controller.bridge.requestEnableBluetooth()
              : null,
        ),
      );
    }
    if (widgets.isNotEmpty) widgets.add(const SizedBox(height: 8));
    return widgets;
  }

  Future<void> _toggle(BuildContext context, GuardController controller) async {
    final messenger = ScaffoldMessenger.of(context);
    if (controller.snapshot.state == GuardState.disarmed) {
      await controller.arm();
      messenger.showSnackBar(
        const SnackBar(
          content: Text('Guarding. Leave everything still for a few seconds.'),
        ),
      );
    } else {
      await controller.disarm();
    }
  }

  Future<void> _rename(
    BuildContext context,
    GuardController controller,
    PeerInfo peer,
  ) async {
    final field = TextEditingController(text: peer.displayName);
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Name this phone'),
        content: TextField(
          controller: field,
          autofocus: true,
          textCapitalization: TextCapitalization.words,
          decoration: const InputDecoration(hintText: 'e.g. Lisa'),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          FilledButton(
            onPressed: () => Navigator.pop(context, field.text.trim()),
            child: const Text('Save'),
          ),
        ],
      ),
    );
    if (name != null) {
      await controller.bridge.renamePeer(peer.deviceId, name.isEmpty ? null : name);
      await controller.refreshSettings();
    }
  }
}

/// Banner for conditions the native warning set does not cover.
class _CustomWarning extends StatelessWidget {
  const _CustomWarning({
    required this.severe,
    required this.icon,
    required this.message,
    this.actionLabel,
    this.onAction,
  });

  final bool severe;
  final IconData icon;
  final String message;
  final String? actionLabel;
  final VoidCallback? onAction;

  @override
  Widget build(BuildContext context) {
    final tint = severe ? context.status.alarm : context.status.suspicious;
    return Container(
      margin: const EdgeInsets.only(bottom: 10),
      padding: const EdgeInsets.fromLTRB(14, 12, 12, 12),
      decoration: BoxDecoration(
        color: tint.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(16),
        border: Border.all(color: tint.withValues(alpha: 0.35)),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: tint),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              message,
              style: TextStyle(
                fontSize: 13.5,
                height: 1.35,
                fontWeight: FontWeight.w600,
                color: context.colors.onSurface,
              ),
            ),
          ),
          if (onAction != null) ...[
            const SizedBox(width: 6),
            TextButton(
              onPressed: onAction,
              style: TextButton.styleFrom(
                foregroundColor: tint,
                padding: const EdgeInsets.symmetric(horizontal: 10),
                minimumSize: const Size(0, 34),
                tapTargetSize: MaterialTapTargetSize.shrinkWrap,
              ),
              child: Text(actionLabel ?? 'Fix'),
            ),
          ],
        ],
      ),
    );
  }
}

class _StatusText extends StatelessWidget {
  const _StatusText({required this.snapshot});

  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Text(
          snapshot.state.label,
          textAlign: TextAlign.center,
          style: const TextStyle(fontSize: 24, fontWeight: FontWeight.w800),
        ),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14),
          child: Text(
            snapshot.state.detail,
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: 14,
              height: 1.4,
              color: context.colors.onSurface.withValues(alpha: 0.65),
            ),
          ),
        ),
      ],
    );
  }
}

class _AlarmPanel extends StatelessWidget {
  const _AlarmPanel({required this.controller, required this.snapshot});

  final GuardController controller;
  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    return Container(
      padding: const EdgeInsets.all(18),
      decoration: BoxDecoration(
        color: status.alarm.withValues(alpha: 0.13),
        borderRadius: BorderRadius.circular(20),
        border: Border.all(color: status.alarm.withValues(alpha: 0.45)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Text(
            snapshot.alarmReason?.label ?? 'Theft alarm',
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w800,
              color: status.alarm,
            ),
          ),
          if (snapshot.alarmSubjectName != null) ...[
            const SizedBox(height: 4),
            Text(
              snapshot.alarmSubjectName!,
              style: TextStyle(
                fontSize: 14,
                color: context.colors.onSurface.withValues(alpha: 0.75),
              ),
            ),
          ],
          const SizedBox(height: 14),
          FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: status.alarm),
            onPressed: () => controller.clearAlarm(),
            icon: const Icon(Icons.volume_off_rounded),
            label: const Text('Stop the noise, keep guarding'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () => controller.disarmGroup(),
            icon: const Icon(Icons.shield_outlined),
            label: const Text('Stop and disarm everyone'),
          ),
        ],
      ),
    );
  }
}

class _QuickActions extends StatelessWidget {
  const _QuickActions({required this.controller, required this.snapshot});

  final GuardController controller;
  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final protecting = snapshot.state.isProtecting;
    return Row(
      children: [
        Expanded(
          child: _ActionTile(
            icon: protecting ? Icons.shield_outlined : Icons.shield_rounded,
            label: protecting ? 'Disarm all' : 'Arm all',
            color: context.status.calibrating,
            onTap: () async {
              final messenger = ScaffoldMessenger.of(context);
              if (protecting) {
                await controller.disarmGroup();
                messenger.showSnackBar(
                  const SnackBar(content: Text('Whole group disarmed.')),
                );
              } else {
                await controller.armGroup();
                messenger.showSnackBar(
                  const SnackBar(content: Text('Whole group armed.')),
                );
              }
            },
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: _ActionTile(
            icon: Icons.campaign_rounded,
            label: 'Panic',
            color: context.status.alarm,
            onTap: () => controller.panic(),
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: _ActionTile(
            icon: Icons.volume_up_rounded,
            label: 'Test',
            color: context.status.suspicious,
            onTap: () async {
              final messenger = ScaffoldMessenger.of(context);
              await controller.testAlarm();
              messenger.showSnackBar(
                const SnackBar(
                  content: Text('Test alarm. Stop it from the alarm screen.'),
                ),
              );
            },
          ),
        ),
      ],
    );
  }
}

class _ActionTile extends StatelessWidget {
  const _ActionTile({
    required this.icon,
    required this.label,
    required this.color,
    required this.onTap,
  });

  final IconData icon;
  final String label;
  final Color color;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: color.withValues(alpha: 0.12),
      borderRadius: BorderRadius.circular(16),
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 14),
          child: Column(
            children: [
              Icon(icon, color: color, size: 22),
              const SizedBox(height: 6),
              Text(
                label,
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: color,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _EmptyPeers extends StatelessWidget {
  const _EmptyPeers({required this.bluetoothOn});

  final bool bluetoothOn;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 22, 18, 22),
        child: Column(
          children: [
            Icon(
              bluetoothOn ? Icons.wifi_tethering_rounded : Icons.bluetooth_disabled_rounded,
              size: 30,
              color: context.colors.onSurface.withValues(alpha: 0.4),
            ),
            const SizedBox(height: 10),
            Text(
              bluetoothOn
                  ? 'Looking for the others'
                  : 'Bluetooth is off',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text(
              bluetoothOn
                  ? 'Everyone needs BeachProtect open once, in the same group. '
                      'They appear here within a few seconds.'
                  : 'The whole thing runs on Bluetooth, so nothing works until '
                      'it is switched on.',
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 13,
                height: 1.4,
                color: context.colors.onSurface.withValues(alpha: 0.6),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A quiet strip of facts that turn "is this actually working?" into a
/// question you can answer at a glance.
class _DiagnosticsStrip extends StatelessWidget {
  const _DiagnosticsStrip({required this.snapshot});

  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final d = snapshot.diagnostics;
    final status = context.status;
    return Wrap(
      spacing: 7,
      runSpacing: 7,
      children: [
        InfoChip(
          icon: d.scanning ? Icons.sensors_rounded : Icons.sensors_off_rounded,
          label: d.scanning ? 'listening' : 'not listening',
          color: d.scanning ? status.armed : status.disarmed,
        ),
        InfoChip(
          icon: d.advertising
              ? Icons.podcasts_rounded
              : Icons.portable_wifi_off_rounded,
          label: d.advertising ? 'broadcasting' : 'silent',
          color: d.advertising ? status.armed : status.disarmed,
        ),
        InfoChip(
          icon: Icons.speed_rounded,
          label: snapshot.radioProfile.label.toLowerCase(),
          color: snapshot.radioProfile == RadioProfile.calm
              ? status.armed
              : status.suspicious,
        ),
        InfoChip(
          icon: snapshot.selfStationary
              ? Icons.hotel_rounded
              : Icons.directions_walk_rounded,
          label: snapshot.selfStationary ? 'this phone still' : 'this phone moving',
          color: snapshot.selfStationary ? status.armed : status.suspicious,
        ),
        if (!d.hasSignificantMotion)
          InfoChip(
            icon: Icons.battery_alert_rounded,
            label: 'no motion wake-up sensor',
            color: status.suspicious,
            emphasise: true,
          ),
      ],
    );
  }
}
