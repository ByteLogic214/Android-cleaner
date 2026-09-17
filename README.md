# TurboClean

Proyecto Android en Kotlin y Jetpack Compose para diagnóstico seguro de almacenamiento y red.

## Ejecutar desde GitHub móvil

1. Extrae este ZIP y sube su contenido a un repositorio GitHub, manteniendo la carpeta `.github`.
2. En GitHub, abre el repositorio y crea o usa la rama `main`.
3. Sube o confirma cualquier cambio: el workflow se inicia automáticamente con cada *push* a `main`.
4. Abre la pestaña **Actions**, elige la ejecución **Android CI** y espera a que termine.
5. En **Artifacts**, descarga `TurboClean-debug-apk` para el APK y `TurboClean-source` para el código empaquetado.

También puedes iniciarlo manualmente desde la web añadiendo `workflow_dispatch` al workflow si lo necesitas; esta versión se activa automáticamente al subir cambios a `main`.
