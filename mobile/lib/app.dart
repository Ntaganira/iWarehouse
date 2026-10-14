// High Bytes POS - the app: the phone's light or dark setting, the driver's language, and one screen at a time as the web
// app shows them (signed out, no trip, sell, pay, receipt, sales, breakage, declare). The system's back goes to the
// screen before; coming back to the app sends what waits.

import 'package:flutter/material.dart';
import 'package:flutter_localizations/flutter_localizations.dart';

import 'controller.dart';
import 'screens/breakage.dart';
import 'screens/cart.dart';
import 'screens/day.dart';
import 'screens/declare.dart';
import 'screens/login.dart';
import 'screens/no_trip.dart';
import 'screens/receipt.dart';
import 'screens/sales.dart';
import 'screens/sell.dart';
import 'theme.dart';
import 'widgets.dart';

class PosApp extends StatelessWidget {
  const PosApp({super.key, required this.c, this.fontFamily});

  final PosController c;
  final String? fontFamily; // see buildTheme

  @override
  Widget build(BuildContext context) => ListenableBuilder(
        listenable: c,
        builder: (context, _) => MaterialApp(
          title: 'High Bytes POS',
          debugShowCheckedModeBanner: false,
          theme: buildTheme(Brightness.light, fontFamily: fontFamily),
          darkTheme: buildTheme(Brightness.dark, fontFamily: fontFamily),
          themeMode: ThemeMode.system,
          locale: Locale(c.lang),
          supportedLocales: const [Locale('en'), Locale('fr')],
          localizationsDelegates: GlobalMaterialLocalizations.delegates,
          home: PosRoot(c: c),
        ),
      );
}

class PosRoot extends StatefulWidget {
  const PosRoot({super.key, required this.c});

  final PosController c;

  @override
  State<PosRoot> createState() => _PosRootState();
}

class _PosRootState extends State<PosRoot> with WidgetsBindingObserver {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) widget.c.resumed();
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    if (!c.started) return const _Splash();
    final Widget screen;
    if (c.session == null) {
      screen = LoginScreen(c: c);
    } else if (c.bundle == null) {
      screen = NoTripScreen(c: c);
    } else {
      final receipt = c.view == PosView.receipt ? c.receiptSale : null;
      screen = switch (c.view) {
        PosView.cart => CartScreen(c: c),
        PosView.receipt => receipt == null ? SalesScreen(c: c) : ReceiptScreen(key: ValueKey(receipt.clientId), c: c, sale: receipt),
        PosView.sales => SalesScreen(c: c),
        PosView.breakage => BreakageScreen(c: c),
        PosView.declare => DeclareScreen(c: c),
        PosView.sell => SellScreen(c: c),
        PosView.day => DayScreen(c: c),
      };
    }
    return PopScope(
      canPop: c.session == null || c.bundle == null || c.view == PosView.day,
      onPopInvokedWithResult: (didPop, _) {
        if (!didPop) c.back();
      },
      child: screen,
    );
  }
}

class _Splash extends StatelessWidget {
  const _Splash();

  @override
  Widget build(BuildContext context) => Scaffold(
        body: const Center(child: BrandMark(size: 96)),
      );
}
