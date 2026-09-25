import 'package:flutter/material.dart';

import '../../../core/theme/jan_tokens.dart';
import '../../../core/widgets/jan_surfaces.dart';

/// Gap-backlog Patch 35/48 (Sep 2026 audit): "The application handles
/// citizen identity, location, complaint photos, contact information -
/// therefore add Privacy Policy / Terms of Use / Location Usage
/// Explanation / Photo Usage Explanation / Data Retention Policy."
///
/// This is static, citizen-facing copy describing what THIS app
/// (JanNet AI) actually does with data, based only on what this
/// codebase's own real behavior is (GPS capture on submission, photo
/// upload + EXIF-stripped storage - see ImageValidationService -,
/// citizen identity via OTP-verified registration) - not generic legal
/// boilerplate copied from elsewhere. A real deployment's legal/
/// compliance team should review this before publishing it as a binding
/// policy; it is written to be accurate to the code, not to be a
/// substitute for that review.
class PrivacyPolicyScreen extends StatelessWidget {
  const PrivacyPolicyScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return JanPage(
      title: 'Privacy & Data Use',
      maxWidth: 760,
      body: ListView(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.md, JanSpace.md, JanSpace.xxl),
        children: const [
          _Section(
            icon: Icons.inventory_2_outlined,
            title: 'What we collect',
            body: 'When you submit a complaint, JanNet AI collects: a photo of '
                'the issue, your device\'s GPS location (or, if you enter it '
                'manually, your approximate location and ward), an optional '
                'description you write, and your account details (name, mobile '
                'number, email) from registration.',
          ),
          _Section(
            icon: Icons.location_on_outlined,
            title: 'Location usage',
            body: 'Your location is used only to route your complaint to the '
                'correct ward and department, to detect possible duplicate '
                'reports of the same issue nearby, and to show your complaint '
                'on department-facing maps. If you deny location permission, '
                'you can still submit a complaint by entering your location '
                'manually.',
          ),
          _Section(
            icon: Icons.photo_camera_outlined,
            title: 'Photo usage',
            body: 'Your photo is analyzed automatically to help classify the '
                'type of issue (e.g. pothole, garbage, streetlight) and is '
                'shown to the department staff handling your complaint. '
                'Photos are stored with their embedded metadata (such as GPS '
                'EXIF tags) removed before storage, and are only accessible '
                'through short-lived, secured links - never a public URL.',
          ),
          _Section(
            icon: Icons.visibility_outlined,
            title: 'Who can see your complaint',
            body: 'Your complaint is visible to you, the department staff and '
                'officers assigned to handle it, and administrators. Public '
                'views (such as the community heatmap) show only aggregated '
                'issue counts by area and category - never your name, contact '
                'details, or which specific complaint is yours.',
          ),
          _Section(
            icon: Icons.history_rounded,
            title: 'Data retention',
            body: 'Your complaint record, including its photos and status '
                'history, is retained for as long as your account exists and '
                'for a reasonable period afterward for civic record-keeping '
                'and audit purposes. Contact your local administrator if you '
                'wish to request deletion of your account and associated data.',
          ),
          _Section(
            icon: Icons.help_outline_rounded,
            title: 'Questions',
            body: 'For questions about how your data is used, contact your '
                'local municipal administrator through the department '
                'directory in this app.',
          ),
        ],
      ),
    );
  }
}

class TermsOfUseScreen extends StatelessWidget {
  const TermsOfUseScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return JanPage(
      title: 'Terms of Use',
      maxWidth: 760,
      body: ListView(
        padding: const EdgeInsets.fromLTRB(JanSpace.md, JanSpace.md, JanSpace.md, JanSpace.xxl),
        children: const [
          _Section(
            icon: Icons.flag_outlined,
            title: 'Purpose',
            body: 'JanNet AI is a civic complaint reporting platform. It is '
                'intended for reporting genuine civic issues (such as '
                'potholes, garbage overflow, broken street lights, water '
                'leakage, open manholes, and illegal construction) to the '
                'relevant municipal department.',
          ),
          _Section(
            icon: Icons.fact_check_outlined,
            title: 'Accurate reporting',
            body: 'Please submit only genuine complaints with an accurate '
                'photo and location. Repeated false or abusive submissions '
                'may result in account restrictions.',
          ),
          _Section(
            icon: Icons.auto_awesome_outlined,
            title: 'Automated classification',
            body: 'Complaints are analyzed automatically to assist '
                'classification and routing. Automated results may be '
                'reviewed and corrected by a human verifier at any point in '
                'the process.',
          ),
          _Section(
            icon: Icons.shield_outlined,
            title: 'Account responsibility',
            body: 'You are responsible for keeping your account credentials '
                'confidential and for the accuracy of the information you '
                'submit.',
          ),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  final IconData icon;
  final String title;
  final String body;
  const _Section({required this.icon, required this.title, required this.body});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(bottom: JanSpace.sm),
      child: JanCard(
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              width: 40,
              height: 40,
              decoration: const BoxDecoration(color: JanColors.infoLight, borderRadius: JanRadius.smAll),
              child: Icon(icon, color: JanColors.primary, size: 22),
            ),
            const SizedBox(width: JanSpace.sm),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Semantics(
                    header: true,
                    child: Text(
                      title,
                      style: Theme.of(context).textTheme.titleMedium?.copyWith(color: JanColors.navy, fontWeight: FontWeight.w800),
                    ),
                  ),
                  const SizedBox(height: 6),
                  Text(body, style: Theme.of(context).textTheme.bodyMedium?.copyWith(color: JanColors.slate, height: 1.5)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}
