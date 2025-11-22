// lib/services/permissions_service.dart
import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';

/// PermissionsService
/// Ensures runtime permissions for:
///  - Phone (READ_PHONE_STATE, READ_CALL_LOG indirect)
///  - Notifications
///  - Microphone (for call recording)
///
/// Usage:
///   await PermissionsService.requestPermissions(context: context);
class PermissionsService {
  /// Request all essential permissions.
  /// Returns true only if phone + microphone permissions are granted.
  static Future<bool> requestPermissions({BuildContext? context}) async {
    try {
      final statuses = await [
        Permission.phone,
        Permission.notification,
        Permission.microphone,
      ].request();

      final phoneGranted = await _isPhonePermissionGranted();
      final micGranted = await Permission.microphone.isGranted;

      if (phoneGranted && micGranted) return true;

      if (context != null) {
        final open = await _showRequestSettingsDialog(context);
        if (open) await openAppSettings();
      }

      return false;
    } catch (e) {
      debugPrint('PermissionsService.requestPermissions error: $e');
      return false;
    }
  }

  /// Check phone permission (READ_PHONE_STATE / READ_CALL_LOG indirectly).
  static Future<bool> _isPhonePermissionGranted() async {
    try {
      final status = await Permission.phone.status;
      return status.isGranted;
    } catch (e) {
      debugPrint('PermissionsService._isPhonePermissionGranted error: $e');
      return false;
    }
  }

  /// Dialog prompting user to open Settings.
  static Future<bool> _showRequestSettingsDialog(BuildContext ctx) async {
    return showDialog<bool>(
      context: ctx,
      builder: (c) => AlertDialog(
        title: const Text('Permissions Required'),
        content: const Text(
          'Phone + Microphone permissions are required for call detection and call recording. '
          'Please enable them in system settings.',
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(c).pop(false), child: const Text('Cancel')),
          TextButton(onPressed: () => Navigator.of(c).pop(true), child: const Text('Open Settings')),
        ],
      ),
    ).then((v) => v ?? false);
  }

  /// Ensure phone permission interactively.
  static Future<bool> ensurePhonePermission({BuildContext? context}) async {
    final phone = await Permission.phone.isGranted;
    if (phone) return true;

    final status = await Permission.phone.request();
    if (status.isGranted) return true;

    if (status.isPermanentlyDenied && context != null) {
      final open = await _showRequestSettingsDialog(context);
      if (open) await openAppSettings();
    }

    return false;
  }
}