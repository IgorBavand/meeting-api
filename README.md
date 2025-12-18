# 📹 Meeting API - Sistema de Videoconferência com Transcrição em Tempo Real

> Sistema completo de videoconferência com transcrição de áudio em tempo real usando Twilio Video, AssemblyAI, FFmpeg e Gemini AI.

---

## 📋 Índice

1. [Visão Geral](#-visão-geral)
2. [Arquitetura do Sistema](#-arquitetura-do-sistema)
3. [Stack Tecnológica](#-stack-tecnológica)
4. [Backend - Detalhamento Técnico](#-backend---detalhamento-técnico)
5. [Frontend - Detalhamento Técnico](#-frontend---detalhamento-técnico)
6. [Fluxo de Funcionamento](#-fluxo-de-funcionamento)
7. [Estrutura do Projeto](#-estrutura-do-projeto)
8. [API Endpoints](#-api-endpoints)
9. [Configuração](#-configuração)
10. [Instalação e Execução](#-instalação-e-execução)
11. [Deploy no Railway](#-deploy-no-railway)

---

## 🎯 Visão Geral

O **Meeting API** é uma solução completa de videoconferência que oferece:

| Funcionalidade | Descrição |
|----------------|-----------|
| 🎥 **Videoconferência** | Salas de vídeo em tempo real via Twilio Video Rooms |
| 🎤 **Transcrição ao Vivo** | Captura e transcrição de áudio de todos os participantes |
| 🤖 **Sumarização com IA** | Resumo estruturado da reunião via Gemini AI |
| 💬 **Chat em Tempo Real** | Mensagens instantâneas via WebSocket/STOMP |
| 📊 **Análise de Sentimento** | Avaliação automática do tom da reunião |

### Características Técnicas

- ✅ Captura de áudio de **todos os participantes** (local + remotos) via Web Audio API
- ✅ Mixagem de áudio em tempo real com **AudioContext**
- ✅ Processamento de áudio com **FFmpeg** (filtros de ruído, normalização)
- ✅ Transcrição via **AssemblyAI** com modelo otimizado para português
- ✅ Chunks de áudio enviados a cada **30 segundos** para processamento
- ✅ Resumo estruturado com **Gemini 2.5 Flash** ao final da reunião
- ✅ WebSocket para transcrição em tempo real
- ✅ Suporte a **HTTPS/WSS** para produção

---

## 🏗️ Arquitetura do Sistema

### Diagrama de Arquitetura

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                            FRONTEND (Angular 19)                             │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │   TwilioService  │  │   ChatService    │  │ WebSocketTranscription   │  │
│  │                  │  │                  │  │ Service                  │  │
│  │  • joinRoom()    │  │  • WebSocket     │  │                          │  │
│  │  • leaveRoom()   │  │  • STOMP         │  │  • AudioContext (mixer)  │  │
│  │  • tracks mgmt   │  │  • messages[]    │  │  • ScriptProcessor       │  │
│  └────────┬─────────┘  └────────┬─────────┘  │  • PCM 16-bit encoding   │  │
│           │                     │            │  • Base64 streaming      │  │
│           │                     │            └────────────┬─────────────┘  │
│           │                     │                         │                │
└───────────┼─────────────────────┼─────────────────────────┼────────────────┘
            │ HTTP                │ WS                      │ WS (audio)
            ▼                     ▼                         ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         BACKEND (Spring Boot 3.5 + Kotlin)                   │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────────┐  ┌──────────────────┐  ┌──────────────────────────┐  │
│  │ TwilioController │  │  ChatController  │  │ StreamingTranscription   │  │
│  │                  │  │                  │  │ Controller               │  │
│  │ GET /token/{id}  │  │  WS /ws/chat     │  │                          │  │
│  │ GET /token/../   │  │                  │  │  POST /chunk             │  │
│  │     room/{name}  │  │                  │  │  POST /finalize          │  │
│  └────────┬─────────┘  └──────────────────┘  │  POST /finalize-summary  │  │
│           │                                   └────────────┬─────────────┘  │
│           ▼                                                │                │
│  ┌──────────────────┐                                      │                │
│  │  TwilioService   │                                      ▼                │
│  │                  │                       ┌──────────────────────────┐    │
│  │ • createRoom()   │                       │ AssemblyAITranscription  │    │
│  │ • generateToken()│                       │ Service                  │    │
│  │ • webhook setup  │                       │                          │    │
│  └──────────────────┘                       │ • queueChunk()           │    │
│                                             │ • processChunk()         │    │
│                                             │ • finalizeTranscription()│    │
│                                             └────────────┬─────────────┘    │
│                                                          │                  │
│                          ┌───────────────────────────────┼──────────────┐   │
│                          ▼                               ▼              │   │
│            ┌──────────────────────┐       ┌──────────────────────┐     │   │
│            │ AudioConverterService│       │   AssemblyAI API     │     │   │
│            │                      │       │                      │     │   │
│            │ FFmpeg:              │       │ • Upload audio       │     │   │
│            │ • WebM → WAV 16kHz   │       │ • Create transcript  │     │   │
│            │ • highpass 80Hz      │       │ • Poll for result    │     │   │
│            │ • lowpass 8000Hz     │       │ • Portuguese (pt)    │     │   │
│            │ • afftdn noise red   │       └──────────────────────┘     │   │
│            │ • compand dynamics   │                                     │   │
│            │ • loudnorm           │                                     │   │
│            └──────────────────────┘                                     │   │
│                                                                         │   │
│                                            ┌──────────────────────┐     │   │
│                                            │ GeminiSummaryService │◄────┘   │
│                                            │                      │         │
│                                            │ • generateSummary()  │         │
│                                            │ • JSON structured    │         │
│                                            │ • Gemini 2.5 Flash   │         │
│                                            └──────────────────────┘         │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

### Fluxo de Dados

```
┌─────────────┐      ┌─────────────┐      ┌─────────────┐      ┌─────────────┐
│   Usuário   │ ───► │   Browser   │ ───► │   Backend   │ ───► │   Twilio    │
│             │      │   Angular   │      │ Spring Boot │      │   Cloud     │
└─────────────┘      └──────┬──────┘      └──────┬──────┘      └─────────────┘
                            │                    │
                            │   Audio Chunks     │
                            │   (WebM/Base64)    │
                            ▼                    ▼
                     ┌─────────────┐      ┌─────────────┐
                     │   FFmpeg    │ ───► │ AssemblyAI  │
                     │   (WAV)     │      │   (STT)     │
                     └─────────────┘      └──────┬──────┘
                                                 │
                                                 │ Transcription
                                                 ▼
                                          ┌─────────────┐
                                          │   Gemini    │
                                          │   (Summary) │
                                          └─────────────┘
```

---

## 🛠️ Stack Tecnológica

### Backend

| Tecnologia | Versão | Função |
|------------|--------|--------|
| **Kotlin** | 1.9.25 | Linguagem principal do backend |
| **Java** | 21 (Temurin) | Runtime JVM |
| **Spring Boot** | 3.5.6 | Framework web e configuração |
| **Spring WebSocket** | 3.x | Comunicação em tempo real |
| **Spring Actuator** | 3.x | Health checks e métricas |
| **Twilio SDK** | 9.14.1 | Integração com Twilio Video |
| **Jackson Kotlin** | 2.x | Serialização JSON |
| **FFmpeg** | 8.x | Processamento de áudio |
| **Maven** | 3.9.x | Build e dependências |

### Frontend

| Tecnologia | Versão | Função |
|------------|--------|--------|
| **Angular** | 19.2.0 | Framework SPA |
| **TypeScript** | 5.7.x | Linguagem tipada |
| **Twilio Video SDK** | 2.28.1 | Videoconferência |
| **RxJS** | 7.8.x | Programação reativa |
| **SweetAlert2** | 11.10.x | Modais e alertas |
| **Supabase JS** | 2.38.x | Cliente Supabase (opcional) |
| **Zone.js** | 0.15.x | Change detection |

### Serviços Externos

| Serviço | Função |
|---------|--------|
| **Twilio Video** | Salas de videoconferência P2P/SFU |
| **AssemblyAI** | Speech-to-Text (transcrição) |
| **Gemini AI** | Sumarização inteligente |

### Infraestrutura

| Componente | Tecnologia |
|------------|------------|
| **Container** | Docker (multi-stage) |
| **Deploy** | Railway |
| **Runtime** | Eclipse Temurin JRE 21 |

---

## 🔧 Backend - Detalhamento Técnico

### Estrutura de Pacotes

```
src/main/kotlin/com/ingstech/meeting/api/
├── Application.kt                    # Entry point Spring Boot
├── config/
│   ├── AsyncConfig.kt               # Thread pool para processamento assíncrono
│   ├── CorsConfig.kt                # Configuração CORS
│   └── WebSocketConfig.kt           # STOMP/SockJS configuration
├── controller/
│   ├── ChatController.kt            # WebSocket handler para chat
│   ├── RoomTranscriptionController.kt # Transcrição via gravações Twilio
│   ├── RoomWebhookController.kt     # Webhooks Twilio (room-ended)
│   ├── StreamingTranscriptionController.kt # Transcrição em tempo real
│   └── TwilioController.kt          # Token e gerenciamento de salas
├── domain/
│   ├── RoomProcessingState.kt       # Estado do processamento
│   ├── RoomSummaryResult.kt         # Resultado do resumo
│   └── RoomTranscriptionResult.kt   # Resultado da transcrição
├── model/
│   └── ChatMessage.kt               # Modelo de mensagem do chat
├── service/
│   ├── AssemblyAITranscriptionService.kt # Integração AssemblyAI
│   ├── AudioConverterService.kt     # Conversão FFmpeg
│   ├── GeminiSummaryService.kt      # Integração Gemini AI
│   ├── RealtimeTranscriptionHandler.kt # WebSocket handler
│   ├── RecordingService.kt          # Download gravações Twilio
│   └── TwilioService.kt             # SDK Twilio
└── util/
    └── TwilioSignatureValidator.kt  # Validação de webhooks
```

### Serviços Principais

#### 1. TwilioService

Responsável pela integração com Twilio Video.

```kotlin
@Service
class TwilioService {
    // Inicializa SDK Twilio
    fun init() { Twilio.init(apiKeySid, apiKeySecret, accountSid) }
    
    // Gera JWT token para acesso à sala
    fun generateTokenForRoom(guest: String, roomName: String): String
    
    // Cria sala com gravação habilitada
    fun createRoomWithRecording(roomName: String): Room
}
```

**Funcionalidades:**
- Geração de Access Tokens JWT
- Criação de salas do tipo GROUP
- Configuração de gravação automática
- Setup de webhooks para eventos

#### 2. AssemblyAITranscriptionService

Gerencia transcrição de áudio via AssemblyAI.

```kotlin
@Service
class AssemblyAITranscriptionService {
    // Estado por sala com chunks e transcrição
    private val roomStates = ConcurrentHashMap<String, AssemblyAIRoomState>()
    
    // Enfileira chunk para processamento assíncrono
    fun queueChunk(roomSid, chunkIndex, audioData, hasOverlap): Boolean
    
    // Processa chunk: FFmpeg → Upload → Transcribe
    private fun processChunk(roomState, chunkIndex, audioData)
    
    // Finaliza e combina todos os chunks
    fun finalizeTranscription(roomSid): String
}
```

**Fluxo de Processamento:**
1. Recebe chunk WebM do frontend
2. Converte para WAV 16kHz via FFmpeg
3. Faz upload para AssemblyAI
4. Cria job de transcrição
5. Polling até conclusão
6. Armazena resultado no estado da sala

**Gerenciamento de Estado:**
```kotlin
data class AssemblyAIRoomState(
    val roomSid: String,
    val chunks: ConcurrentHashMap<Int, String>,  // index → texto
    val lastChunkIndex: AtomicInteger,
    var isFinalized: Boolean,
    var fullTranscription: String?,
    var summary: Map<String, Any?>?
)
```

#### 3. AudioConverterService

Processa áudio com FFmpeg para otimização.

```kotlin
@Service
class AudioConverterService {
    fun convertToPcm16(inputPath: Path): Path? {
        // Pipeline de filtros FFmpeg:
        val audioFilters = listOf(
            "highpass=f=80",          // Remove ruído grave
            "lowpass=f=8000",         // Remove ruído agudo
            "afftdn=nf=-20",          // Redução de ruído
            "compand=...",            // Compressão dinâmica
            "loudnorm=I=-16:TP=-1.5"  // Normalização
        )
    }
}
```

**Parâmetros de Saída:**
- Sample rate: 16kHz (otimizado para STT)
- Canais: Mono
- Formato: PCM 16-bit signed
- Container: WAV

#### 4. GeminiSummaryService

Gera resumos estruturados com IA.

```kotlin
@Service
class GeminiSummaryService {
    // Prompt estruturado para Gemini
    private fun buildPrompt(transcription: String): String = """
        Gere um resumo estruturado desta conversa.
        Responda APENAS em formato JSON:
        {
            "generalSummary": "...",
            "topicsDiscussed": [...],
            "decisionsMade": [...],
            "nextSteps": [...],
            "participantsMentioned": [...],
            "issuesRaised": [...],
            "overallSentiment": "positivo/neutro/negativo"
        }
    """
    
    // Chamada à API Gemini
    private fun callGeminiApi(prompt: String): String?
}
```

**Modelo Utilizado:** `gemini-2.5-flash`
- Temperature: 0.3 (respostas consistentes)
- Max tokens: 2048

---

## 🖥️ Frontend - Detalhamento Técnico

### Estrutura de Componentes

```
meeting-portal/src/app/
├── app.component.ts              # Root component
├── app.config.ts                 # Providers configuration
├── app.routes.ts                 # Roteamento
├── components/
│   ├── video-call.component.ts   # Componente principal de chamada
│   ├── video-call.component.html # Template da chamada
│   ├── video-call.component.scss # Estilos
│   └── transcription.component.ts # Tela de resultado
├── interceptors/
│   └── ...                       # HTTP interceptors
└── services/
    ├── audio-streaming.service.ts # Captura e envio de áudio
    ├── chat.service.ts            # Chat via WebSocket
    ├── error-handler.service.ts   # Tratamento de erros
    ├── token.service.ts           # Obtenção de tokens
    ├── transcription.service.ts   # API de transcrição
    ├── twilio.service.ts          # SDK Twilio Video
    └── websocket-transcription.service.ts # Transcrição real-time
```

### Serviços Principais

#### 1. TwilioService

Gerencia conexão com Twilio Video.

```typescript
@Injectable({ providedIn: 'root' })
export class TwilioService {
    private room?: Room;
    
    // Observables para estado
    room$ = new BehaviorSubject<Room | null>(null);
    participants$ = new BehaviorSubject<RemoteParticipant[]>([]);
    participantDisconnected$ = new Subject<string>();
    
    // Conecta na sala
    async joinRoom(token: string, roomName: string): Promise<Room> {
        this.room = await connect(token, {
            name: roomName,
            audio: true,
            video: { width: 640, height: 480 }
        });
    }
    
    // Desconecta
    leaveRoom(): void { this.room?.disconnect(); }
}
```

#### 2. WebSocketTranscriptionService

Captura e transmite áudio em tempo real.

```typescript
@Injectable({ providedIn: 'root' })
export class WebSocketTranscriptionService {
    private websocket: WebSocket | null = null;
    private audioContext: AudioContext | null = null;
    private mediaStreamDestination: MediaStreamAudioDestinationNode | null = null;
    private scriptProcessor: ScriptProcessorNode | null = null;
    
    // Observables
    transcript$ = new BehaviorSubject<string>('');
    partialTranscript$ = new BehaviorSubject<string>('');
    status$ = new BehaviorSubject<string>('idle');
    
    // Inicia gravação
    async startRecording(roomSid: string): Promise<void> {
        await this.connectWebSocket();
        await this.setupAudioCapture();
        this.sendMessage({ type: 'start', roomSid });
    }
    
    // Configura captura de áudio
    private async setupAudioCapture(): Promise<void> {
        this.audioContext = new AudioContext({ sampleRate: 16000 });
        this.mediaStreamDestination = this.audioContext.createMediaStreamDestination();
        
        // Script processor para capturar amostras
        this.scriptProcessor = this.audioContext.createScriptProcessor(4096, 1, 1);
        this.scriptProcessor.onaudioprocess = (event) => {
            const pcmData = this.float32ToInt16(event.inputBuffer.getChannelData(0));
            const base64Audio = this.arrayBufferToBase64(pcmData.buffer);
            this.sendMessage({ type: 'audio', audio: base64Audio });
        };
    }
    
    // Adiciona áudio de participante remoto
    addRemoteAudioTrack(participantId: string, audioTrack: MediaStreamTrack): void {
        const stream = new MediaStream([audioTrack]);
        const source = this.audioContext.createMediaStreamSource(stream);
        source.connect(this.mediaStreamDestination);
    }
}
```

**Características:**
- Sample rate: 16kHz (requerido por AssemblyAI)
- Buffer size: 4096 samples
- Formato: PCM 16-bit signed → Base64
- Mixagem de múltiplos participantes

#### 3. AudioStreamingService (Modo Chunk)

Alternativa que envia chunks de 30 segundos.

```typescript
@Injectable({ providedIn: 'root' })
export class AudioStreamingService {
    private readonly CHUNK_DURATION_MS = 30000;  // 30 segundos
    private readonly MAX_RETRIES = 2;
    
    // Grava chunk por período
    private recordChunk(): void {
        const recorder = new MediaRecorder(
            this.mediaStreamDestination.stream, 
            { mimeType: 'audio/webm;codecs=opus', audioBitsPerSecond: 128000 }
        );
        
        recorder.ondataavailable = (event) => chunks.push(event.data);
        recorder.onstop = () => this.sendChunkToServer(blob, index);
        
        recorder.start();
        setTimeout(() => recorder.stop(), this.CHUNK_DURATION_MS - 500);
    }
    
    // Envia chunk via HTTP POST
    private async sendChunkToServer(audioBlob: Blob, chunkIndex: number): Promise<void> {
        const formData = new FormData();
        formData.append('audio', audioBlob, `chunk_${chunkIndex}.webm`);
        formData.append('roomSid', this.roomSid);
        formData.append('chunkIndex', chunkIndex.toString());
        
        await fetch(`${apiUrl}/chunk`, { method: 'POST', body: formData });
    }
}
```

### Componentes Principais

#### VideoCallComponent

Componente principal da chamada de vídeo.

```typescript
@Component({
    selector: 'app-video-call',
    standalone: true,
    imports: [CommonModule, FormsModule]
})
export class VideoCallComponent implements OnInit, OnDestroy {
    @ViewChild('localVideo') localVideo!: ElementRef<HTMLVideoElement>;
    
    identity = '';
    roomName = '';
    roomSid = '';
    isConnected = false;
    participants: RemoteParticipant[] = [];
    liveTranscription: string[] = [];
    
    async joinCall() {
        // 1. Obtém token
        const token = await this.tokenService
            .getAccessTokenForRoom(this.identity, this.roomName)
            .toPromise();
        
        // 2. Conecta na sala
        const room = await this.twilioService.joinRoom(token, this.roomName);
        this.roomSid = room.sid;
        
        // 3. Inicia transcrição
        await this.transcriptionService.startRecording(this.roomSid);
        
        // 4. Anexa vídeo local
        room.localParticipant.videoTracks.forEach(track => {
            track.track?.attach(this.localVideo.nativeElement);
        });
    }
    
    async leaveCall() {
        // 1. Para transcrição e obtém resultado
        const result = await this.transcriptionService.stopRecording();
        
        // 2. Desconecta
        this.twilioService.leaveRoom();
        
        // 3. Navega para página de transcrição
        this.router.navigate(['/transcription', this.roomSid], {
            state: { transcriptionResult: result }
        });
    }
}
```

#### TranscriptionComponent

Exibe resultado da transcrição e resumo.

```typescript
@Component({
    selector: 'app-transcription',
    standalone: true
})
export class TranscriptionComponent implements OnInit {
    roomSid: string = '';
    transcription: TranscriptionResult | null = null;
    summary: SummaryResult | null = null;
    isLoading = true;
    
    ngOnInit() {
        const state = history.state;
        
        if (state?.transcriptionResult) {
            // Usa resultado do WebSocket (instantâneo)
            this.loadWebSocketResult(state.transcriptionResult);
        } else {
            // Busca do servidor via polling
            this.loadStreamingTranscription();
        }
    }
    
    private async loadStreamingTranscription() {
        // Aguarda processamento no servidor
        const result = await this.transcriptionService
            .finalizeWithSummary(this.roomSid, this.roomName)
            .toPromise();
        
        this.transcription = result.fullTranscription;
        this.summary = result.summary;
    }
}
```

---

## 🔄 Fluxo de Funcionamento

### 1. Entrada na Sala

```
┌─────────┐           ┌─────────┐           ┌─────────┐           ┌─────────┐
│ Usuário │           │Frontend │           │ Backend │           │ Twilio  │
└────┬────┘           └────┬────┘           └────┬────┘           └────┬────┘
     │                     │                     │                     │
     │ Nome + Sala         │                     │                     │
     │────────────────────►│                     │                     │
     │                     │                     │                     │
     │                     │ GET /token/{id}/    │                     │
     │                     │     room/{name}     │                     │
     │                     │────────────────────►│                     │
     │                     │                     │                     │
     │                     │                     │ Create Room         │
     │                     │                     │────────────────────►│
     │                     │                     │                     │
     │                     │                     │◄───── Room SID ─────│
     │                     │                     │                     │
     │                     │                     │ Generate JWT        │
     │                     │                     │────────────────────►│
     │                     │                     │                     │
     │                     │◄── JWT Token ───────│◄─────── OK ─────────│
     │                     │                     │                     │
     │                     │ Twilio SDK connect  │                     │
     │                     │─────────────────────────────────────────►│
     │                     │                     │                     │
     │◄─── Vídeo/Áudio ────│◄───────────── Media Streams ─────────────│
     │                     │                     │                     │
```

### 2. Captura e Transcrição de Áudio

```
┌───────────────────────────────────────────────────────────────────────────┐
│                      MIXAGEM DE ÁUDIO NO FRONTEND                          │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│   ┌─────────────────┐                                                     │
│   │ Microfone Local │──────┐                                              │
│   └─────────────────┘      │                                              │
│                            │      ┌──────────────┐                        │
│   ┌─────────────────┐      ├─────►│              │                        │
│   │ Participante 1  │──────┤      │ AudioContext │──► MediaStream         │
│   │ (RemoteTrack)   │      │      │    Mixer     │    Destination         │
│   └─────────────────┘      │      │              │         │              │
│                            ├─────►└──────────────┘         │              │
│   ┌─────────────────┐      │                               ▼              │
│   │ Participante N  │──────┘                    ┌──────────────────┐      │
│   │ (RemoteTrack)   │                           │ ScriptProcessor  │      │
│   └─────────────────┘                           │ (4096 samples)   │      │
│                                                 └────────┬─────────┘      │
│                                                          │                │
│                                              Float32 → Int16 PCM          │
│                                                          │                │
│                                                  Base64 encoding          │
│                                                          │                │
│                                                          ▼                │
│                                                 ┌─────────────────┐       │
│                                                 │ WebSocket send  │       │
│                                                 │ { type: 'audio' │       │
│                                                 │   audio: base64 }│       │
│                                                 └─────────────────┘       │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
                                      │
                                      │ A cada 30 segundos (modo chunk)
                                      │ ou streaming contínuo
                                      ▼
┌───────────────────────────────────────────────────────────────────────────┐
│                      PROCESSAMENTO NO BACKEND                              │
├───────────────────────────────────────────────────────────────────────────┤
│                                                                           │
│   1. Recebe chunk (POST /api/v1/transcription/chunk)                      │
│      └── audioFile: MultipartFile (WebM/Opus)                             │
│      └── roomSid: String                                                  │
│      └── chunkIndex: Int                                                  │
│                                                                           │
│   2. Enfileira para processamento assíncrono                              │
│      └── ExecutorService com pool de threads                              │
│                                                                           │
│   3. FFmpeg conversion pipeline:                                          │
│      ┌──────────────────────────────────────────────────────────┐        │
│      │  ffmpeg -i input.webm                                    │        │
│      │    -af "highpass=f=80,                                   │        │
│      │         lowpass=f=8000,                                  │        │
│      │         afftdn=nf=-20,                                   │        │
│      │         compand=attacks=0.3:decays=0.8:...,              │        │
│      │         loudnorm=I=-16:TP=-1.5:LRA=11"                   │        │
│      │    -ar 16000 -ac 1 -sample_fmt s16 -f wav output.wav     │        │
│      └──────────────────────────────────────────────────────────┘        │
│                                                                           │
│   4. Upload para AssemblyAI                                               │
│      └── POST https://api.assemblyai.com/v2/upload                        │
│      └── Retorna upload_url                                               │
│                                                                           │
│   5. Cria job de transcrição                                              │
│      └── POST https://api.assemblyai.com/v2/transcript                    │
│      └── language_code: "pt"                                              │
│      └── speech_model: "best"                                             │
│                                                                           │
│   6. Polling até conclusão (500ms interval)                               │
│      └── GET https://api.assemblyai.com/v2/transcript/{id}                │
│      └── Aguarda status: "completed"                                      │
│                                                                           │
│   7. Armazena resultado no estado da sala                                 │
│      └── roomStates[roomSid].chunks[chunkIndex] = text                    │
│                                                                           │
└───────────────────────────────────────────────────────────────────────────┘
```

### 3. Finalização e Sumarização

```
┌─────────┐           ┌─────────┐           ┌─────────┐           ┌─────────┐
│ Usuário │           │Frontend │           │ Backend │           │ Gemini  │
└────┬────┘           └────┬────┘           └────┬────┘           └────┬────┘
     │                     │                     │                     │
     │ Clica "Sair"        │                     │                     │
     │────────────────────►│                     │                     │
     │                     │                     │                     │
     │                     │ POST /finalize-     │                     │
     │                     │   with-summary      │                     │
     │                     │────────────────────►│                     │
     │                     │                     │                     │
     │                     │                     │ Aguarda chunks      │
     │                     │                     │ pendentes           │
     │                     │                     │                     │
     │                     │                     │ Combina chunks      │
     │                     │                     │ ordenados           │
     │                     │                     │                     │
     │                     │                     │ Remove overlaps     │
     │                     │                     │                     │
     │                     │                     │ Chama Gemini API    │
     │                     │                     │────────────────────►│
     │                     │                     │                     │
     │                     │                     │◄─── JSON Summary ───│
     │                     │                     │                     │
     │                     │◄── Transcription ───│                     │
     │                     │    + Summary        │                     │
     │                     │                     │                     │
     │◄─── Exibe Resultado─│                     │                     │
     │                     │                     │                     │
```

### Estrutura do Resumo (Gemini)

```json
{
    "generalSummary": "Resumo geral da reunião em 2-3 frases",
    "topicsDiscussed": [
        "Tópico 1",
        "Tópico 2"
    ],
    "decisionsMade": [
        "Decisão 1",
        "Decisão 2"
    ],
    "nextSteps": [
        "Próximo passo 1",
        "Próximo passo 2"
    ],
    "participantsMentioned": [
        "Nome 1",
        "Nome 2"
    ],
    "issuesRaised": [
        "Problema 1",
        "Dúvida 1"
    ],
    "overallSentiment": "positivo | neutro | negativo"
}
```

---

## 📁 Estrutura do Projeto

```
meeting-api/
├── 📁 src/
│   ├── 📁 main/
│   │   ├── 📁 kotlin/com/ingstech/meeting/api/
│   │   │   ├── Application.kt
│   │   │   ├── 📁 config/
│   │   │   │   ├── AsyncConfig.kt
│   │   │   │   ├── CorsConfig.kt
│   │   │   │   └── WebSocketConfig.kt
│   │   │   ├── 📁 controller/
│   │   │   │   ├── ChatController.kt
│   │   │   │   ├── RoomTranscriptionController.kt
│   │   │   │   ├── RoomWebhookController.kt
│   │   │   │   ├── StreamingTranscriptionController.kt
│   │   │   │   └── TwilioController.kt
│   │   │   ├── 📁 domain/
│   │   │   │   ├── RoomProcessingState.kt
│   │   │   │   ├── RoomSummaryResult.kt
│   │   │   │   └── RoomTranscriptionResult.kt
│   │   │   ├── 📁 model/
│   │   │   │   └── ChatMessage.kt
│   │   │   ├── 📁 service/
│   │   │   │   ├── AssemblyAITranscriptionService.kt
│   │   │   │   ├── AudioConverterService.kt
│   │   │   │   ├── GeminiSummaryService.kt
│   │   │   │   ├── RealtimeTranscriptionHandler.kt
│   │   │   │   ├── RecordingService.kt
│   │   │   │   └── TwilioService.kt
│   │   │   └── 📁 util/
│   │   │       └── TwilioSignatureValidator.kt
│   │   └── 📁 resources/
│   │       ├── application.properties
│   │       └── application-local.properties
│   └── 📁 test/kotlin/
│       └── ... (testes)
├── 📁 meeting-portal/                    # Frontend Angular
│   ├── 📁 src/
│   │   ├── 📁 app/
│   │   │   ├── app.component.ts
│   │   │   ├── app.config.ts
│   │   │   ├── app.routes.ts
│   │   │   ├── 📁 components/
│   │   │   │   ├── video-call.component.ts
│   │   │   │   ├── video-call.component.html
│   │   │   │   ├── video-call.component.scss
│   │   │   │   └── transcription.component.ts
│   │   │   ├── 📁 interceptors/
│   │   │   └── 📁 services/
│   │   │       ├── audio-streaming.service.ts
│   │   │       ├── chat.service.ts
│   │   │       ├── error-handler.service.ts
│   │   │       ├── token.service.ts
│   │   │       ├── transcription.service.ts
│   │   │       ├── twilio.service.ts
│   │   │       └── websocket-transcription.service.ts
│   │   ├── 📁 environments/
│   │   ├── index.html
│   │   ├── main.ts
│   │   └── styles.scss
│   ├── angular.json
│   ├── package.json
│   ├── proxy.conf.js
│   └── tsconfig.json
├── Dockerfile                            # Multi-stage build
├── railway.toml                          # Config Railway
├── pom.xml                               # Maven config
├── mvnw                                  # Maven wrapper
└── README.md                             # Esta documentação
```

---

## 📡 API Endpoints

### Twilio (Autenticação e Salas)

| Método | Endpoint | Descrição | Request | Response |
|--------|----------|-----------|---------|----------|
| `GET` | `/api/v1/twilio/token/{guest}` | Gera token genérico | - | `String` (JWT) |
| `GET` | `/api/v1/twilio/token/{guest}/room/{roomName}` | Gera token + cria sala | - | `String` (JWT) |
| `POST` | `/api/v1/twilio/room/{roomName}` | Cria sala manualmente | - | `{ sid, name, status }` |

### Transcrição Streaming

| Método | Endpoint | Descrição | Request | Response |
|--------|----------|-----------|---------|----------|
| `POST` | `/api/v1/transcription/chunk` | Envia chunk de áudio | `multipart/form-data` | `{ success, chunkIndex, queued }` |
| `POST` | `/api/v1/transcription/finalize` | Finaliza transcrição | `{ roomSid }` | `{ fullTranscription, wordCount }` |
| `POST` | `/api/v1/transcription/finalize-with-summary` | Finaliza + resumo IA | `{ roomSid, roomName? }` | `{ fullTranscription, summary }` |
| `GET` | `/api/v1/transcription/partial/{roomSid}` | Transcrição parcial | - | `{ transcription, status }` |
| `GET` | `/api/v1/transcription/status/{roomSid}` | Status processamento | - | `{ processedChunks, activeProcessing, ... }` |
| `DELETE` | `/api/v1/transcription/{roomSid}` | Limpa dados da sala | - | `{ success, message }` |

### Request/Response Detalhados

#### POST /api/v1/transcription/chunk

**Request (multipart/form-data):**
```
audio: [Binary WebM file]
roomSid: "RM1234567890abcdef"
chunkIndex: 0
hasOverlap: false
```

**Response:**
```json
{
    "success": true,
    "chunkIndex": 0,
    "queued": true,
    "status": {
        "exists": true,
        "processedChunks": 1,
        "activeProcessing": 1,
        "isFinalized": false,
        "lastChunkIndex": 0
    }
}
```

#### POST /api/v1/transcription/finalize-with-summary

**Request:**
```json
{
    "roomSid": "RM1234567890abcdef",
    "roomName": "Reunião Semanal"
}
```

**Response:**
```json
{
    "success": true,
    "roomSid": "RM1234567890abcdef",
    "fullTranscription": "Olá, vamos começar a reunião...",
    "wordCount": 245,
    "summary": {
        "generalSummary": "Reunião sobre planejamento do projeto...",
        "topicsDiscussed": ["Cronograma", "Recursos", "Riscos"],
        "decisionsMade": ["Aprovar orçamento", "Contratar desenvolvedor"],
        "nextSteps": ["Finalizar documentação até sexta"],
        "participantsMentioned": ["João", "Maria"],
        "issuesRaised": ["Prazo apertado para entrega"],
        "overallSentiment": "positivo",
        "status": "COMPLETED"
    }
}
```

### Webhooks

| Método | Endpoint | Descrição |
|--------|----------|-----------|
| `POST` | `/webhooks/twilio/room-ended` | Callback quando sala encerra |

### WebSocket

| Endpoint | Protocolo | Descrição |
|----------|-----------|-----------|
| `/ws/transcription` | WebSocket | Transcrição em tempo real |
| `/ws/chat` | STOMP/SockJS | Chat em tempo real |

---

## ⚙️ Configuração

### application.properties

```properties
# Server
spring.application.name=meeting.api
server.port=${PORT:8181}

# Twilio Configuration
twilio.account.sid=${TWILIO_ACCOUNT_SID:}
twilio.api.key.sid=${TWILIO_API_KEY_SID:}
twilio.api.key.secret=${TWILIO_API_KEY_SECRET:}
twilio.auth.token=${TWILIO_AUTH_TOKEN:}
twilio.webhook.url=${TWILIO_WEBHOOK_URL:}
twilio.signature.validation.enabled=${TWILIO_SIGNATURE_VALIDATION:false}

# FFmpeg
ffmpeg.path=${FFMPEG_PATH:ffmpeg}

# Gemini AI
gemini.api.key=${GEMINI_API_KEY:}
gemini.model=${GEMINI_MODEL:gemini-2.5-flash}

# AssemblyAI
assemblyai.api.key=${ASSEMBLYAI_API_KEY:}
assemblyai.language=${ASSEMBLYAI_LANGUAGE:pt}

# Async Processing
spring.task.execution.pool.core-size=2
spring.task.execution.pool.max-size=4
spring.task.execution.pool.queue-capacity=10

# Actuator
management.endpoints.web.exposure.include=health,info
management.endpoint.health.show-details=always

# Logging
logging.level.com.ingstech=INFO
logging.level.org.springframework.web=INFO
```

### Variáveis de Ambiente

| Variável | Obrigatória | Descrição |
|----------|-------------|-----------|
| `TWILIO_ACCOUNT_SID` | ✅ | Account SID do Twilio |
| `TWILIO_API_KEY_SID` | ✅ | API Key SID |
| `TWILIO_API_KEY_SECRET` | ✅ | API Key Secret |
| `TWILIO_AUTH_TOKEN` | ✅ | Auth Token |
| `ASSEMBLYAI_API_KEY` | ✅ | API Key AssemblyAI |
| `GEMINI_API_KEY` | ✅ | API Key Google Gemini |
| `PORT` | ❌ | Porta do servidor (default: 8181) |
| `FFMPEG_PATH` | ❌ | Caminho do FFmpeg (default: ffmpeg) |
| `TWILIO_WEBHOOK_URL` | ❌ | URL base para webhooks |

---

## 🚀 Instalação e Execução

### Pré-requisitos

1. **Java 21** (Eclipse Temurin recomendado)
2. **Node.js 18+**
3. **FFmpeg**
   ```bash
   # macOS
   brew install ffmpeg
   
   # Ubuntu/Debian
   sudo apt-get install ffmpeg
   ```

### Backend

```bash
# Clone o repositório
git clone <repository-url>
cd meeting-api

# Configure variáveis de ambiente
export TWILIO_ACCOUNT_SID=ACxxx
export TWILIO_API_KEY_SID=SKxxx
export TWILIO_API_KEY_SECRET=xxx
export TWILIO_AUTH_TOKEN=xxx
export ASSEMBLYAI_API_KEY=xxx
export GEMINI_API_KEY=AIzaxxx

# Execute
./mvnw spring-boot:run

# Ou build e execute
./mvnw package -DskipTests
java -jar target/*.jar
```

O backend estará disponível em: `http://localhost:8181`

### Frontend

```bash
cd meeting-portal

# Instale dependências
npm install

# Execute (modo desenvolvimento com HTTPS)
npm start
```

O frontend estará disponível em: `https://localhost:4200`

> **Nota:** O frontend usa HTTPS por padrão devido à necessidade de `getUserMedia()` para acesso à câmera/microfone.

### Docker (Local)

```bash
# Build
docker build -t meeting-api .

# Run
docker run -p 8080:8080 \
  -e TWILIO_ACCOUNT_SID=ACxxx \
  -e TWILIO_API_KEY_SID=SKxxx \
  -e TWILIO_API_KEY_SECRET=xxx \
  -e ASSEMBLYAI_API_KEY=xxx \
  -e GEMINI_API_KEY=AIzaxxx \
  meeting-api
```

---

## 🚂 Deploy no Railway

### 1. Configuração

O projeto já está configurado com:
- `Dockerfile` multi-stage otimizado
- `railway.toml` com configurações de deploy

### 2. Deploy via CLI

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
4. Railway detectará o Dockerfile automaticamente

### 4. Variáveis de Ambiente no Railway

Configure no Dashboard:

| Variável | Descrição |
|----------|-----------|
| `TWILIO_ACCOUNT_SID` | Account SID |
| `TWILIO_API_KEY_SID` | API Key SID |
| `TWILIO_API_KEY_SECRET` | API Key Secret |
| `TWILIO_AUTH_TOKEN` | Auth Token |
| `ASSEMBLYAI_API_KEY` | API Key AssemblyAI |
| `GEMINI_API_KEY` | API Key Gemini |
| `TWILIO_WEBHOOK_URL` | URL após deploy |

### 5. Recursos Recomendados

| Recurso | Mínimo | Recomendado |
|---------|--------|-------------|
| RAM | 512MB | 1GB+ |
| CPU | 0.5 vCPU | 1 vCPU |
| Disco | 2GB | 5GB |

### 6. Health Check

O endpoint de health check está disponível em:
```
GET /actuator/health
```

---

## 📝 Licença

Projeto privado - **INGSTECH**

---

## 👥 Autor

- **Igor Guerreiro** - Desenvolvimento Full Stack

---

## 📚 Referências

- [Twilio Video Documentation](https://www.twilio.com/docs/video)
- [AssemblyAI API Reference](https://www.assemblyai.com/docs)
- [Google Gemini API](https://ai.google.dev/docs)
- [Angular Documentation](https://angular.io/docs)
- [Spring Boot Reference](https://docs.spring.io/spring-boot/docs/current/reference/html/)
- [FFmpeg Filters](https://ffmpeg.org/ffmpeg-filters.html)
