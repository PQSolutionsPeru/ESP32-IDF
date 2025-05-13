import 'package:flutter/material.dart';
import 'package:flutter_app_hdd_monitor/services/firebase_service.dart';

class RegisterScreen extends StatelessWidget {
  final FirebaseService _firebaseService = FirebaseService();

  RegisterScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Registro de Usuario'),
      ),
      body: Center(
        child: ElevatedButton(
          child: const Text('Registrarse'),
          onPressed: () async {
            String? error = await _firebaseService.registerUser('correo@example.com', 'contraseña123', 'NombreUsuario');
            if (error == null) {
              // Registro exitoso, navegar a la siguiente pantalla
              // Aquí puedes agregar lógica adicional, como mostrar un mensaje de éxito
            } else {
              // Mostrar mensaje de error al usuario
              showDialog(
                context: context,
                builder: (context) => AlertDialog(
                  title: const Text('Error de Registro'),
                  content: Text(error),
                  actions: [
                    TextButton(
                      onPressed: () => Navigator.pop(context),
                      child: const Text('OK'),
                    ),
                  ],
                ),
              );
            }
          },
        ),
      ),
    );
  }
}
