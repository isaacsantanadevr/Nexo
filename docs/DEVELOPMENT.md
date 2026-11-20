# Desenvolvimento e build

## Requisitos detectados no projeto

- JDK 17 ou mais recente compatível com Android Gradle Plugin 9.5.1 ([requisito de JDK](https://developer.android.com/build/jdks), [compatibilidade do AGP 9.5](https://developer.android.com/build/releases/agp-9-5-0-release-notes)).
- Gradle Wrapper 9.6.0 (use `./gradlew`, não é necessário instalar Gradle globalmente).
- Android Gradle Plugin 9.5.1 e Kotlin 2.4.21, declarados em `gradle/libs.versions.toml`.
- Android SDK Platform 37 e componentes do SDK que o Gradle solicitar; `compileSdk` e `targetSdk` são 37.
- Android 6.0/API 23 ou superior para executar, conforme `minSdk`.
- Node.js apenas para `node --check` no JavaScript da interface.
- ADB opcional para instalar o APK em dispositivo conectado.

O `local.properties` contém o caminho local do Android SDK e está ignorado pelo Git. Crie/ajuste esse arquivo na máquina de desenvolvimento; não o versione.

## Obter uma cópia

Ainda não há URL de repositório Nexo confirmada. Depois que o destino for criado, substitua o placeholder abaixo pela URL fornecida:

```bash
git clone <URL_CONFIRMADA_DO_REPOSITORIO_NEXO>
cd Nexo
```

O remoto `origin` deste checkout aponta para o repositório oficial do KDE Connect Android. Não use `git push origin` para publicar alterações do Nexo.

## Compilar e testar

```bash
node --check src/main/assets/nexo/app.js
git diff --check
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

APK debug gerado em:

```text
build/outputs/apk/debug/kdeconnect-android-debug.apk
```

A variante debug tem o sufixo de application ID `.debug` (`br.com.nexo.debug`); o application ID base configurado é `br.com.nexo`.

## Instalar no Android

Com ADB autorizado e o aparelho conectado:

```bash
adb install -r build/outputs/apk/debug/kdeconnect-android-debug.apk
```

No Android, abra o Nexo e conceda as permissões solicitadas para as funções que pretende usar. Abra também o KDE Connect no computador, mantenha os aparelhos na mesma rede local e confirme o pareamento em ambos.

## Assinatura

`debug.keystore` é um arquivo rastreado herdado da base KDE Connect e usado pela variante debug com credenciais padrão públicas de desenvolvimento. Não é uma chave privada de release nem deve ser tratada como identidade de publicação do Nexo. O build release ainda precisa de um processo de assinatura de release configurado fora do repositório. Não coloque keystores de produção, senhas ou propriedades de assinatura no Git.

## Fluxo Git deste checkout

O clone original tem histórico raso e estava em `master`, apontando para o único commit disponível do upstream oficial. O trabalho Nexo deve ocorrer em branch local própria, sem reescrever esse commit. Antes de publicar, confirme um remoto GitHub pertencente ao projeto e a visibilidade pretendida; não publique para `origin` enquanto ele apontar ao KDE Connect.

Após confirmação do destino, um remoto adicional pode ser criado usando a URL recebida:

```bash
git remote add github <URL_CONFIRMADA_DO_REPOSITORIO_NEXO>
git push -u github <BRANCH_NEXO>
git push github v0.3
```

Não execute esses comandos até o remoto e sua visibilidade estarem definidos.
