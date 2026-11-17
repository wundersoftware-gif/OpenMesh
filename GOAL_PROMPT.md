# GOAL_PROMPT.md --- OpenMesh VPN

## Objetivo general

Diseñar e implementar **OpenMesh VPN**, una aplicación Android que
permita que el tráfico de WhatsApp Messenger de un teléfono sin acceso
directo a Internet salga a Internet a través de otros teléfonos Android
cercanos.

La aplicación debe crear una red de transporte entre dispositivos
Android. Un teléfono puede actuar como cliente, relay o gateway. El
tráfico debe poder recorrer varios nodos antes de llegar a un
dispositivo que disponga de una conexión a Internet.

**El objetivo del MVP es demostrar una ruta real A → B → C → Internet →
WhatsApp**, sin root, sin modificar WhatsApp y sin utilizar servidores
centrales para transportar el tráfico.

El proyecto debe desarrollarse como software real, compilable, probado y
documentado. No se acepta una demostración simulada presentada como
funcionalidad terminada.

------------------------------------------------------------------------

## 1. Alcance del MVP

### Plataforma

-   Android inicialmente.
-   Prioridad a dispositivos Android físicos `arm64-v8a`.
-   Desarrollo posible desde Windows 11, VS Code, PowerShell y
    herramientas oficiales de línea de comandos.
-   No exigir Android Studio como entorno de desarrollo.
-   Sin root.
-   No depender de un servidor central para retransmitir tráfico.

### Aplicación objetivo

-   WhatsApp Messenger estándar: `com.whatsapp`.
-   La VPN debe incluir únicamente el tráfico de ese paquete mediante
    las APIs oficiales de Android.
-   Las demás aplicaciones deben conservar su conectividad normal y no
    utilizar OpenMesh VPN.
-   No modificar, automatizar, inspeccionar internamente ni realizar
    ingeniería inversa de WhatsApp.
-   No utilizar APIs privadas ni protocolos no documentados de WhatsApp.

### Funciones dentro del MVP

1.  Servicio VPN Android basado en `VpnService`.
2.  Interfaz TUN.
3.  Captura selectiva del tráfico de WhatsApp.
4.  Integración de un motor `tun2socks` de espacio de usuario.
5.  Gateway Android sin root que acepte conexiones autenticadas y abra
    sockets de salida.
6.  Transporte cifrado entre dispositivos.
7.  Relay intermedio capaz de reenviar streams sin interpretarlos.
8.  Descubrimiento de nodos mediante BLE.
9.  Transporte P2P mediante WiFi Direct cuando el dispositivo lo
    soporte.
10. Enrutamiento multi-hop inicial.
11. Diagnóstico, métricas y registros sin contenido privado.
12. Pruebas automatizadas y pruebas en dispositivos físicos.

### Fuera del alcance inicial

-   iOS.
-   Llamadas de voz o vídeo de WhatsApp.
-   Garantía de compatibilidad con todas las funciones de WhatsApp.
-   UDP genérico y QUIC de aplicaciones en la primera versión.
-   Redes Mesh de gran escala.
-   Recompensas o incentivos.
-   Servidores centrales de transporte.
-   Evasión de portales cautivos, autenticación WiFi, restricciones de
    red o controles de acceso.
-   Uso automático de datos móviles o WiFi de terceros sin
    consentimiento explícito.
-   Integración con WhatsApp Business Platform.

------------------------------------------------------------------------

## 2. Arquitectura objetivo

Separar el sistema en tres planos:

### A. Plano VPN del cliente

En el teléfono A:

1.  Android `VpnService` crea una interfaz TUN.
2.  Se permite únicamente `com.whatsapp` mediante el mecanismo oficial
    de VPN por aplicación.
3.  El tráfico capturado se entrega al motor `tun2socks`.
4.  El motor convierte las conexiones TCP en flujos SOCKS5.
5.  DNS debe resolverse a través del Gateway y no debe escapar por la
    conexión local.
6.  Los sockets que utiliza el propio transporte OpenMesh deben
    excluirse de la VPN mediante `VpnService.protect()` para evitar
    bucles.

El modo de fallo debe ser **fail-closed**: si no hay una ruta válida o
el Gateway no está disponible, el tráfico de WhatsApp no debe escapar
silenciosamente por una conexión no autorizada.

### B. Plano Mesh

Cada nodo puede desempeñar uno o varios roles:

-   `CLIENT`: origina tráfico VPN.
-   `RELAY`: reenvía streams de otros nodos.
-   `GATEWAY`: proporciona salida a Internet.
-   `HYBRID`: combina roles.

El relay no debe resolver destinos de Internet ni abrir conexiones
finales salvo que también tenga explícitamente el rol Gateway.

El relay debe reenviar datos cifrados y no tener acceso al contenido de
las conexiones de aplicación.

### C. Plano Gateway

En el teléfono C:

1.  Recibe solicitudes de conexión desde nodos autenticados.
2.  Valida identidad, autorización y políticas.
3.  Abre conexiones TCP de salida mediante sockets normales de Android.
4.  Devuelve las respuestas por el mismo stream lógico.
5.  Aplica límites de conexiones, timeouts, cuotas y controles
    antiabuso.
6.  No necesita root, `iptables`, NAT del kernel ni privilegios de
    router.

El Gateway no debe convertirse en un proxy SOCKS5 público.

------------------------------------------------------------------------

## 3. Decisiones técnicas iniciales

Estas decisiones son hipótesis de implementación que deben validarse con
documentación oficial y pruebas. No asumir que una librería o API tiene
capacidades que no se hayan comprobado.

  Área                        Dirección inicial
  --------------------------- --------------------------------------------------------
  Lenguaje Android            Kotlin
  UI                          Jetpack Compose, si aporta valor
  VPN                         Android `VpnService`
  Filtrado                    `addAllowedApplication("com.whatsapp")`
  TUN                         Descriptor proporcionado por Android
  tun2socks                   Evaluar implementación Rust basada en smoltcp
  Integración nativa          JNI, con interfaz pequeña
  Descubrimiento              BLE
  Transporte físico           WiFi Direct, sujeto a pruebas de concurrencia
  Transporte lógico inicial   TCP + TLS 1.3
  Gateway                     SOCKS5 restringido, implementado en espacio de usuario
  Persistencia                Room y DataStore, si son necesarios
  Routing                     Motor propio pequeño en la primera iteración
  Tests                       JUnit y Android Instrumentation
  Arquitectura                Módulos desacoplados

### Reglas de selección de dependencias

-   Verificar versión estable actual, licencia, mantenimiento y
    compatibilidad Android antes de incorporar cualquier dependencia.
-   Fijar versiones reproducibles.
-   Evitar dependencias abandonadas.
-   No copiar código de terceros sin revisar licencia.
-   Preferir APIs Android oficiales.
-   No implementar criptografía propia.
-   No afirmar que una biblioteca soporta una función hasta comprobarlo
    en documentación y pruebas.

### tun2socks

Investigar y comparar alternativas antes de decidir:

-   Proyecto basado en Rust y smoltcp.
-   sing-box y sus posibilidades de integración.
-   Otras implementaciones mantenidas compatibles con Android.

La selección debe documentarse en un ADR, incluyendo licencia, ABI,
integración JNI, soporte TCP/UDP, IPv4/IPv6, mantenimiento y
restricciones.

No integrar una solución completa de terceros sin comprender su
arquitectura y licencia.

------------------------------------------------------------------------

## 4. Estructura de repositorio propuesta

Crear un Gradle multiproyecto. Adaptar los nombres si la implementación
elegida lo justifica, pero conservar separación de responsabilidades.

``` text
OpenMesh/
├── app/
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── kotlin/com/openmesh/app/
│       │   ├── MainActivity.kt
│       │   ├── OpenMeshApplication.kt
│       │   ├── ui/
│       │   ├── service/
│       │   └── permissions/
│       └── res/
├── core/
│   ├── model/
│   ├── protocol/
│   ├── crypto/
│   └── routing/
├── vpn/
│   ├── android/
│   └── tun2socks/
├── discovery/
│   ├── ble/
│   └── peers/
├── transport/
│   ├── tcp/
│   ├── tls/
│   └── framing/
├── relay/
│   ├── forwarder/
│   └── session/
├── gateway/
│   ├── socks5/
│   ├── outbound/
│   └── dns/
├── storage/
│   ├── database/
│   └── preferences/
├── diagnostics/
│   ├── metrics/
│   └── logging/
├── test/
│   ├── unit/
│   ├── integration/
│   ├── network/
│   └── fixtures/
├── docs/
│   ├── ARCHITECTURE.md
│   ├── PROTOCOL.md
│   ├── SECURITY.md
│   ├── TEST_PLAN.md
│   └── ADR/
├── gradle/
│   └── libs.versions.toml
├── settings.gradle.kts
├── build.gradle.kts
├── gradle.properties
├── README.md
└── GOAL_PROMPT.md
```

### Reglas de dependencia

-   `core/model` no depende de Android.
-   `core/protocol` depende solo de modelos y utilidades puras.
-   `core/routing` no depende de Android.
-   `transport` depende de interfaces de `core`, no de UI.
-   `relay` depende de `core` y `transport`.
-   `gateway` depende de `core` y `transport`.
-   `vpn/android` integra APIs Android y el motor tun2socks.
-   `app` compone los módulos.
-   Evitar dependencias circulares.
-   Mantener interfaces testeables y sustituibles.

------------------------------------------------------------------------

## 5. Interfaces de módulos

Definir contratos equivalentes a los siguientes. Se pueden ajustar tipos
y nombres, pero mantener las responsabilidades.

``` kotlin
data class MeshNode(
    val id: String,
    val publicKey: ByteArray,
    val capabilities: Set<NodeCapability>,
    val batteryPercent: Int?,
    val isGateway: Boolean
)

enum class NodeCapability {
    CLIENT, RELAY, GATEWAY
}

interface PeerDiscovery {
    suspend fun start()
    suspend fun stop()
    fun observePeers(): Flow<MeshNode>
    suspend fun connect(nodeId: String): PeerConnection
}

interface MeshTransport {
    suspend fun connect(nodeId: String): MeshConnection
    suspend fun send(connection: MeshConnection, frame: MeshFrame)
    fun observeFrames(connection: MeshConnection): Flow<MeshFrame>
    suspend fun close(connection: MeshConnection)
}

interface MeshRouter {
    suspend fun updatePeer(node: MeshNode)
    suspend fun removePeer(nodeId: String)
    suspend fun findRoute(destinationId: String): MeshRoute?
    suspend fun selectGateway(): GatewayInfo?
}

interface StreamForwarder {
    suspend fun openStream(
        destinationId: String,
        streamId: String
    ): ForwardedStream

    suspend fun closeStream(streamId: String)
}

interface GatewayServer {
    suspend fun start()
    suspend fun stop()
    fun observeConnections(): Flow<GatewayConnection>
}

interface VpnController {
    suspend fun requestPermission(): Boolean
    suspend fun start()
    suspend fun stop()
    fun observeStatus(): Flow<VpnStatus>
}
```

Definir modelos concretos, errores tipados, cancelación, timeouts y
ciclo de vida. No dejar interfaces vacías o implementaciones simuladas
en el camino de producción.

------------------------------------------------------------------------

## 6. Protocolo de transporte

Diseñar un protocolo binario versionado. No usar JSON para cada paquete
de datos.

Formato de frame inicial:

  Campo                  Tamaño
  ------------------ ----------
  Magic                 4 bytes
  Protocol version       1 byte
  Message type           1 byte
  Flags                 2 bytes
  Stream ID             8 bytes
  Destination ID       16 bytes
  Payload length        4 bytes
  Payload              Variable

Tipos iniciales:

``` text
HELLO
AUTH
PEER_ANNOUNCE
ROUTE_UPDATE
OPEN_STREAM
STREAM_DATA
WINDOW_UPDATE
CLOSE_STREAM
PING
PONG
ERROR
```

Especificar formalmente:

-   Endianness.
-   Longitud máxima.
-   Validación de frames.
-   Control de flujo.
-   Backpressure.
-   IDs de sesión y stream.
-   Timeouts.
-   Errores recuperables y fatales.
-   Protección contra replay.
-   Límites de buffers.
-   Política de cierre.
-   Compatibilidad de versiones.

No aceptar longitudes arbitrarias ni asignar memoria sin límites.

### Transporte inicial

TCP + TLS 1.3 para la primera versión. TLS debe autenticar a los nodos
autorizados; no basta con cifrar una conexión anónima.

El diseño debe permitir reemplazar el transporte por QUIC más adelante,
sin modificar la interfaz VPN ni la interfaz SOCKS5.

------------------------------------------------------------------------

## 7. Routing

Implementar inicialmente un motor pequeño de rutas y vecinos. No
implementar un protocolo distribuido complejo antes de disponer de
pruebas.

Cada nodo debe exponer únicamente información necesaria:

-   Identificador de nodo.
-   Clave pública o referencia autenticada.
-   Capacidades.
-   Vecinos disponibles.
-   Calidad del enlace.
-   Disponibilidad como Gateway.
-   Caducidad de anuncios.

La métrica de ruta puede combinar:

``` text
cost = alpha * hops
     + beta  * latency
     + gamma * packet_loss
     + delta * instability
     + epsilon * battery_penalty
```

Los coeficientes deben ser configurables y medibles.

Implementar:

-   TTL/hop limit.
-   Expiración de rutas.
-   Detección de bucles.
-   Detección de vecinos caídos.
-   Rechazo de anuncios no autenticados.
-   Selección de Gateway.
-   Prevención de tormentas de anuncios.
-   Reintentos con backoff.

Documentar Babel (RFC 8966) como referencia para una posible evolución,
no asumir que hay que implementarlo en el MVP.

------------------------------------------------------------------------

## 8. Android VPN y permisos

Implementar `VpnService` usando APIs oficiales.

Requisitos:

-   Solicitar autorización VPN mediante el flujo oficial.
-   Filtrar exclusivamente `com.whatsapp`.
-   Configurar IPv4 e IPv6 de manera coherente.
-   Definir DNS y encaminar consultas a través del Gateway.
-   Proteger sockets propios con `VpnService.protect()` antes de
    conectarlos.
-   Evitar loops de túnel.
-   Implementar `onRevoke()` y cierre seguro.
-   Mantener notificación y servicio en primer plano cuando corresponda.
-   Cumplir requisitos de permisos y tipos de foreground service según
    la versión Android.
-   No solicitar permisos innecesarios.
-   Gestionar que Android solo permite una VPN activa por perfil de
    usuario.
-   Mostrar al usuario si otra VPN impide iniciar OpenMesh.

El comportamiento debe ser fail-closed cuando el usuario haya activado
OpenMesh: si no existe ruta, no enviar el tráfico de WhatsApp fuera del
túnel. Explicar claramente el estado y permitir desactivar la VPN.

No asumir que `addAllowedApplication()` resuelve por sí solo todas las
cuestiones de DNS, IPv6 o sockets del servicio. Probar fugas de tráfico.

------------------------------------------------------------------------

## 9. Gateway SOCKS5

Implementar un Gateway restringido a conexiones autenticadas de
OpenMesh.

En la primera versión:

-   SOCKS5 `CONNECT` para TCP.
-   No implementar `BIND`.
-   No implementar `UDP ASSOCIATE` todavía.
-   DNS remoto mediante un mecanismo explícito y documentado.
-   Validar destino y dirección resuelta antes de conectar.
-   Bloquear loopback, link-local, redes privadas y direcciones locales
    salvo excepción de laboratorio explícita.
-   Prevenir DNS rebinding.
-   Límites de conexiones por nodo.
-   Límites de ancho de banda configurables.
-   Timeouts de conexión e inactividad.
-   Backpressure y buffers limitados.
-   Cierre bidireccional correcto.
-   Auditoría de metadatos mínima, sin registrar contenido.

No exponer SOCKS5 a toda la LAN ni a Internet. El listener debe estar
ligado al transporte autenticado de OpenMesh.

El Gateway debe requerir consentimiento explícito del propietario del
dispositivo y permitir revocar clientes.

------------------------------------------------------------------------

## 10. DNS, UDP e IPv6

No ignorar estos temas.

### DNS

-   Resolver mediante el Gateway.
-   No permitir que consultas DNS de WhatsApp escapen por la conexión
    local del cliente.
-   Documentar si se utiliza un proxy DNS local, un resolver remoto o
    ambos.
-   Manejar IPv4 e IPv6 y TTL de respuestas.
-   No registrar dominios consultados salvo modo diagnóstico
    expresamente activado.

### UDP

La primera versión puede limitarse a TCP y DNS, pero debe hacerlo de
forma explícita y segura.

-   Bloquear o rechazar UDP no soportado.
-   No fingir soporte UDP.
-   Medir qué funciones de WhatsApp fallan.
-   Diseñar una futura extensión UDP encapsulada y autenticada.
-   No prometer llamadas ni todas las funciones de WhatsApp en MVP 0.1.

### IPv6

-   Probar IPv6 en TUN y Gateway.
-   Si IPv6 no está implementado correctamente, bloquearlo de forma
    explícita para evitar fugas.
-   No crear una configuración dual-stack que parezca funcionar mientras
    filtra tráfico fuera del túnel.

------------------------------------------------------------------------

## 11. Seguridad y privacidad

El sistema transporta tráfico de terceros. La seguridad es requisito de
arquitectura, no una tarea posterior.

Implementar:

-   Identidad por dispositivo mediante claves.
-   Almacenamiento de claves con Android Keystore cuando sea posible.
-   Autenticación mutua de nodos.
-   TLS 1.3 entre vecinos.
-   Cifrado extremo a extremo lógico entre cliente y Gateway, de modo
    que los relays no puedan leer los streams.
-   Nonces, secuencias y protección contra replay.
-   Límites de recursos.
-   Validación estricta de destinos.
-   Revocación de nodos.
-   Consentimiento explícito para compartir Internet.
-   Cuotas y límites de consumo.
-   Botón de desconexión inmediata.
-   Política de logs sin contenido, contactos ni credenciales.

No implementar criptografía propia. No confiar en un nodo solo porque
esté físicamente cerca.

Documentar un modelo de amenazas en `docs/SECURITY.md`.

------------------------------------------------------------------------

## 12. Interfaz de usuario mínima

La interfaz debe ser funcional y honesta. Mostrar:

-   Estado VPN: desconectada, esperando permiso, conectando, conectada,
    sin ruta, error.
-   Rol actual: cliente, relay, gateway.
-   Número de vecinos.
-   Gateway seleccionado.
-   Número de saltos.
-   Calidad estimada del enlace.
-   Datos enviados y recibidos.
-   Estado de permisos.
-   Si WhatsApp está dentro de la VPN.
-   Control para activar/desactivar compartir Internet.
-   Control para permitir WiFi y/o datos móviles como Gateway.
-   Límites de datos.
-   Botón de parada de emergencia.

No mostrar "Internet conectado" si solo se estableció una conexión
local. Separar estado de enlace, estado de ruta y conectividad real al
destino.

------------------------------------------------------------------------

## 13. Plan de implementación por fases

No desarrollar todo simultáneamente. Cada fase debe compilar, tener
pruebas y cumplir sus criterios antes de continuar.

### P0 --- Esqueleto Android y VPN

-   Crear proyecto Gradle.
-   Configurar Kotlin y Android SDK.
-   Crear UI mínima.
-   Implementar permiso VPN.
-   Crear TUN.
-   Permitir únicamente `com.whatsapp`.
-   Verificar que Chrome y otras aplicaciones no entran en la VPN.
-   Implementar estado y logs.

**Aceptación:** VPN se activa y el tráfico queda correctamente limitado
a WhatsApp. No afirmar todavía que hay conectividad.

### P1 --- TUN + tun2socks + Gateway local

-   Elegir e integrar motor tun2socks.
-   Implementar JNI si es necesario.
-   Implementar SOCKS5 local de prueba.
-   Proteger sockets propios con `VpnService.protect()`.
-   Implementar TCP.
-   Implementar DNS de manera controlada.
-   Verificar ausencia de loops y fugas.

**Aceptación:** WhatsApp utiliza el camino TUN → tun2socks → SOCKS5 de
laboratorio. El tráfico de otras aplicaciones queda fuera.

### P2 --- Gateway Android remoto

-   Implementar servidor SOCKS5 restringido.
-   Implementar autenticación.
-   Crear conexión TLS entre A y C.
-   Abrir sockets TCP de salida en C.
-   Implementar transferencia bidireccional y backpressure.
-   Probar con un destino HTTP/HTTPS de laboratorio.

**Aceptación:** A no tiene salida directa a Internet y puede acceder a
un destino de prueba usando la conexión de C.

### P3 --- Relay intermedio

-   Implementar B como relay.
-   Definir stream IDs.
-   Reenviar frames sin interpretar el contenido.
-   Controlar flujo, límites y cierre.
-   Probar A → B → C.
-   Verificar que B no puede leer el contenido de los streams.

**Aceptación:** tráfico real atraviesa B y sale por C.

### P4 --- BLE Discovery

-   Anunciar nodos.
-   Descubrir peers.
-   Negociar conexión autenticada.
-   Gestionar permisos y ciclo de vida.
-   Evitar anuncios que filtren información personal.

**Aceptación:** A y B se descubren sin introducir manualmente
direcciones.

### P5 --- WiFi Direct

-   Implementar negociación P2P.
-   Gestionar cambios de grupo y desconexiones.
-   Probar dispositivos de fabricantes diferentes.
-   Comprobar concurrencia con la conexión de Internet del Gateway.
-   Verificar si un relay puede mantener dos enlaces simultáneos.

**Aceptación:** enlace inalámbrico real estable en el hardware probado.

### P6 --- Routing dinámico

-   Tabla de vecinos.
-   Anuncios autenticados.
-   Coste de ruta.
-   Expiración.
-   Detección de loops.
-   Selección de Gateway.
-   Failover y recuperación.

**Aceptación:** la red selecciona rutas válidas y detecta rutas caídas.

### P7 --- WhatsApp real

-   Enviar y recibir mensajes.
-   Probar adjuntos.
-   Probar notificaciones con pantalla apagada.
-   Medir latencia y estabilidad.
-   Identificar dependencias UDP/QUIC.
-   Documentar funciones que no funcionen.

**Aceptación:** mensajería de WhatsApp demostrada sobre la ruta A → B →
C, sin conexión directa de A.

------------------------------------------------------------------------

## 14. Plan de pruebas

Crear tests unitarios, de integración y de dispositivos reales.

### Pruebas funcionales

  ID         Prueba                  Criterio
  ---------- ----------------------- ----------------------------------
  VPN-001    Solicitud de permiso    Permiso gestionado correctamente
  VPN-002    Crear TUN               Descriptor válido
  VPN-003    Allowlist WhatsApp      Solo paquete objetivo
  VPN-004    Chrome fuera de VPN     Tráfico directo
  VPN-005    Socket protegido        Sin loop
  GW-001     SOCKS5 CONNECT          TCP bidireccional
  GW-002     DNS remoto              Resolución por Gateway
  GW-003     Destino privado         Rechazado
  GW-004     Cliente desconocido     Rechazado
  MESH-001   Cliente a Gateway       Flujo correcto
  MESH-002   Cliente-Relay-Gateway   Multi-hop real
  MESH-003   Relay desconectado      Ruta invalidada
  MESH-004   Gateway desconectado    Estado sin salida
  SEC-001    Frame manipulado        Rechazado
  SEC-002    Replay                  Rechazado
  WA-001     Enviar texto            Entrega comprobada
  WA-002     Recibir texto           Recepción comprobada
  WA-003     Enviar imagen           Resultado documentado
  WA-004     Notificación            Resultado documentado

### Pruebas físicas

Usar al menos tres teléfonos Android reales:

-   A: cliente, sin acceso directo a Internet.
-   B: relay.
-   C: Gateway con Internet.

Separar las pruebas:

1.  Topología lógica en LAN controlada.
2.  Dos enlaces físicos inalámbricos.
3.  Multi-hop real.
4.  Prueba de WhatsApp.
5.  Prueba con pantalla apagada.
6.  Prueba de duración y batería.

No confundir una prueba LAN con una Mesh inalámbrica real.

### Métricas

Medir:

-   Tiempo de descubrimiento.
-   Tiempo de conexión.
-   RTT.
-   Throughput.
-   Pérdida de datos.
-   Reconexiones.
-   Uso de CPU.
-   Consumo de batería.
-   Bytes por nodo.
-   Número de saltos.

Los objetivos numéricos deben establecerse después de obtener una línea
base, no inventarse.

------------------------------------------------------------------------

## 15. Documentación obligatoria

Mantener actualizados:

-   `README.md`: instalación, compilación y ejecución.
-   `docs/ARCHITECTURE.md`: arquitectura real implementada.
-   `docs/PROTOCOL.md`: frames y estados.
-   `docs/SECURITY.md`: amenazas y mitigaciones.
-   `docs/TEST_PLAN.md`: pruebas y resultados.
-   `docs/ADR/`: decisiones técnicas.
-   `CHANGELOG.md`: cambios relevantes.
-   `IMPLEMENTATION_STATUS.md`: estado de cada fase.

No documentar como implementada una función que solo está diseñada.

------------------------------------------------------------------------

## 16. Reglas para los agentes de programación

Si el proyecto se desarrolla con agentes Planner, Developer y Reviewer:

### Planner

-   Descomponer el trabajo por fases.
-   Identificar dependencias y riesgos.
-   No abrir tareas que dependan de interfaces todavía no acordadas.
-   Mantener un backlog verificable.
-   Señalar decisiones que requieren investigación.

### Developer

-   Implementar una tarea cada vez.
-   No sustituir funcionalidades reales por mocks en producción.
-   No inventar APIs ni versiones.
-   Consultar documentación oficial para APIs Android.
-   Compilar y ejecutar tests antes de cerrar una tarea.
-   Mantener cambios acotados.
-   Actualizar documentación y estado.

### Reviewer / Tester

-   Revisar seguridad, concurrencia y ciclo de vida.
-   Comprobar que no hay fugas de tráfico.
-   Verificar allowlist de WhatsApp.
-   Comprobar que el Gateway no es un proxy abierto.
-   Ejecutar pruebas y registrar resultados.
-   Rechazar afirmaciones de funcionamiento sin evidencia reproducible.

### Reglas generales

1.  No avanzar a la siguiente fase si la anterior no cumple sus
    criterios.
2.  No cambiar interfaces públicas sin actualizar documentación y
    consumidores.
3.  No incorporar dependencias sin verificar licencia y mantenimiento.
4.  No introducir permisos Android innecesarios.
5.  No guardar secretos en el repositorio.
6.  No registrar contenido de mensajes ni datos personales.
7.  No utilizar root.
8.  No eludir autenticaciones de redes WiFi.
9.  No modificar ni automatizar WhatsApp.
10. No afirmar compatibilidad total con WhatsApp hasta completar pruebas
    reales.
11. Toda funcionalidad debe tener pruebas.
12. Todo error debe producir un estado visible y recuperable.
13. Si una limitación de Android o del hardware impide una función,
    documentarla y proponer alternativas antes de cambiar la
    arquitectura.

------------------------------------------------------------------------

## 17. Primera tarea que debe ejecutar el equipo

**No implementar toda la red todavía.**

Comenzar con una investigación técnica breve y una prueba de viabilidad
P0/P1.

Entregables iniciales:

1.  Crear el repositorio Gradle multiproyecto.
2.  Verificar toolchain y versiones compatibles.
3.  Investigar tun2socks y seleccionar una opción mantenida.
4.  Implementar la aplicación Android mínima.
5.  Solicitar autorización VPN.
6.  Crear TUN.
7.  Aplicar allowlist a `com.whatsapp`.
8.  Mostrar estado VPN.
9.  Crear tests que demuestren que otras aplicaciones quedan fuera.
10. Documentar limitaciones detectadas.

Después implementar el camino local tun2socks → SOCKS5 y demostrar que
el tráfico de WhatsApp puede atravesarlo.

**No comenzar BLE, WiFi Direct ni routing multi-hop hasta que P0 y P1
estén validados.**

------------------------------------------------------------------------

## Resultado final esperado

Un repositorio Android reproducible que permita ejecutar OpenMesh en
tres teléfonos:

-   A: WhatsApp utiliza exclusivamente la VPN OpenMesh.
-   B: retransmite el stream cifrado.
-   C: proporciona salida TCP a Internet mediante sus propios sockets.
-   A no necesita conexión móvil ni WiFi propia.
-   El tráfico atraviesa A → B → C → Internet.
-   Los nodos están autenticados.
-   No se necesita root.
-   No existe un servidor central de transporte.

El éxito del MVP se define por una prueba real y repetible de envío y
recepción de mensajes de WhatsApp a través de tres dispositivos Android,
con mediciones, logs técnicos sin contenido privado y documentación de
las limitaciones encontradas.

**Prioridad absoluta: demostrar conectividad real antes de ampliar la
red.**
