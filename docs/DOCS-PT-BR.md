# 🦆 INTRODUÇÃO
WATERMeDIA é uma biblioteca com uma API multimídia para reproduzir, decodificar, codificar e manipular vídeo,
áudio e imagens. Se você já usou _ExoPlayer_ ou _VLCJ_, vai parecer familiar, mas aqui tudo já vem pronto: você nunca
briga com buffers, planos, ordem de bytes ou strides.

Ela resolve links de mídia (YouTube, Twitch, arquivos diretos e muitos outros), decodifica e entrega os
frames para a sua engine gráfica (OpenGL, Vulkan, AWT, JavaFX) e o som para a sua engine de áudio
(OpenAL, Java Sound). Imagens e animações passam pelos nossos próprios decodificadores em Java puro; vídeo e
áudio passam pelo _FFmpeg_, que vem no mod companheiro _WATERMeDIA: Binaries_. Ela precisa do Java
17 ou mais recente.

Todo exemplo deste guia é um trecho de integração: variáveis como `renderThread`,
`renderExecutor` ou `mrl` vêm do seu próprio código.

# 🪁 ESQUEMA DE VERSÕES
> [!IMPORTANT]
> Este esquema não se aplica a APIs experimentais nem a código interno

Cada número diz o que mudou e se isso pode quebrar alguma coisa.

**ESQUEMA DE INCREMENTO**
``X.Y.Z-<channel>.W``
- ``X``: Reescrita completa: tudo o que você conhece da biblioteca foi jogado fora e refeito do zero
- ``Y``: Mudanças que quebram: Refatorações, renomeações e remoções de API que podem quebrar (ou vão quebrar) a sua
  implementação
- ``Z``: Hotfix, Melhorias, API nova e Anotações de depreciação, nada que quebre.
- ``<channel>``: Canais ALPHA ou BETA; a release não usa este sufixo de versão
  - Obs.: às vezes ele fica de fora de propósito, porque alguns modloaders quebram os intervalos de versão com ele.
- ``W``: Lançamento da versão ALPHA ou BETA, omitido no canal release

Isso é uma GARANTIA de que NENHUMA MUDANÇA QUE QUEBRE ALGO VAI ENTRAR NOS BUMPS DE ``Z``, então é seguro você exigir
o watermedia num intervalo de ``[3.0.1,3.1)``

# 🗺 ÍNDICE
Escolha o caminho que combina com o que você está construindo.

## MINECRAFT: MODDERS QUE ADICIONAM VÍDEO
1. [Instalação](#0-instalação): adicione a dependência e respeite o intervalo de versões.
2. [Restrições de software](#11-restrições-de-software): versões de Minecraft e de loaders suportadas.
3. [Gerenciamento de links](#2-gerenciamento-de-links): transforme um link em fontes de mídia.
4. [Criação e controle de um player](#31-criação-e-controle-de-um-player).
5. [Escolha de engines](#33-escolha-de-engines): OpenGL, Vulkan e OpenAL nos próprios contextos do jogo.
6. [Áudio espacial](#34-áudio-espacial): telas posicionais, pares estéreo e alto-falantes.
7. [Reprodução sincronizada](#35-reprodução-sincronizada): todos os players assistem ao mesmo momento.
8. [Upload e download de arquivos](#4-upload-e-download-de-arquivos): compartilhe arquivos pelo servidor de arquivos.

## APLICATIVOS DE DESKTOP
1. [Instalação](#0-instalação): dependências do Gradle, bibliotecas de runtime e [configuração](#02-configuração).
2. [Inicialização](#1-inicialização): inicie o WATERMeDIA uma vez e mostre uma tela de carregamento.
3. [Gerenciamento de links](#2-gerenciamento-de-links) e [busca](#23-busca).
4. [Criação e controle de um player](#31-criação-e-controle-de-um-player) e
   [faixas de áudio](#32-faixas-de-áudio-e-idiomas).
5. [Escolha de engines](#33-escolha-de-engines): AWT, JavaFX, OpenGL ou Vulkan, além de OpenAL ou Java Sound.
6. [Diagnóstico e exemplos](#13-diagnóstico-e-exemplos).

## FERRAMENTAS DE CÓDIGO
Ferramentas de linha de comando, bots, conversores e serviços que funcionam sem janela.

1. [Instalação](#0-instalação) e [inicialização](#1-inicialização): o que precisa ser iniciado e o que não precisa.
2. [Plataformas](#22-plataformas) e [busca](#23-busca): resolva e busque links com a `PlatformAPI`, sem precisar de
   inicialização.
3. [Downloads](#42-downloads): `NetRequest` com downloads limitados e verificados.
4. [Decodificadores](#51-decodificadores) e [codificadores](#52-codificadores): imagens, animações e arquivos de vídeo.
5. [Engine headless](#33-escolha-de-engines): capture frames decodificados sem um contexto gráfico.
6. [Reprodução sincronizada](#35-reprodução-sincronizada): o `ServerMediaPlayer` também roda em servidores.

## AGENTES DE IA
Leia [inicialização](#1-inicialização), [pacotes públicos e internos](#12-pacotes-públicos-e-internos),
[gerenciamento de links](#2-gerenciamento-de-links),
[criação e controle de um player](#31-criação-e-controle-de-um-player) e [escolha de engines](#33-escolha-de-engines)
antes de escrever código de integração. Estas regras são requisitos obrigatórios:

- Use só os pacotes públicos (`org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player`, `api.util`,
  sem os seus subpacotes). Players e engines vêm só da `PlayerAPI`; as classes de engine são seladas.
- Dentro do Minecraft, nunca chame `WaterMedia.start`: o loader faz isso. Fora dele, chame uma vez por processo, nunca
  de um callback ou de uma thread de trabalho do WATERMeDIA. Não existe stop.
- Confira `WaterMedia.loaded()` e a capacidade de que você precisa (como `CodecsAPI.ffmpegLoaded()`) em vez de presumir
  que estão disponíveis.
- A resolução é assíncrona: consulte `mrl.status()` a partir de um tick e nunca chame `mrl.await(...)` na thread do jogo
  ou de renderização.
- `PlayerAPI.player(...)` pode retornar null. Libere o player quando `start()` lançar uma exceção ou o host descartar a
  superfície.
- Passe as engines como suppliers, nunca compartilhe uma entre players e deixe o player ser dono delas.
- Leia `player.texture()` de novo a cada desenho, na thread de renderização; 0 significa que não há nada para desenhar.
- Nunca chame `release()` de um listener de status ou de uma thread de reprodução, e libere todos os players antes de
  destruir o contexto OpenGL, Vulkan ou OpenAL que eles usam.
- O executor do OpenGL roda na thread do GL e é processado a cada frame, e mais uma vez depois de `release()` também. O
  OpenAL precisa do seu contexto atual em toda thread que conduz o áudio. No Vulkan, segure o `queueLock()` em volta dos
  seus próprios submits e presents, e nunca chame `vkDeviceWaitIdle` enquanto existirem players.
- Os listeners de status são consumidos pela transição para `STOPPED`, `ENDED` ou `ERROR`; registre de novo dentro do
  callback para continuar ouvindo.
- Os controles booleanos retornam `true` quando a chamada teve efeito e `false` quando foi recusada; confira o resultado
  em vez de presumir.
- Os tempos são em milissegundos (os timestamps do codificador, em microssegundos), o volume vai de 0 a 100 e a
  velocidade de pouco acima de 0 até 4.
- `ImageReader.next()` reutiliza o seu buffer: copie antes da próxima chamada. As chamadas decode, reader, sniff e
  encoder da `CodecsAPI` são experimentais: não use em mods; as verificações de status dela são seguras.
- Servidores dedicados têm `ServerMediaPlayer`, `PlatformAPI`, os uploads da `NetworkAPI`, o servidor de arquivos e os
  codecs de imagem em Java puro; players e engines lançam exceção lá.

# 0. INSTALAÇÃO
No Minecraft, basta adicionar o WATERMeDIA e o _WATERMeDIA: Binaries_ como dependências (veja
[Binaries e FFmpeg](#01-binaries-e-ffmpeg)) e declarar o WATERMeDIA como obrigatório no seu mod, dentro dos intervalos
de [restrições de software](#11-restrições-de-software); o loader os fornece em tempo de execução.

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

Em aplicativos Java próprios ou jogos em Java, o ambiente precisa fornecer:

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

| Engine | Bibliotecas extras de runtime |
| --- | --- |
| `GLEngine` | _LWJGL_ `lwjgl` e `lwjgl-opengl`, com os seus nativos |
| `VKEngine` | _LWJGL_ `lwjgl`, `lwjgl-vulkan` e `lwjgl-shaderc` (os shaders são compilados em tempo de execução), com os seus nativos (`lwjgl-vulkan` só tem nativos no macOS; Windows e Linux usam o Vulkan do sistema) |
| `ALEngine` | _LWJGL_ `lwjgl` e `lwjgl-openal`, com os seus nativos |
| `JFXEngine` | Um runtime do JavaFX 13+ |
| `AWTEngine`, `JSEngine`, `HeadlessGFXEngine` | Nada extra: o JDK já cobre |

- No Forge, NeoForge e Fabric, o WATERMeDIA inclui o _WATERCoNFIG_ como um JAR aninhado, e o jogo já traz as outras
  bibliotecas.
- O launcher standalone baixa as bibliotecas que faltam quando elas são necessárias, e só usa as que batem com o SHA-256
  fixado no momento do build.
- Um app que incorpora a biblioteca traz o seu próprio classpath: compilar no ambiente de desenvolvimento não quer dizer
  que uma instalação do Minecraft traga essas bibliotecas.
- O Binaries traz o _XZ_ embutido, e o WATERMeDIA traz os bindings de _JavaCPP_ e _FFmpeg_.

Os nativos do _FFmpeg_ cobrem estas plataformas; as imagens decodificam em qualquer lugar onde o Java roda.

| Arquitetura | Windows | Linux | macOS | Android |
| --- | --- | --- | --- | --- |
| x86_64 | ✅ | ✅ | ✅ | ⛔ |
| aarch64 | ⛔ | ✅ | ✅ | ⛔ |

> [!WARNING]
> Não há nativos do _FFmpeg_ para Windows em ARM64 nem para Android: as imagens continuam decodificando lá, vídeo e
> áudio não tocam, e um Binaries instalado faz a inicialização falhar, a menos que `codecs.ffmpeg.disable` esteja
> ativado.

## 0.1 BINARIES E FFMPEG
> [!IMPORTANT]
> A decodificação de vídeo por hardware precisa de uma GPU e de um driver que decodifiquem aquele codec: NVIDIA (CUDA),
> Intel (QSV), AMD (AMF), Direct3D 11 ou 12 no Windows, VideoToolbox no macOS, VAAPI, VDPAU ou DRM no Linux, ou
> [Vulkan](#decodificação-com-vulkan). Qualquer outra coisa, ou uma GPU que não dá conta, decodifica por software, e
> `codecs.ffmpeg.hardwareAccel = false` sempre faz isso.

> [!NOTE]
> Configurável: `codecs.ffmpeg.disable`, `customPath`, `hardwareAccel`, `analyzeDuration`, `probeSize`,
> `trackPacketReads`, `cache` e `cacheMaxResponse`. Veja os comentários deles em `config/watermedia.toml`.

O _WATERMeDIA: Binaries_ carrega junto com o WATERMeDIA e prepara os nativos antes da rede, dos codecs e do player.

- Os clientes do Minecraft precisam dele; os servidores dedicados podem dispensá-lo, já que ali ele não prepara nada.
- Apps que incorporam a API Java também podem dispensá-lo e manter a decodificação de imagens e os resolvedores de
  plataforma que não usam nativos; eles perdem a decodificação de vídeo e as plataformas que dependem do _yt-dlp_.
- Desativar o _FFmpeg_ (`codecs.ffmpeg.disable`) pula a extração.
- O Binaries não tem API para iniciar nem desligar: os arquivos nativos continuam no lugar durante todo o processo.
- O _yt-dlp_ e o _BotGuard_ são baixados no primeiro uso e verificados antes de rodar. As propriedades de sistema
  `watermedia.ytdlp.binary` e `watermedia.botguard.binary` apontam para os seus próprios executáveis no lugar deles.

Para a sua própria build do _FFmpeg_, coloque a pasta com as bibliotecas nativas dela (não um `ffmpeg.exe`) em
`codecs.ffmpeg.customPath`. Qualquer licença ou versão serve, desde que ela traga bindings JNI compatíveis e o mesmo
suporte a TLS da build incluída (veja [Confiança HTTPS e credenciais](#25-confiança-https-e-credenciais)). O WATERMeDIA
só verifica a integridade dos nativos incluídos.

## 0.2 CONFIGURAÇÃO
> [!NOTE]
> Todo campo se explica sozinho no próprio comentário, e os valores ficam por sua conta. Cada seção deste guia lista os
> campos que a afetam.

O WATERMeDIA grava as suas configurações em `config/watermedia.toml` dentro do diretório de trabalho, com um comentário
acima de cada campo. Os valores padrão funcionam de cara, os campos lidos na inicialização valem a partir da próxima
execução, e quando um backend parece indisponível, este arquivo é o primeiro lugar para conferir.

- `codecs.*`: o _FFmpeg_, a decodificação por hardware e os decodificadores de imagem.
- `player.*`: a qualidade padrão e os caches.
- `platforms.*`: conteúdo adulto, busca e contas de plataformas.
- `network.*`: o servidor de arquivos e as requisições web.

# 1. INICIALIZAÇÃO
No Forge, NeoForge e Fabric, o loader inicia o WATERMeDIA para você: pule esta seção.

**EXEMPLO:**
```java
import org.watermedia.WaterMedia;

WaterMedia.start("My application", temporaryDirectory, workingDirectory, true);
boolean ready = WaterMedia.loaded(); // true ONCE EVERY MODULE FINISHED; CHECK THE CAPABILITY YOU NEED TOO
String module = WaterMedia.loading(); // MODULE CURRENTLY LOADING
int step = WaterMedia.step(); // CURRENT LOADING STEP
int steps = WaterMedia.steps(); // TOTAL LOADING STEPS
```

Ele carrega, nesta ordem:
- Configuração (`watermedia.toml`)
- Binaries (só quando o JAR dele está presente)
- `NetworkAPI`
- `CodecsAPI`
- `PlayerAPI`

`steps()` é 5 com o Binaries e 4 sem ele. Você pode ler `loading()`, `step()` e `steps()` de qualquer thread para uma
tela de carregamento.

- `start` roda uma vez por processo: não existe stop, um segundo `start` lança exceção e uma inicialização que falhou
  não pode ser repetida.
- Com null, o diretório temporário é `<java.io.tmpdir>/watermedia` e o de trabalho é o atual. O último argumento diz se
  é um cliente; em servidores dedicados, o Binaries, os codecs e o player não iniciam.
- Se uma parte opcional falhar (o servidor de arquivos, o _FFmpeg_), ela fica desligada e o carregamento continua:
  confira o que você precisa, como `CodecsAPI.ffmpegLoaded()`.
- Qualquer outra falha (a configuração, um Binaries instalado) faz `start` lançar `IllegalStateException` com essa falha
  como causa, e `loaded()` continua false.
- Nunca chame `start` de uma thread ou de um callback do WATERMeDIA: ele se recusa.

Cada API sabe se o seu módulo carregou:

- `PlayerAPI.mrl` lança `IllegalStateException` até o player iniciar, e sempre em servidores dedicados.
- `NetworkAPI.upload` lança exceção até a rede iniciar ou quando não recebe um arquivo existente; um servidor fora do ar
  ou mal configurado aparece no status que ele retorna.
- A `PlatformAPI` não precisa de inicialização: `register`, `fetch` e `search` sempre funcionam. As plataformas que usam
  o Binaries (YouTube, _yt-dlp_) falham com `PlatformException` até ele carregar, e sempre em servidores dedicados; sem
  o JAR do Binaries, `fetch` retorna null para os links delas.
- A decodificação de imagens em Java puro pela `CodecsAPI`, o `ServerMediaPlayer`, o `MathUtil` e o `MathEases` não
  precisam de inicialização.

O player é dono das engines que os seus suppliers criam. Passe suppliers, não engines já montadas: se a fonte não
estiver disponível, nada nativo fica pendurado. Os contextos OpenGL, Vulkan e OpenAL são seus: libere todos os players
antes de destruí-los, e crie engines novas quando recarregar um dispositivo ou um contexto. Os nativos nunca são
descarregados nem trocam de versão enquanto a JVM roda.

## 1.1 RESTRIÇÕES DE SOFTWARE
### Launchers bloqueados
O WATERMeDIA não inicia no Minecraft quando o _TLauncher_ abriu o jogo, e o erro diz o que o denunciou. Restos de uma
instalação antiga só geram um aviso no log, e os apps que incorporam a biblioteca pulam essa verificação.

### Versões compatíveis
Forge, NeoForge e Fabric carregam o WATERMeDIA em clientes e servidores dedicados destas versões, e rejeitam as demais:

| Minecraft | Forge | NeoForge | Fabric Loader |
| --- | --- | --- | --- |
| 1.18.2 | 40.3.10 ou 40.x mais recente | ⛔ | 0.14 ou 0.x mais recente |
| 1.19.2 | 43.5.0 ou 43.x mais recente | ⛔ | 0.14 ou 0.x mais recente |
| 1.20.1 | 47.4.2 ou 47.x mais recente | ⛔ | 0.14 ou 0.x mais recente |
| 1.21.1 | 52.1.1 ou 52.x mais recente | 21.1.0 ou 21.1.x mais recente | 0.14 ou 0.x mais recente |
| 1.21.11 | 61.0.5 ou 61.x mais recente | 21.11.0 ou 21.11.x mais recente | 0.14 ou 0.x mais recente |
| 26.1.2 | 64.x | 26.1.2.x | 0.14 ou 0.x mais recente |
| 26.2 | 65.x | 26.2.0.x | 0.14 ou 0.x mais recente |
| 26.3 | 66.x | 26.3.0.x | 0.14 ou 0.x mais recente |

- No Minecraft, é aceito do Java 17 até o 25.
- O WATERMeDIA e o Binaries se aceitam entre si da 3.0.0 até a 3.1.0 (sem incluí-la); as builds BETA antigas da 3.0.0
  ficam de fora.
- O Fabric não consegue exigir uma dependência só nos clientes, então lá o bootstrap exige o Binaries nos clientes.

## 1.2 PACOTES PÚBLICOS E INTERNOS
A API pública são os pacotes `org.watermedia`, `api.codecs`, `api.network`, `api.platform`, `api.player` e `api.util`,
sem os seus subpacotes. Todo o resto é interno.

Nada impede você de usar os internos, mas, por favor, use os pontos de entrada públicos, como `PlayerAPI.player` e
`PlatformAPI.fetch`, e não mexa no que está por trás deles. Se você contribui, `gradle javadocInventory` lista os
métodos públicos sem documentação.

> [!WARNING]
> A `CodecsAPI` é experimental: `decode`, `reader`, `sniff`, `encoder` e os readers, encoders e filtros em volta deles
> podem mudar em qualquer atualização, sem seguir o esquema de versões. Não use ainda nos seus mods; os players já usam
> isso para você. As verificações de status (`ffmpegLoaded()`, `ffmpegError()`, `vulkanDecode()`) são seguras de usar.

## 1.3 DIAGNÓSTICO E EXEMPLOS
Os loggers do projeto são `watermedia` e `watermedia_binaries`; coloque esses nomes em DEBUG para diagnóstico.

- Os logs mostram os links por completo para ajudar na depuração, mas escondem segredos: senhas em links, tokens JWT,
  `Authorization`, `Cookie`, a sessão do Minecraft e qualquer `key=value` com cara de segredo. Os uploads de log do app
  passam pelo mesmo filtro.
- Um problema que se repete (um buffer curto, um layout recusado, um callback que falha, um upload rejeitado) só é
  registrado na primeira vez em cada engine, player, servidor ou cache. As iterações lentas são contadas e relatadas uma
  vez no final.
- O _FFmpeg_ ainda escreve os seus próprios logs na saída de erro, fora dos loggers do projeto e do filtro de segredos.
- `-Dwatermedia.glchecks=true` ativa as verificações de erro do OpenGL no upload dos frames.

`src/test/java/org/watermedia/ApiGuideExample.java` tem exemplos que compilam para todas as engines e para a reprodução
sincronizada (relógio e seguidor); o de imagem roda em `ApiGuideExampleTest`. Se você contribui,
`-Prequire_natives=true` faz os testes falharem em vez de pular quando os nativos do _FFmpeg_ estão faltando.

# 2. GERENCIAMENTO DE LINKS
> [!NOTE]
> Configurável: `platforms.allowMatureContent`, `searchLimit`, `searchCleanupInterval`, `biliBiliCookie` e
> `player.mrlCleanupInterval`. Veja os comentários deles em `config/watermedia.toml`.

Um MRL (Multimedia Resource Location) transforma qualquer link na mídia que ele contém: uma ou mais fontes, cada uma com
as suas qualidades, faixas de áudio e metadados. Ele é resolvido em segundo plano e fica em cache.

## 2.1 RESOLUÇÃO DE LINKS
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

Um link direto falha quando o servidor responde com um status de erro ou com algo que não é mídia; playlists HLS abrem
como um único vídeo e playlists IPTV como uma fonte por canal.

## 2.2 PLATAFORMAS
Os links destas plataformas são resolvidos logo de cara:

- 📺 YouTube, Twitch, Kick, BiliBili, Odysee, D.tube, VidLii
- 🐦 Twitter (X), Bluesky, TikTok, Facebook, Instagram
- 🎵 SoundCloud, Newgrounds (filmes, música e arte)
- 🖼️ Imgur, Lightshot, Medal, Streamable, Sendvid
- 📁 Google Drive, Dropbox, MediaFire
- 🔞 PornHub (só com `platforms.allowMatureContent` ativado)
- 🔗 Links diretos para qualquer vídeo, música, imagem ou playlist, arquivos locais e
  [links `water://`](#24-o-protocolo-water)

YouTube, SoundCloud, Facebook e Instagram tocam pelo _yt-dlp_, que exige o Binaries. O BiliBili toca até 480p, ou até
1080p (8K com VIP) com o seu cookie de sessão em `platforms.biliBiliCookie`.

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

Qualquer outra falha da sua plataforma também é uma `PlatformException`, e um álbum ou playlist pula um item com
problema em vez de falhar inteiro.

## 2.3 BUSCA
YouTube (com o Binaries), Twitch, Kick e Imgur buscam por palavra-chave, e a sua plataforma entra no grupo
sobrescrevendo `search(query, limit)`.

```java
List<PlatformResult> results = PlatformAPI.search("lofi");   // RETURNS AT ONCE; THE LIST FILLS AS PLATFORMS ANSWER
boolean searching = PlatformAPI.searching("lofi");           // false ONCE EVERY PLATFORM ANSWERED; POLL BOTH FROM THE UI
for (final PlatformResult hit: results) {
    show(hit.title(), hit.thumbnail(), hit.platform().name()); // WHEN PICKED, PlayerAPI.mrl(hit.uri()) OPENS IT LIKE ANY LINK
}
List<PlatformResult> fresh = PlatformAPI.search("lofi", true); // ASK THE PLATFORMS AGAIN INSTEAD OF THE CACHE
```

Uma nova consulta substitui a anterior, e enquanto o usuário continua digitando ela espera um instante pela próxima
tecla. Twitch e Kick só listam canais ao vivo.

## 2.4 O PROTOCOLO WATER
```java
PlayerAPI.mrl("water://local/config/song.mp3");              // ./config/song.mp3, RESOLVED AGAINST THE WORKING DIRECTORY
PlayerAPI.mrl("water://remote/xyz12345");                    // A FILE ON YOUR FILE SERVER (SEE UPLOADING AND DOWNLOADING FILES)
PlayerAPI.mrl("water://global/abcd6789");                    // A FILE ON OUR GLOBAL SERVER
```

Os links remote e global carregam o ID de arquivo que o servidor deu, não um caminho de pasta.

## 2.5 CONFIANÇA HTTPS E CREDENCIAIS
O HTTPS funciona de cara com os certificados em que o Java já confia, e essa verificação nunca é desligada.

Vai usar um certificado autoassinado ou a sua própria autoridade? Importe-o num truststore e aponte o Java para ele
antes de o WATERMeDIA iniciar (no Minecraft, coloque as mesmas opções `-D` nos argumentos da JVM do launcher):

```shell
keytool -importcert -alias my-server -file my-server.crt -keystore my-truststore.jks -storepass changeit -noprompt
java -Djavax.net.ssl.trustStore=my-truststore.jks -Djavax.net.ssl.trustStorePassword=changeit -jar my-app.jar
```

- Um truststore vazio ou quebrado impede o _FFmpeg_ de iniciar, então o vídeo fica desligado até ele ser corrigido.
- Uma build personalizada do _FFmpeg_ precisa do mesmo suporte a TLS da build incluída.
- Os headers de login e os cookies (`Authorization`, `Cookie`, `X-WaterMedia-Token`) nunca seguem um redirecionamento
  para outro servidor, e os redirecionamentos só vão para links http ou https.

# 3. REPRODUÇÃO DE MÍDIA
> [!NOTE]
> Configurável: `player.defaultQuality`, `player.cacheBudget`, `player.watertx.cache` e `player.watertx.texturesBudget`.
> Veja os comentários deles em `config/watermedia.toml`.

## 3.1 CRIAÇÃO E CONTROLE DE UM PLAYER
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

`player` retorna null quando a fonte ainda está sendo resolvida, o índice não existe, o backend está faltando ou a
construção lança uma `Exception` (já um `Error` é propagado). Confira o status do MRL antes de tentar de novo.

- Os suppliers só rodam quando necessário, e as imagens nunca chamam o supplier de áudio.
- Um supplier que retorna null omite aquela saída, como `() -> null` como engine gráfica para reprodução só de áudio.
- Nunca compartilhe uma engine entre players.

O `TxMediaPlayer` reproduz as imagens e animações suportadas, o `FFMediaPlayer` reproduz áudio e vídeo pelo _FFmpeg_, e
o `ServerMediaPlayer` é um relógio de sincronização sem decodificação nem engines nativas.

Os três players compartilham os controles: eles retornam `true` quando a chamada teve efeito e `false` quando foi
recusada (impossível naquele momento, já liberado ou com uma velocidade fora do intervalo). As configurações de saída
não retornam nada.

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

Chame `speed(...)` com o contexto de áudio atual: o relógio só adota uma taxa que a engine de áudio aceitou. As engines
gráficas embutidas ainda não aceitam texturas BC comprimidas.

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

- Um listener roda na thread que provocou a mudança: uma thread de reprodução, o ticker de sincronização ou, em imagens,
  quem chamar `status()`, `time()` ou `texture()`.
- Ele nunca roda enquanto o player segura um lock, nem numa thread pela qual `release()` está esperando. O `STOPPED` de
  `release()` chega quando tudo já foi liberado, incluindo as engines.

| Estado do player | Significado |
| --- | --- |
| `WAITING` | Criado e esperando as condições de inicialização. |
| `LOADING` | Carregando ou preparando a mídia. |
| `BUFFERING` | Esperando dados suficientes para continuar. |
| `PLAYING` | Reproduzindo a mídia. |
| `PAUSED` | Pausado e pronto para continuar. |
| `STOPPED` | Parado e pronto para começar do início. |
| `ENDED` | Chegou ao fim da mídia. |
| `ERROR` | A reprodução encontrou uma falha. |

`Status.allows(next)` diz se uma pausa, retomada, buffering, fim, parada ou falha pode levar direto para `next`.
Iniciar, fazer seek e reiniciar seguem regras próprias: `LOADING` termina quando o pipeline abre (ou numa parada ou
falha), um seek em `ENDED` ou `STOPPED` reinicia, e `STOPPED` não tem mudança direta.

## 3.2 FAIXAS DE ÁUDIO E IDIOMAS
Vídeos dublados e arquivos com vários streams de áudio tocam o áudio original (ou o padrão do arquivo), a menos que você
peça outro.

```java
player.audioLanguage("es-MX"); // PREFERRED FROM THE NEXT START; null CLEARS IT
for (final MediaPlayer.AudioTrack track: player.audioTracks()) {
    if ("es".equals(track.lang())) player.audioTrack(track); // SWITCHES RIGHT AWAY ON A RUNNING PLAYER
}
```

- A ordem de escolha é: o que você pediu com `audioTrack(...)`, depois `audioLanguage(...)`, depois a original (ou o
  stream padrão do arquivo) e, se não houver, o primeiro. `es-MX` escolhe `es-MX` primeiro, depois `es-419`, `es` ou
  `spa`. Sem `audioLanguage(...)` toca a original, nunca uma dublagem automática.
- `audioTracks()` lista um `AudioTrack(track, stream, name, lang)` por stream: primeiro os do arquivo principal, depois
  os de cada faixa separada. `track` é `AudioTrack.MAIN` ou o índice da faixa separada, e `stream` é o índice do
  _FFmpeg_ dentro dela.
- Uma faixa separada que ainda não tocou aparece como uma entrada `provisional()` (`stream` é -1) com o nome e o idioma
  da plataforma, então listar não abre todas as dublagens. Quando ela toca, os streams reais dela tomam o seu lugar. A
  lista é imutável e qualquer thread pode lê-la.
- `audioTrack(track)` retorna `true` quando a escolha mudou. Um player em execução reconstrói o pipeline no tempo atual
  (uma transmissão ao vivo pula para o ponto mais recente), e a escolha sobrevive a trocas de qualidade e reinícios.
  `audioTrack()` retorna o que está tocando agora. Os players de imagem, de servidor e sem áudio não listam nada.

## 3.3 ESCOLHA DE ENGINES
O Vulkan vem primeiro e o OpenGL continua suportado. Qual backend usar é decisão da sua camada de renderização.

| Factory | Obrigação do host |
| --- | --- |
| `PlayerAPI.glEngine(renderThread, executor)` | O executor roda na thread do contexto OpenGL: execute as tarefas dele a cada frame, e mais uma vez depois de `release()`. Com os dois argumentos null, a thread que chama precisa ser dona do contexto. Nunca mova uma engine entre contextos. |
| `PlayerAPI.vkEngine(context)` | Passe um `VKContext` que dure mais que a engine: segure o `queueLock()` em volta dos seus submits e presents, e rode os callbacks de `retire(...)` quando os seus frames em andamento terminarem (todos eles se o dispositivo for embora). Para decodificar na GPU, veja [Decodificação com Vulkan](#decodificação-com-vulkan). |
| `PlayerAPI.awtEngine(onFrame)` | Pinte a imagem publicada a partir da UI AWT/Swing; passe o callback opcional para a thread da UI quando for preciso. |
| `PlayerAPI.jfxEngine(onFrame)` | Precisa de um runtime do JavaFX 13+; vincule a imagem da engine à sua UI. Sem ele, a factory lança `IllegalStateException` em vez de um erro de linkage. |
| `PlayerAPI.headlessEngine()` / `headlessEngine(preload)` | Captura frames sem um contexto gráfico, útil para testes e processamento de imagem. Como toda engine, ela só existe nos clientes; um servidor dedicado só cria relógios `ServerMediaPlayer`. |
| `PlayerAPI.alEngine()` | O contexto OpenAL precisa estar atual nas threads de áudio. |
| `PlayerAPI.alEngine(true)` | O mesmo contrato do OpenAL, tocando por sinks mono posicionais (pares estéreo e alto-falantes extras). |
| `PlayerAPI.jsEngine()` | Há um dispositivo e uma linha do Java Sound disponíveis; sem áudio posicional. |

> [!IMPORTANT]
> **OpenGL:** converter vídeo YUV e em tons de cinza na GPU precisa do OpenGL 3.2 core (GLSL 150); sem ele, os frames
> são convertidos para BGRA na CPU. Fazer o upload direto da thread de decodificação precisa do OpenGL 4.4 ou de
> `ARB_buffer_storage`; drivers mais antigos fazem o upload na thread de renderização por dois pixel buffers.

> [!IMPORTANT]
> **Vulkan:** `queue()` precisa aceitar trabalho de compute e de transferência.
> - **Zero copy:** os planos decodificados chegam à GPU sem cópia pela CPU quando o dispositivo ativa
>   `VK_EXT_external_memory_host` (`hostImportSupported()` retorna true) e o buffer do plano está alinhado a
>   `minImportedHostPointerAlignment()`; caso contrário, o plano é copiado por um staging buffer.
> - **Conversão YUV por hardware:** frames SDR de 8 bits NV12 e YUV 4:2:0, 4:2:2 e 4:4:4 com tamanhos pares e sem
>   primárias BT.2020 são convertidos no hardware do sampler quando o dispositivo ativa `samplerYcbcrConversion` (Vulkan
>   1.1, `ycbcrSampler()` retorna true); todos os outros layouts usam um passo de compute.
> - **Layouts:** além de BGRA, RGBA, YUYV e UYVY de 8 bits, um layout precisa de formatos de plano que suportem
>   sampling, filtragem linear e escritas de transferência, ou cai no fallback para BGRA. RGB sempre cai no fallback.
> - **Decodificação na GPU:** veja [Decodificação com Vulkan](#decodificação-com-vulkan).

As engines são seladas: você traz os contextos e os executores, não engines próprias. Cada engine gerencia as suas
texturas, fontes de áudio e buffers.

Toda engine segue o mesmo ciclo de vida:

- `release()` é idempotente, nunca pode rodar ao mesmo tempo que o player que a alimenta, e deixa a engine inerte:
  `released()` é true e `texture()` retorna 0.
- O player confere `supports(format, bits)` antes do upload e usa BGRA de 8 bits como fallback quando a engine recusa um
  layout.
- Os uploads nunca lançam exceção: uma falha do driver descarta o frame com um único erro registrado no log, e o último
  frame continua na tela.
- As engines de software e headless expõem um sentinela diferente de zero em vez de um handle da GPU, então só compare o
  handle com 0.
- `PixelFormat.planeWidth`, `planeHeight`, `texelBytes` e `frameBytes` medem cada plano de uma engine, por exemplo para
  saber quanta memória ela usa. Eles arredondam para cima como o _FFmpeg_, então tamanhos ímpares não perdem croma.

Tratamento de cor:

- As engines OpenGL e Vulkan convertem YUV com a matriz (BT.601, BT.709 ou BT.2020), o range e a posição de croma que
  cada vídeo declara. Quando ele não declara nada, elas deduzem como o _mpv_: BT.601 para frames SD (com menos de 1280
  pixels de largura e até 576 linhas de altura), BT.709 nos outros casos.
- HDR PQ e HLG aparece como SDR, com as cores BT.2020 mapeadas para BT.709 e os realces comprimidos para que um pico de
  1000 nits chegue ao branco.
- As engines num mesmo contexto ou dispositivo compartilham essa conversão, e ela vai embora com a última. Libere todas
  as engines e deixe a limpeza delas rodar (no executor do OpenGL ou em `VKContext.retire`) antes de destruir o contexto
  ou o dispositivo.
- As engines de software recebem BGRA que o _FFmpeg_ converte com a matriz e o range declarados, sem tone mapping de
  HDR.

O OpenAL precisa do seu contexto atual em toda thread do player, incluindo o ticker de sincronização. O Java Sound não
precisa de lock extra, e quando um bloco de áudio não cabe na linha, ele abre uma maior e descarta o que a antiga ainda
tinha.

> [!IMPORTANT]
> **OpenAL:** áudio de 4, 6, 7 e 8 canais precisa de `AL_EXT_MCFORMATS` numa engine comum (as engines espaciais juntam
> tudo em estéreo), amostras float e double mono e estéreo precisam de `AL_EXT_FLOAT32` e `AL_EXT_DOUBLE` (o float
> surround vem com `AL_EXT_MCFORMATS`), e um relógio que leve em conta a latência de saída do dispositivo precisa de
> `AL_SOFT_source_latency`; sem elas, o áudio é convertido para o que o dispositivo toca. Os
> [processadores de ambiente](#processadores-de-ambiente) que usam EFX precisam de `ALC_EXT_EFX`.

O app standalone (abrindo o JAR do WATERMeDIA) usa Vulkan, a menos que você escolha OpenGL, e quando o Vulkan não está
disponível ele abre em OpenGL daquela vez sem mudar a sua escolha. `-Dwatermedia.engine=opengl` ou
`-Dwatermedia.engine=vulkan` força a engine em uma execução. As configurações dele (engine, escala da interface, efeito
CRT e som de clique) ficam salvas em `config/app.properties`.

### Decodificação com Vulkan
Por padrão, `VKContext.deviceInfo()` retorna null e o vídeo é enviado da RAM. Sobrescreva-o com o jeito como você criou
o seu dispositivo (a cadeia de features passada para `vkCreateDevice`, que você não pode liberar nem mudar enquanto o
dispositivo existir, as extensões de instância e de dispositivo ativadas e as filas criadas por família) e o _FFmpeg_
decodifica H.264, H.265 e AV1 com Vulkan Video, sem passar pela RAM.

> [!IMPORTANT]
> A decodificação com Vulkan precisa de:
> - Uma instância e um dispositivo Vulkan 1.3 com `timelineSemaphore` e `synchronization2` ativados.
> - `VK_KHR_video_queue`, `VK_KHR_video_decode_queue` e a extensão de decodificação de cada codec a acelerar:
>   `VK_KHR_video_decode_h264`, `VK_KHR_video_decode_h265` ou `VK_KHR_video_decode_av1`.
> - Filas sobrando no seu dispositivo, ou as extensões de memória e semáforo externos para um gêmeo, ambos descritos
>   abaixo.
> - Nativos com Vulkan Video (`CodecsAPI.vulkanDecode()` retorna true) e `codecs.ffmpeg.hardwareAccel` ativado.

Então o _FFmpeg_ decodifica em um de dois lugares:

- **O seu dispositivo**, quando ele tem filas sobrando para o _FFmpeg_: uma de compute (que também aceita
  transferências) diferente de `queue()` e uma de decodificação de vídeo, com `queue()` criada por último em
  `queueFamily()`. O _FFmpeg_ fica com todas as outras filas de cada família e faz submits nelas a partir das suas
  threads, então enquanto existirem players não toque nelas nem chame `vkDeviceWaitIdle`; espere na sua fila
  (`vkQueueWaitIdle` sob o `queueLock()`).
- **Um gêmeo**, quando o seu dispositivo não tem essas filas, mas ativa `VK_KHR_external_memory_win32` e
  `VK_KHR_external_semaphore_win32` no Windows, ou `VK_KHR_external_memory_fd` e `VK_KHR_external_semaphore_fd` nos
  outros sistemas. A engine cria um segundo dispositivo na mesma GPU, o _FFmpeg_ decodifica lá e cada imagem é importada
  para o seu dispositivo. As suas filas continuam só suas.

Em qualquer um dos casos, libere todos os players antes de destruir o dispositivo ou a instância dele.

> [!IMPORTANT]
> Vídeos 4:2:2, 4:4:4 e de 12 bits só decodificam na GPU pela _libplacebo_, que transforma todo layout decodificado em
> RGBA SDR (com tone mapping do HDR). Ela precisa de `hostQueryReset`, `runtimeDescriptorArray`,
> `shaderStorageImageArrayNonUniformIndexing` e `maintenance4` no dispositivo de decodificação e, no seu próprio
> dispositivo, de uma segunda fila em `queueFamily()`; um gêmeo ativa essas features sozinho quando a GPU as tem. Sem a
> _libplacebo_, só streams 4:2:0 (de 8 e 10 bits) decodificam na GPU, e a engine os converte com os seus próprios
> shaders.

## 3.4 ÁUDIO ESPACIAL
Crie a engine com `PlayerAPI.alEngine(true)` antes de montar o player; o modo espacial fica fixo por toda a vida dela. O
áudio estéreo e surround é juntado em estéreo (mono continua mono) e toca por sinks, que são fontes mono posicionais. O
`alEngine()` comum toca tudo numa única fonte sem posição, e o Java Sound não tem áudio espacial (ele retorna `false`).

A engine começa com um sink primário em `Mix.MONO`, sem posição. Passe um `SpatialAudio` para posicioná-lo no mundo, ou
`null` para voltar à reprodução simples, sem posição nem efeitos. Volume, mudo, velocidade, pausa e `sync` chegam a
todos os sinks.

```java
import org.watermedia.api.player.SFXEngine.SpatialAudio;

final ALEngine audio = PlayerAPI.alEngine(true);
final SpatialAudio position = new SpatialAudio(
        blockX + 0.5, blockY + 0.5, blockZ + 0.5,
        8.0f, 64.0f, 1.0f, false, null);
audio.spatialAudio(position);
```

Crie, atualize e libere a engine no executor de som do host, com o contexto OpenAL dele atual. O dispositivo, o contexto
e o listener são seus, e precisam durar mais que os players que os usam.

- O `FFMediaPlayer` usa as suas próprias threads, então o contexto precisa estar atual para o processo inteiro. Hosts
  que o deixam atual só por thread (thread-local) não são suportados.
- A engine rejeita um contexto diferente ou ausente, e nunca troca nem recria o seu.
- Libere os players antes de recarregar um dispositivo e depois crie engines novas no novo contexto.
- `FFMediaPlayer.release()` interrompe o pipeline dele e espera ele terminar antes de apagar a fonte: chame antes de
  destruir o contexto, nunca de um callback desse pipeline.

O `SpatialAudio` rejeita posições que não cabem num float do OpenAL, qualquer coisa fora de
`0 < referenceDistance < maxDistance` e um rolloff negativo (0 desliga a atenuação). Como ele atenua depende do modelo
de distância do seu host; a API nunca mexe nele, no listener nem na velocidade da fonte, então `maxDistance` nem sempre
significa silêncio.

### Sinks: pares estéreo e alto-falantes extras
Uma única decodificação alimenta todos os sinks, então um par estéreo ou um alto-falante extra não custa um segundo
download nem uma segunda decodificação. Cada sink toca um `ALEngine.Mix`: `LEFT`, `RIGHT` ou `MONO` (a média). Em mídia
mono todas as mixagens soam iguais, então um par estéreo nunca fica mudo.

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

- O sink primário é dono de `player.audioSource()`, controla o relógio e vive tanto quanto a engine (o `release()` dele
  retorna `false`). `player.sinks()` lista o primário primeiro; sem uma engine OpenAL espacial, `sinks()` devolve uma
  lista vazia e `addSink(...)` devolve `null`.
- Todos os sinks enfileiram as mesmas amostras e são controlados juntos, então um par estéreo fica alinhado amostra por
  amostra. Adicionar um sink durante a reprodução, ou trocar uma mixagem em mídia estéreo, realinha as filas com o
  primário, com uma pausa mínima.
- Cada sink tem o seu próprio `spatialAudio(...)`: o adaptador de ambiente recebe a fonte daquele sink, e
  `sink.release()` faz o reset dele antes de apagar a fonte. Liberar o player libera todos os sinks.
- Adicione, altere e libere sinks no executor de som com o contexto atual; as consultas deles (`source()`, `mix()`,
  `spatialAudio()`, `released()`) não precisam dele.
- Um host pode usar a fonte de um sink no seu sistema de som e mudar o ganho, o pitch e a posição dela, mas nunca tocar,
  enfileirar, desenfileirar ou apagar essa fonte. Um sink novo começa com o ganho e o pitch da engine.

### Processadores de ambiente
O último argumento de `SpatialAudio` é um `SpatialAudio.Environment` opcional que aplica os seus próprios efeitos, como
oclusão ou reverb, a cada fonte OpenAL. É a porta de entrada para mods de som espacial: escreva um pequeno adaptador no
seu mod que repasse cada fonte para eles, e carregue-o só quando esse mod estiver presente.

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

- Mantenha uma única instância estável do processador entre as atualizações, e chame `spatialAudio(...)` a partir do
  executor de som do host com o contexto atual.
- Antes de cada `process`, a posição é restaurada e os filtros e envios da fonte são limpos, então você pode mover
  `AL_POSITION`, mas precisa aplicar o ambiente completo em toda chamada.
- Limite a frequência das atualizações no host (não retornando antes da hora dentro do processador) e distribua muitos
  players entre os ticks.
- A engine apaga só a sua fonte e os seus buffers, nunca os filtros ou os slots de efeito do processador.
- Se `process` ou `reset` lançar exceção, os efeitos são limpos e a exceção segue adiante; um `reset` que falha não
  impede que a fonte seja apagada na liberação.
- `auxOnly = true` pede uma saída só refletida, mas não silencia o caminho direto: deixe o player no mudo quando
  precisar de silêncio total.
- O processador só roda nas suas atualizações, nunca a partir da decodificação. O posicionamento básico não precisa de
  EFX; um processador que usa EFX precisa de um dispositivo que o tenha.

### Verificação
O `SpatialAudioTest` e o `ALSinkTest` testam o áudio espacial e os sinks contra o OpenAL real: argumentos, atenuação,
ambientes, exceções, contextos, mixagens e underruns. Eles precisam de um dispositivo de saída OpenAL, e os casos de
decodificação também precisam dos nativos do _FFmpeg_.

## 3.5 REPRODUÇÃO SINCRONIZADA
`PlayerAPI.serverPlayer()` monta um `ServerMediaPlayer`, um relógio sem mídia nem engines que roda nos dois lados. Ele
acompanha o tempo (início, pausa, seek, velocidade, repetição e fim) e toda mudança incrementa `revision()`. O resto é
com você: quem conduz o relógio, como os snapshots chegam aos clientes e onde você salva a posição.

```java
ServerMediaPlayer clock = PlayerAPI.serverPlayer();
clock.syncDuration(durationMs);
clock.start();
// ON EVERY SERVER TICK: SEND clock.snapshot() WITH YOUR OWN PACKET WHEN clock.revision() MOVED
MediaPlayer player = PlayerAPI.player(mrl, gfxSupplier, sfxSupplier);
player.sync(snapshot); // ON THE CLIENT, FOR EVERY SNAPSHOT RECEIVED
```

Um player que recebe um snapshot por `sync(PlayerState)` (ou `sync(ByteBuffer)` para a forma codificada) passa a seguir
o relógio a partir daí. A cada 50 ms ele:

- Espelha o status do snapshot: início, pausa e parada. Um snapshot `BUFFERING`, que só um snapshot montado à mão traz,
  segura como uma pausa.
- Adota a repetição e a velocidade dele.
- Corrige com um `seekQuick` (de frequência limitada) quando `drift()` passa de `tolerance(ms)` (um segundo por padrão).

O último snapshot recebido sempre vence, então envie-os em ordem. `authority()` retorna esse snapshot, `authorityTime()`
estima onde o relógio está agora e `following()` diz se o player está seguindo um. A correção nunca mexe na velocidade,
é pulada enquanto carrega ou faz buffering, e em mídia que se repete pega o caminho mais curto ao redor do loop.

Um controle num player que segue o relógio é desfeito no próximo tick, então envie os controles do usuário para o
relógio pelos seus pacotes e deixe o snapshot trazer o resultado. Avançar frame a frame é recusado, e volume, mudo,
escala e áudio espacial sempre ficam locais.

O relógio segura a posição (zero depois de `start()`) até `syncDuration(ms)` ou `live(true)` dizer o que está tocando.
Envie a duração uma vez a partir de um cliente que abriu a fonte (o primeiro valor positivo vence), e restaure uma
posição salva com `syncTime(ms)` depois de `start()`, que começa do zero. Sem duração, ou ao vivo, não há seek
(`canSeek()` é false). `release()` congela a posição e deixa o relógio em `STOPPED`: envie esse último snapshot antes de
descartá-lo, senão os clientes ficam com o anterior. Libere cada player quando a sessão dele terminar.

### Formato do snapshot
`MediaPlayer.PlayerState` é um record big-endian fixo de 27 bytes (versão, revisão, status, tempo, duração, velocidade,
flags). `PlayerState.read(ByteBuffer)` lê só esses bytes, seja qual for a ordem do buffer, e deixa o resto para o seu
transporte; quando ele rejeita um snapshot, a posição do buffer não se mexe. Confira você mesmo quem enviou cada
snapshot: a biblioteca não identifica ninguém.

# 4. UPLOAD E DOWNLOAD DE ARQUIVOS
> [!WARNING]
> O servidor é HTTP puro: quando ele ficar exposto à internet, coloque-o atrás de um proxy HTTPS. Com o token padrão,
> ele se recusa a iniciar além de `127.0.0.1`: defina o seu próprio e troque-o a cada atualização do servidor para que
> nenhum estranho possa enviar arquivos.

> [!NOTE]
> Configurável: `network.server`, `serverBindAddress`, `serverPort`, `remoteUrl`, `token`, `maxServerRequests`,
> `serverTimeout`, `maxUploadSize` e `maxStorageSize`. Veja os comentários deles em `config/watermedia.toml`.

O servidor de arquivos opcional guarda os arquivos que os seus jogadores enviam e compartilha com todo mundo por links
`water://remote/<id>`. Para usá-lo:

1. Ative-o com `network.server`: `DEDICATED` o roda em servidores dedicados, `ALWAYS` também nos clientes.
2. Abra a porta dele para fora: o servidor não faz isso sozinho.
3. Aponte `network.remoteUrl` para o endereço público e a porta do servidor, no servidor e em todos os clientes.
4. Dê ao servidor e a todos os clientes o mesmo `network.token` personalizado.

Os arquivos enviados vão parar em `watermedia/files` dentro do diretório de trabalho do servidor; apague-os lá para
liberar espaço.

## 4.1 UPLOADS
```java
NetworkAPI.UploadStatus status = NetworkAPI.upload(new File("clip.mp4"));
// LATER, FROM A TICK OR A UI TIMER
if (status.completed()) {
    URI link = status.uri(); // water://remote/<id>, READY FOR PlayerAPI.mrl
} else if (status.failed()) {
    String reason = status.error();
}
```

`NetworkAPI.upload` faz o upload em segundo plano e retorna um `UploadStatus` por arquivo: `percentage()` e
`displaySpeed()` acompanham a transferência, `cancel()` a interrompe e `error()` diz por que um deles falhou.

## 4.2 DOWNLOADS
> [!NOTE]
> Configurável: `network.timeout`, `maxRedirects` e `maxTextBytes`. Veja os comentários deles em
> `config/watermedia.toml`.

Reproduza arquivos remotos como qualquer outro link: `PlayerAPI.mrl("water://remote/<id>")`. Para trabalho simples com
HTTP, o `NetRequest` (em `org.watermedia.api.util`) envia uma requisição com as mesmas regras de segurança da
reprodução:

```java
try (NetRequest request = new NetRequest("https://example.com/pack.zip").httpsOnly().maxBytes(64L * 1024 * 1024)) {
    request.send();
    request.download(Path.of("pack.zip"), expectedSha256); // THE DIGEST IS MANDATORY
}
```

- `download(path, sha256)` grava um arquivo novo, verifica e o apaga quando algo falha.
- Cada requisição é enviada uma vez só: configure, chame `send()` e leia a resposta numa única thread.
- `text()` e `inputStream()` retornam o corpo de um status de erro em vez de lançar exceção, e `json(type)` faz o bind
  dele quando é JSON (null para qualquer outro tipo); confira `statusCode()`.

# 5. CODECS
> [!WARNING]
> Experimental: `decode`, `reader`, `sniff` e `encoder` podem mudar em qualquer atualização, então não use ainda nos
> seus mods (veja [pacotes públicos e internos](#12-pacotes-públicos-e-internos)).

> [!NOTE]
> Configurável: `codecs.maxImageSourceSize`, `codecs.png.failOnCorruptedData`, `codecs.png.useBKGDChunk`,
> `codecs.svg.maxSize` e `codecs.vp8.brokenTokens`. Veja os comentários deles em `config/watermedia.toml`.

A `CodecsAPI` entrega um decodificador (`ImageReader`) e um codificador (`Encoder`). As imagens usam os nossos codecs em
Java puro, sem inicialização nem _FFmpeg_; o vídeo passa pelo _FFmpeg_.

## 5.1 DECODIFICADORES
### Imagens estáticas
PNG, JPEG, WebP, SVG e Netpbm.

```java
ImageData image = CodecsAPI.decode(bytes);                   // A byte[], A ByteBuffer OR AN InputStream
ByteBuffer pixels = image.frames()[0];                       // TIGHTLY PACKED; JPEG AND WEBP COME ROTATED TO THEIR EXIF ORIENTATION
PixelFormat format = image.format();                         // BGRA (GRAY FOR GRAYSCALE JPEGS)
int width = image.width();
int height = image.height();

ImageData rgba = CodecsAPI.decode(bytes, PixelFormat.RGBA);  // ASK FOR BGRA, RGBA OR GRAY AND IT CONVERTS
```

### Imagens animadas
GIF, APNG e WebP animado. Um reader percorre os frames um por um em vez de manter todos na memória.

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

### Qualquer imagem
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

Alguns formatos que o `sniff` reconhece como imagem (BMP, TIFF, AVIF...) ainda não têm decodificador. Para as suas
próprias engines, `new BcReader(buffer)` lê os blocos comprimidos BC1, BC3 e BC7 de uma textura DDS; as engines
embutidas ainda não os desenham.

### Vídeo
Vídeo e áudio são decodificados dentro dos players: crie um com `PlayerAPI.player` (veja
[reprodução de mídia](#3-reprodução-de-mídia)).

## 5.2 CODIFICADORES
```java
Set<VideoCodec> videoCodecs = Container.MP4.video();         // WHAT EACH CONTAINER ACCEPTS; A TRACK THAT
Set<AudioCodec> audioCodecs = Container.MP4.audio();         // DOESN'T FIT IS REFUSED BEFORE THE FILE IS TOUCHED
```

Todo codificador recebe um frame por chamada, no formato do `Encoder.Video`, com timestamps em microssegundos sempre
crescentes; cada frame dura até o próximo. Alimente cada codificador a partir de uma única thread.

### Imagens estáticas e animadas
PNG (gravado como APNG quando recebe mais de um frame) e GIF, sem _FFmpeg_. Eles aceitam frames RGBA, BGRA, RGB ou GRAY.

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

### Arquivos de vídeo
MP4, MOV, MKV e WebM pelo _FFmpeg_, além de Ogg só para áudio. Eles aceitam qualquer `PixelFormat` bruto.

```java
Encoder.Video video = new Encoder.Video(VideoCodec.H264, 1280, 720, PixelFormat.BGRA, 60); // THE FULL CONSTRUCTOR SETS QUALITY, LIKE ON IMAGES
Encoder.Audio audio = new Encoder.Audio(AudioCodec.AAC, 48_000, 2, SampleType.S16);
try (Encoder encoder = CodecsAPI.encoder(Container.MP4, Path.of("clip.mp4"), video, audio)) { // IOException WITHOUT FFMPEG
    encoder.video(frame, ptsUs);                             // EXACTLY video.frameBytes() BYTES
    encoder.audio(pcm);                                      // INTERLEAVED SAMPLES FROM TIME ZERO: PAD A LATE START WITH SILENCE
}                                                            // close() FINISHES THE FILE, WRITTEN AS <file>.part UNTIL THEN
```
