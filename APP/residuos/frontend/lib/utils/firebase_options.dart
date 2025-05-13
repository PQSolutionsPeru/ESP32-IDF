 // File: firebase_options.dart

import 'package:firebase_core/firebase_core.dart' show FirebaseOptions;
import 'package:flutter/foundation.dart'
    show defaultTargetPlatform, kIsWeb, TargetPlatform;

/// Default [FirebaseOptions] for use with your Firebase apps.
///
/// Example:
/// ```dart
/// import 'firebase_options.dart';
/// // ...
/// await Firebase.initializeApp(
///   options: DefaultFirebaseOptions.currentPlatform,
/// );
/// ```
class DefaultFirebaseOptions {
  static FirebaseOptions get currentPlatform {
    if (kIsWeb) {
      return web;
    }
    switch (defaultTargetPlatform) {
      case TargetPlatform.android:
        return android;
      case TargetPlatform.iOS:
        return ios;
      case TargetPlatform.macOS:
        return macos;
      case TargetPlatform.windows:
        throw UnsupportedError(
          'DefaultFirebaseOptions have not been configured for windows - '
          'you can reconfigure this by running the Flutter Fire CLI again.',
        );
      case TargetPlatform.linux:
        throw UnsupportedError(
          'DefaultFirebaseOptions have not been configured for linux - '
          'you can reconfigure this by running the Flutter Fire CLI again.',
        );
      default:
        throw UnsupportedError(
          'DefaultFirebaseOptions are not supported for this platform.',
        );
    }
  }

  static const FirebaseOptions web = FirebaseOptions(
    apiKey: 'AIzaSyA9356Ag5rbtyev5l7U_iE8dc9Rz6zTgbU',
    appId: '1:1002136032862:web:9e76801b2c9f14543dc6cf',
    messagingSenderId: '1002136032862',
    projectId: 'fir-hdd-monitor-d00de',
    authDomain: 'fir-hdd-monitor-d00de.firebaseapp.com',
    storageBucket: 'fir-hdd-monitor-d00de.appspot.com',
    measurementId: 'G-KKWYMFGP9Z',
  );

  static const FirebaseOptions android = FirebaseOptions(
    apiKey: 'AIzaSyA9356Ag5rbtyev5l7U_iE8dc9Rz6zTgbU',
    appId: '1:1002136032862:web:9e76801b2c9f14543dc6cf',
    messagingSenderId: '1002136032862',
    projectId: 'fir-hdd-monitor-d00de',
  );

  static const FirebaseOptions ios = FirebaseOptions(
    apiKey: 'AIzaSyA9356Ag5rbtyev5l7U_iE8dc9Rz6zTgbU',
    appId: '1:1002136032862:web:9e76801b2c9f14543dc6cf',
    messagingSenderId: '1002136032862',
    projectId: 'fir-hdd-monitor-d00de',
  );

  static const FirebaseOptions macos = FirebaseOptions(
    apiKey: 'AIzaSyA9356Ag5rbtyev5l7U_iE8dc9Rz6zTgbU',
    appId: '1:1002136032862:web:9e76801b2c9f14543dc6cf',
    messagingSenderId: '1002136032862',
    projectId: 'fir-hdd-monitor-d00de',
  );
}