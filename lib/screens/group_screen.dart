import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import 'package:qr_flutter/qr_flutter.dart';

import '../core/guard_controller.dart';
import '../core/models.dart';
import '../core/theme.dart';
import '../widgets/common.dart';

/// Shows the group code as a QR plus text, and lists who is in the group.
class GroupScreen extends StatelessWidget {
  const GroupScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();
    final settings = controller.settings;
    final snapshot = controller.snapshot;
    final code = settings.groupCode;

    return Scaffold(
      appBar: AppBar(title: const Text('Group')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
        children: [
          if (code != null) ...[
            Card(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 24, 20, 20),
                child: Column(
                  children: [
                    Text(
                      settings.groupName.isEmpty ? 'Your group' : settings.groupName,
                      style: const TextStyle(
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(
                      'Others scan this to join',
                      style: TextStyle(
                        fontSize: 13,
                        color: context.colors.onSurface.withValues(alpha: 0.6),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Container(
                      padding: const EdgeInsets.all(14),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(18),
                      ),
                      child: QrImageView(
                        data: code,
                        version: QrVersions.auto,
                        size: 210,
                        backgroundColor: Colors.white,
                        eyeStyle: const QrEyeStyle(
                          eyeShape: QrEyeShape.square,
                          color: Color(0xFF0B2235),
                        ),
                        dataModuleStyle: const QrDataModuleStyle(
                          dataModuleShape: QrDataModuleShape.square,
                          color: Color(0xFF0B2235),
                        ),
                      ),
                    ),
                    const SizedBox(height: 20),
                    SelectableText(
                      code,
                      style: const TextStyle(
                        fontFamily: 'monospace',
                        fontSize: 21,
                        letterSpacing: 2.4,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 14),
                    Row(
                      children: [
                        Expanded(
                          child: OutlinedButton.icon(
                            onPressed: () async {
                              final messenger = ScaffoldMessenger.of(context);
                              await Clipboard.setData(ClipboardData(text: code));
                              messenger.showSnackBar(
                                const SnackBar(content: Text('Code copied.')),
                              );
                            },
                            icon: const Icon(Icons.copy_rounded),
                            label: const Text('Copy'),
                          ),
                        ),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 10),
            Text(
              'Anyone with this code can join the group and silence its alarms, '
              'so share it the way you would share a door key.',
              style: TextStyle(
                fontSize: 12.5,
                height: 1.4,
                color: context.colors.onSurface.withValues(alpha: 0.55),
              ),
            ),
            const SizedBox(height: 22),
          ],

          SectionHeader(title: 'This phone'),
          Card(
            child: SettingTile(
              leading: Icon(Icons.smartphone_rounded, color: context.colors.primary),
              title: settings.selfName.isEmpty ? 'Unnamed' : settings.selfName,
              subtitle:
                  'ID ${settings.deviceId.toRadixString(16).toUpperCase().padLeft(4, '0')}'
                  '  -  ${snapshot.selfStationary ? 'lying still' : 'moving'}',
              trailing: const Icon(Icons.edit_rounded, size: 18),
              onTap: () => _renameSelf(context, controller),
            ),
          ),
          const SizedBox(height: 22),

          SectionHeader(title: 'Members'),
          if (snapshot.peers.isEmpty)
            Card(
              child: Padding(
                padding: const EdgeInsets.all(20),
                child: Text(
                  'Nobody else has been heard yet. Everyone needs to join this '
                  'group and have Bluetooth switched on.',
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
                  for (var i = 0; i < snapshot.peers.length; i++) ...[
                    if (i > 0) const Divider(indent: 16, endIndent: 16),
                    SettingTile(
                      leading: Icon(
                        Icons.smartphone_rounded,
                        color: snapshot.peers[i].armed
                            ? context.status.armed
                            : context.status.disarmed,
                      ),
                      title: snapshot.peers[i].displayName,
                      subtitle: snapshot.peers[i].armed
                          ? 'Guarding  -  ${snapshot.peers[i].proximity.label.toLowerCase()}'
                          : 'Not guarding',
                      trailing: const Icon(Icons.edit_rounded, size: 18),
                      onTap: () => _renamePeer(context, controller, i),
                    ),
                  ],
                ],
              ),
            ),

          const SizedBox(height: 30),
          OutlinedButton.icon(
            style: OutlinedButton.styleFrom(
              foregroundColor: context.status.alarm,
              side: BorderSide(color: context.status.alarm.withValues(alpha: 0.5)),
            ),
            onPressed: () => _leave(context, controller),
            icon: const Icon(Icons.logout_rounded),
            label: const Text('Leave this group'),
          ),
        ],
      ),
    );
  }

  Future<void> _renameSelf(
    BuildContext context,
    GuardController controller,
  ) async {
    final name = await _askName(context, controller.settings.selfName);
    if (name != null) {
      await controller.patch({'selfName': name});
    }
  }

  Future<void> _renamePeer(
    BuildContext context,
    GuardController controller,
    int index,
  ) async {
    final peer = controller.snapshot.peers[index];
    final name = await _askName(context, peer.displayName);
    if (name != null) {
      await controller.bridge.renamePeer(peer.deviceId, name.isEmpty ? null : name);
      await controller.refreshSettings();
    }
  }

  Future<String?> _askName(BuildContext context, String initial) {
    final field = TextEditingController(text: initial);
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Name'),
        content: TextField(
          controller: field,
          autofocus: true,
          maxLength: 12,
          textCapitalization: TextCapitalization.words,
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
  }

  Future<void> _leave(BuildContext context, GuardController controller) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Leave the group?'),
        content: const Text(
          'This phone stops guarding and forgets the group code. You will need '
          'the code again to rejoin.',
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: const Text('Cancel'),
          ),
          FilledButton(
            style: FilledButton.styleFrom(
              backgroundColor: Theme.of(context).colorScheme.error,
            ),
            onPressed: () => Navigator.pop(context, true),
            child: const Text('Leave'),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await controller.leaveGroup();
      if (context.mounted) Navigator.of(context).pop();
    }
  }
}
