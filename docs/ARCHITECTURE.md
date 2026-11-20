# Arquitetura do Nexo

Este documento descreve o código que existe neste checkout. O Nexo reutiliza a aplicação Android do KDE Connect e acrescenta uma interface própria. A comunicação remota continua sob responsabilidade dos plugins e da conexão pareada do KDE Connect.

## Camadas

```mermaid
flowchart TD
  subgraph Android[Aplicação Android]
    WebView[WebView]
    UI[src/main/assets/nexo<br/>HTML · CSS · JavaScript]
    Bridge[Bridge.action(name, JSON)]
    Activity[NexoActivity]
    Prefs[SharedPreferences nexo]
    Store[NexoStore]
    MediaSession[MediaSessionCompat<br/>notificação de mídia]
    WebView --> UI
    UI -->|Nexo.action| Bridge
    Bridge --> Activity
    Activity --> Prefs
    Activity --> Store
    Activity --> MediaSession
  end
  Activity -->|usa APIs dos plugins| Plugins[Plugins Android KDE Connect]
  Plugins -->|estado e callbacks| Activity
  Plugins <-->|conexão oficial pareada| Desktop[Aplicativo KDE Connect no computador]
  Desktop --> Commands[Plugins desktop habilitados<br/>e comandos configurados]
```

## Android e interface local

- `src/main/java/org/kde/kdeconnect/` contém a aplicação Android/Kotlin do KDE Connect e seus componentes reutilizados.
- `src/main/java/org/kde/kdeconnect/nexo/NexoActivity.kt` cria a WebView, configura a ponte `@JavascriptInterface`, seleciona o dispositivo conectado, encaminha ações aos plugins e publica o estado para a interface.
- `src/main/java/org/kde/kdeconnect/nexo/NexoStore.kt` persiste projeções de histórico, transferências e URIs para nova tentativa. Não implementa transporte nem protocolo.
- `src/main/assets/nexo/` contém `index.html`, `style.css`, `app.js`, `icons.js` e a ilustração local usada pela interface.
- A atividade valida as rotas locais servidas como `https://nexo.local/...`; navegação para páginas externas é recusada. JavaScript é habilitado para a interface e a ponte Android.

## Fluxo de ação e estado

1. O JavaScript chama `Nexo.action(nome, JSON)`, por exemplo para abrir a tela de dispositivos ou solicitar uma ação de mídia.
2. `NexoActivity.Bridge` põe a ação na fila da thread principal.
3. `dispatch` revalida o dispositivo/plugin e chama a API existente do KDE Connect. Ações de comandos remotos consultam a lista anunciada e usam a chave que o KDE Connect informou; chaves/UUIDs não são fixados no Nexo.
4. Os callbacks oficiais de conexão, MPRIS, volume e comandos acionam `publish()`. O estado atualizado é serializado para `update(...)` na WebView.
5. A execução remota não é presumida após o envio. A confirmação exibida depende do que o plugin realmente reporta.

O dispositivo principal, tema, atalhos, rotinas, seleção do reprodutor e posições de iluminação solicitadas são mantidos em `SharedPreferences` Android. O histórico de transferências é registrado a partir de eventos dos jobs oficiais de compartilhamento. A posição de iluminação salva é a última solicitação do usuário, não uma leitura física do kernel.

## Plugins usados

| Recurso Nexo | Integração oficial |
|---|---|
| Pareamento, descoberta e conexão | Núcleo Android KDE Connect e `BackgroundService` |
| Enviar/receber arquivos | `SharePlugin` e jobs oficiais de transferência |
| Touchpad e texto remoto | `MousePadPlugin` |
| Reprodução e seleção MPRIS | `MprisPlugin` |
| Volume global | `SystemVolumePlugin` / `SystemVolumeProvider`; separado do volume de um player MPRIS |
| Apresentação | `PresenterPlugin` |
| Brilho, iluminação e energia | `RunCommandPlugin`, apenas com comandos anunciados/configurados no computador |

### Seleção MPRIS

O `playerList` do `MprisPlugin` fornece os identificadores reais usados pelo protocolo; o Nexo exibe esses nomes. A seleção é persistida por dispositivo em SharedPreferences e resolvida contra a lista atual antes do envio. Se a seleção deixou de existir, o primeiro player atualmente anunciado passa a ser o fallback. Enquanto a seleção de interface ainda não foi persistida, os botões ficam inativos para evitar que o comando chegue ao destino anterior.

A `MediaSessionCompat` oficial mantém a identidade selecionada como par dispositivo/player e resolve o player novamente nos callbacks. Assim, controles do Android não dependem de uma referência antiga ao primeiro objeto da lista. A notificação não cria reprodução de áudio local. A tela do Android decide quais controles e ações personalizadas consegue apresentar.

### Transferências

`SharePlugin` permanece responsável pelos envios e recebimentos. Nexo acompanha jobs para registrar arquivo, dispositivo, direção, estado e progresso somente quando o job oferece progresso. Um processo que reinicia marca transferências ativas anteriores como interrompidas. Nova tentativa usa a URI salva e verifica a permissão de leitura; caso ela tenha expirado, solicita novamente a seleção do arquivo.

## Estrutura relevante

```text
src/main/assets/nexo/                         Interface HTML/CSS/JS local
src/main/java/org/kde/kdeconnect/nexo/        Activity, ponte e estado Nexo
src/main/java/org/kde/kdeconnect/plugins/    Plugins oficiais KDE Connect, com integrações pontuais Nexo
src/main/res/                                 Recursos Android, strings e ícones
src/test/java/org/kde/kdeconnect/             Testes unitários existentes e de roteamento MPRIS
gradle/                                       Catálogo de dependências e Gradle Wrapper
docs/                                         Documentação de arquitetura, build e comandos remotos
```

As implementações de plugins continuam no pacote KDE para reutilizar as APIs oficiais. Consulte o cabeçalho SPDX de cada arquivo para copyright e licença específicos.

## Segurança e limites

- O pareamento e o transporte usam o mecanismo oficial do KDE Connect; o Nexo não define endpoint remoto próprio.
- Comandos de energia podem ser destrutivos; desligamento conserva confirmação no Nexo.
- Comandos locais são escolhidos e configurados no computador pelo usuário. O Nexo não instala programas, não usa `sudo` e não altera permissões do sistema.
- O touchpad em Wayland/Niri depende das capacidades e permissões do ambiente desktop.
- A validação automatizada não comprova execução física no computador nem apresentação da notificação em todos os Android.
