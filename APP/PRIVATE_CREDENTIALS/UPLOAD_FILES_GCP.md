# 📤 Cómo Subir Archivos Usando SSH del Navegador de GCP

## 🎯 Objetivo
Subir los archivos necesarios desde tu PC a la VM usando la interfaz SSH del navegador de Google Cloud.

---

## 📍 Paso a Paso

### 1. Abre la Terminal SSH en el Navegador

URL directa:
```
https://ssh.cloud.google.com/v2/ssh/projects/fir-hdd-monitor-d00de/zones/us-central1-c/instances/instanciavm-myqtthub
```

Deberías ver:
```
pqsolutionsperu@instanciavm-myqtthub:~$
```

---

### 2. Busca el Botón "Upload File"

En la interfaz SSH del navegador, busca en la parte superior:

**Opción A - Ícono de Engranaje (⚙️)**
- Haz clic en el ícono de configuración/engranaje
- Selecciona "Upload file" o "Subir archivo"

**Opción B - Menú de Tres Puntos (⋮)**
- Haz clic en los tres puntos verticales
- Busca "Upload file" o "Cargar archivo"

**Opción C - Botón Directo**
- Algunos navegadores muestran un botón "⬆ Upload" directamente

---

### 3. Archivos a Subir

Desde tu PC, navega a esta carpeta:
```
E:\PQSolutions\HDD-Monitor\ESP32-IDF\ESP32-IDF\APP\HDD1_2\VM GOOGLE CLOUD\
```

**Archivos a subir (12 archivos)**:

#### Archivos Nuevos (7):
1. ✓ `requirements.txt`
2. ✓ `rate_limiter.py`
3. ✓ `system_watchdog.py`
4. ✓ `nfpa_metrics.py`
5. ✓ `setup_postgresql.sql`
6. ✓ `hdd-monitor-watchdog.service`
7. ✓ `verify_installation.sh`

#### Archivos Modificados (5):
8. ✓ `config.py`
9. ✓ `mqtt_client.py`
10. ✓ `notification_handler.py`
11. ✓ `firestore_handler.py`
12. ✓ `main.py`

---

### 4. Subir los Archivos

**Para cada archivo**:
1. Haz clic en "Upload file"
2. Selecciona el archivo desde tu PC
3. Espera a que aparezca "Upload complete" o similar
4. El archivo se guardará en `/home/pqsolutionsperu/`

**Nota**: Puedes subir archivos de uno en uno o varios a la vez (dependiendo de la interfaz).

---

### 5. Verificar que se Subieron

Después de subir todos los archivos, en la terminal ejecuta:

```bash
# Ver archivos subidos en tu directorio home
ls -lh ~/ | grep -E "\.py$|\.txt$|\.sql$|\.service$|\.sh$"

# Deberías ver los 12 archivos listados
```

---

### 6. Mover Archivos al Directorio Temporal

```bash
# Crear directorio temporal
mkdir -p ~/hdd-monitor-update

# Mover todos los archivos subidos
mv ~/*.py ~/hdd-monitor-update/ 2>/dev/null
mv ~/*.txt ~/hdd-monitor-update/ 2>/dev/null
mv ~/*.sql ~/hdd-monitor-update/ 2>/dev/null
mv ~/*.service ~/hdd-monitor-update/ 2>/dev/null
mv ~/*.sh ~/hdd-monitor-update/ 2>/dev/null

# Verificar
ls -lh ~/hdd-monitor-update/
```

Deberías ver los 12 archivos en `~/hdd-monitor-update/`

---

## 🔄 Método Alternativo: Crear Archivos Manualmente

Si **no encuentras el botón "Upload file"**, usa este método:

### Opción: Copiar y Pegar Contenido

Para archivos pequeños como `requirements.txt`:

```bash
# En la terminal SSH del navegador
cd ~/hdd-monitor-update
nano requirements.txt
```

Luego:
1. Abre el archivo en tu PC con Notepad o VS Code
2. Copia todo el contenido (Ctrl+A, Ctrl+C)
3. En la terminal, pega el contenido (Ctrl+Shift+V o clic derecho → Paste)
4. Guarda: Ctrl+O, Enter
5. Salir: Ctrl+X

Repite para cada archivo.

---

## ⚠️ Notas Importantes

1. **Límite de tamaño**: La interfaz SSH del navegador puede tener límite de ~10MB por archivo
2. **Archivos grandes**: `firestore_handler.py` y `notification_handler.py` son los más grandes (~40KB)
3. **Permisos**: Los archivos subidos son propiedad de `pqsolutionsperu` (correcto)
4. **Ubicación**: Los archivos se suben a `/home/pqsolutionsperu/` por defecto

---

## ✅ Checklist de Verificación

Antes de continuar con el deployment, verifica:

- [ ] 12 archivos subidos
- [ ] Archivos movidos a `~/hdd-monitor-update/`
- [ ] Comando `ls ~/hdd-monitor-update/` muestra todos los archivos
- [ ] Sin errores de "Permission denied"

---

## 🚀 Siguiente Paso

Una vez que hayas subido todos los archivos, continúa con **PASO 5** de `DEPLOYMENT_GUIDE.md`:

```bash
# Hacer backup y copiar archivos al proyecto
sudo cp -r /home/pqsolutions/hdd-monitor /home/pqsolutions/hdd-monitor-backup-$(date +%Y%m%d-%H%M)
```

---

## 🆘 Problemas Comunes

### No veo el botón "Upload file"
- **Solución**: Usa el método de copiar y pegar con `nano`
- O actualiza tu navegador a la última versión

### "Upload failed"
- **Solución**: Reduce el tamaño del archivo o sube de uno en uno
- Verifica tu conexión a internet

### Los archivos no aparecen en `~/`
- **Solución**: Ejecuta `ls -la ~/` para ver archivos ocultos
- Pueden estar en `/tmp/` → ejecuta `ls -lh /tmp/`

### "Permission denied" al mover archivos
- **Solución**: Los archivos ya son tuyos, no necesitas `sudo`
- Verifica con: `ls -l ~/archivo.py`

---

## 📞 Soporte

Si sigues teniendo problemas para subir archivos, considera estas alternativas:

1. **Google Cloud Console**: Usar `gcloud compute scp` desde Cloud Shell
2. **GitHub**: Subir archivos a un repo privado y clonar en la VM
3. **Pastebin**: Para archivos pequeños, usar servicios de paste

---

**¡Listo para deployment!** 🎉
