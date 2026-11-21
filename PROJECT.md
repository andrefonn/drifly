# Project: DriFly — Aceite por Toque no Overlay & Lista Negra de Bairros

## Architecture
DriFly is an Android application (Kotlin, targetSdk 35, minSdk 26) running as an accessibility service (`RotaAccessibilityService`) and system overlay service (`BubbleOverlayService`).
The core layers are:
1. **Accessibility Parsing & Gesture Dispatching**: `RotaAccessibilityService`, `ParsingEngine`, `accessibility_service_config.xml`.
2. **Floating Overlay & Touch Interaction**: `BubbleOverlayService`, `overlay_bubble.xml`.
3. **Ride Evaluation Engine**: `RideOffer`, evaluation in `RotaAccessibilityService` and `BubbleOverlayService`, Home Mode (`ModoCasaHelper`).
4. **Settings & Persistence**: `MainActivity` (`viewTabRides` in `activity_main.xml`), `PreferencesManager`, `DatabaseHelper` (SQLite), `BackupManager` (JSON backup/restore).
5. **Build & Release Pipeline**: `app/build.gradle.kts`, release keystore `drifly-release.jks`, ADB installation (`ZF523JBHZW`), and GitHub Releases (`gh`).

## Feature Inventory
| # | Feature | Description | Milestone | Source |
|---|---------|-------------|-----------|--------|
| 1 | Baseline Compilation Fix | Restore compile baseline by implementing missing `updateManagementDashboard` in `MainActivity.kt` | M1 | Survey |
| 2 | Gesture Permission Config | Add `android:canPerformGestures="true"` in `accessibility_service_config.xml` | M1 | Survey / R1 |
| 3 | Touch-to-Accept Setting | Switch "Aceitar corrida ao tocar no Overlay" under "Automação & Aceite Inteligente" in `activity_main.xml`, `PreferencesManager`, `BackupManager` | M1 | R1 |
| 4 | Accessibility Service Singleton | In-process singleton `RotaAccessibilityService.instance` and `triggerManualAccept()` | M1 | Survey / R1 |
| 5 | Overlay 52 dp Circular Exclusion Radius | Close button X exclusion circle (`hypot <= 52dp * density`) in `BubbleOverlayService` | M1 | R1 |
| 6 | Overlay Touch-to-Accept & Drag | Unified touch handling in `BubbleOverlayService` with drag threshold, double-tap debounce, Street View 8s hold | M1 | R1 |
| 7 | Pickup Address Extraction | Extract `pickupAddress` in `ParsingEngine` (first address in offer texts) and add to `RideOffer` | M2 | R2 |
| 8 | Neighborhood Blacklist Persistence | Storage for blacklisted neighborhoods (raw text, normalized text, lat/lng) with SQLite / Preferences & Backup integration | M2 | R2 |
| 9 | Neighborhood Blacklist Settings UI | Toggle on/off, input field, geocoding with offline normalized text fallback, item list with ✕ removal | M2 | R2 |
| 10 | Strict Auto-Accept Blocking | Block auto-accept when pickup or destination matches an active blacklisted neighborhood | M2 | R2 |
| 11 | Overlay Red Alert Banner | Red refusal card (`bg_bubble_glass_bad`) with banner `⛔ Embarque em Lista Negra: <Bairro>`, `⛔ Destino em Lista Negra: <Bairro>`, or `⛔ Embarque e Destino em Lista Negra (<Bairros>)` | M2 | R2 |
| 12 | Comprehensive Test Suite | Complete unit test coverage for parsing, blacklist, touch math, and backup | E2E & M3 | R3 |
| 13 | Version Bump & Release Build | Bump to `versionCode = 7`, `versionName = "1.1.5"` and run `assembleRelease` | M3 | R3 |
| 14 | ADB Deployment | Install signed release APK on device `ZF523JBHZW` | M3 | R3 |
| 15 | GitHub Release Publication | Create release `v1.1.5` on GitHub with APK attached via `gh release create` | M3 | R3 |

## Milestones
| # | Name | Scope | Dependencies | Status |
|---|------|-------|-------------|--------|
| E2E | E2E & Test Suite Preparation | Design & implement automated tests for touch math, parsing, blacklist matching, and backup/restore | none | DONE |
| M1 | Overlay Touch-to-Accept & Gesture Infra | Baseline fix, gesture config, `PreferencesManager`, `activity_main.xml`, `RotaAccessibilityService`, `BubbleOverlayService` | none | DONE |
| M2 | Pickup Extraction & Neighborhood Blacklist | `RideOffer`, `ParsingEngine`, blacklist storage, settings UI, evaluation block, overlay red card alerts | M1 | DONE |
| M3 | Unit Tests, Release Build, ADB & GitHub Release | `./gradlew testReleaseUnitTest`, version bump, `assembleRelease`, ADB install on `ZF523JBHZW`, `gh release create` | M1, M2 | DONE |

## Interface Contracts
### `RotaAccessibilityService` ↔ `BubbleOverlayService`
- `RotaAccessibilityService.instance: RotaAccessibilityService?` (volatile companion object reference)
- `fun triggerManualAccept()`: dispatches click or swipe gesture on active offer, updates DB via `commitPendingOfferAsAccepted(now)`, triggers Street View 8s preview on overlay.

### `ParsingEngine` ↔ `RideOffer`
- `fun extractPickupAddress(texts: List<String>): String?`: extracts first address matching `REGEX_ENDERECO`
- `data class RideOffer(..., val pickupAddress: String? = null, val isBlacklistBlocked: Boolean = false, val blacklistAlert: String? = null)`

### `PreferencesManager` / `DatabaseHelper` ↔ Blacklist
- Blacklist item: `id`, `rawName`, `normalizedName`, `latitude`, `longitude`, `createdAt`
- Normalized text match: lower case, remove diacritics/accents, match substring or tokens
- Distance match: Haversine distance < radius (e.g. 1.5 - 2.0 km) if coordinates available

### Overlay Exclusion Geometry
- Center of close button: $(X_{center}, Y_{center})$
- Touch event: $(X_{raw}, Y_{raw})$
- Distance: $d = \sqrt{(X_{raw} - X_{center})^2 + (Y_{raw} - Y_{center})^2}$
- Condition: if $d \le 52 \times \text{density}$, trigger close and suppress accept; if $d > 52 \times \text{density}$, check drag vs tap and trigger accept if enabled.

## Code Layout
- `app/src/main/java/com/drifly/app/`
  - `MainActivity.kt`: Settings UI tab `viewTabRides`, dashboard fix
  - `PreferencesManager.kt`: Shared preferences persistence
  - `BackupManager.kt`: JSON backup and restore
  - `DatabaseHelper.kt`: SQLite DB schema migrations (v4 for blacklist)
  - `ParsingEngine.kt`: Address and ride offer regex parsing
  - `RideOffer.kt`: Ride offer data model
  - `RotaAccessibilityService.kt`: Gesture dispatch, automation, singleton instance
  - `BubbleOverlayService.kt`: Floating window touch handling, exclusion radius, red refusal banner
  - `BlacklistManager.kt` (new / helper): Blacklist matching & geocoding helper
- `app/src/main/res/`
  - `layout/activity_main.xml`: Settings switches and blacklist management UI
  - `layout/overlay_bubble.xml`: Overlay layout and alert banner
  - `xml/accessibility_service_config.xml`: Accessibility capabilities (`canPerformGestures`)
- `app/src/test/java/com/drifly/app/`
  - Unit tests for regex, parsing, blacklist matching, touch geometry math, and backup

## Redesign visual v3 (tema claro)

Fonte da verdade: `Rascunho Visual de Att DriFly.html` (protótipo) + documento v3 da conversa.

### Status

| Fase | Escopo | Estado |
|---|---|---|
| 1 | Tema claro (`Theme.Material3.Light`), tokens `drifly_*`, cabeçalho, rodapé, aba Resultados | concluído |
| 2 | Aba Gestão (salário real, caixa/reservado, ponto de equilíbrio, cascata, ICR, radar 7 dias, lista agrupada A pagar/Pagas, insights) | concluído |
| 3 | Abas Corridas e Apoiar no tema claro | concluído |
| 4 | Configurações: card flutuante claro, chave "Bolinha flutuante", sanfona com uma seção aberta por vez, "Concluído" fixo | concluído |
| 5 | Histórico com filtro "Período" (por dia / por mês, De/Até) + modal claro com faixa de datas, grade 2x2, lucro real e lista de corridas | concluído |
| 6 | Botões "i" (salário real, cascata, custos da pista, contas do mês) em card laranja claro | concluído |
| 7 | Bolinha flutuante do tanque: líquido animado por sensor, cor por nível, arraste com encaixe, zona vermelha de fechar e leque de 4 atalhos | concluído |
| 8 | Aba Corridas com cada ajuste em linha (nome + ajuda + campo com unidade) e slider do nível do tanque | concluído |
| 9 | Diálogos de despesa, conta/parcela e ajuste de corrida no tema claro | concluído |
| 10 | Cartões de posição do alerta (3 mini-celulares), "i" cinza, animação do Modo Casa e ajustes finos da bolinha | concluído |

### Bolinha flutuante (`FuelBubbleService`)

- Vive enquanto `floatingBubbleEnabled` estiver ligado (não depende mais do monitoramento); só o arraste até o X desliga a chave.
- Três janelas separadas (bolinha 58dp, leque temporário, zona de descarte durante o arraste): a bolinha **não move nem muda de tamanho** ao abrir/fechar o leque.
- `FuelTankView`: líquido com ondulação (acelerômetro + arraste) e esvaziamento suave (`shownLevel` persegue `targetLevel`), fundo no tom claro da faixa, brilho de vidro, texto branco acima de 45% e escuro abaixo.
- Faixas de cor em `FuelTankColors`: 100–60% verde, 60→45% amarelando, 45–30% amarelo, 30→10% avermelhando, ≤10% vermelho; ≤7% dispara 3 ondas de radar vermelhas (~2,4 s por ciclo).
- Leque: 4 ícones de 46dp em `#FF6F00` com ícone `#FFE9D6` (branco no ativo), abertura com overshoot e atraso de 45 ms entre itens; com o leque aberto o líquido some e a bolinha vira X vermelho (tocar de novo volta ao combustível).
- Zona de descarte: gradiente radial vermelho de 170dp com X branco em opacidade baixa, cresce quando a bolinha entra nela.
- Toque nos ícones: monitoramento, Modo Casa, lançar despesa (abre o app em Resultados com o diálogo) e aceite/limites (aba Corridas).
- Tanque virtual (`FuelTankEngine`): litros = R$ da despesa ÷ preço do litro, consumo = km ÷ km/L; 100% após abastecer, soma litros se abastecer antes de esvaziar. O diálogo de despesa liga "Foi abastecimento?" sozinho quando a categoria fala de combustível e calcula os litros.
- `fuelLevel`, `floatingBubbleEnabled`, `tankLiters`, `tankReferenceLiters` e `tankOdometerKm` entram no backup JSON.

Regras mantidas: nada de quebra em títulos/valores/chips; Modo Casa laranja sólido com brilho (sem anéis/ponto verde); configurações em card flutuante (não tela cheia).

### Pendências do documento resolvidas pelo JS do protótipo

- **Chip "Faltam"**: `contas do mês − lucro limpo da pista` (2.104,07 − 881,66 = 1.222,41).
- **Caixa em mãos**: `lucro limpo − contas já pagas` (881,66 − 1.703,34 = −821,68).
- **Reservado para contas**: soma das contas ainda não pagas (400,73 no exemplo).
- **Livre hoje**: `max(0, caixa em mãos − reservado)` → por isso aparece R$ 0,00 no mês negativo.
- **Média salarial / Projeção**: no protótipo são valores fixos (-340,75) e não têm fórmula própria; o app usa o motor real (`FinancialManagementEngine`). O ritmo "R$ 141,13/dia" é texto fixo do protótipo.
- **Meta sincronizada (R$ 725,00/dia)**: fixa no protótipo (`meta = 725`); no app vem de `suggestedDailyGoal`.
- **"Atingido"**: `max(0, faturamento − despesas)` e percentual sobre a meta.
- **ICR**: fórmula oficial é a do app (`incomeReference = max(lucro da pista, lucro projetado)`); faixas ≤40 Saudável, ≤65 Equilibrado, >65 Crítico. O 119% do protótipo vem do lucro projetado (2.104,07 ÷ 1.763,32).
- **Subtítulos**: Resultados = "Painel de Resultados"; Gestão = "Gestão Financeira & Salário"; Corridas = "Configurações de Corrida"; Configurações = "Configurações do App". A aba Apoiar não tem subtítulo próprio no protótipo.
- **Aviso âmbar**: "Faltam R$ X (~N dias de pista) para cobrir as contas." com dia de pista = R$ 175 no protótipo (o app usa `effectiveDailyReference`).
- **"i" de "Do volante ao seu bolso"**: explica faturamento bruto, custos da pista (combustível/km + despesas rápidas), lucro limpo, contas do mês (pagas + a pagar) e salário real livre.
- **Formulário de nova conta**: no protótipo só existe um toast; o app já tem `dialog_add_obligation` (presets, natureza, parcelas, vencimento).
- **Dados de exemplo**: 10 bairros de Joinville/Araquari; 7 contas (2 parcelas, 3 fixas, 2 dívidas); corridas geradas por PRNG (semente 20261120), 4–12 por dia nos últimos 400 dias.
