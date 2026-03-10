# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Jicofo (JItsi COnference FOcus) is a signaling server for Jitsi Meet conferences. It manages XMPP Multi-User Chat (MUC) rooms, initiates Jingle sessions with participants, and coordinates media routing through Jitsi Videobridge instances using the Colibri2 protocol.

## Build and Test Commands

### Building
```bash
mvn install                          # Build all modules and create distribution package
mvn clean install                    # Clean build
mvn install -DskipTests              # Build without running tests
```

The distribution package is created in `jicofo/target/jicofo-1.1-SNAPSHOT-archive.zip`.

### Testing
```bash
mvn test                             # Run all tests
mvn test -pl jicofo-selector         # Run tests for specific module
mvn test -Dtest=BridgeSelectorTest   # Run specific test class
```

### Code Quality
```bash
mvn ktlint:check                     # Run ktlint (must be run for individual modules e.g. in ./jicofo-selector/)
mvn checkstyle:check                 # Run checkstyle (config: checkstyle.xml)
```

## Architecture

### Module Structure

This is a multi-module Maven project with three modules:

1. **jicofo-common** - Shared utilities and data structures
   - Conference source management (`ConferenceSourceMap`, `EndpointSourceSet`, `Source`)
   - XMPP/MUC abstractions (`ChatRoom`, `ChatRoomMember`, `BaseBrewery`)
   - Codec management
   - Metrics infrastructure
   - No dependencies on other jicofo modules

2. **jicofo-selector** - Bridge selection and Colibri session management
   - Self-contained, reusable component for bridge selection logic
   - Bridge management (`Bridge`, `BridgeSelector`, `BridgeMucDetector`)
   - Pluggable selection strategies:
     - `SingleBridgeSelectionStrategy` - Single bridge, no Octo
     - `RegionBasedBridgeSelectionStrategy` - Geographic distribution with Octo
     - `IntraRegionBridgeSelectionStrategy` - Load-balanced Octo within regions
     - `VisitorSelectionStrategy` - Separate strategies for visitors vs participants
   - Colibri2 session management (`ColibriV2SessionManager`, `Colibri2Session`)
   - Depends only on jicofo-common

3. **jicofo** - Main application module
   - Entry point: `Main.java`
   - Service orchestration: `JicofoServices.kt` (creates and manages all services)
   - Conference management: `JitsiMeetConferenceImpl` (Java, ~2000+ lines, core conference logic)
   - Focus management: `FocusManager` (conference lifecycle, room JID mapping)
   - XMPP services: `XmppServices`, `XmppProvider`, modular IQ handlers
   - Authentication: `AbstractAuthAuthority`, `XMPPDomainAuthAuthority`, `ExternalJWTAuthority`
   - External service integration: Jibri (recording/streaming), Jigasi (SIP gateway)
   - REST API: Ktor-based HTTP interface for health checks, stats, debug endpoints
   - Depends on both jicofo-common and jicofo-selector

### Key Components & Responsibilities

**Conference Management**
- `JitsiMeetConferenceImpl` - Main conference implementation (manages Jingle sessions, coordinates with bridges, handles MUC membership, lifecycle management)
- `Participant` - Represents a conference participant with Jingle session, sources, and bridge allocation
- `ParticipantInviteRunnable` - Handles participant invitation flow (Jingle session establishment)
- `FocusManager` - Conference lifecycle management, maps room JIDs to conference instances

**Bridge Selection**
- `BridgeSelector` - Core component for selecting optimal bridges based on health, stress, operational status, and configured strategy
- `Bridge` - Represents a single jitsi-videobridge instance with health and stress tracking
- `BridgeMucDetector` - Discovers bridges via XMPP MUC (brewery pattern)
- `JvbDoctor` - Health check management for bridges

**Colibri Session Management**
- `ColibriV2SessionManager` - Manages Colibri2 sessions with bridges
- `Colibri2Session` - Represents a session with a single bridge
- Sends `ConferenceModifyIQ` to bridges for allocations and updates

**XMPP Handling**
- `XmppProvider` - Manages multiple XMPP connections (client, service, visitor)
- Modular IQ handlers: `ConferenceIqHandler`, `JingleIqRequestHandler`, `JibriIqHandler`, etc.
- `VisitorsManager` - Manages visitor functionality across multiple XMPP nodes

### Application Flow

1. **Startup**: `Main.java` loads config, creates `JicofoServices`, sets up signal handlers
2. **Conference Creation**: Client requests conference → `FocusManager.conferenceRequest()` → `JitsiMeetConferenceImpl.start()`
3. **Participant Join**: Participant joins MUC → conference detects new member → `ParticipantInviteRunnable` selects bridge, allocates Colibri endpoint, sends Jingle session-initiate
4. **Bridge Selection**: Considers operational status, stress level, graceful shutdown status, version compatibility, and selection strategy
5. **Media Flow**: Participant accepts Jingle session, media flows through videobridge

### Configuration

Configuration uses HOCON format (lightbend/config). Main config file is typically `/etc/jitsi/jicofo/jicofo.conf`.

Reference configuration with all available options: `jicofo-selector/src/main/resources/reference.conf`

Configuration objects in code are typically Kotlin companion objects (e.g., `BridgeConfig.config`, `ConferenceConfig.config`).

### Language & Conventions

- **Kotlin**: New code, utilities, data structures, all test code
- **Java**: Legacy code, notably `JitsiMeetConferenceImpl` (complex conference management)
- **Interop**: Seamless with `@JvmOverloads`, `@JvmField`, companion objects

### Testing Framework

- **Primary**: Kotest (BDD-style specs with `ShouldSpec`)
- **Style**: `context("...") { should("...") { ... } }` for behavior-driven tests
- **Mocking**: MockK for Kotlin (`mockk { every { ... } returns ... }`)
- **Test Utilities**: Mock implementations in `jicofo/src/test/kotlin/org/jitsi/jicofo/mock/`
  - `MockXmppConnection`, `MockXmppProvider` - XMPP testing
  - `MockChatRoom` - MUC simulation
  - `TestColibri2Server` - Colibri2 protocol testing
- **Configuration Testing**: Use `withNewConfig { ... }` helper for custom configs
- **Time Control**: `FakeClock` for time-dependent tests
- **Isolation**: Tests use `IsolationMode.InstancePerLeaf` for test isolation
- **Location**: Tests mirror source structure (`src/test/kotlin` matches `src/main/kotlin`)

### Important Architectural Patterns

**Separation of Concerns**: Clear module boundaries (common utilities, bridge selection logic, application orchestration)

**Dependency Injection via Constructor**: Services are composed via constructor injection in `JicofoServices.kt`

**Strategy Pattern**: Bridge selection strategies and topology strategies are pluggable, configured via HOCON

**Observer Pattern**: Event-driven architecture with `AsyncEventEmitter` (e.g., `BridgeSelector.EventHandler`, `ColibriSessionManager.Listener`)

**Thread Safety**: Extensive use of `synchronized` blocks, `ConferenceSourceMap` uses `ConcurrentHashMap`, careful lock ordering to prevent deadlocks

**Metrics & Observability**: Centralized metrics via `JicofoMetricsContainer`, periodic metric updates, debug state endpoints (`debugState` properties)

### Key Protocols

**XMPP**: Communication backbone with multiple connections (Client for participants, Service for bridges, Visitor for visitor nodes)

**Jingle**: Standard XMPP protocol for peer-to-peer session negotiation (jicofo terminates Jingle signaling, not media)

**Colibri2**: Modern protocol for bridge communication (endpoint allocation, management, relay support for Octo)

**Brewery Pattern**: Service discovery - bridges/Jibris/Jigasis join specific MUC rooms, jicofo monitors presence

### Notable Design Decisions

- **Graceful Degradation**: Bridges report stress levels, can enter graceful shutdown, health checks with timeout-based recovery
- **Scalability**: Multi-bridge support (Octo mesh), region-based selection, visitor architecture for large conferences, load redistribution
- **Singleton with Synchronization**: `JicofoServices` uses synchronized singleton pattern (required because services start threads that depend on the singleton)
- **Legacy Code Preservation**: `JitsiMeetConferenceImpl` remains in Java with extensive synchronization (noted as needing refactoring, but stable)
