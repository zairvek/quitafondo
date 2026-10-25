# Arquitectura del clon: Quitafondo (Android)

Nombre propio del clon: **Quitafondo**. Paquete `dev.quitafondo`. Sin marca, textos ni imágenes del original.

## Stack

| capa | elección | por qué |
| --- | --- | --- |
| lenguaje | Kotlin 2.0 | el estándar en Android |
| UI | Views + vistas propias (`Canvas`) | el lienzo de borrado es dibujo a mano; Compose no aporta y añade riesgo de versiones |
| componentes | Material Components 1.12 | botones segmentados, sliders, diálogos |
| imagen | `ImageDecoder` (API 28+) | aplica la rotación EXIF y reduce al decodificar |
| recorte de persona | ML Kit Selfie Segmentation 16.0.0-beta6 | modelo incluido en la app, funciona sin conexión |
| guardado | `MediaStore` en `Pictures/Quitafondo` | sin permisos de almacenamiento desde Android 10 |
| selección de fotos | Photo Picker (`PickVisualMedia`) | sin permisos de lectura |
| concurrencia | corrutinas + `lifecycleScope` | Magic, Auto y guardado fuera del hilo principal |
| minSdk / target | 29 / 35 | Android 10+, simplifica permisos |
| build | AGP 8.7.3, Gradle 8.11.1 | estable; Android Studio puede actualizarlo |

## Datos

No hay servidor, cuentas ni base de datos, así que los pasos de esquema SQL y API de la plantilla no aplican. El estado vive en memoria en un objeto `Session` mientras la app está abierta.

```
EditorState
  width, height          tamaño de trabajo (máx. ~4,2 MP)
  original: IntArray     píxeles ARGB de la foto recortada
  alpha: ByteArray       máscara editable, 0 = borrado, 255 = visible
  edges: ByteArray?      mapa de bordes (Sobel), calculado al entrar en Mágico
  history: UndoStack     diffs por rectángulo sucio, tope de memoria 48 MB
```

El motor (`engine/`) es Kotlin puro sobre arreglos: se puede probar en la JVM sin Android.

## Pantallas y código

| ID | pantalla | clase |
| --- | --- | --- |
| S01 | Inicio | `MainActivity` |
| S02 | Selector de fotos | Photo Picker del sistema |
| S03 | Recorte | `CropActivity` + `CropView` |
| S04 | Editor | `EditorActivity` + `EraserView` |
| S05/S06 | Suavizado, guardar, compartir | `SaveActivity` |

## Partes delicadas

- **Memoria:** fotos de 12–50 MP. Se decodifican reducidas (lado máx. 3000 para recortar) y se trabaja a ≤ 4,2 MP. Deshacer guarda solo el rectángulo tocado.
- **Gestos:** un dedo dibuja, dos dedos hacen zoom y mueven. Si entra un segundo dedo durante un trazo, el trazo se revierte.
- **Desplazamiento del cursor:** el punto que borra está por encima del dedo para que el dedo no tape la zona.
- **Muerte del proceso:** si Android cierra la app en segundo plano, se pierde la sesión; las pantallas vuelven al inicio en vez de fallar.
- **Transparencia en la galería:** muchas galerías pintan la transparencia de negro; se avisa al guardar.

## Orden de construcción

1. Corte vertical: S01 → S02 → S04 (Manual) → guardar PNG.
2. Imprescindibles: recorte, Reparar, Color, Mágico, deshacer/rehacer, zoom, tamaño y desplazamiento del cursor, suavizado, errores.
3. Deseables: fondo de contraste, tolerancia, Auto persona, compartir.
