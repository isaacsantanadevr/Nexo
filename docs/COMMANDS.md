# Plugins e comandos no computador

O Nexo não executa comandos locais diretamente. Ele usa o plugin oficial Executar comandos (`RunCommandPlugin`) do KDE Connect, que anuncia as chaves dos comandos cadastrados no computador. O identificador enviado é lido da lista anunciada em tempo de execução; não copie UUIDs entre dispositivos.

## Preparação

1. Instale e pareie o KDE Connect no Android e no computador.
2. Ative os plugins oficiais necessários nos dois lados: Compartilhar, Mousepad/teclado, MPRIS, System Volume, Apresentação e Executar comandos, conforme os controles desejados.
3. No computador, abra as opções do plugin Executar comandos e cadastre apenas ações que deseja permitir ao telefone.
4. Se os comandos chamarem `brightnessctl` ou `asusctl`, esses programas precisam existir no computador e o usuário que executa o KDE Connect precisa de permissão de leitura/escrita para o dispositivo correspondente. O Nexo não instala programas, altera grupos/udev ou executa `sudo`.

## Nomes reconhecidos pelo Nexo

O modal Sessão e energia procura os comandos de bloqueio, desbloqueio, suspensão e desligamento por nomes reconhecidos em português/inglês. O desligamento mantém uma confirmação no aplicativo. Não crie um comando de desligamento sem compreender o efeito no computador.

Os sliders de iluminação esperam comandos anunciados com estes nomes:

- Tela: `Brilho da tela 0%`, `Brilho da tela 10%`, …, `Brilho da tela 100%` (11 valores).
- Teclado: `Brilho do teclado 0`, `Brilho do teclado 1`, `Brilho do teclado 2`, `Brilho do teclado 3` (quatro níveis; exibidos como 0%, 33%, 66% e 100%).

O código atual também verifica a forma da linha de comando cadastrada para reconhecer os níveis. Os nomes de dispositivo de brilho variam entre máquinas e estão definidos nos reconhecedores `screenBrightnessLevel` e `keyboardBrightnessLevel` em `NexoActivity.kt`. Se o hardware usar outros nomes de dispositivo, revise essa compatibilidade antes de cadastrar comandos; o slider permanecerá indisponível em vez de fingir suporte.

Exemplos de formato, substituindo os placeholders pelos dispositivos realmente encontrados no computador:

```bash
brightnessctl -d <DISPOSITIVO_DA_TELA> set <PERCENTUAL>%
brightnessctl -d '<DISPOSITIVO_DO_TECLADO>' set <NIVEL>
```

Esses comandos são exemplos de formato, não comandos prontos para qualquer computador. Consulte as opções e dispositivos expostos localmente por `brightnessctl`; confirme também o suporte do hardware. Alguns teclados oferecem apenas níveis discretos e não oferecem cor RGB.

## Volume e mídia

- A central multimídia usa MPRIS para listar players anunciados e enviar play, pause e mudança de faixa ao player selecionado.
- O slider da central usa o plugin System Volume para o volume global anunciado pelo computador. O slider MPRIS do player não é usado como substituto.
- A MediaSession Android usa o player selecionado para seus callbacks de mídia. As ações exibidas na tela bloqueada dependem da versão do Android, da permissão de notificações e da sessão MPRIS ativa.

## Verificações e diagnóstico

- Confirme no KDE Connect do computador que o plugin necessário está habilitado e que os comandos aparecem na lista anunciada.
- No Nexo, estado indisponível indica que o plugin, o comando, a conexão ou a leitura do estado não foi encontrado.
- O último percentual salvo dos sliders é a última solicitação do Nexo, não uma leitura física do hardware.
- O envio de um comando não prova que o computador o executou. Use a interface/log do KDE Connect e verifique o hardware para validar o efeito.
- Para CachyOS com Niri/Wayland, entrada remota depende do suporte disponível no ambiente de sessão; o pareamento bem-sucedido, sozinho, não comprova que o compositor permite todas as operações de entrada.
