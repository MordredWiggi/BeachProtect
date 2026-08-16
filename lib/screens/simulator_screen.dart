import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/guard_bridge.dart';
import '../core/guard_controller.dart';
import '../core/models.dart';
import '../core/theme.dart';
import '../widgets/common.dart';

/// Runs scripted situations through the real detector.
///
/// The scenarios feed synthetic beacons into exactly the same engine entry
/// points that the Bluetooth radio uses, so a pass here means the detection
/// logic genuinely behaves - including the cases that are impossible to stage
/// on demand, like a stranger walking between two phones at the wrong moment.
class SimulatorScreen extends StatefulWidget {
  const SimulatorScreen({super.key});

  @override
  State<SimulatorScreen> createState() => _SimulatorScreenState();
}

class _SimulatorScreenState extends State<SimulatorScreen> {
  List<SimScenario> _scenarios = const [];
  bool _loading = true;
  bool _runningAll = false;
  String? _queued;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final controller = context.read<GuardController>();
    final list = await controller.bridge.simulationCatalogue();
    if (!mounted) return;
    setState(() {
      _scenarios = list;
      _loading = false;
    });
  }

  @override
  void dispose() {
    // Never leave virtual peers behind in a real guard. The bridge is captured
    // rather than read from context, which is no longer valid during dispose.
    unawaited(_bridge.stopSimulation());
    super.dispose();
  }

  GuardBridge get _bridge => GuardBridge.instance;

  Future<void> _run(SimScenario scenario) async {
    setState(() => _queued = scenario.id);
    await _bridge.startSimulation(scenario.id);
  }

  Future<void> _runAll() async {
    setState(() => _runningAll = true);
    for (final scenario in _scenarios) {
      if (!mounted || !_runningAll) break;
      setState(() => _queued = scenario.id);
      await _bridge.startSimulation(scenario.id);
      await _awaitScenarioEnd(scenario);
    }
    if (mounted) setState(() => _runningAll = false);
  }

  /// Waits for the scenario to reach a verdict.
  ///
  /// Scenarios now stop the moment the answer is known rather than running out
  /// the clock, so polling for completion is much faster than sleeping for the
  /// full duration. The timeout is only a safety net.
  Future<void> _awaitScenarioEnd(SimScenario scenario) async {
    // Captured before the first await: the controller outlives this screen, so
    // holding it is safe where holding the BuildContext would not be.
    final controller = context.read<GuardController>();
    final deadline = DateTime.now().add(
      Duration(milliseconds: scenario.durationMs + 6000),
    );
    // Give the service a moment to actually pick the scenario up.
    await Future<void>.delayed(const Duration(milliseconds: 900));
    while (mounted && _runningAll && DateTime.now().isBefore(deadline)) {
      if (!controller.snapshot.diagnostics.simulationRunning) break;
      await Future<void>.delayed(const Duration(milliseconds: 400));
    }
    // Short breather so the guard is fully back to calm before the next one.
    await Future<void>.delayed(const Duration(milliseconds: 800));
  }

  @override
  Widget build(BuildContext context) {
    final controller = context.watch<GuardController>();
    final snapshot = controller.snapshot;
    final d = snapshot.diagnostics;

    return Scaffold(
      appBar: AppBar(
        title: const Text('Test scenarios'),
        actions: [
          if (d.simulationRunning || _runningAll)
            TextButton(
              onPressed: () async {
                setState(() {
                  _runningAll = false;
                  _queued = null;
                });
                await controller.bridge.stopSimulation();
              },
              child: const Text('Stop'),
            ),
        ],
      ),
      body: _loading
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              padding: const EdgeInsets.fromLTRB(16, 8, 16, 40),
              children: [
                _LivePanel(snapshot: snapshot),
                const SizedBox(height: 14),
                Container(
                  padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
                  decoration: BoxDecoration(
                    color: context.status.calibrating.withValues(alpha: 0.11),
                    borderRadius: BorderRadius.circular(14),
                  ),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Icon(Icons.info_rounded,
                          size: 18, color: context.status.calibrating),
                      const SizedBox(width: 9),
                      Expanded(
                        child: Text(
                          'These are rehearsals. A scenario that trips the '
                          'detector plays a short confirmation beep instead of '
                          'the real siren, tells nobody else, and stands the '
                          'guard straight back up - so the run continues and '
                          'your phone stays armed.',
                          style: TextStyle(
                            fontSize: 12.5,
                            height: 1.4,
                            color:
                                context.colors.onSurface.withValues(alpha: 0.7),
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 14),
                FilledButton.icon(
                  onPressed: _runningAll ? null : _runAll,
                  icon: const Icon(Icons.playlist_play_rounded),
                  label: Text(
                    _runningAll ? 'Running the whole suite...' : 'Run all scenarios',
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Each scenario stops as soon as its verdict is decided, so '
                  'the whole suite takes a couple of minutes. Keep this screen '
                  'open and leave the phone lying still.',
                  style: TextStyle(
                    fontSize: 12.5,
                    height: 1.4,
                    color: context.colors.onSurface.withValues(alpha: 0.58),
                  ),
                ),
                const SizedBox(height: 22),
                SectionHeader(title: 'Scenarios'),
                for (final scenario in _scenarios) ...[
                  _ScenarioCard(
                    scenario: scenario,
                    verdict: d.simulationResults[scenario.id],
                    timeToAlarmMs: d.simulationTimings[scenario.id],
                    active: d.simulationScenario == scenario.id &&
                        d.simulationRunning,
                    queued: _queued == scenario.id && !d.simulationRunning,
                    onRun: _runningAll ? null : () => _run(scenario),
                  ),
                  const SizedBox(height: 10),
                ],
              ],
            ),
    );
  }
}

class _LivePanel extends StatelessWidget {
  const _LivePanel({required this.snapshot});

  final GuardSnapshot snapshot;

  @override
  Widget build(BuildContext context) {
    final d = snapshot.diagnostics;
    final status = context.status;
    final tint = switch (snapshot.state) {
      GuardState.alarm => status.alarm,
      GuardState.pending || GuardState.suspicious => status.suspicious,
      GuardState.armed => status.armed,
      _ => status.calibrating,
    };

    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 16, 18, 18),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Container(
                  width: 10,
                  height: 10,
                  decoration: BoxDecoration(color: tint, shape: BoxShape.circle),
                ),
                const SizedBox(width: 9),
                Text(
                  snapshot.state.label,
                  style: TextStyle(
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                    color: tint,
                  ),
                ),
                const Spacer(),
                if (d.simulationRunning)
                  Text(
                    '${(d.simulationElapsedMs / 1000).round()}s',
                    style: TextStyle(
                      fontSize: 13,
                      fontWeight: FontWeight.w700,
                      color: context.colors.onSurface.withValues(alpha: 0.6),
                    ),
                  ),
              ],
            ),
            if (d.simulationNote.isNotEmpty) ...[
              const SizedBox(height: 6),
              Text(
                d.simulationNote,
                style: TextStyle(
                  fontSize: 13.5,
                  color: context.colors.onSurface.withValues(alpha: 0.7),
                ),
              ),
            ],
            const SizedBox(height: 14),
            Wrap(
              spacing: 7,
              runSpacing: 7,
              children: [
                for (final peer in snapshot.peers)
                  InfoChip(
                    icon: peer.stationary
                        ? Icons.smartphone_rounded
                        : Icons.directions_walk_rounded,
                    label:
                        '${peer.displayName}  ${peer.rssi?.toStringAsFixed(0) ?? '-'} dBm'
                        '${(peer.dropDb ?? 0) > 3 ? '  (-${peer.dropDb!.toStringAsFixed(0)})' : ''}',
                    color: peer.suspected ? status.suspicious : null,
                    emphasise: peer.suspected,
                  ),
                if (snapshot.peers.isEmpty)
                  const InfoChip(
                    icon: Icons.hourglass_empty_rounded,
                    label: 'no peers yet',
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

class _ScenarioCard extends StatelessWidget {
  const _ScenarioCard({
    required this.scenario,
    required this.verdict,
    required this.timeToAlarmMs,
    required this.active,
    required this.queued,
    required this.onRun,
  });

  final SimScenario scenario;
  final String? verdict;
  final int? timeToAlarmMs;
  final bool active;
  final bool queued;
  final VoidCallback? onRun;

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    final passed = verdict == 'PASSED';
    final failed = verdict == 'FAILED';

    return Card(
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(20),
        side: BorderSide(
          color: active
              ? status.calibrating
              : failed
                  ? status.alarm
                  : status.hairline,
          width: active || failed ? 2 : 1,
        ),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Expanded(
                  child: Text(
                    scenario.title,
                    style: const TextStyle(
                      fontSize: 15.5,
                      fontWeight: FontWeight.w700,
                    ),
                  ),
                ),
                if (passed)
                  Icon(Icons.check_circle_rounded,
                      color: status.armed, size: 20)
                else if (failed)
                  Icon(Icons.cancel_rounded, color: status.alarm, size: 20),
              ],
            ),
            const SizedBox(height: 6),
            Text(
              scenario.description,
              style: TextStyle(
                fontSize: 13,
                height: 1.42,
                color: context.colors.onSurface.withValues(alpha: 0.68),
              ),
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                InfoChip(
                  icon: scenario.shouldAlarm
                      ? Icons.notifications_active_rounded
                      : Icons.notifications_off_rounded,
                  label: scenario.shouldAlarm
                      ? 'must alarm'
                      : 'must stay silent',
                  color: scenario.shouldAlarm
                      ? status.alarm
                      : status.armed,
                  emphasise: true,
                ),
                const SizedBox(width: 7),
                if (timeToAlarmMs != null && scenario.shouldAlarm)
                  InfoChip(
                    icon: Icons.timer_rounded,
                    label: 'caught in ${(timeToAlarmMs! / 1000).toStringAsFixed(1)}s',
                    color: scenario.budgetMs > 0 && timeToAlarmMs! > scenario.budgetMs
                        ? status.suspicious
                        : status.armed,
                    emphasise: true,
                  )
                else
                  InfoChip(
                    icon: Icons.schedule_rounded,
                    label: 'up to ${(scenario.durationMs / 1000).round()}s',
                  ),
                const Spacer(),
                if (active)
                  const SizedBox(
                    width: 18,
                    height: 18,
                    child: CircularProgressIndicator(strokeWidth: 2.2),
                  )
                else if (queued)
                  Text(
                    'starting...',
                    style: TextStyle(
                      fontSize: 12.5,
                      color: context.colors.onSurface.withValues(alpha: 0.6),
                    ),
                  )
                else
                  TextButton(
                    onPressed: onRun,
                    child: const Text('Run'),
                  ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
