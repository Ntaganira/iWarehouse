// High Bytes POS - signing in on the phone (SYNC-07): the server's address (kept for next time), the driver's username and
// password, a name for the phone so the office knows which phone sold, and the language.

import 'package:flutter/material.dart';

import '../controller.dart';
import '../widgets.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key, required this.c});

  final PosController c;

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  late String _server = widget.c.server;
  String _username = '';
  String _password = '';
  String _deviceName = '';

  void _submit() {
    FocusScope.of(context).unfocus();
    widget.c.signIn(serverTyped: _server, username: _username, password: _password, deviceName: _deviceName);
  }

  @override
  Widget build(BuildContext context) {
    final c = widget.c;
    final e = c.errors;
    return PosPage(c: c, title: c.t('mobile.app'), sub: c.t('mobile.login.subtitle'), children: [
      noticeOf(c),
      AutofillGroup(
        child: CardBox(children: [
          Text(c.t('mobile.login.title'), style: const TextStyle(fontSize: 18, fontWeight: FontWeight.w700)),
          const SizedBox(height: 14),
          FieldBox(
            label: c.t('mobile.login.server'),
            hint: c.t('mobile.login.serverHint'),
            error: e['server'],
            child: BoundField(
              value: _server,
              keyboard: TextInputType.url,
              invalid: e['server'] != null,
              hint: 'https://',
              action: TextInputAction.next,
              onChanged: (v) => setState(() => _server = v),
            ),
          ),
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.login.username'),
            child: BoundField(
                value: _username,
                autofill: const [AutofillHints.username],
                action: TextInputAction.next,
                onChanged: (v) => setState(() => _username = v)),
          ),
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.login.password'),
            child: BoundField(
                value: _password,
                obscure: true,
                autofill: const [AutofillHints.password],
                action: TextInputAction.next,
                onChanged: (v) => setState(() => _password = v)),
          ),
          const SizedBox(height: 12),
          FieldBox(
            label: c.t('mobile.login.deviceName'),
            hint: c.t('mobile.login.deviceHint'),
            child: BoundField(
                value: _deviceName,
                maxLength: 60,
                hint: c.t('mobile.login.deviceNamePh'),
                caps: TextCapitalization.sentences,
                action: TextInputAction.done,
                onSubmitted: (_) => _submit(),
                onChanged: (v) => setState(() => _deviceName = v)),
          ),
          const SizedBox(height: 12),
          FieldBox(label: c.t('mobile.language'), child: LanguagePicker(c: c)),
          if (e['login'] != null) ...[
            const SizedBox(height: 12),
            Text(e['login']!, style: const TextStyle(fontSize: 13, color: dangerColor, fontWeight: FontWeight.w600)),
          ],
          const SizedBox(height: 16),
          PrimaryButton(c.busy ? c.t('mobile.wait') : c.t('mobile.login.submit'), onPressed: c.busy ? null : _submit),
        ]),
      ),
    ]);
  }
}

class LanguagePicker extends StatelessWidget {
  const LanguagePicker({super.key, required this.c});

  final PosController c;

  @override
  Widget build(BuildContext context) => DropdownButtonFormField<String>(
        initialValue: c.lang,
        isExpanded: true,
        items: [for (final l in PosController.languages) DropdownMenuItem(value: l, child: Text(c.t('mobile.language.$l')))],
        onChanged: (l) {
          if (l != null) c.setLanguage(l);
        },
      );
}
