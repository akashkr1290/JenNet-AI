/// Matches backend's WardResponse - see WardController/PublicWardController's
/// Javadoc for why boundaryGeojson is deliberately never sent to this client.
/// V33: [centreLatitude]/[centreLongitude] - the centre of the ward boundary
/// (null when the ward has none), used to jump the incident-location map to a
/// ward the citizen searches for.
class Ward {
  final int wardId;
  final String name;
  final String? code;
  final double? centreLatitude;
  final double? centreLongitude;

  Ward({required this.wardId, required this.name, this.code, this.centreLatitude, this.centreLongitude});

  bool get hasCentre => centreLatitude != null && centreLongitude != null;

  factory Ward.fromJson(Map<String, dynamic> json) => Ward(
        wardId: json['wardId'] as int,
        name: json['name'] as String,
        code: json['code'] as String?,
        centreLatitude: (json['centreLatitude'] as num?)?.toDouble(),
        centreLongitude: (json['centreLongitude'] as num?)?.toDouble(),
      );
}
