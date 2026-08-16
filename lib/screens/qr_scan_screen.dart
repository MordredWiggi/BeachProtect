import 'package:flutter/material.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../core/theme.dart';

/// Reads the group QR and pops the code back to the caller.
///
/// The QR payload is exactly the printed group code, so a phone that cannot
/// scan can always be joined by typing the same 16 characters instead.
class QrScanScreen extends StatefulWidget {
  const QrScanScreen({super.key});

  @override
  State<QrScanScreen> createState() => _QrScanScreenState();
}

class _QrScanScreenState extends State<QrScanScreen> {
  final _controller = MobileScannerController(
    detectionSpeed: DetectionSpeed.noDuplicates,
    formats: const [BarcodeFormat.qrCode],
  );
  bool _handled = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  void _onDetect(BarcodeCapture capture) {
    if (_handled) return;
    for (final barcode in capture.barcodes) {
      final value = barcode.rawValue?.trim();
      if (value == null || value.isEmpty) continue;
      _handled = true;
      Navigator.of(context).pop(_extractCode(value));
      return;
    }
  }

  /// Accepts either a bare code or a `beachprotect:CODE` style payload.
  String _extractCode(String raw) {
    final trimmed = raw.trim();
    final marker = trimmed.lastIndexOf(':');
    return marker >= 0 ? trimmed.substring(marker + 1).trim() : trimmed;
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: const Text('Scan the group QR'),
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          MobileScanner(controller: _controller, onDetect: _onDetect),
          IgnorePointer(
            child: Center(
              child: Container(
                width: 250,
                height: 250,
                decoration: BoxDecoration(
                  border: Border.all(color: context.status.calibrating, width: 3),
                  borderRadius: BorderRadius.circular(24),
                ),
              ),
            ),
          ),
          Positioned(
            left: 24,
            right: 24,
            bottom: 40,
            child: Text(
              'Point at the QR on a phone that is already in the group.',
              textAlign: TextAlign.center,
              style: const TextStyle(
                color: Colors.white,
                fontSize: 14,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
