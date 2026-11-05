# DriFly — Painel de Resultados & Copiloto Inteligente para Motoristas

**DriFly** é uma aplicação nativa Android desenvolvida em **Kotlin** e **Jetpack**, projetada para motoristas de aplicativos (Uber, 99, etc.). O aplicativo realiza leitura e análise em tempo real dos ganhos, custos de combustível/desgaste do veículo, rentabilidade líquida por KM, gestão de metas financeiras diárias, semanais e mensais, e alertas inteligentes na tela.

---

## 🚀 Principais Funcionalidades

- **Dashboard Financeiro Premium**:
  - Acompanhamento de Metas Diárias, Semanais e Mensais com barra de progresso estilizada.
  - Métricas em tempo real: Faturamento Bruto, Despesas Operacionais e Lucro Líquido Real.
  - Lançamento rápido de despesas (Combustível, Alimentação, Manutenção).
  - Extrato detalhado por período (Diário, Semanal, Mensal e Anual) com edição e exclusão de corridas com 1 toque.

- **Alerta Flutuante na Tela (Overlay Card)**:
  - Card translúcido (*Dark Glassmorphism*) que surge com animação suave ao tocar ou detectar ofertas.
  - Exibição de: Plataforma (Uber / 99), Rentabilidade por KM (color-coded: verde, amarelo, vermelho), Lucro Líquido Real, Desconto de Combustível, KM total e Tempo estimado.
  - Destaque e bloqueio de corridas com notas baixas de passageiros.

- **Ciclo de Vida de Corridas & Cancelamentos**:
  - Status gerenciados: `ESTIMADO` (previsão ao tocar), `CONCLUIDO`, `CANCELADO` e `CANCELADO_TAXA`.
  - Serviço de escuta de notificações (`RideNotificationListenerService`) que detecta avisos de cancelamento da Uber e 99 e aplica as taxas contratuais padrão automaticamente (Uber R$ 5,50 / 99 R$ 3,00).

- **Compatibilidade Global (Android 8 ao 17)**:
  - Rotinas OEM personalizadas (`OemCompatibilityHelper`) para fabricantes com gerenciamento agressivo de bateria (Xiaomi/MIUI/HyperOS, Samsung OneUI, Motorola).
  - Orientação ao usuário para permissões de sobreposição, acessibilidade e inicialização automática.

- **Backup Persistente & Restauração**:
  - Backup automático em formato JSON com hash de integridade SHA-256 gerado diariamente à meia-noite em armazenamento seguro.
  - Opções manuais de exportação e importação de histórico direto nas configurações.

- **Apoio ao Projeto**:
  - Aba integrada para suporte e doações via chave PIX Copia-e-Cola com chips rápidos (R$ 5,00, R$ 10,00, R$ 20,00, R$ 50,00).

---

## 📁 Estrutura do Projeto

```text
C:\Users\Notebook\Documents\DriFly
├── app/
│   ├── src/main/java/com/rotacore/app/
│   │   ├── MainActivity.kt                      # Interface principal, dashboard e abas
│   │   ├── BubbleOverlayService.kt              # Serviço do card flutuante na tela
│   │   ├── MonitoringService.kt                 # Notificação permanente de primeiro plano
│   │   ├── RotaAccessibilityService.kt          # Leitura em tempo real dos apps de motorista
│   │   ├── RideNotificationListenerService.kt   # Interceptação de push de cancelamento
│   │   ├── DatabaseHelper.kt                    # Banco SQLite local (SQLiteOpenHelper)
│   │   ├── ParsingEngine.kt                     # Parser de texto e detecção de ofertas
│   │   ├── OemCompatibilityHelper.kt            # Otimização para Xiaomi, Samsung e Motorola
│   │   ├── BackupManager.kt                     # Rotinas de exportação e restauração de dados
│   │   └── SupportNotificationWorker.kt         # Agendador de lembretes e backups
│   └── src/main/res/                            # Layouts, drawables glassmorphism, fontes e cores
├── bin/
│   └── DriFly.apk                               # Pacote de instalação APK pronto para uso
├── docs/                                        # Documentações técnicas e blueprints do projeto
├── build.gradle.kts                             # Configurações do Gradle
└── settings.gradle.kts                          # Definições de módulos do projeto
```

---

## 🛠️ Como Abrir e Compilar

1. **No Android Studio**:
   - Abra o Android Studio.
   - Selecione **File > Open...** e aponte para `C:\Users\Notebook\Documents\DriFly`.
   - O Gradle sincronizará automaticamente as dependências.
   - Pressione **Run** (`Shift + F10`) com o dispositivo conectado para compilar e instalar.

2. **Via Linha de Comando**:
   ```powershell
   cd "C:\Users\Notebook\Documents\DriFly"
   gradlew assembleDebug
   ```

---

## 📦 APK de Instalação

O instalador APK final encontra-se compilado na pasta:
`C:\Users\Notebook\Documents\DriFly\bin\DriFly.apk`
