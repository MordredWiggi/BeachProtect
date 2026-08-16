import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/models.dart';
import '../core/theme.dart';
import '../widgets/common.dart';

/// Sets up which speaker is guarded, and how.
class BoxSetupScreen extends StatefulWidget {
  const BoxSetupScreen({super.key});

  @override
  State<BoxSetupScreen> createState() => _BoxSetupScreenState();
}

class _BoxSetupScreenState extends State<BoxSetupScreen> {
  List<BtDevice> _paired = const [];
  List<BtDevice> _discovered = const [];
  bool _loadingPaired = true;
  bool _scanning = false;

  @override
  void initState() {
    super.initState();
    _loadPaired();
  }

  Future<void> _loadPaired() async {
    final controller = context.read<GuardController>();
    final devices = await controller.bridge.pairedAudioDevices();
    if (!mounted) return;
    setState(() {
      _paired = devices;
      _loadingPaired = false;
    });
  }

  Future<void> _scanForBeacon() async {
    setState(() => _scanning = true);
    final controller = context.read<GuardController>();
    final devices = await controller.bridge.discoverBleDevices(durationMs: 7000);
    if (!mounted) return;
    setState(() {
      _discovered = devices;
      _scanning = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();
    final s = controller.settings;
    final box = controller.snapshot.box;

    return Scaffold(
      appBar: AppBar(title: const Text('Speaker')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
        children: [
          Card(
            child: Padding(
              padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Icon(Icons.info_rounded,
                          size: 18, color: context.colors.primary),
                      const SizedBox(width: 8),
                      const Text(
                        'How a dumb speaker gets guarded',
                        style: TextStyle(
                            fontSize: 15, fontWeight: FontWeight.w700),
                      ),
                    ],
                  ),
                  const SizedBox(height: 10),
                  Text(
                    'A Bluetooth speaker has no app and no sensors, so the app '
                    'watches the two things it does emit.\n\n'
                    'The phone that holds the audio connection notices the '
                    'moment that link drops - which happens when the speaker '
                    'goes out of range, or when somebody switches it off. '
                    'Either one raises the alarm across the group.\n\n'
                    'Many speakers also advertise over Bluetooth LE. If yours '
                    'does, the app tracks that beacon too, which gives a '
                    'distance warning before the audio link actually fails.',
                    style: TextStyle(
                      fontSize: 13.5,
                      height: 1.5,
                      color: context.colors.onSurface.withValues(alpha: 0.7),
                    ),
                  ),
                ],
              ),
            ),
          ),
          const SizedBox(height: 20),

          if (s.boxEnabled && s.boxAddress != null) ...[
            SectionHeader(title: 'Currently guarded'),
            Card(
              child: Column(
                children: [
                  SettingTile(
                    leading: Icon(
                      Icons.speaker_rounded,
                      color: box.audioLinkConnected
                          ? context.status.armed
                          : context.status.suspicious,
                    ),
                    title: s.boxName ?? 'Speaker',
                    subtitle: [
                      s.boxAddress!,
                      box.audioLinkConnected
                          ? 'audio link up'
                          : 'audio link down',
                      if (s.boxBleAddress != null) 'beacon tracked',
                    ].join('  -  '),
                  ),
                  const Divider(indent: 16, endIndent: 16),
                  SettingTile(
                    title: 'Guard this speaker',
                    subtitle: 'Alarm the group if the audio link drops.',
                    leading: const Icon(Icons.shield_rounded),
                    trailing: Switch(
                      value: s.boxEnabled,
                      onChanged: (value) =>
                          controller.patch({'boxEnabled': value}),
                    ),
                  ),
                  const Divider(indent: 16, endIndent: 16),
                  SettingTile(
                    title: s.boxBleAddress == null
                        ? 'Find its Bluetooth beacon'
                        : 'Beacon found',
                    subtitle: s.boxBleAddress == null
                        ? 'Optional. Gives an early distance warning where the '
                            'speaker supports it.'
                        : s.boxBleAddress!,
                    leading: const Icon(Icons.bluetooth_searching_rounded),
                    trailing: _scanning
                        ? const SizedBox(
                            width: 18,
                            height: 18,
                            child: CircularProgressIndicator(strokeWidth: 2.2),
                          )
                        : const Icon(Icons.chevron_right_rounded),
                    onTap: _scanning ? null : _scanForBeacon,
                  ),
                  const Divider(indent: 16, endIndent: 16),
                  SettingTile(
                    title: 'Forget this speaker',
                    leading: Icon(Icons.delete_outline_rounded,
                        color: context.status.alarm),
                    onTap: () => controller.patch({
                      'boxEnabled': false,
                      'boxAddress': null,
                      'boxName': null,
                      'boxBleAddress': null,
                    }),
                  ),
                ],
              ),
            ),
            const SizedBox(height: 22),
          ],

          if (_discovered.isNotEmpty) ...[
            SectionHeader(title: 'Nearby Bluetooth LE beacons'),
            Card(
              child: Column(
                children: [
                  for (var i = 0; i < _discovered.length && i < 12; i++) ...[
                    if (i > 0) const Divider(indent: 16, endIndent: 16),
                    SettingTile(
                      leading: const Icon(Icons.sensors_rounded),
                      title: _discovered[i].displayName,
                      subtitle:
                          '${_discovered[i].address}   ${_discovered[i].rssi} dBm',
                      trailing: TextButton(
                        onPressed: () async {
                          await controller
                              .patch({'boxBleAddress': _discovered[i].address});
                          if (context.mounted) {
                            setState(() => _discovered = const []);
                          }
                        },
                        child: const Text('Use'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Pick the entry whose signal is strongest when you hold the phone '
              'next to the speaker. Many speakers only advertise while they are '
              'in pairing mode.',
              style: TextStyle(
                fontSize: 12.5,
                height: 1.4,
                color: context.colors.onSurface.withValues(alpha: 0.58),
              ),
            ),
            const SizedBox(height: 22),
          ],

          SectionHeader(
            title: 'Paired Bluetooth devices',
            trailing: IconButton(
              icon: const Icon(Icons.refresh_rounded, size: 20),
              onPressed: _loadPaired,
            ),
          ),
          if (_loadingPaired)
            const Center(
              child: Padding(
                padding: EdgeInsets.all(24),
                child: CircularProgressIndicator(),
              ),
            )
          else if (_paired.isEmpty)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Text(
                  'No paired devices. Pair your speaker in Android\'s Bluetooth '
                  'settings first, then come back here.',
                  style: TextStyle(
                    fontSize: 13.5,
                    height: 1.4,
                    color: context.colors.onSurface.withValues(alpha: 0.65),
                  ),
                ),
              ),
            )
          else
            Card(
              child: Column(
                children: [
                  for (var i = 0; i < _paired.length; i++) ...[
                    if (i > 0) const Divider(indent: 16, endIndent: 16),
                    SettingTile(
                      leading: Icon(
                        Icons.speaker_rounded,
                        color: _paired[i].connected
                            ? context.status.armed
                            : context.status.disarmed,
                      ),
                      title: _paired[i].displayName,
                      subtitle: _paired[i].connected
                          ? 'Connected now  -  ${_paired[i].address}'
                          : _paired[i].address,
                      trailing: s.boxAddress == _paired[i].address
                          ? Icon(Icons.check_circle_rounded,
                              color: context.status.armed)
                          : const Icon(Icons.chevron_right_rounded),
                      onTap: () async {
                        await controller.patch({
                          'boxEnabled': true,
                          'boxAddress': _paired[i].address,
                          'boxName': _paired[i].displayName,
                        });
                        if (context.mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(
                              content: Text(
                                '${_paired[i].displayName} is now guarded.',
                              ),
                            ),
                          );
                        }
                      },
                    ),
                  ],
                ],
              ),
            ),
          const SizedBox(height: 16),
          Text(
            'Only one phone needs to hold the speaker connection. Whichever '
            'phone is playing the music is the one that will notice it leaving.',
            style: TextStyle(
              fontSize: 12.5,
              height: 1.4,
              color: context.colors.onSurface.withValues(alpha: 0.58),
            ),
          ),
        ],
      ),
    );
  }
}
