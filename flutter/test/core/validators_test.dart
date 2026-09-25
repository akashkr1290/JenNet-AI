import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/core/validators.dart';

/// Audit GAP-054 (SRS 17.1 full_name "Alpha + spaces, 2-100 chars"). Mirrors
/// backend PersonNames. NOT EXECUTED in the workspace that wrote it (no Flutter
/// SDK there) - run with `flutter test`.
void main() {
  test('accepts Latin and Devanagari names with single spaces', () {
    expect(isValidPersonName('Ravi Kumar'), isTrue);
    expect(isValidPersonName('राम कुमार'), isTrue);
    expect(isValidPersonName('Al'), isTrue);
  });

  test('rejects too short, too long, digits, punctuation and double spaces', () {
    expect(isValidPersonName('A'), isFalse);
    expect(isValidPersonName('a' * 101), isFalse);
    expect(isValidPersonName('Ravi 2'), isFalse);
    expect(isValidPersonName('A. Kumar'), isFalse);
    expect(isValidPersonName('Ravi  Kumar'), isFalse);
  });

  test('form validator trims and reports the rule', () {
    expect(validatePersonName('  Ravi Kumar  '), isNull);
    expect(validatePersonName(''), 'Full name is required');
    expect(validatePersonName('R2D2'), personNameRuleMessage);
  });
}
