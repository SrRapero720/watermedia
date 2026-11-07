# 🦆 INTRODUCCIÓN
WATERMeDIA es una librería con una API multimedia para reproducir, decodificar, codificar y manejar video, audio e
imágenes. Si ya usaste _ExoPlayer_ o _VLCJ_ te va a resultar conocida, pero acá todo viene listo: nunca te peleás con
buffers, planos, orden de bytes ni strides.

Resuelve links multimedia (YouTube, Twitch, archivos directos y muchos más), los decodifica y le pasa los
cuadros a tu motor gráfico (OpenGL, Vulkan, AWT, JavaFX) y el sonido a tu motor de audio
(OpenAL, Java Sound). Las imágenes y animaciones pasan por nuestros propios decodificadores en Java puro; el video y
el audio pasan por _FFmpeg_, que viene incluido en el mod compañero _WATERMeDIA: Binaries_. Necesita Java
17 o más nuevo.

Cada ejemplo de esta guía es un fragmento de integración: variables como `renderThread`,
`renderExecutor` o `mrl` salen de tu propio código.

# 🪁 ESQUEMA DE VERSIONES
> [!IMPORTANT]
> Este esquema no aplica a las APIs experimentales ni al código interno

Cada número te dice qué cambió y si te puede romper algo.

**ESQUEMA DE INCREMENTOS**
``X.Y.Z-<channel>.W``
- ``X``: Reescritura total: todo lo que conocés de la librería se tiró abajo y se rehízo desde cero
- ``Y``: Cambios que rompen: refactors, renombres y eliminaciones de API que pueden romper (o directamente van a romper)
  tu implementación
- ``Z``: Hotfixes, mejoras, API nueva y anotaciones de deprecación, nada que rompa.
- ``<channel>``: canales ALPHA o BETA; las versiones release no usan este sufijo
  - Nota: a veces se omite a propósito, porque algunos modloaders rompen los rangos de versiones con él.
- ``W``: número de entrega ALPHA o BETA, se omite en el canal release

Esto es una GARANTÍA de que NO SE VAN A METER CAMBIOS QUE ROMPAN EN LOS INCREMENTOS DE ``Z``, así que podés requerir
watermedia tranquilo en un rango de ``[3.0.1,3.1)``

# 🗺 ÍNDICE
Elegí el camino que coincida con lo que estás armando.

## MINECRAFT: MODDERS QUE SUMAN VIDEO
1. [Instalación](#0-instalación): sumá la dependencia y respetá el rango de versiones.
2. [Restricciones de software](#11-restricciones-de-software): versiones de Minecraft y de loaders soportadas.
3. [Manejo de links](#2-manejo-de-links): convertí un link en fuentes multimedia.
4. [Crear y controlar un reproductor](#31-crear-y-controlar-un-reproductor).
5. [Elegir motores](#33-elegir-motores): OpenGL, Vulkan y OpenAL sobre los contextos propios del juego.
6. [Audio espacial](#34-audio-espacial): pantallas posicionales, pares estéreo y parlantes.
7. [Reproducción sincronizada](#35-reproducción-sincronizada): todos los reproductores ven el mismo momento.
8. [Subir y descargar archivos](#4-subir-y-descargar-archivos): compartí archivos a través del servidor de archivos.

## APLICACIONES DE ESCRITORIO
1. [Instalación](#0-instalación): dependencias de Gradle, librerías de ejecución y [configuración](#02-configuración).
2. [Arranque](#1-arranque): arrancá WATERMeDIA una sola vez y mostrá una pantalla de carga.
3. [Manejo de links](#2-manejo-de-links) y [búsquedas](#23-búsquedas).
4. [Crear y controlar un reproductor](#31-crear-y-controlar-un-reproductor) y
   [pistas de audio](#32-pistas-de-audio-e-idiomas).
5. [Elegir motores](#33-elegir-motores): AWT, JavaFX, OpenGL o Vulkan, más OpenAL o Java Sound.
6. [Diagnóstico y ejemplos](#13-diagnóstico-y-ejemplos).

## HERRAMIENTAS DE CÓDIGO
Herramientas de línea de comandos, bots, conversores y servicios que funcionan sin ventana.

1. [Instalación](#0-instalación) y [arranque](#1-arranque): qué necesita arrancarse y qué no.
2. [Plataformas](#22-plataformas) y [búsquedas](#23-búsquedas): resolvé y buscá links con `PlatformAPI`, sin arranque.
3. [Descargas](#42-descargas): `NetRequest` con descargas acotadas y verificadas.
4. [Decodificadores](#51-decodificadores) y [codificadores](#52-codificadores): imágenes, animaciones y archivos de
   video.
5. [Motor headless](#33-elegir-motores): capturá cuadros decodificados sin un contexto gráfico.
6. [Reproducción sincronizada](#35-reproducción-sincronizada): `ServerMediaPlayer` también corre en servidores.

## AGENTES DE IA
Leé [arranque](#1-arranque), [paquetes públicos e internos](#12-paquetes-públicos-e-internos),
[manejo de links](#2-manejo-de-links), [crear y controlar un reproductor](#31-crear-y-controlar-un-reproductor) y
[elegir motores](#33-elegir-motores) antes de escribir código de integración. Estas reglas son requisitos estrictos:

- Usá solo los paquetes públicos (`org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player`,
  `api.util`, sin sus subpaquetes). Los reproductores y los motores salen solo de `PlayerAPI`; las clases de motor son
  sealed.
- Adentro de Minecraft nunca llames a `WaterMedia.start`: lo hace el loader. En cualquier otro lado llamalo una vez por
  proceso, nunca desde un callback ni desde un hilo worker de WATERMeDIA. No hay stop.
- Revisá `WaterMedia.loaded()` y la capacidad que necesitás (como `CodecsAPI.ffmpegLoaded()`) en vez de darlas por
  sentadas.
- La resolución es asíncrona: consultá `mrl.status()` desde un tick y nunca llames a `mrl.await(...)` en el hilo del
  juego ni en el de render.
- `PlayerAPI.player(...)` puede devolver null. Liberá el reproductor cuando `start()` lance una excepción o el host
  descarte la superficie.
- Pasá los motores como suppliers, nunca compartas uno entre reproductores, y dejá que el reproductor sea su dueño.
- Leé `player.texture()` de nuevo en cada dibujado, en el hilo de render; 0 significa que no hay nada para dibujar.
- Nunca llames a `release()` desde un listener de estado ni desde un hilo de reproducción, y liberá cada reproductor
  antes de destruir el contexto OpenGL, Vulkan u OpenAL que usa.
- El executor de OpenGL corre en el hilo de GL y se bombea en cada cuadro, una vez más también después de `release()`.
  OpenAL necesita su contexto activo en cada hilo que maneje audio. En Vulkan, tomá `queueLock()` alrededor de tus
  propios submits y presents, y nunca llames a `vkDeviceWaitIdle` mientras existan reproductores.
- Los listeners de estado se consumen con la transición a `STOPPED`, `ENDED` o `ERROR`; registralos de nuevo desde el
  callback para seguir escuchando.
- Los controles booleanos devuelven `true` cuando la llamada tuvo efecto y `false` cuando se rechazó; revisá el
  resultado en vez de suponerlo.
- Los tiempos van en milisegundos (las marcas de tiempo del encoder, en microsegundos), el volumen va de 0 a 100 y la
  velocidad desde apenas más de 0 hasta 4.
- `ImageReader.next()` reutiliza su buffer: copialo antes de la siguiente llamada. Las llamadas de decodificación,
  reader, sniff y encoder de `CodecsAPI` son experimentales: no las uses en mods; sus chequeos de estado son seguros.
- Los servidores dedicados tienen `ServerMediaPlayer`, `PlatformAPI`, las subidas de `NetworkAPI`, el servidor de
  archivos y los códecs de imagen en Java puro; los reproductores y los motores lanzan excepciones ahí.

# 0. INSTALACIÓN
En Minecraft solo sumá WATERMeDIA y _WATERMeDIA: Binaries_ como dependencias (mirá
[Binaries y FFmpeg](#01-binaries-y-ffmpeg)) y declará WATERMeDIA como requerida en tu mod, dentro de los rangos de
[restricciones de software](#11-restricciones-de-software); el loader las provee al ejecutar.

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    // HEY NEWBIE CODER: implementation IN MODDING ENVIRONMENTS DOESN'T SHIP DEPENDENCIES IN YOUR BUILT JAR
    // AND IS NEEDED SO THE NEOFORGE OR FABRIC RUNTIME CAN LOAD THEM
    implementation "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.1
    implementation "com.github.SrRapero720:watermedia-binaries:$binaries_version" // FFMPEG, YT-DLP AND BOTGUARD, OPTIONAL
}
```

En aplicaciones Java propias o juegos hechos en Java, el entorno tiene que proveer:

```gradle
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.1
    implementation "com.github.SrRapero720:watermedia-binaries:$binaries_version" // FFMPEG, YT-DLP AND BOTGUARD, OPTIONAL
    implementation "com.github.SrRapero720:waterconfig:1.0.0.1" // MIN WATERCONFIG VERSION
    implementation "com.google.code.gson:gson:2.10" // MIN GSON VERSION
    implementation "org.apache.logging.log4j:log4j-api:2.26.1" // MIN LOG4J VERSION, PLUS THE BACKEND YOUR APP LOGS WITH
    implementation "org.joml:joml:1.10.8" // MIN JOML VERSION
    // LWJGL 3.3.6 MODULES, ONLY FOR THE ENGINES YOU USE (SEE THE TABLE BELOW)
}
```

| Motor | Librerías extra en tiempo de ejecución |
| --- | --- |
| `GLEngine` | _LWJGL_ `lwjgl` y `lwjgl-opengl`, con sus nativos |
| `VKEngine` | _LWJGL_ `lwjgl`, `lwjgl-vulkan` y `lwjgl-shaderc` (los shaders se compilan en tiempo de ejecución), con sus nativos (`lwjgl-vulkan` solo tiene nativos en macOS; Windows y Linux usan el Vulkan del sistema) |
| `ALEngine` | _LWJGL_ `lwjgl` y `lwjgl-openal`, con sus nativos |
| `JFXEngine` | Un runtime de JavaFX 13+ |
| `AWTEngine`, `JSEngine`, `HeadlessGFXEngine` | Nada extra: el JDK los cubre |

- En Forge, NeoForge y Fabric, WATERMeDIA incluye _WATERCoNFIG_ como un JAR anidado, y el juego ya trae las demás
  librerías.
- El launcher independiente baja las librerías que le faltan cuando hacen falta, y solo usa las que coinciden con el
  SHA-256 fijado al compilar.
- Una app que lo integra arma su propio classpath: que compile en desarrollo no garantiza que una instalación de
  Minecraft traiga esas librerías.
- Binaries trae _XZ_ adentro, y WATERMeDIA trae los bindings de _JavaCPP_ y _FFmpeg_.

Los nativos de _FFmpeg_ cubren estas plataformas; las imágenes se decodifican en cualquier lugar donde corra Java.

| Arquitectura | Windows | Linux | macOS | Android |
| --- | --- | --- | --- | --- |
| x86_64 | ✅ | ✅ | ✅ | ⛔ |
| aarch64 | ⛔ | ✅ | ✅ | ⛔ |

> [!WARNING]
> No hay nativos de _FFmpeg_ para Windows en ARM64 ni para Android: ahí las imágenes se siguen decodificando, pero el
> video y el audio no se reproducen, y un Binaries instalado hace fallar el arranque salvo que `codecs.ffmpeg.disable`
> esté activado.

## 0.1 BINARIES Y FFMPEG
> [!IMPORTANT]
> La decodificación de video por hardware necesita una GPU y un driver que decodifiquen ese códec: NVIDIA (CUDA), Intel
> (QSV), AMD (AMF), Direct3D 11 o 12 en Windows, VideoToolbox en macOS, VAAPI, VDPAU o DRM en Linux, o
> [Vulkan](#decodificación-con-vulkan). Cualquier otra cosa, o una GPU que no da abasto, decodifica por software, y
> `codecs.ffmpeg.hardwareAccel = false` siempre lo hace.

> [!NOTE]
> Configurable: `codecs.ffmpeg.disable`, `customPath`, `hardwareAccel`, `analyzeDuration`, `probeSize`,
> `trackPacketReads`, `cache` y `cacheMaxResponse`. Mirá sus comentarios en `config/watermedia.toml`.

_WATERMeDIA: Binaries_ carga junto con WATERMeDIA y prepara los nativos antes que la red, los códecs y el reproductor.

- Los clientes de Minecraft lo requieren; los servidores dedicados lo pueden omitir, ya que ahí no prepara nada.
- Las apps que integran la API Java también lo pueden omitir y conservan la decodificación de imágenes y los
  resolvedores de plataformas que no usan nativos; pierden la decodificación de video y las plataformas que dependen de
  _yt-dlp_.
- Si desactivás _FFmpeg_ (`codecs.ffmpeg.disable`), se saltea la extracción.
- Binaries no tiene API para arrancar o apagar: los archivos nativos quedan en su lugar durante todo el proceso.
- _yt-dlp_ y _BotGuard_ se descargan la primera vez que se usan y se verifican antes de correr. Las propiedades del
  sistema `watermedia.ytdlp.binary` y `watermedia.botguard.binary` apuntan a tus propios ejecutables en su lugar.

Para tu propia build de _FFmpeg_, poné la carpeta con sus librerías nativas (no un `ffmpeg.exe`) en
`codecs.ffmpeg.customPath`. Sirve cualquier licencia o versión, siempre que traiga bindings JNI compatibles y el mismo
soporte TLS que la incluida (mirá [Confianza HTTPS y credenciales](#25-confianza-https-y-credenciales)). WATERMeDIA
solo verifica la integridad de los nativos incluidos.

## 0.2 CONFIGURACIÓN
> [!NOTE]
> Cada campo se explica solo en su comentario y los valores los decidís vos. Cada sección de esta guía lista los
> campos que la afectan.

WATERMeDIA escribe su configuración en `config/watermedia.toml` dentro del directorio de trabajo, con un comentario
arriba de cada campo. Los valores por defecto andan de una, los campos que se leen al arrancar se aplican en el próximo
inicio, y cuando un backend parece no estar disponible, este archivo es el primer lugar donde fijarse.

- `codecs.*`: _FFmpeg_, la decodificación por hardware y los decodificadores de imágenes.
- `player.*`: la calidad por defecto y las cachés.
- `platforms.*`: contenido para adultos, búsqueda y cuentas de plataformas.
- `network.*`: el servidor de archivos y las solicitudes web.

# 1. ARRANQUE
En Forge, NeoForge y Fabric el loader arranca WATERMeDIA por vos: salteá esta sección.

**EJEMPLO:**
```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded(); // true ONCE EVERY MODULE FINISHED; CHECK THE CAPABILITY YOU NEED TOO
String module = WaterMedia.loading(); // MODULE CURRENTLY LOADING
int step = WaterMedia.step(); // CURRENT LOADING STEP
int steps = WaterMedia.steps(); // TOTAL LOADING STEPS
```

Carga, en este orden:
- Configuración (`watermedia.toml`)
- Binaries (solo cuando su JAR está presente)
- `NetworkAPI`
- `CodecsAPI`
- `PlayerAPI`

`steps()` es 5 con Binaries y 4 sin él. Podés leer `loading()`, `step()` y `steps()` desde cualquier hilo para una
pantalla de carga.

- `start` corre una sola vez por proceso: no hay stop, un segundo `start` lanza una excepción y un arranque fallido no
  se puede reintentar.
- Con null, el directorio temporal es `<java.io.tmpdir>/watermedia` y el de trabajo, el actual. El último argumento
  dice si es un cliente; en servidores dedicados no arrancan Binaries, los códecs ni el reproductor.
- Si falla una parte opcional (el servidor de archivos, _FFmpeg_), queda apagada y la carga sigue: revisá lo que
  necesitás, como `CodecsAPI.ffmpegLoaded()`.
- Cualquier otra falla (la configuración, un Binaries instalado) hace que `start` lance `IllegalStateException` con
  esa falla como causa, y `loaded()` queda en false.
- Nunca llames a `start` desde un hilo o un callback de WATERMeDIA: lo rechaza.

Cada API sabe si su módulo cargó:

- `PlayerAPI.mrl` lanza `IllegalStateException` hasta que arranca el reproductor, y siempre en servidores dedicados.
- `NetworkAPI.upload` lanza una excepción hasta que arranca la red o si no le pasás un archivo existente; un servidor
  caído o mal configurado se reporta en el estado que devuelve.
- `PlatformAPI` no necesita arranque: `register`, `fetch` y `search` andan siempre. Las plataformas que usan Binaries
  (YouTube, _yt-dlp_) fallan con `PlatformException` hasta que carga, y siempre en servidores dedicados; sin el JAR de
  Binaries, `fetch` devuelve null para sus links.
- La decodificación de imágenes en Java puro con `CodecsAPI`, `ServerMediaPlayer`, `MathUtil` y `MathEases` no necesitan
  arranque.

El reproductor es dueño de los motores que crean tus suppliers. Pasá suppliers y no motores ya creados: si la fuente no
está, no queda nada nativo colgado. Los contextos OpenGL, Vulkan y OpenAL son tuyos: liberá cada reproductor antes de
destruirlos, y creá motores nuevos si recargás un dispositivo o un contexto. Los nativos nunca se descargan ni cambian
de versión con la JVM andando.

## 1.1 RESTRICCIONES DE SOFTWARE
### Launchers bloqueados
WATERMeDIA no arranca en Minecraft si el juego se lanzó con _TLauncher_, y el error dice qué lo delató. Los restos de
una instalación vieja solo dejan una advertencia en el log, y las apps que lo integran no hacen este chequeo.

### Versiones compatibles
Forge, NeoForge y Fabric cargan WATERMeDIA en clientes y servidores dedicados de estas versiones, y rechazan el resto:

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 o una 40.x más nueva | ⛔ | 0.14 o una 0.x más nueva |
| 1.19.2 | 43.5.0 o una 43.x más nueva | ⛔ | 0.14 o una 0.x más nueva |
| 1.20.1 | 47.4.2 o una 47.x más nueva | ⛔ | 0.14 o una 0.x más nueva |
| 1.21.1 | 52.1.1 o una 52.x más nueva | 21.1.0 o una 21.1.x más nueva | 0.14 o una 0.x más nueva |
| 1.21.11 | 61.0.5 o una 61.x más nueva | 21.11.0 o una 21.11.x más nueva | 0.14 o una 0.x más nueva |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 o una 0.x más nueva |
| 26.2 | 65.x | 26.2.0.x | 0.14 o una 0.x más nueva |
| 26.3 | 66.x | 26.3.0.x | 0.14 o una 0.x más nueva |

- En Minecraft se acepta desde Java 17 hasta 25.
- WATERMeDIA y Binaries se aceptan entre sí desde 3.0.0 hasta 3.1.0 (sin incluirla); las BETA viejas de 3.0.0 no.
- Fabric no puede pedir una dependencia solo en clientes, así que ahí es el bootstrap el que exige Binaries en los
  clientes.

## 1.2 PAQUETES PÚBLICOS E INTERNOS
La API pública son los paquetes `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` y
`api.util`, sin sus subpaquetes. Todo lo demás es interno.

Nada te impide usar los internos, pero por favor usá los puntos de entrada públicos, como `PlayerAPI.player` y
`PlatformAPI.fetch`, y no toques lo que hay detrás. Si contribuís, `gradle javadocInventory` lista los métodos públicos
sin documentar.

> [!WARNING]
> `CodecsAPI` es experimental: `decode`, `reader`, `sniff`, `encoder` y los readers, codificadores y filtros que los
> rodean pueden cambiar en cualquier actualización, sin respetar el esquema de versiones. No los uses en tus mods
> todavía; los reproductores ya los usan por vos. Los chequeos de estado (`ffmpegLoaded()`, `ffmpegError()`,
> `vulkanDecode()`) sí se pueden usar.

## 1.3 DIAGNÓSTICO Y EJEMPLOS
Los loggers del proyecto son `watermedia` y `watermedia_binaries`; poné esos nombres en DEBUG para diagnosticar.

- Los logs muestran los links completos para que sirvan al depurar, pero tapan los secretos: contraseñas en links,
  tokens JWT, `Authorization`, `Cookie`, la sesión de Minecraft y cualquier `key=value` que parezca secreto. Los logs
  que sube la app pasan por el mismo filtro.
- Un problema que se repite (un buffer corto, un layout rechazado, un callback que falla, una subida rechazada) se
  registra solo la primera vez en cada motor, reproductor, servidor o caché. Las iteraciones lentas se cuentan y se
  reportan una vez al final.
- _FFmpeg_ todavía escribe sus propios logs en la salida de error, por fuera de los loggers del proyecto y de su
  filtro de secretos.
- `-Dwatermedia.glchecks=true` activa los chequeos de errores de OpenGL al subir cuadros.

`src/test/java/org/watermedia/ApiGuideExample.java` tiene ejemplos que compilan para cada motor y para la reproducción
sincronizada (reloj y seguidor); el de imágenes corre en `ApiGuideExampleTest`. Si contribuís, `-Prequire_natives=true`
hace que los tests fallen en vez de saltearse cuando faltan los nativos de _FFmpeg_.

# 2. MANEJO DE LINKS
> [!NOTE]
> Configurable: `platforms.allowMatureContent`, `searchLimit`, `searchCleanupInterval`, `biliBiliCookie` y
> `player.mrlCleanupInterval`. Mirá sus comentarios en `config/watermedia.toml`.

Un MRL (Multimedia Resource Location) convierte cualquier link en el contenido multimedia que tiene adentro: una o más
fuentes, cada una con sus calidades, pistas de audio y metadatos. Se resuelve en segundo plano y queda en caché.

## 2.1 RESOLVER LINKS
```java
MRL mrl = PlayerAPI.mrl("https://example.com/movie.mp4");    // A WEB LINK, A FILE UNDER THE WORKING DIRECTORY OR A water:// LINK
MRL[] queue = PlayerAPI.preload(firstUri, secondUri);        // START RESOLVING SEVERAL AT ONCE

// FROM A GAME TICK OR A UI TIMER
switch (mrl.status()) {
    case FETCHING -> { }                                     // STILL RESOLVING
    case LOADED -> play(mrl);                                // READY TO PLAY
    case BLOCKED -> showMatureWarning();                     // MATURE CONTENT WHILE IT'S NOT ALLOWED
    case ERROR -> showError(mrl.exception());                // WHY IT FAILED; A MALFORMED LINK STARTS HERE
    case EXPIRED, FORGOTTEN -> mrl = mrl.reload();           // STALE OR EVICTED: KEEP THE HANDLE reload() RETURNS
}

mrl.subscribe(done -> refreshUi());                          // OR GET CALLED ONCE WHEN IT FINISHES OR FAILS
boolean finished = mrl.await(5_000);                         // OR WAIT, BUT NEVER ON THE GAME OR RENDER THREAD
```

```java
// ONE LINK CAN HOLD SEVERAL SOURCES, SUCH AS A GALLERY WITH AN IMAGE AT 0 AND A VIDEO AT 1
MRL.Source first = mrl.source(0);                            // null FOR A WRONG INDEX
MRL.Source video = mrl.sourceByType(MediaType.VIDEO);        // THE FIRST VIDEO, OR null
List<MRL.Source> all = mrl.sources();

MediaType type = video.type();                               // IMAGE, VIDEO, AUDIO...
Metadata info = video.metadata();                            // TITLE, DESCRIPTION, AUTHOR, DURATION...
URI thumbnail = video.thumbnail();
URI link = video.uri(MediaQuality.HIGH);                     // THE CLOSEST QUALITY AVAILABLE
List<Track> dubs = video.audioTracks();                      // SEPARATE AUDIO TRACKS, THE ORIGINAL FIRST

MediaPlayer player = PlayerAPI.player(mrl, 1, gfx, sfx);     // PLAY THE SOURCE AT INDEX 1 (SEE PLAYING MEDIA)
```

Un link directo falla cuando el servidor responde con un estado de error o con algo que no es multimedia; las playlists
HLS se abren como un solo video y las playlists IPTV como una fuente por canal.

## 2.2 PLATAFORMAS
Los links de estas plataformas se resuelven de una:

- 📺 YouTube, Twitch, Kick, BiliBili, Odysee, D.tube, VidLii
- 🐦 Twitter (X), Bluesky, TikTok, Facebook, Instagram
- 🎵 SoundCloud, Newgrounds (películas, música y arte)
- 🖼️ Imgur, Lightshot, Medal, Streamable, Sendvid
- 📁 Google Drive, Dropbox, MediaFire
- 🔞 PornHub (solo con `platforms.allowMatureContent` activado)
- 🔗 Links directos a cualquier video, música, imagen o playlist, archivos locales y
  [links `water://`](#24-el-protocolo-water)

YouTube, SoundCloud, Facebook e Instagram se reproducen con _yt-dlp_, que requiere Binaries. BiliBili reproduce hasta
480p, o hasta 1080p (8K con VIP) con tu cookie de sesión en `platforms.biliBiliCookie`.

```java
Platform.Resolution resolved = PlatformAPI.fetch(uri);       // THE RESOLUTION WITHOUT AN MRL, FOR TOOLS; null WHEN NO PLATFORM HANDLES THE LINK

// YOUR OWN SITE: CHECKED BEFORE THE BUILT-IN PLATFORMS, AND IT NEEDS NO STARTUP
PlatformAPI.register(new Platform() {
    @Override
    public String name() { return "MySite"; }

    @Override
    public Platform.Resolution resolve(final URI uri) throws PlatformException {
        if (!"mysite.com".equals(uri.getHost())) return null;            // NOT YOURS: THE NEXT PLATFORM TRIES
        if (isProfile(uri)) throw PlatformException.unsupported(this.name(), uri); // YOURS BUT NOTHING TO PLAY: MRL GOES ERROR
        if (isMature(uri)) throw new MatureContentException(this.name(), "Mature video"); // MRL GOES BLOCKED

        final Platform.Quality quality = new Platform.Quality(findVideo(uri), 1920, 1080); // REAL SIZE, 0 WHEN UNKNOWN
        final Platform.Source source = new Platform.Source(MediaType.VIDEO, null, null, null, List.of(quality), null, null);
        return new Platform.Resolution(null, source);                    // null EXPIRY: THE LINKS NEVER EXPIRE
    }
});
```

Cualquier otra falla de tu plataforma también es una `PlatformException`, y un álbum o una playlist se saltea un ítem
roto en vez de fallar entero.

## 2.3 BÚSQUEDAS
YouTube (con Binaries), Twitch, Kick e Imgur buscan por palabra clave, y tu plataforma se suma sobrescribiendo
`search(query, limit)`.

```java
List<PlatformResult> results = PlatformAPI.search("lofi");   // RETURNS AT ONCE; THE LIST FILLS AS PLATFORMS ANSWER
boolean searching = PlatformAPI.searching("lofi");           // false ONCE EVERY PLATFORM ANSWERED; POLL BOTH FROM THE UI
for (final PlatformResult hit: results) {
    show(hit.title(), hit.thumbnail(), hit.platform().name()); // WHEN PICKED, PlayerAPI.mrl(hit.uri()) OPENS IT LIKE ANY LINK
}
List<PlatformResult> fresh = PlatformAPI.search("lofi", true); // ASK THE PLATFORMS AGAIN INSTEAD OF THE CACHE
```

Una búsqueda nueva reemplaza a la anterior, y mientras el usuario sigue escribiendo espera un toque a la próxima tecla.
Twitch y Kick solo listan los canales en vivo.

## 2.4 EL PROTOCOLO WATER
```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

Los links remotos y globales llevan el ID de archivo que dio el servidor, no una ruta de carpeta.

## 2.5 CONFIANZA HTTPS Y CREDENCIALES
HTTPS anda de una con los certificados en los que Java ya confía, y ese chequeo nunca se desactiva.

¿Usás un certificado autofirmado o tu propia autoridad? Importalo en un truststore y apuntá Java ahí antes de que
arranque WATERMeDIA (en Minecraft, poné las mismas opciones `-D` en los argumentos de JVM del launcher):

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- Un truststore vacío o roto impide que _FFmpeg_ arranque, así que el video queda apagado hasta que lo arregles.
- Una build propia de _FFmpeg_ necesita el mismo soporte TLS que la incluida.
- Los headers de login y las cookies (`Authorization`, `Cookie`, `X-WaterMedia-Token`) nunca siguen una redirección a
  otro servidor, y las redirecciones solo van a links http o https.

# 3. REPRODUCCIÓN MULTIMEDIA
> [!NOTE]
> Configurable: `player.defaultQuality`, `player.cacheBudget`, `player.watertx.cache` y `player.watertx.texturesBudget`.
> Mirá sus comentarios en `config/watermedia.toml`.

## 3.1 CREAR Y CONTROLAR UN REPRODUCTOR
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

`player` devuelve null si la fuente todavía se resuelve, el índice no existe, falta el backend o la construcción lanza
una `Exception` (un `Error` sí se propaga). Mirá el estado del MRL antes de reintentar.

- Los suppliers corren solo cuando hace falta, y las imágenes nunca llaman al supplier de audio.
- Un supplier que devuelve null omite esa salida, como `() -> null` como motor gráfico para reproducir solo audio.
- Nunca compartas un motor entre reproductores.

`TxMediaPlayer` reproduce las imágenes y animaciones soportadas, `FFMediaPlayer` reproduce audio y video con _FFmpeg_, y
`ServerMediaPlayer` es un reloj de sincronización sin decodificación ni motores nativos.

Los tres reproductores comparten los controles: devuelven `true` si la llamada tuvo efecto y `false` si se rechazó (no
se puede ahora, ya se liberó o la velocidad está fuera de rango). Los ajustes de salida no devuelven nada.

```java
// PLAY, PAUSE AND STOP
boolean started = player.start();                 // PLAYS FROM THE BEGINNING; ALSO RESTARTS AN ENDED, STOPPED OR FAILED PLAYER
boolean startedPaused = player.startPaused();     // THE SAME, BUT LANDS PAUSED (SAFER THAN start() FOLLOWED BY pause())
boolean paused = player.pause();                  // false WHEN ALREADY PAUSED, ENDED, STOPPED OR FAILED; WHILE LOADING IT WAITS FOR THE PIPELINE
boolean resumed = player.resume();                // false UNLESS PAUSED
boolean pauseChanged = player.pause(wantPaused);  // pause() OR resume() FROM A FLAG, SUCH AS A CHECKBOX
boolean toggled = player.togglePlay();            // PAUSES, RESUMES, OR STARTS AN ENDED OR STOPPED PLAYER AGAIN
boolean stopped = player.stop();                  // STOPPED WITH time() AT 0; false WHEN ALREADY STOPPED OR NEVER STARTED

// MOVE THROUGH THE MEDIA (MILLISECONDS, CLAMPED TO THE DURATION)
boolean sought = player.seek(15_000);             // PRECISE; ON AN ENDED OR STOPPED PLAYER IT RESTARTS PAUSED AT THE TARGET
boolean soughtQuick = player.seekQuick(15_000);   // FASTER, LESS PRECISE ON VIDEO; THE SAME AS seek() ON IMAGES
boolean skipped = player.skipTime(-10_000);       // RELATIVE TO time(); NEGATIVE GOES BACK
boolean forwarded = player.forward();             // 5 SECONDS AHEAD (1 SECOND ON ANIMATIONS)
boolean rewound = player.rewind();                // 5 SECONDS BACK (1 SECOND ON ANIMATIONS)
boolean stepped = player.nextFrame();             // ONLY WHILE PAUSED AND NOT FOLLOWING A CLOCK
boolean steppedBack = player.previousFrame();     // ONLY WHILE PAUSED AND NOT FOLLOWING A CLOCK

// PLAYBACK OPTIONS
boolean speedChanged = player.speed(1.25f);       // (0, 4]; false ON LIVE STREAMS, JAVA SOUND OR A RATE THE AUDIO ENGINE REFUSES
boolean repeatChanged = player.repeat(true);      // false ONLY AFTER release()
boolean trackChanged = player.audioTrack(track);  // SEE AUDIO TRACKS AND LANGUAGES
boolean placed = player.spatialAudio(position);   // SEE SPATIAL AUDIO; false WITHOUT A SPATIAL OPENAL ENGINE

// OUTPUT SETTINGS: ALWAYS TAKEN, SO THEY RETURN NOTHING
player.volume(50);                                // 0 TO 100, CLAMPED; MUTE NEVER CHANGES IT
player.mute(true);
player.quality(MediaQuality.HIGH);                // THE CLOSEST AVAILABLE; HOT SWITCH ON VIDEO, NEXT START ON IMAGES
player.scale(1280, 720);                          // UPLOAD SIZE CAP PER AXIS, 0 FOR NO CAP
player.lod(MediaPlayer.LodLevel.FAR);             // UPLOAD SIZE BY DISTANCE: MAX 100%, CLOSE 75%, NEAR 50%, FAR 25%, FAR_AWAY 10%
player.displayTime(10_000);                       // A STILL IMAGE ENDS AFTER THIS AND NEVER LOOPS; 0 KEEPS IT UNTIL STOPPED
player.audioLanguage("es-MX");                    // PREFERRED AUDIO LANGUAGE FROM THE NEXT START

// WHAT THE MEDIA ALLOWS: ASK BEFORE SHOWING A CONTROL
boolean playable = player.canPlay();              // FROM THE FIRST SUCCESSFUL LOAD (FROM CREATION ON A CLOCK) UNTIL IT FAILS OR IS RELEASED
boolean seekable = player.canSeek();              // false UNTIL THE SOURCE OPENS, ON LIVE STREAMS AND ON STILL IMAGES
boolean speedable = player.canSpeed();            // A PURE QUERY, SAFE FROM ANY THREAD ON EVERY FRAME
boolean live = player.live();                     // false UNTIL THE SOURCE OPENS
boolean positional = player.spatial();            // POSITIONAL AUDIO IS AVAILABLE

// WHERE IT IS
MediaPlayer.Status status = player.status();      // ALSO playing(), paused(), buffering(), ended(), error()...
long time = player.time();                        // 0 BEFORE THE FIRST FRAME, THE DURATION ONCE ENDED
long duration = player.duration();                // 0 WHEN UNKNOWN OR LIVE
int buffered = player.buffered();                 // 0 TO 100: REAL PROGRESS WHILE A VIDEO LOADS OR BUFFERS; IMAGES AND CLOCKS REPORT 100 OR 0
float fps = player.fps();                         // 0 UNTIL KNOWN
Throwable failure = player.exception();           // WHY IT LANDED IN ERROR
int width = player.width();                       // UPLOADED SIZE; sourceWidth() AND sourceHeight() GIVE THE NATIVE ONE
int height = player.height();

// DRAW: ON THE RENDER THREAD, READ FRESH ON EVERY FRAME
long texture = player.texture();                  // OPENGL TEXTURE NAME OR VULKAN VkImageView HANDLE; 0 MEANS NOTHING TO DRAW

// CLEAN UP WHEN THE HOST DROPS THE SURFACE, FAILED PLAYBACK INCLUDED
player.release();                                 // IDEMPOTENT, LEAVES status() AT STOPPED; NEVER FROM A LISTENER OR PLAYBACK THREAD
```

Llamá a `speed(...)` con el contexto de audio activo: el reloj solo toma una velocidad que el motor de audio aceptó. Los
motores gráficos incluidos todavía no aceptan texturas comprimidas BC.

```java
final MediaPlayer.StatusListener playlist = new MediaPlayer.StatusListener() {
    @Override
    public void onStatus(final MediaPlayer source, final MediaPlayer.Status previous, final MediaPlayer.Status current) {
        // EVERY REAL TRANSITION, ONE AT A TIME AND IN ORDER; KEEP IT SHORT AND NEVER BLOCK
        if (current != MediaPlayer.Status.ENDED) return;
        source.onStatus(this); // ENDED, STOPPED AND ERROR CONSUME THE LISTENERS: REGISTER AGAIN TO KEEP HEARING
        source.start();        // RESTARTING HERE IS FINE; source.release() NEVER IS
    }
};
player.onStatus(playlist); // ADDING IT TWICE KEEPS IT ONCE
```

- Un listener corre en el hilo que provocó el cambio: un hilo de reproducción, el ticker de sincronización o, en
  imágenes, quien llame a `status()`, `time()` o `texture()`.
- Nunca corre con un lock del reproductor tomado, ni en un hilo al que `release()` está esperando. El `STOPPED` de
  `release()` llega cuando ya se liberó todo, motores incluidos.

| Estado del reproductor | Significado |
| --- | --- |
| `WAITING` | Creado y esperando las condiciones de arranque. |
| `LOADING` | Cargando o preparando el contenido. |
| `BUFFERING` | Esperando suficientes datos para seguir. |
| `PLAYING` | Reproduciendo el contenido. |
| `PAUSED` | En pausa y listo para continuar. |
| `STOPPED` | Detenido y listo para arrancar desde el principio. |
| `ENDED` | Llegó al final del contenido. |
| `ERROR` | La reproducción se topó con una falla. |

`Status.allows(next)` dice si una pausa, reanudación, buffering, final, stop o falla pueden pasar directo a `next`.
Arrancar, saltar y reiniciar tienen sus propias reglas: `LOADING` termina cuando abre el pipeline (o al parar o fallar),
un salto en `ENDED` o `STOPPED` reinicia, y desde `STOPPED` no hay paso directo.

## 3.2 PISTAS DE AUDIO E IDIOMAS
Los videos doblados y los archivos con varios streams de audio suenan con su audio original (o el predeterminado del
archivo) salvo que pidas otro.

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- Se elige en este orden: lo que pediste con `audioTrack(...)`, después `audioLanguage(...)`, después la original (o
  el stream predeterminado del archivo) y si no, el primero. `es-MX` elige primero `es-MX` y después `es-419`, `es` o
  `spa`. Sin `audioLanguage(...)` suena la original, nunca un doblaje automático.
- `audioTracks()` lista un `AudioTrack(track, stream, name, lang)` por stream: primero los del archivo principal y
  después los de cada pista separada. `track` es `AudioTrack.MAIN` o el índice de la pista separada, y `stream` es el
  índice de _FFmpeg_ adentro de ella.
- Una pista separada que todavía no sonó aparece como una entrada `provisional()` (`stream` es -1) con el nombre y el
  idioma de la plataforma, así listar no abre todos los doblajes. Cuando suena, la reemplazan sus streams reales. La
  lista es inmutable y se lee desde cualquier hilo.
- `audioTrack(track)` devuelve `true` si cambió la elección. Un reproductor en marcha reconstruye su pipeline en el
  tiempo actual (un stream en vivo salta a su borde), y la elección sobrevive a cambios de calidad y reinicios.
  `audioTrack()` devuelve lo que suena ahora. Los reproductores de imágenes, de servidor o sin motor de audio no listan
  nada.

## 3.3 ELEGIR MOTORES
Vulkan va primero y OpenGL sigue soportado. Qué backend usar lo decide tu capa de render.

| Fábrica | Obligación del host |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | El executor corre en el hilo del contexto OpenGL: bombealo en cada cuadro, y una vez más después de `release()`. Con los dos argumentos en null, el hilo que llama tiene que ser el dueño del contexto. Nunca muevas un motor entre contextos. |
| `PlayerAPI.vkEngine(context)` | Pasá un `VKContext` que viva más que el motor: tomá `queueLock()` en tus submits y presents, y corré los callbacks de `retire(...)` cuando terminen tus cuadros en vuelo (todos si se va el dispositivo). Para decodificar en la GPU, mirá [Decodificación con Vulkan](#decodificación-con-vulkan). |
| `PlayerAPI.awtEngine(onFrame)` | Pintá la imagen publicada desde la UI de AWT/Swing; pasá el callback opcional al hilo de la UI según haga falta. |
| `PlayerAPI.jfxEngine(onFrame)` | Necesita un runtime de JavaFX 13+; enlazá la imagen del motor a tu UI. Sin él, la fábrica lanza `IllegalStateException` en vez de un error de linkeo. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Captura los cuadros sin contexto gráfico, re útil para tests y para procesar imágenes. Como todo motor, solo existe en clientes; un servidor dedicado solo crea relojes `ServerMediaPlayer`. |
| `PlayerAPI.alEngine()` | El contexto OpenAL tiene que estar activo en los hilos de audio. |
| `PlayerAPI.alEngine(true)` | El mismo contrato de OpenAL, reproduciendo con sinks mono posicionales (pares estéreo y parlantes extra). |
| `PlayerAPI.jsEngine()` | Hay un dispositivo y una línea de Java Sound disponibles; sin audio posicional. |

> [!IMPORTANT]
> **OpenGL:** convertir video YUV y en escala de grises en la GPU necesita OpenGL 3.2 core (GLSL 150); sin eso, los
> cuadros se convierten a BGRA en la CPU. Subir directo desde el hilo de decodificación necesita OpenGL 4.4 o
> `ARB_buffer_storage`; los drivers más viejos suben en el hilo de render a través de dos pixel buffers.

> [!IMPORTANT]
> **Vulkan:** `queue()` tiene que aceptar trabajo de cómputo y de transferencia.
> - **Zero copy:** los planos decodificados llegan a la GPU sin una copia en la CPU cuando el dispositivo habilita
>   `VK_EXT_external_memory_host` (`hostImportSupported()` devuelve true) y el buffer del plano está alineado a
>   `minImportedHostPointerAlignment()`; si no, el plano se copia a través de un staging buffer.
> - **Conversión YUV por hardware:** los cuadros SDR de 8 bits NV12 y YUV 4:2:0, 4:2:2 y 4:4:4 con tamaños pares y sin
>   primarios BT.2020 se convierten en el hardware del sampler cuando el dispositivo habilita `samplerYcbcrConversion`
>   (Vulkan 1.1, `ycbcrSampler()` devuelve true); cualquier otro layout usa un pase de cómputo.
> - **Layouts:** además de BGRA, RGBA, YUYV y UYVY de 8 bits, un layout necesita formatos de plano que soporten
>   muestreo, filtrado lineal y escrituras de transferencia, o cae en BGRA. RGB siempre cae en BGRA.
> - **Decodificación en la GPU:** mirá [Decodificación con Vulkan](#decodificación-con-vulkan).

Los motores son sealed: vos ponés los contextos y los executors, no motores propios. Cada motor maneja sus texturas,
fuentes de audio y buffers.

Todos los motores siguen el mismo ciclo de vida:

- `release()` es idempotente, nunca tiene que correr a la vez que el reproductor que lo alimenta, y deja el motor
  inerte: `released()` es true y `texture()` devuelve 0.
- El reproductor revisa `supports(format, bits)` antes de subir y vuelve a BGRA de 8 bits cuando el motor rechaza un
  layout.
- Las subidas nunca lanzan excepciones: una falla del driver descarta el cuadro con un solo error en el log, y el último
  cuadro queda en pantalla.
- Los motores por software y headless exponen un centinela distinto de cero en vez de un handle de GPU, así que compará
  el handle solo contra 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` y `frameBytes` calculan el tamaño de cada plano de un motor,
  por ejemplo para saber cuánta memoria usa. Redondean para arriba como _FFmpeg_, así los tamaños impares no pierden
  croma.

Manejo del color:

- Los motores OpenGL y Vulkan convierten YUV con la matriz (BT.601, BT.709 o BT.2020), el rango y la posición de croma
  que declara cada video. Cuando no declara nada, adivinan como _mpv_: BT.601 para cuadros SD (de menos de 1280 píxeles
  de ancho y hasta 576 líneas de alto), y BT.709 para el resto.
- El HDR PQ y HLG se muestra como SDR, con los colores BT.2020 mapeados a BT.709 y las luces altas comprimidas para que
  un pico de 1000 nits llegue al blanco.
- Los motores de un mismo contexto o dispositivo comparten esa conversión, y se borra con el último. Liberá cada motor
  y dejá correr su limpieza (en el executor de OpenGL o en `VKContext.retire`) antes de destruir el contexto o el
  dispositivo.
- Los motores por software reciben BGRA que _FFmpeg_ convierte con la matriz y el rango declarados, sin tone mapping de
  HDR.

OpenAL necesita su contexto activo en cada hilo del reproductor, ticker de sincronización incluido. Java Sound no
necesita locks extra, y si un bloque de audio no entra en la línea, abre una más grande y descarta lo que quedaba en la
vieja.

> [!IMPORTANT]
> **OpenAL:** el audio de 4, 6, 7 y 8 canales necesita `AL_EXT_MCFORMATS` en un motor común (los motores espaciales
> mezclan todo a estéreo), las muestras float y double en mono y estéreo necesitan `AL_EXT_FLOAT32` y `AL_EXT_DOUBLE`
> (el float surround viene con `AL_EXT_MCFORMATS`), y un reloj que tenga en cuenta la latencia de salida del dispositivo
> necesita `AL_SOFT_source_latency`; sin eso, el audio se convierte a lo que el dispositivo reproduce. Los
> [procesadores de entorno](#procesadores-de-entorno) que usan EFX necesitan `ALC_EXT_EFX`.

La app independiente (abrir el JAR de WATERMeDIA) usa Vulkan salvo que elijas OpenGL, y si Vulkan no está, arranca en
OpenGL esa vez sin cambiar tu elección. `-Dwatermedia.engine=opengl` o `-Dwatermedia.engine=vulkan` fuerza el motor por
un solo inicio. Sus ajustes (motor, escala de la interfaz, efecto CRT y sonido de clic) se guardan en
`config/app.properties`.

### Decodificación con Vulkan
Por defecto `VKContext.deviceInfo()` devuelve null y el video se sube desde la RAM. Sobrescribilo con cómo creaste tu
dispositivo (la cadena de features de `vkCreateDevice`, que no podés liberar ni cambiar mientras viva el dispositivo,
las extensiones de instancia y de dispositivo habilitadas y las colas creadas por familia) y _FFmpeg_ decodifica H.264,
H.265 y AV1 con Vulkan Video, sin pasar por la RAM.

> [!IMPORTANT]
> La decodificación con Vulkan necesita:
> - Una instancia y un dispositivo Vulkan 1.3 con `timelineSemaphore` y `synchronization2` habilitados.
> - `VK_KHR_video_queue`, `VK_KHR_video_decode_queue` y la extensión de decodificación de cada códec que quieras
>   acelerar: `VK_KHR_video_decode_h264`, `VK_KHR_video_decode_h265` o `VK_KHR_video_decode_av1`.
> - Colas libres en tu dispositivo, o las extensiones de memoria y semáforos externos para un gemelo, las dos opciones
>   explicadas abajo.
> - Nativos con Vulkan Video (`CodecsAPI.vulkanDecode()` devuelve true) y `codecs.ffmpeg.hardwareAccel` activado.

Entonces _FFmpeg_ decodifica en uno de dos lugares:

- **Tu dispositivo**, si tiene colas libres para _FFmpeg_: una de cómputo (que también acepte transferencias) distinta
  de `queue()` y una de decodificación de video, con `queue()` creada última en `queueFamily()`. _FFmpeg_ se queda con
  las demás colas de cada familia y les hace submit desde sus hilos, así que mientras existan reproductores no las
  toques ni llames a `vkDeviceWaitIdle`; esperá en tu cola (`vkQueueWaitIdle` con `queueLock()` tomado).
- **Un gemelo**, si tu dispositivo no tiene esas colas pero habilita `VK_KHR_external_memory_win32` y
  `VK_KHR_external_semaphore_win32` en Windows, o `VK_KHR_external_memory_fd` y `VK_KHR_external_semaphore_fd` en el
  resto. El motor crea un segundo dispositivo en la misma GPU, _FFmpeg_ decodifica ahí y cada imagen se importa a tu
  dispositivo. Tus colas siguen siendo tuyas.

En los dos casos, liberá cada reproductor antes de destruir el dispositivo o su instancia.

> [!IMPORTANT]
> Los videos 4:2:2, 4:4:4 y de 12 bits solo se decodifican en la GPU con _libplacebo_, que convierte cada layout
> decodificado en RGBA SDR (con tone mapping del HDR). Necesita `hostQueryReset`, `runtimeDescriptorArray`,
> `shaderStorageImageArrayNonUniformIndexing` y `maintenance4` en el dispositivo que decodifica y, en tu propio
> dispositivo, una segunda cola en `queueFamily()`; un gemelo habilita esas features por su cuenta cuando la GPU las
> tiene. Sin _libplacebo_, solo los streams 4:2:0 (de 8 y 10 bits) se decodifican en la GPU, y el motor los convierte
> con sus propios shaders.

## 3.4 AUDIO ESPACIAL
Creá el motor con `PlayerAPI.alEngine(true)` antes de armar el reproductor; el modo espacial queda fijo de por vida. El
audio estéreo y surround se mezcla a estéreo (el mono sigue mono) y suena por sinks, que son fuentes mono posicionales.
El `alEngine()` común reproduce todo en una sola fuente sin posición, y Java Sound no tiene audio espacial (devuelve
`false`).

El motor arranca con un sink primario en `Mix.MONO`, sin posición. Pasale un `SpatialAudio` para ubicarlo en el mundo,
o `null` para volver a la reproducción común, sin posición ni efectos. El volumen, el mute, la velocidad, la pausa y
`sync` llegan a todos los sinks.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Creá, actualizá y liberá el motor en el executor de sonido del host, con su contexto OpenAL activo. El dispositivo, el
contexto y el oyente son tuyos y tienen que vivir más que los reproductores que los usan.

- `FFMediaPlayer` usa hilos propios, así que el contexto tiene que ser el activo de todo el proceso. Los hosts que lo
  activan solo por hilo (thread-local) no están soportados.
- El motor rechaza un contexto distinto o ausente, y nunca cambia ni recrea el tuyo.
- Liberá los reproductores antes de recargar un dispositivo, y después creá motores nuevos en el contexto nuevo.
- `FFMediaPlayer.release()` frena su pipeline y espera a su hilo antes de borrar la fuente: llamalo antes de destruir
  el contexto, nunca desde un callback de ese pipeline.

`SpatialAudio` rechaza posiciones que no entran en un float de OpenAL, cualquier cosa fuera de
`0 < referenceDistance < maxDistance` y un rolloff negativo (0 apaga la atenuación). Cómo se atenúa lo decide el modelo
de distancia de tu host; la API nunca lo toca, ni al oyente ni a la velocidad de la fuente, así que `maxDistance` no
siempre significa silencio.

### Sinks: pares estéreo y parlantes extra
Una sola decodificación alimenta todos los sinks, así un par estéreo o un parlante extra no cuestan otra descarga ni
otra decodificación. Cada sink reproduce un `ALEngine.Mix`: `LEFT`, `RIGHT` o `MONO` (el promedio). Con contenido mono
todas las mezclas suenan igual, así un par estéreo nunca queda mudo.

```java
// A STEREO PICTURE: THE PRIMARY TURNS INTO THE LEFT EDGE AND A NEW SINK PLAYS THE RIGHT ONE
final ALEngine.Sink left = player.sinks().get(0);
left.mix(ALEngine.Mix.LEFT);
final ALEngine.Sink right = player.addSink(ALEngine.Mix.RIGHT);
left.spatialAudio(new SpatialAudio(leftX, y, z, 8.0f, 64.0f, 1.0f, false, environment));
right.spatialAudio(new SpatialAudio(rightX, y, z, 8.0f, 64.0f, 1.0f, false, environment));

// A SPEAKER BLOCK: ONE MORE SINK AT ITS OWN POSITION, RELEASED WHEN THE BLOCK GOES AWAY
final ALEngine.Sink speaker = player.addSink(ALEngine.Mix.MONO);
speaker.spatialAudio(new SpatialAudio(speakerX, speakerY, speakerZ, 8.0f, 64.0f, 1.0f, false, environment));
speaker.release();
```

- El sink primario es dueño de `player.audioSource()`, maneja el reloj y vive lo mismo que el motor (su `release()`
  devuelve `false`). `player.sinks()` lista primero el primario; sin un motor OpenAL espacial, `sinks()` da una lista
  vacía y `addSink(...)` da `null`.
- Todos los sinks encolan las mismas muestras y se controlan juntos, así un par estéreo queda alineado a la muestra.
  Sumar un sink durante la reproducción, o cambiar una mezcla con contenido estéreo, realinea las colas con el
  primario, con una pausa cortita.
- Cada sink tiene su propio `spatialAudio(...)`: el adaptador de entorno recibe la fuente de ese sink, y
  `sink.release()` lo resetea antes de borrar la fuente. Liberar el reproductor libera todos los sinks.
- Sumá, cambiá y liberá sinks en el executor de sonido con el contexto activo; sus consultas (`source()`, `mix()`,
  `spatialAudio()`, `released()`) no lo necesitan.
- Un host puede usar la fuente de un sink en su sistema de sonido y cambiar su ganancia, su pitch y su posición, pero
  nunca reproducirla, encolarla, desencolarla ni borrarla. Un sink nuevo arranca con la ganancia y el pitch del motor.

### Procesadores de entorno
El último argumento de `SpatialAudio` es un `SpatialAudio.Environment` opcional que aplica tus propios efectos, como
oclusión o reverb, a cada fuente de OpenAL. Es la puerta para los mods de sonido espacial: escribí un adaptador chiquito
en tu mod que les pase cada fuente, y cargalo solo cuando ese mod esté presente.

```java
final SpatialAudio.Environment environment = new SpatialAudio.Environment() {
    @Override
    public void process(final int source, final SpatialAudio audio) {
        // EVERY spatialAudio(...) UPDATE: APPLY YOUR WHOLE ENVIRONMENT (EFX FILTERS AND SENDS) TO source
    }

    @Override
    public void reset(final int source) {
        // THE PROCESSOR IS REPLACED, REMOVED OR THE SINK IS RELEASED: REMOVE YOUR EFFECTS FROM source
    }
};
player.spatialAudio(new SpatialAudio(x, y, z, 8.0f, 64.0f, 1.0f, false, environment));
```

- Mantené una única instancia estable del procesador entre actualizaciones, y llamá a `spatialAudio(...)` desde el
  executor de sonido del host con el contexto activo.
- Antes de cada `process` se restaura la posición y se limpian los filtros y envíos de la fuente, así que podés mover
  `AL_POSITION`, pero tenés que aplicar el entorno completo en cada llamada.
- Limitá la frecuencia de las actualizaciones en el host (no cortando antes dentro del procesador) y repartí muchos
  reproductores entre varios ticks.
- El motor borra solo su fuente y sus buffers, nunca los filtros ni los slots de efectos del procesador.
- Si `process` o `reset` lanzan una excepción, se limpian los efectos y la excepción sigue; un `reset` que falla no
  impide borrar la fuente al liberar.
- `auxOnly = true` pide solo la salida reflejada, pero no silencia el camino directo: muteá el reproductor si necesitás
  silencio total.
- El procesador solo corre en tus actualizaciones, nunca desde la decodificación. El posicionamiento básico no necesita
  EFX; un procesador con EFX necesita un dispositivo que lo tenga.

### Verificación
`SpatialAudioTest` y `ALSinkTest` prueban el audio espacial y los sinks contra OpenAL real: argumentos, atenuación,
entornos, excepciones, contextos, mezclas y underruns. Necesitan un dispositivo de salida OpenAL, y los casos de
decodificación también los nativos de _FFmpeg_.

## 3.5 REPRODUCCIÓN SINCRONIZADA
`PlayerAPI.serverPlayer()` arma un `ServerMediaPlayer`, un reloj sin contenido ni motores que corre en los dos lados.
Lleva la cuenta del tiempo (arranque, pausa, salto, velocidad, repetición y final) y cada cambio sube `revision()`. Lo
demás es tuyo: quién maneja el reloj, cómo llegan los snapshots a los clientes y dónde guardás la posición.

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

Un reproductor al que le pasás un snapshot con `sync(PlayerState)` (o `sync(ByteBuffer)` para la forma codificada) sigue
al reloj desde ese momento. Cada 50 ms:

- Copia el estado del snapshot: arranque, pausa y stop. Un snapshot en `BUFFERING`, que solo trae uno armado a mano, se
  sostiene como una pausa.
- Adopta su repetición y su velocidad.
- Corrige con un `seekQuick` (con límite de frecuencia) cuando `drift()` pasa `tolerance(ms)` (un segundo por defecto).

Siempre gana el último snapshot recibido, así que mandalos en orden. `authority()` devuelve ese snapshot,
`authorityTime()` estima dónde está el reloj ahora y `following()` dice si el reproductor sigue a uno. La corrección
nunca toca la velocidad, se saltea mientras carga o hace buffering, y en contenido que se repite toma el camino más
corto por el loop.

Un control sobre un reproductor que sigue al reloj se deshace en el siguiente tick, así que mandá los controles del
usuario al reloj con tus paquetes y dejá que el snapshot traiga el resultado. Avanzar cuadro por cuadro se rechaza, y el
volumen, el mute, el escalado y el audio espacial siempre quedan locales.

El reloj se queda en su posición (cero después de `start()`) hasta que `syncDuration(ms)` o `live(true)` le dicen qué
está reproduciendo. Mandá la duración una vez desde un cliente que abrió la fuente (gana el primer valor positivo), y
restaurá una posición guardada con `syncTime(ms)` después de `start()`, que arranca de cero. Sin duración, o en vivo,
no se puede saltar (`canSeek()` es false). `release()` congela la posición y lo deja en `STOPPED`: mandá ese último
snapshot antes de soltarlo, o los clientes siguen con el anterior. Liberá cada reproductor cuando termine su sesión.

### Formato del snapshot
`MediaPlayer.PlayerState` es un registro big-endian fijo de 27 bytes (versión, revisión, estado, tiempo, duración,
velocidad, flags). `PlayerState.read(ByteBuffer)` lee solo esos bytes, sin importar el orden del buffer, y deja el
resto para tu transporte; si rechaza un snapshot, la posición del buffer no se mueve. Verificá vos quién mandó cada
snapshot: la librería no identifica a nadie.

# 4. SUBIR Y DESCARGAR ARCHIVOS
> [!WARNING]
> El servidor es HTTP plano: si lo exponés a internet, ponelo detrás de un proxy HTTPS. Con el token por defecto no
> arranca fuera de `127.0.0.1`: poné uno propio y cambialo en cada actualización del servidor para que ningún
> desconocido pueda subir archivos.

> [!NOTE]
> Configurable: `network.server`, `serverBindAddress`, `serverPort`, `remoteUrl`, `token`, `maxServerRequests`,
> `serverTimeout`, `maxUploadSize` y `maxStorageSize`. Mirá sus comentarios en `config/watermedia.toml`.

El servidor de archivos opcional guarda los archivos que suben tus jugadores y los comparte con todos a través de links
`water://remote/<id>`. Para usarlo:

1. Activalo con `network.server`: `DEDICATED` lo corre en servidores dedicados, `ALWAYS` también en clientes.
2. Abrí su puerto hacia afuera: el servidor no lo hace solo.
3. Apuntá `network.remoteUrl` a la dirección pública y al puerto del servidor, en el servidor y en cada cliente.
4. Dale al servidor y a cada cliente el mismo `network.token` personalizado.

Los archivos subidos quedan en `watermedia/files` dentro del directorio de trabajo del servidor; borralos de ahí para
liberar espacio.

## 4.1 SUBIDAS
```java
NetworkAPI.UploadStatus status = NetworkAPI.upload(new File("clip.mp4"));
// LATER, FROM A TICK OR A UI TIMER
if (status.completed()) {
    URI link = status.uri(); // water://remote/<id>, READY FOR PlayerAPI.mrl
} else if (status.failed()) {
    String reason = status.error();
}
```

`NetworkAPI.upload` sube en segundo plano y devuelve un `UploadStatus` por archivo: `percentage()` y `displaySpeed()`
siguen la transferencia, `cancel()` la frena y `error()` te dice por qué falló una.

## 4.2 DESCARGAS
> [!NOTE]
> Configurable: `network.timeout`, `maxRedirects` y `maxTextBytes`. Mirá sus comentarios en `config/watermedia.toml`.

Reproducí los archivos remotos como cualquier otro link: `PlayerAPI.mrl("water://remote/<id>")`. Para laburo HTTP común,
`NetRequest` (en `org.watermedia.api.util`) manda una solicitud con las mismas reglas de seguridad que la reproducción:

```java
try (NetRequest request = new NetRequest("https://example.com/pack.zip").httpsOnly().maxBytes(64L * 1024 * 1024)) {
    request.send();
    request.download(Path.of("pack.zip"), expectedSha256); // THE DIGEST IS MANDATORY
}
```

- `download(path, sha256)` escribe un archivo nuevo, lo verifica y lo borra cuando algo falla.
- Cada solicitud se manda una sola vez: configurala, hacé `send()` y leé su respuesta en un mismo hilo.
- `text()` e `inputStream()` devuelven el cuerpo de un estado de error en vez de lanzar una excepción, y `json(type)` lo
  mapea cuando es JSON (null para cualquier otro tipo); fijate `statusCode()`.

# 5. CÓDECS
> [!WARNING]
> Experimental: `decode`, `reader`, `sniff` y `encoder` pueden cambiar en cualquier actualización, así que no los uses
> en tus mods todavía (mirá [paquetes públicos e internos](#12-paquetes-públicos-e-internos)).

> [!NOTE]
> Configurable: `codecs.maxImageSourceSize`, `codecs.png.failOnCorruptedData`, `codecs.png.useBKGDChunk`,
> `codecs.svg.maxSize` y `codecs.vp8.brokenTokens`. Mirá sus comentarios en `config/watermedia.toml`.

`CodecsAPI` entrega un decodificador (`ImageReader`) y un codificador (`Encoder`). Las imágenes usan nuestros códecs en
Java puro, sin arranque ni _FFmpeg_; el video pasa por _FFmpeg_.

## 5.1 DECODIFICADORES
### Imágenes estáticas
PNG, JPEG, WebP, SVG y Netpbm.

```java
ImageData image = CodecsAPI.decode(bytes);                   // A byte[], A ByteBuffer OR AN InputStream
ByteBuffer pixels = image.frames()[0];                       // TIGHTLY PACKED; JPEG AND WEBP COME ROTATED TO THEIR EXIF ORIENTATION
PixelFormat format = image.format();                         // BGRA (GRAY FOR GRAYSCALE JPEGS)
int width = image.width();
int height = image.height();

ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);  // ASK FOR BGRA, RGBA OR GRAY AND IT CONVERTS
```

### Imágenes animadas
GIF, APNG y WebP animado. Un reader recorre los cuadros uno por uno en vez de tenerlos todos en memoria.

```java
try (ImageReader reader = CodecsAPI.reader(buffer)) {        // DON'T CHANGE buffer UNTIL THE READER CLOSES
    int frames = reader.frameCount();
    boolean forever = reader.repeat() == ImageData.REPEAT_FOREVER;
    reader.addFilter(new DownscaleFilter(0.5f));             // OPTIONAL: SHRINKS EVERY FRAME, NEVER ENLARGES

    while (reader.hasNext()) {
        ByteBuffer frame = reader.next();                    // REUSED ON THE NEXT CALL: COPY IT TO KEEP IT
        long delayMs = reader.delay();                       // HOW LONG THIS FRAME STAYS ON SCREEN
    }
    boolean rewound = reader.reset();                        // BACK TO THE FIRST FRAME; false WHEN IT CAN'T
    ImageData all = reader.readAll();                        // OR EVERY FRAME LEFT AT ONCE, AS COPIES
}
```

### Cualquier imagen
```java
MediaType type = CodecsAPI.sniff(buffer);                    // IMAGE, VIDEO, AUDIO... FROM THE FIRST BYTES
ImageMetadata info = reader.metadata();                      // ON AN OPEN READER: title(), authors()... AND value(CodecsAPI.METAKEY_EXIF)

try {
    ImageData image = CodecsAPI.decode(bytes);
} catch (UnsupportedFormatException unsupported) {
    // A VALID FILE USING A FEATURE THE DECODER LACKS: FALL BACK TO ANOTHER DECODER
} catch (CodecException broken) {
    // A BROKEN OR OVERSIZED FILE
}
```

Algunos formatos que `sniff` reconoce como imágenes (BMP, TIFF, AVIF...) todavía no tienen decodificador. Para tus
propios motores, `new BcReader(buffer)` lee los bloques comprimidos BC1, BC3 y BC7 de una textura DDS; los motores
incluidos todavía no los dibujan.

### Video
El video y el audio se decodifican adentro de los reproductores: creá uno con `PlayerAPI.player` (mirá
[reproducción multimedia](#3-reproducción-multimedia)).

## 5.2 CODIFICADORES
```java
Set<VideoCodec> videoCodecs = Container.MP4.video();         // WHAT EACH CONTAINER ACCEPTS; A TRACK THAT
Set<AudioCodec> audioCodecs = Container.MP4.audio();         // DOESN'T FIT IS REFUSED BEFORE THE FILE IS TOUCHED
```

Cada codificador toma un cuadro por llamada, en el formato de `Encoder.Video`, con marcas de tiempo en microsegundos que
siempre van creciendo; cada cuadro dura hasta el siguiente. Alimentá cada codificador desde un solo hilo.

### Imágenes estáticas y animadas
PNG (que se escribe como APNG cuando recibe más de un cuadro) y GIF, sin _FFmpeg_. Toman cuadros RGBA, BGRA, RGB o GRAY.

```java
// STATIC: ONE FRAME AND NO AUDIO TRACK
Encoder.Video png = new Encoder.Video(VideoCodec.PNG, 512, 512, PixelFormat.RGBA, 1);
try (Encoder encoder = CodecsAPI.encoder(Container.PNG, Path.of("logo.png"), png, null)) {
    encoder.video(pixels, 0);
}

// ANIMATED: MORE FRAMES; THE FULL CONSTRUCTOR ADDS QUALITY (0 TO 100, 50 BY DEFAULT) AND PLAYS (0 LOOPS FOREVER)
Encoder.Video gif = new Encoder.Video(VideoCodec.GIF, 320, 240, PixelFormat.BGRA, 30, 80, 0);
try (Encoder encoder = CodecsAPI.encoder(Container.GIF, Path.of("clip.gif"), gif, null)) {
    for (int i = 0; i < frames.length; i++) encoder.video(frames[i], i * 33_333L);
}
```

### Archivos de video
MP4, MOV, MKV y WebM con _FFmpeg_, más Ogg solo para audio. Toman cualquier `PixelFormat` crudo.

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60); // THE FULL CONSTRUCTOR SETS QUALITY, LIKE ON IMAGES
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) { // IOException WITHOUT FFMPEG
    encoder.video(frame, ptsUs);                             // EXACTLY video.frameBytes() BYTES
    encoder.audio(pcm);                                      // INTERLEAVED SAMPLES FROM TIME ZERO: PAD A LATE START WITH SILENCE
}                                                            // close() FINISHES THE FILE, WRITTEN AS <file>.part UNTIL THEN
```
