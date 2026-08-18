import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/models.dart';
import '../core/theme.dart';
import '../widgets/common.dart';
import 'box_setup_screen.dart';
import 'permissions_screen.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();
    final s = controller.settings;

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
        children: [
          RadioGroup<PowerProfile>(
            groupValue: s.powerProfile,
            onChanged: (value) =>
                controller.patch({'powerProfile': value!.wireName}),
            child: SettingsGroup(
              title: 'Battery',
              children: [
                for (final profile in PowerProfile.values)
                  SettingTile(
                    leading: Radio<PowerProfile>(value: profile),
                    title: profile.label,
                    subtitle: profile.detail,
                    onTap: () =>
                        controller.patch({'powerProfile': profile.wireName}),
                  ),
              ],
            ),
          ),

          SettingsGroup(
            title: 'Disarming',
            children: [
              RadioGroup<DisarmMode>(
                groupValue: s.disarmMode,
                onChanged: (value) =>
                    controller.patch({'disarmMode': value!.wireName}),
                child: Column(
                  children: [
                    for (final mode in DisarmMode.values)
                      SettingTile(
                        leading: Radio<DisarmMode>(value: mode),
                        title: mode.label,
                        subtitle: mode.detail,
                        onTap: () =>
                            controller.patch({'disarmMode': mode.wireName}),
                      ),
                  ],
                ),
              ),
              SettingTile(
                leading: Icon(
                  s.hasPin ? Icons.lock_rounded : Icons.lock_open_rounded,
                  color: s.hasPin ? context.status.armed : context.status.alarm,
                ),
                title: s.hasPin ? 'Change group PIN' : 'Set a group PIN',
                subtitle: s.hasPin
                    ? 'Shared with the group so anyone can silence a false alarm.'
                    : 'Without a PIN, a single tap disarms this phone.',
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => _changePin(context, controller),
              ),
            ],
          ),

          SettingsGroup(
            title: 'Alarm',
            children: [
              RadioGroup<AlarmTarget>(
                groupValue: s.alarmTarget,
                onChanged: (value) =>
                    controller.patch({'alarmTarget': value!.wireName}),
                child: Column(
                  children: [
                    for (final target in AlarmTarget.values)
                      SettingTile(
                        leading: Radio<AlarmTarget>(value: target),
                        title: target.label,
                        subtitle: target.detail,
                        onTap: () =>
                            controller.patch({'alarmTarget': target.wireName}),
                      ),
                  ],
                ),
              ),
              SettingTile(
                title: 'Siren volume',
                subtitle: '${(s.sirenVolume * 100).round()}% of maximum',
                leading: const Icon(Icons.volume_up_rounded),
                trailing: SizedBox(
                  width: 140,
                  child: Slider(
                    value: s.sirenVolume.clamp(0.1, 1.0),
                    min: 0.1,
                    onChanged: (value) => controller.patch({'sirenVolume': value}),
                  ),
                ),
              ),
              SettingTile(
                title: 'Vibrate',
                subtitle: 'Also buzz the phone while the siren runs.',
                leading: const Icon(Icons.vibration_rounded),
                trailing: Switch(
                  value: s.vibrateOnAlarm,
                  onChanged: (value) =>
                      controller.patch({'vibrateOnAlarm': value}),
                ),
              ),
              SettingTile(
                title: 'Announce the reason',
                subtitle: 'Speaks what happened over the siren.',
                leading: const Icon(Icons.record_voice_over_rounded),
                trailing: Switch(
                  value: s.speakReason,
                  onChanged: (value) => controller.patch({'speakReason': value}),
                ),
              ),
            ],
          ),

          SettingsGroup(
            title: 'Speaker',
            children: [
              SettingTile(
                leading: Icon(
                  Icons.speaker_rounded,
                  color: s.boxEnabled
                      ? context.status.armed
                      : context.status.disarmed,
                ),
                title: s.boxEnabled ? (s.boxName ?? 'Speaker') : 'No speaker guarded',
                subtitle: s.boxEnabled
                    ? 'Watched over the audio link'
                        '${s.boxBleAddress != null ? ' and its Bluetooth beacon' : ''}.'
                    : 'Pick the speaker your group brought.',
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const BoxSetupScreen()),
                ),
              ),
            ],
          ),

          _DetectionGroup(controller: controller),

          SettingsGroup(
            title: 'Testing',
            children: [
              SettingTile(
                title: 'Test scenarios',
                subtitle:
                    'Adds a screen that feeds fake group members through the '
                    'real detector, so you can check it on one phone. Test '
                    'runs never sound the full siren.',
                leading: const Icon(Icons.science_rounded),
                trailing: Switch(
                  value: s.simulationEnabled,
                  onChanged: (value) =>
                      controller.patch({'simulationEnabled': value}),
                ),
              ),
              SettingTile(
                title: 'Test the lock screen alarm',
                subtitle:
                    'Lock your phone within 6 seconds. The disarm screen '
                    'should appear on top of the lock screen. If it does not, '
                    'the full screen alarm permission below is missing.',
                leading: const Icon(Icons.screen_lock_portrait_rounded),
                trailing: const Icon(Icons.play_arrow_rounded),
                onTap: () async {
                  final messenger = ScaffoldMessenger.of(context);
                  await controller.bridge.lockScreenTest();
                  messenger.showSnackBar(
                    const SnackBar(
                      content: Text('Lock your phone now.'),
                      duration: Duration(seconds: 6),
                    ),
                  );
                },
              ),
            ],
          ),

          SettingsGroup(
            title: 'System',
            children: [
              SettingTile(
                title: 'Permissions and setup',
                subtitle:
                    'What the app needs, why it needs it, and whether Android '
                    'has actually granted it.',
                leading: const Icon(Icons.verified_user_rounded),
                trailing: const Icon(Icons.chevron_right_rounded),
                onTap: () => Navigator.of(context).push(
                  MaterialPageRoute(builder: (_) => const PermissionsScreen()),
                ),
              ),
              SettingTile(
                title: 'Android app settings',
                subtitle: 'Permissions, notifications, battery behaviour.',
                leading: const Icon(Icons.settings_applications_rounded),
                trailing: const Icon(Icons.open_in_new_rounded, size: 18),
                onTap: () => controller.bridge.openAppSettings(),
              ),
              SettingTile(
                title: 'Ignore battery optimisation',
                subtitle:
                    'Stops Android suspending the guard while you are away '
                    'from the phone.',
                leading: const Icon(Icons.battery_charging_full_rounded),
                trailing: const Icon(Icons.open_in_new_rounded, size: 18),
                onTap: () => controller.bridge.requestIgnoreBatteryOptimizations(),
              ),
              SettingTile(
                title: 'Full screen alarm permission',
                subtitle: 'Lets the disarm screen appear over the lock screen.',
                leading: const Icon(Icons.screen_lock_portrait_rounded),
                trailing: const Icon(Icons.open_in_new_rounded, size: 18),
                onTap: () => controller.bridge.openFullScreenIntentSettings(),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Future<void> _changePin(
    BuildContext context,
    GuardController controller,
  ) async {
    final field = TextEditingController();
    final pin = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Group PIN'),
        content: TextField(
          controller: field,
          autofocus: true,
          obscureText: true,
          keyboardType: TextInputType.number,
          maxLength: 8,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          decoration: const InputDecoration(helperText: '4 to 8 digits'),
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
    if (pin != null && pin.length >= 4) {
      await controller.bridge.setPin(pin);
      await controller.refreshSettings();
      if (context.mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('PIN updated.')),
        );
      }
    }
  }
}

/// The detector tuning, kept behind an expander because the defaults are right
/// for almost everyone and the wrong values here make the app useless.
class _DetectionGroup extends StatefulWidget {
  const _DetectionGroup({required this.controller});

  final GuardController controller;

  @override
  State<_DetectionGroup> createState() => _DetectionGroupState();
}

class _DetectionGroupState extends State<_DetectionGroup> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final s = widget.controller.settings;
    final controller = widget.controller;

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader(title: 'Detection'),
        Card(
          child: Column(
            children: [
              SettingTile(
                title: 'Alarm when a phone is simply picked up',
                subtitle: s.alarmOnPickupAlone
                    ? 'On. A phone lifted off the towel alarms once the grace '
                        'period runs out, even if nobody else saw it move away.'
                    : 'Off. A lifted phone only alarms if the others can also '
                        'see it receding. Fewer false alarms, slower to react.',
                leading: const Icon(Icons.pan_tool_rounded),
                trailing: Switch(
                  value: s.alarmOnPickupAlone,
                  onChanged: (value) =>
                      controller.patch({'alarmOnPickupAlone': value}),
                ),
              ),
              const Divider(indent: 16, endIndent: 16),
              _SliderTile(
                title: 'Grace period',
                value: s.pickupGraceMs / 1000,
                min: 1,
                max: 30,
                suffix: 'seconds',
                help: 'How long you have to disarm after your phone is lifted, '
                    'before the whole group sounds off. The phone chirps and '
                    'shows the disarm screen immediately either way.',
                onChanged: (v) =>
                    controller.patch({'pickupGraceMs': (v * 1000).round()}),
              ),
              const Divider(indent: 16, endIndent: 16),
              SettingTile(
                title: _expanded ? 'Hide advanced' : 'Advanced tuning',
                subtitle: _expanded
                    ? null
                    : 'Thresholds for the signal detector. The defaults are '
                        'tuned for phones on sand a few metres apart.',
                leading: const Icon(Icons.tune_rounded),
                trailing: Icon(
                  _expanded
                      ? Icons.expand_less_rounded
                      : Icons.expand_more_rounded,
                ),
                onTap: () => setState(() => _expanded = !_expanded),
              ),
              if (_expanded) ...[
                const Divider(indent: 16, endIndent: 16),
                _SliderTile(
                  title: 'Signal drop threshold',
                  value: s.dropThresholdDb,
                  min: 6,
                  max: 25,
                  suffix: 'dB below normal',
                  help: 'Lower reacts sooner but confuses passers-by with '
                      'thieves more often.',
                  onChanged: (v) => controller.patch({'dropThresholdDb': v}),
                ),
                _SliderTile(
                  title: 'Drop must last',
                  value: s.sustainMs / 1000,
                  min: 1,
                  max: 12,
                  suffix: 'seconds',
                  help: 'How long the signal has to stay down before this '
                      'phone votes. The single biggest false-alarm control.',
                  onChanged: (v) =>
                      controller.patch({'sustainMs': (v * 1000).round()}),
                ),
                _ConsensusTile(controller: controller),
                _SliderTile(
                  title: 'Treat as vanished after',
                  value: s.lostTimeoutMs / 1000,
                  min: 5,
                  max: 60,
                  suffix: 'seconds of silence',
                  help: 'Catches a phone that is switched off or bagged. This '
                      'is a floor: on a low-power scan setting, where gaps '
                      'between beacons are naturally long, the guard waits '
                      'proportionally longer before calling a phone gone.',
                  onChanged: (v) =>
                      controller.patch({'lostTimeoutMs': (v * 1000).round()}),
                ),
                _SliderTile(
                  title: 'Must lie still for',
                  value: s.settleMs / 1000,
                  min: 5,
                  max: 60,
                  suffix: 'seconds before pickup counts',
                  help: 'Stops the alarm firing while you are still putting '
                      'the phone down.',
                  onChanged: (v) =>
                      controller.patch({'settleMs': (v * 1000).round()}),
                ),
              ],
            ],
          ),
        ),
        const SizedBox(height: 18),
      ],
    );
  }
}

/// Explains the consensus rule in terms of the group you actually have.
///
/// A bare "34%" means nothing to anyone. What people want to know is "how many
/// of my friends' phones have to agree", so the tile spells that out for the
/// current group size as the slider moves.
class _ConsensusTile extends StatelessWidget {
  const _ConsensusTile({required this.controller});

  final GuardController controller;

  @override
  Widget build(BuildContext context) {
    final s = controller.settings;
    final peers = controller.snapshot.peers.length;
    // Witnesses available for any one phone: everyone except that phone.
    final others = peers > 0 ? peers : 2;
    final needed = s.observersRequiredFor(others);

    return _SliderTile(
      title: 'How many phones must agree',
      value: s.consensusRatio * 100,
      min: 10,
      max: 100,
      suffix: '% of the others',
      help: peers > 0
          ? 'With the ${peers + 1} phones in your group right now, $needed '
              '${needed == 1 ? "other phone has" : "other phones have"} to see '
              'the same thing before the alarm goes off.'
          : 'Scales with the group: a third of two other phones is one, a '
              'third of six is two. Never more than the group can supply.',
      onChanged: (v) => controller.patch({'consensusRatio': v / 100}),
    );
  }
}

/// Slider row that only writes the setting when the finger comes off.
///
/// Committing on every pixel of a drag would fire a platform round trip and a
/// service reconfiguration dozens of times per gesture, and the value bouncing
/// back from the service mid-drag makes the slider fight the finger.
class _SliderTile extends StatefulWidget {
  const _SliderTile({
    required this.title,
    required this.value,
    required this.min,
    required this.max,
    required this.suffix,
    required this.help,
    required this.onChanged,
  });

  final String title;
  final double value;
  final double min;
  final double max;
  final String suffix;
  final String help;
  final ValueChanged<double> onChanged;

  @override
  State<_SliderTile> createState() => _SliderTileState();
}

class _SliderTileState extends State<_SliderTile> {
  double? _dragging;

  @override
  Widget build(BuildContext context) {
    final value = (_dragging ?? widget.value).clamp(widget.min, widget.max);
    return Padding(
      padding: const EdgeInsets.fromLTRB(16, 10, 16, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  widget.title,
                  style: const TextStyle(
                    fontSize: 14.5,
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ),
              Text(
                '${value.toStringAsFixed(value < 10 ? 1 : 0)} ${widget.suffix}',
                style: TextStyle(
                  fontSize: 12.5,
                  fontWeight: FontWeight.w700,
                  color: context.colors.primary,
                ),
              ),
            ],
          ),
          Slider(
            value: value,
            min: widget.min,
            max: widget.max,
            onChanged: (v) => setState(() => _dragging = v),
            onChangeEnd: (v) {
              setState(() => _dragging = null);
              widget.onChanged(v);
            },
          ),
          Text(
            widget.help,
            style: TextStyle(
              fontSize: 12.5,
              height: 1.35,
              color: context.colors.onSurface.withValues(alpha: 0.58),
            ),
          ),
          const SizedBox(height: 6),
        ],
      ),
    );
  }
}
