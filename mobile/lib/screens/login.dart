// High Bytes POS - signing in on the phone (SYNC-07), as the client's design draws it: the brand and the truck on the blue
// header; the company's server remembered (Change to type another), the driver's username and password, a name for the
// phone so the office knows which phone sold, and the language, on underlined fields.

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../art.dart';
import '../controller.dart';
import '../theme.dart';
import '../widgets.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.c});

  final PosController c;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  late String _server = widget.c.server;
  late bool _editServer = widget.c.server.trim().isEmpty;
  String _username = '';
  String _password = '';
  String _deviceName = '';
  bool _showPassword = false;

  void _submit() {
    FocusScope.of(context).unfocus();
    widget.c.signIn(serverTyped: _server, username: _username, password: _password, deviceName: _deviceName);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final e = c.errors;
    final p = Palette.of(context);
    final editServer = _editServer || e['server'] != null;
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Scaffold(
        backgroundColor: p.card,
        body: ListView(padding: EdgeInsets.zero, keyboardDismissBehavior: ScrollViewKeyboardDismissBehavior.onDrag, children: [
          _Hero(c: c),
          Padding(
            padding: EdgeInsets.fromLTRB(24, 4, 24, 24 + MediaQuery.paddingOf(context).bottom),
            child: Center(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxWidth: 520),
                child: AutofillGroup(
                  child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: gaps([
                    noticeOf(c),
                    if (editServer)
                      FieldBox(
                        label: c.t('mobile.login.server'),
                        hint: c.t('mobile.login.serverHint'),
                        error: e['server'],
                        child: BoundField(
                          value: _server,
                          keyboard: TextInputType.url,
                          underline: true,
                          icon: Icons.dns_outlined,
                          invalid: e['server'] != null,
                          hint: 'https://',
                          mono: true,
                          action: TextInputAction.next,
                          onChanged: (v) => setState(() => _server = v),
                        ),
                      )
                    else
                      _ServerCard(c: c, server: _server, onChange: () => setState(() => _editServer = true)),
                    FieldBox(
                      label: c.t('mobile.login.username'),
                      child: BoundField(
                          value: _username,
                          underline: true,
                          icon: Icons.person_outline,
                          hint: c.t('mobile.login.usernamePh'),
                          autofill: const [AutofillHints.username],
                          action: TextInputAction.next,
                          onChanged: (v) => setState(() => _username = v)),
                    ),
                    FieldBox(
                      label: c.t('mobile.login.password'),
                      child: BoundField(
                          value: _password,
                          underline: true,
                          icon: Icons.lock_outline,
                          obscure: !_showPassword,
                          hint: c.t('mobile.login.passwordPh'),
                          autofill: const [AutofillHints.password],
                          action: TextInputAction.next,
                          suffix: IconButton(
                            tooltip: c.t(_showPassword ? 'mobile.login.hidePassword' : 'mobile.login.showPassword'),
                            icon: Icon(_showPassword ? Icons.visibility_off_outlined : Icons.visibility_outlined, size: 20, color: p.muted),
                            onPressed: () => setState(() => _showPassword = !_showPassword),
                          ),
                          onChanged: (v) => setState(() => _password = v)),
                    ),
                    FieldBox(
                      label: c.t('mobile.login.deviceName'),
                      hint: c.t('mobile.login.deviceHint'),
                      child: BoundField(
                          value: _deviceName,
                          underline: true,
                          icon: Icons.smartphone,
                          maxLength: 60,
                          hint: c.t('mobile.login.deviceNamePh'),
                          caps: TextCapitalization.sentences,
                          action: TextInputAction.done,
                          onSubmitted: (_) => _submit(),
                          onChanged: (v) => setState(() => _deviceName = v)),
                    ),
                    FieldBox(label: c.t('mobile.language'), child: LanguagePicker(c: c)),
                    if (e['login'] != null) Text(e['login']!, style: TextStyle(fontSize: 13, color: p.danger, fontWeight: FontWeight.w600)),
                    Padding(
                      padding: const EdgeInsets.only(top: 8),
                      child: PrimaryButton(c.busy ? c.t('mobile.wait') : c.t('mobile.login.submit'), onPressed: c.busy ? null : _submit),
                    ),
                  ], 14)),
                ),
              ),
            ),
          ),
        ]),
      ),
    );
  }
}

/// The sign-in header: the brand, the truck with its badge, the title; a wave into the white form.
class _Hero extends StatelessWidget {
  const _Hero({required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    return HeaderGround(
      wave: p.card,
      waveHeight: 44,
      signIn: true,
      child: Padding(
        padding: EdgeInsets.fromLTRB(24, 20 + MediaQuery.paddingOf(context).top, 24, 60),
        child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: [
          Row(children: [
            const LogoTile(),
            const SizedBox(width: 10),
            Expanded(
              child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text(c.t('mobile.brand').toUpperCase(),
                    style: const TextStyle(fontSize: 16, height: 20 / 16, fontWeight: FontWeight.w800, letterSpacing: 0.16, color: Colors.white)),
                const SizedBox(height: 1),
                Text(c.t('mobile.brand.motto').toUpperCase(),
                    style: TextStyle(fontSize: 10, height: 14 / 10, fontWeight: FontWeight.w600, letterSpacing: 1, color: p.hdrSub)),
              ]),
            ),
          ]),
          const SizedBox(height: 18),
          Center(child: HeroTruck(badge: c.t('mobile.login.heroBadge'))),
          const SizedBox(height: 18),
          Text(c.t('mobile.login.title'),
              style: const TextStyle(fontSize: 26, height: 32 / 26, fontWeight: FontWeight.w800, letterSpacing: -0.52, color: Colors.white)),
          const SizedBox(height: 4),
          Text(c.t('mobile.login.subtitle'), style: TextStyle(fontSize: 14, height: 20 / 14, color: p.hdrSub)),
        ]),
      ),
    );
  }
}

/// The server the phone remembers, with Change to type another.
class _ServerCard extends StatelessWidget {
  const _ServerCard({required this.c, required this.server, required this.onChange});

  final PosController c;
  final String server;
  final VoidCallback onChange;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    // https is the rule, so it is left out; a test build's plain http stays visible
    final shown = server.trim().replaceFirst(RegExp(r'^https://'), '');
    return Container(
      padding: const EdgeInsets.fromLTRB(12, 10, 4, 10),
      decoration: BoxDecoration(color: p.bg, border: Border.all(color: p.border), borderRadius: BorderRadius.circular(10)),
      child: Row(children: [
        Container(
          width: 36,
          height: 36,
          decoration: BoxDecoration(color: p.tile, borderRadius: BorderRadius.circular(8)),
          child: Icon(Icons.dns_outlined, size: 20, color: p.primary),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
            Text(c.t('mobile.login.serverSaved'), style: TextStyle(fontSize: 12, height: 16 / 12, color: p.muted)),
            const SizedBox(height: 2),
            Text(shown, maxLines: 1, overflow: TextOverflow.ellipsis, style: monoStyle(size: 14, weight: FontWeight.w500, color: p.heading)),
          ]),
        ),
        TextButton(
          onPressed: onChange,
          style: TextButton.styleFrom(minimumSize: const Size(48, 44), textStyle: const TextStyle(fontFamily: sans, fontSize: 14, fontWeight: FontWeight.w600)),
          child: Text(c.t('mobile.login.serverChange')),
        ),
      ]),
    );
  }
}

class LanguagePicker extends StatelessWidget {
  const LanguagePicker({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) {
    final p = Palette.of(context);
    UnderlineInputBorder line(Color c, double w) => UnderlineInputBorder(borderSide: BorderSide(color: c, width: w));
    return DropdownButtonFormField<String>(
      initialValue: c.lang,
      isExpanded: true,
      icon: Icon(Icons.keyboard_arrow_down, color: p.muted),
      dropdownColor: p.card,
      style: TextStyle(fontFamily: sans, fontSize: 15, color: p.heading),
      decoration: InputDecoration(
        filled: false,
        prefixIcon: Icon(Icons.language, size: 20, color: p.muted),
        prefixIconConstraints: const BoxConstraints(minWidth: 30, minHeight: 44),
        contentPadding: const EdgeInsets.symmetric(vertical: 12),
        enabledBorder: line(p.ctrl, 1.5),
        focusedBorder: line(p.primary, 2),
        border: line(p.ctrl, 1.5),
      ),
      items: [for (final l in PosController.languages) DropdownMenuItem(value: l, child: Text(c.t('mobile.language.$l')))],
      onChanged: (l) {
        if (l != null) c.setLanguage(l);
      },
    );
  }
}
