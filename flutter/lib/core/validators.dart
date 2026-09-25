/// Audit GAP-054: client-side mirror of the backend's SRS 17.1 full-name
/// rule ("Alpha + spaces, 2-100 chars"; see backend PersonNames.java).
/// Letters of any script (including Devanagari vowel signs, which are
/// Unicode marks) separated by single spaces. The server re-validates.
library;

final RegExp _personNamePattern =
    RegExp(r'^\p{L}[\p{L}\p{M}]*(?: \p{L}[\p{L}\p{M}]*)*$', unicode: true);

const String personNameRuleMessage =
    'Use 2-100 letters, with single spaces between words (no digits or symbols)';

/// True when [name] (already trimmed by the caller) satisfies the rule.
bool isValidPersonName(String name) {
  final length = name.runes.length;
  return length >= 2 && length <= 100 && _personNamePattern.hasMatch(name);
}

/// Form-field validator: null when valid, otherwise the message to show.
String? validatePersonName(String? value) {
  final v = value?.trim() ?? '';
  if (v.isEmpty) return 'Full name is required';
  return isValidPersonName(v) ? null : personNameRuleMessage;
}
