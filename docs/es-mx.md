# Guía de la API de WATERMeDIA

[English](en-us.md) · Español (México)

WATERMeDIA resuelve enlaces multimedia, los decodifica y entrega los cuadros a tus motores gráficos y de audio. Necesita Java 17 o posterior. Los loaders de Minecraft lo arrancan por ti; una app que lo integra lo arranca una vez, después de preparar su entorno.

## Arranque y propiedad de los recursos

```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded();
```

`start` carga cada módulo una sola vez en toda la vida del proceso. Nada se descarga nunca: no existe un stop, y un segundo `start` lanza una excepción. El último argumento elige el entorno de cliente; los módulos sólo de cliente (Binaries, códecs, reproductor) no hacen nada en un servidor dedicado.

- `WaterMedia.loaded()` es la única forma de saber si ya está listo.
- Para una pantalla de carga, `WaterMedia.loading()` nombra el módulo que está arrancando y `step()`/`steps()` los cuentan: configuración, Binaries (si está instalado), red, códecs y reproductor, así que `steps()` es 5 con Binaries y 4 sin él.
- Las partes opcionales (el servidor de archivos, _FFmpeg_) registran su falla en el log y se quedan apagadas mientras la carga termina, así que revisa la capacidad que necesitas, como `CodecsAPI.ffmpegLoaded()`.
- Cualquier otra falla (la configuración, un módulo de Binaries instalado) hace que `start` lance `IllegalStateException` con esa falla como causa, y `loaded()` se queda en falso.
- La decodificación de imágenes en Java puro con `CodecsAPI` no necesita arranque.

`PlayerAPI.mrl`, los reproductores multimedia y `NetworkAPI.upload` lanzan `IllegalStateException` hasta que `loaded()` sea verdadero, y los de medios siempre lo hacen en servidores dedicados. `NetworkAPI.upload` además rechaza de entrada lo que no sea un archivo existente; en cambio, un servidor remoto inalcanzable o mal configurado hace fallar el estado devuelto.

`PlatformAPI` no necesita arranque: su registro es estático y `register`, `fetch` y `search` funcionan en cualquier momento. Los handlers que necesitan Binaries (YouTube, _yt-dlp_) fallan con su propia `PlatformException` hasta que carga, y siempre en servidores dedicados.

Binaries necesita WATERMeDIA y carga con él, preparando los nativos justo después de la configuración y antes de los módulos de red, códecs y reproductor. Los clientes de Minecraft lo requieren; los servidores dedicados pueden omitirlo, ya que nunca lo cargan. Las apps que integran la API Java también pueden omitirlo y conservar la decodificación de imágenes y los resolvedores de plataformas que no usan nativos. Desactivar _FFmpeg_ omite la extracción. Las rutas nativas quedan publicadas durante toda la vida del proceso y no hay una API aparte para iniciar o apagar Binaries.

Las compilaciones personalizadas de _FFmpeg_ pueden usar GPL, LGPL o cualquier otra licencia y versión declaradas: el cargador revisa la integridad y las capacidades nativas requeridas, no una lista permitida de versiones o licencias. Aun así necesitan un pegamento JNI compatible con la API Java que usa WATERMeDIA.

Un reproductor es dueño de los motores que consume su fábrica. Crea los motores de forma diferida con proveedores, para que una fuente no disponible no deje recursos nativos reservados. Tu app es dueña de sus contextos OpenGL, Vulkan y OpenAL: libera cada reproductor en el contexto del host que le corresponde antes de destruir esos contextos, y crea motores nuevos después de recargar un dispositivo o contexto. Las bibliotecas nativas nunca se descargan ni pueden cambiar de versión dentro de una JVM en ejecución.

## Requisitos de ejecución y configuración

La API Java está hecha para Java 17. Tu host aporta _WATERCoNFIG_, _Log4j_, _Gson_, _JOML_ y las bibliotecas gráficas y de audio que use.

- En Forge, NeoForge y Fabric, WATERMeDIA incluye _WATERCoNFIG_ como un JAR anidado.
- El launcher independiente extrae ese JAR a un archivo temporal, lo agrega al classpath de la JVM relanzada y descarga las demás bibliotecas de su lista cuando hacen falta; una biblioteca en caché o descargada sólo entra al classpath si coincide con el SHA-256 fijado al compilar.
- Una app que lo integra trae su propio classpath. Las dependencias de Gradle de desarrollo no demuestran que una instalación de Minecraft traiga las demás bibliotecas.
- El JAR de Binaries incluye y reubica su dependencia _XZ_.
- Forge, NeoForge y Fabric cargan WATERMeDIA en clientes y servidores dedicados, y rechazan las versiones de Minecraft fuera de la lista publicada.
- WATERMeDIA y Binaries se aceptan entre sí desde 3.0.0 hasta 3.1.0 (sin incluirla); las versiones BETA anteriores de 3.0.0 se rechazan. Fabric no puede limitar una dependencia a los clientes, así que su arranque exige Binaries en clientes y su manifiesto rechaza versiones incompatibles de Binaries.

Los arranques de Minecraft se niegan a iniciar cuando _TLauncher_ lanzó el juego. Lo detectan por su mod de skins inyectado, sus clases, sus archivos en el classpath, sus opciones de JVM, sus hilos y sus procesos padre, y fallan nombrando la evidencia; los restos de una instalación vieja en la carpeta del juego sólo dejan una advertencia en el log. Las apps que integran la API se saltan esta comprobación.

La configuración carga antes de que se preparen los nativos, así que revisa la configuración generada y el classpath real del host antes de culpar a un backend no disponible. Éstas son las llaves del `watermedia.toml` generado; los tamaños usan MiB (1,048,576 bytes), aunque la configuración diga MB.

| Ajuste | Valor predeterminado y límites relacionados |
| --- | --- |
| `codecs.ffmpeg.disable` | `false`; `true` omite la preparación y el arranque de _FFmpeg_. Los ajustes de arranque se aplican en el siguiente inicio. Las imágenes nunca dependen de _FFmpeg_. |
| `codecs.ffmpeg.customPath` | Vacío; un directorio con bibliotecas nativas compatibles, no un programa `ffmpeg.exe`. Se siguen exigiendo las capacidades parchadas de TLS y origen, y decodificar en un dispositivo Vulkan gemelo requiere la corrección de exportación de _FFmpeg_ (commit `63d0237033` de upstream), que ya traen los nativos incluidos. |
| `codecs.ffmpeg.hardwareAccel` | `true`; el uso real también depende del soporte nativo y del controlador, del códec y del motor de salida, y la decodificación por software sigue disponible. En un `VKEngine` cuyo `VKContext.deviceInfo()` describe un dispositivo creado para decodificar, H.264, H.265 y AV1 se decodifican en la GPU sin tocar la RAM (mira `PlayerAPI.vkEngine`). Los cuadros que no se pueden importar, y los de cualquier otro acelerador, se bajan a la RAM. |
| `codecs.ffmpeg.analyzeDuration` / `probeSize` | 7,000 ms / 10 MiB, para la entrada principal y la de audio separado. Una duración de cero conserva el valor predeterminado de _FFmpeg_. Valores más grandes pueden hacer más lento el inicio. |
| `codecs.maxImageSourceSize` | 128 MiB de datos de imagen codificada, aparte del límite de 512 MiB de datos decodificados del lector. |
| `player.watertx.texturesBudget` | 32 MiB. La precarga además necesita un motor capaz, una cantidad válida de cuadros, y que tanto los cuadros decodificados como las texturas de salida quepan en el presupuesto. Cero fuerza la reproducción de animaciones cuadro por cuadro. |
| `codecs.ffmpeg.cache` / `cacheMaxResponse` | Activada / 10 MiB por respuesta elegible; no guarda todas las transmisiones ni todas las entradas HLS/DASH. |
| `player.watertx.cache` / `player.cacheBudget` | Caché de imágenes activada / 8,192 MiB de presupuesto de disco compartido; las entradas más viejas se eliminan después de escribir. Los límites de disco, imagen codificada, imagen decodificada y texturas son independientes. |
| `platforms.allowMatureContent` | `false`; los adaptadores de plataforma compatibles pueden marcar un recurso como `BLOCKED` en lugar de pasarlo a reproducción. |
| `platforms.searchCleanupInterval` | 15 minutos; cero limpia las búsquedas en caché en cada búsqueda. |
| `platforms.searchLimit` | 2 resultados por plataforma; una búsqueda que pida más se recorta aquí. |

El servidor de archivos opcional sigue a `network.server`: `OFF` (el valor por defecto) nunca lo arranca, `DEDICATED` lo arranca en cualquier host sin cliente (servidores dedicados) y `ALWAYS` también en los clientes.

- Escucha en `127.0.0.1:25572`. Aceptar conexiones remotas exige una dirección explícita y un `network.token` personalizado y no vacío; un valor inválido hace fallar el arranque del servidor de archivos, que el módulo de red registra en el log mientras la carga termina igual.
- `network.remoteUrl` es la URL base HTTP(S) completa que usa todo el que sube archivos, clientes incluidos; no cambia la dirección donde escucha.
- El servidor integrado habla HTTP; el HTTPS remoto necesita una terminación TLS administrada por el host.
- Su raíz sólo responde el nombre del producto, y los ID de archivo usan minúsculas y dígitos para nombrar el mismo archivo en cualquier sistema de archivos.
- `GET` y `HEAD` respetan un único `Range` satisfacible; uno malformado o multiparte se ignora. Una solicitud que pide cerrar la conexión recibe `Connection: close` de vuelta, así que _FFmpeg_ abre una conexión nueva en cada seek.

Por defecto atiende ocho solicitudes a la vez, con 30 segundos de inactividad por solicitud, cargas de 8 MiB y 1,024 MiB de almacenamiento total.

- El plazo de inactividad sólo corta una solicitud tras ese tiempo sin encabezados ni avance de la transferencia, así que una descarga larga en un enlace lento termina y una colgada se cae.
- `maxUploadSize = 0` sólo quita el límite por carga; la cuota de almacenamiento, la concurrencia y el plazo de inactividad siguen aplicando. Un almacenamiento lleno rechaza las cargas con `507`, con una advertencia en el primer rechazo de cada arranque del servidor, hasta que se borren archivos a mano.
- `NetworkAPI.upload` devuelve un `UploadStatus` por archivo: `uri()` es la dirección `water://remote/<id>` cuando el servidor lo acepta, y `cancel()` aborta una transferencia en curso, dejando el estado como fallido.

Las solicitudes de red Java, las descargas de listas de reproducción y el plazo de socket de _yt-dlp_ comparten `network.timeout` (15 segundos), diez redirecciones y un límite de 16 MiB para cuerpos de texto. Las entradas nativas de reproducción usan su propia política de espera.

## Paquetes públicos e internos

La API pública son los paquetes `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` y `api.util`, sin sus subpaquetes. Todo lo demás es interno: lectores, codificadores y estructuras de chunks de los códecs, colas y cachés de los reproductores, las plataformas integradas, el arranque y los bindings de _FFmpeg_ incluidos. Binaries también usa dos paquetes internos: `org.watermedia.tools` para sus utilerías de descarga, digestos y JSON, y `org.watermedia.bootstrap` para el ciclo de vida de módulos que implementa.

El JAR no declara un módulo de Java, así que ningún loader bloquea los paquetes internos; aun así, trata esa frontera como el contrato y llega a las funciones internas por sus puntos de entrada, como `PlayerAPI.player` y `PlatformAPI.fetch`. Ejecuta `gradle javadocInventory` desde la raíz del proyecto para listar la documentación faltante de métodos públicos con nombre de archivo y número de línea; sólo reporta, y sólo revisa los paquetes públicos listados en `public_packages` de `gradle.properties`.

> [!WARNING]
> `CodecsAPI` es experimental: `decode`, `reader`, `sniff` y `encoder`, junto con los lectores, codificadores y filtros que entregan, pueden cambiar o romperse en cualquier actualización, así que no los uses en tus mods todavía. Los reproductores ya usan los códecs por ti, y las consultas de estado (`ffmpegLoaded()`, `ffmpegError()`, `vulkanDecode()`) son seguras.

## MRL y resolución de direcciones

```java
MRL mrl = PlayerAPI.mrl("https://example.com/movie.mp4");
MRL[] playlist = PlayerAPI.preload(firstUri, secondUri);
```

Un MRL es un localizador multimedia en caché. Una URI puede resolverse en varias fuentes multimedia, y cada fuente puede tener variantes de calidad y pistas de audio separadas. La resolución corre en segundo plano:

- Consulta `mrl.status()` desde un tick del juego. Termina en `LOADED`, `ERROR` o `BLOCKED`; si falla, revisa `mrl.exception()`.
- `FETCHING` significa que sigue resolviendo, `EXPIRED` marca fuentes caducadas y `FORGOTTEN` un recurso descartado.
- `mrl.await(timeoutMillis)` es para código de fondo o de app; nunca bloquees el hilo del juego con él. `true` significa que la resolución terminó, fallas incluidas, no que el contenido se pueda reproducir. Devuelve `false` si se agota la espera o se interrumpe el hilo.
- Al recargar, quédate con el MRL que te devuelve; una recarga pedida mientras corre una carga se programa para cuando esa carga termine.

```java
if (mrl.status() == MRL.Status.LOADED) {
    List<MRL.Source> sources = mrl.sources();
    int count = mrl.sourceCount();
    MRL.Source first = mrl.source(0);
    MRL.Source firstVideo = mrl.sourceByType(MediaType.VIDEO);
    List<MRL.Source> videos = mrl.sourcesByType(MediaType.VIDEO);
}
```

`source(index)` devuelve null cuando esa fuente no está disponible o el índice es inválido, y `sourceByType(...)` devuelve la primera coincidencia o null, mientras que `sources()` y `sourcesByType(...)` devuelven listas (vacías cuando no hay resultados). `MediaType` vive en `org.watermedia.api.util`, no en `MRL`. Recarga con `mrl = mrl.reload()`: una entrada de caché olvidada devuelve un MRL nuevo y el viejo se queda olvidado.

`PlayerAPI.mrl(String)` lee una cadena que nombra un archivo existente como una ruta relativa al directorio de trabajo con el que arrancó WATERMeDIA, e interpreta cualquier otra cosa como URI. Las entradas malformadas, relativas o vacías devuelven un MRL sin caché nacido en `ERROR` cuyo `reload()` se devuelve a sí mismo. Un enlace directo sólo se rechaza cuando el servidor responde con un tipo de documento (HTML, CSS, JavaScript, JSON, XHTML); cualquier otro tipo de contenido pasa, sin importar mayúsculas o parámetros, y los ambiguos se clasifican leyendo sus primeros bytes. Cada fuente conserva una URL por nivel de calidad (la variante más ancha, luego la más alta, luego la de mayor tasa de cuadros, luego la primera que listó la plataforma) y sus encabezados quedan congelados.

Los índices empiezan en cero: una galería puede tener una imagen en 0, un video en 1 y una animación en 2, así que pasa 1 a `PlayerAPI.player` para reproducir el video. Si omites el índice, se elige la primera fuente.

Los handlers de plataforma comparten un contrato:

- `Platform.resolve` devuelve `null` sólo ante un host o esquema ajeno. Una URL de su propio host que no puede reproducir (un perfil, una carpeta, el host a secas) falla con `PlatformException` ("Unsupported URL") sin tocar la red, y cualquier otra falla, esperas interrumpidas incluidas, también es una `PlatformException`, así que `MRL` reporta el mensaje del propio handler.
- El contenido maduro se bloquea con `MatureContentException` donde la plataforma expone una marca o etiqueta (Imgur, Kick, Twitch, Bluesky, PornHub, las etiquetas de Odysee, las clasificaciones de Newgrounds, _yt-dlp_, y las verificaciones de edad de Twitter y YouTube), y `MRL` lo convierte en `BLOCKED`.
- Un álbum o una lista de reproducción salta un elemento malo con una advertencia y sólo falla cuando no queda nada.
- Los handlers agregados con `PlatformAPI.register` se consultan antes que los integrados, del más nuevo al más viejo, y no necesitan arranque: regístralo una vez, antes o después de `WaterMedia.start`, y el reemplazo se mantiene hasta `unregister`, que también quita un handler integrado por el resto del proceso.

Buscar funciona así:

- `PlatformAPI.search(query)` devuelve de inmediato la lista de sólo lectura donde van cayendo sus resultados, hasta `platforms.searchLimit` por plataforma en orden de respuesta (menos con `search(query, limit, reSearch)`). `PlatformAPI.searching(query)` pasa a falso cuando todas las plataformas respondieron; consulta ambos desde el hilo de la interfaz.
- Corre una búsqueda a la vez sobre cuatro hilos de trabajo, y cada petición de búsqueda integrada se rinde a los 5 segundos.
- Twitch y Kick sólo listan canales que están en vivo en ese momento, porque un canal desconectado no tiene nada que reproducir.
- Un query nuevo descarta el que está en curso. Un query a una tecla del anterior (una letra más, menos o corregida mientras el usuario escribe) espera 300 ms a la siguiente tecla antes de tocar la red; cualquier otro query sale de inmediato.
- Un query ya buscado con el mismo límite recibe la misma instancia de lista desde la caché, terminada o aún llenándose, salvo que `search(query, true)` vuelva a preguntar a las plataformas.
- Registrar un handler descarta las búsquedas en caché, y una búsqueda en blanco devuelve una lista vacía y deja en paz la que esté en curso.

`Metadata` significa lo mismo en todas partes: `title` nombra el contenido (un video, transmisión, publicación o clip, nunca la cuenta), `description` lleva su leyenda, texto o categoría, y `author` el canal, quien lo subió o el streamer. Los enlaces firmados de CDN caducan cuando lo dice su parámetro `expire`, `expires`, `validto` o `x-expires`; el resto tiene una vigencia por plataforma o ninguna.

## Creación y control de reproductores

```java
MediaPlayer player = PlayerAPI.player(mrl, 0,
        () -> PlayerAPI.glEngine(renderThread, renderExecutor),
        PlayerAPI::jsEngine);
if (player == null) {
    throw new IllegalStateException("Source is unavailable or its backend failed");
}
try {
    if (!player.start()) throw new IllegalStateException("Player refused to start");
} catch (RuntimeException | Error failure) {
    player.release();
    throw failure;
}
```

`player` devuelve null cuando la fuente sigue resolviéndose, el índice es inválido, el backend necesario no está disponible o la construcción lanza una `Exception`; un `Error` se propaga. Revisa el estado del MRL antes de decidir si reintentas.

- Los proveedores sólo corren cuando hacen falta, y las imágenes nunca llaman al proveedor de audio.
- Un proveedor que devuelve null omite esa salida, como `() -> null` de motor gráfico para reproducir sólo audio.
- Nunca compartas un motor entre reproductores.

`TxMediaPlayer` reproduce imágenes y animaciones compatibles, `FFMediaPlayer` reproduce audio y video con _FFmpeg_, y `ServerMediaPlayer` es un reloj de sincronización sin decodificación ni motores nativos.

```java
player.pause(true);
player.pause(false);
player.seek(15_000);
player.volume(50);
player.mute(true);
player.speed(1.25f);
player.repeat(true);
player.scale(1280, 720);
```

`speed(value)` acepta valores finitos en `(0, 4]` e informa si se aceptó; Java Sound no puede cambiar la velocidad de reproducción. `canSpeed()` es una consulta pura que cualquier hilo puede hacer en cada cuadro, mientras que `speed(value)` debe correr en el contexto de audio dueño, y el reloj del reproductor sólo sigue una velocidad que el motor de audio aceptó. `spatialAudio(...)` también informa si el motor aceptó la actualización; revisa `spatial()` antes de mostrar controles posicionales.

Los tiempos van en milisegundos y el volumen es un porcentaje de 0 a 100. Revisa `canSeek()` en transmisiones que no permiten seek, y consulta `status()`, `time()`, `duration()`, `buffered()` y `exception()` para diagnóstico. El escalado y el nivel de detalle funcionan en los formatos de píxel compatibles, y los motores gráficos integrados todavía no aceptan texturas BC comprimidas. Llama a `player.release()` cuando el host quite la superficie de reproducción, también si la reproducción falló.

Cada control booleano responde una sola pregunta: ¿esta llamada cambió el estado propio del reproductor? `false` cubre llamadas que el contenido rechazó, llamadas ya satisfechas y llamadas después de `release()`; `repeat(boolean)` sigue la misma regla. Los tres reproductores comparten un contrato:

- Una pausa durante la carga queda registrada y se aplica cuando abre la tubería.
- `pause()` y `resume()` devuelven `false` en un reproductor terminado, detenido o fallido.
- `stop()` desmonta la tubería, queda en `STOPPED` con `time()` en 0 y devuelve `true` sólo si hubo una transición.
- `seek(...)` sobre un reproductor terminado o detenido lo reinicia en pausa en la posición pedida, y `togglePlay()` ahí lo vuelve a iniciar.
- `nextFrame()` y `previousFrame()` sólo funcionan en pausa.
- `canPlay()` es verdadero desde la primera carga exitosa hasta que el reproductor falla o se libera; `canSeek()` y `live()` son falsos hasta que la fuente abre; `fps()` es 0 hasta conocer la tasa; `status()` es `STOPPED` después de `release()`.
- Una imagen fija mostrada con _FFmpeg_ respeta `displayTime(ms)` como el reproductor de imágenes y nunca se repite; sin tiempo de exhibición se queda en pantalla hasta que la detengas.

`onStatus(listener)` agrega un `MediaPlayer.StatusListener` que recibe el reproductor, el estado anterior y el actual en cada transición real, incluidas las que causan un reinicio, `stop()` y `release()`.

- Los listeners se acumulan (agregar uno dos veces lo deja una sola vez), y la transición a `STOPPED`, `ENDED` o `ERROR` los consume. Un consumidor de playlist se registra una vez por reproducción y, cuando reinicia el reproductor desde ese callback terminal, se vuelve a registrar desde el propio callback.
- Las transiciones llegan de una en una, en el orden en que cambiaron los estados, aunque varios hilos operen el reproductor. Corren en el hilo que causó el cambio: un hilo de reproducción, el ticker compartido de sincronización, o quien llama a `status()`, `time()` o `texture()` en reproductores de imagen cuyo reloj resuelve el final de forma diferida. Mantén los listeners cortos y nunca llames a `release()` desde uno.
- Un listener nunca corre mientras el reproductor tiene tomado uno de sus candados, ni en un hilo de reproducción al que `release()` está esperando. `release()` entrega su `STOPPED`, más lo publicado mientras desmontaba, cuando termina y los motores ya están liberados, así que ese callback puede esperar a otro hilo que use el reproductor; cualquier otro callback debe ser corto y nunca bloquear.
- `release()` es idempotente.

| Estado del reproductor | Significado |
| --- | --- |
| `WAITING` | Creado y esperando las condiciones para arrancar. |
| `LOADING` | Cargando o preparando el contenido. |
| `BUFFERING` | Esperando suficientes datos para continuar. |
| `PLAYING` | Reproduciendo el contenido. |
| `PAUSED` | En pausa y listo para reanudar. |
| `STOPPED` | Detenido y listo para empezar desde el principio. |
| `ENDED` | Llegó al final del contenido. |
| `ERROR` | La reproducción tuvo una falla. |

`Status.allows(next)` dice si una pausa, reanudación, almacenamiento en búfer, final, detención o falla puede llevar un estado directo a `next`. Arrancar, hacer seek y reiniciar van por su propio camino: un reproductor sale de `LOADING` sólo cuando abre su tubería (o se detiene o falla), un seek en `ENDED` o `STOPPED` lo reinicia, y `STOPPED` no permite ningún movimiento directo.

### Pistas de audio e idiomas

Los videos doblados y los archivos con varios streams de audio reproducen su audio original (o el predeterminado) a menos que pidas otro. Las plataformas listan las pistas de audio separadas de una fuente con la original primero, y dentro de un archivo o un stream HLS gana el stream marcado como predeterminado.

```java
player.audioLanguage("es-MX"); // PREFERIDO DESDE EL SIGUIENTE ARRANQUE; null LO QUITA
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // CAMBIA AL MOMENTO EN UN REPRODUCTOR QUE CORRE
}
```

- El orden de elección es: tu elección con `audioTrack(...)`, luego `audioLanguage(...)`, luego el audio original o predeterminado y al final el primero. Una etiqueta de idioma coincide primero con la misma etiqueta y luego con el mismo idioma, así que `es-MX` toma `es-419`, `es` o el `spa` que trae un archivo. No hay idioma automático: sin `audioLanguage(...)` suena el original, nunca un doblaje automático.
- `audioTracks()` lista un `AudioTrack(track, stream, name, lang)` por cada stream de audio: primero los de la entrada principal y luego los de cada pista separada de `source().audioTracks()`. `track` es `AudioTrack.MAIN` o el índice de la pista separada, y `stream` es el índice del stream de _FFmpeg_ dentro de ella.
- Una pista separada que todavía no ha sonado aparece como una sola entrada `provisional()` (`stream` vale -1) armada con el nombre y el idioma de la plataforma, así que listar nunca abre todos los doblajes por adelantado. Cuando suena, sus streams reales toman su lugar. La lista es una instantánea inmutable que cualquier hilo puede leer.
- `audioTrack(track)` devuelve `true` cuando la elección cambió. Un reproductor que corre reconstruye su tubería en el tiempo actual (una transmisión en vivo vuelve a su borde), y la elección sobrevive a los cambios de calidad y a los reinicios. `audioTrack()` devuelve lo que suena ahora. Los reproductores de imágenes y de servidor, y los que no tienen motor de audio, no listan nada.

## HTTPS y certificados

La reproducción HTTPS necesita las capacidades nativas de verificación de la compilación incluida o de una personalizada compatible. _FFmpeg_ verifica las cadenas de certificados y las identidades DNS/IP contra una copia del truststore de la JVM tomada al arrancar.

- Configura las propiedades estándar `javax.net.ssl.trustStore` antes de arrancar WATERMeDIA si necesitas autoridades certificadoras privadas.
- Un truststore vacío o inválido impide que _FFmpeg_ se inicialice; la verificación nunca se apaga en silencio.
- Un `customPath` vacío no agrega el directorio de trabajo a la búsqueda de nativos.

Las autoridades exportadas también llegan a las solicitudes HLS/DASH anidadas. Esto comparte las anclas de confianza con Java; no reemplaza el transporte _OpenSSL_ de _FFmpeg_. Cuando el módulo de códecs arranca _FFmpeg_, revisa que existan las opciones nativas requeridas y escribe el PEM temporal; `FFMediaPlayer` las aplica a la entrada principal y a la de audio separado de cada reproductor que abre, y nada en la API pública toca esa configuración. Las fábricas de sockets JSSE personalizadas, los callbacks de verificación de hostname y las políticas de revocación no pasan a _OpenSSL_, y las listas de protocolos permitidos se quedan como están. El paquete temporal de CA vive todo el proceso y se borra cuando la JVM sale.

Las credenciales de reproducción pertenecen al origen de la primera solicitud: esquema, nombre de host y puerto efectivo. Las redirecciones y las solicitudes hijas de HLS/DASH envían `Authorization`, `Proxy-Authorization`, `Cookie`, `Cookie2` y `X-WaterMedia-Token` sólo a ese origen. Una solicitud a otro origen arma su propio encabezado `Host`, mientras que los encabezados comunes como `User-Agent`, `Accept`, `Referer` y `Origin` siguen disponibles para las CDN.

Las cookies generadas respetan el mismo límite: las cookies del `Set-Cookie` de otro origen se ignoran, aunque una redirección regrese después al servidor original. Es una política de credenciales de un solo origen, así que un servidor redirigido no puede abrir su propia sesión de cookies dentro de esa entrada de reproducción. Los logs HTTP nativos omiten las solicitudes completas, los volcados de encabezados y los valores de cookies.

`NetRequest` aplica el mismo límite a los cuerpos de solicitud: una redirección a otro origen falla en lugar de reenviar el cuerpo, salvo que se haya configurado con `body(body, true)`. Una respuesta 303 se vuelve a pedir como `GET` sin cuerpo. Una solicitud se envía una sola vez: configúrala, haz `send()` y lee su respuesta en un mismo hilo.

Las redirecciones sólo se mueven entre http y https, en cualquier dirección. Un `Location` que apunte a un recurso `file:`, `jar:` o `ftp:` hace fallar la solicitud, y sólo la primera solicitud puede nombrar un archivo local. Las URLs tomadas del JSON de las plataformas también deben ser enlaces web. Las entradas de playlist pueden usar cualquier esquema de streaming que hable _FFmpeg_ (`rtsp`, `rtmp`, `udp`, `srt`), pero nunca un archivo local ni un archivo comprimido, salvo que la playlist misma sea un archivo local.

Más reglas de `NetRequest`:

- Los encabezados que fija quien llama ganan sobre los predeterminados, `User-Agent` incluido, y el `Referer` derivado conserva un puerto explícito.
- `inputStream()` y `text()` devuelven el cuerpo de un estado de error en lugar de lanzar, y el cuerpo sólo se puede leer una vez.
- `json(type)` acepta todos los tipos de contenido JSON (`application/json`, `text/json`, subtipos `+json`).
- Un cuerpo de texto que llega a cuentagotas se detiene después de agotar cuatro plazos de lectura en total.
- Ni el builder ni la respuesta son seguros entre hilos.

## Selección de motores

El desarrollo de Vulkan va primero; OpenGL sigue soportado como backend secundario. Las decisiones de backend pertenecen a tu capa de renderizado.

La app independiente dibuja con Vulkan salvo que el usuario elija OpenGL; cuando Vulkan no está disponible, corre ese arranque en OpenGL y conserva la elección guardada. `-Dwatermedia.engine=opengl` o `-Dwatermedia.engine=vulkan` cambia el motor para un solo arranque sin guardarlo. La app guarda su motor, la escala de la interfaz (`0` sigue al monitor; si no, un factor de 1 a 3 en pasos de 0.25, que Settings muestra como `Auto`, `x1.0`, `x1.25`, etc.), el efecto CRT y el sonido de clic en `config/app.properties` dentro de su directorio de trabajo.

| Fábrica | Obligación del host |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | El ejecutor corre tareas en el hilo dueño del contexto OpenGL y se sigue procesando mientras la reproducción o la liberación puedan esperarlo. |
| `PlayerAPI.vkEngine(context)` | Proporciona un `VKContext` cuyos objetos Vulkan y mecanismo de retiro sobrevivan al motor. Para decodificar video en la GPU, sobrescribe `deviceInfo()` con la cadena de features, las extensiones y la cantidad de colas del dispositivo (mira el README). _FFmpeg_ decodifica en ese dispositivo cuando le sobran colas (entonces el host nunca las toca ni llama a `vkDeviceWaitIdle` mientras existan reproductores), o en un dispositivo gemelo cuando tu dispositivo activa las extensiones de memoria y semáforos externos (`win32` en Windows, `fd` en los demás); libera todos los reproductores antes de destruir el dispositivo o su instancia. `CodecsAPI.vulkanDecode()` dice si los nativos lo soportan. _libplacebo_ convierte cualquier formato decodificado a RGBA en SDR (con mapeo de tonos del HDR) cuando el dispositivo activa `hostQueryReset`, `runtimeDescriptorArray`, `shaderStorageImageArrayNonUniformIndexing` y `maintenance4` y, en tu propio dispositivo, la familia de `queue()` tiene una segunda cola; un gemelo activa esas features por su cuenta cuando la GPU las tiene. Sin _libplacebo_, sólo los videos 4:2:0 se decodifican en la GPU. |
| `PlayerAPI.awtEngine(onFrame)` | Dibuja la imagen publicada desde la interfaz AWT/Swing; pasa el callback opcional al hilo de interfaz cuando haga falta. |
| `PlayerAPI.jfxEngine(onFrame)` | Necesita un runtime de JavaFX 13+; enlaza la imagen del motor con tu interfaz. Sin él, la fábrica lanza `IllegalStateException` en lugar de un error de enlace. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Captura los cuadros subidos sin contexto gráfico, útil para pruebas y procesamiento de imágenes. Como todo motor y reproductor multimedia, sólo existe en un cliente; un servidor dedicado sólo puede crear relojes `ServerMediaPlayer`. |
| `PlayerAPI.alEngine()` | El contexto OpenAL ya está activo y sigue válido en los hilos que hacen trabajo de audio. |
| `PlayerAPI.alEngine(true)` | El mismo contrato de OpenAL, reproduciendo con sinks: fuentes mono posicionales (pares estéreo y bocinas extra). |
| `PlayerAPI.jsEngine()` | Hay un dispositivo y una línea de Java Sound disponibles; sin audio posicional. |

El audio espacial usa `SpatialAudio` y un procesador de entorno opcional; consulta [audio espacial y Sound Physics Remastered](#audio-espacial-y-sound-physics-remastered) para el adaptador, la propiedad de la fuente y el contexto, la frecuencia de actualización y las condiciones que habilitan los efectos.

Las clases base de los motores son selladas: tu integración aporta contextos y ejecutores del host, no implementaciones propias de motores. Los motores administran sus propios recursos de reproducción, incluidas texturas, fuentes de audio y búferes.

Todo motor sigue el mismo ciclo de vida:

- `release()` es idempotente, nunca debe correr al mismo tiempo que el reproductor que alimenta al motor, y lo deja inerte: `released()` es verdadero y `texture()` devuelve 0.
- El reproductor revisa `supports(format, bits)` antes de subir y cae a BGRA de 8 bits cuando el motor rechaza una disposición.
- Las subidas nunca lanzan. Una falla del controlador sólo detiene el motor con un error en el log mientras la última textura sigue expuesta, y un cambio de resolución en Vulkan conserva la vista anterior hasta que se complete el primer cuadro de la nueva disposición.
- Los motores de software y headless exponen un centinela distinto de cero en lugar de un identificador de GPU, así que compara el identificador sólo contra 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` y `frameBytes` dan el tamaño de cada plano del `format()`, `width()`, `height()` y `bits()` de un motor, por ejemplo para presupuestar su memoria. Los planos submuestreados redondean hacia arriba como _FFmpeg_, así que los tamaños impares conservan su última columna y fila de croma.

Manejo del color:

- Los motores OpenGL y Vulkan convierten el YUV con la matriz (BT.601, BT.709 o BT.2020), el rango y la posición del croma que declara cada video. Cuando no declara nada, lo deducen como _mpv_: BT.601 para cuadros SD (de menos de 1280 píxeles de ancho y hasta 576 líneas de alto), BT.709 en los demás.
- El HDR en PQ y HLG se muestra como SDR, con los colores de BT.2020 llevados a BT.709 y las luces altas comprimidas para que un pico de 1000 nits llegue al blanco.
- Los motores de un mismo contexto OpenGL o dispositivo Vulkan construyen esa conversión una vez y la comparten. Desaparece con el trabajo de liberación del último motor (la tarea que `release()` encola en el ejecutor de render de OpenGL, o los callbacks de `VKContext.retire`), así que libera todos los motores y deja correr ese trabajo antes de destruir el contexto o el dispositivo.
- Los motores de software reciben BGRA que _FFmpeg_ convierte con la matriz y el rango declarados, sin mapeo de tonos HDR.

OpenAL necesita su contexto original activo en cada hilo que opera el reproductor, incluido el ticker de sincronización. Java Sound es seguro entre hilos y reabre una línea más ancha cuando un bloque de audio supera el búfer de la línea, descartando lo que la línea vieja aún tenía.

## Audio espacial y Sound Physics Remastered

Crea un motor OpenAL con `PlayerAPI.alEngine(true)` antes de construir el reproductor. El modo espacial queda fijo durante toda la vida del motor: _FFmpeg_ lleva las fuentes estéreo y envolventes a estéreo (el canal central llega a ambos lados y las fuentes mono siguen en mono) y el motor las reproduce con sinks, que son fuentes mono posicionales. La fábrica `alEngine()` normal reproduce todos los canales en una sola fuente seca y relativa al oyente, aunque los formatos que el dispositivo no puede reproducir aún pueden convertirse a la cantidad de canales más cercana. Java Sound devuelve `false` en las actualizaciones espaciales y no expone una fuente nativa.

El motor arranca con un solo sink, el primario, que reproduce audio `Mix.MONO` seco y relativo al oyente. Pasa un estado `SpatialAudio` para ubicarlo en el mundo, o `null` para volver a la reproducción seca y relativa al oyente. El volumen, el silencio, la velocidad, la pausa y la sincronización no dependen de la configuración espacial y llegan a todos los sinks.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Crea el motor, actualízalo y libéralo en el ejecutor de sonido del host, con el contexto OpenAL original activo. El host es dueño del dispositivo, el contexto y el oyente, y éstos deben sobrevivir a todos los reproductores que los usan.

- `FFMediaPlayer` usa hilos de reproducción privados y, como la reproducción ordinaria con `ALEngine`, actualmente necesita que el contexto de la fuente sea el contexto activo del proceso en esos hilos. No se admiten hosts que expongan su contexto de audio sólo mediante una vinculación local al hilo: vincularlo sólo en el hilo de sonido de quien llama no basta.
- Las operaciones nativas del motor rechazan un contexto distinto o ausente; nunca cambian ni recrean el contexto del host.
- Libera los reproductores antes de recargar el dispositivo y luego crea motores nuevos en el contexto nuevo.
- `FFMediaPlayer.release()` interrumpe y espera a su tubería de reproducción antes de borrar la fuente de audio; córrelo antes de destruir el contexto, nunca como callback de esa tubería.

`SpatialAudio` valida posiciones finitas que quepan en un float de OpenAL, `0 < referenceDistance < maxDistance` y un rolloff finito y no negativo; un rolloff de cero apaga la atenuación por distancia. El modelo de distancia del host decide cómo atenúan el audio estos parámetros, y la API nunca cambia el modelo global de distancia, el oyente ni la velocidad de la fuente, así que `maxDistance` no es un radio de silencio universal en todos los modelos de OpenAL.

### Sinks: pares estéreo y bocinas extra

Una sola decodificación alimenta todos los sinks de un motor espacial, así que una imagen estéreo o una bocina puesta en otro lugar no cuesta una segunda descarga ni decodificación. Cada sink reproduce una `ALEngine.Mix` del audio decodificado: `LEFT`, `RIGHT` o `MONO` (el promedio de ambos). Una fuente mono suena igual en todas las mezclas, así que un par estéreo nunca se queda callado con medios mono.

```java
// UNA IMAGEN ESTÉREO: EL PRIMARIO PASA AL BORDE IZQUIERDO Y UN SINK NUEVO REPRODUCE EL DERECHO
final ALEngine.Sink left = player.sinks().get(0);
left.mix(ALEngine.Mix.LEFT);
final ALEngine.Sink right = player.addSink(ALEngine.Mix.RIGHT);
left.spatialAudio(new SpatialAudio(leftX, y, z, 8.0f, 64.0f, 1.0f, false, environment));
right.spatialAudio(new SpatialAudio(rightX, y, z, 8.0f, 64.0f, 1.0f, false, environment));

// UN BLOQUE BOCINA: UN SINK MÁS EN SU PROPIA POSICIÓN, LIBERADO CUANDO EL BLOQUE DESAPARECE
final ALEngine.Sink speaker = player.addSink(ALEngine.Mix.MONO);
speaker.spatialAudio(new SpatialAudio(speakerX, speakerY, speakerZ, 8.0f, 64.0f, 1.0f, false, environment));
speaker.release();
```

- El sink primario es dueño de `player.audioSource()`. Lleva el reloj del medio y vive lo mismo que el motor; su `release()` devuelve `false`. `player.sinks()` lista primero al primario, y `sinks()` y `addSink(...)` dan una lista vacía o `null` sin un motor OpenAL espacial.
- Todos los sinks encolan las mismas muestras, y reproducir, pausar, vaciar, la velocidad y el volumen corren en un solo lote sobre todos, así que un par estéreo queda alineado muestra por muestra. Agregar un sink o cambiar una mezcla durante la reproducción reconstruye todas las colas sobre la muestra del primario, lo que pausa los sinks cerca de una actualización del mezclador.
- Cada sink tiene su propio `spatialAudio(...)`: el adaptador de entorno recibe la fuente de ese sink en `process` y `reset`, y `sink.release()` lo reinicia antes de borrar la fuente. Liberar el reproductor libera todos los sinks.
- Agrega, cambia y libera sinks en el ejecutor de sonido del host con el contexto del motor activo, como cualquier otra actualización espacial. Sus consultas (`source()`, `mix()`, `spatialAudio()`, `released()`) no necesitan contexto.
- Un host puede adoptar la fuente de un sink en su propio sistema de sonido y escribir su ganancia, tono y posición, pero nunca debe reproducirla, encolar, desencolar ni borrarla: el motor es dueño de su transporte y sus búferes. Un sink nuevo arranca con la ganancia y el tono actuales del motor.

### Adaptador para Sound Physics Remastered

La referencia revisada para esta integración fue `sound-physics-remastered-1.21.1.zip`, que trae código fuente y no un JAR compilado. Su punto de entrada moderno es:

```java
SoundPhysics.processSound(source, x, y, z, category, soundId, auxOnly);
```

Escribe un adaptador pequeño en el mod de Minecraft que lo consume: WATERMeDIA deja fuera de su API las clases de Minecraft y de _Sound Physics_ a propósito. El host debe cargar el adaptador sólo cuando _Sound Physics Remastered_ esté presente e inicializado.

```java
final ResourceLocation soundId = ResourceLocation.fromNamespaceAndPath("waterframes", "media");
final SoundSource category = SoundSource.MASTER;
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        SoundPhysics.processSound(source, audio.x(), audio.y(), audio.z(),
                category, soundId, audio.auxOnly());
    }

    @Override
    public void reset(final int source) {
        SoundPhysics.setDefaultEnvironment(source, false);
    }
};

// INSIDE THE HOST SOUND EXECUTOR, AFTER ITS CONTEXT, SOUND PHYSICS AND MRL ARE READY.
final MRL.Source media = mrl.source(0);
if (media == null || (media.type() != MediaType.AUDIO && media.type() != MediaType.VIDEO)) {
    throw new IllegalStateException("A resolved audio or video source is required");
}
final MediaPlayer player = PlayerAPI.player(mrl, () -> null, () -> PlayerAPI.alEngine(true));
if (player == null) throw new IllegalStateException("The media backend could not create a player");
try {
    player.spatialAudio(new SpatialAudio(sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment));
    if (!player.start()) throw new IllegalStateException("The player refused to start");
} catch (final RuntimeException | Error failure) {
    player.release();
    throw failure;
}

// SUBMIT FROM THE HOST'S TICK INTEGRATION; THE EXECUTOR MUST SERIALIZE SOUND PHYSICS CALLS.
soundExecutor.execute(() -> player.spatialAudio(new SpatialAudio(
        sourceX, sourceY, sourceZ, 8.0f, 64.0f, 1.0f, false, environment)));

// BEFORE THE CONTEXT IS DESTROYED, ON THE SAME SOUND EXECUTOR.
soundExecutor.execute(player::release);
```

El `soundExecutor`, el `mrl` resuelto y las coordenadas del mundo del ejemplo vienen del mod consumidor. Sólo reproduce la pista de audio, así que el proveedor de video que devuelve `null` es intencional. El proveedor de audio crea un motor sólo si la fábrica lo necesita, y la fábrica lo libera si la construcción del reproductor lanza una `Exception`. Para mostrar también el video, pasa un proveedor de video que respete la propiedad del contexto de renderizado, y conserva la propiedad de cualquier motor creado por adelantado hasta que la fábrica lo consuma. La llamada moderna a `processSound` pasa la categoría y el identificador directamente; la pareja heredada `setLastSoundCategoryAndName` más `onPlaySound` depende de metadatos mutables compartidos y no hace falta aquí.

Conserva una sola instancia estable del adaptador entre actualizaciones.

- Cada actualización restaura la posición original en el mundo antes de llamarlo, porque _Sound Physics_ puede reemplazar `AL_POSITION` con el origen de un sonido reflejado.
- También limpia los filtros de la fuente y los envíos auxiliares anteriores, para que un procesador desactivado o reemplazado no deje una oclusión vieja. El procesador debe aplicar su entorno completo en cada llamada.
- Limita la frecuencia de las llamadas a `spatialAudio(...)` en el host, en lugar de regresar antes dentro del procesador.
- `reset` corre al reemplazar o quitar un adaptador y durante la liberación. El motor borra sólo su propia fuente y sus búferes, nunca los filtros compartidos ni las ranuras de reverberación de _Sound Physics_.
- Si el procesamiento o el reset lanzan una excepción, los efectos de la fuente se limpian y la excepción se propaga; un reset que falla no puede impedir que se borre la fuente nativa durante la liberación.

Refresca los medios de larga duración cuando cambien el oyente, la fuente o el mundo, incluidas las fuentes inmóviles cuando el oyente se mueve. Un buen punto de partida es el `soundUpdateInterval` configurado por el mod, de cinco ticks, más actualizaciones inmediatas para fuentes nuevas y cambios de posición. Reparte varios reproductores entre ticks y respeta los límites de frecuencia de sonido; no hace falta lanzar rayos por cada paquete de audio decodificado. La API sólo llama al adaptador durante actualizaciones explícitas del host, nunca desde el bucle de decodificación o reproducción de _FFmpeg_.

Requisitos de _Sound Physics_ y ajustes relacionados, verificados en el código fuente revisado:

| Requisito o condición | Efecto en la integración | Fuente dentro del ZIP |
| --- | --- | --- |
| El host inicializa _Sound Physics_ en el contexto de la fuente | Los ID de sus filtros y reverberación compartidos deben pertenecer al mismo contexto; nunca llames `SoundPhysics.init()` por cada reproductor | `SoundPhysics.java:66`, `:85`; `mixin/SoundSystemMixin.java:27` |
| EFX y cuatro envíos auxiliares | Pide `ALC_MAX_AUXILIARY_SENDS = 4` cuando el host cree el contexto; menos envíos significan menos rutas de reverberación | `mixin/LibraryMixin.java:18`; `SoundPhysics.java:97`, `:631` |
| `enabled` | Tanto el procesamiento como `setDefaultEnvironment` no hacen nada cuando está desactivado, así que WATERMeDIA limpia por su cuenta las conexiones EFX de su fuente | `SoundPhysics.java:189`, `:625` |
| `updateMovingSounds = false` por defecto | La categoría `RECORDS` queda excluida; elige la categoría deseada y activa el procesamiento de sonidos en movimiento si los discos necesitan efectos | `SoundPhysics.java:223`; `config/SoundPhysicsConfig.java:150` |
| Mundo inicializado, jugador y nivel disponibles | Hasta que estén listos, el mod restaura el entorno predeterminado | `SoundPhysics.java:208`, `:230` |
| Posición exacta `(0, 0, 0)` | El mod la trata como entorno predeterminado; WATERMeDIA la acepta, pero no puede obligar al mod a procesarla | `SoundPhysics.java:211` |
| `maxSoundProcessingDistance = 512` por defecto | Las fuentes lejanas reciben el entorno predeterminado | `SoundPhysics.java:216`; `config/SoundPhysicsConfig.java:158` |
| Límites de frecuencia de sonido por identificador y ajustes de sonido ambiental | Las llamadas pueden recibir un entorno predeterminado aunque la fuente y el contexto sean válidos | `SoundPhysics.java:236`, `:242` |
| Factor de atenuación | Para igualar su mixin de atenuación lineal de Minecraft, divide el alcance deseado entre `attenuationFactor` mientras esté activado y pon la distancia de referencia en la mitad de ese alcance; el host debe elegir el modelo de distancia lineal correspondiente para la fuente | `mixin/SourceMixin.java:42` |

`auxOnly = true` le pide al adaptador una salida sólo de reflexiones y necesita un procesador de entorno. Por sí solo no implementa reverberación ni fuerza el silencio de la ruta directa: un procesador desactivado u omitido que regresa sin aplicar efectos deja audio seco, así que silencia el reproductor cuando necesites silencio estricto ahí. El posicionamiento mono básico funciona sin EFX; un adaptador EFX necesita un dispositivo que exponga EFX. Mantén el código opcional del adaptador en el módulo de integración del consumidor para que WATERMeDIA siga cargando sin Minecraft ni _Sound Physics_.

### Verificación

`SpatialAudioTest` cubre el rechazo de estados inválidos, la negativa de Java Sound, las coordenadas y la atenuación de una fuente OpenAL real, la restauración del origen antes del callback, la limpieza de efectos, el manejo de excepciones, los contextos que no coinciden y las llamadas tardías después de liberar. También genera un WAV estéreo y lo reproduce con `FFMediaPlayer`, revisando que la decodificación estéreo llegue a cada sink como PCM mono en búferes reales de OpenAL. `ALSinkTest` cubre el conjunto de sinks, las mezclas en cada tipo de muestra, los controles en lote, la contrapresión, los sinks que se unen sobre la muestra del primario y la recuperación tras un vaciado de búfer. Las pruebas de dispositivo necesitan una salida OpenAL, y el caso de decodificación también necesita los nativos de _FFmpeg_.

Todavía falta una prueba dentro del juego para cómo suenan la oclusión y las reflexiones, la serialización del ejecutor de sonido del host, el movimiento del oyente, los ajustes de categoría y las recargas del dispositivo: el código de referencia se revisó, pero este checkout no tiene un entorno ejecutable de Minecraft con _Sound Physics_.

## Imágenes y contenedores DDS

> [!WARNING]
> Experimental: no lo uses en tus mods todavía (mira [paquetes públicos e internos](#paquetes-públicos-e-internos)).

`CodecsAPI.reader` elige un lector interno según los primeros bytes y lo devuelve como `ImageReader`. Las estructuras de chunks PNG y GIF de `api.codecs.common` son internas, y los codificadores PNG y GIF serializan a través de ellas.

```java
try (ImageReader reader = CodecsAPI.reader(encodedBuffer)) {
    PixelFormat format = reader.pixelFormat();
    while (reader.hasNext()) {
        ByteBuffer pixels = reader.next();
        // CONSUME OR COPY BEFORE THE NEXT FRAME REUSES THE READER'S BUFFER.
    }
}
```

Todos los decodificadores entregan un búfer BGRA compacto por cuadro, salvo los JPEG en escala de grises, que entregan un byte de luma por píxel (`GRAY`).

- `CodecsAPI.reader(buffer, format)` pide `BGRA`, `RGBA` o `GRAY` y el lector convierte cuando ése no es su propio formato, así que `pixelFormat()` e `ImageData.format()` siempre dicen qué contienen los bytes; cualquier otro formato se rechaza con `IllegalArgumentException`.
- `hasNext()` y `next()` siguen las reglas de un iterador: `next()` después del último cuadro lanza `NoSuchElementException`. En un lector cerrado, `hasNext()`, `next()`, `reset()` y `readAll()` lanzan `IllegalStateException` mientras los descriptores siguen legibles. `close()` es idempotente y es el único método seguro de llamar desde otro hilo.
- `delay()` es el retardo del cuadro que `next()` acaba de devolver, `repeat()` cuenta las reproducciones totales (`ImageData.REPEAT_FOREVER`, 0, repite para siempre), `variableFrameRate()` es falso con un solo cuadro y `metadata()` devuelve una instantánea congelada que nunca te cambia por debajo.
- Los cuadros JPEG y WebP salen ya rotados según su orientación EXIF, con `width()` y `height()` intercambiados cuando hace falta.
- La metadata usa valores Java simples bajo claves compartidas (`CodecsAPI.METAKEY_ICC_PROFILE`, `METAKEY_EXIF`, `METAKEY_XMP`, `METAKEY_ORIENTATION`, `METAKEY_LOOP_COUNT`) más claves por formato como `PNG_METAKEY_*`. Un chunk auxiliar malformado o demasiado grande se omite y se registra una vez, nunca es fatal.
- Una característica válida que le falta al decodificador (un chunk crítico PNG desconocido, un JPEG sin pérdida o CMYK, un tipo de tupla PAM desconocido) lanza `UnsupportedFormatException`, un subtipo de `CodecException`, para que el host distinga "usar otra opción" de "archivo roto".
- Netpbm cubre las variantes de texto `P1`..`P3` y las binarias `P4`..`P7`.
- `readAll()` conserva copias y aplica un límite total de bytes decodificados por imagen. `CodecsAPI.decode` también acepta un `ByteBuffer` o un `InputStream`, y `sniff` un `ByteBuffer`.
- El búfer de origen se comparte con el lector, no se copia, así que no lo cambies hasta que el lector se cierre; cuando un lector rechaza su contenido, la posición del búfer se queda donde estaba.
- Los lectores simultáneos siguen usando memoria independiente: los reproductores multimedia coordinan su decodificación y reutilizan las imágenes decodificadas con sus cachés, mientras que el código que usa los decodificadores directamente se encarga de su propia concurrencia.

`BcReader` lee bloques BC1/BC3/BC7 ya comprimidos de un arreglo de texturas DDS DX10, y `pixelFormat()` los nombra como `PixelFormat.BC1`, `BC3` o `BC7` (`PixelFormat.blockBytes()` da el tamaño de bloque).

- Crea `org.watermedia.api.codecs.BcReader` directamente para acceder a los bloques; `CodecsAPI.reader` no abre archivos DDS.
- No hace falta un codificador nativo, pero los motores integrados OpenGL, Vulkan y de software no aceptan texturas BC, así que leer bloques DDS no significa que el reproductor los soporte de punta a punta.
- Un pie de animación de WATERMeDIA aporta los retardos cuando existe. Los bytes después de la textura que no empiezan con la firma del pie (ASCII `WMTC`) se ignoran, y las capas DDS simples tienen retardo cero; un pie que empieza con la firma pero está truncado o es inconsistente está malformado.
- Se rechazan las cadenas de mipmaps, los volúmenes y los mapas de cubo, y no hay codificador BC ni opción de caché de códecs.

## Codificación

> [!WARNING]
> Experimental: no lo uses en tus mods todavía (mira [paquetes públicos e internos](#paquetes-públicos-e-internos)).

`CodecsAPI.encoder(container, target, video, audio)` abre un `Encoder` para un `Container`. `Container.video()` y `Container.audio()` listan los códecs que acepta cada pista; una pista que no encaja, o un contenedor que sólo decodifica (WebP, JPEG, Netpbm, SVG), se rechaza con `IllegalArgumentException` antes de tocar el destino. PNG (APNG cuando llega más de un cuadro) y GIF son Java puro y no necesitan _FFmpeg_; MP4, MOV, MKV, WebM y Ogg pasan por _FFmpeg_ y lanzan `IOException` sin él.

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60);
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) {
    encoder.video(frame, ptsUs); // ONE CALL PER FRAME, TIMESTAMPS IN MICROSECONDS THAT KEEP INCREASING
    encoder.audio(pcm);          // INTERLEAVED SAMPLES, CONTINUOUS FROM TIME ZERO
}
```

- **Cuadros:** exactamente `Video.frameBytes()` bytes en `Video.format()`. Un cuadro dura hasta el siguiente timestamp y el último `1 / fps`; una animación empieza con su primer cuadro sin importar su timestamp. PNG y GIF aceptan RGBA, BGRA, RGB o GRAY. Los códecs de _FFmpeg_ aceptan cualquier `PixelFormat` sin comprimir, que se convierte al formato del códec y se etiqueta como BT.709 de rango limitado (el RGB pasa por esa matriz, el YUV se toma como si ya la usara), descartando el alfa y agrandando un lado impar en un píxel para los códecs 4:2:0.
- **Calidad:** `quality` va de 0 (más chico) a 100 (mejor) y se traduce a la escala de calidad constante de cada códec; PNG la ignora y GIF aplica dithering a su paleta desde 50. `repeat` cuenta las reproducciones de una animación, 0 para siempre.
- **Códecs:** H.264 usa _OpenH264_ (_libx264_ en los nativos GPL), H.265 _Kvazaar_ (_libx265_ en los nativos GPL), AV1 _SVT-AV1_ o _libaom_, VP8 y VP9 _libvpx_, y ProRes `prores_ks`. Opus acepta 8, 12, 16, 24 o 48 kHz y remuestrea cualquier otra frecuencia a la siguiente hacia arriba, MP3 acepta mono o estéreo, y cada códec recibe su propio tamaño de cuadro, rellenando o acortando el último.
- **Audio:** la línea de tiempo del sonido empieza en el tiempo cero del video y avanza con cada muestra, así que quien llama rellena con silencio un inicio tardío.
- **Destinos:** con un `Path`, los contenedores de _FFmpeg_ codifican en `<file>.part` junto a él y lo renombran sobre el destino cuando `close()` termina (MP4 y MOV además mueven su índice al inicio); con un `OutputStream`, codifican en un archivo temporal privado que se copia a él. PNG escribe el archivo completo en `close()` y GIF lo escribe cuadro por cuadro. Cerrar un codificador que no recibió ningún cuadro ni muestra lanza `IllegalStateException` y no escribe nada. El stream pertenece al codificador una vez que abre y se queda con quien llama cuando la apertura se rechaza. Los codificadores no son seguros entre hilos: alimenta cada uno desde un solo hilo.

## Reproducción sincronizada

`PlayerAPI.serverPlayer()` construye un `ServerMediaPlayer`, un reloj sin medios ni motores que corre en cualquiera de los dos lados. Es dueño de las cuentas del tiempo: start, pause, seek, velocidad, repetición y la vuelta del ciclo o la transición a `ENDED` corren sobre el reloj del sistema, y cada cambio exitoso incrementa `revision()`. El host es dueño de todo lo demás: quién puede manejar el reloj, cómo llegan sus instantáneas a los clientes y dónde se guarda su posición. Los motores y reproductores no tienen constructores públicos: toda instancia sale de `PlayerAPI`.

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

Un reproductor al que le pasas una instantánea con `sync(PlayerState)` (o `sync(ByteBuffer)` para la forma codificada) sigue al reloj desde entonces. Cada 50 ms:

- Refleja el estado de la instantánea: start, pause y stop. Una instantánea en `BUFFERING`, que sólo trae una construida a mano, se comporta como una pausa.
- Adopta su repetición y su velocidad.
- Regresa la reproducción con un `seekQuick` limitado en frecuencia cuando `drift()` supera `tolerance(ms)` (un segundo por defecto).

La última instantánea recibida siempre gana, así que envíalas en el orden en que el reloj las produjo. `authority()` devuelve esa instantánea, `authorityTime()` extrapola dónde va el reloj ahora y `following()` dice si un reproductor sigue a uno. La corrección nunca recorta la velocidad de reproducción, se pausa mientras el reproductor carga o almacena en búfer, y toma el camino corto en la frontera del ciclo en contenido con repetición.

Un control llamado sobre un reproductor que sigue al reloj se aplica de inmediato y lo deshace el siguiente tick que no esté de acuerdo, así que manda los controles del usuario al reloj con tus propios paquetes y deja que la instantánea traiga el resultado. El avance por cuadros se rechaza mientras sigue al reloj, y el volumen, el silencio, el escalado y el audio espacial siempre se quedan locales.

El reloj se queda en cero hasta que `syncDuration(ms)` o `live(true)` le dicen sobre qué línea de tiempo corre. Reporta la duración una vez desde un cliente que abrió la fuente (el primer valor positivo gana por toda la vida del reloj y los valores distintos posteriores se ignoran), y restaura una posición guardada con `syncTime(ms)` después de arrancar el reloj, porque `start()` reinicia desde cero. `canSeek()` es falso y `seek(...)` devuelve `false` hasta conocer una duración, y siempre en una sesión en vivo. `release()` congela la posición y deja el reloj en `STOPPED` con una revisión nueva, así que guarda y envía esa última instantánea antes de soltarlo, o los reproductores seguirán extrapolando la anterior. Libera cada reproductor cuando termine su sesión del host.

### Formato de la instantánea

`MediaPlayer.PlayerState` es un record fijo de 27 bytes en big-endian (versión, revisión, estado, tiempo, duración, velocidad, banderas). `PlayerState.read(ByteBuffer)` consume sólo esos bytes y deja los datos posteriores para tu transporte; el orden de bytes del propio búfer no importa, y un payload rechazado deja la posición intacta. Revisa en tu transporte quién envió una instantánea: la biblioteca nunca identifica a los participantes.

## Ejemplos compilados y verificación

Los loggers del proyecto son `watermedia` y `watermedia_binaries`; ponlos en DEBUG para diagnóstico.

- Los mensajes de los loggers del proyecto muestran los links multimedia completos, así las rutas y los IDs siguen sirviendo para depurar. Ocultan los valores de encabezados y enmascaran contraseñas de links (`user:password@`), tokens de acceso sueltos, valores `Authorization` y `Cookie`, argumentos de sesión de Minecraft y pares `key=value` con pinta de secreto (`DataTool.redact` aplica el mismo filtro a las subidas de logs de la app). Las trazas de excepciones con algún secreto conservan su tipo, pila, causas y fallas suprimidas como texto censurado.
- Nada se acumula por ventana de tiempo: una anomalía que puede repetirse (un búfer de cuadro corto, una disposición rechazada, un callback que falla, una subida rechazada) se registra la primera vez que ocurre en cada motor, reproductor, servidor o lista de reproducción y después calla, y las iteraciones lentas de reproducción se cuentan y se reportan una vez al terminar la ejecución.
- _FFmpeg_ todavía escribe sus propias líneas de log nativas en la salida de error del proceso, fuera de los loggers del proyecto y de su censura; enrutarlas por el logger del proyecto ya está listo, pero desactivado hasta validarlo en macOS y Linux.

`src/test/java/org/watermedia/ApiGuideExample.java` compila el uso de las fábricas de cada motor gráfico y de audio, y los dos lados de la reproducción sincronizada: el reloj y un seguidor. Su ejemplo de decodificación de imágenes, que filtra cada cuadro con un `DownscaleFilter`, corre en `ApiGuideExampleTest`; el comportamiento de interfaz gráfica, dispositivos de audio y Minecraft necesita la integración del host correspondiente. Las suites normales de reproducción y audio espacial cubren por separado el comportamiento real de _FFmpeg_ y OpenAL.

Los ejemplos usan variables que aporta el host: son fragmentos de integración, no apps completas. Los jobs configurados con `require_natives=true` exigen los binarios de _FFmpeg_ en lugar de omitirse cuando faltan.
