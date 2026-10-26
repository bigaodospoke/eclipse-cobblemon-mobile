# Eclipse Cobblemon — launcher Android

Launcher Android do servidor **Eclipse Cobblemon**. O jogador entra com a conta Microsoft ou com um nick, toca em **JOGAR**, e o app instala o Minecraft 1.21.1 com Fabric, baixa o modpack e já abre o jogo conectando no servidor.

> Versão modificada **não oficial** do [Zalith Launcher 2](https://github.com/ZalithLauncher/ZalithLauncher2), distribuída sob a GPL-3.0. Não tem ligação com os autores do Zalith.

## Como funciona

| O quê | De onde vem |
|---|---|
| Lista de mods/configs (`manifest.json`) | Painel Voxelith, o mesmo do launcher de PC |
| IP do servidor, Discord, loja, site (`launcher.json`) | Painel Voxelith |
| Notícias (`news.json`) | Painel Voxelith |
| Minecraft + Fabric | Mojang e Fabric (versões indicadas no manifest) |

Atualizou o modpack no painel? O celular baixa só o que mudou na próxima vez que o jogador tocar em JOGAR e apaga os mods que saíram do pack. Arquivos marcados como `keep` (por exemplo, `options.txt`) só são baixados se ainda não existirem.

O jogo fica numa versão isolada chamada `Eclipse Cobblemon`. Os mods, configs e mundos ficam dentro dela.

## Onde mexer

- `ZalithLauncher/src/main/java/com/movtery/zalithlauncher/eclipse/EclipseConfig.kt`: URLs do painel, IP padrão, links, Client ID da Microsoft e regras do nick.
- `.../eclipse/EclipseHomeScreen.kt`: tela inicial (login, JOGAR, status, notícias).
- `.../eclipse/EclipsePreparer.kt`: instalação e sincronização do modpack.
- `.../game/account/microsoft/AuthCodeFlow.kt`: login Microsoft (código de autorização + PKCE com retorno em `http://localhost:59016`, o mesmo do launcher de PC).
- `ZalithLauncher/src/main/res/values/eclipse_strings.xml`: textos.
- `ZalithLauncher/gradle.properties`: nome do app e versão.

## Gerar o APK

O GitHub Actions gera o APK sozinho (`.github/workflows/eclipse-apk.yml`):

- **push na `main`**: o APK fica em *Actions → execução → Artifacts*;
- **tag `v*`** (por exemplo `git tag v1.0.1 && git push origin v1.0.1`): também cria um *Release* com o APK para os jogadores baixarem.

Antes de lançar uma atualização, aumente `launcher_version_code` em `ZalithLauncher/gradle.properties`. O Android só aceita atualizar um app se esse número tiver crescido.

### Chave de assinatura

O APK é assinado com a chave `eclipse_release.jks`. Ela **não está no repositório**: fica nos secrets do GitHub (`KEYSTORE_BASE64`, `STORE_PASSWORD`, `KEY_PASSWORD`) e numa cópia local fora do projeto. Se a chave for perdida, os jogadores terão que desinstalar o app e instalar de novo, então guarde um backup.

## Licença

GPL-3.0, com os termos adicionais do Zalith Launcher 2:

- o nome não pode conter "ZalithLauncher" nem "ZL";
- o app precisa avisar, de forma visível, que é uma versão modificada não oficial (rodapé da tela inicial e tela Sobre);
- os avisos de copyright não podem ser removidos;
- quem recebe o APK tem direito ao código-fonte (este repositório).

## Controles na tela

O layout oficial fica em `controles/Controles_Eclipse.json` (formato do PojavLauncher, como foi desenhado) e é convertido para o formato deste launcher com:

```bash
node ferramentas/converter-controles-pojav.mjs controles/Controles_Eclipse.json ZalithLauncher/src/main/assets/eclipse_layout.json --versao 2
```

Aumente o `--versao` a cada layout novo: o app instala e seleciona o layout de novo para todos os jogadores quando esse número cresce. Entre uma versão e outra, vale o layout que cada jogador escolher.

O botão de mouse virtual do Pojav não existe aqui (o launcher troca entre mouse e toque sozinho), então ele é removido na conversão. As gavetas viram camadas escondidas que o botão da gaveta mostra e esconde.
