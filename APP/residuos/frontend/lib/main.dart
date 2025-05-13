import 'package:flutter/material.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:go_router/go_router.dart';
import 'utils/firebase_options.dart';
import 'routes/app_routes.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Firebase.initializeApp(
    options: DefaultFirebaseOptions.currentPlatform,
  );
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  MyApp({super.key});

  final GoRouter _router = GoRouter(
    routes: appRoutes,
    initialLocation: '/login',
  );

  @override
  Widget build(BuildContext context) {
    return MaterialApp.router(
      title: 'HDD-Monitor',
      theme: ThemeData(
        primarySwatch: Colors.red,
      ),
      routerConfig: _router,
    );
  }
}