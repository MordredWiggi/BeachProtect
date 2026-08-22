import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/models.dart';
import '../core/permissions.dart';
import '../core/theme.dart';
import '../widgets/common.dart';
import '../widgets/peer_card.dart';
import '../widgets/shield_button.dart';
import 'box_setup_screen.dart';
import 'group_screen.dart';
import 'permissions_screen.dart';
import 'settings_screen.dart';
import 'simulator_screen.dart';

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) context.read<GuardController>().refreshPermissions();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Permissions can be revoked, and Bluetooth switched off, in another app
    // entirely - so the reminder is only honest if it is re-read on the way
    // back in.
    if (state == AppLifecycleState.resumed) {
      context.read<GuardController>().refreshPermissions();
    }
  }

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
            // Not while something is actually happening: during the grace
            // period the shield is counting down and the status text already
            // says to disarm, so a chip about arming the detector is at best
            // noise and at worst reads as the opposite instruction.
            if (snapshot.state.isProtecting &&
                snapshot.state != GuardState.pending &&
                snapshot.state != GuardState.alarm) ...[
              const SizedBox(height: 14),
              _PickupReadiness(snapshot: snapshot),
            ],
            const SizedBox(height: 20),

            // Shown for as long as *the group* is in an incident, not just this
            // phone. Silencing your own handset used to take these controls away
            // while everybody else carried on screaming.
            //
            // ...and for as long as a stop this phone issued is still going out,
            // which is the honest end of an incident: the panel now reports what
            // it is actually doing and how far it has got, instead of vanishing
            // and reappearing as stale packets drifted in.
            if (snapshot.state == GuardState.alarm ||
                snapshot.groupAlarmActive ||
                snapshot.stopPending) ...[
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
              // Only claim the radio is off when the guard is actually running
              // and has said so. An empty diagnostics block — no snapshot has
              // arrived yet — is "we do not know", and announcing "Bluetooth is
              // off" to somebody whose Bluetooth is plainly on is worse than
              // saying nothing.
              _EmptyPeers(
                bluetoothOff: snapshot.diagnostics.serviceRunning &&
                    !snapshot.diagnostics.bluetoothOn,
              )
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

    // Anything Android has not allowed yet stays on the home screen until it
    // is dealt with. Half of these are granted in Android's own settings app,
    // where it is easy to wander off half way through - and every one of them
    // fails silently, so a guard that looks armed can be doing nothing at all.
    final permissionReminder = _permissionReminder(context, controller);
    if (permissionReminder != null) widgets.add(permissionReminder);

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

  /// The standing reminder about anything Android has not allowed yet.
  ///
  /// Two levels, because they are genuinely different problems: without the
  /// required ones the guard cannot run at all, while the optional ones only
  /// make it less dependable - and crying wolf about the second kind is how a
  /// banner gets ignored when it matters.
  Widget? _permissionReminder(
    BuildContext context,
    GuardController controller,
  ) {
    final state = controller.permissions;
    if (state.unread) return null;

    // "Bluetooth is switched off" already has its own banner further down,
    // complete with a one-tap fix, so it is left out here rather than said
    // twice in two different voices.
    bool relevant(Need need) => need != Need.bluetoothOn;

    final missing = state.missing.where(relevant).toList();
    if (missing.isEmpty) return null;

    final blocking = state.missingRequired.where(relevant).toList();
    final listed = (blocking.isNotEmpty ? blocking : missing)
        .map((need) => needs[need]!.title)
        .toList();

    final message = blocking.isNotEmpty
        ? 'Not set up yet: ${_sentenceList(listed)}. '
            '${needs[blocking.first]!.consequence}'
        : 'Still to allow: ${_sentenceList(listed)}. The guard runs without '
            'these, but it is more easily interrupted.';

    return _CustomWarning(
      severe: blocking.isNotEmpty,
      icon: blocking.isNotEmpty
          ? Icons.lock_open_rounded
          : Icons.shield_moon_rounded,
      message: message,
      actionLabel: blocking.isNotEmpty ? 'Set up' : 'Review',
      onAction: () async {
        await Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => const PermissionsScreen()),
        );
        await controller.refreshPermissions();
      },
    );
  }

  static String _sentenceList(List<String> items) {
    if (items.length == 1) return items.first;
    return '${items.sublist(0, items.length - 1).join(', ')} and ${items.last}';
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

/// Says plainly whether lifting this phone would actually set anything off.
///
/// The pickup detector only arms once the phone has lain still for a while.
/// Without showing that, someone who arms the app and immediately waves the
/// phone about sees "Guarding", nothing happens, and reasonably concludes the
/// whole thing is broken.
class _PickupReadiness extends StatelessWidget {
  const _PickupReadiness({required this.snapshot});

  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    final ready = snapshot.pickupArmed;
    final seconds = (snapshot.pickupArmsInMs / 1000).ceil();
    final calibrating = snapshot.state == GuardState.calibrating;

    final Color tint;
    final IconData icon;
    final String text;

    if (calibrating) {
      tint = status.calibrating;
      icon = Icons.hourglass_top_rounded;
      text = 'Settling in';
    } else if (ready) {
      tint = status.armed;
      icon = Icons.verified_user_rounded;
      text = 'Pickup protection active';
    } else if (!snapshot.selfStationary) {
      tint = status.suspicious;
      icon = Icons.back_hand_rounded;
      text = 'Put the phone down to arm pickup protection';
    } else {
      tint = status.suspicious;
      icon = Icons.hourglass_bottom_rounded;
      text = 'Pickup protection in ${seconds}s';
    }

    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
      decoration: BoxDecoration(
        color: tint.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: tint.withValues(alpha: 0.3)),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Icon(icon, size: 17, color: tint),
          const SizedBox(width: 8),
          Flexible(
            child: Text(
              text,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontSize: 13.5,
                fontWeight: FontWeight.w700,
                color: tint,
              ),
            ),
          ),
        ],
      ),
    );
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
    final here = snapshot.state == GuardState.alarm;
    // This phone is out of it, but somebody else is not. That combination used
    // to be a dead end: no alarm panel, and a quick action offering to "Arm
    // all" while the towel was still screaming.
    final onlyOthers = !here && snapshot.groupAlarmActive;
    // A stop this phone issued is still going out. Giving that its own voice is
    // what replaced the banner that appeared and disappeared at random: the
    // phone now knows exactly who has confirmed, so it says so instead of
    // guessing from whichever packet it last happened to catch.
    final stopping = snapshot.stopPending;
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
            switch ((here, onlyOthers)) {
              (true, _) => snapshot.alarmReason?.label ?? 'Theft alarm',
              (_, true) => 'The group is still alarming',
              _ => 'Stopping everyone',
            },
            style: TextStyle(
              fontSize: 17,
              fontWeight: FontWeight.w800,
              color: status.alarm,
            ),
          ),
          const SizedBox(height: 4),
          if (onlyOthers)
            Text(
              'This phone has stopped, but at least one other has not. '
              'Either button below reaches all of them.',
              style: TextStyle(
                fontSize: 13.5,
                height: 1.35,
                color: context.colors.onSurface.withValues(alpha: 0.75),
              ),
            )
          else if (here && snapshot.alarmSubjectName != null)
            Text(
              snapshot.alarmSubjectName!,
              style: TextStyle(
                fontSize: 14,
                color: context.colors.onSurface.withValues(alpha: 0.75),
              ),
            ),
          // The count is the point. "Waiting for 1 phone" is a fact the phone
          // can now establish, and it either resolves in a second or two or
          // tells you honestly that somebody is out of reach - which is a far
          // more useful thing to look at than a banner blinking on and off.
          if (stopping) ...[
            const SizedBox(height: 8),
            Row(
              children: [
                SizedBox(
                  width: 15,
                  height: 15,
                  child: CircularProgressIndicator(
                    strokeWidth: 2,
                    color: status.alarm,
                  ),
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    snapshot.stopExpected == 0
                        ? 'Telling the group to stop...'
                        : '${snapshot.stopConfirmed} of '
                            '${snapshot.stopExpected} '
                            '${snapshot.stopExpected == 1 ? 'phone has' : 'phones have'}'
                            ' confirmed',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w600,
                      color: context.colors.onSurface.withValues(alpha: 0.75),
                    ),
                  ),
                ),
              ],
            ),
          ],
          if (here && !snapshot.diagnostics.sirenAudible) ...[
            const SizedBox(height: 10),
            Row(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Icon(Icons.volume_off_rounded, size: 17, color: status.alarm),
                const SizedBox(width: 8),
                Expanded(
                  child: Text(
                    'This phone could not open an audio output, so it is not '
                    'making any sound. Check the media volume and any Do Not '
                    'Disturb setting.',
                    style: TextStyle(
                      fontSize: 12.5,
                      height: 1.35,
                      fontWeight: FontWeight.w600,
                      color: status.alarm,
                    ),
                  ),
                ),
              ],
            ),
          ],
          const SizedBox(height: 14),
          // Two decisions, and the first one is the common case. Most alarms
          // somebody is standing in front of are false, and what they want is
          // quiet without giving up on the afternoon's guarding.
          FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: status.alarm),
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(context);
              await controller.clearAlarm();
              messenger.showSnackBar(
                const SnackBar(
                  content: Text('Telling everyone to stop. All phones keep '
                      'guarding.'),
                ),
              );
            },
            icon: const Icon(Icons.volume_off_rounded),
            label: const Text('False alarm - stop everyone, keep guarding'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: () async {
              final messenger = ScaffoldMessenger.of(context);
              await controller.disarmGroup();
              messenger.showSnackBar(
                const SnackBar(
                  content: Text('Telling everyone to stop and stand down.'),
                ),
              );
            },
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
    // Read off the *group*, not off this handset. Basing it on the local state
    // meant a phone that had just disarmed itself offered "Arm all" to a group
    // that was still guarding — or still alarming.
    final protecting = snapshot.anyoneGuarding;
    return Row(
      children: [
        Expanded(
          child: _ActionTile(
            icon: protecting ? Icons.shield_outlined : Icons.shield_rounded,
            label: protecting ? 'Disarm all' : 'Arm all',
            color: context.status.calibrating,
            // Said as what it is. The command is broadcast and repeated for
            // several seconds, but nothing acknowledges it — so claiming the
            // group is armed would be a promise this phone cannot keep. The
            // count in the Group header below is the real answer.
            onTap: () async {
              final messenger = ScaffoldMessenger.of(context);
              if (protecting) {
                await controller.disarmGroup();
                messenger.showSnackBar(
                  const SnackBar(
                    content: Text('Telling everyone to stand down.'),
                  ),
                );
              } else {
                await controller.armGroup();
                messenger.showSnackBar(
                  const SnackBar(
                    content: Text('Telling everyone to arm. Watch the count '
                        'under Group.'),
                  ),
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
  const _EmptyPeers({required this.bluetoothOff});

  /// Known to be off, as opposed to merely not confirmed to be on.
  final bool bluetoothOff;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 22, 18, 22),
        child: Column(
          children: [
            Icon(
              bluetoothOff
                  ? Icons.bluetooth_disabled_rounded
                  : Icons.wifi_tethering_rounded,
              size: 30,
              color: context.colors.onSurface.withValues(alpha: 0.4),
            ),
            const SizedBox(height: 10),
            Text(
              bluetoothOff ? 'Bluetooth is off' : 'Looking for the others',
              style: const TextStyle(fontSize: 15, fontWeight: FontWeight.w700),
            ),
            const SizedBox(height: 6),
            Text(
              bluetoothOff
                  ? 'The whole thing runs on Bluetooth, so nothing works until '
                      'it is switched on.'
                  : 'Everyone needs BeachProtect open once, in the same group. '
                      'They appear here within a few seconds.',
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
        // Proof that the radio is doing something, not just claiming to. "The
        // group is empty" has two very different causes - nothing is arriving,
        // or things arrive and are discarded - and they need different fixes.
        InfoChip(
          icon: d.beaconsHeard > 0
              ? Icons.hearing_rounded
              : Icons.hearing_disabled_rounded,
          label: switch ((d.beaconsHeard, d.packetsHeard)) {
            (0, 0) => 'no beacons heard',
            (0, _) => '${d.packetsHeard} packets, none in this group',
            _ => '${d.beaconsHeard} group beacons',
          },
          color: d.beaconsHeard > 0 ? status.armed : status.disarmed,
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
