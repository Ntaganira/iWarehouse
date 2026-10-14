// High Bytes POS - the server's mobile API (/api/v1, controller/api on the server): the phone's token, its id and the audit
// headers on every call (AUD-07). A call that cannot reach the server is "offline"; 401 means the token is no longer valid
// (revoked, the user disabled); any other refusal carries the server's message in the phone's language (MobileApiAdvice).

import 'dart:async';
import 'dart:convert';

import 'package:http/http.dart' as http;
import 'package:uuid/uuid.dart';

class ApiError implements Exception {
  const ApiError.offline()
      : offline = true,
        unauthorized = false,
        status = null,
        error = null,
        message = null;

  const ApiError.unauthorized()
      : offline = false,
        unauthorized = true,
        status = null,
        error = null,
        message = null;

  const ApiError.refused(int this.status, this.error, this.message)
      : offline = false,
        unauthorized = false;

  final bool offline;
  final bool unauthorized;
  final int? status;
  final String? error;
  final String? message;

  @override
  String toString() => offline ? 'offline' : unauthorized ? 'unauthorized' : 'HTTP $status $error: $message';
}

class Api {
  Api(this._client, {required this.userAgent, this.timeout = const Duration(seconds: 25)});

  final http.Client _client;
  final String userAgent;
  final Duration timeout;
  static const _uuid = Uuid();

  /// The server, as https://host[:port] (no trailing slash).
  String server = '';
  String lang = 'en';
  String deviceKey = '';
  String? token;

  Uri _uri(String path) => Uri.parse('$server/api/v1$path');

  Future<dynamic> request(String method, String path, [Object? body]) async {
    final req = http.Request(method, _uri(path))
      ..headers.addAll({
        'Accept': 'application/json',
        'Accept-Language': lang,
        'User-Agent': userAgent,
        'X-Device-Id': deviceKey,
        'X-Client-Time': DateTime.now().toUtc().toIso8601String(),
        'X-Request-Id': _uuid.v4(),
        if (token != null) 'Authorization': 'Bearer $token',
        if (body != null) 'Content-Type': 'application/json; charset=utf-8',
      });
    if (body != null) req.body = jsonEncode(body);
    final http.Response response;
    try {
      response = await http.Response.fromStream(await _client.send(req).timeout(timeout)).timeout(timeout);
    } catch (_) {
      // No network, no answer in time, the server unknown or refusing plain http: the phone cannot reach it
      throw const ApiError.offline();
    }
    if (response.statusCode == 401) throw const ApiError.unauthorized();
    Object? json;
    try {
      json = response.bodyBytes.isEmpty ? null : jsonDecode(utf8.decode(response.bodyBytes));
    } catch (_) {
      json = null;
    }
    if (response.statusCode < 200 || response.statusCode >= 300) {
      final m = json is Map ? json : const {};
      throw ApiError.refused(response.statusCode, m['error']?.toString(), m['message']?.toString());
    }
    return json;
  }

  /// The texts in a language (open, no token): null when the server cannot be reached.
  Future<Map<String, String>?> messages(String lang) async {
    if (server.isEmpty) return null;
    try {
      final response = await _client.get(Uri.parse('$server/api/v1/messages?lang=${Uri.encodeQueryComponent(lang)}'),
          headers: {'Accept': 'application/json', 'User-Agent': userAgent}).timeout(timeout);
      if (response.statusCode != 200) return null;
      final json = jsonDecode(utf8.decode(response.bodyBytes));
      return json is Map ? json.map((k, v) => MapEntry(k.toString(), v.toString())) : null;
    } catch (_) {
      return null;
    }
  }
}
