# 🦆 INTRODUCCIÓN
WATERMeDIA es una librería con una API multimedia para reproducir, decodificar, codificar y manejar video, audio e
imágenes. Usarla es bastante simple si ya laburaste con librerías como ExoPlayer (en Android) o VLCJ, pero acá todo está
mucho más enfocado, automatizado y listo para usar, así que nunca te tenés que pelear con detalles muy avanzados y
técnicos como la cantidad de buffers, los planos por cuadro, el orden de bytes o los strides de los datos.

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

Un montón de devs usan el esquema base y la mayoría se queda un buen rato en 0.x.y, clasificando reescrituras
como parches; acá la regla es más clara que el agua. Este esquema te ayuda a identificar qué implica subir cada número

**ESQUEMA DE INCREMENTOS**
``X.Y.Z-<channel>.W``
- ``X``: Reescritura total: todo lo que conocés de la librería se tiró abajo y se rehízo desde cero
- ``Y``: Cambios que rompen: refactors, renombres y eliminaciones de API que pueden romper (o directamente van a romper)
  tu implementación
- ``Z``: Hotfixes, mejoras, API nueva y anotaciones de deprecación, nada que rompa.
- ``<channel>``: canales ALPHA o BETA; las versiones release no usan este sufijo
  - Nota: en algunos casos extremos, este sufijo se puede omitir a propósito, porque los modloaders no lo manejan
    bien y suelen romper los rangos de versiones.
- ``W``: número de entrega ALPHA o BETA, se omite en el canal release

Esto es una GARANTÍA de que NO SE VAN A METER CAMBIOS QUE ROMPAN EN LOS INCREMENTOS DE ``Z``, así que podés requerir
watermedia tranquilo en un rango de ``[3.0.1,3.1)``

# 🗺 ÍNDICE

Elegí el camino que coincida con lo que estás armando; cada uno lista las secciones que necesita.

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

En entornos de Minecraft no hace falta nada de acá salvo respetar el rango de versiones del jar: sumá WATERMeDIA como
dependencia de compilación, declarala como dependencia requerida de tu mod dentro de los rangos que figuran en
[restricciones de software](#11-restricciones-de-software), y el loader la provee en tiempo de ejecución. Los clientes
también necesitan tener instalado _WATERMeDIA: Binaries_ (mirá [Binaries y FFmpeg](#01-binaries-y-ffmpeg)).

```gradle
repositories {
    maven { url "https://jitpack.io" }
}

dependencies {
    compileOnly "com.github.SrRapero720:watermedia:$watermedia_version" // A RELEASE TAG, SUCH AS 3.0.0
}
```

En aplicaciones Java propias o juegos hechos en Java, el entorno tiene que proveer:

```gradle
repositories {
    mavenCentral()
    maven { url "https://jitpack.io" }
}

dependencies {
    implementation "com.github.SrRapero720:watermedia:$watermedia_version"
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
- El launcher independiente extrae ese JAR a un archivo temporal, lo agrega al classpath de la JVM relanzada y descarga
  las demás librerías de ejecución que lista a medida que hacen falta; una librería en caché o descargada entra al
  classpath solo cuando coincide con el SHA-256 fijado al compilar.
- Una app que lo integra trae su propio classpath. Las dependencias de Gradle de desarrollo no prueban que una
  instalación de Minecraft traiga las demás librerías.
- El JAR de Binaries embebe y reubica su dependencia _XZ_, y el JAR de WATERMeDIA trae sus bindings de _JavaCPP_ y
  _FFmpeg_.

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

_WATERMeDIA: Binaries_ necesita WATERMeDIA y carga junto con él, preparando los nativos justo después de la
configuración y antes de los módulos de red, códecs y reproductor.

- Los clientes de Minecraft lo requieren; los servidores dedicados lo pueden omitir, ya que ahí no prepara nada.
- Las apps que integran la API Java también lo pueden omitir y conservan la decodificación de imágenes y los
  resolvedores de plataformas que no usan nativos; pierden la decodificación de video y las plataformas que dependen de
  _yt-dlp_.
- Si desactivás _FFmpeg_ (`codecs.ffmpeg.disable`), se saltea la extracción.
- Las rutas nativas quedan publicadas durante toda la vida del proceso, y no hay una API aparte para iniciar o apagar
  Binaries.
- _yt-dlp_ y _BotGuard_ se descargan la primera vez que se usan y se verifican antes de correr. Las propiedades del
  sistema `watermedia.ytdlp.binary` y `watermedia.botguard.binary` apuntan a tus propios ejecutables en su lugar.

Las builds propias de _FFmpeg_ van en `codecs.ffmpeg.customPath`: una carpeta con las librerías nativas, no un programa
`ffmpeg.exe`. Pueden usar GPL, LGPL o cualquier otra licencia y versión declarada: el loader revisa las capacidades
nativas requeridas, no una lista permitida de versiones o licencias, y verifica la integridad solo de los nativos
incluidos. Igual necesitan bindings JNI compatibles con la API Java que usa WATERMeDIA, más el soporte TLS de la build
incluida (mirá [Confianza HTTPS y credenciales](#25-confianza-https-y-credenciales)).

## 0.2 CONFIGURACIÓN

> [!NOTE]
> Todos los campos son configurables y se describen solos: su comentario en `config/watermedia.toml` es la documentación
> de cómo funcionan, y los valores los decidís vos. Cada sección de esta guía lista los campos que la afectan.

WATERMeDIA escribe su configuración en `config/watermedia.toml` dentro del directorio de trabajo, con un comentario
arriba de cada campo. Los valores por defecto andan de una, los campos que se leen al arrancar se aplican en el próximo
inicio, y cuando un backend parece no estar disponible, este archivo es el primer lugar donde fijarse.

- `codecs.*`: _FFmpeg_, la decodificación por hardware y los decodificadores de imágenes.
- `player.*`: la calidad por defecto y las cachés.
- `platforms.*`: contenido para adultos, búsqueda y cuentas de plataformas.
- `network.*`: el servidor de archivos y las solicitudes web.

# 1. ARRANQUE

En modloaders populares con soporte nativo como Forge, NeoForge y Fabric, el loader arranca WATERMeDIA por vos: no hace
falta que sigas esta sección.

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

Así que `steps()` es 5 con Binaries y 4 sin él, y `loading()`, `step()` y `steps()` se pueden consultar sin problema
desde cualquier hilo para una pantalla de carga.

- `start` carga cada módulo una sola vez en toda la vida del proceso. Nada se descarga de memoria nunca: no hay stop, un
  segundo `start` lanza una excepción, y un arranque fallido no se puede reintentar en el mismo proceso.
- Un directorio temporal null pasa a ser `<java.io.tmpdir>/watermedia` y un directorio de trabajo null, el actual. El
  último argumento elige el entorno de cliente; los módulos solo de cliente (Binaries, códecs, reproductor) se saltean
  su arranque en un servidor dedicado.
- Las partes opcionales (el servidor de archivos, _FFmpeg_) registran su falla en el log y quedan apagadas mientras la
  carga termina, así que revisá la capacidad que necesitás, como `CodecsAPI.ffmpegLoaded()`.
- Cualquier otra falla (la configuración, un módulo de Binaries instalado) hace que `start` lance
  `IllegalStateException` con esa falla como causa, y `loaded()` queda en false.
- `start` se niega a correr desde un hilo worker de WATERMeDIA, así que nunca lo llames desde uno de sus callbacks.

Cada API sabe por sí misma si su módulo cargó:

- `PlayerAPI.mrl` y los reproductores de `PlayerAPI.player` lanzan `IllegalStateException` hasta que arranca el módulo
  del reproductor, y siempre lo hacen en servidores dedicados.
- `NetworkAPI.upload` lanza una excepción hasta que arranca el módulo de red. Además rechaza de entrada todo lo que no
  sea un archivo existente; en cambio, un servidor remoto inalcanzable o mal configurado hace fallar el estado devuelto.
- `PlatformAPI` no necesita arranque: su registro es estático y `register`, `fetch` y `search` funcionan en cualquier
  momento. Los handlers que necesitan Binaries (YouTube, _yt-dlp_) fallan con su propia `PlatformException` hasta que
  carga, y siempre en servidores dedicados; sin el JAR de Binaries no existen, así que `fetch` devuelve null para sus
  links.
- La decodificación de imágenes en Java puro con `CodecsAPI`, `ServerMediaPlayer`, `MathUtil` y `MathEases` no necesitan
  ningún arranque.

Un reproductor es dueño de los motores que crean los suppliers que le pasás a `PlayerAPI.player`. Creá los motores de
forma diferida con suppliers, así una fuente no disponible no deja fugas de recursos nativos reservados de antemano. Tu
app es dueña de sus contextos OpenGL, Vulkan y OpenAL: liberá cada reproductor en el contexto correcto del host antes de
destruir esos contextos, y creá motores nuevos después de recargar un dispositivo o un contexto. Las librerías nativas
nunca se descargan de memoria y no pueden cambiar de versión dentro de una JVM en ejecución.

## 1.1 RESTRICCIONES DE SOFTWARE

### Launchers bloqueados

Los bootstraps de Minecraft se niegan a arrancar cuando el juego se lanzó con _TLauncher_. Lo detectan por su mod de
skins inyectado, sus clases, sus archivos en el class path, sus opciones de JVM, sus hilos y sus procesos padre, y
fallan nombrando la evidencia; los restos de una instalación vieja en el directorio del juego o en el perfil del usuario
solo dejan una advertencia en el log. Las apps que lo integran se saltean este chequeo.

### Versiones compatibles

Forge, NeoForge y Fabric cargan WATERMeDIA en clientes y servidores dedicados, y rechazan las versiones de Minecraft que
no están en esta lista:

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 o una 40.x más nueva | ⛔ | 0.14 o una 0.x más nueva |
| 1.19.2 | 43.5.0 o una 43.x más nueva | ⛔ | 0.14 o una 0.x más nueva |
| 1.20.1 | 47.4.2 o una 47.x más nueva | ⛔ | 0.14 o una 0.x más nueva |
| 1.21.1 | 52.1.1 o una 52.x más nueva | 21.1.0 o una 21.1.x más nueva | 0.14 o una 0.x más nueva |
| 1.21.11 | 61.0.5 o una 61.x más nueva | 21.11.0 o una 21.11.x más nueva | 0.14 o una 0.x más nueva |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 o una 0.x más nueva |
| 26.2 | 65.x | 26.2.0.x | 0.14 o una 0.x más nueva |

- En Minecraft se acepta desde Java 17 hasta 25.
- WATERMeDIA y Binaries se aceptan entre sí desde 3.0.0 hasta 3.1.0 (sin incluirla); las builds BETA anteriores de 3.0.0
  se rechazan.
- Fabric no puede limitar una dependencia a los clientes, así que su bootstrap exige Binaries en los clientes y su
  manifiesto rechaza las versiones incompatibles de Binaries.

## 1.2 PAQUETES PÚBLICOS E INTERNOS

La API pública son los paquetes `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` y
`api.util`, sin sus subpaquetes. Todo lo demás es interno: los lectores, codificadores y estructuras de chunks de los
códecs, las colas y cachés del reproductor, las plataformas integradas, el bootstrap, `org.watermedia.tools` y los
bindings de _FFmpeg_ incluidos. Binaries también usa `org.watermedia.tools` para sus utilidades de digest, archivos y
JSON.

El JAR no declara ningún módulo de Java, así que ningún loader bloquea los paquetes internos; igual tratá ese límite
como el contrato y usá los puntos de entrada públicos, como `PlayerAPI.player` y `PlatformAPI.fetch`, en vez de las
clases internas que hay detrás. Quienes contribuyen pueden correr `gradle javadocInventory` desde la raíz del proyecto
para listar los métodos públicos sin docs, con nombres de archivo y números de línea; solo reporta, y solo revisa los
paquetes públicos listados en `public_packages` de `gradle.properties`.

> [!WARNING]
> `CodecsAPI` es experimental: `decode`, `reader`, `sniff` y `encoder`, más los `ImageReader`, `Encoder` y filtros que
> entregan, pueden cambiar o romperse en cualquier actualización sin seguir el esquema de versiones, así que ojo: no los
> uses en tus mods todavía. Los reproductores multimedia ya usan los códecs por vos, y los chequeos de estado
> (`ffmpegLoaded()`, `ffmpegError()`, `vulkanDecode()`) se pueden llamar sin problema.

## 1.3 DIAGNÓSTICO Y EJEMPLOS

Los loggers del proyecto son `watermedia` y `watermedia_binaries`; poné esos nombres en DEBUG para diagnosticar.

- Los mensajes de log del proyecto muestran los links multimedia completos, así las rutas y los IDs siguen sirviendo
  para depurar. Enmascaran las contraseñas de los links (`user:password@`), los tokens JWT sueltos, los valores de
  `Authorization` y `Cookie`, los argumentos de sesión de Minecraft y los pares `key=value` que parecen secretos (los
  logs que sube la app pasan por el mismo filtro). Las trazas de excepciones que llevan un secreto conservan su tipo,
  stack, causas y fallas suprimidas como texto sanitizado.
- Nada se agrupa por ventana de tiempo: una anomalía que se puede repetir (un buffer de cuadros corto, un layout
  rechazado, un callback que falla, una subida rechazada) se registra la primera vez que pasa en cada motor,
  reproductor, servidor o caché y después se queda callada, y las iteraciones lentas de reproducción se cuentan y se
  reportan una sola vez cuando termina la ejecución.
- _FFmpeg_ sigue escribiendo sus propias líneas de log nativas en la salida de error del proceso, por fuera de los
  loggers del proyecto y de su filtro de secretos; mandarlas por el logger del proyecto ya está listo, pero desactivado
  hasta validar macOS y Linux.
- `-Dwatermedia.glchecks=true` activa los chequeos de errores de OpenGL en la ruta de subida del motor.

`src/test/java/org/watermedia/ApiGuideExample.java` compila el uso de las fábricas para cada motor gráfico y de audio y
los dos lados de la reproducción sincronizada, el reloj y un seguidor. Su ejemplo de decodificación de imágenes, que
filtra cada cuadro con un `DownscaleFilter`, corre en `ApiGuideExampleTest`; el comportamiento de GUI, de dispositivos
de audio y de Minecraft necesita la integración de host correspondiente. Las suites de tests de reproducción y de audio
espacial cubren por separado el comportamiento real de _FFmpeg_ y de OpenAL. Las corridas de tests que arrancan con
`-Prequire_natives=true` (los jobs nativos de CI) fallan en vez de saltearse cuando faltan los nativos de _FFmpeg_.

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

Un link directo falla cuando el servidor responde con un estado de error, una página web o un script; las playlists HLS
se abren como un solo video y las playlists IPTV como una fuente por canal.

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

Una búsqueda nueva reemplaza a la que está en curso, y una búsqueda que parece la anterior todavía a medio escribir
espera un momento a la próxima tecla. Twitch y Kick solo listan los canales que están en vivo en este momento.

## 2.4 EL PROTOCOLO WATER

```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

Los links remotos y globales llevan el ID de archivo que dio el servidor, no una ruta de carpeta.

## 2.5 CONFIANZA HTTPS Y CREDENCIALES

HTTPS anda de una con cualquier certificado en el que Java ya confía. La reproducción valida cada certificado contra el
truststore de la JVM que WATERMeDIA lee al arrancar, y ese chequeo nunca se desactiva.

Para usar un certificado autofirmado o tu propia autoridad certificante, importalo en un truststore y hacé que Java
apunte a él antes de que arranque WATERMeDIA (adentro de Minecraft, agregá las mismas opciones `-D` a los argumentos de
JVM del launcher):

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- Un truststore vacío o roto impide que _FFmpeg_ arranque, así que el video queda apagado hasta que lo arregles.
- Una build propia de _FFmpeg_ necesita el mismo soporte TLS que la incluida.
- Los headers de login y las cookies (`Authorization`, `Cookie`, `X-WaterMedia-Token`) solo llegan al servidor de la
  primera solicitud, nunca a una redirección hacia otro, y las redirecciones solo pueden apuntar a links http o https.

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

`player` devuelve null cuando la fuente todavía se está resolviendo, el índice es inválido, el backend necesario no está
disponible o la construcción lanza una `Exception`; un `Error` se propaga. Revisá el estado del MRL antes de decidir si
reintentás.

- Los suppliers corren solo cuando hace falta, y las imágenes nunca llaman al supplier de audio.
- Un supplier que devuelve null omite esa salida, como `() -> null` como motor gráfico para reproducir solo audio.
- Nunca compartas un motor entre reproductores.

`TxMediaPlayer` reproduce las imágenes y animaciones soportadas, `FFMediaPlayer` reproduce audio y video con _FFmpeg_, y
`ServerMediaPlayer` es un reloj de sincronización sin decodificación ni motores nativos.

Los tres reproductores comparten los mismos controles. Los controles de reproducción devuelven `true` cuando la llamada
tuvo efecto y `false` cuando se rechazó: imposible en este momento, después de `release()` o con una velocidad fuera de
rango; los ajustes de salida no devuelven nada.

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

Llamá a `speed(...)` donde el contexto de audio esté activo: el reloj del reproductor solo sigue una velocidad que el
motor de audio aceptó. El escalado y el nivel de detalle funcionan con los layouts de píxeles soportados, y los motores
gráficos incluidos todavía no aceptan texturas comprimidas BC.

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

- Un listener corre en el hilo que haya provocado el cambio: un hilo de reproducción, el ticker compartido de
  sincronización, o quien llame a `status()`, `time()` o `texture()` en los reproductores de imágenes cuyo reloj
  resuelve el final de forma perezosa.
- Nunca corre mientras el reproductor tiene tomado uno de sus locks, ni en un hilo de reproducción al que `release()`
  está esperando. `release()` entrega su `STOPPED` una vez que termina y los motores quedan liberados, así que cuando
  corre ese callback no queda nada pendiente del reproductor.

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

`Status.allows(next)` indica si una pausa, una reanudación, un buffering, un final, una detención o una falla pueden
llevar un estado directo a `next`. Arrancar, saltar (seek) y reiniciar van por sus propios caminos: un reproductor sale
de `LOADING` solo cuando se abre su pipeline (o cuando se detiene o falla), un salto en `ENDED` o `STOPPED` lo reinicia,
y `STOPPED` no permite ningún paso directo.

## 3.2 PISTAS DE AUDIO E IDIOMAS

Los videos doblados y los archivos con varios streams de audio reproducen su audio original (o el predeterminado) salvo
que pidas otra cosa. Las plataformas listan las pistas de audio separadas de una fuente con la original primero, y
adentro de un archivo o de un stream HLS gana el stream marcado como predeterminado.

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- El orden de elección es: lo que elegiste con `audioTrack(...)`, después `audioLanguage(...)`, después la original (la
  primera pista separada) o, sin pistas separadas, el stream predeterminado del archivo, y después el primero. Una
  etiqueta de idioma coincide primero con la misma etiqueta y después con el mismo idioma, así que `es-MX` toma
  `es-419`, `es` o el `spa` que traiga un archivo. No hay idioma automático: sin `audioLanguage(...)` suena la original,
  nunca un doblaje automático.
- `audioTracks()` lista un `AudioTrack(track, stream, name, lang)` por cada stream de audio: primero los de la entrada
  principal, después cada pista separada de `source().audioTracks()`. `track` es `AudioTrack.MAIN` o el índice de la
  pista separada, y `stream` es el índice de stream de _FFmpeg_ adentro de ella.
- Una pista separada que todavía no sonó aparece como una sola entrada `provisional()` (`stream` es -1) armada con el
  nombre y el idioma de la plataforma, así listar nunca abre todos los doblajes de entrada. Una vez que suena, sus
  streams reales toman su lugar. La lista es una instantánea inmutable que cualquier hilo puede leer.
- `audioTrack(track)` devuelve `true` cuando la elección cambió. Un reproductor en marcha reconstruye su pipeline en el
  tiempo actual (un stream en vivo vuelve a arrancar en su borde), y la elección sobrevive a los cambios de calidad y a
  los reinicios. `audioTrack()` devuelve lo que suena ahora. Los reproductores de imágenes y de servidor, y los
  reproductores sin motor de audio, no listan nada.

## 3.3 ELEGIR MOTORES

El desarrollo de Vulkan va primero; OpenGL sigue soportado como backend secundario. Las decisiones de backend le
corresponden a tu capa de renderizado.

| Fábrica | Obligación del host |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | El executor corre tareas en el hilo dueño del contexto OpenGL y se tiene que seguir bombeando en cada cuadro mientras la reproducción o la liberación puedan estar esperándolo, una vez más también después de `release()`. Con los dos argumentos en null, el hilo que llama tiene que ser el dueño del contexto. Nunca muevas un motor entre contextos. |
| `PlayerAPI.vkEngine(context)` | Pasá un `VKContext` cuyos objetos de Vulkan y mecanismo de retiro sobrevivan al motor: tomá `queueLock()` alrededor de tus propios submits y presents, y ejecutá los callbacks de `retire(...)` una vez que terminen tus cuadros en vuelo (todos cuando el dispositivo desaparece). Para decodificar video en la GPU, mirá [Decodificación con Vulkan](#decodificación-con-vulkan). |
| `PlayerAPI.awtEngine(onFrame)` | Pintá la imagen publicada desde la UI de AWT/Swing; pasá el callback opcional al hilo de la UI según haga falta. |
| `PlayerAPI.jfxEngine(onFrame)` | Necesita un runtime de JavaFX 13+; enlazá la imagen del motor a tu UI. Sin él, la fábrica lanza `IllegalStateException` en vez de un error de linkeo. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Captura los cuadros subidos sin un contexto gráfico, re útil para tests y procesamiento de imágenes. Como todo motor y todo reproductor multimedia, solo existe en un cliente; un servidor dedicado solo puede crear relojes `ServerMediaPlayer`. |
| `PlayerAPI.alEngine()` | El contexto OpenAL ya está activo y sigue siendo válido en los hilos que hacen el laburo de audio. |
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

Las clases base de los motores son sealed: tu integración aporta contextos y executors del host, no implementaciones
propias de motores. Los motores manejan sus propios recursos de reproducción, incluidas las texturas, las fuentes de
audio y los buffers.

Todos los motores siguen un mismo ciclo de vida:

- `release()` es idempotente, nunca tiene que correr al mismo tiempo que el reproductor que alimenta al motor, y lo deja
  inerte: `released()` es true y `texture()` devuelve 0.
- El reproductor revisa `supports(format, bits)` antes de subir y vuelve a BGRA de 8 bits cuando el motor rechaza un
  layout.
- Las subidas nunca lanzan excepciones: una falla del driver descarta el cuadro con un solo error en el log, y el último
  cuadro queda en pantalla.
- Los motores por software y headless exponen un centinela distinto de cero en vez de un handle de GPU, así que compará
  el handle solo contra 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` y `frameBytes` calculan el tamaño de cada plano del `format()`,
  `width()`, `height()` y `bits()` de un motor, por ejemplo para presupuestar su memoria. Los planos submuestreados
  redondean para arriba como _FFmpeg_, así que los tamaños impares conservan su última columna y su última fila de
  croma.

Manejo del color:

- Los motores OpenGL y Vulkan convierten YUV con la matriz (BT.601, BT.709 o BT.2020), el rango y la posición de croma
  que declara cada video. Cuando no declara nada, adivinan como _mpv_: BT.601 para cuadros SD (de menos de 1280 píxeles
  de ancho y hasta 576 líneas de alto), y BT.709 para el resto.
- El HDR PQ y HLG se muestra como SDR, con los colores BT.2020 mapeados a BT.709 y las luces altas comprimidas para que
  un pico de 1000 nits llegue al blanco.
- Los motores sobre un mismo contexto OpenGL o dispositivo Vulkan arman esa conversión una sola vez y la comparten. Se
  va con el trabajo de liberación del último motor (la tarea que `release()` encola en el executor de render de OpenGL,
  o los callbacks de `VKContext.retire`), así que liberá cada motor y dejá que ese trabajo corra antes de destruir el
  contexto o el dispositivo.
- Los motores por software reciben BGRA que _FFmpeg_ convierte con la matriz y el rango declarados, sin tone mapping de
  HDR.

OpenAL necesita su contexto original activo en cada hilo que maneja el reproductor, incluido el ticker de
sincronización. El motor de Java Sound serializa sus propias llamadas, así que no necesita locks extra, y reabre una
línea más grande cuando un bloque de audio supera el buffer de la línea, descartando lo que la línea vieja todavía
tenía.

> [!IMPORTANT]
> **OpenAL:** el audio de 4, 6, 7 y 8 canales necesita `AL_EXT_MCFORMATS` en un motor común (los motores espaciales
> mezclan todo a estéreo), las muestras float y double en mono y estéreo necesitan `AL_EXT_FLOAT32` y `AL_EXT_DOUBLE`
> (el float surround viene con `AL_EXT_MCFORMATS`), y un reloj que tenga en cuenta la latencia de salida del dispositivo
> necesita `AL_SOFT_source_latency`; sin eso, el audio se convierte a lo que el dispositivo reproduce. Los
> [procesadores de entorno](#procesadores-de-entorno) que usan EFX necesitan `ALC_EXT_EFX`.

La app independiente (abrir el JAR de WATERMeDIA) renderiza con Vulkan salvo que el usuario elija OpenGL; cuando Vulkan
no está disponible, corre ese inicio en OpenGL y mantiene la elección guardada. `-Dwatermedia.engine=opengl` o
`-Dwatermedia.engine=vulkan` fuerza el motor para un solo inicio sin guardarlo. La app guarda su motor, la escala de la
interfaz (`0` sigue al monitor; si no, un factor de 1 a 3 en pasos de 0.25, que en Settings se muestra como `Auto`,
`x1.0`, `x1.25` y así), el efecto CRT y el sonido de clic en `config/app.properties` dentro de su directorio de trabajo.

### Decodificación con Vulkan

Por defecto, `VKContext.deviceInfo()` devuelve null y los videos se decodifican en otro lado y después se suben desde la
RAM. Sobrescribilo con la forma en que se creó tu dispositivo (la cadena de features que le pasaste a `vkCreateDevice`,
que tiene que seguir reservada y sin cambios mientras viva el dispositivo, más las extensiones de instancia y de
dispositivo habilitadas y las colas creadas por familia) y _FFmpeg_ decodifica H.264, H.265 y AV1 con Vulkan Video,
mientras el motor muestrea las imágenes decodificadas sin pasar por la RAM.

> [!IMPORTANT]
> La decodificación con Vulkan necesita:
> - Una instancia y un dispositivo Vulkan 1.3 con `timelineSemaphore` y `synchronization2` habilitados.
> - `VK_KHR_video_queue`, `VK_KHR_video_decode_queue` y la extensión de decodificación de cada códec que quieras
>   acelerar: `VK_KHR_video_decode_h264`, `VK_KHR_video_decode_h265` o `VK_KHR_video_decode_av1`.
> - Colas libres en tu dispositivo, o las extensiones de memoria y semáforos externos para un gemelo, las dos opciones
>   explicadas abajo.
> - Nativos con Vulkan Video (`CodecsAPI.vulkanDecode()` devuelve true) y `codecs.ffmpeg.hardwareAccel` activado.

Entonces _FFmpeg_ decodifica en uno de dos lugares:

- **Tu dispositivo**, cuando además tiene colas de las que _FFmpeg_ se puede adueñar: una cola con capacidad de cómputo
  (que también acepta transferencias) distinta de `queue()`, más una cola de decodificación de video, con `queue()`
  creada última en `queueFamily()`. Todas las demás colas de cada familia pasan a _FFmpeg_, que les hace submit desde
  sus propios hilos, así que mientras existan reproductores nunca toques esas colas ni llames a `vkDeviceWaitIdle`;
  esperá en tu propia cola (`vkQueueWaitIdle` con `queueLock()` tomado).
- **Un gemelo**, cuando tu dispositivo no tiene esas colas pero habilita `VK_KHR_external_memory_win32` y
  `VK_KHR_external_semaphore_win32` en Windows, o `VK_KHR_external_memory_fd` y `VK_KHR_external_semaphore_fd` en el
  resto. El motor crea un segundo dispositivo en la misma GPU con tu cadena de features y tus extensiones, _FFmpeg_
  decodifica ahí, y el motor importa cada imagen decodificada a tu dispositivo. Tus colas siguen siendo tuyas.

Sea como sea, liberá cada reproductor antes de destruir el dispositivo o su instancia: _FFmpeg_ los usa, gemelo
incluido, hasta que se va su último decodificador.

> [!IMPORTANT]
> Los videos 4:2:2, 4:4:4 y de 12 bits solo se decodifican en la GPU con _libplacebo_, que convierte cada layout
> decodificado en RGBA SDR (con tone mapping del HDR). Necesita `hostQueryReset`, `runtimeDescriptorArray`,
> `shaderStorageImageArrayNonUniformIndexing` y `maintenance4` en el dispositivo que decodifica y, en tu propio
> dispositivo, una segunda cola en `queueFamily()`; un gemelo habilita esas features por su cuenta cuando la GPU las
> tiene. Sin _libplacebo_, solo los streams 4:2:0 (de 8 y 10 bits) se decodifican en la GPU, y el motor los convierte
> con sus propios shaders.

## 3.4 AUDIO ESPACIAL

Creá un motor OpenAL con `PlayerAPI.alEngine(true)` antes de armar el reproductor. El modo espacial queda fijo durante
toda la vida del motor: _FFmpeg_ mezcla las fuentes estéreo y surround a estéreo (el canal central llega a los dos
lados, las fuentes mono siguen en mono) y el motor las reproduce con sinks, que son fuentes mono posicionales. La
fábrica común `alEngine()` reproduce todos los canales en una sola fuente seca y relativa al oyente, aunque los layouts
que el dispositivo no puede reproducir igual se pueden convertir a la cantidad de canales más cercana. Java Sound
devuelve `false` en las actualizaciones espaciales y no expone ninguna fuente nativa.

El motor arranca con un sink, el primario, que reproduce audio `Mix.MONO` seco y relativo al oyente. Pasale un estado
`SpatialAudio` para ubicarlo en el mundo, o `null` para volver a la reproducción seca y relativa al oyente. El volumen,
el mute, la velocidad, la pausa y el seguimiento del reloj (`sync`) no dependen de los ajustes espaciales y llegan a
todos los sinks.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Creá el motor, actualizalo y liberalo en el executor de sonido del host, con el contexto OpenAL original activo. El host
es dueño del dispositivo, el contexto y el oyente, y tienen que sobrevivir a todos los reproductores que los usan.

- `FFMediaPlayer` usa hilos de reproducción privados y, como la reproducción común de `ALEngine`, hoy necesita que el
  contexto de la fuente sea el contexto activo del proceso en esos hilos. Los hosts que exponen su contexto de audio
  solo con un enlace thread-local no están soportados: enlazarlo nada más en el hilo de sonido del que llama no alcanza.
- Las operaciones nativas del motor rechazan un contexto distinto o ausente; nunca cambian ni recrean el contexto del
  host.
- Liberá los reproductores antes de recargar un dispositivo, y después creá motores nuevos en el contexto nuevo.
- `FFMediaPlayer.release()` interrumpe y espera (join) su pipeline de reproducción antes de borrar la fuente de audio;
  correlo antes de que se destruya el contexto, nunca como callback desde ese pipeline.

`SpatialAudio` valida posiciones finitas que entren en un float de OpenAL, `0 < referenceDistance < maxDistance` y un
rolloff finito y no negativo; un rolloff de cero apaga la atenuación por distancia. El modelo de distancia del host
decide cómo estos parámetros atenúan el audio, y la API nunca toca el modelo de distancia global, el oyente ni la
velocidad de la fuente, así que `maxDistance` no es un radio de silencio universal bajo cualquier modelo de OpenAL.

### Sinks: pares estéreo y parlantes extra

Una sola decodificación alimenta todos los sinks de un motor espacial, así que una imagen estéreo o un parlante ubicado
en otro lado no cuestan una segunda descarga ni una segunda decodificación. Cada sink reproduce un `ALEngine.Mix` del
audio decodificado: `LEFT`, `RIGHT` o `MONO` (el promedio de los dos). Una fuente mono reproduce las mismas muestras en
cada mezcla, así que un par estéreo nunca se queda mudo con contenido mono.

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

- El sink primario es dueño de `player.audioSource()`. Maneja el reloj del contenido y vive tanto como el motor; su
  `release()` devuelve `false`. `player.sinks()` lista primero el primario, y tanto `sinks()` como `addSink(...)` dan
  una lista vacía o `null` sin un motor OpenAL espacial.
- Todos los sinks encolan las mismas muestras, y la reproducción, la pausa, el vaciado, la velocidad y el volumen corren
  como un solo lote en todos, así un par estéreo se mantiene alineado a la muestra. Sumar un sink durante la
  reproducción, o cambiar una mezcla con contenido estéreo, reconstruye todas las colas sobre la muestra del primario,
  lo que pausa los sinks durante más o menos una actualización del mezclador.
- Cada sink tiene su propio `spatialAudio(...)`: el adaptador de entorno recibe la fuente de ese sink en `process` y
  `reset`, y `sink.release()` lo resetea antes de borrar la fuente. Liberar el reproductor libera todos los sinks.
- Sumá, cambiá y liberá sinks en el executor de sonido del host con el contexto del motor activo, como cualquier otra
  actualización espacial. Sus consultas (`source()`, `mix()`, `spatialAudio()`, `released()`) no necesitan contexto.
- Un host puede adoptar la fuente de un sink en su propio sistema de sonido y escribir su ganancia, su pitch y su
  posición, pero nunca la puede reproducir, encolar, desencolar ni borrar: el motor es dueño de su transporte y sus
  buffers. Un sink nuevo arranca con la ganancia y el pitch actuales del motor.

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
- Cada actualización restaura la posición original en el mundo y limpia los filtros y envíos auxiliares anteriores de la
  fuente antes de llamar a `process`, así que un procesador puede mover `AL_POSITION` y tiene que aplicar su entorno
  completo en cada llamada.
- Limitá la frecuencia de las actualizaciones en el host en vez de retornar antes de tiempo dentro del procesador, y
  repartí muchos reproductores entre varios ticks.
- El motor borra solo su propia fuente y sus buffers, nunca los filtros ni los slots de efectos del procesador.
- Si `process` o `reset` lanzan una excepción, los efectos de la fuente se limpian y la excepción se propaga; un `reset`
  que falla no puede impedir que la fuente se borre durante la liberación.
- `auxOnly = true` le pide al procesador solo la salida reflejada; no silencia el camino directo por sí solo, así que
  muteá el reproductor cuando necesites silencio estricto.
- El procesador solo corre durante tus actualizaciones explícitas, nunca desde el loop de decodificación o de
  reproducción de _FFmpeg_. El posicionamiento básico funciona sin EFX; un procesador que usa EFX necesita un
  dispositivo que lo exponga.

### Verificación

`SpatialAudioTest` cubre el rechazo de argumentos inválidos, las coordenadas y la atenuación reales de las fuentes
OpenAL, la restauración del origen en el callback, la limpieza de efectos, el manejo de excepciones, los contextos que
no coinciden y las llamadas tardías después de la liberación. También genera un WAV estéreo y lo reproduce con
`FFMediaPlayer`, verificando que la decodificación estéreo llegue a cada sink como PCM mono en buffers reales de OpenAL.
`ALSinkTest` cubre el conjunto de sinks, las mezclas en cada tipo de muestra, los controles en lote, la contrapresión,
los sinks que se suman sobre la muestra del primario y la recuperación de underruns. Los tests de dispositivo necesitan
un dispositivo de salida OpenAL, y los casos de decodificación también necesitan los nativos de _FFmpeg_.

## 3.5 REPRODUCCIÓN SINCRONIZADA

`PlayerAPI.serverPlayer()` arma un `ServerMediaPlayer`, un reloj sin contenido ni motores que corre en cualquiera de los
dos lados. Se encarga de la matemática del tiempo: el arranque, la pausa, el salto, la velocidad, la repetición y la
vuelta del loop o la transición a `ENDED` corren sobre el reloj del sistema, y cada cambio exitoso incrementa
`revision()`. El host se encarga de todo lo demás: quién puede manejar el reloj, cómo llegan sus snapshots a los
clientes y dónde se guarda su posición. Los motores y los reproductores no tienen constructores públicos: cada instancia
sale de `PlayerAPI`.

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
- Trae la reproducción de vuelta con un `seekQuick` de frecuencia limitada una vez que `drift()` supera `tolerance(ms)`
  (un segundo por defecto).

El último snapshot recibido siempre gana, así que mandalos en el orden en que los produjo el reloj. `authority()`
devuelve ese snapshot, `authorityTime()` extrapola dónde está el reloj ahora y `following()` indica si un reproductor
sigue a uno. La corrección nunca recorta la velocidad de reproducción; se saltea mientras el reproductor carga o hace
buffering, y toma el camino más corto alrededor del límite del loop en contenido que se repite.

Un control llamado sobre un reproductor que sigue al reloj se aplica en el acto y se deshace en el siguiente tick que no
coincida, así que mandá los controles del usuario al reloj con tus propios paquetes y dejá que el snapshot traiga el
resultado. Avanzar cuadro por cuadro se rechaza mientras sigue al reloj, y el volumen, el mute, el escalado y el audio
espacial siempre quedan locales.

El reloj mantiene su posición (cero después de `start()`) hasta que `syncDuration(ms)` o `live(true)` le dicen sobre qué
línea de tiempo corre. Reportá la duración una sola vez desde un cliente que abrió la fuente (el primer valor positivo
gana durante toda la vida del reloj, y los valores distintos que lleguen después se ignoran), y restaurá una posición
guardada con `syncTime(ms)` después de arrancar el reloj, ya que `start()` reinicia desde cero. `canSeek()` es false y
`seek(...)` devuelve `false` hasta que se conozca una duración, y siempre en una sesión en vivo. `release()` congela la
posición y deja el reloj en `STOPPED` con una revisión nueva, así que guardá y mandá ese último snapshot antes de
soltarlo, o los reproductores siguen extrapolando el anterior. Liberá cada reproductor cuando termine la sesión de su
host.

### Formato del snapshot

`MediaPlayer.PlayerState` es un registro fijo big-endian de 27 bytes (versión, revisión, estado, tiempo, duración,
velocidad, flags). `PlayerState.read(ByteBuffer)` consume solo esos bytes y deja los datos que siguen para tu
transporte; el orden de bytes del propio buffer no importa, y un payload rechazado deja la posición sin tocar. Verificá
en tu transporte quién mandó un snapshot: la librería nunca identifica a los pares.

# 4. SUBIR Y DESCARGAR ARCHIVOS

> [!WARNING]
> El servidor habla HTTP plano: ponelo detrás de tu propio proxy HTTPS cuando quede expuesto a internet. Se niega a
> escuchar más allá de `127.0.0.1` con el token por defecto, y cambiar el token en cada actualización del servidor evita
> que cualquier desconocido suba archivos.

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

`CodecsAPI` entrega un decodificador (`ImageReader`) y un codificador (`Encoder`). Las imágenes, estáticas o animadas,
usan nuestros propios códecs en Java puro y no necesitan ni arranque ni _FFmpeg_; el video pasa por _FFmpeg_.

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
