import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_controller.dart';
import '../core/theme.dart';
import 'permissions_screen.dart';
import 'qr_scan_screen.dart';

/// Create or join a group. The app's default screen whenever there is no group.
///
/// This used to be step two of the first-run wizard, which meant leaving a
/// group threw the user back to "Watch each other's things", a name field they
/// had already filled in, and a four-step progress bar counting through a PIN
/// and a permissions walkthrough that were both long since done. Setting up the
/// phone and belonging to a group are simply not the same event: the first
/// happens once, the second happens every time the group changes.
class GroupGateScreen extends StatefulWidget {
  const GroupGateScreen({super.key});

  @override
  State<GroupGateScreen> createState() => _GroupGateScreenState();
}

class _GroupGateScreenState extends State<GroupGateScreen> {
  final _groupNameController = TextEditingController(text: 'Beach day');
  final _codeController = TextEditingController();

  bool _joining = false;
  bool _busy = false;
  String? _error;

  @override
  void dispose() {
    _groupNameController.dispose();
    _codeController.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();
    final name = controller.settings.selfName;

    return Scaffold(
      body: SafeArea(
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.fromLTRB(22, 28, 22, 12),
                children: [
                  Text(
                    _joining ? 'Join a group' : 'Start a group',
                    style: const TextStyle(
                      fontSize: 27,
                      fontWeight: FontWeight.w800,
                    ),
                  ),
                  const SizedBox(height: 8),
                  Text(
                    _joining
                        ? 'Scan the QR from a phone that already has the group, '
                            'or type its code.'
                        : 'One person creates the group and shares the code. '
                            'Everyone else joins it. Nothing is guarded until '
                            'you are all in the same one.',
                    style: TextStyle(
                      fontSize: 15,
                      height: 1.45,
                      color: context.colors.onSurface.withValues(alpha: 0.68),
                    ),
                  ),
                  const SizedBox(height: 24),

                  SegmentedButton<bool>(
                    segments: const [
                      ButtonSegment(
                        value: false,
                        label: Text('Create'),
                        icon: Icon(Icons.add_rounded),
                      ),
                      ButtonSegment(
                        value: true,
                        label: Text('Join'),
                        icon: Icon(Icons.qr_code_scanner_rounded),
                      ),
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
                              color: context.colors.onSurface
                                  .withValues(alpha: 0.5),
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

                  if (_error != null) ...[
                    const SizedBox(height: 16),
                    Text(
                      _error!,
                      style: TextStyle(
                        color: context.status.alarm,
                        fontSize: 14,
                      ),
                    ),
                  ],

                  const SizedBox(height: 30),
                  // The only place to change the name once the group step no
                  // longer asks for it, and the others see it, so it is worth
                  // one line here rather than being buried in Settings.
                  Card(
                    child: ListTile(
                      leading: Icon(
                        Icons.person_rounded,
                        color: context.colors.primary,
                      ),
                      title: Text(name.isEmpty ? 'Unnamed' : name),
                      subtitle: const Text('The name the others will see'),
                      trailing: const Icon(Icons.edit_rounded, size: 18),
                      onTap: () => _renameSelf(controller),
                    ),
                  ),
                  const SizedBox(height: 8),
                  TextButton.icon(
                    onPressed: () => Navigator.of(context).push(
                      MaterialPageRoute(
                        builder: (_) => const PermissionsScreen(),
                      ),
                    ),
                    icon: const Icon(Icons.tune_rounded, size: 18),
                    label: const Text('Permissions and setup'),
                  ),
                ],
              ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(22, 8, 22, 20),
              child: FilledButton(
                onPressed: _busy ? null : _submit,
                child: _busy
                    ? const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2.4),
                      )
                    : Text(_joining ? 'Join group' : 'Create group'),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // =====================================================================

  Future<void> _renameSelf(GuardController controller) async {
    final field = TextEditingController(text: controller.settings.selfName);
    final name = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Your name'),
        content: TextField(
          controller: field,
          autofocus: true,
          maxLength: 12,
          textCapitalization: TextCapitalization.words,
          decoration: const InputDecoration(
            helperText: 'Shown to the others. Up to 12 characters.',
          ),
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
    if (name != null && name.isNotEmpty) {
      await controller.patch({'selfName': name});
    }
  }

  Future<void> _scanQr() async {
    final code = await Navigator.of(context).push<String>(
      MaterialPageRoute(builder: (_) => const QrScanScreen()),
    );
    if (code != null && mounted) {
      _codeController.text = code;
      await _submit();
    }
  }

  Future<void> _submit() async {
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
          selfName: controller.settings.selfName,
        );
        if (!ok && mounted) {
          setState(() =>
              _error = 'That code is not valid. Check it and try again.');
        }
      } else {
        await controller.createGroup(
          groupName: _groupNameController.text.trim().isEmpty
              ? 'Beach day'
              : _groupNameController.text.trim(),
          selfName: controller.settings.selfName,
        );
      }
      // Nothing to navigate to: the root swaps this screen for the home screen
      // the moment the settings say a group exists.
    } catch (e) {
      if (mounted) setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }
}
