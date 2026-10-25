# Mapa de reconocimiento: Background Eraser (handyCloset Inc.)

- **App original:** Background Eraser, Android, paquete `com.handycloset.android.eraser`
- **Plataforma del clon:** Android
- **Alcance:** núcleo completo — cargar foto, recortar, borrar (Manual, Magic, Color, Repair, Auto-Selfie), suavizar bordes, guardar PNG
- **Destino:** proyecto propio / aprendizaje
- **Método:** solo fuentes públicas (tienda, página de soporte oficial, reseñas). Sin código fuente, sin APIs privadas, sin cuenta ajena.
- **Fecha:** 2026-10-25

## Lo que es la app

Una herramienta de un solo propósito: tomas una foto, borras el fondo con cuatro modos de borrador y guardas el resultado como PNG con transparencia para usarlo como pegatina en otras apps (PhotoLayers, My Original Stickers para WhatsApp). No tiene cuenta, nube, ajustes ni editor de fondos. Es gratis con anuncios. 100 M+ descargas, 4,7 ★ con ~994 k opiniones (Google Play, oct. 2026).

**Bucle central:** Cargar foto → Recortar → Borrar fondo → Suavizar borde → Guardar PNG. Cinco pantallas, sin registro.

## 1. Fuentes

| fuente | URL | qué aporta |
| --- | --- | --- |
| Página de soporte oficial (Android) | https://redirect.handycloset.app/a-eraser-26-en.html (también https://support.handycloset.com/a-eraser-26-en.html) | Lista completa de pantallas y modos con 11 capturas: Launch, Crop, Manual, Magic, Color, Repair, Auto-Selfie, Other Features, Smooth Edge and Save, FAQ |
| Ficha de Google Play (es) | https://play.google.com/store/apps/details?id=com.handycloset.android.eraser&hl=es_US | Descripción, anuncios, descargas, rating, última actualización (29 may 2026), apps hermanas, seguridad de datos |
| Ficha de Google Play (en-GB) | https://play.google.com/store/apps/details?id=com.handycloset.android.eraser&hl=en-GB | Descripción en inglés, reseñas sobre anuncios tras cada edición, PNG sin fondo |
| Ficha iOS "Background Eraser 〜 Stickers" | https://apps.apple.com/us/app/-/id926035241 | Equivalente iOS: modo "Star" (= Magic), modo Auto, recorte IA automático |
| Ficha iOS Pro (Arabia Saudí, texto antiguo) | https://apps.apple.com/sa/app/app/id437015777 | Confirma formatos de salida: PNG (transparente) y JPEG (fondo blanco) |
| Reseña Softpedia (versión antigua 1.4.6) | https://mobile.softpedia.com/android/Background-Eraser-Android-Review-2758.html | Detalle de herramientas del editor: desplazamiento del cursor, tamaño del cursor, brillo del fondo, deshacer/rehacer, zoom a dos dedos |
| Historial de versiones (APKMirror) | https://www.apkmirror.com/apk/handycloset-inc/background-eraser/ | Texto de la descripción con los modos "Magic" y "Auto/Color" |
| Tutorial VOI | https://voi.id/en/teknologi/106038 | Flujo "Load Photo" → Auto / Magic / Manual → Repair |
| Tutorial (Indonesia) | https://my.sancarlo.co.uk/sancarlo-news/edit-foto-keren-tutorial-lengkap-dengan-background-eraser-1767648629 | Confirma "Load a photo", "Repair", "Smooth", "Save" como pasos |
| Web del desarrollador | https://apps.handycloset.com | Enlace oficial desde la ficha (no se pudo abrir desde aquí) |

Capturas de referencia: las 11 imágenes de la página de soporte están en `https://photolayers.com/images/a-eraser-26-en/a-1.png` … `a-11.png`. No se pudieron descargar desde este entorno (el dominio no está permitido). Guárdalas tú en `replica/screens/` si quieres compararlas con el clon; nunca se incluyen en él.

## 2. Inventario de pantallas

| ID | pantalla | cómo se llega | propósito | componentes clave | estados vistos |
| --- | --- | --- | --- | --- | --- |
| S01 | Inicio | al abrir la app | Punto de entrada: botón "Load a photo"; enlaces a apps hermanas (PhotoLayers, My Original Stickers, Album) | Botón primario grande, banner de anuncio, lista de apps relacionadas | normal; sin permiso de fotos (debe pedirlo); banner sin red (espacio vacío) |
| S02 | Selector de foto | S01 → "Load a photo" | Elegir una imagen de la galería del sistema (no hay cámara en la app) | Intent de selección del sistema (Photo Picker) | normal; cancelado (vuelve a S01); imagen no legible → aviso "Can't load a photo" (FAQ) |
| S03 | Recorte | tras elegir foto | Encuadrar el sujeto antes de borrar; reduce trabajo y memoria | Imagen con marco de recorte de 4 esquinas arrastrables, botón "Done", botón atrás | normal; foto muy grande (se reduce la resolución de trabajo, inferido); orientación EXIF corregida |
| S04 | Editor (borrador) | S03 → "Done" | Pantalla principal: borrar fondo con 4 modos + Auto-Selfie | Lienzo con fondo de tablero de ajedrez, barra de modos (Manual / Magic / Color / Repair), botón Auto-Selfie, slider de tamaño de cursor, slider de desplazamiento del cursor, slider de brillo/color de fondo, Undo / Redo, Zoom (dos dedos), botón "Done" | con foto cargada; Magic calculando bordes (progreso); Auto-Selfie calculando (progreso); sin cambios (Undo deshabilitado); nada que rehacer (Redo deshabilitado); zoom activo |
| S05 | Suavizado y guardado | S04 → "Done" | Previsualizar el recorte sobre fondo, ajustar suavizado de borde, guardar | Vista previa, slider "Smooth Edge" (0…n), botón "Save", (JPEG opcional con fondo blanco, confirmado en iOS; en Android solo PNG según reseñas), botón atrás para seguir editando | antes de guardar; guardando (progreso); guardado con ruta/aviso; error "Can't save a photo" (FAQ) |
| S06 | Resultado / interstitial | tras "Save" | Confirma guardado; muestra anuncio intersticial (reseñas: tras cada edición) | Diálogo o toast "Saved", anuncio, acciones "Share" / "Open with" (inferido de "How to use the saved image") | guardado; anuncio cerrado; usuario pulsa atrás para saltar anuncio (reseña 2021) |

No hay pantalla de ajustes (Softpedia: "sin opciones de salida"). No hay cuenta ni onboarding.

## 3. Flujos de usuario

```
F01 Quitar el fondo y guardar (camino feliz)
    S01 inicio -> S02 elegir foto -> S03 recortar "Done" -> S04 editor:
        Magic (toca el fondo) -> Repair (repasa sujeto) -> "Done"
    -> S05 Smooth Edge -> "Save" -> S06 guardado
    clics en camino feliz: 1 (Load) + 1 (foto) + 1 (Done) + ~3 (modo + toques) + 1 (Done) + 1 (Save) = 8
    bordes: permiso de fotos denegado; foto corrupta; app sin memoria con foto 4K;
            usuario sale a mitad (¿se pierde el trabajo? no hay borrador guardado, inferido)

F02 Borrar fondo de color plano
    S04 editor: modo Color -> toca el fondo -> se borran píxeles similares -> ajusta tolerancia -> "Done"
    bordes: color del fondo parecido al sujeto; sombras; se borran partes del sujeto -> F04

F03 Recorte automático de persona (Auto-Selfie)
    S04 editor: botón Auto-Selfie -> procesa -> fondo borrado -> retoque con Repair/Manual -> "Done"
    bordes: no hay persona en la foto (resultado vacío o aviso); pelo fino; varias personas

F04 Recuperar una zona borrada por error
    S04 editor: modo Repair -> pinta sobre la zona -> vuelve la imagen original
    alternativa: Undo (paso a paso)
    bordes: Repair tras Smooth Edge no aplica (Smooth es paso posterior)

F05 Trabajo de precisión
    S04 editor: zoom con dos dedos -> ajusta tamaño del cursor -> ajusta desplazamiento del cursor
    -> sube brillo del fondo para ver restos -> Manual en bordes
    bordes: zoom máximo; cursor desplazado fuera de la pantalla

F06 Usar el resultado
    S06 -> "Share"/"Open with" -> PhotoLayers o My Original Stickers (apps externas)
    borde: la galería del sistema muestra la transparencia como negro (FAQ) -> el usuario cree que falló
```

## 4. Componentes

| componente | variantes | estados | pantallas |
| --- | --- | --- | --- |
| Botón primario | "Load a photo", "Done", "Save" | normal, pulsado, deshabilitado (Save mientras guarda) | S01, S03, S04, S05 |
| Barra de modos | 4 segmentos: Manual, Magic, Color, Repair | seleccionado / no seleccionado | S04 |
| Botón Auto-Selfie | icono + etiqueta | normal, procesando (spinner), deshabilitado sin red si usa modelo remoto (inferido: probablemente modelo local, ML Kit o similar) | S04 |
| Lienzo de edición | tablero de ajedrez bajo la imagen | zoom 1x…Nx, panning, brillo de fondo ajustable | S04, S05 |
| Cursor de borrado | círculo con punto de contacto desplazado | tamaño variable, desplazamiento variable (eje vertical) | S04 |
| Slider | tamaño cursor, desplazamiento cursor, brillo de fondo, tolerancia (Color/Magic), Smooth Edge | valor mínimo, máximo, por defecto | S04, S05 |
| Undo / Redo | iconos | habilitado / deshabilitado según pila de historial | S04 |
| Marco de recorte | 4 esquinas + bordes arrastrables | arrastrando, mínimo tamaño | S03 |
| Indicador de progreso | diálogo modal | Magic, Auto-Selfie, Save | S04, S05 |
| Diálogo / toast | "Saved", errores de carga/guardado | — | S05, S06 |
| Banner de anuncio | parte inferior | cargado, vacío | S01, S04 (inferido) |
| Anuncio intersticial | pantalla completa | tras guardar (reseñas) | S06 |

## 5. Modelo de datos inferido

La app no tiene cuentas ni servidor. Todo vive en memoria durante la sesión y en disco como archivos.

```
Project (en memoria, 1 por sesión)
  source_uri        Uri de la foto original (content://)
  crop_rect         Rect en píxeles de la original
  work_bitmap       Bitmap ARGB_8888 recortado y reducido a tamaño de trabajo
  mask              canal alfa editable (misma resolución que work_bitmap)
  history           pila de máscaras o de trazos para Undo/Redo (límite de pasos, inferido)
  zoom, pan         estado de vista
  evidencia: S03, S04, Undo/Redo en Softpedia
  confianza: alta en campos, media en representación del historial

EraserSettings (en memoria, se reinician al abrir)
  mode              manual | magic | color | repair
  cursor_size       int
  cursor_offset     int (px por encima del dedo)
  bg_brightness     0…255 o color del tablero
  tolerance         int (para color y magic)
  evidencia: Softpedia, página de soporte "Other Features"
  confianza: media (no hay pantalla de ajustes, así que no persisten; inferido)

ExportOptions
  smooth_edge       int (0 = sin suavizado)
  format            png (transparente) | jpeg (fondo blanco, confirmado solo en iOS)
  evidencia: S05, ficha iOS Pro
  confianza: alta en smooth_edge, baja en jpeg para Android

SavedImage (en disco)
  path              Pictures/Background Eraser/… o MediaStore (inferido)
  width, height     resolución de salida (probablemente la de trabajo, no la original)
  evidencia: FAQ "Can't save a photo", app hermana "Album for Background Eraser"
  confianza: media
```

Sin base de datos. Una tabla SQLite opcional en el clon para un historial de exportaciones sería una mejora, no una copia.

## 6. Algoritmos a replicar (lo que hay detrás de cada modo)

| modo | qué hace según las fuentes | implementación razonable en el clon |
| --- | --- | --- |
| Manual | borra lo que pintas con el cursor | pincel sobre el canal alfa con `PorterDuff.CLEAR` o Canvas + Path |
| Magic | "detecta bordes de la imagen para borrar fácil y preciso"; iOS lo llama "Star" | relleno por inundación limitado por bordes (Canny/Sobel) desde el punto tocado; o segmentación por regiones (watershed) precalculada al entrar al modo |
| Color / Auto | "borra píxeles similares automáticamente" | flood fill por distancia de color en RGB/LAB con tolerancia, contiguo al toque; variante global (no contigua) como opción |
| Repair | restaura zonas borradas | pincel que vuelve a poner alfa = 255 desde el bitmap original |
| Auto-Selfie | recorte de persona en un toque | segmentación de personas en el dispositivo (ML Kit Selfie Segmentation o un modelo TFLite tipo MODNet/U²-Net) |
| Smooth Edge | suaviza el borde del recorte | desenfoque gaussiano del canal alfa con radio = valor del slider, opcionalmente erosión de 1 px antes |

## 7. Matriz de funciones

Ver `replica/features.csv`. Resumen: 27 funciones, 17 "must", 5 "should", 2 "could", 3 "skip".

## 8. Lo que no se clona

| elemento | motivo |
| --- | --- |
| Marca, icono, nombre "Background Eraser", capturas de la tienda | propiedad de handyCloset; el clon usa nombre e icono propios |
| Red de anuncios y su configuración | no aporta al aprendizaje; el clon va sin anuncios |
| Enlaces promocionales a PhotoLayers, My Original Stickers, Album | son productos del desarrollador; el clon expone "Compartir" genérico |
| Modelo exacto de Auto-Selfie | desconocido; se usa un modelo público en el dispositivo |
| Modo "Extraer" con marcadores azul/rojo (texto antiguo de la ficha en español) | parece una versión anterior sustituida por Magic; se anota como "could" |

## 9. Tamaño

- **Pantallas:** 6 (una es el selector del sistema)
- **Flujos:** 6
- **Entidades:** 4, todas en memoria o archivo
- **Partes difíciles:**
  1. Magic mode: detección de bordes + relleno que se sienta igual de rápido al tocar (precalcular mapa de bordes en segundo plano).
  2. Rendimiento del lienzo: pincel fluido sobre bitmaps grandes con zoom, deshacer/rehacer sin agotar memoria (guardar diffs de máscara o tiles, no bitmaps enteros).
  3. Auto-Selfie: integrar un modelo de segmentación en el dispositivo y fusionar su máscara con el canal alfa editable.
- **Tamaño:** **M** (unas semanas). Manual + Color + Repair + Smooth + Save caben en un fin de semana largo; Magic bien hecho y Auto-Selfie añaden las semanas.

## Siguiente paso

`/replica-architect` para elegir stack (Kotlin + Jetpack Compose o Views, ML Kit para Auto-Selfie), definir el modelo de máscara/historial y el orden de construcción: S01 → S02 → S04 Manual → Save → Color → Repair → Crop → Smooth → Magic → Auto-Selfie.
