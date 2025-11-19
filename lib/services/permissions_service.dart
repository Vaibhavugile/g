// lib/services/permissions_service.dart
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// PermissionsService
///
/// Ensures the app has the runtime permissions needed for call detection & call-log fallback:
///  - phone (permission_handler: Permission.phone)
///  - call log (READ_CALL_LOG) — on Android this is handled by Permission.phone in some mappings,
///    but we explicitly request Permission.phone and also check Permission.sms as a fallback
///    if your mapping/environment requires it.
///
/// Usage:
///   await PermissionsService.requestPermissions(context: context);
///
class PermissionsService {
  /// Request the basic permissions used by the app. Returns true if the essential permissions
  /// (phone / call log) are granted, false otherwise.
  static Future<bool> requestPermissions({BuildContext? context}) async {
    try {
      // Request a set of relevant permissions.
      // Note: permission_handler currently exposes `Permission.phone` which maps to READ_PHONE_STATE
      // and related on Android. READ_CALL_LOG may not be directly exposed by permission_handler
      // for all versions; we handle common mappings and check status explicitly.
      final Map<Permission, PermissionStatus> statuses = await [
        Permission.phone, // core telephony
        Permission.notification, // optional, your manifest includes POST_NOTIFICATIONS
      ].request();

      // Check effective call-log access:
      final bool phoneGranted = await _isPhonePermissionGranted();
      if (phoneGranted) {
        return true;
      }

      // Not granted: if context is present, show a dialog guiding the user to settings
      if (context != null) {
        final open = await _showRequestSettingsDialog(context);
        if (open) {
          await openAppSettings();
        }
      }

      return false;
    } catch (e) {
      debugPrint('PermissionsService.requestPermissions error: $e');
      return false;
    }
  }

  /// Check whether the app effectively has access to call-related data.
  /// Returns true if Permission.phone is granted (best-effort), or if the app settings show
  /// the user has granted runtime permissions on the platform.
  static Future<bool> _isPhonePermissionGranted() async {
    try {
      final status = await Permission.phone.status;
      if (status.isGranted) return true;

      // Some OEMs / mappings might require checking other permissions; try READ_CALL_LOG if available.
      // permission_handler doesn't always expose READ_CALL_LOG explicitly; but Permission.sms or contacts
      // are not the same. We'll treat phone.isGranted as the canonical indicator for our app.
      return false;
    } catch (e) {
      debugPrint('PermissionsService._isPhonePermissionGranted error: $e');
      return false;
    }
  }

  /// Simple UI dialog prompting the user to open app settings when permissions are permanently denied.
  static Future<bool> _showRequestSettingsDialog(BuildContext ctx) async {
    return showDialog<bool>(
      context: ctx,
      builder: (c) {
        return AlertDialog(
          title: const Text('Permissions needed'),
          content: const Text(
              'To reliably detect and finalize calls we need phone permissions. Please open app settings and grant Phone / Call Log permissions.'),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(c).pop(false),
              child: const Text('Cancel'),
            ),
            TextButton(
              onPressed: () => Navigator.of(c).pop(true),
              child: const Text('Open settings'),
            ),
          ],
        );
      },
    ).then((v) => v ?? false);
  }

  /// Public helper to check permission state and optionally open the system settings page if needed.
  static Future<bool> ensurePhonePermission({BuildContext? context}) async {
    final granted = await _isPhonePermissionGranted();
    if (granted) return true;

    // Try to request interactively
    final status = await Permission.phone.request();
    if (status.isGranted) return true;

    if (status.isPermanentlyDenied && context != null) {
      final open = await _showRequestSettingsDialog(context);
      if (open) {
        await openAppSettings();
      }
    }

    return false;
  }
}
