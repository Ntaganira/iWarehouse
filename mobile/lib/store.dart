// High Bytes POS - what the phone keeps between launches: settings and the trip (kv), the sales and the breakage reports
// (SYNC-02). SQLite in the app's own storage, which the system does not clear the way a browser may clear a web app's:
// a sale waiting to sync is money the driver holds.

import 'dart:convert';

import 'package:path/path.dart' as p;
import 'package:sqflite/sqflite.dart';

abstract class Store {
  Future<Object?> get(String key);

  Future<void> set(String key, Object? value);

  Future<void> remove(String key);

  /// Every row of 'sales' or 'breakages', oldest first.
  Future<List<Map<String, dynamic>>> all(String table);

  /// Saves a row by its clientId.
  Future<void> put(String table, Map<String, dynamic> row);

  Future<void> clear(String table);
}

class SqliteStore implements Store {
  SqliteStore._(this._db);

  final Database _db;
  static const tables = ['sales', 'breakages'];

  static Future<SqliteStore> open() async {
    final db = await openDatabase(p.join(await getDatabasesPath(), 'highbytes-pos.db'), version: 1, onCreate: (db, version) async {
      await db.execute('CREATE TABLE kv (key TEXT PRIMARY KEY, value TEXT NOT NULL)');
      for (final t in tables) {
        await db.execute('CREATE TABLE $t (client_id TEXT PRIMARY KEY, seq INTEGER NOT NULL, body TEXT NOT NULL)');
      }
    });
    return SqliteStore._(db);
  }

  @override
  Future<Object?> get(String key) async {
    final rows = await _db.query('kv', columns: ['value'], where: 'key = ?', whereArgs: [key]);
    return rows.isEmpty ? null : jsonDecode(rows.first['value'] as String);
  }

  @override
  Future<void> set(String key, Object? value) async {
    await _db.insert('kv', {'key': key, 'value': jsonEncode(value)}, conflictAlgorithm: ConflictAlgorithm.replace);
  }

  @override
  Future<void> remove(String key) async {
    await _db.delete('kv', where: 'key = ?', whereArgs: [key]);
  }

  @override
  Future<List<Map<String, dynamic>>> all(String table) async {
    final rows = await _db.query(_table(table), columns: ['body'], orderBy: 'seq');
    return rows.map((r) => Map<String, dynamic>.from(jsonDecode(r['body'] as String) as Map)).toList();
  }

  @override
  Future<void> put(String table, Map<String, dynamic> row) async {
    await _db.insert(_table(table), {'client_id': row['clientId'], 'seq': row['seq'], 'body': jsonEncode(row)},
        conflictAlgorithm: ConflictAlgorithm.replace);
  }

  @override
  Future<void> clear(String table) async {
    await _db.delete(_table(table));
  }

  static String _table(String table) => tables.contains(table) ? table : throw ArgumentError(table);
}

/// The same in memory, for the tests.
class MemoryStore implements Store {
  final Map<String, String> _kv = {};
  final Map<String, Map<String, String>> _rows = {'sales': {}, 'breakages': {}};

  @override
  Future<Object?> get(String key) async => _kv.containsKey(key) ? jsonDecode(_kv[key]!) : null;

  @override
  Future<void> set(String key, Object? value) async {
    _kv[key] = jsonEncode(value);
  }

  @override
  Future<void> remove(String key) async {
    _kv.remove(key);
  }

  @override
  Future<List<Map<String, dynamic>>> all(String table) async {
    final rows = _rows[table]!.values.map((b) => Map<String, dynamic>.from(jsonDecode(b) as Map)).toList();
    rows.sort((a, b) => (a['seq'] as num).compareTo(b['seq'] as num));
    return rows;
  }

  @override
  Future<void> put(String table, Map<String, dynamic> row) async {
    _rows[table]![row['clientId'] as String] = jsonEncode(row);
  }

  @override
  Future<void> clear(String table) async {
    _rows[table]!.clear();
  }
}
