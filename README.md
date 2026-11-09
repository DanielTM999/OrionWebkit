# Orion Webkit

Plugin de desenvolvimento web para a Orion IDE. Ele adiciona suporte a HTML, CSS, SCSS, Sass, Less, JavaScript, JSX, TypeScript e TSX, com realce de sintaxe, autocomplete e recursos baseados em Language Server Protocol (LSP).

## Recursos

- Realce de sintaxe para arquivos web, incluindo propriedades e métodos JavaScript/TypeScript.
- Dobramento de código para tags HTML, blocos CSS e estruturas JavaScript/TypeScript.
- Diagnósticos em tempo real.
- Autocomplete para HTML, CSS, JavaScript e TypeScript.
- Documentação dos itens de autocomplete e suporte a snippets do servidor.
- Snippets locais para JavaScript/TypeScript, inclusive em blocos `<script>` de arquivos HTML.
- Hover com documentação.
- Ajuda de assinatura de funções.
- Ir para definição e localizar referências.
- Destaque de ocorrências do símbolo atual.
- Rename e quick fixes/refactors no arquivo atual, quando suportados pelo servidor.

## Linguagens e extensões

| Linguagem | Extensões |
| --- | --- |
| HTML | `.html`, `.htm` |
| Componentes HTML | `.vue`, `.svelte` |
| CSS | `.css`, `.scss`, `.sass`, `.less` |
| JavaScript | `.js`, `.jsx`, `.mjs`, `.cjs` |
| TypeScript | `.ts`, `.tsx` |

## Snippets JavaScript e TypeScript

Digite o gatilho e abra o autocomplete. Quando o gatilho for digitado por completo, o snippet correspondente fica em primeiro na lista.

| Gatilho | Resultado |
| --- | --- |
| `cl` | `console.log(...)` |
| `cr` ou `ce` | `console.error(...)` |
| `cw` | `console.warn(...)` |
| `req` | `const modulo = require('pacote')` |
| `fun` | Declaração de função |
| `af` | Função assíncrona |
| `arr` | Arrow function |
| `if`, `ife` | Estruturas condicionais |
| `for`, `fof` | Laços `for` e `for...of` |
| `try` | Bloco `try/catch` |
| `imp`, `imd`, `exp` | Imports e export default |

Os snippets usam placeholders navegáveis, como `${1:nome}` e `$0`.

## Bootstrap

O plugin reconhece Bootstrap em links HTML locais ou CDN, imports JavaScript/React e CSS/Sass, e dependências `bootstrap` ou `react-bootstrap` no `package.json`. A busca no projeto acontece em segundo plano a cada cinco segundos e ignora dependências instaladas e pastas de build.

Quando detectado, o autocomplete oferece classes em `class` e `className`, incluindo valores literais JSX, classes responsivas, atributos `data-bs-*` e seus valores mais comuns. Em CSS, oferece seletores de classe e variáveis `--bs-*`. O catálogo local usa Bootstrap 5.3.8 e não precisa de rede durante a edição; não adapta as sugestões para Bootstrap 3 ou 4.

Os catálogos em `src/main/resources/bootstrap` foram extraídos do [CSS oficial do Bootstrap 5.3.8](https://github.com/twbs/bootstrap/blob/v5.3.8/dist/css/bootstrap.css). A licença MIT está incluída nessa pasta.

## Formatação

A formatação de HTML, CSS, JavaScript e TypeScript, incluindo JSX/TSX, usa os servidores de linguagem e respeita o tamanho de indentação e a opção de tabs ou espaços da IDE. Ao formatar uma seleção, o documento completo fornece o contexto e apenas o trecho selecionado é retornado. Se o servidor ainda estiver iniciando, a barra de status informa quando é necessário tentar novamente.

## Servidores de linguagem

Na primeira utilização, o plugin prepara automaticamente o runtime Node.js e instala os servidores necessários:

- `vscode-langservers-extracted` para HTML, CSS, SCSS e Less.
- `typescript-language-server` e `typescript` para JavaScript e TypeScript.

O download pode exigir acesso à internet na primeira execução. Depois, os componentes instalados são reutilizados.

## Desenvolvimento

### Pré-requisitos

- JDK 25
- Maven 3.9 ou superior
- Orion API disponível no repositório Maven configurado

### Compilar

```bash
mvn clean package
```

Para apenas validar a compilação:

```bash
mvn -DskipTests compile
```

O artefato é gerado em `target/OrionWebkit-1.0.0.jar`.

## Estrutura

```text
src/main/java/dtm/ide/
├── WebkitAdapter.java             # Integração do plugin com a Orion IDE
├── editor/                        # Tema e tokenizers
├── lsp/                           # Cliente JSON-RPC e recursos LSP
├── services/sdk/                  # Provisionamento de Node.js e pacotes LSP
└── utils/                         # Convenções de extensões e utilitários
```

## Licença

MIT.
