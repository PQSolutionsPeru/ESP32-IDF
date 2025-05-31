import firebase_admin
from firebase_admin import credentials
from firebase_admin import firestore

def initialize_firestore():
    # Usa una ruta raw (r antes de la cadena) para evitar problemas con caracteres de escape
    cred = credentials.Certificate(r'E:\PQSolutions\HDD-Monitor\fir-hdd-monitor-d00de-firebase-adminsdk-mmnr4-45eeaf670a.json')
    firebase_admin.initialize_app(cred, {
        'projectId': 'fir-hdd-monitor-d00de',
    })
    return firestore.client()

def print_collection_structure(collection, indent=''):
    docs = collection.get()
    for doc in docs:
        print(f"{indent}Documento: {doc.id}")
        print_document_structure(doc, indent + '  ')

def print_document_structure(doc, indent=''):
    data = doc.to_dict()
    for key, value in data.items():
        if isinstance(value, firestore.DocumentReference):
            print(f"{indent}{key}: (Referencia a documento)")
            sub_doc = value.get()
            print_document_structure(sub_doc, indent + '  ')
        elif isinstance(value, list) and len(value) > 0 and isinstance(value[0], firestore.DocumentReference):
            print(f"{indent}{key}: (Lista de referencias a documentos)")
            for ref in value:
                sub_doc = ref.get()
                print_document_structure(sub_doc, indent + '  ')
        else:
            print(f"{indent}{key}: {value}")
    
    # Verificar si hay subcolecciones
    sub_collections = doc.reference.collections()
    for sub_collection in sub_collections:
        print(f"{indent}Subcolección: {sub_collection.id}")
        print_collection_structure(sub_collection, indent + '  ')

def main():
    db = initialize_firestore()
    collections = db.collections()
    for collection in collections:
        print(f"Colección: {collection.id}")
        print_collection_structure(collection)

if __name__ == "__main__":
    main()



# Adjunto todos los archivos python que corre mi ESP32, también adjunto el código completo que corre una VM virtual machine en google cloud computing, además mi BD completa en firestore y por último, los archivos de la APP relacionados con la configuración de un ESP32.

