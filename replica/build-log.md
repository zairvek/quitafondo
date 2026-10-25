# Registro de construcción

Fecha: 2026-10-25. Clon: Quitafondo (Android, Kotlin).

**Limitación importante:** este entorno no tiene el SDK de Android ni acceso a los repositorios de Google y Maven, así que la app no se compiló con Gradle ni se probó en un teléfono. Se verificó así:

- El motor (`engine/`) se compiló con Kotlin 2.0.21 y pasa 19 pruebas unitarias en la JVM, más una medición de velocidad con una imagen de 4 MP.
- Todo el código de Android se compiló contra firmas de la API escritas a mano (Android, AndroidX, Material, ML Kit) y un `R` generado desde los recursos: sin errores.
- Se validaron todos los XML y que cada recurso referenciado exista.
- Revisión independiente del código buscando fallos de ejecución.

La primera compilación real ocurre en Android Studio.

| ID | pantalla | estado | falta | más difícil de lo esperado |
| --- | --- | --- | --- | --- |
| S01 | Inicio | hecho | — | — |
| S02 | Selector de fotos | hecho (del sistema) | — | — |
| S03 | Recorte | hecho | conservar el marco al girar la pantalla | rangos mínimos en fotos diminutas |
| S04 | Editor | hecho | modo "Extraer" con marcadores (could) | Mágico: relleno limitado por bordes y círculo; gestos de dos dedos que cancelan el trazo |
| S05/S06 | Guardar y compartir | hecho | anuncios (skip) | — |

Funciones: 25 de 28 hechas; las 17 "must" están hechas. No hechas: modo Extraer (could), anuncios y enlaces a apps del desarrollador (skip).

Rendimiento medido en la JVM con 2048×2048: mapa de bordes 56–87 ms, trazo mágico de 40 pasos 16–76 ms, suavizado 200 ms. En un teléfono será de 2 a 5 veces más lento; el suavizado corre fuera del hilo principal.

## Revisión independiente (2026-10-25)

Corregido tras la revisión:

1. Girar la pantalla mezclaba los valores de los tres deslizadores del editor (compartían id). Ahora no guardan estado propio; los valores vienen de la sesión.
2. La cuenta de memoria de deshacer solo crecía, y con el tiempo dejaba un solo paso. Corregido, con prueba nueva.
3. Cerrar o girar la pantalla a mitad de una tarea mostraba errores falsos ("no se pudo guardar"). La cancelación ya no cuenta como error, y el guardado termina siempre.
4. Cambiar de herramienta reiniciaba el zoom. La fila de "Parecido" ahora se oculta sin cambiar el tamaño del lienzo.
5. Durante "Buscando…" se podían pulsar otros botones y lanzar tareas duplicadas. La capa de espera cubre toda la pantalla y cada tarea no puede lanzarse dos veces.
6. Un segundo dedo en Deshacer o Persona a mitad de trazo dejaba el trazo fuera del historial. Ahora el trazo en curso se cancela antes.
7. La vista previa de guardar podía cerrar la app por falta de memoria en teléfonos modestos. Ahora se calcula una a la vez, se detiene si ya no hace falta y avisa en vez de cerrarse.
8. ML Kit: el segmentador se cierra al terminar la tarea, no antes.
9. Mágico limita su radio a 320 píxeles de imagen para que el dibujo no se vuelva lento; el cursor muestra ese límite.
10. Barras del sistema configuradas después de `super.onCreate`.
11. Quitafondo ya no aparece en su propio menú de compartir.
