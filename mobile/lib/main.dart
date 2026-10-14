// High Bytes POS - the drivers' mobile POS of iWarehouse as an Android and iOS app (Flutter). It talks to the same API as
// the web app (/api/v1) and works the same way, offline first.
//
// Build settings (--dart-define):
//   SERVER_URL   the server's address the sign-in screen starts with (the driver may change it), e.g. https://pos.example.rw
//   ALLOW_HTTP   true to accept a plain http:// address (a test server on the office network); never for the real server

import 'dart:convert';
import 'dart:io';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:http/http.dart' as http;

import 'api.dart';
import 'app.dart';
import 'controller.dart';
import 'device.dart';
import 'store.dart';

const appVersion = '1.0.0';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final store = await SqliteStore.open();
  final controller = PosController(
    store: store,
    api: Api(http.Client(), userAgent: 'HighBytesPOS/$appVersion (${Platform.operatingSystem} ${Platform.operatingSystemVersion})'),
    network: PhoneNetwork(),
    bundledTexts: (lang) async =>
        Map<String, String>.from(jsonDecode(await rootBundle.loadString('assets/texts/$lang.json')) as Map),
    defaultServer: const String.fromEnvironment('SERVER_URL'),
    allowHttp: const bool.fromEnvironment('ALLOW_HTTP'),
    systemLanguage: WidgetsBinding.instance.platformDispatcher.locale.languageCode,
  );
  runApp(PosApp(c: controller));
  await controller.start();
}
