# Quitafondo

App de Android para quitar el fondo de una foto y guardarla como PNG transparente. Inspirada en cómo funciona Background Eraser, escrita desde cero: sin código, textos, iconos ni marca del original.

## Qué hace

1. **Elegir foto** desde la galería (o "Compartir" una foto con Quitafondo desde otra app).
2. **Encuadrar**: arrastra las esquinas o los lados; mueve el marco tocando dentro.
3. **Borrar el fondo** con cinco herramientas:
   - **Borrar**: borra lo que tocas.
   - **Mágico**: borra la zona parecida al centro del círculo y se detiene en los bordes del objeto.
   - **Color**: borra, dentro del círculo, los colores parecidos al primero que tocas.
   - **Reparar**: devuelve la foto original donde pasas el dedo.
   - **Auto**: recorta el objeto principal de un toque, sea lo que sea. Si el modelo de objetos aún no se ha descargado en el teléfono, usa el de personas, que va dentro de la app.
4. Ajustes del pincel: **Tamaño**, **Distancia** (el círculo va por encima del dedo para que no lo tape) y **Parecido** (en Mágico y Color). Mientras dibujas, una **lupa** en la esquina muestra ampliado lo que hay bajo el pincel.
5. **Dos dedos** para acercar y moverte. **Deshacer** y **Rehacer** arriba. El botón de medio círculo cambia el fondo de contraste (tablero, blanco, gris, negro) para ver restos.
6. **Guardar**: elige el **fondo** (transparente, blanco, negro, un color, la misma foto desenfocada u otra foto), suaviza el borde, añade un **borde de pegatina** si quieres, quita el espacio vacío y elige PNG o JPG. Se guarda en *Imágenes › Quitafondo* y puedes compartirla.

## Cómo instalarla en tu teléfono

Necesitas una computadora (Windows, Mac o Linux) y tu teléfono Android 10 o más nuevo.

### 1. Instala Android Studio

Descárgalo gratis de https://developer.android.com/studio e instálalo con las opciones por defecto. La primera vez que lo abras descargará el SDK de Android; deja que termine.

### 2. Abre el proyecto

1. Descomprime `Quitafondo.zip` en una carpeta fácil de encontrar (por ejemplo, Documentos).
2. En Android Studio: **File › Open** (o **Open** en la pantalla de bienvenida) y elige la carpeta `Quitafondo`.
3. Si pregunta si confías en el proyecto, elige **Trust Project**.
4. Espera a que termine la sincronización (barra de progreso abajo). La primera vez descarga Gradle y las librerías: puede tardar de 5 a 15 minutos.
   - Si pide instalar **Android SDK Platform 35** o aceptar licencias, acepta.
   - Si sugiere **actualizar el Android Gradle Plugin**, puedes ignorarlo por ahora. El proyecto funciona con la versión que trae.

### 3. Prepara el teléfono

1. En el teléfono: **Ajustes › Acerca del teléfono** y toca **7 veces** "Número de compilación" hasta que diga que eres desarrollador. (En algunos teléfonos está en *Ajustes › Acerca del teléfono › Información de software*.)
2. Vuelve a Ajustes, entra en **Opciones de desarrollador** y activa **Depuración por USB**.
3. Conecta el teléfono a la computadora con el cable. En el teléfono aparecerá "¿Permitir depuración por USB?": toca **Permitir**.

### 4. Instala la app

Arriba en Android Studio verás el nombre de tu teléfono junto a un botón verde **▶ Run**. Púlsalo. En un minuto la app se instala y se abre en el teléfono. Desde ahí queda instalada como cualquier otra.

### Alternativa: instalar un APK sin cable

1. En Android Studio: **Build › Build App Bundle(s) / APK(s) › Build APK(s)**.
2. Cuando termine, pulsa **locate** en el aviso. Ese archivo `app-debug.apk` es la app.
3. Pásalo al teléfono (correo, Drive, cable) y ábrelo. Android te pedirá permitir "instalar apps de fuentes desconocidas" para esa app; acéptalo solo para esto.

## Si algo falla

- **La sincronización falla con un error de red**: revisa tu conexión y pulsa *Try Again* o el icono del elefante ("Sync Project with Gradle Files").
- **No aparece el teléfono**: cambia el modo USB del teléfono a "Transferencia de archivos", desconecta y vuelve a conectar, y acepta el aviso de depuración.
- **"SDK location not found"**: abre *Tools › SDK Manager*, instala Android 15 (API 35) y vuelve a sincronizar.
- **La galería muestra el fondo negro**: es normal en algunas galerías. La imagen sí es transparente; ábrela en otra app (WhatsApp, un editor) para comprobarlo.
- Si ves un error al compilar, copia el texto del panel **Build** y pásamelo.

## Estructura del código

```
app/src/main/java/dev/quitafondo/
  engine/          motor de borrado en Kotlin puro (máscara, pinceles, deshacer, suavizado)
  view/            vistas propias: EraserView (lienzo), CropView (recorte), PreviewView
  MainActivity     inicio y selector de fotos
  CropActivity     recorte
  EditorActivity   editor con herramientas
  SaveActivity     suavizado, formato, guardar y compartir
  AutoCutout       recorte automático con ML Kit (objetos; respaldo de personas)
app/src/test/      pruebas del motor (en Android Studio: clic derecho en la carpeta › Run Tests)
replica/           estudio de la app original, arquitectura y registro de construcción
```
