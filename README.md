# Visor

Reproductor IPTV para Fire TV (Android TV) con listas Xtream.

## Qué hace

- **TV en directo** por carpetas del proveedor, con la emisión actual de la EPG.
- **Guía** de 3 horas: OK sobre un programa en emisión lo abre; sobre uno futuro, lo programa para grabar (una vez, diario o semanal).
- **Películas y series** por carpetas, con carátula, sinopsis y nota del proveedor. En Ajustes se puede cambiar a TMDB si la app se compiló con clave.
- **Favoritos** de canales, películas y series (botón ≡ del mando sobre cualquier elemento), y "Seguir viendo".
- **Reproductor** a elegir en Ajustes: ExoPlayer o VLC. Cambio de idioma de audio y subtítulos durante la reproducción.
- **Grabaciones**: botón REC en el reproductor, desde la guía o con temporizador manual. Se guardan en la memoria interna o en un USB.
- **Temas** claro y negro OLED.

## Seguridad de la cuenta

- Servidor, usuario y contraseña se guardan cifrados con una clave del Android Keystore que no se puede sacar del aparato.
- Sin copias de seguridad ni transferencias de los datos de la app.
- Solo se confía en certificados del sistema: un proxy o una app instalada no puede leer el tráfico HTTPS.
- Las URLs con credenciales se construyen en memoria al reproducir; no se guardan ni se registran.
- PIN opcional para Ajustes y para abrir la app.

Límites: si el proveedor redirige los streams a `http://`, esa conexión va sin cifrar (es cosa del proveedor). Alguien con acceso de depuración (ADB) al aparato con la app abierta podría inspeccionar la memoria; no actives ADB si no lo usas.

## Compilar

GitHub Actions compila el APK en cada `push` a `main` (pestaña *Actions* → artefacto `visor-apk`).

Secretos opcionales del repositorio:

| Secreto | Para qué |
|---|---|
| `VISOR_KEYSTORE_B64`, `VISOR_KEYSTORE_PASSWORD`, `VISOR_KEY_ALIAS`, `VISOR_KEY_PASSWORD` | Firmar siempre con la misma clave, para poder actualizar sin desinstalar |
| `TMDB_API_KEY` | Activar la opción de carátulas de TMDB |

En local: Android Studio, abrir la carpeta y *Build → Build APK(s)*.

## Instalar en el Fire TV

1. Fire TV: *Ajustes → Mi Fire TV → Opciones para desarrolladores* → activa *Apps de origen desconocido* (o *Depuración ADB*).
2. Con ADB desde el PC: `adb connect IP_DEL_FIRETV:5555` y `adb install -r visor.apk`.
3. O con la app *Downloader* del Fire TV, desde un enlace al APK.
