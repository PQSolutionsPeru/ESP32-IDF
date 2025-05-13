import 'package:firebase_auth/firebase_auth.dart';
import 'package:cloud_firestore/cloud_firestore.dart';

class FirebaseService {
  final FirebaseAuth _auth = FirebaseAuth.instance;
  final FirebaseFirestore _firestore = FirebaseFirestore.instance;

  // Método para registrar un nuevo usuario
  Future<String?> registerUser(String email, String password, String username) async {
    try {
      await _auth.createUserWithEmailAndPassword(email: email, password: password);
      String userId = _auth.currentUser!.uid;
      await _firestore.collection('users').doc(userId).set({
        'username': username,
        'email': email,
        'isAdmin': false, // Indica si el usuario es administrador (false por defecto para usuarios normales)
        // Puedes agregar más campos según tus necesidades
      });
      return null; // Registro exitoso, retorna null sin errores
    } catch (e) {
      return e.toString(); // Retorna el mensaje de error en caso de fallo
    }
  }

  // Método para iniciar sesión con correo y contraseña
  Future<String?> signInUser(String email, String password) async {
    try {
      await _auth.signInWithEmailAndPassword(email: email, password: password);
      return null; // Inicio de sesión exitoso, retorna null sin errores
    } catch (e) {
      return e.toString(); // Retorna el mensaje de error en caso de fallo
    }
  }

  // Método para cerrar sesión
  Future<void> signOutUser() async {
    await _auth.signOut();
  }

  // Método para obtener información del usuario actual
  Future<Map<String, dynamic>?> getCurrentUserData() async {
    String? userId = _auth.currentUser?.uid;
    if (userId != null) {
      DocumentSnapshot userSnapshot = await _firestore.collection('users').doc(userId).get();
      if (userSnapshot.exists) {
        return userSnapshot.data() as Map<String, dynamic>?; // Retorna los datos del usuario
      }
    }
    return null; // No se encontró información del usuario
  }

  // Método para verificar si el usuario actual es administrador
  Future<bool> isUserAdmin() async {
    Map<String, dynamic>? userData = await getCurrentUserData();
    if (userData != null) {
      return userData['isAdmin'] ?? false; // Retorna true si el usuario es administrador, false si no lo es
    }
    return false; // Por defecto, el usuario no es administrador si no se puede obtener la información
  }

  // Implementa más métodos según las operaciones que necesites realizar con Firebase
}
