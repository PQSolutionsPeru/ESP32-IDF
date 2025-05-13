import 'package:flutter/material.dart';
import 'package:go_router/go_router.dart';

final appRoutes = [
  GoRoute(
    path: '/login',
    builder: (context, state) => const Center(
      child: Text('Login Screen'),
    ),
  ),
  GoRoute(
    path: '/dashboard',
    builder: (context, state) => const Center(
      child: Text('Dashboard Screen'),
    ),
  ),
];