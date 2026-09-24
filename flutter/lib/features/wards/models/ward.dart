/// Matches backend's WardResponse (wardId, name, code) - see
/// WardController/PublicWardController's Javadoc for why boundaryGeojson
/// is deliberately never sent to this client.
class Ward {
  final int wardId;
  final String name;
  final String? code;

  Ward({required this.wardId, required this.name, this.code});

  factory Ward.fromJson(Map<String, dynamic> json) => Ward(
        wardId: json['wardId'] as int,
        name: json['name'] as String,
        code: json['code'] as String?,
      );
}
