import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/api/api_time.dart';

/// Audit GAP-026: server timestamps are UTC, with or without the `Z`.
void main() {
  group('parseApiTimestamp', () {
    test('a zone-less timestamp is read as UTC and returned in local time', () {
      final parsed = parseApiTimestamp('2026-09-25T10:00:00');
      expect(parsed, DateTime.utc(2026, 9, 25, 10).toLocal());
      expect(parsed!.isUtc, isFalse);
    });

    test('a Z-suffixed timestamp is the same instant', () {
      expect(parseApiTimestamp('2026-09-25T10:00:00Z'), parseApiTimestamp('2026-09-25T10:00:00'));
    });

    test('an explicit offset is honoured', () {
      expect(parseApiTimestamp('2026-09-25T15:30:00+05:30')!.toUtc(), DateTime.utc(2026, 9, 25, 10));
    });

    test('fractional seconds are kept', () {
      expect(parseApiTimestamp('2026-09-25T10:00:00.123')!.toUtc(), DateTime.utc(2026, 9, 25, 10, 0, 0, 123));
    });

    test('null, empty, non-string and garbage return null', () {
      expect(parseApiTimestamp(null), isNull);
      expect(parseApiTimestamp(''), isNull);
      expect(parseApiTimestamp(42), isNull);
      expect(parseApiTimestamp('not-a-date'), isNull);
    });
  });
}
