# DriFly — Visão Geral & Arquitetura Final

## 1. Identidade & Branding
- **Nome:** **Dri** (Branco `#FFFFFF`) + **Fly** (Laranja `#FF6B00`).
- **Ícone:** Squircle minimalista com silhueta de veículo e asas em vetor XML (`ic_drifly_logo.xml`).
- **Tema:** Dark Profundo (`#0B0E14` / `#121620`) com cards em estilo **Frosted Glass** (`bg_card_frosted_glass.xml`), bordas sutis com gradiente suave e tipografia arrojada (`sans-serif-black`, `sans-serif-bold`).

---

## 2. Estrutura de Telas & Navegação (Bottom Navigation)

### Aba 1: Painel de Resultados (Tela Padrão de Abertura)
- **Progresso de Metas:** Meta de ganhos com barra de progresso em gradiente dinâmico (`R$ atingido / R$ meta — X%`).
- **Card de Resumo:** Faturamento Bruto, Despesas e Lucro Líquido Real em destaque.
- **Ação Principal:** Botão em gradiente laranja `+ Lançar Despesa` (abre modal de lançamento com categorias e valores em R$).
- **Extrato por Período:** Botões para visualização consolidada (Diário, Semanal, Mensal e Anual).
- **Ajuste de Metas:** Definição da meta (Diária, Semanal ou Mensal) com formatação monetária padrão brasileiro (`R$ 0,00`).

### Aba 2: Configurações de Corrida
- **Posição do Alerta:** Seleção do local de aparição da notificação flutuante (*No Topo*, *No Meio*, *Embaixo*).
- **Automação & Aceite Inteligente:**
  - Interruptor de Aceite Automático de Corridas Ideais.
  - Interruptor de Recusa Automática para corridas fora do padrão.
  - Modo Moto com execução de gesto nativo de arrasto (`dispatchGesture` swipe horizontal).
  - Parâmetros: Valor Mínimo por KM (`R$/km`), Distância Máxima Total (`km`) e Nota Mínima do Passageiro.
- **Custos do Veículo:** Preço do combustível por litro, consumo médio (`km/l`) e custo de manutenção/desgaste por km.
- **Botão de Teste:** Simulação instantânea do card flutuante na tela.

---

## 3. Modal de Permissões (Ícone de Engrenagem no Topo)
- Localizado no topo direito da tela para manter o dashboard limpo.
- Exibido automaticamente na primeira inicialização se houver alguma pendência.
- Gerencia:
  1. **Sobrepor a outros apps** (`SYSTEM_ALERT_WINDOW`).
  2. **Serviço de Acessibilidade** (`RotaAccessibilityService` para Uber, 99 e iFood).
  3. **Otimização de Bateria** (`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`).

---

## 4. Alerta Flutuante (Card Overlay)
- Entrada e saída animadas suaves (`anim_slide_down_fade`, `anim_slide_up_fade`, `anim_slide_out_up`, `anim_slide_out_down`).
- Card de visualização rápida em vidro escuro com borda laranja.
- Badge arredondada da plataforma (UBER, 99, IFOOD).
- Rentabilidade em destaque colorido (Verde $\ge$ R$ 2,50/km, Amarelo $\ge$ R$ 2,00/km, Vermelho abaixo).
- Card interno com Lucro Líquido Real e dedução de combustível/desgaste.
- Tarja vermelha de aviso imediato caso a nota do passageiro seja inferior à configurada (com bloqueio estrito de aceite).
- Botão "X" de fechamento rápido e suporte a arraste livre pela tela.

---

## 5. Build & Armazenamento
- **APK Final Salvo:** `scratch/bin/DriFly.apk` (12.2 MB).
- **Dispositivo Alvo Testado:** Motorola Moto G30 (`ZF523JBHZW`), Android 12 (API 31).
- **Políticas de Cache:** `gradle clean --no-daemon` executado após compilação para preservação rigorosa do espaço em disco.
