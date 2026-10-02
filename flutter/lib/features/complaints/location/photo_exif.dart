import 'dart:typed_data';

/// What a photo's own EXIF metadata says about where and when it was taken.
class PhotoExif {
  /// GPS position stored by the camera, or null.
  final double? latitude;
  final double? longitude;

  /// DateTimeOriginal (or DateTime) - when the photo was taken, or null.
  /// With an EXIF OffsetTimeOriginal the value is exact (UTC); without one
  /// it is read as this device's local time, which is what phone cameras write.
  final DateTime? takenAt;

  const PhotoExif({this.latitude, this.longitude, this.takenAt});

  bool get hasGps => latitude != null && longitude != null;

  static const empty = PhotoExif();
}

/// Reads GPS and the capture time from a JPEG's EXIF block - a pure-Dart port
/// of the backend's `ExifGps` (same APP1/TIFF walk), so it works the same on
/// Android and Flutter Web without a new package.
///
/// It must be given the ORIGINAL file: resizing in the browser re-encodes the
/// image and drops all metadata. EXIF is a bonus, never guaranteed - Android's
/// photo picker, browsers, WhatsApp, screenshots and editors often remove it.
///
/// Never throws: malformed or missing metadata gives [PhotoExif.empty].
PhotoExif readPhotoExif(Uint8List bytes) {
  try {
    final tiff = _locateExifTiff(bytes);
    if (tiff == null) return PhotoExif.empty;
    return _readTiff(bytes, tiff.$1, tiff.$2);
  } catch (_) {
    return PhotoExif.empty;
  }
}

const _gpsIfdPointer = 0x8825;
const _exifIfdPointer = 0x8769;
const _tagDateTime = 0x0132;
const _tagDateTimeOriginal = 0x9003;
const _tagOffsetTimeOriginal = 0x9011;
const _typeAscii = 2;
const _typeLong = 4;
const _typeRational = 5;

(int, int)? _locateExifTiff(Uint8List b) {
  if (b.length < 4 || b[0] != 0xFF || b[1] != 0xD8) return null;
  var i = 2;
  while (i + 4 <= b.length) {
    if (b[i] != 0xFF) return null;
    final marker = b[i + 1];
    if (marker == 0xD9 || marker == 0xDA) return null;
    final length = (b[i + 2] << 8) | b[i + 3];
    if (length < 2 || i + 2 + length > b.length) return null;
    if (marker == 0xE1 &&
        length >= 8 &&
        b[i + 4] == 0x45 && // E
        b[i + 5] == 0x78 && // x
        b[i + 6] == 0x69 && // i
        b[i + 7] == 0x66 && // f
        b[i + 8] == 0 &&
        b[i + 9] == 0) {
      return (i + 10, i + 2 + length);
    }
    i += 2 + length;
  }
  return null;
}

class _Tiff {
  final Uint8List b;
  final int start;
  final int end;
  final bool little;
  _Tiff(this.b, this.start, this.end, this.little);

  int u16(int at) {
    if (at < 0 || at + 2 > end) throw RangeError('u16');
    return little ? b[at] | (b[at + 1] << 8) : (b[at] << 8) | b[at + 1];
  }

  int u32(int at) {
    if (at < 0 || at + 4 > end) throw RangeError('u32');
    return little
        ? b[at] | (b[at + 1] << 8) | (b[at + 2] << 16) | (b[at + 3] << 24)
        : (b[at] << 24) | (b[at + 1] << 16) | (b[at + 2] << 8) | b[at + 3];
  }

  /// Absolute offset of an IFD (its entry count), or -1.
  int directory(int relative) {
    if (relative < 8 || start + relative + 2 > end) return -1;
    return start + relative;
  }

  /// Absolute offset of the 12-byte entry for [tag], or -1.
  int entry(int dir, int tag) {
    if (dir < 0) return -1;
    final count = u16(dir);
    for (var e = 0; e < count; e++) {
      final at = dir + 2 + e * 12;
      if (at + 12 > end) return -1;
      if (u16(at) == tag) return at;
    }
    return -1;
  }

  int subDirectory(int dir, int pointerTag) {
    final at = entry(dir, pointerTag);
    if (at < 0 || u16(at + 2) != _typeLong) return -1;
    return directory(u32(at + 8));
  }

  String? ascii(int at) {
    if (at < 0 || u16(at + 2) != _typeAscii) return null;
    final count = u32(at + 4);
    if (count <= 0 || count > 64) return null;
    final from = count <= 4 ? at + 8 : start + u32(at + 8);
    if (from < start || from + count > end) return null;
    final chars = <int>[];
    for (var i = 0; i < count; i++) {
      final c = b[from + i];
      if (c == 0) break;
      chars.add(c);
    }
    return String.fromCharCodes(chars);
  }

  double? rational(int at) {
    final den = u32(at + 4);
    if (den == 0) return null;
    return u32(at) / den;
  }

  /// Three RATIONALs (deg, min, sec) -> decimal degrees.
  double? degrees(int at) {
    if (at < 0 || u16(at + 2) != _typeRational || u32(at + 4) != 3) return null;
    final offset = u32(at + 8);
    final from = start + offset;
    if (offset < 8 || from + 24 > end) return null;
    final deg = rational(from);
    final min = rational(from + 8);
    final sec = rational(from + 16);
    if (deg == null || min == null || sec == null || min >= 60 || sec >= 60) return null;
    return deg + min / 60 + sec / 3600;
  }
}

PhotoExif _readTiff(Uint8List b, int start, int end) {
  if (start + 8 > end) return PhotoExif.empty;
  final bool little;
  if (b[start] == 0x49 && b[start + 1] == 0x49) {
    little = true;
  } else if (b[start] == 0x4D && b[start + 1] == 0x4D) {
    little = false;
  } else {
    return PhotoExif.empty;
  }
  final t = _Tiff(b, start, end, little);
  final ifd0 = t.directory(t.u32(start + 4));
  if (ifd0 < 0) return PhotoExif.empty;

  double? lat;
  double? lng;
  try {
    final gps = t.subDirectory(ifd0, _gpsIfdPointer);
    if (gps >= 0) {
      final latRef = t.ascii(t.entry(gps, 1))?.toUpperCase();
      var la = t.degrees(t.entry(gps, 2));
      final lngRef = t.ascii(t.entry(gps, 3))?.toUpperCase();
      var lo = t.degrees(t.entry(gps, 4));
      if (la != null && lo != null && (latRef == 'N' || latRef == 'S') && (lngRef == 'E' || lngRef == 'W')) {
        if (latRef == 'S') la = -la;
        if (lngRef == 'W') lo = -lo;
        // 0,0 is a common "no fix" placeholder.
        if (la.abs() <= 90 && lo.abs() <= 180 && !(la == 0 && lo == 0)) {
          lat = double.parse(la.toStringAsFixed(6));
          lng = double.parse(lo.toStringAsFixed(6));
        }
      }
    }
  } catch (_) {
    // GPS unreadable - the time may still be fine.
  }

  DateTime? takenAt;
  try {
    final exif = t.subDirectory(ifd0, _exifIfdPointer);
    final original = exif >= 0 ? t.ascii(t.entry(exif, _tagDateTimeOriginal)) : null;
    final offset = exif >= 0 ? t.ascii(t.entry(exif, _tagOffsetTimeOriginal)) : null;
    takenAt = parseExifDateTime(original ?? t.ascii(t.entry(ifd0, _tagDateTime)), offset);
  } catch (_) {
    takenAt = null;
  }
  return PhotoExif(latitude: lat, longitude: lng, takenAt: takenAt);
}

/// `YYYY:MM:DD HH:MM:SS` (+ optional `+05:30` offset) -> DateTime, or null.
DateTime? parseExifDateTime(String? value, [String? offset]) {
  if (value == null) return null;
  final m = RegExp(r'^(\d{4}):(\d{2}):(\d{2})[ T](\d{2}):(\d{2}):(\d{2})').firstMatch(value.trim());
  if (m == null) return null;
  final p = [for (var i = 1; i <= 6; i++) int.parse(m.group(i)!)];
  if (p[0] < 1990 || p[1] < 1 || p[1] > 12 || p[2] < 1 || p[2] > 31 || p[3] > 23 || p[4] > 59 || p[5] > 59) {
    return null; // "0000:00:00 00:00:00" and other placeholders
  }
  final o = offset == null ? null : RegExp(r'^([+-])(\d{2}):(\d{2})$').firstMatch(offset.trim());
  if (o != null) {
    final sign = o.group(1) == '-' ? -1 : 1;
    final minutes = sign * (int.parse(o.group(2)!) * 60 + int.parse(o.group(3)!));
    return DateTime.utc(p[0], p[1], p[2], p[3], p[4], p[5]).subtract(Duration(minutes: minutes));
  }
  return DateTime(p[0], p[1], p[2], p[3], p[4], p[5]);
}
