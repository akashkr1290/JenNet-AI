import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:jannet_ai/features/complaints/location/photo_exif.dart';

// 8x8 JPEGs with hand-built EXIF (generated with Pillow + a TIFF writer):
//  * little-endian (II): 28.6139 N, 77.2090 E, DateTimeOriginal 2026:09:20 10:15:30, OffsetTimeOriginal +05:30
//  * big-endian (MM):    33.8688 S, 151.2093 W, DateTimeOriginal 2026:09:20 10:15:30 (no offset)
//  * no EXIF at all (a screenshot / WhatsApp-style stripped photo)
final _gpsLittle = base64Decode(
    '/9j/4QDORXhpZgAASUkqAAgAAAACAGmHBAABAAAAXAAAACWIBAABAAAAJgAAAAAAAAAEAAEAAgACAAAATgAAAAIABQADAAAA'
    'egAAAAMAAgACAAAARQAAAAQABQADAAAAkgAAAAAAAAACAAOQAgAUAAAAqgAAABGQAgAHAAAAvgAAAAAAAAAcAAAAAQAAACQA'
    'AAABAAAAjBMAAGQAAABNAAAAAQAAAAwAAAABAAAAqAwAAGQAAAAyMDI2OjA5OjIwIDEwOjE1OjMwACswNTozMAAA/+AAEEpG'
    'SUYAAQEAAAEAAQAA/9sAQwAIBgYHBgUIBwcHCQkICgwUDQwLCwwZEhMPFB0aHx4dGhwcICQuJyAiLCMcHCg3KSwwMTQ0NB8n'
    'OT04MjwuMzQy/9sAQwEJCQkMCwwYDQ0YMiEcITIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy'
    'MjIyMjIy/8AAEQgACAAIAwEiAAIRAQMRAf/EAB8AAAEFAQEBAQEBAAAAAAAAAAABAgMEBQYHCAkKC//EALUQAAIBAwMCBAMF'
    'BQQEAAABfQECAwAEEQUSITFBBhNRYQcicRQygZGhCCNCscEVUtHwJDNicoIJChYXGBkaJSYnKCkqNDU2Nzg5OkNERUZHSElK'
    'U1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6g4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV'
    '1tfY2drh4uPk5ebn6Onq8fLz9PX29/j5+v/EAB8BAAMBAQEBAQEBAQEAAAAAAAABAgMEBQYHCAkKC//EALURAAIBAgQEAwQH'
    'BQQEAAECdwABAgMRBAUhMQYSQVEHYXETIjKBCBRCkaGxwQkjM1LwFWJy0QoWJDThJfEXGBkaJicoKSo1Njc4OTpDREVGR0hJ'
    'SlNUVVZXWFlaY2RlZmdoaWpzdHV2d3h5eoKDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT'
    '1NXW19jZ2uLj5OXm5+jp6vLz9PX29/j5+v/aAAwDAQACEQMRAD8AKKKK2Mj/2Q==');
final _gpsBigSouthWest = base64Decode(
    '/9j/4QC6RXhpZgAATU0AKgAAAAgAAodpAAQAAAABAAAAXIglAAQAAAABAAAAJgAAAAAABAABAAIAAAACUwAAAAACAAUAAAAD'
    'AAAAbgADAAIAAAACVwAAAAAEAAUAAAADAAAAhgAAAAAAAZADAAIAAAAUAAAAngAAAAAAAAAhAAAAAQAAADQAAAABAAADAAAA'
    'AGQAAACXAAAAAQAAAAwAAAABAAANFAAAAGQyMDI2OjA5OjIwIDEwOjE1OjMwAP/gABBKRklGAAEBAAABAAEAAP/bAEMACAYG'
    'BwYFCAcHBwkJCAoMFA0MCwsMGRITDxQdGh8eHRocHCAkLicgIiwjHBwoNyksMDE0NDQfJzk9ODI8LjM0Mv/bAEMBCQkJDAsM'
    'GA0NGDIhHCEyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMv/AABEIAAgACAMBIgAC'
    'EQEDEQH/xAAfAAABBQEBAQEBAQAAAAAAAAAAAQIDBAUGBwgJCgv/xAC1EAACAQMDAgQDBQUEBAAAAX0BAgMABBEFEiExQQYT'
    'UWEHInEUMoGRoQgjQrHBFVLR8CQzYnKCCQoWFxgZGiUmJygpKjQ1Njc4OTpDREVGR0hJSlNUVVZXWFlaY2RlZmdoaWpzdHV2'
    'd3h5eoOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4eLj5OXm5+jp6vHy8/T1'
    '9vf4+fr/xAAfAQADAQEBAQEBAQEBAAAAAAAAAQIDBAUGBwgJCgv/xAC1EQACAQIEBAMEBwUEBAABAncAAQIDEQQFITEGEkFR'
    'B2FxEyIygQgUQpGhscEJIzNS8BVictEKFiQ04SXxFxgZGiYnKCkqNTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1'
    'dnd4eXqCg4SFhoeIiYqSk5SVlpeYmZqio6Slpqeoqaqys7S1tre4ubrCw8TFxsfIycrS09TV1tfY2dri4+Tl5ufo6ery8/T1'
    '9vf4+fr/2gAMAwEAAhEDEQA/ACiiitjI/9k=');
final _plain = base64Decode(
    '/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcp'
    'LDAxNDQ0Hyc5PTgyPC4zNDL/2wBDAQkJCQwLDBgNDRgyIRwhMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIyMjIy'
    'MjIyMjIyMjIyMjIyMjL/wAARCAAIAAgDASIAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAA'
    'AgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6'
    'Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXG'
    'x8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREA'
    'AgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5'
    'OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPE'
    'xcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwAooorYyP/Z');

void main() {
  group('readPhotoExif (V33, port of the backend ExifGps)', () {
    test('reads GPS and the capture time with its offset (little-endian)', () {
      final exif = readPhotoExif(_gpsLittle);
      expect(exif.hasGps, isTrue);
      expect(exif.latitude, closeTo(28.6139, 0.000001));
      expect(exif.longitude, closeTo(77.2090, 0.000001));
      expect(exif.takenAt, DateTime.utc(2026, 9, 20, 4, 45, 30));
    });

    test('reads southern/western hemispheres (big-endian), time without offset is local', () {
      final exif = readPhotoExif(_gpsBigSouthWest);
      expect(exif.latitude, closeTo(-33.8688, 0.000001));
      expect(exif.longitude, closeTo(-151.2093, 0.000001));
      expect(exif.takenAt, DateTime(2026, 9, 20, 10, 15, 30));
    });

    test('scenario J: a photo without metadata gives nothing, never throws', () {
      final exif = readPhotoExif(_plain);
      expect(exif.hasGps, isFalse);
      expect(exif.takenAt, isNull);
    });

    test('garbage, truncated and non-JPEG input is ignored', () {
      expect(readPhotoExif(Uint8List(0)).hasGps, isFalse);
      expect(readPhotoExif(Uint8List.fromList([0xFF, 0xD8, 0xFF, 0xE1, 0x00])).hasGps, isFalse);
      expect(readPhotoExif(Uint8List.sublistView(_gpsLittle, 0, 60)).hasGps, isFalse);
      expect(readPhotoExif(Uint8List.fromList(List.filled(64, 7))).hasGps, isFalse);
    });

    test('EXIF date parsing rejects placeholders', () {
      expect(parseExifDateTime('0000:00:00 00:00:00'), isNull);
      expect(parseExifDateTime('not a date'), isNull);
      expect(parseExifDateTime('2026:10:02 09:15:00', '-04:00'), DateTime.utc(2026, 10, 2, 13, 15));
    });
  });
}
