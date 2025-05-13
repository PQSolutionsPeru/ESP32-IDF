import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:flutter_app_hdd_monitor/screens/home_screen.dart';
import 'package:flutter_app_hdd_monitor/screens/login_screen.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  
  // Reemplaza los valores con los de tu proyecto Firebase
  String apiKey = 'AIzaSyA9356Ag5rbtyev5l7U_iE8dc9Rz6zTgbU';
  String appId = '1:1002136032862:android:44e8c6751e458dca3dc6cf';
  String projectId = 'fir-hdd-monitor-d00de';
  String messagingSenderId = '1002136032862';

  await Firebase.initializeApp(
    options: FirebaseOptions(
      apiKey: apiKey,
      appId: appId,
      projectId: projectId,
      messagingSenderId: messagingSenderId,
    ),
  );

  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'HDD-Monitor',
      theme: ThemeData(
        primarySwatch: Colors.red,
        visualDensity: VisualDensity.adaptivePlatformDensity,
      ),
      home: LoginScreen(),
      routes: {
        '/home': (context) => HomeScreen(), // Reemplaza HomeScreen con tu pantalla principal
      },
    );
  }
}