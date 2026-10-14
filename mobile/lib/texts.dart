// High Bytes POS - every text the driver sees: the server's mobile.* keys (messages*.properties, served by
// /api/v1/messages), as the web app uses them. A copy ships in assets/texts (tool/texts.py), so the app reads well before
// it has ever reached the server; the server's texts replace it once fetched.

class Texts {
  Map<String, String> _texts = const {};

  void use(Map<String, String> texts) => _texts = Map.unmodifiable(texts);

  bool has(String key) => _texts.containsKey(key);

  /// The key's text with {0}, {1}... replaced by [args]; the key itself when there is no text.
  String t(String key, [List<Object?> args = const []]) {
    var text = _texts[key] ?? key;
    for (var i = 0; i < args.length; i++) {
      text = text.split('{$i}').join('${args[i] ?? ''}');
    }
    return text;
  }

  /// A text with a count: the key's ".one" form when the count is 1 (the texts are served raw, without choice formats).
  String tn(String key, int count, [List<Object?> args = const []]) {
    final one = count == 1 && _texts.containsKey('$key.one') ? '$key.one' : key;
    return t(one, args);
  }
}
