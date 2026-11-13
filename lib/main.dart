import 'dart:async';

import 'package:flutter/widgets.dart';

import 'core/copy/lindo_copy.dart';
import 'core/diagnostics/lindo_diagnostic_logger.dart';
import 'core/network/linux_do_repository.dart';
import 'core/platform/lindo_platform_channels.dart';
import 'core/storage/lindo_local_store.dart';
import 'lindo_app.dart';

export 'lindo_app.dart';

Future<void> main() async {
  runZonedGuarded(
    () async {
      WidgetsFlutterBinding.ensureInitialized();
      LinDoDiagnosticLogger.instance.installGlobalCrashHandlers();

      final stateDir = await LinDoPlatformBridge.instance.resolveStateDirectory();
      await LinDoDiagnosticLogger.instance.initialize(stateDir: stateDir);
      await LinDoBrowserBridge.instance.initialize();

      final store = LinDoLocalStore(stateDirOverride: stateDir);
      await store.initialize(stateDir: stateDir);

      final repository = LinuxDoRepository(store: store);

      LinDoDiagnosticLogger.instance.info(
        DiagnosticCategory.SYSTEM,
        'Startup',
        'Lindo $kLinDoAppVersionName ($kLinDoAppBuildNumber) initialized',
      );

      runApp(
        LinDoApp(
          store: store,
          repository: repository,
        ),
      );
    },
    (Object error, StackTrace stack) {
      LinDoDiagnosticLogger.instance.crash('UncaughtZone', error, stack);
    },
  );
}
