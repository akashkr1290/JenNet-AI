import 'package:flutter/material.dart';

import '../user_api.dart';

/// Who is signed in, for the home shell (pilot 2026-09-30): the person's name
/// and "Role · Department" (citizens: "Citizen · Ward"), e.g.
///   Ravi Kumar
///   DEPARTMENT HEAD · PUBLIC WORKS
/// Until the profile loads (or if it cannot be loaded) only [fallbackRole]
/// is shown, exactly like before.
class SignedInIdentity extends StatefulWidget {
  final String fallbackRole;

  /// An already-running GET /users/me to reuse; null starts one.
  final Future<UserProfile>? profile;

  const SignedInIdentity({super.key, required this.fallbackRole, this.profile});

  @override
  State<SignedInIdentity> createState() => _SignedInIdentityState();
}

class _SignedInIdentityState extends State<SignedInIdentity> {
  late final Future<UserProfile> _profile;

  @override
  void initState() {
    super.initState();
    _profile = widget.profile ?? UserApi.instance.me();
  }

  @override
  Widget build(BuildContext context) {
    final base = DefaultTextStyle.of(context).style;
    final subtle = (base.color ?? Colors.white).withValues(alpha: 0.78);
    final detailStyle = TextStyle(color: subtle, fontSize: 11.5, fontWeight: FontWeight.w700, letterSpacing: 1.1);
    return FutureBuilder<UserProfile>(
      future: _profile,
      builder: (context, snapshot) {
        final p = snapshot.data;
        if (p == null) {
          return Text(widget.fallbackRole.toUpperCase(), style: detailStyle);
        }
        return Semantics(
          label: 'Signed in as ${p.fullName}, ${p.roleAndUnit}',
          excludeSemantics: true,
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              Text(p.fullName,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: base.color, fontSize: 15, fontWeight: FontWeight.w800)),
              const SizedBox(height: 2),
              Text(p.roleAndUnit.toUpperCase(), maxLines: 2, overflow: TextOverflow.ellipsis, style: detailStyle),
            ],
          ),
        );
      },
    );
  }
}
