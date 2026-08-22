import 'package:flutter/material.dart';

import '../core/models.dart';
import '../core/theme.dart';
import 'common.dart';

/// One group member, as this phone currently sees them.
class PeerCard extends StatelessWidget {
  const PeerCard({super.key, required this.peer, this.onRename});

  final PeerInfo peer;
  final VoidCallback? onRename;

  /// An annotation rather than a state.
  ///
  /// "Not heard for 7s" tells you the link is having a moment without claiming
  /// anything has changed about the phone, which is the honest reading: at the
  /// calm duty cycle the scanner is listening about a quarter of the time, so
  /// gaps this long are the radio's business and not the group's.
  String get _faintSuffix =>
      peer.faint ? ' · not heard for ${(peer.lastSeenMsAgo / 1000).round()}s' : '';

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    final Color tint;
    final String stateLine;

    // Being properly lost is tested first, and outranks every memory: a phone
    // nobody has heard from in half a minute must not go on being rendered as
    // whatever it last said — "Alarming" for minutes after an incident was over,
    // "Not guarding" from a beacon caught mid-rearm.
    //
    // A peer that is merely *faint* deliberately falls through to exactly the
    // line it would have had anyway, with a note appended. That is the whole fix
    // for the flickering group list. Silence long enough to notice is a normal
    // event on a duty-cycled radio — a run of missed scan windows, several times
    // an hour — and treating it as a state of its own meant every one of those
    // repainted the card green-to-grey and straight back. The reader cannot tell
    // that apart from the group genuinely changing its mind twice a second.
    if (peer.lost) {
      tint = status.disarmed;
      stateLine = 'No signal for ${(peer.lastSeenMsAgo / 1000).round()}s';
    } else if (peer.alarming) {
      tint = status.alarm;
      stateLine = 'Alarming';
    } else if (peer.suspected) {
      tint = status.suspicious;
      stateLine = peer.votesRequired > 1
          ? 'Moving away (${peer.votesAgainst}/${peer.votesRequired} agree)'
          : 'Moving away';
    } else if (!peer.armed) {
      tint = status.disarmed;
      stateLine = 'Not guarding$_faintSuffix';
    } else {
      tint = status.armed;
      stateLine =
          '${peer.stationary ? 'Still, watched' : 'Moving'}$_faintSuffix';
    }

    return Card(
      child: InkWell(
        onTap: onRename,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Container(
                    width: 38,
                    height: 38,
                    decoration: BoxDecoration(
                      color: tint.withValues(alpha: 0.16),
                      shape: BoxShape.circle,
                    ),
                    child: Icon(
                      peer.lost
                          ? Icons.signal_cellular_off_rounded
                          : peer.alarming
                              ? Icons.warning_rounded
                              : peer.stationary
                                  ? Icons.smartphone_rounded
                                  : Icons.directions_walk_rounded,
                      size: 20,
                      color: tint,
                    ),
                  ),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Flexible(
                              child: Text(
                                peer.displayName,
                                overflow: TextOverflow.ellipsis,
                                style: const TextStyle(
                                  fontSize: 16,
                                  fontWeight: FontWeight.w700,
                                ),
                              ),
                            ),
                            if (peer.simulated) ...[
                              const SizedBox(width: 6),
                              const InfoChip(
                                icon: Icons.science_rounded,
                                label: 'sim',
                              ),
                            ],
                          ],
                        ),
                        const SizedBox(height: 2),
                        Text(
                          stateLine,
                          style: TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                            color: tint,
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(width: 8),
                  Column(
                    crossAxisAlignment: CrossAxisAlignment.end,
                    children: [
                      SignalBars(proximity: peer.proximity, color: tint),
                      const SizedBox(height: 5),
                      Text(
                        peer.proximity.label,
                        style: TextStyle(
                          fontSize: 11.5,
                          fontWeight: FontWeight.w600,
                          color:
                              context.colors.onSurface.withValues(alpha: 0.55),
                        ),
                      ),
                    ],
                  ),
                ],
              ),
              const SizedBox(height: 12),
              Wrap(
                spacing: 7,
                runSpacing: 7,
                children: [
                  InfoChip(
                    icon: _batteryIcon(peer.battery),
                    label: '${peer.battery}%',
                    color: peer.battery <= 10 ? status.suspicious : null,
                    emphasise: peer.battery <= 10,
                  ),
                  if (peer.boxGuardian)
                    InfoChip(
                      icon: Icons.speaker_rounded,
                      label: 'holds speaker',
                      color: status.calibrating,
                      emphasise: true,
                    ),
                  // Both of these are readings rather than states, so they go
                  // once the reading behind them stops being current — which is
                  // a different and stricter question than what the card says.
                  if (peer.current && peer.dropDb != null && peer.dropDb! > 3)
                    InfoChip(
                      icon: Icons.trending_down_rounded,
                      label: '-${peer.dropDb!.toStringAsFixed(0)} dB',
                      color: peer.dropDb! > 10 ? status.suspicious : null,
                      emphasise: peer.dropDb! > 10,
                    ),
                  if (peer.current && !peer.stationary)
                    InfoChip(
                      icon: Icons.vibration_rounded,
                      label: 'in motion',
                      color: status.suspicious,
                      emphasise: true,
                    ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }

  IconData _batteryIcon(int percent) {
    if (percent >= 80) return Icons.battery_full_rounded;
    if (percent >= 50) return Icons.battery_5_bar_rounded;
    if (percent >= 25) return Icons.battery_3_bar_rounded;
    if (percent >= 10) return Icons.battery_2_bar_rounded;
    return Icons.battery_alert_rounded;
  }
}

/// The guarded speaker, and how it is currently being watched.
class BoxCard extends StatelessWidget {
  const BoxCard({super.key, required this.box, this.onTap});

  final BoxInfo box;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final status = context.status;
    final healthy = box.audioLinkConnected;
    final tint = healthy ? status.armed : status.suspicious;

    return Card(
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(20),
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
          child: Row(
            children: [
              Container(
                width: 38,
                height: 38,
                decoration: BoxDecoration(
                  color: tint.withValues(alpha: 0.16),
                  shape: BoxShape.circle,
                ),
                child: Icon(Icons.speaker_rounded, size: 20, color: tint),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      box.displayName,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontSize: 16,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 2),
                    Text(
                      healthy
                          ? (box.guardedByThisPhone
                              ? 'Audio link held by this phone'
                              : 'Audio link held by another phone')
                          : 'Audio link down',
                      style: TextStyle(
                        fontSize: 13,
                        fontWeight: FontWeight.w600,
                        color: tint,
                      ),
                    ),
                    if (box.bleTracked) ...[
                      const SizedBox(height: 6),
                      InfoChip(
                        icon: Icons.bluetooth_searching_rounded,
                        label: 'beacon: ${box.bleProximity.label.toLowerCase()}',
                        color: status.calibrating,
                      ),
                    ],
                  ],
                ),
              ),
              if (box.bleTracked)
                SignalBars(proximity: box.bleProximity, color: tint),
            ],
          ),
        ),
      ),
    );
  }
}
