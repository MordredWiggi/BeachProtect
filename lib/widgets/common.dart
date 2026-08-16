import 'package:flutter/material.dart';

import '../core/models.dart';
import '../core/theme.dart';

/// Small caps section heading used to break the home screen into scannable bands.
class SectionHeader extends StatelessWidget {
  const SectionHeader({super.key, required this.title, this.trailing});

  final String title;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 8, 4, 10),
      child: Row(
        children: [
          Text(
            title.toUpperCase(),
            style: TextStyle(
              fontSize: 12,
              letterSpacing: 1.4,
              fontWeight: FontWeight.w800,
              color: context.colors.onSurface.withValues(alpha: 0.55),
            ),
          ),
          const Spacer(),
          ?trailing,
        ],
      ),
    );
  }
}

/// Five-bar signal indicator driven by the coarse proximity bucket.
///
/// Bars rather than a dB figure on purpose: RSSI-to-distance is only good to
/// about a factor of two in the open, so showing "17 m" would be inventing
/// precision the radio does not have.
class SignalBars extends StatelessWidget {
  const SignalBars({
    super.key,
    required this.proximity,
    required this.color,
    this.size = 18,
  });

  final Proximity proximity;
  final Color color;
  final double size;

  @override
  Widget build(BuildContext context) {
    const barCount = 5;
    final filled = (proximity.strength * barCount).round();
    return SizedBox(
      height: size,
      child: Row(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: List.generate(barCount, (index) {
          final active = index < filled;
          return Container(
            width: size * 0.18,
            height: size * (0.32 + 0.17 * index),
            margin: EdgeInsets.only(right: size * 0.1),
            decoration: BoxDecoration(
              color: active ? color : context.status.subtle,
              borderRadius: BorderRadius.circular(size * 0.09),
            ),
          );
        }),
      ),
    );
  }
}

/// Compact labelled pill used for battery, motion state and similar facts.
class InfoChip extends StatelessWidget {
  const InfoChip({
    super.key,
    required this.icon,
    required this.label,
    this.color,
    this.emphasise = false,
  });

  final IconData icon;
  final String label;
  final Color? color;
  final bool emphasise;

  @override
  Widget build(BuildContext context) {
    final tint = color ?? context.colors.onSurface.withValues(alpha: 0.7);
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 9, vertical: 5),
      decoration: BoxDecoration(
        color: emphasise ? tint.withValues(alpha: 0.14) : context.status.subtle,
        borderRadius: BorderRadius.circular(9),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 13, color: tint),
          const SizedBox(width: 5),
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: tint,
            ),
          ),
        ],
      ),
    );
  }
}

/// Banner for the warning set. Severe warnings mean the guard cannot actually
/// do its job, so they are styled to look like a problem rather than a note.
class WarningBanner extends StatelessWidget {
  const WarningBanner({
    super.key,
    required this.warning,
    this.onAction,
    this.actionLabel,
  });

  final GuardWarning warning;
  final VoidCallback? onAction;
  final String? actionLabel;

  @override
  Widget build(BuildContext context) {
    final severe = warning.isSevere;
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
          Icon(
            severe ? Icons.error_rounded : Icons.info_rounded,
            size: 20,
            color: tint,
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              warning.label,
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

/// A settings row with a title, explanation and trailing control.
class SettingTile extends StatelessWidget {
  const SettingTile({
    super.key,
    required this.title,
    this.subtitle,
    this.trailing,
    this.leading,
    this.onTap,
  });

  final String title;
  final String? subtitle;
  final Widget? trailing;
  final Widget? leading;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      borderRadius: BorderRadius.circular(14),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.center,
          children: [
            if (leading != null) ...[leading!, const SizedBox(width: 14)],
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: const TextStyle(
                      fontSize: 15.5,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  if (subtitle != null) ...[
                    const SizedBox(height: 3),
                    Text(
                      subtitle!,
                      style: TextStyle(
                        fontSize: 13,
                        height: 1.35,
                        color: context.colors.onSurface.withValues(alpha: 0.62),
                      ),
                    ),
                  ],
                ],
              ),
            ),
            if (trailing != null) ...[const SizedBox(width: 12), trailing!],
          ],
        ),
      ),
    );
  }
}

/// Groups [SettingTile]s into a card with a heading.
class SettingsGroup extends StatelessWidget {
  const SettingsGroup({super.key, required this.title, required this.children});

  final String title;
  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SectionHeader(title: title),
        Card(
          child: Column(
            children: [
              for (var i = 0; i < children.length; i++) ...[
                if (i > 0) const Divider(indent: 16, endIndent: 16),
                children[i],
              ],
            ],
          ),
        ),
        const SizedBox(height: 18),
      ],
    );
  }
}
