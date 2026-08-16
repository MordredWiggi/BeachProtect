import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  // Portrait only: the whole UI is a single vertical column, and a phone lying
  // on a towel has no business rotating.
  SystemChrome.setPreferredOrientations([
    DeviceOrientation.portraitUp,
    DeviceOrientation.portraitDown,
  ]);
  runApp(const BeachProtectApp());
}
