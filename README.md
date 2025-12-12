# Meeting API - Sistema de Videoconferência com Transcrição em Tempo Real

Sistema completo de videoconferência com transcrição de áudio em tempo real usando Twilio, Whisper.cpp e Gemini AI.

## 📋 Índice

- [Visão Geral](#visão-geral)
- [Arquitetura](#arquitetura)
- [Stack Tecnológica](#stack-tecnológica)
- [Fluxo de Funcionamento](#fluxo-de-funcionamento)
- [Estrutura do Projeto](#estrutura-do-projeto)
- [Configuração](#configuração)
- [Instalação](#instalação)
- [Uso](#uso)
- [API Endpoints](#api-endpoints)

---

## 🎯 Visão Geral

Este sistema permite:

1. **Videoconferência em tempo real** via Twilio Video Rooms
2. **Transcrição de áudio ao vivo** de todos os participantes
3. **Sumarização inteligente** da reunião via Gemini AI
4. **Chat em tempo real** via WebSocket

### Características Principais

- ✅ Captura de áudio de **todos os participantes** (local + remotos)
- ✅ Transcrição em **tempo real** a cada 10 segundos
- ✅ Suporte a **português brasileiro**
- ✅ Redução de ruído e normalização de áudio
- ✅ Resumo estruturado com IA ao final da reunião

---

## 🏗️ Arquitetura

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              FRONTEND (Angular)                         │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐  │
│  │   Twilio     │    │    Chat      │    │   Audio Streaming        │  │
│  │   Service    │    │   Service    │    │   Service                │  │
│  │              │    │              │    │                          │  │
│  │ - joinRoom   │    │ - WebSocket  │    │ - AudioContext (mixer)   │  │
│  │ - leaveRoom  │    │ - messages   │    │ - MediaRecorder          │  │
│  │ - tracks     │    │              │    │ - Chunks cada 10s        │  │
│  └──────┬───────┘    └──────┬───────┘    └────────────┬─────────────┘  │
│         │                   │                         │                 │
└─────────┼───────────────────┼─────────────────────────┼─────────────────┘
          │                   │                         │
          ▼                   ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                              BACKEND (Spring Boot)                       │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────────────┐  │
│  │   Twilio     │    │    Chat      │    │   Streaming              │  │
│  │   Controller │    │   Controller │    │   Transcription          │  │
│  │              │    │              │    │   Controller             │  │
│  │ /token/{id}  │    │ /ws/chat     │    │                          │  │
│  └──────┬───────┘    └──────────────┘    │ POST /chunk              │  │
│         │                                │ POST /finalize           │  │
│         ▼                                │ POST /finalize-with-     │  │
│  ┌──────────────┐                        │      summary             │  │
│  │   Twilio     │                        └────────────┬─────────────┘  │
│  │   Service    │                                     │                 │
│  │              │                                     ▼                 │
│  │ - createRoom │                        ┌──────────────────────────┐  │
│  │ - getToken   │                        │   Streaming              │  │
│  └──────────────┘                        │   Transcription Service  │  │
│                                          │                          │  │
│                                          │ - processChunk()         │  │
│                                          │ - finalizeTranscription()│  │
│                                          └────────────┬─────────────┘  │
│                                                       │                 │
│                          ┌────────────────────────────┼────────────┐   │
│                          ▼                            ▼            │   │
│             ┌──────────────────────┐    ┌──────────────────────┐  │   │
│             │   Audio Converter    │    │   Whisper            │  │   │
│             │   Service            │    │   Transcription      │  │   │
│             │                      │    │   Service            │  │   │
│             │ FFmpeg:              │    │                      │  │   │
│             │ - WebM → WAV 16kHz   │    │ - whisper-cli local  │  │   │
│             │ - Noise reduction    │    │ - Modelo: small      │  │   │
│             │ - Normalization      │    │ - Idioma: pt         │  │   │
│             └──────────────────────┘    └──────────┬───────────┘  │   │
│                                                    │              │   │
│                                                    ▼              │   │
│                                         ┌──────────────────────┐  │   │
│                                         │   Gemini Summary     │◄─┘   │
│                                         │   Service            │      │
│                                         │                      │      │
│                                         │ - Resumo geral       │      │
│                                         │ - Tópicos discutidos │      │
│                                         │ - Decisões tomadas   │      │
│                                         │ - Próximos passos    │      │
│                                         └──────────────────────┘      │
│                                                                       │
└───────────────────────────────────────────────────────────────────────┘
```

---

## 🛠️ Stack Tecnológica

### Backend
| Tecnologia | Versão | Uso |
|------------|--------|-----|
| Java | 21 | Runtime |
| Spring Boot | 3.x | Framework |
| Kotlin | 1.9 | Linguagem principal |
| Twilio SDK | - | Videoconferência |
| Whisper.cpp | 1.8.2 | Transcrição de áudio |
| FFmpeg | 8.0 | Processamento de áudio |
| Gemini AI | 2.0-flash | Sumarização |

### Frontend
| Tecnologia | Versão | Uso |
|------------|--------|-----|
| Angular | 19 | Framework |
| TypeScript | 5.x | Linguagem |
| Twilio Video | - | SDK de vídeo |
| SweetAlert2 | - | Modais |
| STOMP/SockJS | - | WebSocket |

---

## 🔄 Fluxo de Funcionamento

### 1. Entrada na Sala

```
Usuário                    Frontend                    Backend                    Twilio
   │                          │                           │                          │
   │─── Preenche nome/sala ──►│                           │                          │
   │                          │                           │                          │
   │                          │─── GET /token/{identity} ─►│                          │
   │                          │                           │─── Create/Get Room ──────►│
   │                          │                           │◄── Room SID ─────────────│
   │                          │                           │─── Generate Token ───────►│
   │                          │◄── Token + Room SID ──────│◄── Access Token ─────────│
   │                          │                           │                          │
   │                          │─── Connect to Room ───────────────────────────────────►│
   │◄── Vídeo/Áudio ──────────│◄── Media Streams ─────────────────────────────────────│
```

### 2. Transcrição em Tempo Real

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           MIXAGEM DE ÁUDIO NO FRONTEND                              │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│   Microfone Local ──────┐                                                           │
│                         │                                                           │
│   Participante 1 ───────┼──► AudioContext ──► MediaStreamDestination ──► Recorder  │
│                         │         │                                                 │
│   Participante 2 ───────┤         │                                                 │
│                         │         ▼                                                 │
│   Participante N ───────┘    Mix de todos                                           │
│                              os áudios                                              │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘

                                    │
                                    │ A cada 10 segundos
                                    ▼

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           PROCESSAMENTO NO BACKEND                                   │
├─────────────────────────────────────────────────────────────────────────────────────┤
│                                                                                     │
│   1. Recebe chunk WebM (POST /api/v1/transcription/chunk)                          │
│                         │                                                           │
│                         ▼                                                           │
│   2. FFmpeg: WebM → WAV 16kHz                                                       │
│      ├── highpass=80Hz (remove ruído grave)                                         │
│      ├── lowpass=8000Hz (remove ruído agudo)                                        │
│      ├── afftdn (redução de ruído)                                                  │
│      ├── compand (compressão dinâmica)                                              │
│      └── loudnorm (normalização)                                                    │
│                         │                                                           │
│                         ▼                                                           │
│   3. Whisper.cpp                                                                    │
│      ├── Modelo: ggml-small.bin (465MB)                                             │
│      ├── Idioma: português (pt)                                                     │
│      ├── beam-size: 5                                                               │
│      ├── best-of: 5                                                                 │
│      └── suppress-nst: true (remove [MÚSICA], etc)                                  │
│                         │                                                           │
│                         ▼                                                           │
│   4. Retorna transcrição do chunk                                                   │
│                                                                                     │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### 3. Finalização e Sumarização

```
Usuário clica "Sair da Chamada"
            │
            ▼
┌───────────────────────────────────────────────────────────────┐
│  POST /api/v1/transcription/finalize-with-summary             │
│                                                               │
│  1. Combina todos os chunks transcritos                       │
│                         │                                     │
│                         ▼                                     │
│  2. Envia para Gemini AI com prompt:                          │
│     ┌─────────────────────────────────────────────────────┐   │
│     │ "Gere um resumo estruturado desta conversa..."      │   │
│     │                                                     │   │
│     │ Campos esperados:                                   │   │
│     │ - generalSummary                                    │   │
│     │ - topicsDiscussed                                   │   │
│     │ - decisionsMade                                     │   │
│     │ - nextSteps                                         │   │
│     │ - participantsMentioned                             │   │
│     │ - issuesRaised                                      │   │
│     │ - overallSentiment                                  │   │
│     └─────────────────────────────────────────────────────┘   │
│                         │                                     │
│                         ▼                                     │
│  3. Retorna transcrição completa + resumo estruturado         │
│                                                               │
└───────────────────────────────────────────────────────────────┘
            │
            ▼
┌───────────────────────────────────────────────────────────────┐
│  Tela de Transcrição (/transcription/:roomSid)                │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  📄 Transcrição Completa                                │  │
│  │  ─────────────────────────────────────────────────────  │  │
│  │  "Olá, vamos começar a reunião. Hoje vamos discutir..." │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
│  ┌─────────────────────────────────────────────────────────┐  │
│  │  📊 Resumo da Reunião                                   │  │
│  │  ─────────────────────────────────────────────────────  │  │
│  │  • Resumo Geral: ...                                    │  │
│  │  • Tópicos Discutidos: ...                              │  │
│  │  • Decisões Tomadas: ...                                │  │
│  │  • Próximos Passos: ...                                 │  │
│  └─────────────────────────────────────────────────────────┘  │
│                                                               │
└───────────────────────────────────────────────────────────────┘
```

---

## 📁 Estrutura do Projeto

```
meeting-api/
├── src/main/kotlin/com/ingstech/meeting/api/
│   ├── Application.kt
│   ├── config/
│   │   ├── AsyncConfig.kt              # Configuração de threads assíncronas
│   │   ├── CorsConfig.kt               # Configuração CORS
│   │   └── WebSocketConfig.kt          # Configuração WebSocket/STOMP
│   ├── controller/
│   │   ├── ChatController.kt           # WebSocket para chat
│   │   ├── RoomTranscriptionController.kt  # Endpoints de transcrição (Twilio recordings)
│   │   ├── RoomWebhookController.kt    # Webhooks do Twilio
│   │   ├── StreamingTranscriptionController.kt  # Transcrição em tempo real
│   │   └── TwilioController.kt         # Token e sala Twilio
│   ├── domain/
│   │   ├── RoomProcessingState.kt      # Estado do processamento
│   │   ├── RoomSummaryResult.kt        # Resultado do resumo
│   │   └── RoomTranscriptionResult.kt  # Resultado da transcrição
│   ├── model/
│   │   └── ChatMessage.kt              # Modelo de mensagem do chat
│   ├── service/
│   │   ├── AudioConverterService.kt    # Conversão de áudio (FFmpeg)
│   │   ├── GeminiSummaryService.kt     # Integração com Gemini AI
│   │   ├── RecordingService.kt         # Download de gravações Twilio
│   │   ├── RoomProcessingService.kt    # Processamento assíncrono
│   │   ├── StreamingTranscriptionService.kt  # Gerenciamento de chunks
│   │   ├── TwilioService.kt            # Integração Twilio
│   │   └── WhisperTranscriptionService.kt    # Transcrição com Whisper
│   └── util/
│       └── TwilioSignatureValidator.kt # Validação de webhooks
├── src/main/resources/
│   └── application.properties          # Configurações
├── meeting-portal/                      # Frontend Angular
│   ├── src/app/
│   │   ├── components/
│   │   │   ├── video-call.component.ts     # Componente principal
│   │   │   └── transcription.component.ts  # Tela de transcrição
│   │   └── services/
│   │       ├── audio-streaming.service.ts  # Captura e envio de áudio
│   │       ├── chat.service.ts             # Chat WebSocket
│   │       ├── transcription.service.ts    # API de transcrição
│   │       └── twilio.service.ts           # Integração Twilio Video
│   └── proxy.conf.js                    # Proxy para desenvolvimento
└── pom.xml
```

---

## ⚙️ Configuração

### application.properties

```properties
# Twilio
twilio.account.sid=ACxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
twilio.api.key.sid=SKxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
twilio.api.key.secret=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
twilio.auth.token=xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx

# Whisper
whisper.mode=local
whisper.path=/opt/homebrew/bin/whisper-cli
whisper.model=small
whisper.language=pt
whisper.threads=4
whisper.models.path=/tmp/whisper-models

# FFmpeg
ffmpeg.path=/opt/homebrew/bin/ffmpeg

# Gemini
gemini.api.key=AIzaxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
gemini.model=gemini-2.0-flash

# Server
server.port=8181
```

---

## 🚀 Instalação

### Pré-requisitos

1. **Java 21**
2. **Node.js 18+**
3. **FFmpeg**
   ```bash
   brew install ffmpeg
   ```
4. **Whisper.cpp**
   ```bash
   brew install whisper-cpp
   ```
5. **Modelo Whisper**
   ```bash
   mkdir -p /tmp/whisper-models
   curl -L -o /tmp/whisper-models/ggml-small.bin \
     "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.bin"
   ```

### Backend

```bash
cd meeting-api
./mvnw spring-boot:run
```

### Frontend

```bash
cd meeting-api/meeting-portal
npm install
npm start
```

Acesse: http://localhost:4200

---

## 📡 API Endpoints

### Twilio

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| GET | `/api/v1/twilio/token/{identity}` | Gera token para entrar na sala |

### Transcrição Streaming

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| POST | `/api/v1/transcription/chunk` | Envia chunk de áudio |
| POST | `/api/v1/transcription/finalize` | Finaliza transcrição |
| POST | `/api/v1/transcription/finalize-with-summary` | Finaliza com resumo Gemini |
| GET | `/api/v1/transcription/partial/{roomSid}` | Transcrição parcial |
| DELETE | `/api/v1/transcription/{roomSid}` | Limpa dados da sala |

### Transcrição (Gravações Twilio)

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| GET | `/api/v1/rooms/{roomSid}/transcription` | Transcrição da sala |
| GET | `/api/v1/rooms/{roomSid}/summary` | Resumo da sala |
| GET | `/api/v1/rooms/{roomSid}/full` | Transcrição + resumo |
| GET | `/api/v1/rooms/{roomSid}/status` | Status do processamento |

### Webhooks

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| POST | `/webhooks/twilio/room-ended` | Webhook quando sala encerra |

---

## 📊 Parâmetros de Qualidade

### Whisper

| Parâmetro | Valor | Descrição |
|-----------|-------|-----------|
| `-m` | ggml-small.bin | Modelo (melhor para PT) |
| `-l` | pt | Idioma português |
| `-bs` | 5 | Beam size (precisão) |
| `-bo` | 5 | Best of (qualidade) |
| `-mc` | 64 | Max context |
| `-sns` | true | Suprime [MÚSICA], etc |

### FFmpeg (Pré-processamento)

| Filtro | Descrição |
|--------|-----------|
| `highpass=f=80` | Remove frequências < 80Hz |
| `lowpass=f=8000` | Remove frequências > 8kHz |
| `afftdn=nf=-20` | Redução de ruído |
| `compand` | Compressão dinâmica |
| `loudnorm` | Normalização de volume |

---

## 📝 Licença

Projeto privado - INGSTECH

---

## 👥 Autores

- Igor Guerreiro

---

## 🚂 Deploy no Railway

### 1. Preparação

O projeto já está configurado com:
- `Dockerfile` multi-stage (Java + FFmpeg + Whisper.cpp)
- `railway.toml` com configurações de deploy
- `application.properties` com variáveis de ambiente

### 2. Deploy via Railway CLI

```bash
# Instalar Railway CLI
npm install -g @railway/cli

# Login
railway login

# Inicializar projeto
railway init

# Deploy
railway up
```

### 3. Deploy via GitHub

1. Push do código para GitHub
2. No Railway Dashboard, criar novo projeto
3. Selecionar "Deploy from GitHub repo"
4. Selecionar o repositório
5. Railway detectará o Dockerfile automaticamente

### 4. Configurar Variáveis de Ambiente

No Railway Dashboard, adicionar as seguintes variáveis:

| Variável | Descrição |
|----------|-----------|
| `TWILIO_ACCOUNT_SID` | Account SID do Twilio |
| `TWILIO_API_KEY_SID` | API Key SID |
| `TWILIO_API_KEY_SECRET` | API Key Secret |
| `TWILIO_AUTH_TOKEN` | Auth Token |
| `GEMINI_API_KEY` | API Key do Google Gemini |
| `TWILIO_WEBHOOK_URL` | URL do webhook (após deploy) |

### 5. Recursos Recomendados

| Recurso | Mínimo | Recomendado |
|---------|--------|-------------|
| RAM | 512MB | 1GB+ |
| CPU | 0.5 vCPU | 1 vCPU |
| Disco | 2GB | 5GB |

> ⚠️ **Nota**: O modelo Whisper `small` (465MB) requer memória adicional durante execução.

### 6. Monitoramento

- Health check: `GET /actuator/health`
- Logs: `railway logs`

### 7. Atualizar Webhook Twilio

Após o deploy, atualizar a variável `TWILIO_WEBHOOK_URL` com:
```
https://seu-app.railway.app/webhooks/twilio/room-ended
```
